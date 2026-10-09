package cc.polychrome.bighome;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.Settings;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the phone's notifications (only once "Benachrichtigungen lesen" is allowed in Android's settings):
 * - how many unread WhatsApp messages each person has (the badges on the family tiles), by the sender's name;
 * - "Nachrichten vorlesen": a new message (WhatsApp or SMS) is spoken, from the family only if so chosen.
 * Nothing leaves the phone.
 */
public class Notes extends NotificationListenerService {
    static final String[] WHATSAPP = { "com.whatsapp", "com.whatsapp.w4b" };
    /** normalised sender name -> unread messages */
    static volatile Map<String, Integer> unread = new HashMap<>();
    static volatile boolean connected;

    TextToSpeech tts;
    volatile boolean ttsReady;
    String lastSpoken = "";

    static boolean enabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(new ComponentName(c, Notes.class).flattenToString());
    }

    static String norm(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s*\\(\\d+[^)]*\\)\\s*$", "");   // "Anna (2 Nachrichten)"
        return s.trim().toLowerCase(Locale.GERMANY);
    }

    @Override public void onListenerConnected() { connected = true; count(); }
    @Override public void onListenerDisconnected() { connected = false; }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        count();
        try { speak(sbn); } catch (Exception ignored) { }
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) { count(); }

    static boolean isWhatsApp(String pkg) { for (String p : WHATSAPP) if (p.equals(pkg)) return true; return false; }

    void count() {
        Map<String, Integer> m = new HashMap<>();
        StatusBarNotification[] all;
        try { all = getActiveNotifications(); } catch (Exception e) { return; }
        if (all == null) return;
        for (StatusBarNotification sbn : all) {
            if (!isWhatsApp(sbn.getPackageName())) continue;
            Notification n = sbn.getNotification();
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            Bundle x = n.extras;
            String who = norm(String.valueOf(x.getCharSequence(Notification.EXTRA_TITLE, "")));
            if (who.isEmpty()) continue;
            Parcelable[] msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES);
            int k = msgs != null && msgs.length > 0 ? msgs.length : 1;
            m.put(who, (m.containsKey(who) ? m.get(who) : 0) + k);
        }
        unread = m;
        MainActivity.poke();
    }

    /** a new message, spoken (if "Nachrichten vorlesen" is on) */
    void speak(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        String sms = Telephony.Sms.getDefaultSmsPackage(this);
        if (!isWhatsApp(pkg) && !pkg.equals(sms)) return;
        Notification n = sbn.getNotification();
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        JSONObject cfg = Care.config(this);
        if (!cfg.optBoolean("readAloud")) return;
        Bundle x = n.extras;
        String who = String.valueOf(x.getCharSequence(Notification.EXTRA_TITLE, "")).replaceAll("\\s*\\(\\d+[^)]*\\)\\s*$", "").trim();
        String text = String.valueOf(x.getCharSequence(Notification.EXTRA_TEXT, ""));
        Parcelable[] msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
            CharSequence t = ((Bundle) msgs[msgs.length - 1]).getCharSequence("text");
            if (t != null) text = t.toString();
        }
        if (who.isEmpty() || text.isEmpty()) return;
        // the family's names, as on the tiles and as in the contacts
        String name = null;
        JSONArray ppl = cfg.optJSONArray("people");
        for (int i = 0; ppl != null && i < ppl.length(); i++) {
            JSONObject p = ppl.optJSONObject(i);
            if (p == null) continue;
            if (norm(who).equals(norm(p.optString("name"))) || norm(who).equals(norm(p.optString("contact")))) { name = p.optString("name"); break; }
        }
        if (name == null && cfg.optBoolean("readFamilyOnly", true)) return;
        String say = "Nachricht von " + (name != null ? name : who) + ": " + text;
        if (say.equals(lastSpoken)) return;   // the same message updated again
        lastSpoken = say;
        if (tts == null) {
            tts = new TextToSpeech(this, st -> {
                if (st != TextToSpeech.SUCCESS) return;
                tts.setLanguage(Locale.GERMANY); tts.setSpeechRate(0.9f); ttsReady = true;
                tts.speak(lastSpoken, TextToSpeech.QUEUE_ADD, null, "msg");
            });
        } else if (ttsReady) tts.speak(say, TextToSpeech.QUEUE_ADD, null, "msg");
    }

    @Override
    public void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
