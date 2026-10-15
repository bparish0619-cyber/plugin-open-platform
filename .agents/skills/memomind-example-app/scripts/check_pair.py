#!/usr/bin/env python3
"""Check declared Web/Glass pairing and optional GMP identity metadata, without running plugins."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile

REPO = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(REPO / 'GlassSDK/build-host/tools'))
from gmp_xip_pack import HEADER


def read_manifest(path):
    path = Path(path)
    if path.is_dir():
        path = path / 'manifest.json'
    if path.suffix == '.mmpkg':
        with zipfile.ZipFile(path) as archive:
            if archive.namelist().count('manifest.json') != 1:
                raise ValueError('MMPKG must contain exactly one root manifest.json')
            result = json.loads(archive.read('manifest.json'))
    else:
        result = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(result, dict) or not isinstance(result.get('id'), str) or not result['id']:
        raise ValueError('Manifest must contain a nonempty id: ' + str(path))
    return result


def version(value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9]+(?:\.[0-9]+){0,3}', value):
        raise ValueError('Expected a numeric version with one to four components: ' + repr(value))
    parts = tuple(int(part) for part in value.split('.'))
    return parts + (0,) * (4 - len(parts))


def protocols(entries, version_key):
    if not isinstance(entries, list):
        raise ValueError('protocols must be an array')
    result = {}
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get('id'), str) or not entry['id']:
            raise ValueError('Protocol entries require a nonempty id')
        if entry['id'] in result:
            raise ValueError('Duplicate protocol: ' + entry['id'])
        result[entry['id']] = version(entry.get(version_key))
    return result


def check_pair(web, glass):
    requirements = web.get('deviceRequirements')
    if not isinstance(requirements, dict):
        raise ValueError('No deviceRequirements: declare the intended pair or treat this as a phone-only application')
    provides = glass.get('provides', {})
    if not isinstance(provides, dict):
        raise ValueError('Glass provides must be an object')
    needed = protocols(requirements.get('protocols', []), 'minVersion')
    available = protocols(provides.get('protocols', []), 'version')
    errors, notes = [], []
    required_id = requirements.get('requiredPluginId')
    if required_id is not None:
        if not isinstance(required_id, str) or not required_id:
            raise ValueError('requiredPluginId must be a nonempty string')
        if required_id != glass['id']:
            errors.append('Required plugin ' + required_id + '; selected ' + glass['id'])
        if 'minPluginVersion' in requirements:
            if version(str(glass.get('version'))) < version(requirements['minPluginVersion']):
                errors.append('Glass package version is below minPluginVersion')
    for protocol_id, minimum in needed.items():
        if protocol_id not in available:
            errors.append('Missing protocol ' + protocol_id)
        elif available[protocol_id] < minimum:
            errors.append('Protocol version too old: ' + protocol_id)
    preferred = requirements.get('preferredPluginId')
    if preferred and preferred != glass['id']:
        notes.append('Preferred plugin is ' + str(preferred) + '; a different provider must satisfy all requirements')
    if not needed and not required_id:
        notes.append('No required protocol or identity is declared; this is weak evidence of compatibility')
    return errors, notes


def check_gmp(path, glass):
    path = Path(path)
    with path.open('rb') as stream:
        data = stream.read(HEADER.size)
    if len(data) != HEADER.size:
        raise ValueError('Truncated GMP header: ' + str(path))
    fields = HEADER.unpack(data)
    if fields[0] != b'GMPK' or fields[1] != 2 or fields[3] != path.stat().st_size:
        raise ValueError('Expected a GMP v2 header with matching file size')
    name = fields[15].split(b'\0', 1)[0].decode('utf-8')
    if (name, fields[14], fields[2]) != (glass.get('name'), glass.get('version'), glass.get('abi_version')):
        raise ValueError('GMP name/version/ABI differs from the selected Glass manifest; rebuild or select the matching package')


def check_delivery_inputs(web_path, gmp_path, web):
    if Path(web_path).suffix != '.mmpkg' or not Path(web_path).is_file():
        raise ValueError('Delivery requires the built .mmpkg, not a source manifest/directory')
    if not gmp_path or not Path(gmp_path).is_file():
        raise ValueError('Delivery requires --gmp pointing to the built companion')
    requirements = web.get('deviceRequirements', {})
    if not requirements.get('protocols') and not requirements.get('requiredPluginId'):
        raise ValueError('Delivery requires a required protocol or plugin identity; a preferred ID alone is insufficient')


def stream_hash(stream):
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(chunk)
    return digest.hexdigest()


def check_bundle(path, web_path, gmp_path):
    """Check actual paired export bytes and review attachment integrity, not execution."""
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or any('/' in name or '\\' in name for name in names):
            raise ValueError('Paired ZIP must have unique root-level files')
        web_entries = [name for name in names if name.endswith('.mmpkg')]
        glass_entries = [name for name in names if name.endswith('.gmp')]
        if len(web_entries) != 1 or len(glass_entries) != 1:
            raise ValueError('Paired ZIP requires exactly one MMPKG and one GMP')
        if set(names) != {web_entries[0], glass_entries[0], 'review.json', 'source.enc'}:
            raise ValueError('Paired ZIP requires matching review.json and source.enc and no extra entries')
        for entry, intended in [(web_entries[0], web_path), (glass_entries[0], gmp_path)]:
            with archive.open(entry) as packed, Path(intended).open('rb') as source:
                if stream_hash(packed) != stream_hash(source):
                    raise ValueError('Paired ZIP contains a different ' + Path(entry).suffix + ': ' + entry)
        if archive.getinfo('review.json').file_size > 1024 * 1024:
            raise ValueError('Unexpectedly large review.json')
        review = json.loads(archive.read('review.json'))
        if not isinstance(review, dict) or review.get('format') != 'gm-review' or review.get('version') not in (1, 2):
            raise ValueError('Expected a gm-review v1/v2 descriptor')
        for field, entry in [('gmp', glass_entries[0]), ('source', 'source.enc')]:
            descriptor = review.get(field)
            with archive.open(entry) as stream:
                actual = stream_hash(stream)
            if not isinstance(descriptor, dict) or descriptor.get('sha256') != actual:
                raise ValueError('Review hash mismatch: ' + entry)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--web', required=True, help='Web source manifest/directory or the actual .mmpkg to deliver')
    parser.add_argument('--glass', required=True, help='manifest/directory for the Glass plugin actually selected in Studio')
    parser.add_argument('--gmp', help='actual GMP file; checks existence and name/version/ABI header metadata only')
    parser.add_argument('--delivery', action='store_true', help='require built Web/GMP artifacts and a required pairing constraint')
    parser.add_argument('--bundle', help='actual paired ZIP to deliver; implies --delivery and verifies package bytes and review hashes')
    args = parser.parse_args()
    try:
        web, glass = read_manifest(args.web), read_manifest(args.glass)
        errors, notes = check_pair(web, glass)
        if args.delivery or args.bundle:
            check_delivery_inputs(args.web, args.gmp, web)
        if args.gmp:
            check_gmp(args.gmp, glass)
        print('Web: ' + web['id'] + ' -> Glass: ' + glass['id'])
        for note in notes:
            print('NOTE: ' + note)
        if errors:
            for error in errors:
                print('FAIL: ' + error)
            return 1
        if args.bundle:
            check_bundle(args.bundle, args.web, args.gmp)
            print('PASS: paired ZIP contains the checked packages and matching review hashes')
        print('PASS: declared pairing' + (' and GMP identity metadata' if args.gmp else ' (manifest only; no GMP checked)'))
        print('This does not validate GMP code, CRC, source freshness, signatures/review approval, channel handlers, permissions, Studio selection, or runtime behavior.')
        return 0
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print('FAIL: ' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
