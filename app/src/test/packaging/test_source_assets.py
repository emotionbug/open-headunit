import tempfile
import unittest
import zipfile
from pathlib import Path

from check_source_assets import REQUIRED_ASSETS, verify


class SourceAssetPackagingTest(unittest.TestCase):
    def package(self, assets):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        apk = Path(directory.name) / "test.apk"
        with zipfile.ZipFile(apk, "w") as package:
            for name, content in assets.items():
                package.writestr(name, content)
        return apk

    def complete_assets(self):
        return {name: path.read_bytes() for name, path in REQUIRED_ASSETS.items()}

    def test_complete_verbatim_assets_are_accepted(self):
        verify(self.package(self.complete_assets()))

    def test_each_asset_is_required(self):
        for missing in REQUIRED_ASSETS:
            with self.subTest(asset=missing):
                assets = self.complete_assets()
                del assets[missing]
                with self.assertRaises(KeyError):
                    verify(self.package(assets))

    def test_each_asset_must_match_its_source(self):
        for stale in REQUIRED_ASSETS:
            with self.subTest(asset=stale):
                assets = self.complete_assets()
                assets[stale] = assets[stale][:-1]
                with self.assertRaises(ValueError):
                    verify(self.package(assets))


if __name__ == "__main__":
    unittest.main()
