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
    # Static assertions guard the actual workflow. They are not an Actions execution.
    def test_branch_build_cannot_publish(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text()
        build, release = workflow.split("\n  release:", 1)
        self.assertIn("permissions:\n  contents: read", build)
        self.assertNotIn("contents: write", build)
        self.assertNotIn("gh release", build)
        self.assertIn("default: false", build)
        self.assertIn("github.event_name == 'workflow_dispatch' && github.ref == 'refs/heads/main' && inputs.publish", release)
        self.assertIn("needs: build", release)
        self.assertIn("contents: write", release)
        self.assertNotIn("release delete", workflow)
        self.assertNotIn("--clobber", workflow)
        self.assertIn('--target "$RELEASE_COMMIT"', release)

if __name__ == "__main__":
    unittest.main()
