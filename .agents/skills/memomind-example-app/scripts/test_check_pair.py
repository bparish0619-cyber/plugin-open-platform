"""Pairing regressions using synthetic manifests/header metadata, not executable GMP fixtures."""
import copy
import hashlib
import warnings
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.dont_write_bytecode = True
from check_pair import HEADER, check_gmp, check_pair, read_manifest, check_bundle

WEB = {'id': 'com.example.remote', 'deviceRequirements': {
    'preferredPluginId': 'com.example.pet',
    'protocols': [{'id': 'gm.example-pet', 'minVersion': '1.2'}],
}}
GLASS = {'id': 'com.example.pet', 'name': 'Example Pet', 'version': 3, 'abi_version': 256,
         'provides': {'protocols': [{'id': 'gm.example-pet', 'version': '1.2.0'}]}}


class PairTests(unittest.TestCase):
    def test_matching_protocol_accepts_equivalent_numeric_versions(self):
        self.assertEqual(check_pair(WEB, GLASS), ([], []))

    def test_default_web_bridge_does_not_provide_custom_protocol(self):
        bridge = {'id': 'com.gm.example.web-bridge', 'provides': {
            'protocols': [{'id': 'gm.scene', 'version': '1.0'}]}}
        errors, notes = check_pair(WEB, bridge)
        self.assertIn('Missing protocol gm.example-pet', errors)
        self.assertTrue(notes)

    def test_preferred_identity_is_advisory_but_required_identity_is_enforced(self):
        alternative = copy.deepcopy(GLASS)
        alternative['id'] = 'com.example.alternative'
        errors, notes = check_pair(WEB, alternative)
        self.assertFalse(errors)
        self.assertTrue(notes)
        web = copy.deepcopy(WEB)
        web['deviceRequirements']['requiredPluginId'] = GLASS['id']
        self.assertTrue(check_pair(web, alternative)[0])

    def test_protocol_and_required_package_minimum_versions(self):
        old = copy.deepcopy(GLASS)
        old['provides']['protocols'][0]['version'] = '1.1.9'
        self.assertIn('Protocol version too old: gm.example-pet', check_pair(WEB, old)[0])
        web = copy.deepcopy(WEB)
        web['deviceRequirements'].update(requiredPluginId=GLASS['id'], minPluginVersion='4')
        self.assertIn('Glass package version is below minPluginVersion', check_pair(web, GLASS)[0])

    def test_malformed_or_duplicate_protocols_do_not_pass(self):
        invalid = copy.deepcopy(GLASS)
        invalid['provides']['protocols'][0]['version'] = 'latest'
        with self.assertRaises(ValueError):
            check_pair(WEB, invalid)
        invalid['provides']['protocols'] = GLASS['provides']['protocols'] * 2
        with self.assertRaises(ValueError):
            check_pair(WEB, invalid)
        with self.assertRaises(ValueError):
            check_pair({'id': 'com.example.phone-only'}, GLASS)

    def test_built_web_package_requirements_are_used(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'old.mmpkg'
            stale = copy.deepcopy(WEB)
            stale['deviceRequirements']['protocols'][0]['minVersion'] = '2.0'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('manifest.json', json.dumps(stale))
            self.assertTrue(check_pair(read_manifest(path), GLASS)[0])

    def test_gmp_presence_and_identity_metadata(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'pet.gmp'
            # Header-only fixture: this checker intentionally does not validate executable code.
            path.write_bytes(HEADER.pack(b'GMPK', 2, 256, HEADER.size,
                                         *([0] * 10), 3, b'Example Pet'))
            check_gmp(path, GLASS)
            wrong = dict(GLASS, name='Different Plugin')
            with self.assertRaises(ValueError):
                check_gmp(path, wrong)
            path.write_bytes(b'GMPK')
            with self.assertRaises(ValueError):
                check_gmp(path, GLASS)
            path.unlink()
            with self.assertRaises(FileNotFoundError):
                check_gmp(path, GLASS)

    def test_cli_returns_failure_for_incompatible_pair(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'web.json').write_text(json.dumps(WEB), encoding='utf-8')
            (root / 'glass.json').write_text(json.dumps({'id': 'com.example.unrelated'}), encoding='utf-8')
            result = subprocess.run([sys.executable, str(Path(__file__).with_name('check_pair.py')),
                                     '--web', str(root / 'web.json'), '--glass', str(root / 'glass.json')],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 1)
            self.assertIn('Missing protocol gm.example-pet', result.stdout)



class DeliveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.web = self.root / 'app.mmpkg'
        with zipfile.ZipFile(self.web, 'w') as archive:
            archive.writestr('manifest.json', json.dumps(WEB))
        self.glass = self.root / 'glass.json'
        self.glass.write_text(json.dumps(GLASS), encoding='utf-8')
        self.gmp = self.root / 'app.gmp'
        self.gmp.write_bytes(HEADER.pack(b'GMPK', 2, 256, HEADER.size + 4,
                                        *([0] * 10), 3, b'Example Pet') + b'demo')
        encrypted = b'encrypted-source-fixture'
        review = {'format': 'gm-review', 'version': 2,
                  'gmp': {'sha256': hashlib.sha256(self.gmp.read_bytes()).hexdigest()},
                  'source': {'sha256': hashlib.sha256(encrypted).hexdigest()}}
        self.entries = {'app.mmpkg': self.web.read_bytes(), 'app.gmp': self.gmp.read_bytes(),
                        'review.json': json.dumps(review).encode(), 'source.enc': encrypted}
        self.bundle = self.root / 'app.zip'

    def write_bundle(self, updates=None):
        entries = dict(self.entries)
        entries.update(updates or {})
        with zipfile.ZipFile(self.bundle, 'w') as archive:
            for name, data in entries.items():
                if data is not None:
                    archive.writestr(name, data)

    def cli(self, *options, web=None, gmp=True):
        command = [sys.executable, str(Path(__file__).with_name('check_pair.py')),
                   '--web', str(web or self.web), '--glass', str(self.glass)]
        if gmp:
            command += ['--gmp', str(self.gmp)]
        return subprocess.run(command + list(options), capture_output=True, text=True)

    def test_delivery_rejects_source_only_and_missing_companion(self):
        source = self.root / 'web.json'
        source.write_text(json.dumps(WEB), encoding='utf-8')
        result = self.cli('--delivery', web=source)
        self.assertEqual(result.returncode, 1)
        self.assertIn('built .mmpkg', result.stderr)
        result = self.cli('--delivery', gmp=False)
        self.assertEqual(result.returncode, 1)
        self.assertIn('built companion', result.stderr)
        self.assertEqual(self.cli('--delivery').returncode, 0)

    def test_delivery_rejects_preferred_identity_without_requirements(self):
        weak = {'id': 'com.example.phone', 'deviceRequirements': {'preferredPluginId': GLASS['id']}}
        with zipfile.ZipFile(self.web, 'w') as archive:
            archive.writestr('manifest.json', json.dumps(weak))
        result = self.cli('--delivery')
        self.assertEqual(result.returncode, 1)
        self.assertIn('preferred ID alone', result.stderr)

    def test_bundle_implies_delivery_and_accepts_exact_pair(self):
        self.write_bundle()
        result = self.cli('--bundle', str(self.bundle))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('paired ZIP contains the checked packages', result.stdout)
        self.assertEqual(self.cli('--bundle', str(self.bundle), gmp=False).returncode, 1)

    def test_bundle_rejects_phone_only_export(self):
        self.write_bundle({'app.gmp': None})
        with self.assertRaisesRegex(ValueError, 'one MMPKG and one GMP'):
            check_bundle(self.bundle, self.web, self.gmp)

    def test_bundle_rejects_other_gmp_even_with_identical_header_identity(self):
        self.write_bundle({'app.gmp': self.gmp.read_bytes()[:-4] + b'old!'})
        result = self.cli('--bundle', str(self.bundle))
        self.assertEqual(result.returncode, 1)
        self.assertIn('different .gmp', result.stderr)

    def test_bundle_rejects_stale_phone_package(self):
        self.write_bundle({'app.mmpkg': b'older-phone-package'})
        with self.assertRaisesRegex(ValueError, 'different .mmpkg'):
            check_bundle(self.bundle, self.web, self.gmp)

    def test_bundle_rejects_missing_or_mismatched_review_attachments(self):
        for updates in [{'source.enc': None}, {'review.json': None},
                        {'source.enc': b'stale-encrypted-source'},
                        {'review.json': json.dumps({'format': 'gm-review', 'version': 2,
                                                   'gmp': {'sha256': '0' * 64}}).encode()}]:
            with self.subTest(updates=updates):
                self.write_bundle(updates)
                with self.assertRaises(ValueError):
                    check_bundle(self.bundle, self.web, self.gmp)

    def test_bundle_rejects_ambiguous_or_nested_entries(self):
        for updates in [{'other.gmp': b'other'}, {'nested/source.enc': b'other'}]:
            self.write_bundle(updates)
            with self.assertRaises(ValueError):
                check_bundle(self.bundle, self.web, self.gmp)
        self.write_bundle()
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.bundle, 'a') as archive:
                archive.writestr('app.gmp', self.gmp.read_bytes())
        with self.assertRaisesRegex(ValueError, 'unique root-level'):
            check_bundle(self.bundle, self.web, self.gmp)

if __name__ == '__main__':
    unittest.main()
