"""Host regression for seek observation, not an Android/browser integration test.

Only the observation function is AST-loaded: no APK, UI, or browser is simulated
or claimed here. Real installed-browser evidence is linked in VALIDATION-REPORT.
"""
import ast
import json
import pathlib
import tempfile
import unittest


class Clock:
    def __init__(self):
        self.now = 0
    def monotonic(self):
        return self.now
    def sleep(self, seconds):
        self.now += seconds


class PausedSeekObservation(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.out = pathlib.Path(self.directory.name)
        source = pathlib.Path(__file__).with_name("run.py").read_text()
        module = ast.parse(source)
        wanted = {"paused_seek_clock", "media_clock_seconds"}
        selected = [n for n in module.body if isinstance(n, ast.FunctionDef) and n.name in wanted]
        self.assertEqual({n.name for n in selected}, wanted)
        self.clock = Clock()
        self.context = {"OUT": self.out, "json": json}
        def wait(fn, timeout=20, interval=.25, dialogs=True):
            self.assertFalse(dialogs)
            end = self.clock.monotonic() + timeout
            while self.clock.monotonic() < end:
                result = fn()
                if result:
                    return result
                self.clock.sleep(interval)
            raise AssertionError("Condition not reached")
        self.context["wait"] = wait
        self.context["icon_control"] = lambda name: name == "P.play"
        exec(compile(ast.Module(body=selected, type_ignores=[]), "run.py", "exec"), self.context)

    def observe(self, clocks, paused=True):
        values = iter(clocks)
        last = clocks[-1]
        def read(evidence):
            nonlocal last
            last = next(values, last)
            return last
        self.context["playing_times"] = read
        self.context["icon_control"] = lambda name: paused and name == "P.play"
        return self.context["paused_seek_clock"](["0:01"])

    def observations(self):
        return json.loads((self.out / "seek-clock-observations.json").read_text())

    def test_delayed_ack_does_not_fail_on_first_stale_frame(self):
        # The previous single read rejects the first stale value immediately.
        clocks = [["0:01"], ["0:01"], ["0:11"]]
        self.assertEqual(self.observe(clocks), ["0:11"])
        self.assertEqual(len(self.observations()), 3)

    def test_missing_ack_times_out_and_preserves_evidence(self):
        with self.assertRaisesRegex(AssertionError, "Condition not reached"):
            self.observe([["0:01"]])
        self.assertGreaterEqual(self.clock.now, 8)
        self.assertTrue(self.observations())

    def test_playback_cannot_masquerade_as_seek(self):
        with self.assertRaisesRegex(AssertionError, "resumed"):
            self.observe([["0:11"]], paused=False)

    def test_wrong_displacement_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "displacement"):
            self.observe([["0:21"]])

    def test_rounding_tolerance(self):
        for label in ("0:10", "0:11", "0:12"):
            self.assertEqual(self.observe([[label]]), [label])

    def test_undersized_seek_never_passes(self):
        with self.assertRaisesRegex(AssertionError, "Condition not reached"):
            self.observe([["0:08"]])


if __name__ == "__main__":
    unittest.main()
