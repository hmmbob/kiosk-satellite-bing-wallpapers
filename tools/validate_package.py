#!/usr/bin/env python3
"""Validate the finished Bing Wallpapers release files, independently of the builder.

SDK 1 rules: jxlarrea/kiosk-satellite at c0d11069763eac7bf0b7ca16ceec2ea3f6614ba6,
PluginManifest.kt and PluginPackage.kt. This validator supports the manifest fields
used by this plugin; new SDK features fail closed until their checks are added.
"""
import argparse
import hashlib
import io
import json
import math
from pathlib import Path
import re
import stat
import struct
import zipfile
import zlib

from plugin_assets import MAX_PACKAGE_BYTES, MAX_PACKAGE_FILES

MANIFEST = 'kiosk-satellite-plugin.json'
CAPABILITIES = {'overlay', 'native', 'entities', 'host.read', 'host.control', 'shizuku',
                'screensaver', 'screensaver.mode', 'noise', 'ha', 'voice.skin',
                'voice.text', 'camera', 'barcode', 'ma', 'player'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def json_document(data):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, f'Duplicate JSON key: {key}')
            result[key] = value
        return result

    def invalid(value):
        raise ValueError(f'Invalid JSON constant: {value}')

    return json.loads(data, object_pairs_hook=pairs, parse_constant=invalid)


def text(data, key, limit):
    value = data.get(key)
    require(isinstance(value, str) and value.strip() and len(value) <= limit, f'Invalid {key}')
    return value


def fields(data, allowed):
    require(isinstance(data, dict), 'Expected a JSON object')
    require(not data.keys() - allowed, f'Unsupported fields: {sorted(data.keys() - allowed)}')


def array(data, key, maximum):
    value = data.get(key, [])
    require(isinstance(value, list) and len(value) <= maximum, f'Invalid {key}')
    return value


def number(value):
    try:
        return type(value) in (int, float) and math.isfinite(value)
    except OverflowError:
        return False


def asset_path(path):
    return isinstance(path, str) and 1 <= len(path) <= 240 and all(
        part not in ('.', '..') and re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}', part)
        for part in path.split('/'))


def validate_manifest(manifest):
    fields(manifest, {'schemaVersion', 'apiVersion', 'id', 'name', 'version', 'minAndroidSdk',
                      'entryClass', 'description', 'author', 'license', 'capabilities',
                      'settings', 'commands', 'groups', 'icon', 'headless', 'minAppVersion'})
    for key in ('schemaVersion', 'apiVersion'):
        require(type(manifest.get(key)) is int and manifest[key] == 1, f'Unsupported {key}')
    require(re.fullmatch(r'[a-z][a-z0-9]*(?:-[a-z0-9]+)*', text(manifest, 'id', 64)), 'Invalid ID')
    require(re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9.-]+)?', text(manifest, 'version', 40)), 'Invalid version')
    require(re.fullmatch(r'[a-zA-Z_$][a-zA-Z0-9_$]*(?:\.[a-zA-Z_$][a-zA-Z0-9_$]*)+', text(manifest, 'entryClass', 200)), 'Invalid entry class')
    for key, limit in (('name', 80), ('description', 1000), ('author', 120), ('license', 120)):
        text(manifest, key, limit)
    require(type(manifest.get('minAndroidSdk')) is int and manifest['minAndroidSdk'] >= 24, 'Invalid minAndroidSdk')
    caps = manifest.get('capabilities')
    require(isinstance(caps, list) and all(isinstance(c, str) and c in CAPABILITIES for c in caps), 'Invalid capabilities')
    require(not ({'noise', 'voice.text'} & set(caps)) or 'host.read' in caps, 'Missing host.read capability')
    require('voice.skin' not in caps or {'overlay', 'host.read'} <= set(caps), 'Missing voice.skin capabilities')
    if 'headless' in manifest:
        require(type(manifest['headless']) is bool, 'Invalid headless')
    if 'minAppVersion' in manifest:
        require(re.fullmatch(r'[0-9]{4}\.(?:[1-9]|1[0-2])\.(?:0|[1-9][0-9]{0,3})', text(manifest, 'minAppVersion', 20)), 'Invalid minAppVersion')
    if 'icon' in manifest:
        require(asset_path(manifest['icon']) and manifest['icon'].lower().endswith('.png'), 'Invalid icon')
    keys, groups = set(), set()
    for setting in array(manifest, 'settings', 50):
        fields(setting, {'key', 'type', 'title', 'default', 'group', 'description', 'unit',
                         'min', 'max', 'step', 'input', 'choices', 'options'})
        key = text(setting, 'key', 64)
        require(re.fullmatch(r'[a-zA-Z][a-zA-Z0-9_]*', key) and key not in keys, 'Invalid or duplicate setting key')
        keys.add(key)
        text(setting, 'title', 80)
        groups.add(text(setting, 'group', 80) if 'group' in setting else 'Settings')
        if 'description' in setting:
            text(setting, 'description', 400)
        if 'unit' in setting:
            require(isinstance(setting['unit'], str), 'Invalid unit')
        kind, default = setting.get('type'), setting.get('default')
        require('default' in setting, 'Missing setting default')
        if 'choices' in setting:
            require(kind == 'string' and setting['choices'] == 'plugin', 'Invalid choices')
        if 'input' in setting:
            require(kind == 'number' and setting['input'] in ('slider', 'field'), 'Invalid input')
        if kind == 'string':
            require(isinstance(default, str) and len(default) <= 512, 'Invalid text default')
        elif kind == 'boolean':
            require(type(default) is bool, 'Invalid boolean default')
        elif kind == 'number':
            low, high, step = setting.get('min'), setting.get('max'), setting.get('step', 1)
            require(all(number(v) for v in (low, high, step, default)), 'Invalid numeric setting')
            require(low < high and step > 0 and low <= default <= high, 'Numeric setting outside range')
            stops, selected = (high - low) / step, (default - low) / step
            require(math.isfinite(stops) and math.isfinite(selected), 'Invalid numeric step')
            require(abs(selected - round(selected)) < 0.00001, 'Default does not match step')
            if setting.get('input', 'slider') == 'slider':
                require(1 <= stops <= 10000 and abs(stops - round(stops)) < 0.00001, 'Invalid slider range')
        elif kind == 'select':
            options = array(setting, 'options', 32)
            require(options and all(isinstance(v, str) and v.strip() and len(v) <= 80 for v in options), 'Invalid options')
            require(len(set(options)) == len(options) and isinstance(default, str) and default in options, 'Invalid selection default')
        else:
            raise ValueError(f'Unsupported setting type: {kind}')
    ids = set()
    for command in array(manifest, 'commands', 20):
        fields(command, {'id', 'title'})
        key = text(command, 'id', 64)
        require(re.fullmatch(r'[a-z][a-zA-Z0-9]*', key) and key not in ids, 'Invalid or duplicate command ID')
        ids.add(key)
        text(command, 'title', 80)
    seen = set()
    for group in array(manifest, 'groups', 20):
        fields(group, {'title'})
        title = text(group, 'title', 80)
        require(title in groups and title not in seen, 'Invalid or duplicate display group')
        seen.add(title)


def archive_entries(archive, allowed, maximum):
    entries = archive.infolist()
    names = [entry.filename for entry in entries]
    require(len(names) == len(set(names)) and len(names) <= maximum, 'Duplicate or too many archive entries')
    require(sum(e.file_size for e in entries) <= MAX_PACKAGE_BYTES, 'Expanded archive exceeds 4 MB')
    for entry in entries:
        require(not entry.is_dir() and not stat.S_ISLNK(entry.external_attr >> 16)
                and not entry.flag_bits & 1 and allowed(entry.filename), f'Unexpected archive entry: {entry.filename}')
    require(archive.testzip() is None, 'Archive CRC check failed')
    return set(names)


def dex_classes(data):
    require(len(data) >= 112 and re.fullmatch(rb'dex\n0(?:3[5-9]|40)\x00', data[:8]), 'Invalid DEX header')
    require(struct.unpack_from('<III', data, 32) == (len(data), 112, 0x12345678), 'Invalid DEX size or byte order')
    require(data[12:32] == hashlib.sha1(data[32:]).digest(), 'Invalid DEX signature')
    require(struct.unpack_from('<I', data, 8)[0] == zlib.adler32(data[12:]), 'Invalid DEX checksum')
    tables = {}
    for key, offset, width in (('strings', 56, 4), ('types', 64, 4), ('protos', 72, 12),
                               ('fields', 80, 8), ('methods', 88, 8), ('classes', 96, 32)):
        count, start = struct.unpack_from('<II', data, offset)
        require((count == 0 and start == 0) or (start >= 112 and start % 4 == 0 and start + count * width <= len(data)), 'Invalid DEX table')
        tables[key] = count, start
    strings_count, strings = tables['strings']
    types_count, types = tables['types']
    classes_count, classes = tables['classes']
    defined = set()
    for index in range(classes_count):
        type_index, = struct.unpack_from('<I', data, classes + index * 32)
        require(type_index < types_count, 'Invalid DEX class type')
        string_index, = struct.unpack_from('<I', data, types + type_index * 4)
        require(string_index < strings_count, 'Invalid DEX type descriptor')
        start, = struct.unpack_from('<I', data, strings + string_index * 4)
        require(112 <= start < len(data), 'Invalid DEX string offset')
        for _ in range(5):
            require(start < len(data), 'Truncated DEX string')
            value = data[start]
            start += 1
            if value < 128:
                break
        else:
            raise ValueError('Invalid DEX string length')
        end = data.find(b'\x00', start)
        require(end >= start, 'Unterminated DEX descriptor')
        descriptor = data[start:end].decode('ascii')
        require(re.fullmatch(r'L[a-zA-Z0-9_$/]+;', descriptor), 'Invalid DEX class descriptor')
        require(not descriptor.startswith(('Lme/jxl/kiosk/plugins/', 'Ljava/', 'Ljavax/', 'Landroid/')), 'SDK or platform classes bundled in DEX')
        require(descriptor not in defined, 'Duplicate DEX class')
        defined.add(descriptor)
    return defined


def validate_release(directory, version=None):
    directory = Path(directory)
    standalone = (directory / MANIFEST).read_bytes()
    require(0 < len(standalone) <= 32768, 'Manifest exceeds 32 KB or is empty')
    manifest = json_document(standalone)
    validate_manifest(manifest)
    if version is not None:
        require(manifest['version'] == version, 'Package version does not match release tag')
    package = directory / f"{manifest['id']}-{manifest['version']}.zip"
    checksum = package.with_suffix('.zip.sha256')
    require(0 < package.stat().st_size <= MAX_PACKAGE_BYTES, 'Plugin ZIP exceeds 4 MB or is empty')
    require(0 < checksum.stat().st_size <= 1024, 'Invalid checksum file size')
    digest = hashlib.sha256(package.read_bytes()).hexdigest()
    require(checksum.read_bytes() == f'{digest}  {package.name}\n'.encode('ascii'), 'Invalid release checksum')
    required = {MANIFEST, 'plugin.jar', 'LICENSE'}
    with zipfile.ZipFile(package) as archive:
        names = archive_entries(archive, lambda name: name in required or
                                (name.startswith('assets/') and asset_path(name[7:])), MAX_PACKAGE_FILES)
        require(required <= names, 'Missing required package files')
        require(archive.read(MANIFEST) == standalone, 'Packaged and release manifests differ')
        require(archive.read('LICENSE').strip(), 'Empty package license')
        classes = set()
        with zipfile.ZipFile(io.BytesIO(archive.read('plugin.jar'))) as jar:
            dex_names = archive_entries(jar, lambda name: re.fullmatch(r'classes(?:[2-9]|[1-9][0-9]+)?\.dex', name), MAX_PACKAGE_FILES)
            require('classes.dex' in dex_names, 'Missing classes.dex')
            for name in sorted(dex_names):
                found = dex_classes(jar.read(name))
                require(not classes & found, 'Duplicate class across DEX files')
                classes.update(found)
        require('L' + manifest['entryClass'].replace('.', '/') + ';' in classes, 'Entry class missing from DEX')
        if 'icon' in manifest:
            icon = archive.read('assets/' + manifest['icon'])
            require(len(icon) <= 256 * 1024 and len(icon) >= 24 and
                    icon[:8] == b'\x89PNG\r\n\x1a\n' and icon[12:16] == b'IHDR' and
                    struct.unpack_from('>II', icon, 16) == (256, 256), 'Invalid plugin icon')
    return [package, checksum, directory / MANIFEST]


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', nargs='?', default='dist')
    parser.add_argument('--version', help='Expected package version from the release tag')
    args = parser.parse_args()
    try:
        files = validate_release(args.directory, args.version)
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f'Package validation failed: {error}\n')
    print('PASS: release manifest, checksum, ZIP, assets and DEX classes: ' + files[0].name)
