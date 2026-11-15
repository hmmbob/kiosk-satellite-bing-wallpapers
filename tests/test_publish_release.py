import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import publish_release as publishing
from test_package_validation import PackageFixture, SOURCE


def asset(file, digest=True):
    return {'name': file.name, 'state': 'uploaded', 'size': file.stat().st_size,
            'digest': 'sha256:' + hashlib.sha256(file.read_bytes()).hexdigest() if digest else None,
            'uploader': dict(zip(('id', 'login', 'type'), publishing.BOT))}


class PublishReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        fixture = PackageFixture(self.temp.name)
        fixture.write()
        self.files = publishing.validate_release(self.temp.name)
        self.assets, self.uploads = [], []
        self.release = {'id': 123, 'draft': False, 'prerelease': False}
        self.tag = 'v' + SOURCE['version']
        env = patch.dict(os.environ, GITHUB_REF_TYPE='tag', GITHUB_REF_NAME=self.tag)
        env.start()
        self.addCleanup(env.stop)

    def gh(self, *args):
        if args[:2] == ('api', '--paginate'):
            return json.dumps([self.assets])
        if args[0] == 'api':
            return json.dumps(self.release)
        if args[:2] == ('release', 'upload'):
            self.assertNotIn('--clobber', args)
            file = Path(args[3])
            self.uploads.append(file.name)
            self.assets.append(asset(file))
            return ''
        if args[:2] == ('release', 'download'):
            name, folder = args[args.index('--pattern') + 1], args[args.index('--dir') + 1]
            (Path(folder) / name).write_bytes(next(f for f in self.files if f.name == name).read_bytes())
            return ''
        self.fail(f'Unexpected gh command: {args}')

    def publish(self):
        with patch.object(publishing, 'gh', side_effect=self.gh):
            publishing.publish(self.temp.name, self.tag, 'owner/repo')

    def test_first_publish_uploads_exactly_three_files(self):
        self.publish()
        self.assertEqual(self.uploads, [f.name for f in self.files])

    def test_identical_rerun_does_not_upload_anything(self):
        self.assets = [asset(file) for file in self.files]
        self.publish()
        self.assertEqual(self.uploads, [])

    def test_partial_release_keeps_existing_file_and_only_uploads_missing(self):
        self.assets = [asset(self.files[0])]
        self.publish()
        self.assertEqual(self.uploads, [f.name for f in self.files[1:]])

    def test_conflict_found_last_blocks_all_uploads(self):
        conflicting = asset(self.files[-1])
        conflicting['digest'] = 'sha256:' + '0' * 64
        self.assets = [conflicting]
        with self.assertRaisesRegex(ValueError, 'different contents'):
            self.publish()
        self.assertEqual(self.uploads, [])
        self.assertEqual(self.assets, [conflicting])

    def test_invalid_uploader_incomplete_asset_and_wrong_size_are_rejected(self):
        for mutation in ({'uploader': {'login': 'someone'}}, {'state': 'starter'}, {'size': 1}):
            with self.subTest(mutation=mutation):
                self.assets = [dict(asset(self.files[-1]), **mutation)]
                with self.assertRaises(ValueError):
                    self.publish()
                self.assertEqual(self.uploads, [])

    def test_legacy_assets_without_digests_are_downloaded_and_compared(self):
        self.assets = [asset(file, digest=False) for file in self.files]
        self.publish()
        self.assertEqual(self.uploads, [])

    def test_duplicate_assets_are_rejected(self):
        self.assets = [asset(self.files[0]), asset(self.files[0])]
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.publish()
        self.assertEqual(self.uploads, [])

    def test_branch_draft_and_prerelease_are_rejected(self):
        with patch.dict(os.environ, GITHUB_REF_TYPE='branch'):
            with self.assertRaisesRegex(ValueError, 'tag'):
                self.publish()
        for field in ('draft', 'prerelease'):
            self.release[field] = True
            with self.assertRaisesRegex(ValueError, 'stable'):
                self.publish()
            self.release[field] = False
        self.assertEqual(self.uploads, [])

    def test_upload_race_fails_without_clobber_or_other_mutations(self):
        def race(*args):
            if args[:2] == ('release', 'upload'):
                self.assertNotIn('--clobber', args)
                raise subprocess.CalledProcessError(1, ['gh', *args])
            return self.gh(*args)
        with patch.object(publishing, 'gh', side_effect=race):
            with self.assertRaises(subprocess.CalledProcessError):
                publishing.publish(self.temp.name, self.tag, 'owner/repo')
        self.assertEqual(self.uploads, [])


if __name__ == '__main__':
    unittest.main()
