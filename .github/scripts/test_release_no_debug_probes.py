import importlib.util
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("probe_check", Path(__file__).with_name("check-release-no-debug-probes.py"))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)
AXML = b"\x03\x00\x08\x00\x08\x00\x00\x00"
DEX = b"dex\n035\x00"


class ReleaseProbeCheckTest(unittest.TestCase):
    def setUp(self):
        root = self.enterContext(tempfile.TemporaryDirectory())
        self.manifest = Path(root, "AndroidManifest.xml")
        self.apk = Path(root, "release.apk")
        self.manifest.write_text("<manifest><application /></manifest>", encoding="utf-8")

    def package(self, manifest=AXML, dex=DEX, extra=()):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", manifest)
            if dex is not None:
                archive.writestr("classes.dex", dex)
            for name, data in extra:
                archive.writestr(name, data)

    def test_accepts_clean_manifest_and_multidex(self):
        self.package(extra=[("classes10.dex", DEX)])
        check.verify(self.manifest, self.apk)

    def test_rejects_probe_in_merged_manifest(self):
        self.package()
        self.manifest.write_text('<manifest><application><activity name=".FollowLifecycleProbeActivity" /></application></manifest>', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "merged release manifest"):
            check.verify(self.manifest, self.apk)

    def test_rejects_utf16_manifest_and_chunk_boundary_in_later_dex(self):
        self.package(manifest=AXML + "FollowLifecycleProbeActivity".encode("utf-16le"))
        with self.assertRaisesRegex(ValueError, "APK manifest contains"):
            check.verify(self.manifest, self.apk)
        self.package(extra=[("classes10.dex", DEX + b"x" * (64 * 1024 - 12) + b"AutomationProbeReceiver")])
        with self.assertRaisesRegex(ValueError, "classes10.dex contains"):
            check.verify(self.manifest, self.apk)

    def test_rejects_missing_invalid_duplicate_and_corrupt_members(self):
        self.package(dex=None)
        with self.assertRaisesRegex(ValueError, "missing its manifest or primary DEX"):
            check.verify(self.manifest, self.apk)
        self.package(dex=b"not a dex file")
        with self.assertRaisesRegex(ValueError, "invalid header in classes.dex"):
            check.verify(self.manifest, self.apk)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            self.package(extra=[("classes.dex", DEX)])
        with self.assertRaisesRegex(ValueError, "duplicate member"):
            check.verify(self.manifest, self.apk)
        self.package(dex=DEX + b"unique CRC test bytes")
        self.apk.write_bytes(self.apk.read_bytes().replace(b"unique CRC test bytes", b"unlque CRC test bytes", 1))
        with self.assertRaises(zipfile.BadZipFile):
            check.verify(self.manifest, self.apk)


if __name__ == "__main__":
    unittest.main()
