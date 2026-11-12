package io.github.instantbook.ambient;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.http.SslError;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Getting through a captive portal - a plane's or a hotel's "sign in to
 * use this Wi-Fi" page - on a box whose stock answer is unusable.
 *
 * Android hands the portal to com.android.captiveportallogin, a touch-only
 * WebView with no d-pad navigation. AMBIENT's own WebView already has
 * spatial navigation and the air-mouse cursor for outside pages, so the
 * fix is to find the portal ourselves and open it there.
 *
 * The second, hidden half: THE CLOCK. The box has no RTC battery and boots
 * believing it is 2024-05-22 (ro.build.date.utc - SystemServer clamps to the
 * build date). Only NTP corrects it, and a captive portal blocks NTP until
 * you have signed in. So on a plane every https page - including the sign-in
 * page itself - fails certificate validation as "not yet valid", and the
 * portal can never be passed. A circular dependency, invisible from the UI.
 *
 * The way out is to check the certificate properly, but against the RIGHT
 * time. Only when (a) the system clock is provably wrong - earlier than
 * this APK was built - and (b) the WebView's only complaint is the date, the
 * chain is fetched and validated in full (trust anchors, signatures, host
 * name, same leaf the WebView saw) at the time the network itself reports
 * in its HTTP Date header. Anything else is refused exactly as before.
 * Once NTP lands the clock is no longer behind and none of this runs.
 *
 * The residual risk, stated plainly: the Date header is unauthenticated, so
 * a hostile network could pick the time - but only forward of the APK's
 * build date, which could at most revive a certificate that expired since
 * then, and only while this box's clock is wrong. Against a sign-in page
 * that otherwise cannot load at all, that is the right trade.
 */
final class Net {
    private static final String TAG = "Ambient";
    static final String PROBE = "http://connectivitycheck.gstatic.com/generate_204";
    /** Purpose-built for exactly this: a plain-http page a portal can hijack. */
    static final String FALLBACK = "http://neverssl.com/";

    private final Activity act;
    private final ConnectivityManager cm;
    private final WifiManager wm;
    private final long floor;
    private ConnectivityManager.NetworkCallback cb;

    volatile Network wifi;
    volatile NetworkCapabilities caps;
    volatile String probe = "unknown";      // unknown|probing|online|portal|offline
    volatile String portal;                  // where the portal sent us
    volatile int probeCode = -1;
    volatile long probeAt = 0;               // elapsedRealtime
    volatile long netTimeMs = 0, netTimeAt = 0;
    volatile boolean bound = false;
    private volatile boolean probing = false;
    /** android.net.CaptivePortal from the system's sign-in intent, if any. */
    private volatile Object captive;
    private volatile Network captiveNet;

    Net(Activity a) {
        act = a;
        cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
        wm = (WifiManager) a.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        long f = BuildLocal.BUILT;
        try {
            android.content.pm.PackageInfo pi = a.getPackageManager()
                    .getPackageInfo(a.getPackageName(), 0);
            f = Math.max(f, pi.lastUpdateTime);
        } catch (Throwable ignored) {}
        floor = f;
        watch();
    }

    /* ---- watching the Wi-Fi ---- */

    private void watch() {
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            cb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) {
                    wifi = n;
                    probe();
                }
                @Override public void onCapabilitiesChanged(Network n,
                        NetworkCapabilities c) {
                    boolean was = caps != null && caps.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    wifi = n;
                    caps = c;
                    boolean now = c.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    // Android just changed its mind about the internet:
                    // re-check rather than show a stale answer.
                    if (was != now) probe();
                    if (now) unbind();
                }
                @Override public void onLost(Network n) {
                    if (n.equals(wifi)) {
                        wifi = null; caps = null;
                        probe = "offline"; portal = null;
                    }
                    // A process bound to a network that has gone has no
                    // network at all - release it immediately.
                    if (bound) unbind();
                }
            };
            cm.registerNetworkCallback(req, cb);
        } catch (Throwable t) {
            Log.w(TAG, "wifi watch failed: " + t);
        }
    }

    void release() {
        try { if (cb != null) cm.unregisterNetworkCallback(cb); }
        catch (Throwable ignored) {}
        unbind();
    }

    /* ---- the probe ---- */

    /** Ask the network the question Android asks: does a known URL that
     *  returns 204 come back as 204? A redirect is the portal announcing
     *  itself; its Location is the sign-in page. Asynchronous. */
    void probe() {
        if (probing) return;
        new Thread(new Runnable() {
            @Override public void run() { probeNow(); }
        }, "ambient-probe").start();
    }

    /** Blocking. Never on the UI thread. */
    void probeNow() {
        if (probing) return;
        probing = true;
        probe = "probing";
        String state = "offline", where = null;
        int code = -1;
        HttpURLConnection c = null;
        try {
            URL u = new URL(PROBE);
            Network n = wifi;
            c = (HttpURLConnection) (n != null ? n.openConnection(u)
                    : u.openConnection());
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(7000);
            c.setReadTimeout(7000);
            c.setUseCaches(false);
            try {
                c.setRequestProperty("User-Agent",
                        WebSettings.getDefaultUserAgent(act));
            } catch (Throwable ignored) {}
            code = c.getResponseCode();
            /* The Date header is the only clock on offer while NTP is
               blocked, and it is what the certificate check below uses. */
            long d = c.getHeaderFieldDate("Date", 0);
            if (d > 0) { netTimeMs = d; netTimeAt = SystemClock.elapsedRealtime(); }
            if (code == 204) {
                state = "online";
            } else if (code == 200 && c.getContentLength() == 0) {
                state = "online";    // some proxies turn 204 into an empty 200
            } else if (code >= 300 && code < 400) {
                state = "portal";
                String loc = c.getHeaderField("Location");
                if (loc != null) where = new URL(u, loc).toString();
            } else {
                // 200 with a page, 511, ...: the portal answered in place
                // of Google, so the probe URL itself shows the portal.
                state = "portal";
                where = PROBE;
            }
        } catch (Throwable t) {
            state = "offline";
        } finally {
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
        }
        probeCode = code;
        probeAt = SystemClock.elapsedRealtime();
        portal = safeUrl(where);
        probe = state;
        probing = false;
        if ("online".equals(state)) signedIn();
        Log.i(TAG, "probe " + state + " code=" + code + " -> " + portal);
    }

    /** We got through. Tell Android, so it re-validates now rather than at
     *  its next scheduled check - which is also what lets NTP fix the
     *  clock - and so it drops its own sign-in notification. */
    private void signedIn() {
        Network n = wifi;
        NetworkCapabilities c = caps;
        boolean validated = c != null
                && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        try { if (n != null && !validated) cm.reportNetworkConnectivity(n, true); }
        catch (Throwable ignored) {}
        Object cp = captive;
        if (cp != null && Build.VERSION.SDK_INT >= 23) {
            try { ((android.net.CaptivePortal) cp).reportCaptivePortalDismissed(); }
            catch (Throwable ignored) {}
            captive = null;
        }
        unbind();
    }

    /** Only http(s). The Location header and the system's extra are both
     *  chosen by the network, and loadUrl("javascript:...") would run in
     *  whatever page is loaded - which may be AMBIENT, with the bridge. */
    static String safeUrl(String u) {
        if (u == null) return null;
        String l = u.trim().toLowerCase(java.util.Locale.ROOT);
        return (l.startsWith("http://") || l.startsWith("https://")) ? u.trim() : null;
    }

    /** Where SIGN IN should go: the system's portal URL, then ours, then a
     *  page whose only job is to be hijackable by a portal. */
    String target(String fromSystem) {
        String s = safeUrl(fromSystem);
        if (s != null) return s;
        s = portal;
        if (s != null) return s;
        return FALLBACK;
    }

    /* ---- the system's own sign-in intent ---- */

    static boolean isCaptiveIntent(Intent i) {
        return i != null
                && ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN.equals(i.getAction());
    }

    /** Returns the portal URL to open. */
    String takeCaptive(Intent i) {
        try {
            captiveNet = i.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK);
            if (captiveNet != null) wifi = captiveNet;
            captive = i.getParcelableExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL);
        } catch (Throwable ignored) {}
        String u = null;
        try {
            if (Build.VERSION.SDK_INT >= 23)
                u = i.getStringExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL_URL);
        } catch (Throwable ignored) {}
        probe();
        return target(u);
    }

    /** CaptivePortalLogin binds itself to the portal's network, because an
     *  unvalidated network is not always the default one. Do the same, but
     *  only when it is needed, and let go the moment it is not. */
    void bindIfNeeded() {
        Network n = wifi;
        if (n == null) return;
        try {
            if (n.equals(cm.getActiveNetwork())) return;
            bound = cm.bindProcessToNetwork(n);
        } catch (Throwable ignored) {}
    }

    void unbind() {
        if (!bound) return;
        try { cm.bindProcessToNetwork(null); } catch (Throwable ignored) {}
        bound = false;
    }

    /* ---- status for the WI-FI card ---- */

    private String ssid() {
        try {
            WifiInfo wi = wm.getConnectionInfo();
            String s = wi == null ? null : wi.getSSID();
            if (s == null || s.contains("unknown ssid")) return "";
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2)
                s = s.substring(1, s.length() - 1);
            return s;
        } catch (Throwable t) { return ""; }
    }

    boolean clockBehind() {
        return System.currentTimeMillis() < floor - 60_000L;
    }

    /** The best available idea of what time it really is. */
    long trustedNow() {
        long sys = System.currentTimeMillis();
        if (sys >= floor - 60_000L) return sys;
        long nt = netTimeMs;
        if (nt > 0) return Math.max(floor,
                nt + (SystemClock.elapsedRealtime() - netTimeAt));
        return floor;
    }

    String status() {
        NetworkCapabilities c = caps;
        Network n = wifi;
        boolean validated = c != null
                && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        boolean cap = c != null
                && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        String p = portal;
        long age = probeAt > 0 ? SystemClock.elapsedRealtime() - probeAt : -1;
        return "{\"wifi\":" + (n != null)
                + ",\"ssid\":\"" + esc(n != null ? ssid() : "") + "\""
                + ",\"validated\":" + validated
                + ",\"captive\":" + cap
                + ",\"probe\":\"" + probe + "\""
                + ",\"code\":" + probeCode
                + ",\"age\":" + age
                + ",\"portal\":" + (p == null ? "null" : "\"" + esc(p) + "\"")
                + ",\"clockBehind\":" + clockBehind()
                + ",\"sys\":" + System.currentTimeMillis()
                + ",\"trusted\":" + trustedNow()
                + ",\"netTime\":" + (netTimeMs > 0)
                + ",\"bound\":" + bound
                + "}";
    }

    private static String esc(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /* ---- certificates, checked against the real time ---- */

    void onSslError(final SslErrorHandler h, final SslError e) {
        final int p = e.getPrimaryError();
        final boolean dateOnly = (p == SslError.SSL_NOTYETVALID
                || p == SslError.SSL_EXPIRED || p == SslError.SSL_DATE_INVALID)
                && !e.hasError(SslError.SSL_UNTRUSTED)
                && !e.hasError(SslError.SSL_IDMISMATCH)
                && !e.hasError(SslError.SSL_INVALID);
        final String host = hostOf(e.getUrl());
        if (!dateOnly || !clockBehind()) {
            Log.w(TAG, "ssl refused " + host + " primary=" + p);
            h.cancel();
            toast("Secure page refused: " + host);
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = false;
                String why = "";
                try {
                    // Want a Date header. A probe already in flight will
                    // bring one, so wait for it rather than skip it.
                    for (int i = 0; probing && i < 50; i++) SystemClock.sleep(200);
                    if (netTimeMs <= 0) probeNow();
                    ok = verifyAt(e, new Date(trustedNow()));
                } catch (Throwable t) {
                    why = t.toString();
                }
                final boolean fok = ok;
                final String fwhy = why;
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (fok) {
                            Log.i(TAG, "ssl " + host + " valid at network time");
                            h.proceed();
                        } else {
                            Log.w(TAG, "ssl " + host + " failed at network time: " + fwhy);
                            h.cancel();
                            toast("Secure page refused: " + host);
                        }
                    }
                });
            }
        }, "ambient-ssl").start();
    }

    private void toast(final String m) {
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(act, m, Toast.LENGTH_LONG).show(); }
                catch (Throwable ignored) {}
            }
        });
    }

    private static String hostOf(String url) {
        try { return Uri.parse(url).getHost(); } catch (Throwable t) { return "?"; }
    }

    private static Set<TrustAnchor> anchors;
    private static Set<String> anchorEnc;

    private static synchronized void loadAnchors() throws Exception {
        if (anchors != null) return;
        KeyStore ks = KeyStore.getInstance("AndroidCAStore");
        ks.load(null, null);
        Set<TrustAnchor> a = new HashSet<TrustAnchor>();
        Set<String> enc = new HashSet<String>();
        Enumeration<String> al = ks.aliases();
        while (al.hasMoreElements()) {
            Certificate c = ks.getCertificate(al.nextElement());
            if (c instanceof X509Certificate) {
                a.add(new TrustAnchor((X509Certificate) c, null));
                enc.add(Base64.encodeToString(c.getEncoded(), Base64.NO_WRAP));
            }
        }
        anchors = a;
        anchorEnc = enc;
    }

    /** Full validation of the server's chain at `when`. Throws or returns
     *  false on anything short of a clean pass. */
    private boolean verifyAt(SslError e, Date when) throws Exception {
        if (Build.VERSION.SDK_INT < 29) return false;
        X509Certificate seen = e.getCertificate() == null ? null
                : e.getCertificate().getX509Certificate();
        if (seen == null) return false;
        Uri u = Uri.parse(e.getUrl());
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : 443;
        if (host == null) return false;

        /* Capture the chain the server presents. This trust manager accepts
           anything ONLY so the handshake completes and the chain can be read;
           nothing is sent over the socket, and the verdict comes from the
           validation below, not from here. */
        final X509Certificate[][] got = new X509Certificate[1][];
        X509TrustManager capture = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a)
                    throws java.security.cert.CertificateException {
                throw new java.security.cert.CertificateException("client");
            }
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {
                got[0] = c;
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{capture}, null);
        Socket raw = new Socket();
        SSLSocket s = null;
        try {
            raw.connect(new InetSocketAddress(host, port), 8000);
            raw.setSoTimeout(8000);
            // host passed here so SNI is sent - without it a shared host
            // presents its default certificate rather than this site's
            s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
            s.startHandshake();
            SSLSession ss = s.getSession();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ss))
                return false;
        } finally {
            try { if (s != null) s.close(); else raw.close(); }
            catch (Throwable ignored) {}
        }
        X509Certificate[] chain = got[0];
        if (chain == null || chain.length == 0) return false;
        // The same certificate the WebView was shown, not merely a valid one.
        if (!Arrays.equals(chain[0].getEncoded(), seen.getEncoded())) return false;

        loadAnchors();
        List<X509Certificate> path = new ArrayList<X509Certificate>();
        for (X509Certificate c : chain) {
            if (anchorEnc.contains(Base64.encodeToString(c.getEncoded(), Base64.NO_WRAP)))
                break;                          // the anchor itself ends the path
            if (!path.isEmpty() && c.getSubjectX500Principal()
                    .equals(c.getIssuerX500Principal()))
                break;                          // a self-signed root someone sent
            path.add(c);
        }
        /* Try the path, then shorter prefixes of it. A server may send an
           extra cross-signed intermediate that chains to a retired root;
           the shorter path ending at the current one is the valid reading. */
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        CertPathValidator v = CertPathValidator.getInstance("PKIX");
        Exception last = null;
        for (int k = path.size(); k >= 1; k--) {
            try {
                CertPath cp = cf.generateCertPath(path.subList(0, k));
                PKIXParameters pp = new PKIXParameters(anchors);
                pp.setRevocationEnabled(false);
                pp.setDate(when);
                v.validate(cp, pp);
                return true;
            } catch (Exception ex) {
                last = ex;
            }
        }
        if (last != null) throw last;
        return false;
    }
}
