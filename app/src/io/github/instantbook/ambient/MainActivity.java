package io.github.instantbook.ambient;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.DownloadManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.os.SystemClock;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
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

    /** A quick second BACK returns to AMBIENT from a launched site. It is
     *  deliberately NOT an exit gesture any more - see onKeyDown. */
    private static final long EXIT_WINDOW_MS = 2000L;

    private WebView web;
    private long lastBackAt = 0L;
    private boolean backWasLong = false;

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

        /**
         * Panel backlight, 0..1. A web page has no brightness API at all, so
         * dimming from JS can only darken what is drawn - which helps on an
         * OLED but still runs the panel at full power. This lowers the panel
         * itself, which is where the battery actually goes when the glasses
         * are being used to listen rather than to watch.
         *
         * Window-scoped on purpose: it reverts the moment AMBIENT loses
         * focus, so a dimmed dashboard can never leave the whole device dark
         * with no obvious way back. Values are clamped above zero for the
         * same reason - 0.0f on some panels is indistinguishable from off.
         */
        @JavascriptInterface
        public void setBrightness(final float v) {
            if (!atAmbient) return;
            final float b = v < 0.01f ? 0.01f : (v > 1f ? 1f : v);
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        android.view.WindowManager.LayoutParams lp =
                                getWindow().getAttributes();
                        lp.screenBrightness = b;
                        getWindow().setAttributes(lp);
                    } catch (Throwable ignored) {}
                }
            });
        }

        /**
         * [{id,name,dir}] from MediaStore.
         *
         * Walking the filesystem directly could not see the microSD at all -
         * an ordinary app gets no read access to a removable volume even with
         * All-files granted, so a card full of music looked empty. MediaStore
         * indexes every volume, needs only READ_EXTERNAL_STORAGE, and hands
         * back real tags, so tracks show as "Learning To Fly / Pink Floyd"
         * rather than 02_Pink_Floyd_-_Learning_To_Fly_(A_Momentary...).mp3.
         */
        @JavascriptInterface
        public String listMedia() {
            if (!atAmbient) return "[]";
            StringBuilder b = new StringBuilder("[");
            Cursor c = null;
            try {
                Uri uri = MediaStore.Audio.Media.getContentUri("external");
                c = getContentResolver().query(uri, new String[]{
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.ALBUM,
                        // Where the file lives is the only durable way to
                        // tell one part of the collection from another:
                        // the tags were romanised, so the Greek titles are
                        // in Latin script now and read as English.
                        MediaStore.Audio.Media.RELATIVE_PATH},
                        MediaStore.Audio.Media.IS_MUSIC + "!=0", null,
                        MediaStore.Audio.Media.ARTIST + "," +
                        MediaStore.Audio.Media.ALBUM + "," +
                        MediaStore.Audio.Media.TRACK);
                boolean first = true;
                int n = 0;
                // 500 was an arbitrary guard that became a silent ceiling:
                // a real library hits it and the rest of the collection is
                // simply invisible, with nothing on screen to say so.
                while (c != null && c.moveToNext() && n < 20000) {
                    n++;
                    if (!first) b.append(",");
                    first = false;
                    String artist = c.getString(2);
                    if (artist == null || "<unknown>".equals(artist))
                        artist = c.getString(3);
                    String album = c.getString(3);
                    b.append("{\"id\":").append(c.getLong(0))
                     .append(",\"name\":\"").append(jesc(String.valueOf(c.getString(1))))
                     .append("\",\"dir\":\"").append(jesc(artist == null ? "" : artist))
                     .append("\",\"album\":\"").append(jesc(album == null ? "" : album))
                     .append("\",\"path\":\"").append(jesc(
                        c.getString(4) == null ? "" : c.getString(4)))
                     .append("\"}");
                }
            } catch (Throwable t) {
                // permission missing or provider unavailable - report nothing
                // rather than a half list, and let the card explain itself
            } finally {
                try { if (c != null) c.close(); } catch (Throwable ignored) {}
            }
            return b.append("]").toString();
        }

        /**
         * Queue an episode. DownloadManager rather than our own thread: it
         * survives the app being killed, retries across a dropped
         * connection and resumes - which matters when one episode is
         * 60-100MB over house wifi and the box is a TV appliance people
         * turn off mid-download.
         *
         * The destination is load-bearing. Files land in the public
         * Podcasts directory, so MediaStore classifies them IS_PODCAST=1
         * and IS_MUSIC=0 by path - which means listMedia()'s existing
         * "IS_MUSIC != 0" filter keeps them out of the 8000-track music
         * library for free. Writing them under Music/ would mix a podcast
         * back-catalogue into the artist list.
         *
         * Internal storage, never the microSD: an ordinary app cannot write
         * a removable volume at all (see listMedia).
         */
        @JavascriptInterface
        public String podcastDownload(String url, String name) {
            if (!atAmbient) return "";
            try {
                String safe = String.valueOf(name)
                        .replaceAll("[^A-Za-z0-9 ._-]", "_").trim();
                if (safe.length() > 80) safe = safe.substring(0, 80);
                if (safe.isEmpty()) safe = "episode";
                if (!safe.toLowerCase().endsWith(".mp3")) safe += ".mp3";
                DownloadManager dm = (DownloadManager)
                        getSystemService(Context.DOWNLOAD_SERVICE);
                DownloadManager.Request rq =
                        new DownloadManager.Request(Uri.parse(url));
                rq.setTitle(safe);
                rq.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_PODCASTS, "AMBIENT/" + safe);
                rq.setNotificationVisibility(DownloadManager.Request
                        .VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                rq.setAllowedOverRoaming(false);
                return String.valueOf(dm.enqueue(rq));
            } catch (Throwable t) { return ""; }
        }

        /** [{name,pct}] for whatever is downloading right now. */
        @JavascriptInterface
        public String podcastActive() {
            if (!atAmbient) return "[]";
            StringBuilder b = new StringBuilder("[");
            Cursor c = null;
            try {
                DownloadManager dm = (DownloadManager)
                        getSystemService(Context.DOWNLOAD_SERVICE);
                DownloadManager.Query q = new DownloadManager.Query();
                q.setFilterByStatus(DownloadManager.STATUS_RUNNING
                        | DownloadManager.STATUS_PENDING
                        | DownloadManager.STATUS_PAUSED);
                c = dm.query(q);
                boolean first = true;
                while (c != null && c.moveToNext()) {
                    long so = c.getLong(c.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    long tot = c.getLong(c.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    String ttl = c.getString(c.getColumnIndexOrThrow(
                        DownloadManager.COLUMN_TITLE));
                    if (!first) b.append(",");
                    first = false;
                    b.append("{\"name\":\"").append(jesc(ttl == null ? "" : ttl))
                     .append("\",\"pct\":")
                     .append(tot > 0 ? (int) (so * 100 / tot) : 0)
                     .append("}");
                }
            } catch (Throwable t) { /* report nothing rather than half */ }
            finally { try { if (c != null) c.close(); } catch (Throwable ig) {} }
            return b.append("]").toString();
        }

        /** [{id,name,show,dur,bytes}] - episodes already on the device. */
        @JavascriptInterface
        public String listPodcasts() {
            if (!atAmbient) return "[]";
            StringBuilder b = new StringBuilder("[");
            Cursor c = null;
            try {
                c = getContentResolver().query(
                    MediaStore.Audio.Media.getContentUri("external"),
                    new String[]{
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.DURATION,
                        MediaStore.Audio.Media.SIZE,
                        // The FILENAME, which we chose - TITLE comes from
                        // the ID3 tag and is whatever the publisher wrote,
                        // so it cannot be matched back to a feed entry.
                        MediaStore.Audio.Media.DISPLAY_NAME},
                    MediaStore.Audio.Media.IS_PODCAST + "!=0", null,
                    MediaStore.Audio.Media.DATE_ADDED + " DESC");
                boolean first = true;
                while (c != null && c.moveToNext()) {
                    if (!first) b.append(",");
                    first = false;
                    b.append("{\"id\":").append(c.getLong(0))
                     .append(",\"name\":\"")
                     .append(jesc(String.valueOf(c.getString(1))))
                     .append("\",\"show\":\"")
                     .append(jesc(c.getString(2) == null ? "" : c.getString(2)))
                     .append("\",\"dur\":").append(c.getLong(3))
                     .append(",\"bytes\":").append(c.getLong(4))
                     .append(",\"file\":\"")
                     .append(jesc(c.getString(5) == null ? "" : c.getString(5)))
                     .append("\"}");
                }
            } catch (Throwable t) { /* permission or provider gone */ }
            finally { try { if (c != null) c.close(); } catch (Throwable ig) {} }
            return b.append("]").toString();
        }

        /**
         * Delete a downloaded episode. The FILE goes first, by path: the
         * rows belong to DownloadManager rather than to us, so asking the
         * resolver to delete one can be refused - but All-files access lets
         * the file itself go. The index entry is then dropped best-effort,
         * because a card listing an episode that is no longer there is
         * worse than one that briefly does not list one that is.
         */
        @JavascriptInterface
        public boolean podcastDelete(long id) {
            if (!atAmbient) return false;
            Cursor c = null;
            try {
                Uri one = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.getContentUri("external"), id);
                String path = null;
                c = getContentResolver().query(one,
                    new String[]{MediaStore.Audio.Media.DATA},
                    null, null, null);
                if (c != null && c.moveToFirst()) path = c.getString(0);
                boolean gone = false;
                if (path != null) {
                    try { gone = new File(path).delete(); }
                    catch (Throwable ignored) {}
                }
                try { getContentResolver().delete(one, null, null); }
                catch (Throwable ignored) {}
                return gone;
            } catch (Throwable t) { return false; }
            finally { try { if (c != null) c.close(); } catch (Throwable ig) {} }
        }

        @JavascriptInterface
        public String mediaBase() { return atAmbient ? MEDIA_HOST : ""; }

        /**
         * [{id,name,dur,mime,w,h,size,dir}] of every video MediaStore knows.
         *
         * mime matters more here than it did for audio: the WebView plays
         * what Chromium plays - H.264/VP8/VP9 in mp4/webm - and a library of
         * home video is full of AVI and MKV that it cannot decode. The card
         * uses this to decide up front whether to play a file inline or to
         * hand it to the system player, rather than starting playback and
         * showing a black rectangle.
         */
        @JavascriptInterface
        public String listVideo() {
            if (!atAmbient) return "[]";
            StringBuilder b = new StringBuilder("[");
            Cursor c = null;
            try {
                Uri uri = MediaStore.Video.Media.getContentUri("external");
                c = getContentResolver().query(uri, new String[]{
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.DURATION,
                        MediaStore.Video.Media.MIME_TYPE,
                        MediaStore.Video.Media.WIDTH,
                        MediaStore.Video.Media.HEIGHT,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                        MediaStore.Video.Media.TITLE,
                        // The bucket is only the LAST folder, so a series
                        // arrives as "Season 1" with the show's name thrown
                        // away - and two shows would both be "Season 1" and
                        // merge. The relative path keeps the whole shape:
                        // "Movies/The B in Apartment 23/Season 1/".
                        MediaStore.Video.Media.RELATIVE_PATH},
                        null, null,
                        MediaStore.Video.Media.RELATIVE_PATH + "," +
                        MediaStore.Video.Media.DISPLAY_NAME);
                boolean first = true;
                int n = 0;
                while (c != null && c.moveToNext() && n < 20000) {
                    n++;
                    if (!first) b.append(",");
                    first = false;
                    String title = c.getString(8);
                    if (title == null || title.length() == 0)
                        title = c.getString(1);
                    String dir = c.getString(7);
                    b.append("{\"id\":").append(c.getLong(0))
                     .append(",\"name\":\"").append(jesc(String.valueOf(title)))
                     .append("\",\"file\":\"").append(jesc(String.valueOf(c.getString(1))))
                     .append("\",\"dir\":\"").append(jesc(dir == null ? "" : dir))
                     .append("\",\"path\":\"").append(jesc(String.valueOf(c.getString(9))))
                     .append("\",\"mime\":\"").append(jesc(String.valueOf(c.getString(3))))
                     .append("\",\"dur\":").append(c.getLong(2))
                     .append(",\"w\":").append(c.getInt(4))
                     .append(",\"h\":").append(c.getInt(5))
                     .append(",\"size\":").append(c.getLong(6))
                     .append("}");
                }
            } catch (Throwable t) {
                // same policy as listMedia: report nothing rather than half
            } finally {
                try { if (c != null) c.close(); } catch (Throwable ignored) {}
            }
            return b.append("]").toString();
        }

        /**
         * Hand a video to whatever on the device can actually play it.
         * The escape hatch for the formats Chromium will not decode - an
         * AVI is still watchable, just not by us, and refusing to open it
         * at all would be worse than briefly leaving the cockpit. BACK
         * returns here, which onKeyUp already handles.
         */
        @JavascriptInterface
        public boolean openVideo(final long id) {
            if (!atAmbient) return false;
            try {
                Uri item = ContentUris.withAppendedId(
                        MediaStore.Video.Media.getContentUri("external"), id);
                String mime = getContentResolver().getType(item);
                final android.content.Intent i =
                        new android.content.Intent(
                                android.content.Intent.ACTION_VIEW);
                i.setDataAndType(item, mime == null ? "video/*" : mime);
                i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                if (i.resolveActivity(getPackageManager()) == null) return false;
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { startActivity(i); } catch (Throwable ignored) {}
                    }
                });
                return true;
            } catch (Throwable t) { return false; }
        }

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

            // Battery, from the sticky ACTION_BATTERY_CHANGED broadcast.
            // registerReceiver(null, filter) just reads the last one - no
            // permission, no receiver left registered, cheap enough for the
            // card's three-second poll. A box running on mains reports
            // EXTRA_PRESENT=0, and -1 then means "no battery here" rather
            // than "flat", which the card has to be able to tell apart.
            int batt = -1, battProp = -1, voltmV = -1;
            long chargeUah = -1;
            boolean charging = false;
            try {
                android.content.Intent bi = registerReceiver(null,
                        new android.content.IntentFilter(
                                android.content.Intent.ACTION_BATTERY_CHANGED));
                if (bi != null) {
                    int level = bi.getIntExtra(
                            android.os.BatteryManager.EXTRA_LEVEL, -1);
                    int scale = bi.getIntExtra(
                            android.os.BatteryManager.EXTRA_SCALE, -1);
                    boolean present = bi.getBooleanExtra(
                            android.os.BatteryManager.EXTRA_PRESENT, true);
                    if (present && level >= 0 && scale > 0)
                        batt = Math.round(level * 100f / scale);
                    int st = bi.getIntExtra(
                            android.os.BatteryManager.EXTRA_STATUS, -1);
                    charging =
                        st == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                     || st == android.os.BatteryManager.BATTERY_STATUS_FULL;
                    voltmV = bi.getIntExtra(
                            android.os.BatteryManager.EXTRA_VOLTAGE, -1);
                }
            } catch (Throwable t) { /* leave batt at -1 = unknown */ }

            // The broadcast above is a CACHED snapshot the framework
            // re-sends; BatteryManager's properties query the fuel gauge
            // itself. On a device whose own indicator had visibly dropped
            // while EXTRA_LEVEL still said full, the cached value is the
            // prime suspect, so the live capacity wins when it answers.
            //
            // CHARGE_COUNTER is the honest fine-grained number - microamp
            // hours actually left, not a percentage someone rounded - and
            // is reported alongside so the card can show what the gauge
            // really knows rather than a figure quantised to whole
            // percent, or to whatever step this box's driver uses.
            try {
                android.os.BatteryManager bm = (android.os.BatteryManager)
                        getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    int cap = bm.getIntProperty(android.os.BatteryManager
                            .BATTERY_PROPERTY_CAPACITY);
                    // unsupported properties come back as 0 or
                    // Integer.MIN_VALUE, neither of which is a level
                    if (cap > 0 && cap <= 100) battProp = cap;
                    long cc = bm.getLongProperty(android.os.BatteryManager
                            .BATTERY_PROPERTY_CHARGE_COUNTER);
                    if (cc > 0 && cc < Long.MAX_VALUE) chargeUah = cc;
                }
            } catch (Throwable t) { /* properties are optional */ }

            // THE ONLY SOURCE THAT WORKS ON THIS BOX.
            //
            // Measured over ADB: /sys/class/power_supply is empty, the
            // health HAL reports present=false, and dumpsys battery returns
            // placeholders - level 50, voltage 4, charge counter 10, status
            // and health both UNKNOWN. So every standard API above is
            // reading a battery that Android does not believe exists, which
            // is why the tile sat at a fixed number while the device's own
            // settings showed the level dropping.
            //
            // The real value comes off an MCU over a serial link (see
            // com.sei.serialportservice) and the vendor publishes it into
            // Settings.Global as "battery", with "battery_discharging" as
            // the charge state. Both are plain readable settings - no
            // permission, no vendor SDK.
            int battVendor = -1, vendorDischarging = -1, battTempC = -1;
            try {
                android.content.ContentResolver cr = getContentResolver();
                battVendor = android.provider.Settings.Global.getInt(
                        cr, "battery", -1);
                if (battVendor < 0 || battVendor > 100) battVendor = -1;
                vendorDischarging = android.provider.Settings.Global.getInt(
                        cr, "battery_discharging", -1);
                // Plain degrees here, NOT the tenths that
                // EXTRA_TEMPERATURE uses - measured 41 on a charging pack,
                // which is 41 C and not 4.1. Anything big enough to have
                // been tenths is divided, so other hardware still reads
                // sensibly.
                int t = android.provider.Settings.Global.getInt(
                        cr, "battery_temperature", -999);
                if (t > 200) battTempC = Math.round(t / 10f);
                else if (t > -50 && t < 150) battTempC = t;
            } catch (Throwable t) { /* not this device: fall through */ }

            // Vendor first, then the live gauge, then the broadcast.
            int battBcast = batt;
            if (battProp >= 0) batt = battProp;
            if (battVendor >= 0) {
                batt = battVendor;
                // 0 = not discharging = on the charger, which matches what
                // dumpsys reports for AC power on this box.
                if (vendorDischarging >= 0) charging = vendorDischarging == 0;
            }

            return "{\"totalKb\":" + totalKb
                 + ",\"availKb\":" + availKb
                 + ",\"cpu\":" + String.format(java.util.Locale.US, "%.1f", cpu)
                 + ",\"batt\":" + batt
                 + ",\"battBcast\":" + battBcast
                 + ",\"battProp\":" + battProp
                 + ",\"battVendor\":" + battVendor
                 + ",\"battTempC\":" + battTempC
                 + ",\"chargeUah\":" + chargeUah
                 + ",\"voltmV\":" + voltmV
                 + ",\"charging\":" + charging
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

    /**
     * Spatial navigation for sites opened by LAUNCH.
     *
     * The WebView gives a page arrow keys as plain scrolling and nothing
     * else - there is no focus ring and no way to choose a link, so a
     * streaming site can be scrolled through and never used. Chromium has
     * spatial navigation built in but it cannot be switched on from here,
     * so this supplies it: arrows move a visible highlight to the nearest
     * clickable thing in that direction, OK activates it.
     *
     * Deliberately small and defensive - it runs on somebody else's page.
     */
    private static final String SPATIAL_NAV =
        "(function(){\n" +
        "if(window.__ambientNav)return;window.__ambientNav=1;\n" +
        // Roles and tabindex catch the well-behaved half of a page. They do
        // NOT catch a hamburger: that is usually a bare <div> with its
        // listener bound in JavaScript, carrying no href, no role and no
        // tabindex. The cursor sweep below is what finds those.
        "var S='a[href],button,input,select,textarea,[onclick],[role=button],'+\n" +
        "  '[role=link],[role=menuitem],[role=tab],[role=checkbox],'+\n" +
        "  '[role=switch],[aria-haspopup],summary,'+\n" +
        "  '[tabindex]:not([tabindex=\"-1\"]),video';\n" +
        "var DLG='[role=dialog],[role=alertdialog],[aria-modal=\"true\"]';\n" +
        "var st=document.createElement('style');\n" +
        "st.textContent='.__ambsel{outline:3px solid #6cf!important;'+\n" +
        "  'outline-offset:2px!important;scroll-margin:120px!important}';\n" +
        "document.documentElement.appendChild(st);\n" +
        "var cur=null;\n" +
        // Typing must never be hijacked. A login field is the one place a
        // wrong guess is unrecoverable: OK has to submit the form, not
        // click whatever the highlight happened to be resting on.
        "function edit(e){if(!e)return false;\n" +
        "  if(e.isContentEditable)return true;\n" +
        "  var t=(e.tagName||'').toUpperCase();\n" +
        "  if(t==='TEXTAREA'||t==='SELECT')return true;\n" +
        "  if(t!=='INPUT')return false;\n" +
        "  return !/^(button|submit|reset|checkbox|radio|image|file)$/i\n" +
        "    .test(e.type||'text');}\n" +
        "function typing(){return edit(document.activeElement);}\n" +
        "function shown(e){var s;try{s=getComputedStyle(e)}catch(_){return false}\n" +
        "  return s.visibility!=='hidden'&&s.display!=='none'&&\n" +
        "    s.opacity!=='0'&&s.pointerEvents!=='none';}\n" +
        // Candidates are limited to what is ON SCREEN. That keeps the hit
        // test below meaningful, and when nothing lies further in a
        // direction the key falls through and the page scrolls, which
        // brings the next screenful into range.
        "function onScreen(r){return r.width>=10&&r.height>=10&&\n" +
        "  r.bottom>0&&r.top<innerHeight&&r.right>0&&r.left<innerWidth;}\n" +
        // Is this element actually the thing at its own centre? Without
        // asking, the highlight walks happily onto buttons sitting UNDER a
        // popup - which is what made an overlay impossible to dismiss. It
        // also confines navigation to a modal for free, with no need to
        // recognise that a modal is what this is.
        "function onTop(e,r){\n" +
        "  var x=Math.max(1,Math.min(innerWidth-2,r.left+r.width/2));\n" +
        "  var y=Math.max(1,Math.min(innerHeight-2,r.top+r.height/2));\n" +
        "  var h=null;try{h=document.elementFromPoint(x,y)}catch(_){}\n" +
        "  if(!h)return false;\n" +
        "  return h===e||e.contains(h)||h.contains(e);}\n" +
        "var cacheAt=0,cacheList=null;\n" +
        "function all(){\n" +
        "  var now=Date.now();\n" +
        "  if(cacheList&&now-cacheAt<400)return cacheList;\n" +
        "  var found=[],i,e,r,s;\n" +
        "  function add(el,rect){if(found.indexOf(el)<0&&shown(el)&&\n" +
        "    onTop(el,rect))found.push(el);}\n" +
        "  var q=document.querySelectorAll(S);\n" +
        "  for(i=0;i<q.length;i++){e=q[i];r=e.getBoundingClientRect();\n" +
        "    if(onScreen(r))add(e,r);}\n" +
        // cursor:pointer is the one mark a page reliably leaves on
        // something it expects to be clicked, whatever the markup is.
        // Capped and viewport-limited so a heavy page cannot make a
        // keypress expensive.
        "  var nodes=document.body?document.body.getElementsByTagName('*'):[];\n" +
        "  for(i=0;i<nodes.length&&found.length<300;i++){\n" +
        "    e=nodes[i];r=e.getBoundingClientRect();\n" +
        "    if(!onScreen(r))continue;\n" +
        "    if(r.width>innerWidth*0.9&&r.height>innerHeight*0.9)continue;\n" +
        "    try{s=getComputedStyle(e)}catch(_){continue}\n" +
        "    if(s.cursor!=='pointer')continue;\n" +
        "    add(e,r);}\n" +
        // A clickable wrapper around a clickable button is two stops on one
        // target, and the outer one is the one that does nothing on some
        // sites. Keep the innermost.
        "  var drop=[];\n" +
        "  for(i=0;i<found.length;i++){var p=found[i].parentElement;\n" +
        "    while(p){if(found.indexOf(p)>=0&&drop.indexOf(p)<0)drop.push(p);\n" +
        "      p=p.parentElement;}}\n" +
        "  var out=[];\n" +
        "  for(i=0;i<found.length;i++)\n" +
        "    if(drop.indexOf(found[i])<0)out.push(found[i]);\n" +
        "  cacheList=out.length?out:found;cacheAt=now;return cacheList;}\n" +
        "function box(e){var r=e.getBoundingClientRect();\n" +
        "  return{x:r.left+r.width/2,y:r.top+r.height/2};}\n" +
        // mark() must NOT focus. Focusing an input on Android pops the
        // on-screen keyboard, so merely passing the highlight over a login
        // box would throw the IME up uninvited. The outline is the
        // selection; focus happens only when OK actually chooses it.
        "function mark(e){if(cur)cur.classList.remove('__ambsel');\n" +
        "  cur=e;if(!e)return;e.classList.add('__ambsel');\n" +
        "  try{e.scrollIntoView({block:'center',inline:'nearest'})}catch(_){}}\n" +
        "function live(e){if(!e||!e.isConnected)return false;\n" +
        "  var r=e.getBoundingClientRect();\n" +
        "  return onScreen(r)&&shown(e)&&onTop(e,r);}\n" +
        "function move(dir){var els=all();if(!els.length)return false;\n" +
        "  if(!cur||els.indexOf(cur)<0||!live(cur)){mark(els[0]);return true;}\n" +
        "  var c=box(cur),best=null,bd=1e9;\n" +
        "  for(var i=0;i<els.length;i++){var e=els[i];if(e===cur)continue;\n" +
        "    var b=box(e),dx=b.x-c.x,dy=b.y-c.y;\n" +
        "    var fwd=dir==='right'?dx:dir==='left'?-dx:dir==='down'?dy:-dy;\n" +
        "    if(fwd<=1)continue;\n" +
        "    var off=(dir==='left'||dir==='right')?Math.abs(dy):Math.abs(dx);\n" +
        "    var d=fwd+off*2;\n" +      // straight ahead beats diagonal
        "    if(d<bd){bd=d;best=e;}}\n" +
        "  if(best){mark(best);return true;}return false;}\n" +
        "function fire(e,type,x,y){var ev=null;\n" +
        "  try{\n" +
        "    if(type.indexOf('pointer')===0)\n" +
        "      ev=new PointerEvent(type,{bubbles:true,cancelable:true,\n" +
        "        composed:true,clientX:x,clientY:y,pointerId:1,\n" +
        "        pointerType:'mouse',isPrimary:true});\n" +
        "    else ev=new MouseEvent(type,{bubbles:true,cancelable:true,\n" +
        "      composed:true,view:window,clientX:x,clientY:y,button:0});\n" +
        "  }catch(_){return}\n" +
        "  try{e.dispatchEvent(ev)}catch(_){}}\n" +
        // A bare .click() is not enough for a control that listens for
        // pointer or mouse events, which is most custom menus - they simply
        // do nothing and the button looks broken. Send what a real press
        // produces, then the click itself.
        "function activate(){if(!cur)return false;\n" +
        "  var e=cur,r=e.getBoundingClientRect();\n" +
        "  var x=r.left+r.width/2,y=r.top+r.height/2;\n" +
        "  try{e.focus({preventScroll:true})}catch(_){}\n" +
        "  fire(e,'pointerdown',x,y);fire(e,'mousedown',x,y);\n" +
        "  fire(e,'pointerup',x,y);fire(e,'mouseup',x,y);\n" +
        "  try{e.click()}catch(_){}\n" +
        "  return true;}\n" +
        // ---- floating cursor ----
        // The fallback for everything the highlight cannot reach: controls
        // bound in ways no selector finds, and hover menus, which spatial
        // navigation cannot open at all. Off by default, because the
        // highlight is faster whenever it does work.
        "var curMode=false,cx=0,cy=0,dot=null,hov=null,fl=null,flT=null;\n" +
        "function flash(m){\n" +
        "  if(!fl){fl=document.createElement('div');\n" +
        "    fl.style.cssText='position:fixed;left:50%;bottom:8%;'+\n" +
        "      'transform:translateX(-50%);z-index:2147483647;'+\n" +
        "      'pointer-events:none;font:600 14px system-ui;'+\n" +
        "      'letter-spacing:.12em;color:#fff;background:rgba(0,0,0,.75);'+\n" +
        "      'padding:6px 14px;border-radius:4px;opacity:0;'+\n" +
        "      'transition:opacity .2s';\n" +
        "    document.documentElement.appendChild(fl);}\n" +
        "  fl.textContent=m;fl.style.opacity='1';\n" +
        "  clearTimeout(flT);flT=setTimeout(function(){\n" +
        "    try{fl.style.opacity='0'}catch(_){}},1600);}\n" +
        "function draw(){\n" +
        "  if(!dot){dot=document.createElement('div');\n" +
        "    dot.style.cssText='position:fixed;z-index:2147483646;'+\n" +
        "      'width:18px;height:18px;margin:-9px 0 0 -9px;'+\n" +
        "      'border:2px solid #6cf;border-radius:50%;'+\n" +
        "      'background:rgba(0,0,0,.35);'+\n" +
        "      'box-shadow:0 0 0 1px #000,0 0 8px #000;pointer-events:none';\n" +
        "    document.documentElement.appendChild(dot);}\n" +
        "  dot.style.left=cx+'px';dot.style.top=cy+'px';\n" +
        "  dot.style.display=curMode?'block':'none';}\n" +
        // Hover is the whole reason this mode earns its place: a menu that
        // only opens on mouseover is unreachable by any other means here.
        "function hover(){var h=null;\n" +
        "  try{h=document.elementFromPoint(cx,cy)}catch(_){}\n" +
        "  if(!h)return;\n" +
        "  if(h!==hov){if(hov)fire(hov,'mouseout',cx,cy);\n" +
        "    fire(h,'mouseover',cx,cy);hov=h;}\n" +
        "  fire(h,'mousemove',cx,cy);}\n" +
        "function nudge(dir,fast){var step=fast?54:16,m=48;\n" +
        "  if(dir==='left')cx-=step;else if(dir==='right')cx+=step;\n" +
        "  else if(dir==='up')cy-=step;else cy+=step;\n" +
        // Pushing against the top or bottom edge scrolls rather than
        // stopping, so a long page stays reachable without leaving cursor
        // mode to scroll and coming back.
        "  if(cy<m){try{scrollBy(0,cy-m-step)}catch(_){}cy=m;}\n" +
        "  if(cy>innerHeight-m){\n" +
        "    try{scrollBy(0,cy-(innerHeight-m)+step)}catch(_){}\n" +
        "    cy=innerHeight-m;}\n" +
        "  cx=Math.max(2,Math.min(innerWidth-2,cx));\n" +
        "  cy=Math.max(2,Math.min(innerHeight-2,cy));\n" +
        "  draw();hover();}\n" +
        // ---- timeline control ----
        // A player's scrub bar is a custom widget that wants a real drag:
        // mousedown, a stream of mousemoves, mouseup. Synthesising that
        // with a d-pad is hopeless, and half of them ignore a synthetic
        // press anyway. So do not touch the bar at all - take the <video>
        // and set currentTime, which every player on earth is built on.
        "var scrub=null,sb=null;\n" +
        "function fmt(t){t=Math.max(0,Math.round(t||0));\n" +
        "  var h=Math.floor(t/3600),m=Math.floor(t%3600/60),s=t%60;\n" +
        "  function p(n){return (n<10?'0':'')+n;}\n" +
        "  return (h?h+':'+p(m):''+m)+':'+p(s);}\n" +
        "function bar(){\n" +
        "  if(!sb){sb=document.createElement('div');\n" +
        "    sb.style.cssText='position:fixed;left:50%;bottom:13%;'+\n" +
        "      'transform:translateX(-50%);z-index:2147483647;'+\n" +
        "      'pointer-events:none;min-width:320px;font:600 13px system-ui;'+\n" +
        "      'letter-spacing:.1em;color:#fff;background:rgba(0,0,0,.8);'+\n" +
        "      'padding:10px 14px;border-radius:6px;display:none';\n" +
        "    document.documentElement.appendChild(sb);}\n" +
        "  return sb;}\n" +
        "function showScrub(){var v=scrub,e=bar();\n" +
        "  if(!v||!v.isConnected){e.style.display='none';return;}\n" +
        "  var d=v.duration,p=v.currentTime||0;\n" +
        "  var pct=(isFinite(d)&&d>0)?(100*p/d):0;\n" +
        "  e.innerHTML='<div style=\"display:flex;justify-content:space-between;'+\n" +
        "    'gap:24px\"><span>TIMELINE &#9668;&#9658; 10s &#9650;&#9660; 60s'+\n" +
        "    ' &#183; OK done</span><span>'+fmt(p)+' / '+\n" +
        "    (isFinite(d)?fmt(d):'live')+'</span></div>'+\n" +
        "    '<div style=\"height:4px;background:rgba(255,255,255,.25);'+\n" +
        "    'margin-top:8px;border-radius:2px;overflow:hidden\">'+\n" +
        "    '<div style=\"height:100%;background:#6cf;width:'+\n" +
        "    pct.toFixed(1)+'%\"></div></div>';\n" +
        "  e.style.display='block';}\n" +
        "function startScrub(v){scrub=v;showScrub();flash('TIMELINE');}\n" +
        "function endScrub(){scrub=null;bar().style.display='none';\n" +
        "  flash('CURSOR');}\n" +
        "function seek(dt){var v=scrub;if(!v)return;\n" +
        "  var d=v.duration;\n" +
        "  if(!isFinite(d)||d<=0){flash('LIVE - NO TIMELINE');return;}\n" +
        "  try{v.currentTime=\n" +
        "    Math.max(0,Math.min(d-0.25,(v.currentTime||0)+dt))}catch(_){}\n" +
        "  showScrub();}\n" +
        // Is this thing a scrub bar? Players label them, one way or
        // another, and four levels up covers the usual wrapper nesting.
        "function sliderish(e){\n" +
        "  for(var n=e,i=0;n&&i<4;n=n.parentElement,i++){\n" +
        "    var t=(n.tagName||'').toUpperCase();\n" +
        "    if(t==='INPUT'&&/range/i.test(n.type||''))return true;\n" +
        "    var r='';try{r=n.getAttribute('role')||''}catch(_){}\n" +
        "    if(/slider|progressbar/i.test(r))return true;\n" +
        "    var c=n.className;\n" +
        "    if(c&&c.baseVal!==undefined)c=c.baseVal;\n" +
        "    c=(typeof c==='string'?c:'')+' '+(n.id||'');\n" +
        "    if(/seek|scrub|timeline|progress|playbar|slider/i.test(c))\n" +
        "      return true;}\n" +
        "  return false;}\n" +
        "function pickVideo(){var vs=vids(),best=null,ba=0;\n" +
        "  for(var i=0;i<vs.length;i++){var v=vs[i];\n" +
        "    var r=v.getBoundingClientRect(),a=r.width*r.height;\n" +
        "    if(a<1600)continue;\n" +
        "    if(cx>=r.left&&cx<=r.right&&cy>=r.top&&cy<=r.bottom+60)return v;\n" +
        "    if(a>ba){best=v;ba=a;}}\n" +
        "  return best;}\n" +
        // "Near the timeline" taken literally: the bottom strip of the
        // picture, which is where every player puts its bar, plus a little
        // slack below for controls drawn outside the video box.
        "function nearTimeline(){var vs=vids();\n" +
        "  for(var i=0;i<vs.length;i++){\n" +
        "    var r=vs[i].getBoundingClientRect();\n" +
        "    if(r.width<80||r.height<60)continue;\n" +
        "    if(cx<r.left||cx>r.right)continue;\n" +
        "    var lip=Math.max(44,r.height*0.22);\n" +
        "    if(cy>r.bottom-lip&&cy<r.bottom+48)return vs[i];}\n" +
        "  return null;}\n" +
        "function clickAt(){var h=null;\n" +
        "  try{h=document.elementFromPoint(cx,cy)}catch(_){}\n" +
        // OK is what leaves the timeline again, so the mode cannot trap.
        "  if(scrub){endScrub();return true;}\n" +
        // Deliberately NO synthetic click when taking the timeline: a click
        // on a seek bar jumps to wherever the cursor happens to be, and
        // being thrown somewhere you did not ask for is a worse start than
        // simply having control from where you are.
        "  var sv=(h&&sliderish(h))?pickVideo():nearTimeline();\n" +
        "  if(sv&&isFinite(sv.duration)&&sv.duration>0){\n" +
        "    startScrub(sv);return true;}\n" +
        "  if(!h)return false;\n" +
        "  try{h.focus({preventScroll:true})}catch(_){}\n" +
        "  fire(h,'pointerdown',cx,cy);fire(h,'mousedown',cx,cy);\n" +
        "  fire(h,'pointerup',cx,cy);fire(h,'mouseup',cx,cy);\n" +
        "  try{h.click()}catch(_){}\n" +
        "  return true;}\n" +
        // Called from onNewIntent. The shortcut button is FREE on a
        // launched site - __ambientRelaunch only exists on AMBIENT's own
        // page - so one hardware button means the playback lock at home
        // and the cursor toggle out here, with no collision.
        "window.__ambientCursor=function(){\n" +
        "  curMode=!curMode;\n" +
        "  if(curMode){if(cur)cur.classList.remove('__ambsel');cur=null;\n" +
        "    cx=innerWidth/2;cy=innerHeight/2;hover();}\n" +
        "  else {if(hov){fire(hov,'mouseout',cx,cy);hov=null;}\n" +
        "    if(scrub){scrub=null;bar().style.display='none';}}\n" +
        "  draw();flash(curMode?'CURSOR':'HIGHLIGHT');\n" +
        "  return true;};\n" +
        "document.addEventListener('keydown',function(ev){\n" +
        "  var k=ev.key,d=null;\n" +
        "  if(k==='ArrowRight')d='right';else if(k==='ArrowLeft')d='left';\n" +
        "  else if(k==='ArrowDown')d='down';else if(k==='ArrowUp')d='up';\n" +
        "  if(!d&&k!=='Enter')return;\n" +
        "  if(curMode&&!typing()){\n" +
        "    if(scrub){\n" +
        "      if(d==='left')seek(ev.repeat?-30:-10);\n" +
        "      else if(d==='right')seek(ev.repeat?30:10);\n" +
        "      else if(d==='up')seek(60);\n" +
        "      else if(d==='down')seek(-60);\n" +
        "      else endScrub();\n" +
        "      ev.preventDefault();ev.stopPropagation();return;}\n" +
        "    if(d){nudge(d,ev.repeat===true);\n" +
        "      ev.preventDefault();ev.stopPropagation();return;}\n" +
        "    clickAt();ev.preventDefault();ev.stopPropagation();return;}\n" +
        "  if(typing()){\n" +
        // In a text field the keys belong to the field: left/right are the
        // text cursor and Enter submits. Up/down are the way OUT - without
        // them a field you have finished with is a trap, since the page
        // keeps focus after the keyboard closes.
        "    if(d==='up'||d==='down'){\n" +
        "      try{document.activeElement.blur()}catch(_){}\n" +
        "      if(move(d)){ev.preventDefault();ev.stopPropagation();}\n" +
        "    }\n" +
        "    return;}\n" +
        "  if(d){if(move(d)){ev.preventDefault();ev.stopPropagation();}return;}\n" +
        // Enter with nothing chosen falls through to the page, or a site's
        // own key handling stops working for no visible reason.
        "  if(cur){activate();ev.preventDefault();ev.stopPropagation();}\n" +
        "},true);\n" +
        // ---- fixed chrome sitting on top of a playing video ----
        // A site pins its nav to the bottom of the viewport. Over a video
        // that fills the viewport that lands straight across the picture,
        // and on a worn display there is no "rest of the screen" to move it
        // to. Hidden only while something is actually playing and restored
        // the moment it pauses, so navigation is never taken away. The
        // player's own controls are left alone - anything inside the
        // player's container is its UI, not the site's chrome.
        "var H='__ambhide';\n" +
        "var s2=document.createElement('style');\n" +
        "s2.textContent='.'+H+'{opacity:0!important;'+\n" +
        "  'pointer-events:none!important}';\n" +
        "document.documentElement.appendChild(s2);\n" +
        "function vids(){return [].slice.call(\n" +
        "  document.querySelectorAll('video'));}\n" +
        "function liveVids(){return vids().filter(function(v){\n" +
        "  return !v.paused&&!v.ended;});}\n" +
        // Fixed chrome sits near the top of the DOM, so two levels under
        // body is far enough. Walking every node and calling
        // getComputedStyle on each, once a second, would cost more than the
        // problem it solves.
        "function shallow(){var b=document.body;if(!b)return [];\n" +
        "  var l1=[].slice.call(b.children),out=l1.slice();\n" +
        "  l1.forEach(function(e){\n" +
        "    out=out.concat([].slice.call(e.children));});\n" +
        "  return out;}\n" +
        "function owner(v){var p=v;\n" +
        "  for(var i=0;i<4&&p&&p.parentElement;i++)p=p.parentElement;\n" +
        "  return p;}\n" +
        // NEVER hide a dialog. A modal is fixed-position exactly like a nav
        // bar, so it was eligible for this - and hiding the thing the page
        // is waiting for you to dismiss leaves you stuck behind an
        // invisible wall, which is worse than any nav bar over a film.
        "function dialogish(e,r){\n" +
        "  try{if(e.matches&&e.matches(DLG))return true}catch(_){}\n" +
        "  return r.width>innerWidth*0.6&&r.height>innerHeight*0.5;}\n" +
        "var wasOn=null;\n" +
        "function sweep(){\n" +
        "  var vs=liveVids(),on=vs.length>0;\n" +
        "  if(!on){\n" +
        "    if(wasOn!==false){\n" +
        "      [].slice.call(document.querySelectorAll('.'+H))\n" +
        "        .forEach(function(e){e.classList.remove(H);});\n" +
        "      wasOn=false;}\n" +
        "    return;}\n" +
        "  var roots=vs.map(owner);\n" +
        "  shallow().forEach(function(e){\n" +
        "    if(e.classList.contains(H))return;\n" +
        "    var st;try{st=getComputedStyle(e)}catch(_){return}\n" +
        "    if(st.position!=='fixed'&&st.position!=='sticky')return;\n" +
        "    var r=e.getBoundingClientRect();\n" +
        "    if(r.height<10||r.width<50)return;\n" +
        "    if(dialogish(e,r))return;\n" +
        "    for(var i=0;i<roots.length;i++)\n" +
        "      if(roots[i]&&roots[i].contains(e))return;\n" +
        "    e.classList.add(H);});\n" +
        "  wasOn=true;}\n" +
        "setInterval(function(){sweep();if(scrub)showScrub();},1000);\n" +
        // No auto-selection on load: picking a target unasked moved focus
        // on pages that were working fine.
        "})();";


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
        // content:// URIs carry no extension; the decoder sniffs anyway
        return "audio/mpeg";
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

    /**
     * The whole file, with a skip() that actually seeks.
     *
     * The WebView asks for byte ranges by calling skip() on this stream.
     * InputStream.skip() reads and discards by default, so a seek an hour
     * into a 2.7GB film would have read a gigabyte off the card first -
     * which is what "it freezes when I start playing" turned out to be
     * once ranging moved back to where it belongs. A FileChannel position
     * is O(1) at any offset.
     */
    private InputStream fullStream(Uri item) {
        android.os.ParcelFileDescriptor pfd = null;
        try {
            pfd = getContentResolver().openFileDescriptor(item, "r");
            if (pfd == null) return null;
            final android.os.ParcelFileDescriptor held = pfd;
            final FileInputStream fis =
                    new FileInputStream(pfd.getFileDescriptor());
            return new InputStream() {
                @Override public int read() throws java.io.IOException {
                    return fis.read();
                }
                @Override public int read(byte[] b, int off, int len)
                        throws java.io.IOException {
                    return fis.read(b, off, len);
                }
                @Override public long skip(long n) throws java.io.IOException {
                    if (n <= 0) return 0;
                    java.nio.channels.FileChannel ch = fis.getChannel();
                    long pos = ch.position(), size = ch.size();
                    long target = Math.min(size, pos + n);
                    ch.position(target);
                    return target - pos;
                }
                @Override public int available() throws java.io.IOException {
                    java.nio.channels.FileChannel ch = fis.getChannel();
                    long a = ch.size() - ch.position();
                    return a > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) a;
                }
                @Override public void close() throws java.io.IOException {
                    try { fis.close(); } finally { held.close(); }
                }
            };
        } catch (Throwable t) {
            try { if (pfd != null) pfd.close(); } catch (Throwable ignored) {}
            return null;
        }
    }

    private WebResourceResponse serveMedia(String url, String rangeHeader) {
        try {
            // ?id= is a track, ?vid= is a video. They live in different
            // MediaStore collections and the same numeric id means different
            // files in each, so the prefix is what picks the collection -
            // guessing would serve the wrong thing rather than nothing.
            boolean video = false;
            int q = url.indexOf("?id=");
            int skip = 4;
            if (q < 0) { q = url.indexOf("?vid="); skip = 5; video = true; }
            if (q < 0) return null;
            long id;
            try { id = Long.parseLong(url.substring(q + skip).trim()); }
            catch (Throwable t) { return null; }
            Uri item = ContentUris.withAppendedId(
                    video ? MediaStore.Video.Media.getContentUri("external")
                          : MediaStore.Audio.Media.getContentUri("external"),
                    id);

            long len = -1;
            android.content.res.AssetFileDescriptor afd = null;
            try {
                afd = getContentResolver().openAssetFileDescriptor(item, "r");
                if (afd != null) len = afd.getLength();
            } catch (Throwable ignored) {
            } finally {
                try { if (afd != null) afd.close(); } catch (Throwable ignored) {}
            }
            if (len < 0) return null;
            String name = item.toString();
            // A content:// URI has no extension, so mimeFor() falls back to
            // audio/mpeg - which would label every video as audio and give
            // the page a <video> element that decodes nothing. The resolver
            // knows the real type; only fall back when it does not.
            String mime = null;
            try { mime = getContentResolver().getType(item); }
            catch (Throwable ignored) {}
            if (mime == null || mime.length() == 0)
                mime = video ? "video/mp4" : mimeFor(name);

            Map<String, String> h = new HashMap<String, String>();
            h.put("Access-Control-Allow-Origin", "*");   // makes the FFT work
            h.put("Accept-Ranges", "bytes");

            /* Hand back the WHOLE resource and set no length.
             *
             * The WebView does its own range handling on an intercepted
             * response - it slices the stream and injects the correct
             * Content-Length itself. Anything set here is appended as a
             * SECOND value, and Chromium aborts a response whose
             * Content-Length contradicts itself. That is what every failed
             * seek was: "Content-Length: 152030935, 277597911".
             *
             * Serving a pre-sliced 206 was worse - the range then got
             * applied twice, once by this code and again by the WebView,
             * giving "26463959" for a 152MB body. The rule is simply that
             * ranging is not ours to do; the stream's skip() below makes
             * the WebView's own seek O(1).
             */
            InputStream in = fullStream(item);
            if (in == null) return null;
            /* A ranged request needs the 206 status and a Content-Range -
               answering 200 tells Chromium the range was ignored, and it
               aborts because the body then does not start where it asked.
               The BODY, though, stays whole: the WebView slices it itself
               (via the seeking skip() above) and computes the length. */
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                long start = 0, end = len - 1;
                String[] parts = rangeHeader.substring(6).split("-");
                try {
                    if (parts.length > 0 && parts[0].length() > 0)
                        start = Long.parseLong(parts[0].trim());
                    if (parts.length > 1 && parts[1].length() > 0)
                        end = Long.parseLong(parts[1].trim());
                } catch (Throwable ignored) {}
                if (start < 0 || start >= len) start = 0;
                if (end < start || end >= len) end = len - 1;
                h.put("Content-Range",
                        "bytes " + start + "-" + end + "/" + len);
                return new WebResourceResponse(mime, null, 206,
                        "Partial Content", h, in);
            }
            return new WebResourceResponse(mime, null, 200, "OK", h, in);
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

        // Makes the page inspectable from Chrome on a tethered computer
        // (chrome://inspect). Without it, diagnosing anything inside the
        // WebView is guesswork from the outside - which is how a stalled
        // video stayed a theory instead of a measurement. This is a
        // sideloaded debug build; there is nothing here worth hiding.
        try { WebView.setWebContentsDebuggingEnabled(true); }
        catch (Throwable ignored) {}
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
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                // Write cookies to disk NOW, not at onPause. A TV app is
                // killed rather than closed - a sideloaded reinstall
                // SIGKILLs it outright - and onPause does not run on a
                // kill, so anything since the last flush is lost. That is
                // exactly a login: signed in, killed, signed out again.
                try { CookieManager.getInstance().flush(); }
                catch (Throwable ignored) {}
                // A launched site gets arrow keys as SCROLLING and nothing
                // else: the WebView has no spatial navigation, so links can
                // be scrolled past but never chosen. This injects the
                // missing half - arrows move a highlight between clickable
                // things, OK activates one.
                if (!atAmbient) {
                    try { v.evaluateJavascript(SPATIAL_NAV, null); }
                    catch (Throwable ignored) {}
                }
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

    /**
     * The shortcut ("rocket") button launches AMBIENT. When AMBIENT is
     * ALREADY in front, singleTask means the system delivers the launch
     * here instead of starting anything - so the button becomes a spare
     * input the app can use, without claiming a keycode the system
     * intercepts (which is what makes the assistant button unusable).
     *
     * The page turns it into a playback lock. A hardware button is the
     * right unlock precisely because it cannot be pressed by the same
     * accidental brush of the d-pad that the lock exists to ignore.
     */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // THIS is the path the shortcut button actually takes, verified on
        // hardware: pressing it while AMBIENT is already in front toggles
        // the lock from here, and onKeyDown never sees a key at all - the
        // system consumes KEY_COPY to perform the launch and delivers the
        // launch here instead.
        //
        // Worth knowing, because it misled me once: `adb shell am start` is
        // NOT equivalent. The system answers that with "Activity not
        // started, its current task has been brought to the front" and
        // delivers no intent, so testing this over ADB shows a dead button
        // that works perfectly in the hand.
        // AMBIENT's own page answers the first; a launched site
        // answers the second. Neither defines the other, so one
        // button means the playback lock at home and the cursor
        // toggle on a site, with nothing to choose between.
        callPage("(window.__ambientRelaunch&&window.__ambientRelaunch())"
               + "||(window.__ambientCursor&&window.__ambientCursor())");
    }

    /** Run a snippet in the page from any thread. */
    private void callPage(final String js) {
        final WebView w = web;
        if (w == null) return;
        w.post(new Runnable() {
            public void run() {
                try { w.evaluateJavascript(js, null); }
                catch (Throwable ignored) {}
            }
        });
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
     * On AMBIENT it is a navigation key - up one level, leave fullscreen,
     * open the overview - but Android delivers it to the Activity, not the
     * page, so it is forwarded as an Escape keydown.
     *
     * On a site opened by the LAUNCH card it has to behave like a browser.
     * Overriding onKeyDown had shadowed onBackPressed entirely, so BACK sent
     * an Escape that Wikipedia ignored and a second press quit the app:
     * there was no way back to the dashboard at all. Now it walks history,
     * falls back to AMBIENT when history runs out, and a double press jumps
     * straight home however deep you have browsed, so a launched site can
     * never be a dead end.
     *
     * The work happens on key UP. A long press has to be able to mean
     * something different from a short one, and Android only offers that
     * through startTracking()/onKeyLongPress, which requires the down event
     * to be claimed and the decision deferred until the key is released.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // The shortcut ("rocket") button reports as KEY_COPY at the input
        // layer - verified with getevent, alongside a d-pad press as a
        // control. On THIS device the system consumes it to launch the app,
        // so it arrives at onNewIntent and never here; this stays as
        // insurance for a build or a device that dispatches it normally,
        // and costs nothing when it does not. It could never be caught in
        // the page: the WebView maps no DOM key event to KEYCODE_COPY, so
        // JavaScript cannot see it however the page listens.
        if (keyCode == KeyEvent.KEYCODE_COPY) {
            if (event.getRepeatCount() == 0) callPage(
                "window.__ambientRelaunch&&window.__ambientRelaunch()");
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getRepeatCount() == 0) {
                event.startTracking();
                backWasLong = false;
            }
            return true;
        }
        // Everything else - DPAD_LEFT/RIGHT/UP/DOWN, DPAD_CENTER - falls
        // through to the WebView, which turns it into the arrow and Enter
        // key events the cockpit shell listens for.
        return super.onKeyDown(keyCode, event);
    }

    /** Hold BACK to leave AMBIENT. A hold cannot be produced by navigating,
     *  which is the whole point: the exit gesture and the navigation keys
     *  can no longer be mistaken for one another. */
    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            backWasLong = true;
            finish();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (backWasLong) {          // already handled by the long press
                backWasLong = false;
                return true;
            }
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

            /* At AMBIENT, BACK belongs to the page, every time.
             *
             * It used to quit on a second press inside EXIT_WINDOW_MS, which
             * was fine while BACK only ever meant "leave". Once the cockpit
             * made BACK mean "up one level", the two collided: walking out of
             * MUSIC is track -> album -> tiles, two presses well inside two
             * seconds, so climbing back out of a hierarchy quit the app.
             *
             * Exit is a LONG press now (handled in onKeyLongPress). A hold
             * cannot be produced by navigating, so the gesture and the
             * navigation can no longer be confused for one another. */
            web.evaluateJavascript(
                    "window.dispatchEvent(new KeyboardEvent('keydown',"
                            + "{key:'Escape',bubbles:true}));", null);
            return true;
        }
        return super.onKeyUp(keyCode, event);
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
