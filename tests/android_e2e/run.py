#!/usr/bin/env python3
"""Real Android UI/HTTP checks. Never patches product JS or emulates a GM API."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

import uiautomator2 as u2
from PIL import ImageChops, ImageStat

PKG = "de.evp.helfer.debug"
FIREFOX = "org.mozilla.firefox"
OUT = Path(os.environ.get("E2E_OUT", "android-e2e-results"))
OUT.mkdir(parents=True, exist_ok=True)
RESULTS = []
KEY = ""
AUTH_KEY = "evp-1"
D = None
WEB_EXT = None
LOGCAT = None

class Blocked(Exception):
    pass

def command(*args, check=True, timeout=45):
    return subprocess.run(list(args), text=True, capture_output=True, check=check, timeout=timeout).stdout

def adb(*args, **kwargs):
    return command("adb", *args, **kwargs)

def shell(*args, **kwargs):
    return adb("shell", *args, **kwargs)

def wait(fn, timeout=20, interval=0.25):
    until = time.monotonic() + timeout
    last = None
    while time.monotonic() < until:
        try:
            value = fn()
            if value:
                return value
        except Exception as exc:
            last = exc
        time.sleep(interval)
    raise AssertionError("Condition not reached" + (": " + type(last).__name__ if last else ""))

def redact(s):
    if KEY:
        s = s.replace(KEY, "<disposable-pair-key>")
    return re.sub(r"(/pair)#?[0-9a-f]{32}", r"\1#<disposable-pair-key>", s)

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
        record = subprocess.Popen(["adb", "shell", "screenrecord", "--time-limit", "120", video],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        detail = fn()
        RESULTS.append({"name": name, "status": "PASS", "seconds": round(time.monotonic() - started, 3), "detail": detail})
    except Blocked as exc:
        RESULTS.append({"name": name, "status": "NOT_TESTED", "detail": str(exc)})
    except Exception as exc:
        RESULTS.append({"name": name, "status": "FAIL", "detail": redact(str(exc)), "trace": redact(traceback.format_exc())})
    finally:
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
        write_results()
        print(name, RESULTS[-1]["status"], redact(str(RESULTS[-1].get("detail", ""))), flush=True)

def write_results():
    (OUT / "results.json").write_text(json.dumps(RESULTS, indent=2), encoding="utf8")
    rows = ["# Android E2E — real emulator / installed APK", "",
            "| Case | Status | Detail |", "|---|---|---|"]
    for r in RESULTS:
        rows.append("| " + r["name"] + " | " + r["status"] + " | " +
                    str(r.get("detail", "")).replace("|", "/").replace("\n", " ")[:700] + " |")
    (OUT / "REPORT.md").write_text("\n".join(rows) + "\n", encoding="utf8")

def click(label, scroll=False):
    # Accessible user-facing labels, no product debug hooks.
    selectors = (D(text=label), D(description=label))
    for sel in selectors:
        if sel.exists(timeout=1):
            sel.click()
            return
    if scroll:
        D(scrollable=True).scroll.to(text=label)
        if D(text=label).exists:
            D(text=label).click()
            return
    raise AssertionError("Visible control absent: " + label)

def open_app():
    D.app_start(PKG, stop=False)
    wait(lambda: D(textContains="Video Assistant Helper").exists or D(textContains="Video-Assistent Helper").exists)
    time.sleep(0.6)

def setup():
    global KEY
    open_app()
    shell("pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    # Permission provisioning is explicit infrastructure. Later gesture tests
    # still exercise the real overlay, audio service and browser/extension.
    shell("appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")
    shell("dumpsys", "deviceidle", "whitelist", "+" + PKG)
    open_app()
    click("English", scroll=True)
    wait(lambda: prefs().get("lang") == "en")
    # A first bootstrap ping creates the random key without marking the app paired.
    status = wait(lambda: request("/ping"))
    KEY = wait(lambda: prefs().get("key"))
    assert len(KEY) == 32
    assert status["ok"] and status["overlay"]
    D(scrollable=True).scroll.toBeginning()
    assert D(textContains="Running").exists
    return "Installed debug APK, real foreground service, overlay permission. Permission grant was ADB provisioning."

def security():
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
    click("Export settings", scroll=True)
    wait(lambda: D(resourceId="com.android.documentsui:id/container_save").exists or D(textMatches="(?i)save").exists)
    # Use the real Storage Access Framework, not a direct preferences write.
    if D(description="Show roots").exists:
        D(description="Show roots").click()
        if D(text="Downloads").exists(timeout=2):
            D(text="Downloads").click()
    field = D(resourceId="android:id/text1", className="android.widget.EditText")
    if not field.exists:
        field = D(className="android.widget.EditText")
    if field.exists:
        field.set_text("e2e-settings.txt")
    click("SAVE") if D(text="SAVE").exists else click("Save")
    wait(lambda: shell("test", "-f", "/sdcard/Download/e2e-settings.txt", check=False).strip() == ""
         and "theme" in shell("cat", "/sdcard/Download/e2e-settings.txt", check=False))
    raw = shell("cat", "/sdcard/Download/e2e-settings.txt")
    assert KEY not in raw and "light" in raw
    open_app()
    click("Dark", scroll=True)
    assert prefs().get("theme") == "dark"
    click("Import settings", scroll=True)
    if D(description="Show roots").exists(timeout=2):
        D(description="Show roots").click()
        if D(text="Downloads").exists(timeout=2):
            D(text="Downloads").click()
    click("e2e-settings.txt")
    wait(lambda: prefs().get("theme") == "light")
    assert prefs().get("key") == KEY
    return "SAF export/import round trip restored theme; pairing key excluded from export and retained in app."

def restart():
    samples = []
    for i in range(8):
        D.app_stop(PKG)
        open_app()
        wait(lambda: request("/ping")["ok"])
        assert prefs().get("key") == KEY
        samples.append({"cycle": i + 1, "meminfo": shell("dumpsys", "meminfo", PKG)})
    (OUT / "helper-restart-memory.json").write_text(json.dumps(samples), encoding="utf8")
    return "8 real force-stop/restart cycles, same key, bound port recovered. PSS observations; no leak threshold invented."

def firefox_prepare():
    D.app_start(FIREFOX)
    # Fenix onboarding is version-dependent. Only dismiss known onboarding;
    # an unknown screen is a captured infrastructure failure, never a pass.
    for _ in range(8):
        found = False
        for text in ("Not now", "Skip", "Start browsing", "Continue", "Get started"):
            if D(text=text).exists(timeout=0.4):
                D(text=text).click()
                found = True
                time.sleep(0.5)
                break
        if not found:
            break
    launch_url("http://127.0.0.1:8765/health")
    # Firefox release supports the real remote debugging toggle.
    menu = D(resourceIdMatches=".*:(id/)(menuButton|mozac_browser_menu_button|menu_button)")
    if menu.exists(timeout=3):
        menu.click()
    else:
        for desc in ("More options", "Menu", "Open menu"):
            if D(description=desc).exists:
                D(description=desc).click()
                break
        else:
            raise AssertionError("Firefox browser menu not found")
    click("Settings", scroll=True)
    D(scrollable=True).scroll.to(textContains="Remote debugging")
    row = D(textContains="Remote debugging")
    assert row.exists
    row.click()
    D.press("back")
    return "Real Firefox installed; Remote debugging via USB enabled through UI."

def launch_url(url):
    shell("am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", url, "-p", FIREFOX)
    time.sleep(1)

def extension():
    global WEB_EXT
    log = (OUT / "web-ext.log").open("w", encoding="utf8")
    cmd = ["web-ext", "run", "--target=firefox-android", "--android-device=" + os.environ["ANDROID_SERIAL"],
           "--firefox-apk=" + FIREFOX, "--source-dir=" + os.environ["KADRI_DIST"],
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
    # UI appearance on a real fixture is the acceptance criterion, not a guessed log line.
    launch_url("http://127.0.0.1:8765/page/direct")
    click("Play fixture")
    wait(lambda: D(descriptionContains="Videoplayer").exists or D(descriptionContains="video player").exists, timeout=25)
    return "Unmodified dist/firefox loaded temporarily into Firefox Android; real player entry visible."

def pair():
    global AUTH_KEY
    assert prefs().get("paired") != "true", "Setup must not pre-pair through an authenticated probe"
    open_app()
    click("Connect", scroll=True)
    # Android resolver may show Firefox once or per visit.
    if D(text="Firefox").exists(timeout=3):
        D(text="Firefox").click()
    if D(text="Just once").exists(timeout=2):
        D(text="Just once").click()
    wait(lambda: D(textContains="Connected.").exists or D(textContains="Verbunden.").exists, timeout=20)
    assert prefs().get("paired") == "true"
    AUTH_KEY = KEY
    # Fragment is removed by the real content script before success is displayed.
    open_app()
    D(scrollable=True).scroll.toBeginning()
    assert D(textContains="Connected").exists
    return "App Connect → Android chooser → Firefox → actual Kadri storage/ping; helper paired preference confirmed."

def player_entry(kind="direct"):
    launch_url("http://127.0.0.1:8765/page/" + kind)
    click("Play fixture")
    return wait(lambda: D(descriptionContains="Videoplayer") if D(descriptionContains="Videoplayer").exists
                else D(descriptionContains="video player") if D(descriptionContains="video player").exists else None)

def reveal():
    if not D(description="Wiedergabe").exists and not D(description="Play").exists:
        w, h = D.window_size()
        D.click(w * 0.5, h * 0.35)
        time.sleep(0.4)

def playing_times():
    root = ET.fromstring(D.dump_hierarchy())
    vals = []
    for node in root.iter("node"):
        for attr in ("text", "content-desc"):
            t = node.attrib.get(attr, "").strip()
            if re.fullmatch(r"\d{1,2}:\d{2}", t):
                bounds = list(map(int, re.findall(r"\d+", node.attrib.get("bounds", ""))))
                if len(bounds) == 4 and bounds[1] > D.window_size()[1] * .15:
                    vals.append(t)
    return sorted(set(vals))

def player():
    button = player_entry()
    time.sleep(2)
    started = time.monotonic()
    button.click()
    wait(lambda: D(description="Schließen").exists or D(description="Close").exists, timeout=25)
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
    assert first and second and first != second, "Player clock did not advance: " + repr([first, second])
    click("Wiedergabe") if D(description="Wiedergabe").exists else click("Play")
    time.sleep(0.5)
    paused = playing_times()
    time.sleep(1.2)
    assert playing_times() == paused, "Paused clock advanced"
    click("Wiedergabe") if D(description="Wiedergabe").exists else click("Play")
    return {"clock_before": first, "clock_after": second, "changed_video_pixels": changed,
            "entry_to_controls_ms": controls_ms,
            "note": "UI latency/clock evidence. Not a first-decoded-frame measurement."}

def gestures():
    reveal()
    before = request("/ping")
    w, h = D.window_size()
    D.swipe(w * .85, h * .58, w * .85, h * .46, duration=.5)
    after_vol = wait(lambda: (s if (s := request("/ping"))["vol"] != before["vol"] else None))
    D.swipe(w * .15, h * .40, w * .15, h * .50, duration=.5)
    after_bright = wait(lambda: (s if (s := request("/ping"))["bright"] != before["bright"] else None))
    assert after_bright["bright"] < before["bright"]
    return {"before": before, "volume_after": after_vol["vol"], "brightness_after": after_bright["bright"],
            "note": "Real UI swipes through Kadri and helper to Android services"}

def seek_restore():
    reveal()
    before = playing_times()
    sel = D(descriptionMatches=r".*(s vor|s forward).*")
    assert sel.exists, "Native accessible forward control missing"
    sel.click()
    time.sleep(.6)
    after = playing_times()
    assert before != after, "Seek did not change clock"
    click("Schließen") if D(description="Schließen").exists else click("Close")
    with urllib.request.urlopen("http://127.0.0.1:8765/events") as response:
        events = json.load(response)
    times = [e["time"] for e in events if e.get("event") == "seeked" and "time" in e]
    assert times and max(times) > 5, "Original video did not receive restored position"
    return {"clock_before": before, "clock_after": after, "fixture_seek_events": times[-5:]}

def source(kind):
    launch_url("http://127.0.0.1:8765/page/" + kind)
    click("Play fixture")
    button = wait(lambda: D(descriptionContains="Videoplayer") if D(descriptionContains="Videoplayer").exists
                  else D(descriptionContains="video player") if D(descriptionContains="video player").exists else None, timeout=25)
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

def downloads():
    # Trigger downloads using actual user clicks; no direct /download API invocation.
    player_entry("download").click()
    wait(lambda: D(description="Schließen").exists or D(description="Close").exists)
    reveal()
    before = set(shell("ls", "/sdcard/Download/Video-Assistent", check=False).splitlines())
    selector = D(descriptionContains="Herunterladen")
    if not selector.exists:
        selector = D(descriptionContains="Download")
    assert selector.exists
    selector.click()
    time.sleep(.5)
    reveal()
    selector.click()
    expected_size = Path("tests/android_e2e/generated/clip.mp4").stat().st_size
    def finished():
        now = set(shell("ls", "/sdcard/Download/Video-Assistent", check=False).splitlines())
        files = [x for x in now - before if x.endswith(".mp4") and
                 shell("stat", "-c", "%s", "/sdcard/Download/Video-Assistent/" + x, check=False).strip() == str(expected_size)]
        return files if len(files) >= 2 else None
    files = wait(finished, timeout=60)
    hashes = {}
    for i, filename in enumerate(files):
        local = OUT / ("download-" + str(i) + ".mp4")
        adb("pull", "/sdcard/Download/Video-Assistent/" + filename, str(local))
        hashes[filename] = hashlib.sha256(local.read_bytes()).hexdigest()
        assert local.stat().st_size > 10000
        local.unlink()  # artifacts retain hashes and screen evidence, not duplicate owned media
    expected = hashlib.sha256(Path("tests/android_e2e/generated/clip.mp4").read_bytes()).hexdigest()
    assert all(value == expected for value in hashes.values()), "Saved video differs from fixture"
    return {"parallel_ui_downloads": len(files), "sha256": hashes,
            "note": "Two user-triggered downloads; file content verified, not just HTTP success"}

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
    reveal()
    assert D(description="Schließen").exists or D(description="Close").exists
    if not pip:
        raise Blocked("Browser returned to player; Android did not expose a pinned PiP task in this route. See recording/dumpsys.")
    return "Android pinned PiP task present after Home; player restored on returning."

def subtitles():
    player_entry().click()
    wait(lambda: D(description="Schließen").exists or D(description="Close").exists)
    reveal()
    click("Untertitel") if D(description="Untertitel").exists else click("Subtitles")
    click("E2E English")
    wait(lambda: D(text="ANDROID E2E SUBTITLE").exists)
    shot("rendered-owned-subtitle")
    click("Schließen") if D(description="Schließen").exists else click("Close")
    return "VTT selected through actual player menu; rendered owned subtitle accessible on screen."

def switch_source():
    button = player_entry()
    button.click()
    wait(lambda: D(description="Schließen").exists or D(description="Close").exists)
    reveal()
    click("Schließen") if D(description="Schließen").exists else click("Close")
    click("Switch fixture source")
    button = wait(lambda: D(descriptionContains="Videoplayer") if D(descriptionContains="Videoplayer").exists
                  else D(descriptionContains="video player") if D(descriptionContains="video player").exists else None)
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
    wait(lambda: D(description="Schließen").exists or D(description="Close").exists)
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
    wait(lambda: D(description="Schließen").exists or D(description="Close").exists)
    reveal()
    sel = D(descriptionContains="Herunterladen")
    if not sel.exists:
        sel = D(descriptionContains="Download")
    assert sel.exists
    sel.click()
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
    for i in range(6):
        button = player_entry()
        start = time.monotonic()
        button.click()
        wait(lambda: D(description="Schließen").exists or D(description="Close").exists)
        reveal()
        samples.append({"cycle": i + 1, "entry_to_controls_ms": round((time.monotonic() - start) * 1000, 1),
            "helper_pss": shell("dumpsys", "meminfo", PKG),
            "firefox_pss": shell("dumpsys", "meminfo", FIREFOX),
            "cpu": shell("dumpsys", "cpuinfo")})
        click("Schließen") if D(description="Schließen").exists else click("Close")
    (OUT / "player-cycle-observations.json").write_text(json.dumps(samples), encoding="utf8")
    return "6 real open/close cycles with timing/PSS/CPU observations. No baseline or leak verdict inferred."

def native_cookie_redirect():
    # This is deliberately labeled real Android HTTP/API integration, not UI interaction.
    start = time.monotonic()
    params = urllib.parse.urlencode({
        "u": "http://127.0.0.1:8765/redirect?cross=1&to=/sink",
        "ref": "http://127.0.0.1:8765/page/direct", "c": "e2e_sentinel=disposable",
        "t": "e2e-cookie-boundary", "k": "hls"})
    result = request("/download?" + params)
    assert result["ok"]
    def seen():
        with urllib.request.urlopen("http://127.0.0.1:8765/events") as response:
            data = json.load(response)
        rows = [e for e in data if e.get("path") == "/sink" and e.get("received", 0) >= start]
        return rows or None
    rows = wait(seen, timeout=20)
    assert all(not e["cookie"] and not e["authorization"] for e in rows), "Credentials crossed Android HTTP redirect"
    return "Actual installed APK stream fetch redirected to localhost; destination received no Cookie/Authorization. API integration, no browser simulation."

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
    return "No target-process Java FATAL EXCEPTION observed. Native/ANR evidence also saved for review."

def main():
    global D
    D = u2.connect(os.environ["ANDROID_SERIAL"])
    adb("forward", "tcp:14781", "tcp:47811")
    adb("reverse", "tcp:8765", "tcp:8765")
    adb("logcat", "-c")
    provenance = {
        "helper_commit": os.environ.get("HELPER_COMMIT"), "kadri_commit": os.environ.get("KADRI_COMMIT"),
        "api": shell("getprop", "ro.build.version.sdk").strip(),
        "android": shell("getprop", "ro.build.version.release").strip(),
        "firefox": shell("dumpsys", "package", FIREFOX),
        "fixture_transport": "adb reverse, device loopback; not a Wi-Fi/LAN throughput claim",
        "permissions": "ADB grants overlay/notification/idle exemption, real UI verifies app",
        "extension": "unmodified dist/firefox; temporarily installed via Mozilla web-ext",
    }
    (OUT / "provenance.json").write_text(json.dumps(provenance, indent=2), encoding="utf8")
    case("helper_setup", setup)
    case("helper_authentication", security, ["helper_setup"])
    case("settings_export_import", export_import, ["helper_setup"])
    case("helper_restart_memory", restart, ["helper_setup"])
    case("firefox_setup", firefox_prepare)
    case("real_extension_install", extension, ["firefox_setup"])
    case("real_browser_pairing", pair, ["helper_setup", "real_extension_install"])
    case("player_play_pause", player, ["real_browser_pairing"])
    case("device_volume_brightness_swipes", gestures, ["player_play_pause"])
    case("seek_and_restore", seek_restore, ["player_play_pause"])
    for kind in ("redirect", "hls", "embedded"):
        case("play_" + kind, lambda k=kind: source(k), ["real_browser_pairing"])
    case("subtitle_selection", subtitles, ["real_browser_pairing"])
    case("site_source_switch", switch_source, ["real_browser_pairing"])
    case("orientation_roundtrip", rotation, ["real_browser_pairing"])
    case("player_open_close_memory", opening_cycles, ["real_browser_pairing"])
    case("native_cookie_redirect", native_cookie_redirect, ["real_browser_pairing"])
    case("download_cancel", cancel_download, ["real_browser_pairing"])
    case("parallel_downloads", downloads, ["real_browser_pairing"])
    case("background_pip_return", background, ["parallel_downloads"])
    case("real_port_conflict", port_conflict, ["helper_setup"])
    case("no_java_crash", crash_check)
    for name, reason in {
        "userscript_manager": "Not installed by this route. Never infer Violentmonkey coverage from Kadri.",
        "vivaldi_android": "No verified unpacked-extension installation/automation route in this harness.",
        "quality_source_switch": "Alternate fixture present; own-player quality UI assertion still required.",
        "physical_battery_display": "Emulator cannot establish Samsung energy usage or physical display quality.",
        "production_signer_migration": "Separate disposable migration workflow; no production identity available or generated.",
        "chrome_dnr_worker_recovery": "Covered by existing real desktop Chromium tests; Firefox Android cannot establish Chrome DNR coverage.",
    }.items():
        RESULTS.append({"name": name, "status": "NOT_TESTED", "detail": reason})
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
