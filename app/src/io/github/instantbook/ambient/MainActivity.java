package io.github.instantbook.ambient;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
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
     * Deliberately NOT attached while an external site is loaded - the
     * LAUNCH card navigates anywhere, and a JavascriptInterface is exposed
     * to whatever page happens to be open. See shouldOverrideUrlLoading /
     * onPageStarted below.
     */
    public class Host {
        private long lastCpuMs = 0L, lastWallMs = 0L;

        @JavascriptInterface
        public String worker() { return WORKER_URL; }

        @JavascriptInterface
        public String stats() {
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

    private final Host host = new Host();
    private boolean hostAttached = false;

    private void attachHost(boolean on) {
        if (on == hostAttached || web == null) return;
        if (on) web.addJavascriptInterface(host, "AmbientHost");
        else web.removeJavascriptInterface("AmbientHost");
        hostAttached = on;
    }

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
                attachHost(url != null && url.startsWith(URL));
                super.onPageStarted(v, url, f);
            }
        });
        attachHost(true);

        setContentView(web);
        web.requestFocus();

        if (state == null) {
            web.loadUrl(URL);
        } else {
            web.restoreState(state);
        }
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

    /**
     * BACK is a navigation key inside AMBIENT (leave fullscreen, open the
     * overview), but Android delivers it to the Activity rather than to the
     * page. Forward it as an Escape keydown, which the shell's router
     * already maps to "back". Two presses in quick succession still quit.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            long now = System.currentTimeMillis();
            if (now - lastBackAt < EXIT_WINDOW_MS) {
                finish();
                return true;
            }
            lastBackAt = now;
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
