#!/usr/bin/env python3
"""Real Android UI/HTTP checks. Never patches product JS or emulates a GM API."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import shlex
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

import uiautomator2 as u2
from PIL import ImageChops, ImageStat
import visual

PKG = "de.evp.helfer.debug"
FIREFOX = "org.mozilla.firefox"
ROUTE = os.environ.get("E2E_ROUTE", "kadri")
assert ROUTE in ("kadri", "userscript")
SCOPE = os.environ.get("E2E_SCOPE", "full")
assert SCOPE in ("full", "smoke", "controls", "core")
OUT = Path(os.environ.get("E2E_OUT", "android-e2e-results"))
OUT.mkdir(parents=True, exist_ok=True)
RESULTS = []
KEY = ""
AUTH_KEY = ""
D = None
WEB_EXT = None
LOGCAT = None
LAST_REVEAL_AT = 0.0
NAVIGATION_INITIALIZED = False
DENSITY = 1.0

class Blocked(Exception):
    pass

class InvalidInput(AssertionError):
    pass

def command(*args, check=True, timeout=45):
    return subprocess.run(list(args), text=True, capture_output=True, check=check, timeout=timeout).stdout

def adb(*args, **kwargs):
    return command("adb", *args, **kwargs)

def shell(*args, **kwargs):
    return adb("shell", shlex.join([str(arg) for arg in args]), **kwargs)

def allow_firefox_notifications():
    prompt = D(resourceIdMatches=r"(com\.android\.permissioncontroller|com\.google\.android\.permissioncontroller):id/permission_message")
    if not prompt.exists:
        return False
    text = str(prompt.info.get("text", ""))
    if "Firefox" not in text or "notification" not in text.lower():
        return False
    allow = D(resourceIdMatches=r"(com\.android\.permissioncontroller|com\.google\.android\.permissioncontroller):id/permission_allow_button")
    if not allow.exists:
        allow = D(textMatches="(?i)allow")
    if not allow.exists or not allow.info.get("enabled"):
        return False
    allow.click()
    print("UI: allowed Android notification permission for the disposable Firefox profile", flush=True)
    return True

def dismiss_fullscreen_tutorial():
    notice = D(resourceId="android:id/immersive_cling_title")
    if not notice.exists:
        notice = D(text="Viewing full screen")
    if not notice.exists: return False
    confirm = D(resourceId="android:id/ok")
    if not confirm.exists:
        confirm = D(textMatches="(?i)got it")
    if not confirm.exists: return False
    confirm.click()
    print("UI: confirmed Android's first-use immersive fullscreen tutorial", flush=True)
    return True

def dismiss_translation_sheet():
    close = D(description="Close Translations sheet")
    if not close.exists or not D(textContains="Try private translations").exists:
        return False
    later = D(text="Not now")
    if later.exists: tap_fresh(later)
    else: D.press("back")
    until = time.monotonic() + 1
    while time.monotonic() < until:
        if not D(description="Close Translations sheet").exists:
            print("UI: Firefox translation sheet visibly closed without translating", flush=True)
            return True
        time.sleep(.15)
    D.press("back")
    time.sleep(.3)
    assert not D(description="Close Translations sheet").exists, "Native Firefox translation sheet did not close"
    print("UI: Firefox translation sheet closed with Android Back", flush=True)
    return True

def tap_fresh(selector):
    for attempt in range(3):
        try:
            selector.click()
            return
        except Exception as exc:
            # UiObject.click resolves bounds before sending the tap. A stale
            # lookup has sent no input; re-query that exact selector, not a case.
            if "StaleObjectException" not in str(exc) or attempt == 2: raise
            time.sleep(.2)

def wait(fn, timeout=20, interval=0.25, dialogs=True):
    until = time.monotonic() + timeout
    last = None
    while time.monotonic() < until:
        try:
            if D is not None and dialogs:
                dismiss_fullscreen_tutorial()
                dismiss_translation_sheet()
            if D is not None and dialogs and allow_firefox_notifications():
                # Resume the owned source if the platform prompt interrupted its first Play.
                source_play = D(text="Play fixture")
                if source_play.exists(timeout=1): source_play.click()
            value = fn()
            if value:
                return value
        except AssertionError:
            raise
        except Exception as exc:
            last = exc
        time.sleep(interval)
    raise AssertionError("Condition not reached" + (": " + type(last).__name__ if last else ""))

def redact(s):
    if KEY:
        s = s.replace(KEY, "[disposable-pair-key]")
    return re.sub(r"(/pair)#?[0-9a-f]{32}", r"\1#[disposable-pair-key]", s)

def request(path, key=True):
    headers = {"X-EVP-Key": AUTH_KEY} if key else {}
    with urllib.request.urlopen(urllib.request.Request(
            "http://127.0.0.1:14781" + path, headers=headers), timeout=15) as response:
        return json.load(response)

def prefs():
    raw = shell("run-as", PKG, "cat", "shared_prefs/helfer.xml")
    return {n.attrib["name"]: n.text if n.tag == "string" else n.attrib.get("value")
            for n in ET.fromstring(raw)}

def shot(name):
    try:
        D.screenshot(str(OUT / (name + ".png")))
        (OUT / (name + ".xml")).write_text(redact(D.dump_hierarchy()), encoding="utf8")
    except Exception:
        pass

def case(name, fn, requires=None):
    if requires and not all(any(r["name"] == x and r["status"] == "PASS" for r in RESULTS) for x in requires):
        RESULTS.append({"name": name, "status": "BLOCKED", "detail": "Prerequisite did not pass"})
        write_results()
        return
    started = time.monotonic()
    record = None
    video = "/sdcard/e2e-" + name + ".mp4"
    try:
        record_log = (OUT / (name + "-screenrecord.log")).open("w")
        record = subprocess.Popen(["adb", "shell", "screenrecord", "--time-limit", "120", "--bit-rate", "1000000", "--size", "720x1280", video],
                                  stdout=record_log, stderr=subprocess.STDOUT)
        detail = fn()
        RESULTS.append({"name": name, "status": "PASS", "seconds": round(time.monotonic() - started, 3), "detail": detail})
    except Blocked as exc:
        RESULTS.append({"name": name, "status": "NOT_TESTED", "detail": str(exc)})
    except InvalidInput as exc:
        RESULTS.append({"name":name,"status":"FAIL","category":"test_input","detail":str(exc),"trace":traceback.format_exc()})
    except Exception as exc:
        RESULTS.append({"name": name, "status": "FAIL", "detail": redact(str(exc)), "trace": redact(traceback.format_exc())})
    finally:
        try:
            with urllib.request.urlopen("http://127.0.0.1:8765/events", timeout=3) as response:
                events = [e for e in json.load(response) if e.get("received", 0) >= started]
            (OUT / (name + "-http-events.json")).write_text(json.dumps(events, indent=2))
        except Exception as exc:
            print("Owned fixture evidence unavailable:",type(exc).__name__,flush=True)
        shot(name)
        if RESULTS[-1]["status"] == "FAIL":
            print(redact(RESULTS[-1].get("trace", "")), flush=True)
            try:
                nodes = ET.fromstring(D.dump_hierarchy()).iter("node")
                labels = [(n.attrib.get("text") or n.attrib.get("content-desc")) for n in nodes]
                print("Visible UI labels:", redact(repr([x for x in labels if x])[:6000]), flush=True)
            except Exception:
                pass
        if record:
            try:
                pids = shell("pidof", "screenrecord", check=False).split()
                if pids:
                    shell("kill", "-2", *pids, check=False)
                record.wait(timeout=10)
            except Exception:
                record.terminate()
            adb("pull", video, str(OUT / (name + ".mp4")), check=False)
            record_log.close()
            RESULTS[-1]["recording_available"] = (OUT / (name + ".mp4")).is_file()
        write_results()
        print(name, RESULTS[-1]["status"], redact(str(RESULTS[-1].get("detail", ""))), flush=True)
        if os.environ.get("GITHUB_ACTIONS") == "true":
            print("::notice title=Android E2E "+ROUTE+"/"+name+"::"+RESULTS[-1]["status"], flush=True)

def write_results():
    (OUT / "results.json").write_text(json.dumps(RESULTS, indent=2), encoding="utf8")
    rows = [f"# Android E2E — {ROUTE} / {SCOPE} — real emulator / installed APK", "",
            "| Case | Status | Detail |", "|---|---|---|"]
    for r in RESULTS:
        rows.append("| " + r["name"] + " | " + r["status"] + " | " +
                    str(r.get("detail", "")).replace("|", "/").replace("\n", " ")[:700] + " |")
    (OUT / "REPORT.md").write_text("\n".join(rows) + "\n", encoding="utf8")


class VisualControl:
    def __init__(self, hit): self.hit = hit
    def click(self):
        print("Visual UI tap:", self.hit, flush=True)
        D.click(self.hit["x"], self.hit["y"])
    def long_click(self, duration=.7):
        print("Visual UI long press:", self.hit, flush=True)
        D.long_click(self.hit["x"], self.hit["y"], duration=duration)

def icon_control(key):
    hit = visual.locate(D.screenshot(), key, DENSITY)
    return VisualControl(hit) if hit else None

def entry_control():
    for label in ("Videoplayer", "video player"):
        for selector in (D(descriptionContains=label), D(textContains=label)):
            if selector.exists: return selector
    return icon_control("I.player")

def label_control(label):
    for selector in (D(text=label), D(description=label)):
        if selector.exists: return selector
    hit = visual.phrase(D.screenshot(), label)
    return VisualControl(hit) if hit else None

def player_controls_visible():
    return D(description="Close").exists or D(description="Schließen").exists or bool(icon_control("P.close"))

def visual_click(label):
    keys = {"Close":"P.close","Schließen":"P.close","Play":"P.pause","Wiedergabe":"P.pause",
            "Subtitles":"P.cc","Untertitel":"P.cc"}
    if label in keys:
        control = icon_control(keys[label])
        if control is None and label in ("Play","Wiedergabe"): control = icon_control("P.play")
        if control:
            control.click()
            return True
    hit = visual.phrase(D.screenshot(), label)
    if hit:
        print("OCR UI tap:",hit,flush=True)
        D.click(hit["x"],hit["y"])
        return True
    return False


def dismiss_addon_notice():
    notice = visible_label("was added", contains=True)
    if not notice:
        return False
    ok = D(resourceId=FIREFOX + ":id/confirm_button")
    if not ok.exists:
        ok = D(text="OK")
    if not ok.exists:
        ok = D(description="OK")
    wait(lambda: ok.exists and ok.info.get("enabled"), timeout=8)
    ok.click()
    wait(lambda: not visible_label("was added", contains=True), timeout=8)
    return True

def fresh_input_controls():
    # Observe or reveal once. Toggling visible controls off/on before every
    # snapshot races their normal hide timer and adds unrelated user input.
    wait(reveal, timeout=8)
    age=time.monotonic()-LAST_REVEAL_AT
    time.sleep(max(0,.40-age))  # respect the product's 320ms input guard

def tap_player_icon(label):
    keys = {"Close":("P.close",),"Schließen":("P.close",),
            "Play":("P.pause","P.play"),"Wiedergabe":("P.pause","P.play"),
            "Subtitles":("P.cc",),"Untertitel":("P.cc",)}
    def attempt():
        # Match and tap from one current screenshot; no slow AX waits between
        # detecting a short-lived control and sending its real touch.
        frame = D.screenshot()
        for key in keys[label]:
            hit = visual.locate(frame, key, DENSITY)
            if hit:
                if time.monotonic()-LAST_REVEAL_AT<.40:
                    return False
                VisualControl(hit).click()
                return True
        reveal()
        return False
    fresh_input_controls()
    wait(attempt, timeout=10, dialogs=False)
    if label in ("Close","Schließen"):
        # Confirm the real native browser toolbar, not a hidden/stale source AX
        # node. A missed Close must fail here instead of contaminating later cases.
        wait(lambda: visible_label("Search or enter address", contains=True), timeout=8)

def click(label, scroll=False):
    # Accessible user-facing labels, no product debug hooks.
    if label in ("Close","Schließen","Play","Wiedergabe","Subtitles","Untertitel"):
        tap_player_icon(label)
        return
    if label != "OK":
        dismiss_addon_notice()
    selectors = (D(text=label), D(description=label))
    for sel in selectors:
        if sel.exists(timeout=1):
            sel.click()
            return
    if scroll:
        try:
            D(scrollable=True).scroll.to(text=label)
        except Exception:
            D(scrollable=True).scroll.to(description=label)
        for sel in selectors:
            if sel.exists:
                sel.click()
                return
    if label in ("Close", "Schließen", "Play", "Wiedergabe", "Subtitles", "Untertitel") and reveal():
        for sel in selectors:
            if sel.exists:
                sel.click()
                return
    if label != "OK" and dismiss_addon_notice():
        wait(lambda: any(sel.exists for sel in selectors), timeout=8)
        for sel in selectors:
            if sel.exists:
                sel.click()
                return
    if visual_click(label): return
    raise AssertionError("Visible control absent: " + label)

def open_app():
    # Explicit task restoration dismisses a stale system picker from a failed prior case.
    shell("am", "start", "-W", "-n", PKG + "/de.evp.helfer.MainActivity", "-f", "0x14000000")
    wait(lambda: D(packageName=PKG, className="android.widget.ScrollView").exists)
    time.sleep(0.6)

def setup():
    global KEY, AUTH_KEY
    open_app()
    api = int(shell("getprop", "ro.build.version.sdk").strip())
    if api >= 33:
        shell("pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    if api <= 28:
        shell("pm", "grant", PKG, "android.permission.WRITE_EXTERNAL_STORAGE")
    # Permission provisioning is explicit infrastructure. Later gesture tests
    # still exercise the real overlay, audio service and browser/extension.
    shell("appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")
    shell("dumpsys", "deviceidle", "whitelist", "+" + PKG)
    open_app()
    click("English", scroll=True)
    wait(lambda: prefs().get("lang") == "en")
    # The actual service intentionally rejects every legacy/bootstrap key.
    # A nonempty invalid key invokes the lazy key generator but never pairs.
    wait(unauthenticated_ready)
    KEY = wait(lambda: prefs().get("key"))
    AUTH_KEY = KEY
    assert len(KEY) == 32 and prefs().get("paired") != "true"
    assert "allow" in shell("appops", "get", PKG, "SYSTEM_ALERT_WINDOW")
    D(scrollable=True).scroll.toBeginning()
    assert D(textContains="Running").exists
    return "Installed debug APK, real foreground service, overlay permission. Permission grant was ADB provisioning."

def unauthenticated_ready():
    try:
        with urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:14781/ping",
                headers={"X-EVP-Key": "evp-1"}), timeout=3):
            raise AssertionError("Compromised legacy bootstrap key accepted")
    except urllib.error.HTTPError as exc:
        assert exc.code == 403
        return True


def security():
    assert unauthenticated_ready()
    for value in (False, "wrong"):
        try:
            if value:
                with urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:14781/ping",
                        headers={"X-EVP-Key": "invalid"}), timeout=5):
                    raise AssertionError("Wrong key accepted")
            else:
                request("/ping", key=False)
                raise AssertionError("No key accepted")
        except urllib.error.HTTPError as exc:
            assert exc.code == 403
    try:
        with urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:14781/ping",
                headers={"X-EVP-Key": AUTH_KEY, "Host": "attacker.invalid:47811"}), timeout=5):
            raise AssertionError("Foreign Host accepted")
    except urllib.error.HTTPError as exc:
        assert exc.code == 403
    return "Real APK rejected missing/wrong key and DNS-rebinding Host (403). Not a DNR test."

def export_import():
    open_app()
    click("Light", scroll=True)
    wait(lambda: prefs().get("theme") == "light")
    click("Export settings", scroll=True)
    wait(lambda: D(resourceId="com.android.documentsui:id/container_save").exists or D(textMatches="(?i)save").exists)
    # Use the real Storage Access Framework, not a direct preferences write.
    if D(description="Show roots").exists:
        D(description="Show roots").click()
        if D(text="Downloads").exists(timeout=2):
            tap_fresh(D(text="Downloads"))
    field = D(resourceId="android:id/text1", className="android.widget.EditText")
    if not field.exists:
        field = D(className="android.widget.EditText")
    if field.exists:
        field.set_text("e2e-settings-" + ROUTE + ".txt")
    # Finish IME input before touching the moving SAF save footer.
    D.press("back")
    time.sleep(.4)
    save = D(textMatches="(?i)save")
    wait(lambda: save.exists and save.info.get("enabled"), timeout=8)
    save.click()
    wait(lambda: D.app_current().get("package") == PKG, timeout=10)
    wait(lambda: shell("test", "-f", "/sdcard/Download/e2e-settings-" + ROUTE + ".txt", check=False).strip() == ""
         and "theme" in shell("cat", "/sdcard/Download/e2e-settings-" + ROUTE + ".txt", check=False))
    raw = shell("cat", "/sdcard/Download/e2e-settings-" + ROUTE + ".txt")
    assert KEY not in raw and "light" in raw
    open_app()
    click("Dark", scroll=True)
    wait(lambda: prefs().get("theme") == "dark")
    click("Import settings", scroll=True)
    if D(description="Show roots").exists(timeout=2):
        D(description="Show roots").click()
        if D(text="Downloads").exists(timeout=2):
            tap_fresh(D(text="Downloads"))
    click("e2e-settings-" + ROUTE + ".txt")
    wait(lambda: prefs().get("theme") == "light")
    assert prefs().get("key") == KEY
    return "SAF export/import round trip restored theme; pairing key excluded from export and retained in app."

def restart():
    samples = []
    for i in range(8):
        D.app_stop(PKG)
        open_app()
        wait(unauthenticated_ready)
        assert prefs().get("key") == KEY
        samples.append({"cycle": i + 1, "meminfo": shell("dumpsys", "meminfo", "--package", PKG)})
    (OUT / "helper-restart-memory.json").write_text(json.dumps(samples), encoding="utf8")
    return "8 real force-stop/restart cycles, same key, bound port recovered. PSS observations; no leak threshold invented."

def visible_label(label, contains=False):
    selectors = (D(textContains=label), D(descriptionContains=label)) if contains else (D(text=label), D(description=label))
    width, height = D.window_size()
    for selector in selectors:
        if selector.exists:
            info = selector.info
            bounds = info.get("visibleBounds") or info.get("bounds") or {}
            if (bounds.get("right", 0) > bounds.get("left", 0) and
                    0 <= bounds.get("top", -1) < bounds.get("bottom", 0) <= height - 60):
                return selector

def swipe_find(label, contains=False, limit=8):
    for attempt in range(limit + 1):
        selector = visible_label(label, contains)
        if selector:
            return selector
        if attempt == limit:
            break
        # Compose menus can scroll without publishing scrollable=true.
        # Exercise the same physical gesture a user makes, inside the list.
        width, height = D.window_size()
        D.swipe(width*.78, height*.80, width*.78, height*.34, duration=.45)
        time.sleep(.3)
    shot("missing-" + re.sub(r"\W+", "-", label))
    raise AssertionError("Visible label not reached by real swipes: " + label)

def firefox_prepare():
    if int(shell("getprop", "ro.build.version.sdk").strip()) >= 33:
        shell("pm", "grant", FIREFOX, "android.permission.POST_NOTIFICATIONS")
    shell("cmd", "role", "add-role-holder", "--user", "0", "android.app.role.BROWSER", FIREFOX)
    D.app_start(FIREFOX)
    for _ in range(8):
        found = False
        for text in ("Not now", "Skip", "Start browsing", "Continue", "Get started"):
            if D(text=text).exists(timeout=.4):
                try:
                    D(text=text).click(timeout=.5)
                except Exception as exc:
                    if type(exc).__name__ != "UiObjectNotFoundError" and "StaleObjectException" not in str(exc): raise
                    continue
                found = True
                time.sleep(.5)
                break
        if not found:
            break
    launch_url("http://127.0.0.1:8765/health")
    menu = D(description="More options")
    if not menu.exists(timeout=3):
        menu = D(resourceIdMatches=".*:(id/)(menuButton|mozac_browser_menu_button|menu_button)")
    assert menu.exists, "Firefox browser menu not found"
    menu.click()
    more = visible_label("More Collapsed")
    if more:
        more.click()
    shot("firefox-before-settings")
    swipe_find("Settings").click()
    shot("firefox-settings")
    row = swipe_find("Remote debugging", contains=True)
    # Every route starts with pm clear Firefox, so this toggle starts disabled.
    row.click()
    shot("firefox-remote-debug-enabled")
    D.press("back")
    return "Fresh real Firefox profile; Remote debugging via USB enabled through actual UI swipes/toggle; Firefox provisioned as emulator default browser."

def launch_url(url):
    global NAVIGATION_INITIALIZED
    if D.app_current().get("package") != FIREFOX:
        D.app_start(FIREFOX, stop=False)
        wait(lambda: D.app_current().get("package") == FIREFOX, timeout=10)
    for _ in range(3):
        dismiss_addon_notice()
        until = time.monotonic() + 4
        bar = None
        while time.monotonic() < until:
            dismiss_addon_notice()
            bar = visible_label("Search or enter address", contains=True)
            if bar:
                break
            time.sleep(.2)
        if bar:
            bar.click()
            edit = D(className="android.widget.EditText")
            wait(lambda: edit.exists, timeout=5)
            edit.set_text(url)
            D.press("enter")
            NAVIGATION_INITIALIZED = True
            time.sleep(.8)
            return
        if not NAVIGATION_INITIALIZED:
            # The supported development installer restarts Firefox. A single
            # initial VIEW intent can restore its tab; later navigations reuse UI.
            shell("am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", url,
                  "--es", "com.android.browser.application_id", "android-e2e-coordinator", "-p", FIREFOX)
            NAVIGATION_INITIALIZED = True
            time.sleep(1)
            dismiss_addon_notice()
            return
        if D.app_current().get("package") != FIREFOX:
            raise AssertionError("Firefox lost foreground before navigation")
        D.press("back")
        time.sleep(.4)
    raise AssertionError("Firefox address bar unavailable; no unbounded new-tab fallback")

def play_fixture():
    wait(lambda: D(text="Play fixture").exists or D(description="Play fixture").exists, timeout=15)
    click("Play fixture")

def extension():
    global WEB_EXT, NAVIGATION_INITIALIZED
    log = (OUT / "web-ext.log").open("w", encoding="utf8")
    cmd = ["web-ext", "run", "--target=firefox-android", "--android-device=" + os.environ["ANDROID_SERIAL"],
           "--firefox-apk=" + FIREFOX, "--source-dir=" + (os.environ["KADRI_DIST"] if ROUTE == "kadri" else str(Path("e2e-bundle/violentmonkey").resolve())),
           "--no-reload", "--no-input", "--verbose"]
    WEB_EXT = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
    until = time.monotonic() + 100
    while time.monotonic() < until:
        for text in ("Allow", "OK"):
            if D(text=text).exists(timeout=0.2):
                D(text=text).click()
        data = (OUT / "web-ext.log").read_text(errors="replace")
        if WEB_EXT.poll() is not None:
            raise AssertionError("web-ext exited; see web-ext.log")
        if re.search(r"Installed.*temporary|Installed.*addon|installTemporaryAddon.*addon|reloadable", data, re.I):
            break
        if "kadri@owndot" in data and "Installed" in data:
            break
        time.sleep(1)
    # Firefox can defer this native notice until the browser returns to foreground.
    time.sleep(.5)
    dismiss_addon_notice()
    NAVIGATION_INITIALIZED = False  # web-ext explicitly restarted Firefox
    if ROUTE == "userscript":
        # The real manager intercepts a .user.js navigation and opens its own
        # installer; clicking Install exercises its actual storage/GM execution.
        launch_url("http://127.0.0.1:8765/video-assistent.user.js")
        def press_install():
            dismiss_addon_notice()
            dismiss_translation_sheet()
            install = D(textMatches="(?i)(Install|Confirm installation)")
            if not install.exists or not install.info.get("enabled"): return False
            try:
                # The translation sheet can arrive between lookup and touch.
                # Retry only a lookup that sent no input, not the installation.
                install.click(timeout=.5)
                return True
            except Exception as exc:
                if type(exc).__name__ == "UiObjectNotFoundError" or "StaleObjectException" in str(exc):
                    return False
                raise
        wait(press_install, timeout=60)
        wait(lambda: visible_label("Script installed", contains=True)
             or visual.phrase(D.screenshot(), "Script installed"), timeout=90)
        shot("actual-userscript-install")
    # UI appearance on a real fixture is the acceptance criterion, not a guessed log line.
    launch_url("http://127.0.0.1:8765/page/direct")
    play_fixture()
    wait(entry_control, timeout=25)
    return ("Unmodified dist/firefox" if ROUTE == "kadri" else "Pinned real Violentmonkey + unmodified userscript via manager Install UI") + " loaded into Firefox Android; real player entry visible."

def pair():
    global AUTH_KEY
    assert prefs().get("paired") != "true", "Setup must not pre-pair through an authenticated probe"
    open_app()
    click("Connect", scroll=True)
    # Never accept the emulator's WebView Browser Tester as a pairing route.
    # Select the actual installed Firefox; do not click an unselected "Just once".
    time.sleep(.8)
    shot("browser-chooser")
    selected = False
    until = time.monotonic() + 15
    while time.monotonic() < until:
        if D.app_current().get("package") == FIREFOX:
            break
        target = visible_label("Firefox", contains=True)
        if target and D.app_current().get("package") in ("android", "com.android.intentresolver"):
            target.click()
            selected = True
        once = D(textMatches="(?i)just once")
        if selected and once.exists: once.click()
        time.sleep(.3)
    assert D.app_current().get("package") == FIREFOX, "Pairing intent did not reach installed Firefox"
    wait(lambda: label_control("Connected.") or label_control("Verbunden."), timeout=20)
    paired_state = prefs()
    assert paired_state.get("paired") == "true" and paired_state.get("key") == KEY
    (OUT / "pairing-proof.json").write_text(json.dumps({"visible_confirmation": True, "native_paired": True, "native_key_unchanged": True}))
    AUTH_KEY = KEY
    # Fragment is removed by the real content script before success is displayed.
    open_app()
    D(scrollable=True).scroll.toBeginning()
    assert D(textContains="Connected").exists
    return "App Connect → installed Firefox → actual extension/manager storage and Helper ping; paired preference confirmed. Firefox default role was ADB provisioning."

def player_entry(kind="direct"):
    launch_url("http://127.0.0.1:8765/page/" + kind)
    play_fixture()
    return wait(entry_control)

def reveal():
    global LAST_REVEAL_AT
    dismiss_fullscreen_tutorial()
    # Gecko can retain a Close AX node while CSS opacity hides the controls.
    # Confirm actual rendered pixels before treating them as visible.
    if icon_control("P.close"): return True
    now = time.monotonic()
    if now - LAST_REVEAL_AT > .9:
        w, h = D.window_size()
        D.click(w*.5, h*.35)
        LAST_REVEAL_AT = time.monotonic()  # after actual injected UP, not before RPC latency
        time.sleep(.25)
    return bool(icon_control("P.close"))

def playing_times(evidence=None):
    # Recognition and clock reading use the same real frame, never a later
    # screenshot taken after the verified controls may already have disappeared.
    def captured_controls():
        frame=D.screenshot()
        if visual.locate(frame,"P.close",DENSITY):
            return frame
        reveal()
        return None
    for attempt in range(3):
        frame=wait(captured_controls, timeout=8, dialogs=False)
        w,h=frame.size
        rows=visual.words(frame.crop((0,int(h*.65),w,h)))
        labels=sorted({r["text"] for r in rows if r["left"]<w*.30
                       and re.fullmatch(r"\d{1,2}:\d{2}",r["text"])})
        if labels:
            if evidence: frame.save(OUT / (evidence + ".png"))
            return labels
        frame.save(OUT/("clock-not-readable-"+str(attempt)+".png"))
    raise AssertionError("Own-player clock not readable after three real visible-control snapshots")

def media_clock_seconds(labels):
    assert labels, "Visible player clock missing"
    return min(int(t.split(":")[0])*60 + int(t.split(":")[1]) for t in labels)

def player():
    button = player_entry()
    time.sleep(2)
    started = time.monotonic()
    button.click()
    wait(reveal, timeout=25)
    controls_ms = round((time.monotonic() - started) * 1000, 1)
    reveal()
    first = playing_times()
    frame1 = D.screenshot().convert("RGB")
    frame1.save(OUT / "player-visible-frame-1.png")
    time.sleep(2)
    reveal()
    second = playing_times()
    frame2 = D.screenshot().convert("RGB")
    frame2.save(OUT / "player-visible-frame-2.png")
    width, height = frame1.size
    box = (int(width * .25), int(height * .35), int(width * .75), int(height * .60))
    crop1, crop2 = frame1.crop(box), frame2.crop(box)
    assert max(ImageStat.Stat(crop2).stddev) > 20, "Owned moving test pattern not visible in video region"
    motion = ImageChops.difference(crop1, crop2).convert("L")
    changed = sum(value for level, value in enumerate(motion.histogram()) if level > 10)
    assert changed > crop1.width * crop1.height * .005, "No visible changing video pixels (clock alone is insufficient)"
    assert media_clock_seconds(second) > media_clock_seconds(first), "Player clock did not advance: " + repr([first, second])
    return {"clock_before": first, "clock_after": second, "changed_video_pixels": changed,
            "entry_to_controls_ms": controls_ms,
            "note": "UI latency/clock evidence. Not a first-decoded-frame measurement."}


def play_pause():
    click("Wiedergabe") if D(description="Wiedergabe").exists else click("Play")
    wait(lambda: icon_control("P.play"), timeout=5)
    time.sleep(.5)
    paused = playing_times()
    shot("player-paused-visible")
    time.sleep(1.2)
    after_pause = playing_times()
    assert paused and after_pause == paused, "Paused clock advanced: " + repr([paused, after_pause])
    click("Wiedergabe") if D(description="Wiedergabe").exists else click("Play")
    wait(lambda: icon_control("P.pause"), timeout=5)
    resumed = playing_times()
    time.sleep(1.2)
    after_resume = playing_times()
    assert media_clock_seconds(after_resume) > media_clock_seconds(resumed), "Resumed clock did not advance"
    return {"paused_clock": paused, "resumed_clock": after_resume,
            "note":"Rendered play/pause glyph, actual touches and rendered clock; handoff is checked independently."}


def pointer_rows(since):
    with urllib.request.urlopen("http://127.0.0.1:8765/events", timeout=3) as response:
        return sorted((r for r in json.load(response) if r.get("event")=="pointer" and r.get("received",0)>=since), key=lambda r:r["at"])

def completed_pointer_rows(since, ups=1):
    rows=pointer_rows(since)
    return rows if sum(r["phase"]=="pointerup" for r in rows)>=ups else None

def assert_swipe_input(since):
    rows=wait(lambda: completed_pointer_rows(since),timeout=5)
    down=next((r for r in rows if r["phase"]=="pointerdown"),None)
    moved=next((r for r in rows if r["phase"]=="pointermove-threshold"),None)
    if not down or not moved or not down.get("trusted") or not moved.get("trusted"):
        raise InvalidInput("Harness swipe did not deliver a trusted movement beyond the player's 14px threshold")
    elapsed=moved["at"]-down["at"]
    if elapsed>=450:
        raise InvalidInput("Harness swipe crossed 14px only after "+str(elapsed)+"ms; player long-press activates at 450ms")
    return {"threshold_ms":elapsed,"total_ms":rows[-1]["at"]-down["at"]}

def gestures():
    # Each assertion gets a fresh clip, rather than inheriting a 60s movie at EOF.
    player_entry().click()
    wait(reveal)
    # Establish a real Android positive control independently of browser input.
    # Only the following swipes are claimed as extension-to-Helper integration.
    original = request("/ping")
    request("/vol?v=0.4")
    request("/bright?v=0.65")
    positive = request("/ping")
    assert .2 <= positive["vol"] <= .6, "Native volume positive control failed"
    time.sleep(3)  # one real player heartbeat observes device baseline
    before = request("/ping")
    (OUT / "gesture-positive-control.json").write_text(json.dumps({
        "initial_volume":original["vol"],"native_volume":positive["vol"],
        "before_swipe_volume":before["vol"],"before_swipe_brightness":before["bright"]}))
    fresh_input_controls()
    w, h = D.window_size()
    volume_input_at=time.monotonic()
    D.swipe(w * .85, h * .58, w * .85, h * .46, duration=.18)
    volume_input=assert_swipe_input(volume_input_at)
    after_vol = wait(lambda: (s if (s := request("/ping"))["vol"] != before["vol"] else None))
    assert after_vol["vol"] > before["vol"], "Upward volume swipe reduced volume"
    brightness_input_at=time.monotonic()
    D.swipe(w * .15, h * .40, w * .15, h * .50, duration=.18)
    brightness_input=assert_swipe_input(brightness_input_at)
    after_bright = wait(lambda: (s if (s := request("/ping"))["bright"] != before["bright"] else None))
    assert after_bright["bright"] < before["bright"]
    return {"before": before, "volume_after": after_vol["vol"], "brightness_after": after_bright["bright"],
            "volume_input":volume_input,"brightness_input":brightness_input,
            "note": "Actual Android positive control, then real UI swipes through extension and Helper"}

def paused_seek_clock(before):
    """Observe a paused seek; normal playback must never satisfy this assertion."""
    baseline = media_clock_seconds(before)
    observations = []
    def observed():
        labels = playing_times("seek-clock-" + str(len(observations)))
        delta = media_clock_seconds(labels) - baseline
        observations.append({"clock": labels, "delta": delta})
        assert icon_control("P.play"), "Seek unexpectedly resumed the paused player"
        assert -1 <= delta <= 11, "Unexpected paused seek displacement: " + repr(observations)
        return labels if 9 <= delta <= 11 else None
    try:
        return wait(observed, timeout=8, dialogs=False)
    finally:
        (OUT / "seek-clock-observations.json").write_text(json.dumps(observations, indent=2))

def seek_restore():
    player_entry().click()
    wait(reveal)
    # A screenshot immediately after a seek may still contain the old HUD.
    # Pause first so a delayed acknowledgement cannot pass by ordinary playback.
    click("Wiedergabe") if D(description="Wiedergabe").exists else click("Play")
    wait(lambda: icon_control("P.play"), timeout=5, dialogs=False)
    observed_at = time.monotonic()
    before = playing_times("seek-before")
    time.sleep(.6)
    assert playing_times() == before, "Seek baseline is not paused"
    sel = D(descriptionMatches=r".*(s vor|s forward).*")
    if sel.exists:
        sel.click()
    else:
        # The existing public double-tap gesture also works when Gecko omits AX.
        w, h = D.window_size()
        double_at=time.monotonic()
        D.double_click(w*.80, h*.50, duration=.01)
        rows=wait(lambda: completed_pointer_rows(double_at,2),timeout=5)
        ups=[r for r in rows if r["phase"]=="pointerup"]
        gap=ups[1]["at"]-ups[0]["at"]
        if not all(r.get("trusted") for r in ups[:2]) or not 0<gap<300:
            raise InvalidInput("Harness double tap up/up interval "+str(gap)+"ms misses the player's 300ms window")
    after = paused_seek_clock(before)
    own_time = media_clock_seconds(after)
    sampled_at = time.monotonic()
    click("Schließen") if D(description="Schließen").exists else click("Close")
    def restored():
        with urllib.request.urlopen("http://127.0.0.1:8765/events") as response:
            events = json.load(response)
        times = [e["time"] for e in events if e.get("event") == "seeked"
                 and e.get("received", 0) >= observed_at and "time" in e]
        if not times:
            return None
        # UI clock rounds to whole seconds; allowance follows measured elapsed
        # time until close, not an arbitrary player performance threshold.
        upper = own_time + (time.monotonic() - sampled_at) + 1.5
        assert any(own_time - 1 <= t <= upper for t in times), "Original did not restore the actual own-player position"
        return times
    times = wait(restored, timeout=5)
    return {"clock_before": before, "clock_after": after, "new_fixture_seek_events": times}

def source(kind):
    launch_url("http://127.0.0.1:8765/page/" + kind)
    play_fixture()
    button = wait(entry_control, timeout=25)
    button.click()
    reveal()
    a = playing_times()
    time.sleep(2)
    reveal()
    b = playing_times()
    assert a and b and a != b, kind + " clock not advancing"
    shot("source-" + kind)
    click("Schließen") if D(description="Schließen").exists else click("Close")
    return kind + ": real Firefox playback/own player clock advanced"

def ui_helper_download():
    fresh_input_controls()
    selector = D(descriptionContains="Herunterladen")
    if not selector.exists:
        selector = D(descriptionContains="Download")
    if not selector.exists:
        selector = icon_control("I.download")
    assert selector, "No visible download control"
    selector.long_click(duration=.7)
    option = wait(lambda: label_control("With the helper") or label_control("Mit dem Helper"), timeout=8)
    option.click()


def downloads():
    # Trigger downloads using actual user clicks; no direct /download API invocation.
    player_entry("download?label=" + ROUTE).click()
    wait(reveal)
    reveal()
    before = set(shell("ls", "/sdcard/Download/Video-Assistent", check=False).splitlines())
    download_start = time.monotonic()
    ui_helper_download()
    click("Close")
    player_entry("download-other?label=" + ROUTE).click()
    wait(reveal)
    ui_helper_download()
    # A separate real browser/player handoff while the two slow downloads run.
    reveal()
    click("Schließen") if D(description="Schließen").exists else click("Close")
    resolving_at = time.monotonic()
    launch_url("http://127.0.0.1:8765/page/redirect")
    play_fixture()
    button = wait(entry_control)
    button.click()
    wait(reveal)
    resolved_at = time.monotonic()
    player_during_download_ms = round((resolved_at - resolving_at) * 1000, 1)
    media = {"/slow/" + name: Path("tests/android_e2e/generated/" + name) for name in ("parallel-one.mp4", "parallel-two.mp4")}
    expected_sizes = {str(path.stat().st_size) for path in media.values()}
    expected_hashes = {hashlib.sha256(path.read_bytes()).hexdigest() for path in media.values()}
    def finished():
        now = set(shell("ls", "/sdcard/Download/Video-Assistent", check=False).splitlines())
        files = [x for x in now - before if x.endswith(".mp4") and
                 shell("stat", "-c", "%s", "/sdcard/Download/Video-Assistent/" + x, check=False).strip() in expected_sizes]
        return files if len(files) >= 2 else None
    files = wait(finished, timeout=60)
    hashes = {}
    for i, filename in enumerate(files):
        local = OUT / ("download-" + str(i) + ".mp4")
        adb("pull", "/sdcard/Download/Video-Assistent/" + filename, str(local))
        hashes[filename] = hashlib.sha256(local.read_bytes()).hexdigest()
        assert local.stat().st_size > 10000
        local.unlink()  # artifacts retain hashes and screen evidence, not duplicate owned media
    assert set(hashes.values()) == expected_hashes, "Saved videos do not match both distinct fixtures"
    def overlapping():
        with urllib.request.urlopen("http://127.0.0.1:8765/events") as response:
            events = json.load(response)
        begins = {e["id"]: e for e in events if e.get("event") == "request"
                  and e.get("path") in media and not e.get("range") and not e.get("fetch_dest") and e["received"] >= download_start}
        ends = [e for e in events if e.get("event") == "transfer_end" and e.get("id") in begins
                and e.get("complete") and e.get("bytes") == media[e["path"]].stat().st_size]
        for i, one in enumerate(ends):
            for two in ends[i+1:]:
                if one["path"] == two["path"]: continue
                left = max(begins[one["id"]]["received"], begins[two["id"]]["received"])
                right = min(one["received"], two["received"])
                if left <= resolving_at and right >= resolved_at:
                    return {"both_active_through_handoff": True, "overlap_ms": round((right-left)*1000, 1),
                            "first_request": one["id"], "second_request": two["id"]}
    overlap = wait(overlapping, timeout=5)
    return {"parallel_ui_downloads": len(files), "sha256": hashes, "server_transfer_evidence": overlap,
            "browser_handoff_during_downloads_ms": player_during_download_ms,
            "note": "Actual concurrent complete transfers and two saved-file hashes; UI/redirect timing, not Chrome-DNR resolution timing"}


def large_download():
    button = player_entry("large?label=" + ROUTE)
    button.click()
    wait(reveal)
    before = set(shell("ls", "/sdcard/Download/Video-Assistent", check=False).splitlines())
    expected_path = Path("tests/android_e2e/generated/large.mp4")
    expected_size = expected_path.stat().st_size
    samples = []
    stop = threading.Event()
    def sample():
        while not stop.is_set():
            row = {"at": time.monotonic()}
            for pkg in (PKG, FIREFOX, "com.android.providers.downloads"):
                row[pkg] = shell("dumpsys", "meminfo", "--package", pkg, check=False)
            samples.append(row)
            stop.wait(1)
    sampler = threading.Thread(target=sample, daemon=True)
    sampler.start()
    started = time.monotonic()
    try:
        ui_helper_download()
        def finished():
            now = set(shell("ls", "/sdcard/Download/Video-Assistent", check=False).splitlines())
            for filename in now - before:
                if filename.endswith(".mp4") and shell("stat", "-c", "%s",
                        "/sdcard/Download/Video-Assistent/" + filename, check=False).strip() == str(expected_size):
                    return filename
        filename = wait(finished, timeout=100)
    finally:
        stop.set()
        sampler.join(timeout=10)
        (OUT / "large-download-memory.json").write_text(json.dumps(samples))
    elapsed = round(time.monotonic() - started, 3)
    local = OUT / "large-downloaded.mp4"
    adb("pull", "/sdcard/Download/Video-Assistent/" + filename, str(local), timeout=90)
    with local.open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    with expected_path.open("rb") as stream:
        expected = hashlib.file_digest(stream, "sha256").hexdigest()
    local.unlink()
    assert actual == expected, "Large download bytes corrupted"
    return {"bytes": expected_size, "sha256": actual, "seconds_including_ui": elapsed,
            "memory_samples": len(samples),
            "note": "128MiB cookie-protected file through native credentialed streaming and MediaStore, with actual hash and package PSS observations. Not a formal peak-memory bound, multi-GB or physical power measurement."}


def background():
    reveal()
    # Firefox may implement only automatic Android PiP. Verify Android's actual
    # pinned task, not a simulated JS pictureInPictureElement.
    D.press("home")
    time.sleep(2)
    dump = shell("dumpsys", "activity", "activities")
    pip = bool(re.search(r"mode=pinned|windowingMode=2|mWindowingMode=2", dump))
    (OUT / "pip-activity.txt").write_text(dump)
    D.app_start(FIREFOX)
    wait(reveal, timeout=15)
    assert player_controls_visible()
    if not pip:
        raise Blocked("Browser returned to player; Android did not expose a pinned PiP task in this route. See recording/dumpsys.")
    return "Android pinned PiP task present after Home; player restored on returning."

def subtitles():
    player_entry().click()
    wait(reveal)
    reveal()
    click("Untertitel") if D(description="Untertitel").exists else click("Subtitles")
    click("E2E English")
    wait(lambda: visual.subtitle_visible(D.screenshot(), "ANDROID E2E SUBTITLE"))
    shot("rendered-owned-subtitle")
    click("Schließen") if D(description="Schließen").exists else click("Close")
    return "VTT selected through actual player menu; rendered owned subtitle confirmed by screenshot OCR."

def switch_source():
    button = player_entry()
    button.click()
    wait(reveal)
    reveal()
    click("Schließen") if D(description="Schließen").exists else click("Close")
    click("Switch fixture source")
    button = wait(entry_control)
    button.click()
    time.sleep(1)
    reveal()
    a = playing_times()
    time.sleep(2)
    reveal()
    b = playing_times()
    with urllib.request.urlopen("http://127.0.0.1:8765/events") as response:
        events = json.load(response)
    assert any(e.get("path") == "/media/alternate.mp4" for e in events)
    assert a and b and a != b, "Replacement source is not playing"
    click("Schließen") if D(description="Schließen").exists else click("Close")
    return "User changed site's video source, reopened own player, alternate source requested and clock advances. Not a quality-menu assertion."

def rotation():
    player_entry().click()
    wait(reveal)
    D.set_orientation("l")
    wait(lambda: D.window_size()[0] > D.window_size()[1])
    reveal()
    a = playing_times()
    time.sleep(2)
    reveal()
    b = playing_times()
    assert a and b and a != b
    D.set_orientation("n")
    wait(lambda: D.window_size()[1] > D.window_size()[0])
    reveal()
    click("Schließen") if D(description="Schließen").exists else click("Close")
    return "Real display orientation changes both ways; player clock advances in landscape. Screenshot records fullscreen/chrome geometry."

def cancel_download():
    player_entry("slowhls").click()
    wait(reveal)
    reveal()
    ui_helper_download()
    time.sleep(.5)
    D.open_notification()
    # Expand download notification if actions are collapsed.
    cancel = D(textMatches="(?i)(Cancel|Abbrechen)")
    if not cancel.exists(timeout=2):
        w, h = D.window_size()
        D.swipe(w * .5, h * .2, w * .5, h * .65, duration=.5)
    assert cancel.exists(timeout=5), "Download notification has no visible cancellation action"
    cancel.click()
    D.press("back")
    def cancelled():
        for ident in range(101, 121):
            if request("/dlstate?id=" + str(ident)).get("state") == "abgebrochen":
                return ident
    ident = wait(cancelled, timeout=25)
    return {"cancelled_job": ident, "note": "Actual Android notification Cancel button; helper state confirmed"}

def opening_cycles():
    samples = []
    button = player_entry()
    for i in range(6):
        if i:
            button = wait(entry_control)
        start = time.monotonic()
        button.click()
        wait(reveal)
        samples.append({"cycle": i + 1, "entry_to_controls_ms": round((time.monotonic() - start) * 1000, 1),
            "helper_pss": shell("dumpsys", "meminfo", "--package", PKG),
            "firefox_pss": shell("dumpsys", "meminfo", "--package", FIREFOX),
            "cpu": shell("dumpsys", "cpuinfo")})
        click("Schließen") if D(description="Schließen").exists else click("Close")
    (OUT / "player-cycle-observations.json").write_text(json.dumps(samples), encoding="utf8")
    return "6 real open/close cycles in the same page/tab; PSS/CPU observations. No navigation/GC reset or leak verdict inferred."

def native_context():
    """Restore the actual test app after another case's system-UI failure."""
    global KEY, AUTH_KEY
    # Browser scenarios are finished; isolate native API checks from Firefox PiP.
    D.app_stop(FIREFOX)
    open_app()
    wait(unauthenticated_ready)
    KEY = prefs()["key"]
    AUTH_KEY = KEY
    assert request("/ping")["ok"]

def native_hls_download():
    native_context()
    before = set(shell("ls", "/sdcard/Download/Video-Assistent", check=False).splitlines())
    params = urllib.parse.urlencode({"u": "http://127.0.0.1:8765/media/hls/index.m3u8",
        "ref": "http://127.0.0.1:8765/page/hls", "c": "e2e_sentinel=disposable",
        "t": "native-e2e-hls-" + ROUTE, "k": "hls"})
    response = request("/download?" + params)
    assert response["ok"]
    def done():
        state = request("/dlstate?id=" + str(response["id"])).get("state", "")
        if state.startswith("Fehler"):
            raise AssertionError(state)
        return state == "fertig"
    wait(done, timeout=90)
    saved = set(shell("ls", "/sdcard/Download/Video-Assistent", check=False).splitlines()) - before
    assert saved, "Native HLS was not saved"
    for index, name in enumerate(saved):
        local = OUT / ("native-hls-" + str(index) + ".mp4")
        adb("pull", "/sdcard/Download/Video-Assistent/" + name, str(local))
        probe = json.loads(command("ffprobe", "-v", "error", "-show_format", "-show_streams", "-of", "json", str(local)))
        local.unlink()
        (OUT / ("native-hls-" + str(index) + "-probe.json")).write_text(json.dumps(probe, indent=2))
        assert any(stream.get("codec_type") == "video" for stream in probe["streams"])
        assert any(stream.get("codec_type") == "audio" for stream in probe["streams"])
        assert 59 < float(probe["format"]["duration"]) < 62
    return "Installed APK fetched HLS with native Android code, saved via MediaStore; ffprobe verified video/audio and fixture duration. API integration."


def native_cookie_redirect(kind="hls"):
    native_context()
    # This is deliberately labeled real Android HTTP/API integration, not UI interaction.
    start = time.monotonic()
    before_files = set(shell("ls", "/sdcard/Download/Video-Assistent", check=False).splitlines())
    params = urllib.parse.urlencode({
        "u": "http://127.0.0.1:8765/redirect?cross=1&to=" + ("/sink" if kind == "hls" else "/media/clip.mp4"),
        "ref": "http://127.0.0.1:8765/page/direct", "c": "e2e_sentinel=disposable",
        "t": "e2e-cookie-boundary-" + kind, "k": kind})
    result = request("/download?" + params)
    assert result["ok"]
    def seen():
        with urllib.request.urlopen("http://127.0.0.1:8765/events") as response:
            data = json.load(response)
        rows = [e for e in data if e.get("event") == "request" and e.get("path") == ("/sink" if kind == "hls" else "/media/clip.mp4") and e.get("received", 0) >= start and e.get("host") == "localhost:8765"]
        original = [e for e in data if e.get("event") == "request" and e.get("path") == "/redirect" and e.get("received", 0) >= start and e.get("host") == "127.0.0.1:8765"]
        return (rows, original) if rows else None
    try:
        rows, original = wait(seen, timeout=20)
    except AssertionError:
        raise AssertionError("Installed APK did not reach redirect destination: " + str(request("/dlstate?id=" + str(result["id"]))))
    assert original and any(e["cookie"] for e in original), "Control missing: first hop did not receive the intended disposable cookie"
    assert all(not e["cookie"] and not e["authorization"] for e in rows), "Credentials crossed Android HTTP redirect"
    if kind == "file":
        def done():
            state = request("/dlstate?id=" + str(result["id"])).get("state", "")
            assert not state.startswith("Fehler"), state
            return state == "fertig"
        wait(done, timeout=60)
        files = set(shell("ls", "/sdcard/Download/Video-Assistent", check=False).splitlines()) - before_files
        assert files, "Credentialed download was not saved"
        expected = hashlib.sha256(Path("tests/android_e2e/generated/clip.mp4").read_bytes()).hexdigest()
        verified = False
        for name in files:
            local = OUT / "credentialed-download.mp4"
            adb("pull", "/sdcard/Download/Video-Assistent/" + name, str(local))
            with local.open("rb") as stream:
                verified = verified or hashlib.file_digest(stream, "sha256").hexdigest() == expected
            local.unlink()
        assert verified, "Credentialed native download bytes differ from fixture"
    return "Actual installed APK " + kind + " fetch: first hop received disposable Cookie, cross-site destination received none." + (" Saved file verified by exact hash." if kind == "file" else " The redirect destination is intentionally not a valid playlist; native HLS playback/download is checked separately.") + " Authorization was not supplied."


def paired_restart():
    if icon_control("P.close") or reveal():
        click("Close")
    old = KEY
    D.app_stop(PKG)
    open_app()
    wait(unauthenticated_ready)
    assert prefs().get("key") == old and prefs().get("paired") == "true"
    # Reopen with the original browser storage; no /pair navigation or GM writes.
    button = player_entry()
    button.click()
    wait(reveal)
    before = request("/ping")
    w, h = D.window_size()
    if before["vol"] >= .85:
        request("/vol?v=0.4")
        time.sleep(3)
        before = request("/ping")
    D.swipe(w*.85, h*.58, w*.85, h*.46, duration=.5)
    wait(lambda: request("/ping")["vol"] > before["vol"])
    click("Schließen") if D(description="Schließen").exists else click("Close")
    return "Real Helper force-stop/restart retained key and pairing; existing browser storage controlled Android volume without re-pairing."

def reset_pairing():
    global KEY, AUTH_KEY
    old = KEY
    open_app()
    D(scrollable=True).scroll.toBeginning()
    row = D(text="Connect to the browser")
    if not row.exists:
        D(scrollable=True).scroll.to(text="Connect to the browser")
    row.long_click(duration=1.2)
    wait(lambda: prefs().get("paired") == "false" and prefs().get("key") != old)
    KEY = prefs()["key"]
    try:
        with urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:14781/ping",
                headers={"X-EVP-Key": old}), timeout=5):
            raise AssertionError("Reset failed to revoke old pairing key")
    except urllib.error.HTTPError as exc:
        assert exc.code == 403
    pair()
    assert request("/ping")["ok"]
    return "Long-pressed native pairing card, old key rejected (403), re-paired using actual browser page and new disposable key."


def port_conflict():
    D.app_start("de.evp.helfer.e2ecompeting")
    wait(lambda: D(textContains="47811").exists)
    # Actual owner still responds to its key, competing app cannot claim running.
    assert request("/ping")["ok"]
    assert not D(textContains="● Running").exists
    return "Two installed test APKs competed for same real socket; second app displayed port conflict, first stayed reachable."

def crash_check():
    data = adb("logcat", "-d", "-v", "threadtime")
    (OUT / "logcat.txt").write_text(redact(data), encoding="utf8")
    # AndroidRuntime groups can span multiple lines. Keep complete evidence.
    crashes = [b for b in re.split(r"(?=FATAL EXCEPTION)", data) if
               b.startswith("FATAL EXCEPTION") and
               re.search(r"Process: (?:de\.evp\.helfer(?:\.debug|\.e2ecompeting)?|org\.mozilla\.firefox)\b", b[:2500])]
    assert not crashes, "App crash recorded; see logcat.txt"
    for package in (PKG, FIREFOX):
        exits = shell("dumpsys", "activity", "exit-info", package)
        (OUT / (package + "-exit-info.txt")).write_text(exits)
        assert not re.search(r"reason=(?:4|5|6)\s*\((?:CRASH|CRASH_NATIVE|ANR)\)", exits), "Crash/native crash/ANR exit reason for " + package
    return "No target Java fatal exception or recorded crash/native-crash/ANR exit reason observed."

def main():
    global D, DENSITY
    assert shell("getprop", "ro.kernel.qemu").strip() == "1", "Only disposable Android emulators are allowed"
    D = u2.connect(os.environ["ANDROID_SERIAL"])
    density_text = shell("wm", "density")
    DENSITY = int(re.findall(r"\d+", density_text)[-1]) / 160
    adb("forward", "tcp:14781", "tcp:47811")
    adb("reverse", "tcp:8765", "tcp:8765")
    adb("logcat", "-c")
    provenance = {
        "route": ROUTE, "scope": SCOPE,
        "automation_commit": os.environ.get("GITHUB_SHA"),
        "helper_commit": os.environ.get("HELPER_COMMIT"), "kadri_commit": os.environ.get("KADRI_COMMIT"),
        "api": shell("getprop", "ro.build.version.sdk").strip(),
        "android": shell("getprop", "ro.build.version.release").strip(),
        "firefox": shell("dumpsys", "package", FIREFOX),
        "fixture_transport": "adb reverse, device loopback; not a Wi-Fi/LAN throughput claim",
        "permissions": "ADB grants overlay/notification/idle exemption, real UI verifies app",
        "extension": "unmodified dist/firefox" if ROUTE == "kadri" else "real Violentmonkey with unmodified userscript; upstream @require remains network-fetched",
    }
    (OUT / "provenance.json").write_text(json.dumps(provenance, indent=2), encoding="utf8")
    case("helper_setup", setup)
    if SCOPE == "full":
        case("helper_authentication", security, ["helper_setup"])
        case("settings_export_import", export_import, ["helper_setup"])
        case("helper_restart_memory", restart, ["helper_setup"])
    case("firefox_setup", firefox_prepare)
    case("real_extension_install", extension, ["firefox_setup"])
    case("real_browser_pairing", pair, ["helper_setup", "real_extension_install"])
    # Stabilization: confirm the corrected seek first; later core cases remain
    # independent if automation for one case fails.
    if SCOPE == "core":
        case("seek_and_restore", seek_restore, ["real_browser_pairing"])
    case("player_handoff", player, ["real_browser_pairing"])
    case("player_play_pause", play_pause, ["player_handoff"])
    if SCOPE in ("full", "controls", "core"):
        case("device_volume_brightness_swipes", gestures, ["player_handoff"])
        if SCOPE != "core":
            case("seek_and_restore", seek_restore, ["player_handoff"])
    if SCOPE == "core":
        case("site_source_switch", switch_source, ["real_browser_pairing"])
        case("parallel_downloads", downloads, ["real_browser_pairing"])
        def fresh_background():
            player_entry().click()
            wait(reveal)
            return background()
        case("background_pip_return", fresh_background, ["real_browser_pairing"])
    if SCOPE == "full":
        for kind in ("redirect", "hls", "embedded"):
            case("play_" + kind, lambda k=kind: source(k), ["player_handoff"])
        case("subtitle_selection", subtitles, ["player_handoff"])
        case("site_source_switch", switch_source, ["player_handoff"])
        case("orientation_roundtrip", rotation, ["player_handoff"])
        case("player_open_close_memory", opening_cycles, ["player_handoff"])
        case("download_cancel", cancel_download, ["player_handoff"])
        case("parallel_downloads", downloads, ["player_handoff"])
        case("large_download_memory", large_download, ["player_handoff"])
        case("background_pip_return", background, ["large_download_memory"])
        case("paired_helper_restart", paired_restart, ["player_handoff"])
        case("pairing_reset_roundtrip", reset_pairing, ["player_handoff"])
        case("native_hls_download", native_hls_download, ["helper_setup"])
        case("native_cookie_redirect", native_cookie_redirect, ["helper_setup"])
        case("native_downloadmanager_cookie_redirect", lambda: native_cookie_redirect("file"), ["helper_setup"])
        case("real_port_conflict", port_conflict, ["helper_setup"])
    case("no_java_crash", crash_check)
    for name, reason in {
        "alternate_browser_route": "This report covers " + ROUTE + " only; consult the separate route report.",
        "vivaldi_android": "Vivaldi 8.2 documents Load unpacked on Android; this browser route has not yet been automated/executed here.",
        "quality_source_switch": "Alternate fixture present; own-player quality UI assertion still required.",
        "physical_battery_display": "Emulator cannot establish Samsung energy usage or physical display quality.",
        "production_signer_migration": "Separate disposable migration workflow; no production identity available or generated.",
        "chrome_dnr_worker_recovery": "Covered by existing real desktop Chromium tests; Firefox Android cannot establish Chrome DNR coverage.",
    }.items():
        RESULTS.append({"name": name, "status": "NOT_TESTED", "detail": reason})
    if SCOPE != "full":
        RESULTS.append({"name":"full_functional_suite","status":"NOT_TESTED","detail":"Explicit "+SCOPE+" scope: remaining feature matrix deliberately omitted; no full-validation claim."})
    write_results()
    if WEB_EXT and WEB_EXT.poll() is None:
        WEB_EXT.send_signal(signal.SIGINT)
    return 1 if any(r["status"] in ("FAIL", "BLOCKED") for r in RESULTS) else 0

if __name__ == "__main__":
    try:
        sys.exit(main())
    finally:
        if WEB_EXT and WEB_EXT.poll() is None:
            WEB_EXT.terminate()
