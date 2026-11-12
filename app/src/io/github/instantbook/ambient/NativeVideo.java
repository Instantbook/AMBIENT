package io.github.instantbook.ambient;

import android.app.Activity;
import android.content.ContentUris;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.VideoView;

/**
 * Android's own player, laid over the WebView, for the videos the page
 * cannot play.
 *
 * WHY THIS EXISTS: a WebView reads an app-served response's size from
 * InputStream.available(), which is an int (InputStreamReader::
 * VerifyRequestedRange in Chromium). Every byte past 2,147,483,647 is
 * therefore unreachable through shouldInterceptRequest, whatever the app
 * does - a range starting there is answered "not satisfiable" and the
 * <video> dies with PIPELINE_ERROR_READ, permanently. Measured on this box:
 * Tomorrowland (2.84 GiB) played from 1:23:42 and died at any seek past
 * ~1:31:37, which is where its 2 GiB mark falls. Nine videos in the library
 * are over that size.
 *
 * Serving from a loopback HTTP server instead was tested and is blocked:
 * WebView 156 refuses an https page's http://127.0.0.1 media before sending
 * a single request. So the page cannot reach those bytes at all, and the
 * player has to live here, reading the content:// URI directly - which has
 * no size limit and also gets the Amlogic hardware decoders, so MKV and AVI
 * that Chromium will not touch play too.
 *
 * The page stays in charge. It keeps focus and receives every key, and
 * drives this through the bridge: play / toggle / seek / stop, plus an OSD
 * line, since nothing the page draws can appear over a SurfaceView.
 */
final class NativeVideo {
    private final Activity act;
    private final FrameLayout layer;
    private final VideoView view;
    private final TextView osd;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /* Written on the UI thread, read by the bridge on a binder thread. */
    private volatile long id = -1;
    private volatile int posMs = 0, durMs = 0;
    private volatile boolean playing = false, prepared = false,
            ended = false, buffering = false;
    private volatile String err = null;

    NativeVideo(Activity a, FrameLayout root) {
        act = a;
        layer = new FrameLayout(a);
        layer.setBackgroundColor(Color.BLACK);
        layer.setVisibility(View.GONE);
        /* Never focusable: the WebView must keep focus or the page stops
           receiving the d-pad, and with it every control - including BACK
           as "stop". VideoView makes itself focusable in its constructor,
           so this has to be undone explicitly. */
        layer.setFocusable(false);

        view = new VideoView(a);
        view.setFocusable(false);
        view.setFocusableInTouchMode(false);
        // MATCH_PARENT both ways + CENTER: VideoView shrinks one side to
        // keep the aspect ratio, and gravity centres what is left.
        layer.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        osd = new TextView(a);
        osd.setTextColor(Color.WHITE);
        osd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        osd.setTypeface(android.graphics.Typeface.MONOSPACE);
        osd.setLetterSpacing(0.12f);
        osd.setShadowLayer(6f, 0f, 0f, Color.BLACK);
        osd.setGravity(Gravity.CENTER);
        osd.setAlpha(0f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = (int) (a.getResources().getDisplayMetrics()
                .heightPixels * 0.08f);
        layer.addView(osd, lp);

        view.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) {
                prepared = true;
                durMs = mp.getDuration();
            }
        });
        view.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer mp) {
                ended = true;
                playing = false;
            }
        });
        /* Returning true matters: false makes VideoView put up its own
           "Can't play this video" dialog, which takes focus away from the
           page and leaves the remote talking to a dialog nobody expected.
           The page shows the error instead. */
        view.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                err = "player error " + what + "/" + extra;
                playing = false;
                return true;
            }
        });
        view.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) buffering = true;
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) buffering = false;
                return false;
            }
        });

        root.addView(layer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** Polls the player for the bridge. Only while something is loaded. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (id < 0) return;
            try {
                /* Before prepare, getCurrentPosition() reports 0. Passing
                   that up would let the page save "0" over the real resume
                   point - and a position under a minute is DELETED, so the
                   film would forget where you were the moment you opened
                   it. Hold the requested start until the player is real. */
                if (prepared) {
                    posMs = view.getCurrentPosition();
                    playing = view.isPlaying();
                    if (durMs <= 0) durMs = view.getDuration();
                }
            } catch (Throwable ignored) {}
            ui.postDelayed(this, 400);
        }
    };

    private final Runnable hideOsd = new Runnable() {
        @Override public void run() { osd.animate().alpha(0f).setDuration(250); }
    };

    void play(final long vid, final double atSec) {
        ui.post(new Runnable() {
            @Override public void run() {
                stopNow();
                id = vid;
                err = null; ended = false; prepared = false; buffering = false;
                posMs = (int) Math.max(0, Math.round(atSec * 1000));
                durMs = 0;
                Uri u = ContentUris.withAppendedId(
                        MediaStore.Video.Media.getContentUri("external"), vid);
                layer.setVisibility(View.VISIBLE);
                view.setVideoURI(u);
                // Both before prepare: VideoView holds the seek and the
                // intent to play, and applies them once it is ready.
                if (posMs > 0) view.seekTo(posMs);
                view.start();
                playing = true;
                ui.removeCallbacks(tick);
                ui.post(tick);
            }
        });
    }

    void cmd(final String c, final double arg) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (id < 0) return;
                try {
                    if ("toggle".equals(c)) {
                        if (view.isPlaying()) { view.pause(); playing = false; }
                        else { view.start(); playing = true; }
                    } else if ("pause".equals(c)) {
                        view.pause(); playing = false;
                    } else if ("play".equals(c)) {
                        view.start(); playing = true;
                    } else if ("seek".equals(c)) {
                        int ms = (int) Math.max(0, Math.round(arg * 1000));
                        if (durMs > 0) ms = Math.min(ms, Math.max(0, durMs - 1000));
                        view.seekTo(ms);
                        posMs = ms;          // so a quick second press stacks
                        ended = false;
                    } else if ("stop".equals(c)) {
                        stopNow();
                    }
                } catch (Throwable ignored) {}
            }
        });
    }

    void osd(final String text, final int ms) {
        ui.post(new Runnable() {
            @Override public void run() {
                osd.setText(text == null ? "" : text);
                osd.animate().cancel();
                osd.setAlpha(1f);
                ui.removeCallbacks(hideOsd);
                ui.postDelayed(hideOsd, ms > 0 ? ms : 2000);
            }
        });
    }

    String state() {
        String e = err;
        return "{\"id\":" + id
                + ",\"t\":" + (posMs / 1000.0)
                + ",\"dur\":" + (durMs / 1000.0)
                + ",\"playing\":" + playing
                + ",\"prepared\":" + prepared
                + ",\"buffering\":" + buffering
                + ",\"ended\":" + ended
                + ",\"err\":" + (e == null ? "null"
                        : "\"" + e.replace("\"", "'") + "\"")
                + "}";
    }

    boolean active() { return id >= 0; }

    /** UI thread only. */
    void stopNow() {
        ui.removeCallbacks(tick);
        ui.removeCallbacks(hideOsd);
        try { view.stopPlayback(); } catch (Throwable ignored) {}
        osd.setAlpha(0f);
        layer.setVisibility(View.GONE);
        id = -1;
        playing = false; prepared = false; buffering = false;
    }

    /* Leaving the app destroys the SurfaceView's surface, and VideoView
       releases its player with it - so coming back would otherwise find a
       black rectangle at time zero. Remember the spot and come back paused
       there: resuming with sound the instant the app reappears is a
       surprise, while OK to continue is not. */
    void onPause() {
        if (id < 0) return;
        try {
            if (prepared) posMs = view.getCurrentPosition();
        } catch (Throwable ignored) {}
        playing = false;
        prepared = false;
    }

    void onResume() {
        if (id < 0) return;
        try { view.seekTo(posMs); } catch (Throwable ignored) {}
        ui.removeCallbacks(tick);
        ui.post(tick);
    }
}
