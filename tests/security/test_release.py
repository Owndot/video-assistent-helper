"""Host-only regression checks; no Android device or publication is involved."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("check_update", ROOT / "tools/check-update.py")
update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update)

class UpdateCompatibility(unittest.TestCase):
    def test_valid_upgrade(self):
        update.compatible("a"*64, "a"*64, ("de.evp.helfer", 10), ("de.evp.helfer", 11))
    def test_reject_key_change(self):
        with self.assertRaises(ValueError):
            update.compatible("a"*64, "b"*64, ("de.evp.helfer", 10), ("de.evp.helfer", 11))
    def test_reject_package_change(self):
        with self.assertRaises(ValueError):
            update.compatible("a"*64, "a"*64, ("de.evp.helfer", 10), ("de.evp.helfer.debug", 11))
    def test_reject_downgrade(self):
        for code in (9, 10):
            with self.assertRaises(ValueError):
                update.compatible("a"*64, "a"*64, ("de.evp.helfer", 10), ("de.evp.helfer", code))
    def test_reject_multiple_signers(self):
        with self.assertRaises(ValueError):
            update.certificate("Signer #1 certificate SHA-256 digest: aa\nSigner #2 certificate SHA-256 digest: bb")
    def test_parse_sdk_output(self):
        self.assertEqual(update.package("package: name='de.evp.helfer' versionCode='11' versionName='1.0.11'"),
                         ("de.evp.helfer", 11))

class PublicationPolicy(unittest.TestCase):
    def test_validation_has_no_production_access_or_publication(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text()
        self.assertIn("contents: read", workflow)
        for forbidden in ("contents: write", "secrets.", "pull_request_target", "gh release", "git push", "publish:"):
            self.assertNotIn(forbidden, workflow)
        self.assertIn("test-only.jks", workflow)
        self.assertIn("assembleDebug", workflow)
        self.assertNotIn("path: app/build/outputs/apk/release", workflow)

if __name__ == "__main__":
    unittest.main()
