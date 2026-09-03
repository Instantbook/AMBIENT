package io.github.instantbook.ambient;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
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

    /** Two BACK presses inside this window quit, so the app is never a trap. */
    private static final long EXIT_WINDOW_MS = 2000L;

    private WebView web;
    private long lastBackAt = 0L;

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

        // Keep navigation inside the app; without this an external link would
        // hand off to the cursor-driven browser we are trying to avoid.
        web.setWebViewClient(new WebViewClient());

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
