import json
import re
import unittest
from pathlib import Path

from build_identity import PRODUCTION_ID, TEST_ID, identity_manifest, test_identity_source
from plugin_manifest import build_manifest

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'kiosk-satellite-plugin.json'


class BuildIdentityTest(unittest.TestCase):
    def test_production_identity_matches_source_and_manifest(self):
        manifest = json.loads(identity_manifest(build_manifest(MANIFEST), False))
        source = (ROOT / 'src/kiosk/plugins/bing/BuildIdentity.java').read_text(encoding='utf-8')
        self.assertEqual(manifest['id'], PRODUCTION_ID)
        self.assertEqual(manifest['name'], 'Bing Wallpapers')
        self.assertRegex(source, r'PLUGIN_ID\s*=\s*"' + re.escape(manifest['id']) + r'"')
        self.assertRegex(source, r'TEST\s*=\s*false\s*;')

    def test_test_identity_preserves_other_manifest_fields(self):
        source = json.loads(build_manifest(MANIFEST, '1.0.5-blur.2'))
        actual = json.loads(identity_manifest(build_manifest(MANIFEST, '1.0.5-blur.2'), True))
        expected = dict(source, id=TEST_ID, name='Bing Wallpapers (Test)')
        self.assertEqual(actual, expected)
        self.assertNotEqual(actual['id'], PRODUCTION_ID)
        self.assertIn('PLUGIN_ID="' + actual['id'] + '"', test_identity_source())
        self.assertIn('TEST=true', test_identity_source())

    def test_prerelease_requires_test_build(self):
        prerelease = build_manifest(MANIFEST, '1.0.5-blur.2')
        with self.assertRaisesRegex(ValueError, 'require --test'):
            identity_manifest(prerelease, False)
        self.assertEqual(json.loads(identity_manifest(prerelease, True))['version'], '1.0.5-blur.2')
        self.assertEqual(json.loads(identity_manifest(build_manifest(MANIFEST, '1.0.5'), False))['version'], '1.0.5')

    def test_unexpected_production_identity_is_rejected(self):
        for field in ('id', 'name'):
            source = json.loads(build_manifest(MANIFEST))
            source[field] = 'unexpected'
            for test in (False, True):
                with self.assertRaisesRegex(ValueError, 'Unexpected production identity'):
                    identity_manifest(json.dumps(source).encode(), test)


if __name__ == '__main__':
    unittest.main()
