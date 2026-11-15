#!/usr/bin/env python3
"""Upload verified release files without replacing any existing release asset."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from urllib.parse import quote
import zipfile

from validate_package import validate_release

BOT = (41898282, 'github-actions[bot]', 'Bot')


def gh(*args):
    return subprocess.run(['gh', *args], check=True, capture_output=True, text=True).stdout


def existing_assets(repository, release_id):
    pages = json.loads(gh('api', '--paginate', '--slurp',
                         f'repos/{repository}/releases/{release_id}/assets?per_page=100'))
    return [asset for page in pages for asset in page]


def upload_plan(files, assets, existing_digest):
    """Check every existing asset before allowing the first upload."""
    pending = []
    for file in files:
        found = [asset for asset in assets if asset.get('name') == file.name]
        if len(found) > 1:
            raise ValueError(f'Duplicate existing asset: {file.name}')
        if not found:
            pending.append(file)
            continue
        asset = found[0]
        uploader = asset.get('uploader') or {}
        if asset.get('state') != 'uploaded' or tuple(uploader.get(k) for k in ('id', 'login', 'type')) != BOT:
            raise ValueError(f'{file.name} is not a completed github-actions[bot] upload; publish a new version.')
        expected = 'sha256:' + hashlib.sha256(file.read_bytes()).hexdigest()
        digest = asset.get('digest') or existing_digest(file.name)
        if asset.get('size') != file.stat().st_size or digest != expected:
            raise ValueError(f'{file.name} already exists with different contents; publish a new version.')
    return pending


def publish(directory, tag, repository):
    if os.environ.get('GITHUB_REF_TYPE') != 'tag' or os.environ.get('GITHUB_REF_NAME') != tag:
        raise ValueError('Run this workflow on the matching release tag, not a branch.')
    files = validate_release(directory, tag.removeprefix('v'))
    release = json.loads(gh('api', f'repos/{repository}/releases/tags/{quote(tag, safe="")}'))
    if release.get('draft') is not False or release.get('prerelease') is not False:
        raise ValueError('Production uploads require a published stable release.')

    def downloaded_digest(name):
        with tempfile.TemporaryDirectory(prefix='bing-release-') as folder:
            gh('release', 'download', tag, '--repo', repository, '--pattern', name, '--dir', folder)
            return 'sha256:' + hashlib.sha256((Path(folder) / name).read_bytes()).hexdigest()

    assets = existing_assets(repository, release['id'])
    pending = upload_plan(files, assets, downloaded_digest)
    for file in files:
        if file not in pending:
            print(f'Keep identical release asset: {file.name}')
    for file in pending:
        # No --clobber: concurrent or manual uploads must fail without replacing bytes.
        gh('release', 'upload', tag, str(file), '--repo', repository)
        print(f'Uploaded: {file.name}')
    remaining = upload_plan(files, existing_assets(repository, release['id']), downloaded_digest)
    if remaining:
        raise ValueError('Release upload is incomplete.')
    print('PASS: all three verified release assets are present; no assets were replaced.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tag')
    parser.add_argument('--directory', default='dist')
    parser.add_argument('--repository', default=os.environ.get('GITHUB_REPOSITORY'))
    args = parser.parse_args()
    if not args.repository:
        parser.error('--repository or GITHUB_REPOSITORY is required')
    try:
        publish(args.directory, args.tag, args.repository)
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, f'Release upload stopped: {error}\n')
