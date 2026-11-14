#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {'.git', '.gradle', '.kotlin', '.idea', 'build', '.cxx', 'captures'}


def source_filter(info):
    parts = Path(info.name).parts
    if any(part in EXCLUDED for part in parts):
        return None
    if Path(info.name).name in {'local.properties', 'release.keystore', 'keystore.properties'}:
        return None
    if Path(info.name).suffix in {'.jks', '.keystore'}:
        return None
    info.uid = info.gid = 0
    info.uname = info.gname = ''
    return info


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--client', type=Path, default=ROOT / 'build/telegram-client')
    parser.add_argument('--output', type=Path, default=ROOT / 'dist')
    args = parser.parse_args()
    client, output = args.client.resolve(), args.output.resolve()
    release = json.loads((ROOT / 'release.json').read_text())
    graphs = list((client / 'TMessagesProj/.cxx').glob('Debug/*/arm64-v8a/build.ninja'))
    if len(graphs) != 1:
        raise SystemExit('Expected one ARM64 debug native build graph')
    graph = graphs[0].read_text()
    if 'tgnet/NativeByteBuffer.cpp' not in graph or re.search(
            r'tgnet/(?:Connections?Manager|Connection(?:Socket|Session)?|Handshake|MTProtoScheme|Datacenter|TLSHello)\.cpp', graph):
        raise SystemExit('Unexpected native transport sources in the compiled APK build')
    metadata = client / 'TMessagesProj_AppStandalone/build/outputs/apk/afat/debug/output-metadata.json'
    data = json.loads(metadata.read_text())
    if data['applicationId'] != 'io.github.lubara7.tgmax':
        raise SystemExit('Refusing to release an unexpected applicationId')
    candidates = data['elements']
    if len(candidates) != 1:
        raise SystemExit('Expected exactly one ARM64 Telegram APK')
    element = candidates[0]
    if element['versionName'] != release['version']:
        raise SystemExit('APK version differs from release.json')
    if element['versionCode'] != release['versionCode']:
        raise SystemExit('APK versionCode differs from release.json')
    apk = metadata.parent / element['outputFile']
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if 'classes.dex' not in names or not any(n.startswith('lib/arm64-v8a/libtmessages') for n in names):
            raise SystemExit('Expected full Telegram classes and native engine in APK')
        if any(n.startswith('lib/') and not n.startswith('lib/arm64-v8a/') for n in names):
            raise SystemExit('Unexpected ABI in ARM64-only release')
        if len([n for n in names if n.startswith('lib/arm64-v8a/libzstd-jni-') and n.endswith('.so')]) != 1:
            raise SystemExit('Expected the Android Zstandard JNI library in the APK')
        if 'assets/tgmax-zstandard-notices.txt' not in names:
            raise SystemExit('Expected the Zstandard dependency notices in the APK')
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise SystemExit('Set ANDROID_HOME to verify manifest')
    badging = subprocess.check_output([str(Path(sdk) / 'build-tools/36.0.0/aapt'), 'dump', 'badging', str(apk)], text=True)
    if "sdkVersion:'27'" not in badging or "name='io.github.lubara7.tgmax'" not in badging:
        raise SystemExit('Manifest identity/minimum SDK check failed')
    output.mkdir(parents=True, exist_ok=True)
    filename = f'MAXGRAM-CLIENT-{release["version"]}-{release["abi"]}.apk'
    destination = output / filename
    shutil.copy2(apk, destination)
    (output / 'apk-manifest.txt').write_text(badging)
    modules = subprocess.check_output(['git', 'submodule', 'status'], cwd=client, text=True)
    (client / 'TGMAX_NATIVE_DEPENDENCIES.txt').write_text(modules)
    source = output / f'MAXGRAM-CLIENT-{release["version"]}-source.tar.gz'
    with tarfile.open(source, 'w:gz', compresslevel=3) as tar:
        tar.add(client, arcname='MAXGRAM-CLIENT/telegram-client', filter=source_filter)
        for path in ['LICENSE', 'SOURCE_PROVENANCE.md', 'upstream.lock.json', 'release.json', 'README.md', 'scripts', 'integrations', 'docs', 'max-backend']:
            tar.add(ROOT / path, arcname='MAXGRAM-CLIENT/integration/' + path, filter=source_filter)
    hashes = []
    for file in [destination, source]:
        digest = hashlib.file_digest(file.open('rb'), 'sha256').hexdigest()
        hashes.append(f'{digest}  {file.name}')
    (output / 'SHA256SUMS.txt').write_text('\n'.join(hashes) + '\n')
    catalog_path = ROOT / 'integrations/max-session-profiles.json'
    catalog = json.loads(catalog_path.read_text())
    profile = catalog['profiles'][catalog['activeProfile']]
    provenance = {
        'schema': 1, 'status': 'prototype', 'version': release['version'], 'tag': release['tag'],
        'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'sessionProfileSha256': hashlib.sha256(catalog_path.read_bytes()).hexdigest(),
        'sessionAppVersion': profile['appVersion'], 'sessionProfile': catalog['activeProfile'],
    }
    (output / 'build-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps({'apk': str(destination), 'apk_bytes': destination.stat().st_size,
                      'source_bytes': source.stat().st_size, 'version': release['version']}, indent=2))


if __name__ == '__main__':
    main()
