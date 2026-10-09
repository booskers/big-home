package cc.polychrome.bighome;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import android.telecom.TelecomManager;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * "Großer Anruf-Bildschirm" (optional): Großer Start as the phone's call app, with a very large, simple screen.
 * Android (Telecom) still rings and vibrates; this shows who is calling, with a huge "Annehmen" and "Ablehnen", and
 * during a call a huge "Auflegen", speaker, mute and a number pad. Dial is the keypad Android expects a call app to
 * have. Switched on and off in the settings ("Anrufe").
 */
public class Phone {
    static final String CH_CALL = "calls";
    static final int NOTE_CALL = 400;

    static int dp(Context c, float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics())); }

    static String digits(String n) { String d = n == null ? "" : n.replaceAll("\\D", ""); return d.length() > 8 ? d.substring(d.length() - 8) : d; }

    /** name and picture for a number: the family first (as on the tiles), then the phone's contacts */
    static Object[] who(Context c, String number) {
        String d = digits(number);
        JSONArray ppl = Care.config(c).optJSONArray("people");
        for (int i = 0; ppl != null && i < ppl.length() && !d.isEmpty(); i++) {
            JSONObject p = ppl.optJSONObject(i);
            if (p != null && d.equals(digits(p.optString("number")))) {
                Bitmap b = null;
                String ph = p.optString("photo");
                if (ph.startsWith("data:")) try { byte[] x = Base64.decode(ph.substring(ph.indexOf(',') + 1), Base64.DEFAULT); b = BitmapFactory.decodeByteArray(x, 0, x.length); } catch (Exception ignored) { }
                return new Object[] { p.optString("name"), b };
            }
        }
        if (number != null && !number.isEmpty() && c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            Uri q = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            try (Cursor cur = c.getContentResolver().query(q, new String[] { ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI }, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    Bitmap b = null;
                    if (cur.getString(1) != null) try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(cur.getString(1)))) { b = BitmapFactory.decodeStream(in); } catch (Exception ignored) { }
                    return new Object[] { cur.getString(0), b };
                }
            } catch (Exception ignored) { }
        }
        return new Object[] { number == null || number.isEmpty() ? "Unbekannt" : number, null };
    }

    static String number(Call call) {
        Uri h = call.getDetails().getHandle();
        return h == null ? "" : h.getSchemeSpecificPart();
    }

    @SuppressWarnings("deprecation")
    static int state(Call c) { return Build.VERSION.SDK_INT >= 31 ? c.getDetails().getState() : c.getState(); }

    // ------------------------------------------------------------------ the service Android talks to

    public static class Service extends InCallService {
        static Service me;
        static final List<Call> calls = new ArrayList<>();
        static Runnable onChange;

        final Call.Callback cb = new Call.Callback() {
            @Override public void onStateChanged(Call call, int st) { changed(); }
        };

        static Call current() {
            Call ring = null, live = null;
            for (Call c : calls) {
                int s = state(c);
                if (s == Call.STATE_RINGING) ring = c;
                else if (s == Call.STATE_ACTIVE || s == Call.STATE_DIALING || s == Call.STATE_CONNECTING || s == Call.STATE_HOLDING) live = live == null || s == Call.STATE_ACTIVE ? c : live;
            }
            return ring != null ? ring : live;
        }

        @Override public void onCreate() { super.onCreate(); me = this; }
        @Override public void onDestroy() { me = null; super.onDestroy(); }

        @Override
        public void onCallAdded(Call call) {
            calls.add(call);
            call.registerCallback(cb);
            note();
            Intent i = new Intent(this, Screen.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try { startActivity(i); } catch (Exception ignored) { }   // when locked, the notification's full-screen intent shows it
            changed();
        }

        @Override
        public void onCallRemoved(Call call) {
            call.unregisterCallback(cb);
            calls.remove(call);
            changed();
        }

        @Override public void onCallAudioStateChanged(CallAudioState s) { changed(); }

        void changed() {
            note();
            Runnable r = onChange; if (r != null) new Handler(Looper.getMainLooper()).post(r);
        }

        @SuppressWarnings("deprecation")
        void note() {
            NotificationManager nm = getSystemService(NotificationManager.class);
            Call c = current();
            if (c == null) { nm.cancel(NOTE_CALL); return; }
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_CALL) == null) {
                NotificationChannel ch = new NotificationChannel(CH_CALL, "Anrufe", NotificationManager.IMPORTANCE_HIGH);
                ch.setSound(null, null);   // Android rings by itself
                nm.createNotificationChannel(ch);
            }
            Intent i = new Intent(this, Screen.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 9, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            boolean ringing = state(c) == Call.STATE_RINGING;
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_CALL) : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.sym_call_incoming).setOngoing(true).setContentIntent(pi).setCategory(Notification.CATEGORY_CALL)
                .setContentTitle((String) who(this, number(c))[0]).setContentText(ringing ? "Ruft an – zum Annehmen tippen" : "Anruf läuft – zum Öffnen tippen")
                .setPriority(Notification.PRIORITY_MAX);
            if (ringing) b.setFullScreenIntent(pi, true);
            try { nm.notify(NOTE_CALL, b.build()); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ the big call screen

    public static class Screen extends Activity {
        final Handler h = new Handler(Looper.getMainLooper());
        LinearLayout root, buttons;
        ImageView pic;
        TextView name, status;
        PowerManager.WakeLock prox;
        String shownNumber = null;
        boolean keypad;
        long since;

        final Runnable tick = new Runnable() { @Override public void run() { update(); h.postDelayed(this, 1000); } };

        @Override
        @SuppressWarnings("deprecation")
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
            else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().setStatusBarColor(Color.rgb(13, 27, 42));
            getWindow().setNavigationBarColor(Color.rgb(13, 27, 42));
            root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setBackgroundColor(Color.rgb(13, 27, 42));
            int p = dp(this, 20);
            root.setPadding(p, dp(this, 36), p, p);
            pic = new ImageView(this);
            int s = dp(this, 150);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
            pic.setLayoutParams(lp);
            pic.setScaleType(ImageView.ScaleType.CENTER_CROP);
            pic.setClipToOutline(true);
            pic.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); } });
            root.addView(pic);
            name = text(46, true); name.setPadding(0, dp(this, 16), 0, 0); root.addView(name);
            status = text(26, false); status.setPadding(0, dp(this, 6), 0, dp(this, 20)); root.addView(status);
            buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.VERTICAL);
            root.addView(buttons, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            setContentView(root);
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
                prox = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "grosserstart:call");
            Service.onChange = this::update;
            update();
        }

        @Override protected void onResume() { super.onResume(); h.post(tick); }
        @Override protected void onPause() { h.removeCallbacks(tick); super.onPause(); }
        @Override protected void onDestroy() { if (Service.onChange != null) Service.onChange = null; if (prox != null && prox.isHeld()) prox.release(); super.onDestroy(); }
        // Back does not end or hide a call by accident
        @Override @SuppressWarnings("deprecation") public void onBackPressed() { }

        TextView text(int sp, boolean bold) {
            TextView t = new TextView(this);
            t.setTextColor(Color.WHITE); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setGravity(Gravity.CENTER);
            if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
            return t;
        }

        Button big(String label, int color, int heightDp, View.OnClickListener fn) {
            Button b = new Button(this);
            b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(this, 34));
            b.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, heightDp));
            lp.topMargin = dp(this, 14);
            b.setLayoutParams(lp);
            b.setOnClickListener(fn);
            return b;
        }

        String lastLayout = "";
        void update() {
            Call c = Service.current();
            if (c == null) { if (prox != null && prox.isHeld()) prox.release(); finish(); return; }
            String num = number(c);
            if (!num.equals(shownNumber)) {
                shownNumber = num;
                Object[] w = who(this, num);
                name.setText((String) w[0]);
                if (w[1] != null) { pic.setImageBitmap((Bitmap) w[1]); pic.setBackground(null); }
                else {
                    pic.setImageDrawable(null);
                    GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(Color.rgb(53, 105, 184)); pic.setBackground(g);
                }
            }
            int st = state(c);
            boolean ringing = st == Call.STATE_RINGING, active = st == Call.STATE_ACTIVE;
            if (active && since == 0) since = System.currentTimeMillis();
            if (ringing) status.setText("ruft an …");
            else if (st == Call.STATE_DIALING || st == Call.STATE_CONNECTING) status.setText("Verbinde …");
            else if (st == Call.STATE_HOLDING) status.setText("Gehalten");
            else if (active) { long s = (System.currentTimeMillis() - since) / 1000; status.setText("Im Gespräch  " + (s / 60) + ":" + String.format("%02d", s % 60)); }
            // the screen goes dark at the ear during a call (not while it rings)
            if (prox != null) { if (!ringing && !prox.isHeld()) prox.acquire(60 * 60 * 1000L); else if (ringing && prox.isHeld()) prox.release(); }
            CallAudioState a = Service.me == null ? null : Service.me.getCallAudioState();
            boolean speaker = a != null && a.getRoute() == CallAudioState.ROUTE_SPEAKER, muted = a != null && a.isMuted();
            String layout = ringing + "|" + speaker + "|" + muted + "|" + keypad;
            if (layout.equals(lastLayout)) return;
            lastLayout = layout;
            buttons.removeAllViews();
            if (ringing) {
                buttons.addView(big("Annehmen", Color.rgb(16, 122, 62), 150, v -> c.answer(0)));
                buttons.addView(big("Ablehnen", Color.rgb(200, 30, 45), 110, v -> c.reject(false, null)));
                return;
            }
            if (keypad) {
                GridLayout g = new GridLayout(this); g.setColumnCount(3);
                for (String k : new String[] { "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#" }) {
                    Button b = new Button(this); b.setText(k); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30); b.setTextColor(Color.WHITE);
                    GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.argb(40, 255, 255, 255)); bg.setCornerRadius(dp(this, 20)); b.setBackground(bg);
                    GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
                    lp.width = 0; lp.height = dp(this, 64); lp.setMargins(dp(this, 5), dp(this, 5), dp(this, 5), dp(this, 5));
                    b.setLayoutParams(lp);
                    b.setOnClickListener(v -> { c.playDtmfTone(k.charAt(0)); h.postDelayed(c::stopDtmfTone, 200); });
                    g.addView(b);
                }
                buttons.addView(g);
                buttons.addView(big("Tasten schließen", Color.argb(46, 255, 255, 255), 72, v -> { keypad = false; update(); }));
            } else {
                buttons.addView(big(speaker ? "Lautsprecher aus" : "Lautsprecher", speaker ? Color.rgb(30, 80, 190) : Color.argb(46, 255, 255, 255), 84,
                    v -> { if (Service.me != null) Service.me.setAudioRoute(speaker ? CallAudioState.ROUTE_WIRED_OR_EARPIECE : CallAudioState.ROUTE_SPEAKER); }));
                buttons.addView(big(muted ? "Mikrofon an" : "Stumm", muted ? Color.rgb(176, 80, 12) : Color.argb(46, 255, 255, 255), 84,
                    v -> { if (Service.me != null) Service.me.setMuted(!muted); }));
                buttons.addView(big("Tasten", Color.argb(46, 255, 255, 255), 72, v -> { keypad = true; update(); }));
            }
            View gap = new View(this); buttons.addView(gap, new LinearLayout.LayoutParams(1, 0, 1));
            buttons.addView(big("Auflegen", Color.rgb(200, 30, 45), 130, v -> c.disconnect()));
        }
    }

    // ------------------------------------------------------------------ the keypad (Android's "Wählen")

    public static class Dial extends Activity {
        TextView shown;
        final StringBuilder num = new StringBuilder();

        @Override
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            getWindow().setStatusBarColor(Color.rgb(13, 27, 42));
            getWindow().setNavigationBarColor(Color.rgb(13, 27, 42));
            Uri d = getIntent() == null ? null : getIntent().getData();
            if (d != null && "tel".equals(d.getScheme())) num.append(d.getSchemeSpecificPart());
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Color.rgb(13, 27, 42));
            int p = dp(this, 16);
            root.setPadding(p, dp(this, 30), p, p);
            shown = new TextView(this);
            shown.setTextColor(Color.WHITE); shown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40); shown.setGravity(Gravity.CENTER);
            shown.setTypeface(Typeface.DEFAULT_BOLD); shown.setMinHeight(dp(this, 80));
            root.addView(shown);
            GridLayout g = new GridLayout(this); g.setColumnCount(3);
            for (String k : new String[] { "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#" }) {
                Button bt = new Button(this); bt.setText(k); bt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36); bt.setTextColor(Color.WHITE);
                GradientDrawable bg = new GradientDrawable(); bg.setColor(Color.argb(40, 255, 255, 255)); bg.setCornerRadius(dp(this, 24)); bt.setBackground(bg);
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
                lp.width = 0; lp.height = dp(this, 76); lp.setMargins(dp(this, 6), dp(this, 6), dp(this, 6), dp(this, 6));
                bt.setLayoutParams(lp);
                bt.setOnClickListener(v -> { num.append(k); show(); });
                g.addView(bt);
            }
            root.addView(g);
            LinearLayout row = new LinearLayout(this);
            row.addView(btn("Löschen", Color.argb(46, 255, 255, 255), v -> { if (num.length() > 0) num.setLength(num.length() - 1); show(); }), new LinearLayout.LayoutParams(0, dp(this, 76), 1));
            row.addView(btn("Schließen", Color.argb(46, 255, 255, 255), v -> finish()), new LinearLayout.LayoutParams(0, dp(this, 76), 1));
            root.addView(row);
            Button call = btn("Anrufen", Color.rgb(16, 122, 62), v -> {
                if (num.length() == 0) return;
                Uri u = Uri.fromParts("tel", num.toString(), null);
                try {
                    if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) getSystemService(TelecomManager.class).placeCall(u, null);
                    else startActivity(new Intent(Intent.ACTION_CALL, u));
                } catch (Exception ignored) { }
                finish();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(this, 110));
            clp.topMargin = dp(this, 14);
            root.addView(call, clp);
            setContentView(root);
            show();
        }

        Button btn(String label, int color, View.OnClickListener fn) {
            Button b = new Button(this);
            b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26); b.setTypeface(Typeface.DEFAULT_BOLD);
            GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(this, 30)); b.setBackground(g);
            b.setOnClickListener(fn);
            return b;
        }

        void show() { shown.setText(num.length() == 0 ? "Nummer eingeben" : num); }
    }
}
