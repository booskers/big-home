package cc.polychrome.bighome;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Großer Start on top of WhatsApp, without ever talking to WhatsApp's servers (so the account is never at risk):
 * - calls out through WhatsApp's own "voice call" / "video call" rows in the phone's contacts;
 * - "WhatsApp-Anrufe groß anzeigen": an incoming WhatsApp call (seen as WhatsApp's notification, see Notes) gets the same
 *   big screen as a phone call, and its "Annehmen" / "Ablehnen" press the notification's own buttons; during the call a
 *   big "Auflegen" bar floats on top and presses the call notification's hang-up button;
 * - "WhatsApp-Nachrichten groß": new messages for the front page, answered through the notification's reply field.
 * WhatsApp's own call screen still shows the call itself; nothing here can or does replace it.
 */
public class WhatsApp {
    static final String MIME_VOICE = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call";
    static final String MIME_VIDEO = "vnd.android.cursor.item/vnd.com.whatsapp.video.call";

    // ------------------------------------------------------------------ calling out

    /** WhatsApp's contacts row for calling this number, or -1 (not in the contacts, or not on WhatsApp) */
    static long callRow(Context c, String number, boolean video) {
        String d = Phone.digits(number);
        if (d.isEmpty() || c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return -1;
        try (Cursor cur = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                new String[] { ContactsContract.Data._ID, ContactsContract.Data.DATA1, ContactsContract.Data.DATA3 },
                ContactsContract.Data.MIMETYPE + "=?", new String[] { video ? MIME_VIDEO : MIME_VOICE }, null)) {
            while (cur != null && cur.moveToNext()) {
                // DATA1 is "4917…@s.whatsapp.net"; DATA3 is a label with the number ("Sprachanruf an +49 …") in some versions
                String jid = cur.getString(1), label = cur.getString(2);
                String a = Phone.digits(jid == null ? "" : jid.split("@")[0]), b = Phone.digits(label);
                if (d.equals(a) || (!b.isEmpty() && d.equals(b))) return cur.getLong(0);
            }
        } catch (Exception ignored) { }
        return -1;
    }

    /** a WhatsApp voice or video call to this number, straight away; false when WhatsApp has no such row for it */
    static boolean call(Activity a, String pkg, String number, boolean video) {
        long id = callRow(a, number, video);
        if (id < 0 || pkg == null) return false;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id), video ? MIME_VIDEO : MIME_VOICE);
        i.setPackage(pkg);
        try { a.startActivity(i); return true; } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------------ reading WhatsApp's notifications

    static boolean isCall(Notification n) {
        return Notification.CATEGORY_CALL.equals(n.category) || n.extras.containsKey("android.callType");
    }

    /** 1 = ringing, 2 = in a call */
    static int callType(Notification n) {
        int t = n.extras.getInt("android.callType", 0);   // Notification.CallStyle (Android 12+)
        if (t == 1 || t == 2) return t;
        if (find(n, "answer") != null) return 1;
        if (find(n, "hangup") != null) return 2;
        return n.fullScreenIntent != null ? 1 : 2;
    }

    static final String[] ANSWER = { "annehmen", "abheben", "answer", "accept", "rangehen" };
    static final String[] DECLINE = { "ablehnen", "decline", "reject", "ignorieren" };
    static final String[] HANGUP = { "auflegen", "beenden", "hang up", "end call", "end" };

    /** the notification's own button for answer / decline / hangup: CallStyle's fields first, then by its label */
    static PendingIntent find(Notification n, String kind) {
        String key = kind.equals("answer") ? "android.answerIntent" : kind.equals("decline") ? "android.declineIntent" : "android.hangUpIntent";
        Parcelable p = n.extras.getParcelable(key);
        if (p instanceof PendingIntent) return (PendingIntent) p;
        String[] words = kind.equals("answer") ? ANSWER : kind.equals("decline") ? DECLINE : HANGUP;
        if (n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            String t = a.title == null ? "" : a.title.toString().toLowerCase(Locale.ROOT).trim();
            for (String w : words) if (t.equals(w) || t.startsWith(w + " ") || (w.length() > 4 && t.contains(w))) return a.actionIntent;
        }
        return null;
    }

    static boolean isVideo(Notification n) {
        String t = (String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT, "")) + " " + n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT, "")).toLowerCase(Locale.ROOT);
        return t.contains("video");
    }

    static String caller(Notification n) {
        return String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE, "")).trim();
    }

    /** presses a button of another app's notification; on Android 14+ this app lends its right to open a screen */
    static boolean fire(Context c, PendingIntent pi, Intent fill) {
        if (pi == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                pi.send(c, 0, fill, null, null, null, o.toBundle());
            } else pi.send(c, 0, fill);
            return true;
        } catch (Exception e) { return false; }
    }

    /** the family member for a WhatsApp name (as on the tile, or as in the contacts), or null */
    static JSONObject family(Context c, String who) {
        String w = Notes.norm(who);
        if (w.isEmpty()) return null;
        JSONArray ppl = Care.config(c).optJSONArray("people");
        for (int i = 0; ppl != null && i < ppl.length(); i++) {
            JSONObject p = ppl.optJSONObject(i);
            if (p != null && (w.equals(Notes.norm(p.optString("name"))) || w.equals(Notes.norm(p.optString("contact"))))) return p;
        }
        return null;
    }

    static boolean overlayAllowed(Context c) { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c); }

    // ------------------------------------------------------------------ calls coming in (from Notes)

    /** the WhatsApp call shown right now, as WhatsApp's notification has it */
    static volatile StatusBarNotification ring, live;
    static final Handler main = new Handler(Looper.getMainLooper());
    static Runnable onRing;   // the open ring screen, told about changes

    static void posted(Context c, StatusBarNotification sbn) {
        JSONObject cfg = Care.config(c);
        if (!cfg.optBoolean("waCalls")) return;
        Notification n = sbn.getNotification();
        if (callType(n) == 1) {
            boolean fresh = ring == null || !ring.getKey().equals(sbn.getKey());
            ring = sbn;
            if (live != null && live.getKey().equals(sbn.getKey())) live = null;
            if (fresh) {
                showRing(c);
                // WhatsApp opens its own ringing screen too; ours goes back on top once it is up
                main.postDelayed(() -> { if (ring != null && ring.getKey().equals(sbn.getKey())) showRing(c); }, 1300);
            } else tell();
        } else {
            if (ring != null && ring.getKey().equals(sbn.getKey())) ring = null;
            live = sbn;
            tell();
            main.post(() -> Bar.show(c));
        }
    }

    static void removed(Context c, StatusBarNotification sbn) {
        boolean was = false;
        if (ring != null && ring.getKey().equals(sbn.getKey())) { ring = null; was = true; }
        if (live != null && live.getKey().equals(sbn.getKey())) { live = null; was = true; main.post(Bar::hide); }
        if (was) tell();
    }

    static void tell() { Runnable r = onRing; if (r != null) main.post(r); }

    static void showRing(Context c) {
        Intent i = new Intent(c, Ring.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { c.startActivity(i); } catch (Exception ignored) { }
    }

    static Bitmap picture(Context c, Notification n, JSONObject fam) {
        if (fam != null) {
            Object[] w = Phone.who(c, fam.optString("number"));
            if (w[1] != null) return (Bitmap) w[1];
        }
        try {
            Icon ic = Build.VERSION.SDK_INT >= 23 ? n.getLargeIcon() : null;
            Drawable d = ic == null ? null : ic.loadDrawable(c);
            if (d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
        } catch (Exception ignored) { }
        return null;
    }

    /** the big screen for a ringing WhatsApp call: photo, name, a huge "Annehmen" and "Ablehnen" */
    public static class Ring extends Activity {
        ImageView pic;
        TextView name, status;
        LinearLayout buttons;
        String shownKey;
        final Runnable auto = this::answer;

        @Override
        @SuppressWarnings("deprecation")
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
            else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            int night = Color.rgb(13, 27, 42);
            getWindow().setStatusBarColor(night); getWindow().setNavigationBarColor(night);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER_HORIZONTAL); root.setBackgroundColor(night);
            int p = Phone.dp(this, 20);
            root.setPadding(p, Phone.dp(this, 36), p, p);
            pic = new ImageView(this);
            int s = Phone.dp(this, 150);
            pic.setLayoutParams(new LinearLayout.LayoutParams(s, s));
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            pic.setClipToOutline(true);
            pic.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); } });
            root.addView(pic);
            name = Phone.label(this, 46, true); name.setPadding(0, Phone.dp(this, 16), 0, 0); root.addView(name);
            status = Phone.label(this, 26, false); status.setPadding(0, Phone.dp(this, 6), 0, Phone.dp(this, 20)); root.addView(status);
            buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.VERTICAL);
            root.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            buttons.addView(Phone.bigButton(this, "Annehmen", Color.rgb(16, 122, 62), 150, v -> answer()));
            buttons.addView(Phone.bigButton(this, "Ablehnen", Color.rgb(200, 30, 45), 110, v -> decline()));
            setContentView(root);
            onRing = this::update;
            update();
        }

        @Override protected void onDestroy() { main.removeCallbacks(auto); if (onRing != null) onRing = null; super.onDestroy(); }
        @Override @SuppressWarnings("deprecation") public void onBackPressed() { }

        void update() {
            StatusBarNotification sbn = ring;
            if (sbn == null) { main.removeCallbacks(auto); finish(); return; }
            if (sbn.getKey().equals(shownKey)) return;
            shownKey = sbn.getKey();
            Notification n = sbn.getNotification();
            String who = caller(n);
            JSONObject fam = family(this, who);
            name.setText(fam != null ? fam.optString("name") : who.isEmpty() ? "WhatsApp" : who);
            Bitmap bm = picture(this, n, fam);
            if (bm != null) { pic.setImageBitmap(bm); pic.setBackground(null); }
            else {
                pic.setImageDrawable(null);
                GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(Color.rgb(37, 160, 90)); pic.setBackground(g);
            }
            String kind = isVideo(n) ? "WhatsApp-Videoanruf" : "WhatsApp-Anruf";
            main.removeCallbacks(auto);
            if (fam != null && Care.config(this).optBoolean("waAuto")) {
                status.setText(kind + "\nwird gleich angenommen …");
                main.postDelayed(auto, 5000);
            } else status.setText(kind + "\nruft an …");
        }

        void answer() {
            main.removeCallbacks(auto);
            StatusBarNotification sbn = ring;
            if (sbn == null) { finish(); return; }
            Notification n = sbn.getNotification();
            if (!fire(this, find(n, "answer"), null)) {
                // no answer button found: WhatsApp's own ringing screen, where the green button is
                fire(this, n.fullScreenIntent != null ? n.fullScreenIntent : n.contentIntent, null);
                android.widget.Toast.makeText(this, "Bitte in WhatsApp auf den grünen Knopf tippen.", android.widget.Toast.LENGTH_LONG).show();
            }
            finish();
        }

        void decline() {
            main.removeCallbacks(auto);
            StatusBarNotification sbn = ring;
            if (sbn != null && !fire(this, find(sbn.getNotification(), "decline"), null)) {
                Notification n = sbn.getNotification();
                fire(this, n.fullScreenIntent != null ? n.fullScreenIntent : n.contentIntent, null);
                android.widget.Toast.makeText(this, "Bitte in WhatsApp auf den roten Knopf tippen.", android.widget.Toast.LENGTH_LONG).show();
            }
            finish();
        }
    }

    /** during a WhatsApp call: a big "Auflegen" bar at the top, above WhatsApp's own call screen */
    static class Bar {
        static android.widget.FrameLayout wrap;
        static LinearLayout view;
        static TextView label;
        static long since;
        static final Runnable tick = new Runnable() { @Override public void run() { text(); main.postDelayed(this, 1000); } };

        static void show(Context c) {
            if (!overlayAllowed(c) || live == null) return;
            Context app = c.getApplicationContext();
            if (view == null) {
                WindowManager wm = app.getSystemService(WindowManager.class);
                view = new LinearLayout(app);
                view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL);
                int p = Phone.dp(app, 12);
                view.setPadding(p + p, p, p, p);
                GradientDrawable g = new GradientDrawable(); g.setColor(Color.argb(235, 13, 27, 42)); g.setCornerRadius(Phone.dp(app, 32));
                view.setBackground(g);
                label = Phone.label(app, 24, true); label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                view.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button end = Phone.bigButton(app, "Auflegen", Color.rgb(200, 30, 45), 76, v -> hangUp(app));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Phone.dp(app, 170), Phone.dp(app, 76));
                end.setLayoutParams(lp);
                view.addView(end);
                WindowManager.LayoutParams wp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
                wp.gravity = Gravity.TOP;
                wp.y = statusBar(app) + Phone.dp(app, 10);
                // a window has no margins: the bar sits in a clear frame, a little in from the sides
                wrap = new android.widget.FrameLayout(app);
                int m = Phone.dp(app, 10);
                wrap.setPadding(m, 0, m, 0);
                wrap.addView(view);
                try { wm.addView(wrap, wp); } catch (Exception e) { view = null; wrap = null; return; }
                since = System.currentTimeMillis();
                main.post(tick);
            }
            text();
        }

        static int statusBar(Context c) {
            int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
            return id > 0 ? c.getResources().getDimensionPixelSize(id) : Phone.dp(c, 24);
        }

        static void text() {
            if (label == null) return;
            StatusBarNotification sbn = live;
            String who = sbn == null ? "" : caller(sbn.getNotification());
            JSONObject fam = sbn == null ? null : family(label.getContext(), who);
            long s = (System.currentTimeMillis() - since) / 1000;
            label.setText((fam != null ? fam.optString("name") : who.isEmpty() ? "WhatsApp" : who) + "\n" + (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60));
        }

        static void hangUp(Context c) {
            StatusBarNotification sbn = live;
            if (sbn == null) { hide(); return; }
            Notification n = sbn.getNotification();
            if (!fire(c, find(n, "hangup"), null)) {
                fire(c, n.contentIntent, null);
                android.widget.Toast.makeText(c, "Bitte in WhatsApp auf den roten Knopf tippen.", android.widget.Toast.LENGTH_LONG).show();
            }
        }

        static void hide() {
            main.removeCallbacks(tick);
            if (wrap == null) return;
            try { wrap.getContext().getSystemService(WindowManager.class).removeView(wrap); } catch (Exception ignored) { }
            wrap = null; view = null; label = null;
        }
    }

    // ------------------------------------------------------------------ messages, for the front page

    /** the new WhatsApp messages: [{key, who, text, time, reply, family}] (newest first), from the active notifications */
    static JSONArray messages(Context c, boolean familyOnly) {
        JSONArray out = new JSONArray();
        Notes s = Notes.me;
        if (s == null) return out;
        StatusBarNotification[] all;
        try { all = s.getActiveNotifications(); } catch (Exception e) { return out; }
        if (all == null) return out;
        java.util.Arrays.sort(all, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        for (StatusBarNotification sbn : all) {
            if (!Notes.isWhatsApp(sbn.getPackageName())) continue;
            Notification n = sbn.getNotification();
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 || isCall(n)) continue;
            Bundle x = n.extras;
            String who = String.valueOf(x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE, x.getCharSequence(Notification.EXTRA_TITLE, ""))).replaceAll("\\s*\\(\\d+[^)]*\\)\\s*$", "").trim();
            if (who.isEmpty()) continue;
            StringBuilder text = new StringBuilder();
            Parcelable[] msgs = x.getParcelableArray(Notification.EXTRA_MESSAGES);
            boolean group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION);
            if (msgs != null && msgs.length > 0) {
                for (int i = Math.max(0, msgs.length - 4); i < msgs.length; i++) {
                    if (!(msgs[i] instanceof Bundle)) continue;
                    Bundle m = (Bundle) msgs[i];
                    CharSequence t = m.getCharSequence("text"), from = m.getCharSequence("sender");
                    if (t == null) continue;
                    if (text.length() > 0) text.append('\n');
                    if (group && from != null) text.append(from).append(": ");
                    text.append(t);
                }
            }
            if (text.length() == 0) text.append(x.getCharSequence(Notification.EXTRA_BIG_TEXT, x.getCharSequence(Notification.EXTRA_TEXT, "")));
            if (text.length() == 0) continue;
            JSONObject fam = family(c, who);
            if (familyOnly && fam == null) continue;
            try {
                out.put(new JSONObject().put("key", sbn.getKey()).put("who", fam != null ? fam.optString("name") : who).put("id", fam != null ? fam.optString("id") : "")
                    .put("text", text.toString()).put("time", sbn.getPostTime()).put("reply", replyAction(n) != null).put("family", fam != null));
            } catch (Exception ignored) { }
        }
        return out;
    }

    static StatusBarNotification byKey(String key) {
        Notes s = Notes.me;
        if (s == null || key == null) return null;
        try {
            StatusBarNotification[] a = s.getActiveNotifications(new String[] { key });
            return a != null && a.length > 0 ? a[0] : null;
        } catch (Exception e) { return null; }
    }

    static Notification.Action replyAction(Notification n) {
        if (n.actions == null) return null;
        Notification.Action any = null;
        for (Notification.Action a : n.actions) {
            if (a.getRemoteInputs() == null || a.getRemoteInputs().length == 0) continue;
            if (Build.VERSION.SDK_INT >= 28 && a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY) return a;
            if (any == null) any = a;
        }
        return any;
    }

    /** the answer, sent by WhatsApp itself through the notification's reply field */
    static boolean reply(Context c, String key, String text) {
        StatusBarNotification sbn = byKey(key);
        if (sbn == null || text == null || text.trim().isEmpty()) return false;
        Notification.Action a = replyAction(sbn.getNotification());
        if (a == null) return false;
        RemoteInput[] in = a.getRemoteInputs();
        Bundle res = new Bundle();
        for (RemoteInput r : in) res.putCharSequence(r.getResultKey(), text.trim());
        Intent fill = new Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        RemoteInput.addResultsToIntent(in, fill, res);
        return fire(c, a.actionIntent, fill);
    }

    /** "Gelesen": WhatsApp's own "mark as read", or else the message is only put away here */
    static boolean markRead(Context c, String key) {
        StatusBarNotification sbn = byKey(key);
        if (sbn == null) return false;
        Notification n = sbn.getNotification();
        if (n.actions != null) for (Notification.Action a : n.actions) {
            String t = a.title == null ? "" : a.title.toString().toLowerCase(Locale.ROOT);
            boolean read = Build.VERSION.SDK_INT >= 28 && a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ;
            if (read || t.contains("gelesen") || t.contains("mark as read")) return fire(c, a.actionIntent, null);
        }
        try { Notes.me.cancelNotification(key); return true; } catch (Exception e) { return false; }
    }

    static boolean open(Context c, String key) {
        StatusBarNotification sbn = byKey(key);
        return sbn != null && fire(c, sbn.getNotification().contentIntent, null);
    }
}
