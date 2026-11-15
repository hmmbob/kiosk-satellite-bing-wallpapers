import copy
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import warnings
import zipfile
import zlib

from validate_package import MANIFEST, dex_classes, json_document, validate_manifest, validate_release

ROOT = Path(__file__).resolve().parents[1]
SOURCE = json.loads((ROOT / MANIFEST).read_bytes())


def dex(*classes):
    """Small DEX table fixture; the CI build also checks real D8 output."""
    count = len(classes)
    strings, types, definitions = 112, 112 + count * 4, 112 + count * 8
    data_start = 112 + count * 40
    data = bytearray(data_start)
    data[:8] = b'dex\n035\0'
    struct.pack_into('<II', data, 36, 112, 0x12345678)
    for at, start in ((56, strings), (64, types), (96, definitions)):
        struct.pack_into('<II', data, at, count, start if count else 0)
    for i, name in enumerate(classes):
        encoded = name.encode('ascii')
        struct.pack_into('<I', data, strings + i * 4, len(data))
        struct.pack_into('<I', data, types + i * 4, i)
        struct.pack_into('<I', data, definitions + i * 32, i)
        length = len(encoded)
        while length >= 128:
            data.append((length & 127) | 128)
            length >>= 7
        data.extend(bytes([length]) + encoded + b'\0')
    struct.pack_into('<I', data, 32, len(data))
    struct.pack_into('<II', data, 104, len(data) - data_start, data_start)
    return sign_dex(data)


def sign_dex(data):
    data = bytearray(data)
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into('<I', data, 8, zlib.adler32(data[12:]))
    return bytes(data)


def zipped(entries):
    data = io.BytesIO()
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)
        with zipfile.ZipFile(data, 'w', zipfile.ZIP_DEFLATED) as archive:
            for name, body in entries:
                archive.writestr(name, body)
    return data.getvalue()


class PackageFixture:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.manifest = copy.deepcopy(SOURCE)
        self.manifest_bytes = (json.dumps(self.manifest, ensure_ascii=False) + '\n').encode()
        self.jar = zipped([('classes.dex', dex('Lkiosk/plugins/bing/BingPlugin;'))])
        self.package = self.directory / f"{self.manifest['id']}-{self.manifest['version']}.zip"

    def write(self, entries=None, inner=None):
        (self.directory / MANIFEST).write_bytes(self.manifest_bytes)
        if entries is None:
            entries = [(MANIFEST, self.manifest_bytes if inner is None else inner),
                       ('plugin.jar', self.jar), ('LICENSE', b'Apache-2.0'),
                       ('assets/icon.png', (ROOT / 'assets/icon.png').read_bytes())]
        self.package.write_bytes(zipped(entries))
        self.checksum()

    def checksum(self):
        digest = hashlib.sha256(self.package.read_bytes()).hexdigest()
        self.package.with_suffix('.zip.sha256').write_bytes(f'{digest}  {self.package.name}\n'.encode())


class PackageValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.fixture = PackageFixture(self.temp.name)
        self.fixture.write()

    def test_real_manifest_and_complete_release_files(self):
        self.assertEqual(len(validate_release(self.temp.name, SOURCE['version'])), 3)

    def test_manifest_rejects_invalid_contract_and_setting_values(self):
        changes = [
            lambda m: m.update(schemaVersion=True),
            lambda m: m.update(apiVersion=2),
            lambda m: m.update(minAndroidSdk=23),
            lambda m: m.update(entryClass='../BingPlugin'),
            lambda m: m.update(description='x' * 1001),
            lambda m: m.update(capabilities=['unknown']),
            lambda m: m.update(capabilities=['noise']),
            lambda m: m.update(headless=1),
            lambda m: m.update(minAppVersion='2026.13.1'),
            lambda m: m.update(icon='../icon.png'),
            lambda m: m.update(unsupported='unchecked'),
            lambda m: m['settings'][0].update(default=True),
            lambda m: m['settings'][0].update(default=9),
            lambda m: m['settings'][0].update(step=0),
            lambda m: m['settings'][0].update(default=1.5),
            lambda m: m['settings'][0].update(max=8.5),
            lambda m: m['settings'][0].update(min=float('nan')),
            lambda m: m['settings'][0].update(choices='plugin'),
            lambda m: m['settings'][0].update(type='unknown'),
            lambda m: m['settings'][1].update(default=False),
            lambda m: m['settings'][-1].update(default='999'),
            lambda m: m['settings'][-1].update(options=['1280', '1280']),
            lambda m: m['settings'].append(copy.deepcopy(m['settings'][0])),
            lambda m: m['commands'].append(copy.deepcopy(m['commands'][0])),
            lambda m: m['groups'][0].update(title='Missing'),
        ]
        for index, change in enumerate(changes):
            with self.subTest(index=index):
                manifest = copy.deepcopy(SOURCE)
                change(manifest)
                with self.assertRaises(ValueError):
                    validate_manifest(manifest)

    def test_json_rejects_duplicate_fields_and_non_finite_numbers(self):
        for body in (b'{"version":"1","version":"2"}', b'{"default":NaN}', b'{"default":Infinity}'):
            with self.subTest(body=body), self.assertRaises(ValueError):
                json_document(body)

    def test_version_mismatch_and_changed_checksum_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'version'):
            validate_release(self.temp.name, '99.0.0')
        self.fixture.package.with_suffix('.zip.sha256').write_bytes(b'0' * 64 + b'  wrong.zip\n')
        with self.assertRaisesRegex(ValueError, 'checksum'):
            validate_release(self.temp.name)

    def test_manifests_must_be_byte_identical(self):
        self.fixture.write(inner=json.dumps(SOURCE).encode())
        with self.assertRaisesRegex(ValueError, 'manifests differ'):
            validate_release(self.temp.name)

    def test_unexpected_paths_duplicate_entries_and_missing_files_are_rejected(self):
        with zipfile.ZipFile(self.fixture.package) as archive:
            original = [(i.filename, archive.read(i)) for i in archive.infolist()]
        cases = [original + [('assets/../escape', b'x')], original + [('extra.txt', b'x')],
                 original + [original[0]], original[1:],
                 original + [('assets/huge', b'x' * (4 * 1024 * 1024))]]
        for entries in cases:
            with self.subTest(names=[n for n, _ in entries]):
                self.fixture.write(entries)
                with self.assertRaises(ValueError):
                    validate_release(self.temp.name)

    def test_jar_requires_dex_and_rejects_bundled_classes_or_sdk_definitions(self):
        cases = [[], [('BingPlugin.class', b'class')], [('classes.dex', b'dex\n')],
                 [('classes.dex', dex('Lme/jxl/kiosk/plugins/PluginHost;'))],
                 [('classes.dex', dex('Lkiosk/plugins/bing/Other;'))],
                 [('classes.dex', dex('Lkiosk/plugins/bing/BingPlugin;')),
                  ('classes2.dex', dex('Lkiosk/plugins/bing/BingPlugin;'))]]
        for entries in cases:
            with self.subTest(names=[n for n, _ in entries]):
                self.fixture.jar = zipped(entries)
                self.fixture.write()
                with self.assertRaises(ValueError):
                    validate_release(self.temp.name)

    def test_dex_signature_checksum_and_table_bounds(self):
        valid = dex('Lkiosk/plugins/bing/BingPlugin;')
        self.assertIn('Lkiosk/plugins/bing/BingPlugin;', dex_classes(valid))
        damaged = bytearray(valid)
        damaged[-2] ^= 1
        with self.assertRaisesRegex(ValueError, 'signature'):
            dex_classes(damaged)
        damaged = bytearray(valid)
        damaged[8] ^= 1
        with self.assertRaisesRegex(ValueError, 'checksum'):
            dex_classes(damaged)
        damaged = bytearray(valid)
        struct.pack_into('<I', damaged, 100, len(valid) + 4)
        with self.assertRaisesRegex(ValueError, 'table'):
            dex_classes(sign_dex(damaged))


if __name__ == '__main__':
    unittest.main()
