r"""Builds Großer Start (Android): dist/Grosser-Start.apk.

No Gradle: the app is one activity with no libraries, so the Android SDK's own tools do it directly. The game itself is
../web/index.html, packed into the app as an asset. Needs Java 17 and the Android SDK (platforms;android-35,
build-tools;35.0.0), found through JAVA_HOME / ANDROID_HOME or their usual places. Run:  python build.py

The signing key lives outside the repository in %USERPROFILE%\.bighome\ (bighome.jks and its password in
bighome.pass), made on the first build. Keep it: Android only installs an update signed with the same key.
"""
import glob, json, os, re, secrets, shutil, subprocess, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
B = os.path.join(HERE, 'build'); DIST = os.path.join(HERE, 'dist')
LOCAL = os.environ.get('LOCALAPPDATA', '')
SDK = os.environ.get('ANDROID_HOME') or os.path.join(LOCAL, 'Android', 'Sdk')
JDK = os.environ.get('JAVA_HOME') or next(iter(sorted(glob.glob(r'C:\Program Files\Microsoft\jdk-17*'))), '')
BT = os.path.join(SDK, 'build-tools', '35.0.0')
JAR = os.path.join(SDK, 'platforms', 'android-35', 'android.jar')
KEYDIR = os.path.join(os.path.expanduser('~'), '.bighome')
KS, KP = os.path.join(KEYDIR, 'bighome.jks'), os.path.join(KEYDIR, 'bighome.pass')
OUT = os.path.join(DIST, 'Grosser-Start.apk')
env = dict(os.environ, JAVA_HOME=JDK, PATH=os.path.join(JDK, 'bin') + os.pathsep + os.environ.get('PATH', ''))

def run(*args):
    r = subprocess.run([str(a) for a in args], env=env, capture_output=True, text=True)
    if r.returncode: sys.exit(f'failed: {args[0]}\n{r.stdout}\n{r.stderr}')
    return r.stdout

def tool(name):
    for ext in ('.exe', '.bat', ''):
        p = os.path.join(BT, name + ext)
        if os.path.exists(p): return p
    sys.exit(f'{name} is missing from {BT}')

for need in (JDK, BT, JAR):
    if not need or not os.path.exists(need): sys.exit(f'missing: {need or "Java 17 (JAVA_HOME)"}')
shutil.rmtree(B, ignore_errors=True)
for d in ('gen', 'classes', 'dex', 'assets'): os.makedirs(os.path.join(B, d))
shutil.copy(os.path.join(HERE, '..', 'web', 'index.html'), os.path.join(B, 'assets', 'index.html'))
shutil.copytree(os.path.join(HERE, '..', 'web', 'fonts'), os.path.join(B, 'assets', 'fonts'))   # Inter
os.makedirs(DIST, exist_ok=True)

# 1. resources and the manifest
run(tool('aapt2'), 'compile', '--dir', os.path.join(HERE, 'res'), '-o', os.path.join(B, 'res.zip'))
run(tool('aapt2'), 'link', '-o', os.path.join(B, 'base.apk'), '-I', JAR, '--manifest', os.path.join(HERE, 'AndroidManifest.xml'),
    '-A', os.path.join(B, 'assets'), '--java', os.path.join(B, 'gen'), os.path.join(B, 'res.zip'))
# 2. the code: Java 11 bytecode against Android's classes, then d8 (which turns lambdas into plain classes) to Android's dex
src = glob.glob(os.path.join(HERE, 'src', '**', '*.java'), recursive=True) + glob.glob(os.path.join(B, 'gen', '**', '*.java'), recursive=True)
run(os.path.join(JDK, 'bin', 'javac'), '-nowarn', '--release', '11', '-classpath', JAR, '-d', os.path.join(B, 'classes'), *src)
classes = glob.glob(os.path.join(B, 'classes', '**', '*.class'), recursive=True)
run(tool('d8'), '--release', '--min-api', '24', '--lib', JAR, '--output', os.path.join(B, 'dex'), *classes)
# 3. one package: the resources plus the code
unsigned = os.path.join(B, 'unsigned.apk')
shutil.copy(os.path.join(B, 'base.apk'), unsigned)
with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as z: z.write(os.path.join(B, 'dex', 'classes.dex'), 'classes.dex')
aligned = os.path.join(B, 'aligned.apk')
run(tool('zipalign'), '-f', '-p', '4', unsigned, aligned)
# 4. signed with the app's own key (made once)
if not os.path.exists(KS):
    os.makedirs(KEYDIR, exist_ok=True)
    pw = secrets.token_urlsafe(24)
    with open(KP, 'w') as f: f.write(pw)
    run(os.path.join(JDK, 'bin', 'keytool'), '-genkeypair', '-keystore', KS, '-alias', 'bighome', '-keyalg', 'RSA', '-keysize', '3072',
        '-validity', '20000', '-dname', 'CN=booskers, O=Polychrome', '-storepass', pw, '-keypass', pw)
    print('made the signing key:', KS)
pw = open(KP).read().strip()
run(tool('apksigner'), 'sign', '--ks', KS, '--ks-key-alias', 'bighome', '--ks-pass', 'pass:' + pw, '--key-pass', 'pass:' + pw, '--out', OUT, aligned)
run(tool('apksigner'), 'verify', OUT)
man = open(os.path.join(HERE, 'AndroidManifest.xml'), encoding='utf-8').read()
ver = re.search(r'versionName="([^"]+)"', man).group(1)
# the updater reads this from the latest GitHub release: attach it next to the APK every time
with open(os.path.join(DIST, 'big-home.json'), 'w') as f:
    json.dump({'versionCode': int(re.search(r'versionCode="(\d+)"', man).group(1)), 'versionName': ver}, f)
print(f'Großer Start {ver} -> {os.path.relpath(OUT, HERE)} ({os.path.getsize(OUT) // 1024} KB)')
