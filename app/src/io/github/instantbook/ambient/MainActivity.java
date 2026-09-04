package io.github.instantbook.ambient;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceResponse;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import android.webkit.WebResourceRequest;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * A deliberately thin WebView host for AMBIENT.
 *
 * Why this exists: the Pocket TV's stock browser (com.tcl.browser) drives
 * pages with a virtual mouse cursor and never forwards D-pad presses to the
 * page, so none of AMBIENT's keyboard navigation was reachable on the
 * device. That browser exposes no settings activity, so the cursor cannot
 * be turned off. A WebView delivers DPAD_* to the page as ordinary
 * ArrowLeft/ArrowRight/Enter key events, which is exactly what the cockpit
 * shell already listens for.
 *
 * Everything else stays on GitHub Pages - this is a shell, not a fork, so
 * `git push` remains the whole deploy process and this APK should rarely
 * need reinstalling.
 */
public class MainActivity extends Activity {

    private static final String URL = "https://instantbook.github.io/AMBIENT/";

    /** Baked in at build time from app/worker.url, which is gitignored, so
     *  the endpoint never lands in the public repo. Empty when unset. */
    private static final String WORKER_URL = BuildLocal.WORKER;

    /** Two BACK presses inside this window quit, so the app is never a trap. */
    private static final long EXIT_WINDOW_MS = 2000L;

    private WebView web;
    private long lastBackAt = 0L;

    /**
     * Bridge for the things a web page cannot see: the device's memory and
     * this process's CPU time, plus the Worker URL so a wiped localStorage
     * does not mean retyping it on a TV keyboard.
     *
     * The interface stays attached but REFUSES TO ANSWER off-site. Adding
     * and removing it per navigation looked tidier but does not work:
     * Android only exposes an interface from the next page load, so
     * re-adding it in onPageStarted was too late for the load in progress
     * and the card came back from a launched site reading "host only".
     * A volatile flag set on the UI thread is both correct and thread-safe,
     * since these methods run on a binder thread where touching the WebView
     * would not be.
     */
    public class Host {
        private long lastCpuMs = 0L, lastWallMs = 0L;

        @JavascriptInterface
        public String worker() { return atAmbient ? WORKER_URL : ""; }

        /** [{name,dir,path,size}] - dir is the album folder, for grouping. */
        @JavascriptInterface
        public String listMedia() {
            if (!atAmbient) return "[]";
            java.util.List<File> found = new java.util.ArrayList<File>();
            for (File r : mediaRoots()) scan(r, 0, found);
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (File f : found) {
                if (!first) b.append(",");
                first = false;
                String parent = f.getParentFile() != null
                        ? f.getParentFile().getName() : "";
                b.append("{\"name\":\"").append(jesc(f.getName()))
                 .append("\",\"dir\":\"").append(jesc(parent))
                 .append("\",\"path\":\"").append(jesc(f.getAbsolutePath()))
                 .append("\",\"size\":").append(f.length()).append("}");
            }
            return b.append("]").toString();
        }

        @JavascriptInterface
        public String mediaBase() { return atAmbient ? MEDIA_HOST : ""; }

        @JavascriptInterface
        public String configPath() {
            return atAmbient ? configFile().getAbsolutePath() : "";
        }

        /** "" when there is no file yet - the page then keeps its defaults. */
        @JavascriptInterface
        public String readConfig() {
            if (!atAmbient) return "";
            RandomAccessFile f = null;
            try {
                File c = configFile();
                if (!c.exists() || c.length() > 1 << 20) return "";
                f = new RandomAccessFile(c, "r");
                byte[] b = new byte[(int) c.length()];
                f.readFully(b);
                return new String(b, "UTF-8");
            } catch (Throwable t) {
                return "";
            } finally {
                try { if (f != null) f.close(); } catch (Throwable ignored) {}
            }
        }

        @JavascriptInterface
        public boolean writeConfig(String json) {
            if (!atAmbient || json == null) return false;
            byte[] bytes;
            try { bytes = json.getBytes("UTF-8"); }
            catch (Throwable t) { return false; }
            for (File dir : configDirs()) {
                FileOutputStream o = null;
                try {
                    if (!dir.exists() && !dir.mkdirs()) continue;
                    File c = new File(dir, "config.json");
                    o = new FileOutputStream(c);
                    o.write(bytes);
                    cachedConfig = c;      // remember what actually worked
                    return true;
                } catch (Throwable t) {
                    // not writable after all - try the next candidate
                } finally {
                    try { if (o != null) o.close(); } catch (Throwable ignored) {}
                }
            }
            return false;
        }

        @JavascriptInterface
        public String stats() {
            if (!atAmbient) return "{}";   // youtube.com does not get this
            long totalKb = 0, availKb = 0;
            try {
                ActivityManager am = (ActivityManager)
                        getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                totalKb = mi.totalMem / 1024;
                availKb = mi.availMem / 1024;
            } catch (Throwable t) { /* report what we have */ }

            // Per-process CPU as a share of one core, from the delta between
            // calls. System-wide /proc/stat is not readable to an ordinary
            // app on modern Android, so this is honestly OUR cpu, not the
            // box's - the card labels it that way.
            double cpu = -1;
            try {
                long cpuMs = Process.getElapsedCpuTime();
                long wallMs = SystemClock.elapsedRealtime();
                if (lastWallMs > 0 && wallMs > lastWallMs) {
                    cpu = 100.0 * (cpuMs - lastCpuMs) / (wallMs - lastWallMs);
                }
                lastCpuMs = cpuMs;
                lastWallMs = wallMs;
            } catch (Throwable t) { /* leave cpu at -1 = unknown */ }

            return "{\"totalKb\":" + totalKb
                 + ",\"availKb\":" + availKb
                 + ",\"cpu\":" + String.format(java.util.Locale.US, "%.1f", cpu)
                 + ",\"cores\":" + Runtime.getRuntime().availableProcessors()
                 + ",\"model\":\"" + Build.MODEL.replace("\"", "") + "\"}";
        }
    }

    /**
     * Where settings actually live.
     *
     * localStorage is the wrong home for anything the user typed: `pm clear`
     * wipes it, uninstalling wipes it, and it cannot be read or edited from
     * a computer. This writes a plain JSON file to the microSD instead -
     * /AMBIENT/config.json, alongside the media folders - so bookmarks and
     * preferences survive a wipe and can be edited over MTP.
     *
     * Falls back to the app's own external directory when All-files access
     * has not been granted. That still works, but it is inside
     * Android/data and IS cleared with the app, so the card reports which
     * of the two is in use rather than quietly degrading.
     */
    private File cachedConfig = null;

    /**
     * Candidate homes, best first. Testing canWrite() on a volume ROOT said
     * the microSD was unwritable and sent everything to internal storage,
     * even though the AMBIENT directory on the card may well be writable -
     * so this probes the actual directory it would use, and the caller
     * falls through on a real failure rather than a predicted one.
     */
    private File[] configDirs() {
        java.util.List<File> out = new java.util.ArrayList<File>();
        boolean allFiles = Build.VERSION.SDK_INT < 30
                || Environment.isExternalStorageManager();
        if (allFiles) {
            File[] vols = new File("/storage").listFiles();
            if (vols != null) {
                for (File v : vols) {
                    String n = v.getName();
                    // the removable card first: it survives a factory reset
                    // and can be pulled and read in any computer
                    if ("emulated".equals(n) || "self".equals(n)) continue;
                    if (v.isDirectory()) out.add(new File(v, "AMBIENT"));
                }
            }
            out.add(new File("/storage/emulated/0/AMBIENT"));
        }
        File priv = getExternalFilesDir(null);
        if (priv != null) out.add(priv);   // always works; cleared with the app
        return out.toArray(new File[0]);
    }

    private File configFile() {
        if (cachedConfig != null) return cachedConfig;
        File first = null;
        for (File d : configDirs()) {
            File f = new File(d, "config.json");
            if (first == null) first = f;
            if (f.exists()) { cachedConfig = f; return f; }   // keep using it
        }
        return first != null ? first
                : new File(getFilesDir(), "config.json");
    }

    /* ---------------- local media ----------------
       An https page cannot load file:// URLs, and routing local audio over a
       plain-http helper would be blocked as mixed content. So the app serves
       files under an https URL it intercepts itself: the page asks for
       https://ambient.local/media?p=..., shouldInterceptRequest reads the
       file, and because the response carries CORS headers the audio is
       same-scheme AND analysable - local tracks drive the real FFT exactly
       like the CORS-clean radio streams do. */
    private static final String MEDIA_HOST = "https://ambient.local/media";

    private File[] mediaRoots() {
        java.util.List<File> out = new java.util.ArrayList<File>();
        File[] vols = new File("/storage").listFiles();
        if (vols != null) {
            for (File v : vols) {
                if ("self".equals(v.getName())) continue;
                out.add(new File(v, "Music"));
                out.add(new File(v, "AMBIENT/music"));
            }
        }
        out.add(new File("/storage/emulated/0/Music"));
        out.add(new File("/storage/emulated/0/AMBIENT/music"));
        return out.toArray(new File[0]);
    }

    private static boolean isAudio(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac")
            || n.endsWith(".flac") || n.endsWith(".ogg") || n.endsWith(".oga")
            || n.endsWith(".wav") || n.endsWith(".opus");
    }

    private static String mimeFor(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a") || n.endsWith(".aac")) return "audio/mp4";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".ogg") || n.endsWith(".oga") || n.endsWith(".opus"))
            return "audio/ogg";
        if (n.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }

    /** Depth 5: tracks on this device sit at Music/<artist>/<album>/file. */
    private void scan(File dir, int depth, java.util.List<File> out) {
        if (depth > 5 || out.size() > 400 || dir == null || !dir.isDirectory())
            return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (out.size() > 400) return;
            String n = k.getName();
            if (n.startsWith(".")) continue;      // .thumbnails and friends
            if (k.isDirectory()) scan(k, depth + 1, out);
            else if (isAudio(n)) out.add(k);
        }
    }

    /** Only ever serve from inside a known media root - the page asks for
     *  arbitrary paths, and this is what stops it reading the filesystem. */
    private boolean underMediaRoot(File f) {
        try {
            String c = f.getCanonicalPath();
            for (File r : mediaRoots()) {
                if (c.startsWith(r.getCanonicalPath() + File.separator))
                    return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private WebResourceResponse serveMedia(String url, String rangeHeader) {
        try {
            int q = url.indexOf("?p=");
            if (q < 0) return null;
            File f = new File(URLDecoder.decode(url.substring(q + 3), "UTF-8"));
            if (!f.isFile() || !underMediaRoot(f)) return null;

            Map<String, String> h = new HashMap<String, String>();
            h.put("Access-Control-Allow-Origin", "*");   // makes the FFT work
            h.put("Accept-Ranges", "bytes");
            long len = f.length(), start = 0, end = len - 1;

            // Chromium range-requests media; without 206 support seeking
            // fails and some containers refuse to start at all.
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-");
                try {
                    if (parts.length > 0 && parts[0].length() > 0)
                        start = Long.parseLong(parts[0].trim());
                    if (parts.length > 1 && parts[1].length() > 0)
                        end = Long.parseLong(parts[1].trim());
                } catch (Throwable ignored) {}
                if (start < 0 || start >= len) start = 0;
                if (end < start || end >= len) end = len - 1;
                InputStream in = new FileInputStream(f);
                long skipped = 0;
                while (skipped < start) {
                    long n = in.skip(start - skipped);
                    if (n <= 0) break;
                    skipped += n;
                }
                h.put("Content-Range", "bytes " + start + "-" + end + "/" + len);
                h.put("Content-Length", String.valueOf(end - start + 1));
                return new WebResourceResponse(mimeFor(f.getName()), null,
                        206, "Partial Content", h, in);
            }
            h.put("Content-Length", String.valueOf(len));
            return new WebResourceResponse(mimeFor(f.getName()), null,
                    200, "OK", h, new FileInputStream(f));
        } catch (Throwable t) {
            return new WebResourceResponse("text/plain", "UTF-8", 404,
                    "Not Found", new HashMap<String, String>(),
                    new ByteArrayInputStream(new byte[0]));
        }
    }

    private static String jesc(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    private final Host host = new Host();
    /** Written on the UI thread in onPageStarted, read from binder threads. */
    private volatile boolean atAmbient = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // A cockpit should not sleep. This also lets us restore the device's
        // own screensaver settings rather than leaving them disabled.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage: themes, scenes, bookmarks
        s.setMediaPlaybackRequiresUserGesture(false);  // radio starts on OK, not a tap
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        // The page is served over HTTPS, but most public radio streams are
        // still plain http - 7 of the 8 top Greek stations on
        // radio-browser.info, for instance. Chromium blocks mixed-content
        // media, so those stations failed silently while the https presets
        // played. COMPATIBILITY_MODE re-allows http *media and images* while
        // still blocking http scripts and stylesheets, which is the specific
        // trade this needs - not ALWAYS_ALLOW, which would also let an
        // http script run inside the page's origin.
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // Keep navigation inside the app; without this an external link would
        // hand off to the cursor-driven browser we are trying to avoid.
        // Also gates the JS bridge: LAUNCH navigates to arbitrary sites, and
        // any page that is loaded can call whatever interface is attached.
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
                atAmbient = url == null || url.startsWith(URL);
                super.onPageStarted(v, url, f);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView v, WebResourceRequest req) {
                String u = req.getUrl() != null ? req.getUrl().toString() : "";
                if (atAmbient && u.startsWith(MEDIA_HOST)) {
                    String range = null;
                    try {
                        Map<String, String> hs = req.getRequestHeaders();
                        if (hs != null) range = hs.get("Range");
                    } catch (Throwable ignored) {}
                    return serveMedia(u, range);
                }
                return super.shouldInterceptRequest(v, req);
            }
        });
        web.addJavascriptInterface(host, "AmbientHost");

        // Logins on sites opened from LAUNCH. Persistent cookies and site
        // localStorage already survive a restart, but two defaults get in the
        // way: WebView blocks THIRD-PARTY cookies by default, which breaks
        // most "sign in with..." redirect flows, and cookies are only written
        // to disk on a clean shutdown, so a force-stop loses a fresh session.
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(web, true);
        } catch (Throwable ignored) {}

        setContentView(web);
        web.requestFocus();

        if (state == null) {
            web.loadUrl(URL);
        } else {
            web.restoreState(state);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // flush now rather than trusting a clean exit - a TV app is usually
        // killed, not closed, and an unflushed login is a lost login
        try { CookieManager.getInstance().flush(); } catch (Throwable ignored) {}
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    /** Hide the system bars so the bezel really does reach the screen edge. */
    private void goImmersive() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** True while the WebView is showing AMBIENT rather than a launched site. */
    private boolean atHome() {
        String u = web != null ? web.getUrl() : null;
        return u == null || u.startsWith(URL);
    }

    /**
     * BACK means two different things depending on where you are, and
     * getting that wrong stranded people.
     *
     * On AMBIENT it is a navigation key - leave fullscreen, open the
     * overview - but Android delivers it to the Activity, not the page, so
     * it is forwarded as an Escape keydown. Two presses quit.
     *
     * On a site opened by the LAUNCH card it has to behave like a browser.
     * Overriding onKeyDown had shadowed onBackPressed entirely, so BACK sent
     * an Escape that Wikipedia ignored and a second press quit the app:
     * there was no way back to the dashboard at all. Now it walks history,
     * falls back to AMBIENT when history runs out, and a double press jumps
     * straight home however deep you have browsed. Quitting is only possible
     * from AMBIENT itself, so a launched site can never be a dead end.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long now = System.currentTimeMillis();
            boolean quick = now - lastBackAt < EXIT_WINDOW_MS;
            lastBackAt = now;

            if (!atHome()) {
                if (quick) {                 // impatient: go straight home
                    web.loadUrl(URL);
                } else if (web.canGoBack()) {
                    web.goBack();
                    Toast.makeText(this, "Press BACK again for AMBIENT",
                            Toast.LENGTH_SHORT).show();
                } else {
                    web.loadUrl(URL);
                }
                return true;
            }

            if (quick) {
                finish();
                return true;
            }
            web.evaluateJavascript(
                    "window.dispatchEvent(new KeyboardEvent('keydown',"
                            + "{key:'Escape',bubbles:true}));", null);
            Toast.makeText(this, "Press BACK again to exit AMBIENT",
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        // Everything else - DPAD_LEFT/RIGHT/UP/DOWN, DPAD_CENTER - falls
        // through to the WebView, which turns it into the arrow and Enter
        // key events the cockpit shell listens for.
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
