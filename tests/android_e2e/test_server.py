import http.client
import importlib.util
from pathlib import Path
import tempfile
import threading
import time
import unittest

spec = importlib.util.spec_from_file_location("fixtures", Path(__file__).with_name("server.py"))
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)

class FixtureContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        fixtures.MEDIA = Path(cls.tmp.name)
        (fixtures.MEDIA / "clip.mp4").write_bytes(b"0123456789")
        cls.server = fixtures.serve(0)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()
        cls.tmp.cleanup()

    def get(self, path="/media/clip.mp4", range_=None, method="GET"):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        conn.request(method, path, headers={"Range": range_} if range_ else {})
        r = conn.getresponse()
        data = r.read()
        result = r.status, dict(r.getheaders()), data
        conn.close()
        return result

    def test_complete(self):
        self.assertEqual(self.get()[2], b"0123456789")

    def test_closed_range(self):
        s, h, b = self.get(range_="bytes=2-4")
        self.assertEqual((s, h["Content-Range"], b), (206, "bytes 2-4/10", b"234"))

    def test_open_range(self):
        self.assertEqual(self.get(range_="bytes=8-")[2], b"89")

    def test_suffix_range(self):
        self.assertEqual(self.get(range_="bytes=-3")[2], b"789")

    def test_unsatisfiable(self):
        self.assertEqual(self.get(range_="bytes=10-")[0], 416)
        self.assertEqual(self.get(range_="bytes=3-1")[0], 416)

    def test_multi_range_rejected(self):
        self.assertEqual(self.get(range_="bytes=1-2,4-5")[0], 416)

    def test_large_fixture_requires_page_cookie(self):
        self.assertEqual(self.get("/media/large.mp4")[0], 403)
        status, headers, _ = self.get("/page/large")
        self.assertEqual(status, 200)
        self.assertIn("e2e_bounded_stream=disposable", headers["Set-Cookie"])

    def test_head(self):
        s, h, b = self.get(method="HEAD")
        self.assertEqual((s, h["Content-Length"], b), (200, "10", b""))

    def test_redirect(self):
        s, h, b = self.get("/redirect?cross=1&to=/sink")
        self.assertEqual(s, 302)
        self.assertTrue(h["Location"].startswith("http://localhost:"))

    def test_delayed_headers(self):
        start = time.monotonic()
        self.get("/media/clip.mp4?header_ms=200")
        self.assertGreaterEqual(time.monotonic() - start, .18)

    def test_no_external_redirect_or_traversal(self):
        self.assertEqual(self.get("/redirect?to=https://example.invalid/")[0], 400)
        self.assertEqual(self.get("/media/../server.py")[0], 404)

if __name__ == "__main__":
    unittest.main()
