# Testbrowser umschalten: BROWSER=firefox python3 test_xyz.py
# Die Tests sind für Chromium geschrieben (p.chromium.launch, is_mobile …). Diese Weiche ersetzt Chromium durch
# Firefox und passt die Optionen an (Firefox kennt is_mobile nicht, Autoplay über Einstellungen statt Startoptionen).
import os, sys
import playwright.sync_api as _pw

if os.environ.get('BROWSER', '').lower() == 'firefox' and not getattr(_pw, '_evp_ff', False):
    _orig = _pw.sync_playwright
    FF_PREFS = {
        'media.autoplay.default': 0,               # Videos dürfen mit Ton starten
        'media.autoplay.blocking_policy': 0,
        'media.autoplay.block-webaudio': False,
        'full-screen-api.allow-trusted-requests-only': False,
        'full-screen-api.warning.timeout': 0,
        'full-screen-api.ignore-widgets': True,    # Vollbild im Fenster (sonst springt es ohne Bildschirm auf 1280 px)
        'full-screen-api.transition-duration.enter': '0 0',
        'full-screen-api.transition-duration.leave': '0 0',
        'dom.user_activation.transient.timeout': 5.0,
    }

    HOSTS = 'top.test,mid.test,cdn.test'
    # Firefox hängt bei einer Größenänderung im Vollbild. Tests, die drehen (set_viewport_size), bekommen deshalb ein
    # nachgebautes Vollbild (gleiche Schnittstelle und Ereignisse, ohne echtes Umschalten).
    try: FAKE_FS = 'set_viewport_size' in open(sys.argv[0], encoding='utf-8').read()
    except Exception: FAKE_FS = False
    FAKE_FS_JS = r'''(() => {
      let fe = null;
      const fire = () => { try { document.dispatchEvent(new Event('fullscreenchange', { bubbles: true })); } catch (e) {} };
      Object.defineProperty(Document.prototype, 'fullscreenElement', { configurable: true, get() { return fe; } });
      Object.defineProperty(Document.prototype, 'fullscreenEnabled', { configurable: true, get() { return true; } });
      Element.prototype.requestFullscreen = function () { fe = this; setTimeout(fire, 0); return Promise.resolve(); };
      Document.prototype.exitFullscreen = function () {
        if (!fe) return Promise.reject(new TypeError('Not in fullscreen mode'));
        fe = null; setTimeout(fire, 0); return Promise.resolve(); };
    })();'''

    # Firefox wie auf dem Handy (Ambient/Vorschau ohne Kopieren von Videobildern) – Playwright meldet keinen Touchscreen
    MOBILE_JS = "try { localStorage.setItem('evp-ffmobile', '1'); } catch (e) {}"

    class _Ctx:
        def __init__(s, c): s._c = c
        def __getattr__(s, n): return getattr(s._c, n)

    class _Browser:
        def __init__(s, b): s._b = b
        def _fix(s, kw):
            kw.pop('is_mobile', None)
            return kw
        def new_context(s, **kw):
            c = s._b.new_context(**s._fix(kw))
            c.add_init_script(MOBILE_JS)
            if FAKE_FS: c.add_init_script(FAKE_FS_JS)
            return c
        def new_page(s, **kw):
            pg = s._b.new_page(**s._fix(kw))
            pg.add_init_script(MOBILE_JS)
            if FAKE_FS: pg.add_init_script(FAKE_FS_JS)
            return pg
        def __getattr__(s, n): return getattr(s._b, n)

    class _Type:
        def __init__(s, t): s._t = t
        def launch(s, **kw):
            args = kw.pop('args', None) or []
            prefs = dict(FF_PREFS)
            # --host-resolver-rules=MAP *.test 127.0.0.1 → Firefox: diese Namen auf das eigene Gerät
            if any('host-resolver-rules' in a for a in args):
                prefs['network.dns.localDomains'] = HOSTS
            prefs.update(kw.pop('firefox_user_prefs', {}) or {})
            return _Browser(s._t.launch(firefox_user_prefs=prefs, **kw))
        def __getattr__(s, n): return getattr(s._t, n)

    class _PW:
        def __init__(s, p): s._p = p
        @property
        def chromium(s): return _Type(s._p.firefox)
        def __getattr__(s, n): return getattr(s._p, n)

    class _CM:
        def __init__(s): s._cm = _orig()
        def start(s): return _PW(s._cm.start())
        def __enter__(s): return _PW(s._cm.__enter__())
        def __exit__(s, *a): return s._cm.__exit__(*a)

    _pw.sync_playwright = lambda: _CM()
    _pw._evp_ff = True
