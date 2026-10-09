package cc.polychrome.bighome;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

/**
 * Reminders and care, also while nobody looks at the phone: medication reminders ("Erinnerungen", with a big
 * "Genommen"), and, only with "Erweiterte Betreuung" switched on, the things that contact the family by themselves:
 * the daily "Mir geht's gut" check-in and its deadline, a note when a reminder wasn't confirmed, and the SOS.
 *
 * Everything is read from the page's own config.json (people, "meds", "care"), so there is one source of truth.
 * One alarm is kept for the next thing due; when it fires, everything due is handled and the next alarm is set.
 */
public class Care {
    static final String CH_MEDS = "meds", CH_CARE = "care";
    static final int NOTE_MED = 300, NOTE_INFO = 301;

    static SharedPreferences state(Context c) { return c.getSharedPreferences("care", Context.MODE_PRIVATE); }

    static JSONObject config(Context c) {
        File f = new File(c.getFilesDir(), "config.json");
        if (!f.exists()) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        } catch (Exception e) { return new JSONObject(); }
    }

    static String today() {
        Calendar k = Calendar.getInstance();
        return String.format(Locale.ROOT, "%04d-%02d-%02d", k.get(Calendar.YEAR), k.get(Calendar.MONTH) + 1, k.get(Calendar.DAY_OF_MONTH));
    }

    /** today at h:m, in milliseconds */
    static long at(int h, int m) {
        Calendar k = Calendar.getInstance();
        k.set(Calendar.HOUR_OF_DAY, h); k.set(Calendar.MINUTE, m); k.set(Calendar.SECOND, 0); k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    static String hm(int h, int m) { return h + ":" + (m < 10 ? "0" : "") + m; }

    /** the numbers of the chosen people ("to": a list of person ids) */
    static ArrayList<String> numbers(JSONObject cfg, JSONArray ids) {
        ArrayList<String> out = new ArrayList<>();
        if (ids == null) return out;
        JSONArray ppl = cfg.optJSONArray("people");
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i);
            for (int k = 0; ppl != null && k < ppl.length(); k++) {
                JSONObject p = ppl.optJSONObject(k);
                if (p != null && id.equals(p.optString("id")) && !p.optString("number").isEmpty()) out.add(p.optString("number"));
            }
        }
        return out;
    }

    static String owner(JSONObject cfg) {
        String n = cfg.optJSONObject("care") == null ? "" : cfg.optJSONObject("care").optString("name", "");
        return n.isEmpty() ? "Ihr Familienmitglied" : n;
    }

    static boolean careOn(JSONObject cfg) { return cfg.optJSONObject("care") != null && cfg.optJSONObject("care").optBoolean("on"); }

    // ---------------------------------------------------------------- SMS

    @SuppressWarnings("deprecation")
    static boolean sms(Context c, ArrayList<String> to, String text) {
        if (to.isEmpty() || c.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            SmsManager sm = Build.VERSION.SDK_INT >= 31 ? c.getSystemService(SmsManager.class) : SmsManager.getDefault();
            for (String n : to) {
                ArrayList<String> parts = sm.divideMessage(text);
                sm.sendMultipartTextMessage(n, null, parts, null, null);
            }
            return true;
        } catch (Exception e) { return false; }
    }

    // ---------------------------------------------------------------- the one alarm

    /** after any change of the config, at boot, and after each alarm: set the alarm for the next thing due */
    static void schedule(Context c) {
        JSONObject cfg = config(c);
        SharedPreferences st = state(c);
        String day = today();
        long now = System.currentTimeMillis(), next = Long.MAX_VALUE;
        JSONArray meds = cfg.optJSONArray("meds");
        JSONObject care = cfg.optJSONObject("care");
        for (int i = 0; meds != null && i < meds.length(); i++) {
            JSONObject m = meds.optJSONObject(i); if (m == null) continue;
            long due = at(m.optInt("h"), m.optInt("m"));
            String id = m.optString("id");
            if (due > now) next = Math.min(next, due);
            else next = Math.min(next, due + 86400000L);   // tomorrow's
            boolean notify = careOn(cfg) && care.optJSONObject("medNotify") != null && care.optJSONObject("medNotify").optBoolean("on");
            if (notify && !st.getBoolean("taken_" + id + "_" + day, false) && !st.getBoolean("missed_" + id + "_" + day, false)) {
                long late = due + 60000L * Math.max(10, care.optJSONObject("medNotify").optInt("after", 60));
                if (late > now) next = Math.min(next, late);
            }
        }
        JSONObject ci = care == null ? null : care.optJSONObject("checkin");
        if (careOn(cfg) && ci != null && ci.optBoolean("on") && !st.getBoolean("checkin_" + day, false) && !st.getBoolean("ciwarn_" + day, false)) {
            long dl = at(ci.optInt("h", 12), ci.optInt("m", 0));
            if (dl > now) next = Math.min(next, dl);
        }
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 7, new Intent(c, Alarm.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        if (next == Long.MAX_VALUE) return;
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
    }

    /** what is due now: reminders to show, notes to send */
    static void run(Context c) {
        JSONObject cfg = config(c);
        SharedPreferences st = state(c);
        SharedPreferences.Editor ed = st.edit();
        String day = today();
        long now = System.currentTimeMillis();
        JSONArray meds = cfg.optJSONArray("meds");
        JSONObject care = cfg.optJSONObject("care");
        for (int i = 0; meds != null && i < meds.length(); i++) {
            JSONObject m = meds.optJSONObject(i); if (m == null) continue;
            String id = m.optString("id"), text = m.optString("text", "Tabletten nehmen");
            long due = at(m.optInt("h"), m.optInt("m"));
            if (now >= due - 30000 && !st.getBoolean("shown_" + id + "_" + day, false) && !st.getBoolean("taken_" + id + "_" + day, false)) {
                ed.putBoolean("shown_" + id + "_" + day, true);
                medNote(c, text, hm(m.optInt("h"), m.optInt("m")));
            }
            JSONObject mn = care == null ? null : care.optJSONObject("medNotify");
            if (careOn(cfg) && mn != null && mn.optBoolean("on") && !st.getBoolean("taken_" + id + "_" + day, false)
                    && !st.getBoolean("missed_" + id + "_" + day, false)) {
                int after = Math.max(10, mn.optInt("after", 60));
                if (now >= due + 60000L * after - 30000) {
                    ed.putBoolean("missed_" + id + "_" + day, true);
                    sms(c, numbers(cfg, mn.optJSONArray("to")), "Hinweis von Großer Start: " + owner(cfg) + " hat die Erinnerung „" + text + "“ um "
                        + hm(m.optInt("h"), m.optInt("m")) + " bis jetzt nicht bestätigt.");
                }
            }
        }
        JSONObject ci = care == null ? null : care.optJSONObject("checkin");
        if (careOn(cfg) && ci != null && ci.optBoolean("on") && !st.getBoolean("checkin_" + day, false) && !st.getBoolean("ciwarn_" + day, false)
                && now >= at(ci.optInt("h", 12), ci.optInt("m", 0)) - 30000) {
            ed.putBoolean("ciwarn_" + day, true);
            sms(c, numbers(cfg, ci.optJSONArray("to")), "Hinweis von Großer Start: " + owner(cfg) + " hat heute bis "
                + hm(ci.optInt("h", 12), ci.optInt("m", 0)) + " noch nicht „Mir geht's gut“ gedrückt. Bitte kurz melden.");
        }
        ed.apply();
        schedule(c);
        MainActivity.poke();
    }

    /** reminders due today and not yet confirmed (for the big card on the front page) */
    static JSONArray pending(Context c) {
        JSONArray out = new JSONArray();
        JSONObject cfg = config(c);
        SharedPreferences st = state(c);
        String day = today();
        long now = System.currentTimeMillis();
        JSONArray meds = cfg.optJSONArray("meds");
        for (int i = 0; meds != null && i < meds.length(); i++) {
            JSONObject m = meds.optJSONObject(i); if (m == null) continue;
            String id = m.optString("id");
            if (now >= at(m.optInt("h"), m.optInt("m")) && !st.getBoolean("taken_" + id + "_" + day, false))
                try { out.put(new JSONObject().put("id", id).put("text", m.optString("text", "Tabletten nehmen")).put("time", hm(m.optInt("h"), m.optInt("m")))); } catch (Exception ignored) { }
        }
        return out;
    }

    static void taken(Context c, String id) {
        state(c).edit().putBoolean("taken_" + id + "_" + today(), true).apply();
        if (pending(c).length() == 0) c.getSystemService(NotificationManager.class).cancel(NOTE_MED);
        schedule(c);
    }

    static boolean checkedIn(Context c) { return state(c).getBoolean("checkin_" + today(), false); }

    /** "Mir geht's gut": noted for today, and (if wanted) told to the chosen people */
    static boolean checkIn(Context c) {
        state(c).edit().putBoolean("checkin_" + today(), true).apply();
        JSONObject cfg = config(c);
        JSONObject ci = cfg.optJSONObject("care") == null ? null : cfg.optJSONObject("care").optJSONObject("checkin");
        boolean sent = true;
        if (careOn(cfg) && ci != null && ci.optBoolean("send", true))
            sent = sms(c, numbers(cfg, ci.optJSONArray("to")), owner(cfg) + ": Mir geht es gut. (" + hm(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), Calendar.getInstance().get(Calendar.MINUTE)) + ", Großer Start)");
        schedule(c);
        return sent;
    }

    // ---------------------------------------------------------------- SOS

    /** tells the chosen people right away, then sends where the phone is as soon as it is known (at most 40 s) */
    @SuppressWarnings({"deprecation", "MissingPermission"})
    static void sos(Context c) {
        JSONObject cfg = config(c);
        JSONObject s = cfg.optJSONObject("care") == null ? null : cfg.optJSONObject("care").optJSONObject("sos");
        if (!careOn(cfg) || s == null || !s.optBoolean("on")) return;
        ArrayList<String> to = numbers(cfg, s.optJSONArray("to"));
        String who = owner(cfg);
        sms(c, to, "NOTRUF von " + who + " (Großer Start): Bitte sofort melden! Der Standort folgt, sobald er bekannt ist.");
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            sms(c, to, "Der Standort von " + who + " ist nicht verfügbar (keine Erlaubnis).");
            return;
        }
        LocationManager lm = c.getSystemService(LocationManager.class);
        Handler h = new Handler(Looper.getMainLooper());
        final boolean[] done = { false };
        final Location[] best = { null };
        for (String p : new String[] { LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER }) {
            try { Location l = lm.getLastKnownLocation(p); if (l != null && (best[0] == null || l.getTime() > best[0].getTime())) best[0] = l; } catch (Exception ignored) { }
        }
        Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            Location l = best[0];
            if (l == null) sms(c, to, "Der Standort von " + who + " konnte nicht bestimmt werden.");
            else sms(c, to, "Standort von " + who + ": https://maps.google.com/?q=" + String.format(Locale.ROOT, "%.6f,%.6f", l.getLatitude(), l.getLongitude())
                + " (auf etwa " + Math.round(l.getAccuracy()) + " m genau)");
        };
        LocationListener ll = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                if (best[0] == null || l.getAccuracy() < best[0].getAccuracy() || l.getTime() > best[0].getTime() + 60000) best[0] = l;
                if (l.getAccuracy() <= 50) { lm.removeUpdates(this); finish.run(); }
            }
            @Override public void onStatusChanged(String p, int st, Bundle b) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        };
        try {
            for (String p : lm.getProviders(true)) if (!LocationManager.PASSIVE_PROVIDER.equals(p)) lm.requestLocationUpdates(p, 1000, 0, ll, Looper.getMainLooper());
        } catch (Exception ignored) { }
        h.postDelayed(() -> { try { lm.removeUpdates(ll); } catch (Exception ignored) { } finish.run(); }, 40000);
    }

    // ---------------------------------------------------------------- notifications

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH_MEDS, "Erinnerungen", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Erinnerungen wie „Tabletten nehmen“");
        ch.enableVibration(true);
        ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        nm.createNotificationChannel(ch);
    }

    @SuppressWarnings("deprecation")
    static void medNote(Context c, String text, String time) {
        channels(c);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 8, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH_MEDS) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(text).setContentText("Erinnerung für " + time + " Uhr. Zum Bestätigen tippen.")
            .setContentIntent(pi).setAutoCancel(true).setCategory(Notification.CATEGORY_ALARM).setPriority(Notification.PRIORITY_MAX)
            .setFullScreenIntent(pi, true);
        if (Build.VERSION.SDK_INT < 26) b.setDefaults(Notification.DEFAULT_ALL);
        try { c.getSystemService(NotificationManager.class).notify(NOTE_MED, b.build()); } catch (Exception ignored) { }   // no notification permission
    }

    /** the alarm: handle what's due, set the next one */
    public static class Alarm extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            PendingResult r = goAsync();
            new Thread(() -> { try { run(c); } finally { r.finish(); } }).start();
        }
    }

    /** after a restart of the phone (and after an update of the app) the next alarm is set again */
    public static class Boot extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) { schedule(c); }
    }
}
