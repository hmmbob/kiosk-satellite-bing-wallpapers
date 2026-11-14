"""Keep development packages separate from the installed production plugin."""
import json

PRODUCTION_ID = 'kiosk-satellite-bing-wallpapers'
TEST_ID = PRODUCTION_ID + '-test'


def identity_manifest(manifest_bytes, test):
    manifest = json.loads(manifest_bytes)
    if manifest['id'] != PRODUCTION_ID or manifest['name'] != 'Bing Wallpapers':
        raise ValueError('Unexpected production identity; update the build identity configuration.')
    if not test:
        if '-' in manifest['version']:
            raise ValueError('Prerelease versions require --test to protect the production installation.')
        return manifest_bytes
    manifest['id'] = TEST_ID
    manifest['name'] = 'Bing Wallpapers (Test)'
    return (json.dumps(manifest, indent=2, ensure_ascii=False) + '\n').encode('utf-8')


def test_identity_source():
    return ('''// Generated for an isolated test package; do not edit.
package kiosk.plugins.bing;
final class BuildIdentity {
    static final String PLUGIN_ID="%s";
    static final boolean TEST=true;
}
''' % TEST_ID)
