"""Pure policy/metadata rejection tests; real SDK tests are separate and disposable."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
import release_verify as v

class LocalRelease(unittest.TestCase):
    def test_policy_starts_unapproved(self):
        with self.assertRaisesRegex(ValueError, 'UNAPPROVED'):
            v.policy(ROOT/'release/production-policy.json')
    def test_policy_no_automatic_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'policy.json'
            for d in ({'status':'OWNER_CONFIRMED','certificate_sha256':v.BLOCKED,'owner_confirmation':'synthetic'},
                      {'status':'OWNER_CONFIRMED','certificate_sha256':None,'owner_confirmation':'synthetic'},
                      {'status':'OWNER_CONFIRMED','certificate_sha256':'a'*64,'owner_confirmation':'synthetic'},
                      {'status':'OWNER_CONFIRMED','certificate_sha256':'ab'*32,'owner_confirmation':''}):
                p.write_text(json.dumps(d))
                with self.assertRaises(ValueError): v.policy(p)
    def test_blocked_old_signer(self):
        with self.assertRaises(ValueError): v.fingerprint(v.BLOCKED)
        with self.assertRaises(ValueError): v.compatible(v.BLOCKED,v.BLOCKED,('de.evp.helfer',1),('de.evp.helfer',2))
    def test_metadata_rejections(self):
        base="package: name='de.evp.helfer' versionCode='200' versionName='2.0.0'\nsdkVersion:'26'\ntargetSdkVersion:'34'"
        xml='A: android:debuggable(0x0101000f)=(type 0x12)0x0'
        for bad in (base.replace('200','201'),base.replace('2.0.0','2.0.1'),base.replace('helfer','helfer.debug'),base.replace("sdkVersion:'26'","sdkVersion:'27'"),base+'\napplication-debuggable'):
            with patch.object(v,'run',side_effect=[bad,xml]), self.assertRaises(ValueError): v.metadata('synthetic.apk',200,'2.0.0')
        with patch.object(v,'run',side_effect=[base,xml.replace('0x0','0xffffffff')]),self.assertRaises(ValueError): v.metadata('synthetic.apk',200,'2.0.0')
        with patch.object(v,'run',side_effect=[base,xml]): self.assertEqual(v.metadata('synthetic.apk',200,'2.0.0')[0],('de.evp.helfer',200))
    def test_no_v3_or_lineage(self):
        for ids in ({v.V2,0xf05368c0},{v.V2,0x1b93ad61},set()):
            with patch.object(v,'signing_ids',return_value=ids),self.assertRaisesRegex(ValueError,'lineage'): v.inspect('synthetic.apk','ab'*32)
    def test_signer_and_version_rejections(self):
        for cert in ('cd'*32,v.BLOCKED):
            with self.assertRaises(ValueError): v.compatible('ab'*32,cert,('de.evp.helfer',200),('de.evp.helfer',201))
        for code in (199,200):
            with self.assertRaises(ValueError): v.compatible('ab'*32,'ab'*32,('de.evp.helfer',200),('de.evp.helfer',code))
        v.compatible('ab'*32,'ab'*32,('de.evp.helfer',200),('de.evp.helfer',201))

if __name__=='__main__': unittest.main()
