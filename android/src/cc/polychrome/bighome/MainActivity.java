package cc.polychrome.bighome;

import android.Manifest;
import android.app.Activity;
import android.app.WallpaperManager;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Großer Start: a home screen with a big clock, the battery, and the family as big picture tiles. A tile opens
 * WhatsApp, call and SMS for that person as three big buttons. The page is assets/index.html; this activity gives it
 * the phone (calls, contacts, apps, battery) through window.Android.
 *
 * As the phone's Home app it can't be left by pressing Back: Back on the front page does nothing, and Home always
 * comes back here.
 */
public class MainActivity extends Activity {
    static final String PAGE = "file:///android_asset/index.html";
    static final int REQ_CONTACT = 1, REQ_PHOTO = 2, REQ_PERMS = 3, REQ_ROLE = 4, REQ_BG = 5;
    static final String[] WHATSAPP = { "com.whatsapp", "com.whatsapp.w4b" };

    WebView web;
    TextToSpeech tts;
    volatile boolean ttsReady;
    BroadcastReceiver batteryRx;
    File cfg, bgFile;
    Updater up;
    CameraManager cams;
    String torchId;
    volatile boolean torchOn;
    final Handler main = new Handler(Looper.getMainLooper());
    final Runnable torchOff = () -> setTorch(false);
    BroadcastReceiver screenOff;
    volatile boolean bgToPhone = true;
    float inT, inR, inB, inL;   // status bar, navigation bar, keyboard and notch, in CSS pixels

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        cfg = new File(getFilesDir(), "config.json");
        bgFile = new File(getFilesDir(), "background.jpg");
        fitOrientation();

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);   // the phone's wallpaper shows through (the theme asks for it)
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);                        // the page is already big; the phone's font size would push it off the screen
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // no text selection, copy menus or long-press surprises
        web.setLongClickable(false);
        web.setOnLongClickListener(v -> true);
        web.setHapticFeedbackEnabled(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) { return true; }   // the page never leaves
            @Override public void onPageFinished(WebView v, String url) { sendInsets(); }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        edgeToEdge();

        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            int ok = tts.setLanguage(Locale.GERMANY);
            if (ok == TextToSpeech.LANG_MISSING_DATA || ok == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.GERMAN);
            tts.setSpeechRate(0.9f);
            ttsReady = true;
        });
        web.loadUrl(PAGE);

        // updates: looked for (and installed quietly) while the screen is off, so nobody sees anything
        up = new Updater(this, () -> js("window.onUpdate&&onUpdate(" + JSONObject.quote(up.info()) + ")"));
        screenOff = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { up.check(false); }
        };
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        findTorch();
    }

    /** the flashlight: the back camera's light, without opening the camera (no permission needed) */
    void findTorch() {
        try {
            cams = getSystemService(CameraManager.class);
            for (String id : cams.getCameraIdList()) {
                CameraCharacteristics c = cams.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && (torchId == null || (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK))) torchId = id;
            }
            if (torchId == null) return;
            cams.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String id, boolean on) {
                    if (!id.equals(torchId)) return;
                    torchOn = on;
                    main.removeCallbacks(torchOff);
                    if (on) main.postDelayed(torchOff, 15 * 60 * 1000L);   // nobody has to remember to switch it off
                    js("window.onTorch&&onTorch(" + on + ")");
                }
            }, main);
        } catch (Exception e) { torchId = null; }
    }

    boolean setTorch(boolean on) {
        if (torchId == null) return false;
        try { cams.setTorchMode(torchId, on); return true; } catch (Exception e) { return false; }
    }

    /**
     * The page draws behind the status and navigation bars (Android 15 does that anyway), so the wallpaper fills the whole
     * screen; it keeps its own content clear of them with the sizes from setInsets.
     */
    @SuppressWarnings("deprecation")
    void edgeToEdge() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        web.setOnApplyWindowInsetsListener((v, ins) -> {
            float d = getResources().getDisplayMetrics().density;
            int t, r, b, l;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets k = ins.getInsets(WindowInsets.Type.ime());
                t = i.top; r = i.right; l = i.left; b = Math.max(i.bottom, k.bottom);
            } else {
                t = ins.getSystemWindowInsetTop(); r = ins.getSystemWindowInsetRight(); b = ins.getSystemWindowInsetBottom(); l = ins.getSystemWindowInsetLeft();
            }
            inT = t / d; inR = r / d; inB = b / d; inL = l / d;
            sendInsets();
            return ins;
        });
        web.requestApplyInsets();
    }

    void sendInsets() { js("window.setInsets&&setInsets(" + inT + "," + inR + "," + inB + "," + inL + ")"); }

    /** upright on phones; a big screen (an unfolded OnePlus Open, a tablet) may turn */
    void fitOrientation() {
        boolean big = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        int want = big ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (getRequestedOrientation() != want) setRequestedOrientation(want);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        fitOrientation();   // folding or unfolding
    }

    void js(String code) { runOnUiThread(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

    // the Home button while this screen is already showing: back to the front page, settings closed
    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i != null && i.hasCategory(Intent.CATEGORY_HOME)) js("window.goHome&&goHome(true)");
    }

    // Back closes whatever is open; on the front page it does nothing (this is the home screen, there is nowhere to go)
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() { js("window.appBack&&appBack()"); }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        batteryRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { sendBattery(i); }
        };
        sendBattery(registerReceiver(batteryRx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
        js("window.onResumed&&onResumed()");
    }

    @Override
    protected void onPause() {
        if (batteryRx != null) { unregisterReceiver(batteryRx); batteryRx = null; }
        if (ttsReady) tts.stop();
        web.onPause();
        super.onPause();
    }

    // after a call or WhatsApp, the phone comes back to the front page, not to that person's buttons
    @Override
    protected void onStop() {
        js("window.goHome&&goHome(false)");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (screenOff != null) unregisterReceiver(screenOff);
        if (up != null) up.stop();
        if (tts != null) tts.shutdown();
        web.destroy();
        super.onDestroy();
    }

    void sendBattery(Intent i) {
        if (i == null) return;
        int[] b = battery(i);
        js("window.onBattery&&onBattery(" + b[0] + "," + (b[1] == 1) + ")");
    }

    static int[] battery(Intent i) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            || i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        return new int[] { level < 0 ? 0 : Math.round(level * 100f / Math.max(1, scale)), charging ? 1 : 0 };
    }

    boolean installed(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    String whatsAppPackage() {
        for (String p : WHATSAPP) if (installed(p)) return p;
        return null;
    }

    boolean start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); return true; } catch (ActivityNotFoundException | SecurityException e) { return false; }
    }

    boolean granted(String perm) { return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    boolean isDefaultHome() {
        Intent h = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo r = getPackageManager().resolveActivity(h, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
    }

    // ---- pictures: square, at most 480 pixels, as a data: URL the page can show and keep ----

    /** the picture at `u`, turned upright, its longer side at most about `max` pixels */
    Bitmap decode(Uri u, int max) throws Exception {
        Bitmap b;
        if (Build.VERSION.SDK_INT >= 28) {
            b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), u), (dec, info, src) -> {
                int big = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                if (big > max) dec.setTargetSampleSize(Math.max(1, big / max));
                dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        } else {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int big = Math.max(o.outWidth, o.outHeight), n = 1;
            while (big / (n * 2) >= max) n *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = n;
            try (InputStream in = getContentResolver().openInputStream(u)) { b = BitmapFactory.decodeStream(in, null, o); }
        }
        return b;
    }

    /** a new background: kept as a file for the page, and (if wanted) set as the phone's wallpaper too */
    void setBackground(Uri u) {
        try {
            Bitmap b = decode(u, 2000);
            if (b == null) return;
            float k = Math.min(1f, 2000f / Math.max(b.getWidth(), b.getHeight()));
            if (k < 1f) b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true);
            try (FileOutputStream out = new FileOutputStream(bgFile)) { b.compress(Bitmap.CompressFormat.JPEG, 88, out); }
            if (bgToPhone) {
                try { WallpaperManager.getInstance(this).setBitmap(b, null, true, WallpaperManager.FLAG_SYSTEM); }
                catch (Exception e) { /* the page still has it */ }
            }
            js("window.onBackground&&onBackground(" + JSONObject.quote(backgroundData()) + ")");
        } catch (Exception | OutOfMemoryError e) { /* the old background stays */ }
    }

    String backgroundData() {
        if (!bgFile.exists()) return "";
        try (FileInputStream in = new FileInputStream(bgFile)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return ""; }
    }

    String photoData(Uri u) {
        try {
            Bitmap b;
            if (Build.VERSION.SDK_INT >= 28) {
                b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), u), (dec, info, src) -> {
                    int big = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                    if (big > 1400) dec.setTargetSampleSize(big / 1000);
                    dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } else {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
                int big = Math.max(o.outWidth, o.outHeight), n = 1;
                while (big / (n * 2) >= 1000) n *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = n;
                try (InputStream in = getContentResolver().openInputStream(u)) { b = BitmapFactory.decodeStream(in, null, o); }
            }
            if (b == null) return null;
            int w = b.getWidth(), h = b.getHeight(), side = Math.min(w, h), out = Math.min(480, side);
            Bitmap sq = Bitmap.createBitmap(b, (w - side) / 2, (h - side) / 2, side, side);
            Bitmap small = Bitmap.createScaledBitmap(sq, out, out, true);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 85, bytes);
            return "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    static String iconData(Drawable d, int px) {
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, px, px);
        d.draw(new Canvas(b));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        return "data:image/png;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_ROLE) { js("window.onResumed&&onResumed()"); return; }
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        new Thread(() -> {
            if (req == REQ_CONTACT) {
                JSONObject c = new JSONObject();
                String[] cols = { ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI };
                try (Cursor cur = getContentResolver().query(u, cols, null, null, null)) {
                    if (cur == null || !cur.moveToFirst()) return;
                    c.put("name", cur.getString(0) == null ? "" : cur.getString(0));
                    c.put("number", cur.getString(1) == null ? "" : cur.getString(1));
                    String photo = cur.getString(2) == null ? null : photoData(Uri.parse(cur.getString(2)));
                    c.put("photo", photo == null ? "" : photo);
                } catch (Exception e) {
                    return;
                }
                js("window.onContactPicked&&onContactPicked(" + JSONObject.quote(c.toString()) + ")");
            } else if (req == REQ_BG) {
                setBackground(u);
            } else if (req == REQ_PHOTO) {
                String p = photoData(u);
                if (p != null) js("window.onPhotoPicked&&onPhotoPicked(" + JSONObject.quote(p) + ")");
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_PERMS) js("window.onPerms&&onPerms()");
    }

    /** window.Android in the page */
    class Bridge {
        @JavascriptInterface public String loadConfig() {
            if (!cfg.exists()) return "";
            try (FileInputStream in = new FileInputStream(cfg)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString("UTF-8");
            } catch (Exception e) { return ""; }
        }

        // written beside the old file first, so a sudden power-off never leaves half a file
        @JavascriptInterface public void saveConfig(String json) {
            File tmp = new File(getFilesDir(), "config.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            } catch (Exception e) { return; }
            if (!tmp.renameTo(cfg)) { cfg.delete(); tmp.renameTo(cfg); }
        }

        @JavascriptInterface public String battery() {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return "";
            int[] b = MainActivity.battery(i);
            return "{\"level\":" + b[0] + ",\"charging\":" + (b[1] == 1) + "}";
        }

        @JavascriptInterface public boolean hasWhatsApp() { return whatsAppPackage() != null; }

        @JavascriptInterface public boolean openWhatsApp() {
            String p = whatsAppPackage();
            Intent i = p == null ? null : getPackageManager().getLaunchIntentForPackage(p);
            return i != null && start(i);
        }

        /** the chat with this number; `number` is digits with the country code (4917…) */
        @JavascriptInterface public boolean whatsapp(String number) {
            String p = whatsAppPackage();
            if (p == null) return false;
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=" + Uri.encode(number)));
            i.setPackage(p);
            return start(i);
        }

        /** calls straight away when allowed, otherwise opens the dialer with the number in it */
        @JavascriptInterface public boolean call(String number) {
            Uri u = Uri.fromParts("tel", number, null);
            if (granted(Manifest.permission.CALL_PHONE) && start(new Intent(Intent.ACTION_CALL, u))) return true;
            return start(new Intent(Intent.ACTION_DIAL, u));
        }

        @JavascriptInterface public boolean sms(String number) {
            return start(new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)));
        }

        @JavascriptInterface public String whatsAppPackage() { return MainActivity.this.whatsAppPackage(); }

        @JavascriptInterface public boolean appInstalled(String pkg) { return pkg != null && installed(pkg); }

        /** a big icon for the front page (160 dp, as the phone's launcher shape) */
        @JavascriptInterface public String appIcon(String pkg) {
            try {
                int px = Math.min(512, Math.round(160 * getResources().getDisplayMetrics().density));
                return iconData(getPackageManager().getApplicationIcon(pkg), px);
            } catch (Exception | OutOfMemoryError e) { return ""; }
        }

        /** Android's own "Uninstall this app?" question for another app */
        @JavascriptInterface public void uninstallApp(String pkg) {
            if (pkg == null || pkg.isEmpty()) return;
            runOnUiThread(() -> {
                Uri u = Uri.parse("package:" + pkg);
                if (!start(new Intent(Intent.ACTION_DELETE, u))) start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, u));
            });
        }

        /** the contact photo for each [{id, number}], found by phone number; answers with onContactPhoto(id, dataUrl) */
        @JavascriptInterface public void findPhotos(String json) {
            if (!granted(Manifest.permission.READ_CONTACTS)) return;
            new Thread(() -> {
                try {
                    JSONArray list = new JSONArray(json);
                    for (int n = 0; n < list.length(); n++) {
                        JSONObject o = list.getJSONObject(n);
                        String num = o.optString("number");
                        if (num.isEmpty()) continue;
                        Uri q = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num));
                        String photoUri = null;
                        try (Cursor c = getContentResolver().query(q, new String[] { ContactsContract.PhoneLookup.PHOTO_URI }, null, null, null)) {
                            while (c != null && c.moveToNext() && photoUri == null) photoUri = c.getString(0);
                        }
                        String data = photoUri == null ? null : photoData(Uri.parse(photoUri));
                        if (data != null) js("window.onContactPhoto&&onContactPhoto(" + JSONObject.quote(o.optString("id")) + "," + JSONObject.quote(data) + ")");
                    }
                } catch (Exception e) { /* no photo then */ }
            }).start();
        }

        @JavascriptInterface public boolean openApp(String pkg) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            return i != null && start(i);
        }

        @JavascriptInterface public void listApps() {
            new Thread(() -> {
                PackageManager pm = getPackageManager();
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> found = pm.queryIntentActivities(q, 0);
                List<String[]> rows = new ArrayList<>();
                List<String> seen = new ArrayList<>();
                int px = Math.round(48 * getResources().getDisplayMetrics().density);
                for (ResolveInfo r : found) {
                    String pkg = r.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || seen.contains(pkg)) continue;
                    seen.add(pkg);
                    String icon;
                    try { icon = iconData(r.loadIcon(pm), px); } catch (Exception e) { icon = ""; }
                    rows.add(new String[] { pkg, String.valueOf(r.loadLabel(pm)), icon });
                }
                Collections.sort(rows, (a, b) -> a[1].compareToIgnoreCase(b[1]));
                JSONArray out = new JSONArray();
                try {
                    for (String[] r : rows) out.put(new JSONObject().put("pkg", r[0]).put("label", r[1]).put("icon", r[2]));
                } catch (Exception e) { /* only bad strings land here */ }
                js("window.onApps&&onApps(" + JSONObject.quote(out.toString()) + ")");
            }).start();
        }

        @JavascriptInterface public void pickContact() {
            runOnUiThread(() -> {
                try { startActivityForResult(new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_CONTACT); }
                catch (ActivityNotFoundException e) { /* no contacts app */ }
            });
        }

        @JavascriptInterface public void pickPhoto() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                try { startActivityForResult(i, REQ_PHOTO); } catch (ActivityNotFoundException e) { /* no gallery */ }
            });
        }

        @JavascriptInterface public boolean hasTorch() { return torchId != null; }
        @JavascriptInterface public boolean torch(boolean on) { return setTorch(on); }

        @JavascriptInterface public String updateInfo() { return up.info(); }
        @JavascriptInterface public void checkUpdate() { up.check(true); }
        @JavascriptInterface public void installUpdate() { runOnUiThread(() -> up.install(true)); }
        @JavascriptInterface public void autoUpdates() { runOnUiThread(() -> up.becomeInstaller()); }

        @JavascriptInterface public String getBackground() { return backgroundData(); }

        @JavascriptInterface public void pickBackground(boolean alsoPhone) {
            bgToPhone = alsoPhone;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
                try { startActivityForResult(i, REQ_BG); } catch (ActivityNotFoundException e) { /* no gallery */ }
            });
        }

        @JavascriptInterface public void clearBackground() { bgFile.delete(); }

        @JavascriptInterface public boolean hasCallPermission() { return granted(Manifest.permission.CALL_PHONE); }
        @JavascriptInterface public boolean hasContactPermission() { return granted(Manifest.permission.READ_CONTACTS); }

        @JavascriptInterface public void askPermissions() {
            runOnUiThread(() -> requestPermissions(new String[] { Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS }, REQ_PERMS));
        }

        @JavascriptInterface public boolean isDefaultHome() { return MainActivity.this.isDefaultHome(); }

        /** Android 10+ asks right here ("Set as default Home app?"); older phones open the Home app setting */
        @JavascriptInterface public void openHomeSettings() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 29 && !isDefaultHome()) {
                    RoleManager rm = getSystemService(RoleManager.class);
                    if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                        try { startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_ROLE); return; }
                        catch (ActivityNotFoundException e) { /* fall through to the settings */ }
                    }
                }
                if (!start(new Intent(Settings.ACTION_HOME_SETTINGS))) start(new Intent(Settings.ACTION_SETTINGS));
            });
        }

        @JavascriptInterface public void openDisplaySettings() {
            runOnUiThread(() -> { if (!start(new Intent(Settings.ACTION_DISPLAY_SETTINGS))) start(new Intent(Settings.ACTION_SETTINGS)); });
        }

        /** Android's own "Uninstall this app?" question */
        @SuppressWarnings("deprecation")
        @JavascriptInterface public void uninstall() {
            runOnUiThread(() -> {
                Uri u = Uri.parse("package:" + getPackageName());
                if (!start(new Intent(Intent.ACTION_DELETE, u))) start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, u));
            });
        }

        @JavascriptInterface public void speak(String text) {
            if (ttsReady && text != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bh");
        }
    }
}
