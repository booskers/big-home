# Großer Start (big home)

A simple home screen (Android launcher) for older people, in German (polite "Sie").

**Download:** [Grosser-Start.apk](https://github.com/booskers/big-home/releases/latest/download/Grosser-Start.apk)
(latest release). Free and open source under the MIT licence. Made with love by booskers / Polychrome.

- **Front page:** the phone's own wallpaper, a big clock, the date written out ("Freitag, 9. Oktober"), and the
  battery level. It turns red with "Bitte aufladen" at 20 % or less. Below that are the family tiles: a person with a
  photo is just their face (found in the contacts by phone number), and anyone without one gets their initials and
  name. Then come a large WhatsApp tile and optionally other apps as really big icons. Holding an app shows its full
  name with **Öffnen** and **Deinstallieren**.
- **Glass look:** the battery, WhatsApp, the app shelf, name labels and buttons are tinted glass with kube's fine
  specular rim (light from the upper left; kube.io/blog/liquid-glass-css-svg). The real refraction and the live blurs
  were taken out again in 1.7.0: Android renders them on the processor for every frame, which made big screens lag.
  **Foto als Hintergrund wählen** in the settings shows a photo behind it all (and sets it as the phone's wallpaper).
- **Anordnung:** the order of the blocks (clock, family, WhatsApp, apps), the people and the apps can be changed with
  ▲ ▼ in the settings, then fixed with **Anordnung sperren**.
- **Alle Apps:** a swipe from right to left (or the "Alle Apps" button) shows all apps as big icons with names;
  the settings can hide apps from it or switch the swipe off. **Taschenlampe:** a flashlight button that switches
  itself off after 15 minutes.
- **Helfer:** Wecker, Kurzzeitwecker and Stoppuhr as big widgets (switched on in the settings). Alarm and timer go
  into the phone's own clock app, which rings reliably; the stopwatch keeps counting while the app is closed.
- **Größe:** "Größe anpassen" in the settings scales everything (60–150 %) with big Kleiner / Größer buttons while the
  front page is visible; the family grid gets more columns as the size goes down.
- **A tap on a person** opens three big buttons: **WhatsApp** (opens the chat with that person), **Anrufen** (calls
  straight away once allowed) and **SMS**. A big "Zurück" button returns to the front page, and so does doing nothing
  for 90 seconds or coming back from a call.
- **Can't get lost:** once chosen as the phone's Home app, the Home button always comes back here, Back on the front
  page does nothing, and long presses do nothing.
- **Settings** (for the relative who sets it up): hold the clock for 4 seconds, then enter a PIN (optional). From
  there you can add people from the contacts (name, number and photo) or by hand, choose a photo, pick which buttons
  each person has, reorder people, add apps, set the country code for WhatsApp, turn on reading names aloud, open the
  phone's font-size settings, and use **Anderen Startbildschirm wählen** or **Großer Start deinstallieren**.
  Uninstalling also works without the PIN: Android Settings → Apps → Großer Start → Deinstallieren.
- **Updates by themselves, unnoticed:** the app looks for a new release (at most every hour) whenever the screen goes
  off, and installs it right there, quietly, while the phone is dark. Nobody using the phone is asked anything. Android
  allows that from Android 12 on, after **Automatische Updates einschalten** in the settings has been tapped once (one
  confirmation by the family member). On Android 11 and older every update needs a tap; it waits in the settings.
- **Phones:** Android 7 and newer (older Samsung models), Nothing Phone (1), OnePlus Open. It stays upright on phone
  screens. On the unfolded OnePlus Open it uses three to four columns and can rotate.

## Layout

- `web/index.html`: the whole screen. Open it in a browser to try it, with stand-in people and a stand-in phone.
- `android/`: one WebView activity that is the Home app. `python build.py` makes `android/dist/Grosser-Start.apk`
  (no Gradle; needs JDK 17 and Android SDK platform 35 / build-tools 35.0.0). The signing key is created on the first
  build in `%USERPROFILE%\.bighome\`. Keep it, or updates won't install.
- `web/fonts/`: Inter (SIL Open Font License, `OFL.txt`), the open stand-in for Apple's SF Pro, which may only be
  used on Apple devices. Copied into the app by `build.py`.
- `tools/serve.mjs`: `node tools/serve.mjs` serves the page at http://localhost:5192/.

## Setting up a phone

1. Install `Grosser-Start.apk` (allow "install unknown apps" for the file manager or browser you open it with).
2. Open "Großer Start" from the app list. The setup opens by itself the first time.
3. Tap **Als Startbildschirm festlegen** and confirm. On Android 10+ this is a single dialog; on older phones choose
   it under Default apps → Home app.
4. Add the family with **Aus den Kontakten**, then allow the two questions (calls, contacts).
5. Under **Updates**, tap **Automatische Updates einschalten** (allow "Aus dieser Quelle zulassen" if asked, then
   confirm once).
6. Set a PIN, and if needed, **Schrift und Anzeige größer stellen**.

## Releasing an update

1. Raise `versionCode` and `versionName` in `android/AndroidManifest.xml`.
2. `python android/build.py` (signs with the key in `%USERPROFILE%\.bighome\`).
3. Add a section to `CHANGELOG.md`, commit, then attach **both** `android/dist/Grosser-Start.apk` and
   `android/dist/big-home.json` to a new release:
   `gh release create vX.Y.Z android/dist/Grosser-Start.apk android/dist/big-home.json --title "Großer Start X.Y.Z" --notes "…"`.
   Phones read `releases/latest/download/big-home.json`; a release without it is never installed.
