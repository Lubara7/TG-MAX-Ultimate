#!/usr/bin/env python3
"""Validate evidence for an already built APK; never start an account session."""
import argparse
import datetime as dt
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

CHECKS = {
    'phoneTokenAndDeviceQrLogin', 'chatsAndMessagesBothDirections',
    'allMediaBothDirections', 'androidContactNames', 'botNamesAvatarsAndStatus',
    'presenceMapping', 'formattingAndLinks', 'noDeadButtonsOrEmptyScreens',
    'noCrashes', 'firstLaunchDisclaimer',
}


def timestamp(value):
    if not isinstance(value, str):
        raise ValueError('Missing UTC timestamp')
    result = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    if result.utcoffset() != dt.timedelta(0):
        raise ValueError('Timestamp must specify UTC')
    return result


def validate(evidence, provenance, apk_digest, now=None):
    now = now or dt.datetime.now(dt.timezone.utc)
    failures = []
    if evidence.get('schema') != 1 or evidence.get('status') != 'passed':
        failures.append('Acceptance is pending')
    for key, actual in [('apkSha256', apk_digest), ('sourceCommit', provenance.get('sourceCommit')),
                        ('sessionProfileSha256', provenance.get('sessionProfileSha256'))]:
        value = evidence.get(key)
        pattern = r'[0-9a-f]{40}' if key == 'sourceCommit' else r'[0-9a-f]{64}'
        if not isinstance(value, str) or not re.fullmatch(pattern, value) or value != actual:
            failures.append('Evidence does not match ' + key)
    official = evidence.get('officialVersionEvidence', {})
    try:
        source = urlsplit(official.get('sourceUrl') or '')
        reviewed = timestamp(official.get('reviewedAtUtc'))
        if official.get('confirmed') is not True or source.scheme != 'https' or not source.hostname:
            raise ValueError('Official version review is missing')
        if not isinstance(official.get('appVersion'), str) or not official['appVersion'].strip():
            raise ValueError('Missing official app version')
        if reviewed > now or now - reviewed > dt.timedelta(hours=24) or official.get('appVersion') != provenance.get('sessionAppVersion'):
            raise ValueError('Official version evidence does not match the tested profile')
    except (TypeError, ValueError):
        failures.append('Current official-client version is not verified')
    observation = evidence.get('observation', {})
    try:
        start = timestamp(observation.get('startedAtUtc'))
        end = timestamp(observation.get('finishedAtUtc'))
        if end - start < dt.timedelta(hours=72) or end > now or start > end:
            raise ValueError('Observation is shorter than 72 hours or is in the future')
        if observation.get('banObserved') is not False:
            raise ValueError('No-ban observation is not confirmed')
    except (TypeError, ValueError):
        failures.append('72-hour observation has not passed')
    checks = evidence.get('checks', {})
    for name in sorted(CHECKS):
        if checks.get(name) is not True:
            failures.append('Unverified: ' + name)
    if not re.fullmatch(r'v[0-9][A-Za-z0-9.+-]{0,100}', provenance.get('tag', '')):
        failures.append('Invalid release tag')
    return failures


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--provenance', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, default=Path('docs/validation/acceptance.json'))
    args = parser.parse_args()
    evidence = json.loads(args.evidence.read_text())
    provenance = json.loads(args.provenance.read_text())
    digest = hashlib.file_digest(args.apk.open('rb'), 'sha256').hexdigest()
    failures = validate(evidence, provenance, digest)
    if failures:
        raise SystemExit('Release blocked:\n' + '\n'.join('- ' + failure for failure in failures))
    print('Acceptance matches this APK, its source and session profile; observation >=72 hours')


if __name__ == '__main__':
    main()
