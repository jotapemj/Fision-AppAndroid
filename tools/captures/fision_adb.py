"""Shared adb helpers for the README capture phases.

Device: ANDROID_SERIAL (or FISION_SERIAL) picks it; with one device connected
nothing is needed. Captures land in captures_out/ next to this file (or in
FISION_CAPTURES_DIR).

The app is driven only from outside: launcher intent, taps on views found by
resource id or text in a uiautomator dump, and screencap.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.jotapeveloper.fision"
SPLASH = PKG + "/.SplashScreenActivity"
OUT = os.environ.get("FISION_CAPTURES_DIR",
                     os.path.join(os.path.dirname(os.path.abspath(__file__)), "captures_out"))
SERIAL = os.environ.get("FISION_SERIAL") or os.environ.get("ANDROID_SERIAL")


def adb(*args, check=True, capture=True):
    cmd = ["adb"] + (["-s", SERIAL] if SERIAL else []) + list(args)
    r = subprocess.run(cmd, capture_output=capture)
    if check and r.returncode != 0:
        sys.exit(f"adb failed: {' '.join(args)}\n{r.stderr.decode(errors='replace')}")
    return r.stdout if capture else None


def sh(cmd, check=True):
    return adb("shell", cmd, check=check).decode(errors="replace")


def wait(seconds):
    time.sleep(seconds)


def pause(msg):
    input(f"\n>>> {msg}\n    Enter para seguir... ")


# ------------------------------------------------------------------ device state

def demo_mode(on):
    """Clean status bar: 12:00, full battery and signal, no notifications."""
    b = "am broadcast -a com.android.systemui.demo -e command "
    if on:
        sh("settings put global sysui_demo_allowed 1")
        sh(b + "enter")
        sh(b + "clock -e hhmm 1200")
        sh(b + "battery -e level 100 -e plugged false")
        sh(b + "network -e wifi show -e level 4")
        sh(b + "network -e mobile show -e datatype none -e level 4")
        sh(b + "notifications -e visible false")
    else:
        sh(b + "exit")


LOCALE_FILE = os.path.join(OUT, ".app_locale_before")


def app_locale_get():
    """Per-app language now set ("" = follows the system)."""
    out = sh(f"cmd locale get-app-locales {PKG}", check=False)
    m = re.search(r"\[(.*?)\]", out)
    return m.group(1).strip() if m else ""


def app_locale(tag):
    """Per-app language (Android 13+); empty tag = follow the system."""
    sh(f"cmd locale set-app-locales {PKG} --locales '{tag}'", check=False)


# Debug builds show the welcome and the tours on every start unless the
# debug switch is off: for the captures it goes off and both tours count as
# seen. The previous files are kept as .bak and put back by restore().
_QUIET = {
    "prefs_debug.xml": '<boolean name="onboarding_always" value="false" />',
    "prefs_onboarding.xml": '<boolean name="done_library" value="true" />'
                            '<boolean name="done_player" value="true" />',
}


def runas(cmd, check=False):
    return sh(f'run-as {PKG} sh -c "{cmd}"', check=check)


def quiet_onboarding():
    sh(f"am force-stop {PKG}")
    for name, body in _QUIET.items():
        tmp = "/data/local/tmp/" + name
        sh(f"echo '<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>"
           f"<map>{body}</map>' > {tmp}")
        sh(f"chmod 644 {tmp}")
        p = "shared_prefs/" + name
        # First run only: keep the original (or mark that there was none).
        runas(f"if [ ! -e {p}.bak ] && [ ! -e {p}.none ]; then "
              f"if [ -e {p} ]; then cp {p} {p}.bak; else touch {p}.none; fi; fi; "
              f"cp {tmp} {p}")
        sh(f"rm -f {tmp}")


def loud_onboarding():
    sh(f"am force-stop {PKG}")
    for name in _QUIET:
        p = "shared_prefs/" + name
        runas(f"if [ -e {p}.bak ]; then mv {p}.bak {p}; "
              f"elif [ -e {p}.none ]; then rm -f {p} {p}.none; fi")


# Downloaded songs of the demo account: parked outside the app folder so
# the library starts empty; unpark_songs() puts them back.
DEMO_ACC = os.environ.get("FISION_DEMO_ACC", "acc_34eae9381d6acd598e399980")
LIB = f"/sdcard/Android/data/{PKG}/files/Library/{DEMO_ACC}"
PARK = f"/sdcard/fision_parked/{DEMO_ACC}"


def park_songs():
    sh(f"am force-stop {PKG}")
    sh(f"mkdir -p {PARK}; for d in {LIB}/*/; do [ -d \"$d\" ] && mv \"$d\" {PARK}/; done",
       check=False)
    print(f"  aparcadas: {sh(f'ls {PARK} | wc -l', check=False).strip()} canciones")


def unpark_songs(only=None):
    """only: hash prefixes to bring back (default FISION_DOWNLOADED, comma
    separated); empty = all. The rest stays parked and shows as cloud."""
    if only is None:
        only = [p for p in os.environ.get("FISION_DOWNLOADED", "").split(",") if p]
    pat = " ".join(f"{PARK}/{p}*/" for p in only) if only else f"{PARK}/*/"
    sh(f"am force-stop {PKG}")
    sh(f"mkdir -p {LIB}; for d in {pat}; do [ -d \"$d\" ] && mv \"$d\" {LIB}/; done; "
       f"rmdir {PARK} /sdcard/fision_parked 2>/dev/null", check=False)
    print(f"  en biblioteca: {sh(f'ls {LIB} | wc -l', check=False).strip()} canciones")


def prepare():
    os.makedirs(OUT, exist_ok=True)
    if not os.path.exists(LOCALE_FILE):
        with open(LOCALE_FILE, "w") as f:
            f.write(app_locale_get())
    demo_mode(True)
    app_locale("en-US")
    quiet_onboarding()


def restore():
    before = ""
    if os.path.exists(LOCALE_FILE):
        with open(LOCALE_FILE) as f:
            before = f.read().strip()
        os.remove(LOCALE_FILE)
    app_locale(before)
    loud_onboarding()
    demo_mode(False)


def launch(settle=7):
    """Cold start through the splash (the only exported screen)."""
    sh(f"am force-stop {PKG}")
    sh(f"am start -n {SPLASH}")
    wait(settle)


def back(settle=1.2):
    sh("input keyevent KEYCODE_BACK")
    wait(settle)


def media_pause():
    sh("input keyevent KEYCODE_MEDIA_PAUSE", check=False)


def media_play():
    sh("input keyevent KEYCODE_MEDIA_PLAY", check=False)


# ------------------------------------------------------------------ captures

def shot(name):
    """Screencap to OUT/<name>.png."""
    png = adb("exec-out", "screencap", "-p")
    path = os.path.join(OUT, name + ".png")
    with open(path, "wb") as f:
        f.write(png)
    print(f"  captura: {name}.png")


# ------------------------------------------------------------------ finding views

_BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def dump(retries=4):
    """UI hierarchy; uiautomator needs an idle UI, so it is retried."""
    for _ in range(retries):
        out = sh("uiautomator dump /sdcard/fision_ui.xml", check=False)
        if "dumped" in out:
            xml = adb("exec-out", "cat", "/sdcard/fision_ui.xml")
            return ET.fromstring(xml)
        wait(1.0)
    sys.exit("uiautomator no pudo leer la pantalla (UI sin reposo). Pausa la canción y repite.")


def center(node):
    x1, y1, x2, y2 = map(int, _BOUNDS.match(node.get("bounds")).groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(rid=None, text=None, desc=None, contains=False, index=0, root=None):
    """Node by resource id (short name), text or content description."""
    root = root if root is not None else dump()
    hits = []
    for n in root.iter("node"):
        if rid and n.get("resource-id") != f"{PKG}:id/{rid}":
            continue
        for want, attr in ((text, "text"), (desc, "content-desc")):
            if want is None:
                continue
            val = n.get(attr) or ""
            if (want.lower() not in val.lower()) if contains else (val != want):
                break
        else:
            hits.append(n)
    return hits[index] if len(hits) > index else None


def tap(rid=None, text=None, desc=None, contains=False, index=0, settle=1.5, required=True):
    n = find(rid, text, desc, contains, index)
    if n is None:
        what = rid or text or desc
        if required:
            sys.exit(f"No encuentro en pantalla: {what}")
        print(f"  (no está: {what})")
        return False
    x, y = center(n)
    sh(f"input tap {x} {y}")
    wait(settle)
    return True


def tap_xy(x, y, settle=1.5):
    sh(f"input tap {x} {y}")
    wait(settle)


def type_text(s):
    sh("input text " + s.replace(" ", "%s"))
