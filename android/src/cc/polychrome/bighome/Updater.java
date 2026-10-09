package cc.polychrome.bighome;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Keeps Großer Start up to date from its GitHub releases. Every release carries big-home.json
 * ({"versionCode": 7, "versionName": "1.6.0"}) next to Grosser-Start.apk.
 *
 * Nobody using the phone is ever asked anything or sees anything: when the screen goes off (at most every hour) the
 * app looks for a newer version and installs it right away, quietly, while the phone is dark. Android allows that from
 * Android 12 on, once the app is its own installer: the settings' "Automatische Updates einschalten" reinstalls the
 * app's own copy once (one tap by the family member) so that it is. Where Android wants a confirmation (the first update, older phones, "Install unknown apps" not allowed yet),
 * the update waits in the settings for the family member ("Updates"). Android only accepts an update signed with the
 * same key, so nothing else can be slipped in.
 */
class Updater {
    static final String BASE = "https://github.com/booskers/big-home/releases/latest/download/";
    static final String INFO = BASE + "big-home.json", APK = BASE + "Grosser-Start.apk";
    static final String DONE = "cc.polychrome.bighome.INSTALLED";
    static final long EVERY = 60 * 60 * 1000L;   // every hour at most

    interface Listener { void changed(); }

    final Activity a;
    final SharedPreferences prefs;
    final Listener listener;
    volatile boolean busy;
    volatile String status = "";   // "", "checking", "downloading", "failed", "offline", "needsOk"
    volatile long newCode = -1;
    volatile String newName = "";

    Updater(Activity a, Listener l) {
        this.a = a; this.listener = l;
        prefs = a.getSharedPreferences("updates", Context.MODE_PRIVATE);
        newCode = prefs.getLong("newCode", -1); newName = prefs.getString("newName", "");
    }

    @SuppressWarnings("deprecation")
    long myVersion() {
        try {
            android.content.pm.PackageInfo p = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) { return Long.MAX_VALUE; }
    }

    String myName() {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    /** true when Android counts this app as its own installer, so updates may go in without anyone asked */
    boolean ownInstaller() {
        if (Build.VERSION.SDK_INT < 31) return false;
        try { return a.getPackageName().equals(a.getPackageManager().getInstallSourceInfo(a.getPackageName()).getInstallingPackageName()); }
        catch (Exception e) { return false; }
    }

    /** "Automatische Updates einschalten": install this very app over itself once (Android shows one confirmation), which
     *  makes it its own installer; from then on updates need nobody */
    void becomeInstaller() {
        if (busy) return;
        if (!canInstall()) { a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName()))); return; }
        busy = true;
        set("downloading");
        new Thread(() -> {
            File me = new File(a.getApplicationInfo().sourceDir);
            try (InputStream in = new FileInputStream(me)) { session(in, me.length(), true); }
            catch (Exception e) { busy = false; set("failed"); }
        }).start();
    }

    boolean available() { return newCode > myVersion(); }
    boolean canInstall() { return Build.VERSION.SDK_INT < 26 || a.getPackageManager().canRequestPackageInstalls(); }

    /** what the settings page shows */
    String info() {
        try {
            return new JSONObject().put("current", myName()).put("available", available()).put("name", newName)
                .put("status", status).put("canInstall", canInstall()).put("quiet", Build.VERSION.SDK_INT >= 31)
                .put("checked", prefs.getLong("checked", 0)).put("auto", ownInstaller()).toString();
        } catch (Exception e) { return "{}"; }
    }

    void set(String s) { status = s; listener.changed(); }

    /** quietly (the screen just went off): look every few hours, and install a newer version without asking if Android
     *  lets us. By hand (the settings' "Nach Updates suchen"): look now. */
    void check(boolean byHand) {
        if (busy) return;
        if (!byHand && System.currentTimeMillis() - prefs.getLong("checked", 0) < EVERY) {
            if (available() && !"needsOk".equals(status)) install(false);
            return;
        }
        busy = true;
        if (byHand) set("checking");
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(new String(fetch(INFO + "?t=" + System.currentTimeMillis()), "UTF-8"));
                newCode = j.getLong("versionCode"); newName = j.optString("versionName", "");
                prefs.edit().putLong("checked", System.currentTimeMillis()).putLong("newCode", newCode).putString("newName", newName).apply();
                busy = false;
                set("");
                if (available() && !byHand) install(false);
            } catch (Exception e) {   // offline, or GitHub unreachable: quietly try again next time
                busy = false;
                set(byHand ? "offline" : "");
            }
        }).start();
    }

    /** `byHand`: from the settings, where Android's confirmation screen may be shown. Otherwise only a quiet install. */
    void install(boolean byHand) {
        if (busy || !available()) return;
        if (!canInstall()) {
            if (byHand) a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            else set("needsOk");
            return;
        }
        if (!byHand && !ownInstaller()) { set("needsOk"); return; }   // Android would ask: it waits for the family member
        busy = true;
        set("downloading");
        new Thread(() -> {
            try {
                HttpURLConnection c = open(APK);
                try (InputStream in = c.getInputStream()) { session(in, c.getContentLengthLong(), byHand); }
            } catch (Exception e) {
                busy = false;
                set(byHand ? "failed" : "");
            }
        }).start();
    }

    /** hand the APK in `in` to Android's installer; quietly if allowed */
    void session(InputStream in, long total, boolean byHand) throws Exception {
        PackageInstaller pi = a.getPackageManager().getPackageInstaller();
        int id = -1;
        try {
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(a.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            if (total > 0) sp.setSize(total);
            id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (OutputStream out = s.openWrite("update.apk", 0, total)) {
                    byte[] buf = new byte[64 * 1024]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
                listen(byHand);
                Intent done = new Intent(DONE).setPackage(a.getPackageName());
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                s.commit(PendingIntent.getBroadcast(a, 41, done, flags).getIntentSender());
            }
        } catch (Exception e) {
            if (id >= 0) try { pi.abandonSession(id); } catch (Exception ignored) { }
            throw e;
        }
    }

    BroadcastReceiver receiver;
    boolean showConfirm;
    // what the installer says: it needs a person's OK, it worked (the app restarts by itself), or it failed
    void listen(boolean byHand) {
        showConfirm = byHand;
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            @SuppressWarnings("deprecation")
            public void onReceive(Context ctx, Intent in) {
                busy = false;
                int st = in.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = in.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (showConfirm && confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); a.startActivity(confirm); set(""); }
                    else set("needsOk");   // not in front of the person using the phone: it waits in the settings
                } else if (st == PackageInstaller.STATUS_SUCCESS) set("");
                else set(st == PackageInstaller.STATUS_FAILURE_ABORTED ? "" : "failed");
            }
        };
        IntentFilter f = new IntentFilter(DONE);
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else a.registerReceiver(receiver, f);
    }

    void stop() { if (receiver != null) { try { a.unregisterReceiver(receiver); } catch (Exception ignored) { } receiver = null; } }

    static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);   // GitHub sends release files on to its download host (https to https)
        c.setRequestProperty("User-Agent", "GrosserStart");
        c.setUseCaches(false);
        c.setRequestProperty("Cache-Control", "no-cache");
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        return c;
    }

    static byte[] fetch(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0 && b.size() < 64 * 1024) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }
}
