import copy
import datetime as dt
import unittest
from validate_release import CHECKS, validate


class ReleaseValidationTest(unittest.TestCase):
    def setUp(self):
        self.now = dt.datetime(2026, 11, 14, tzinfo=dt.timezone.utc)
        self.provenance = {'sourceCommit': 'a' * 40, 'sessionProfileSha256': 'b' * 64,
                           'sessionAppVersion': 'test-version', 'tag': 'v0.3.0-test'}
        self.evidence = {
            'schema': 1, 'status': 'passed', 'apkSha256': 'c' * 64,
            'sourceCommit': 'a' * 40, 'sessionProfileSha256': 'b' * 64,
            'officialVersionEvidence': {'confirmed': True, 'appVersion': 'test-version',
                'sourceUrl': 'https://example.test/synthetic-evidence', 'reviewedAtUtc': '2026-11-14T00:00:00Z'},
            'observation': {'startedAtUtc': '2026-11-10T00:00:00Z',
                'finishedAtUtc': '2026-11-13T00:00:00Z', 'banObserved': False},
            'checks': {name: True for name in CHECKS},
        }

    def check(self, evidence=None, provenance=None, digest=None):
        return validate(evidence or self.evidence, provenance or self.provenance, digest or 'c' * 64, self.now)

    def test_complete_synthetic_evidence(self):
        self.assertEqual([], self.check())

    def test_short_or_future_observation(self):
        for end in ['2026-11-12T23:59:59Z', '2026-11-15T00:00:00Z', None]:
            record = copy.deepcopy(self.evidence)
            record['observation']['finishedAtUtc'] = end
            self.assertTrue(self.check(record))

    def test_ban_and_unknown_outcome_rejected(self):
        for outcome in [True, None, 'false']:
            record = copy.deepcopy(self.evidence)
            record['observation']['banObserved'] = outcome
            self.assertTrue(self.check(record))

    def test_different_binary_source_or_profile_rejected(self):
        self.assertTrue(self.check(digest='d' * 64))
        for key in ['sourceCommit', 'sessionProfileSha256', 'sessionAppVersion']:
            provenance = dict(self.provenance, **{key: 'different'})
            self.assertTrue(self.check(provenance=provenance))

    def test_each_acceptance_requirement_is_required(self):
        for name in CHECKS:
            record = copy.deepcopy(self.evidence)
            record['checks'][name] = False
            self.assertTrue(self.check(record), name)

    def test_unverified_official_version_rejected(self):
        record = copy.deepcopy(self.evidence)
        record['officialVersionEvidence']['confirmed'] = False
        self.assertTrue(self.check(record))

    def test_outdated_version_review_rejected(self):
        record = copy.deepcopy(self.evidence)
        record['officialVersionEvidence']['reviewedAtUtc'] = '2026-11-10T00:00:00Z'
        self.assertTrue(self.check(record))

    def test_pending_template_cannot_publish(self):
        import json
        from pathlib import Path
        record = json.loads((Path(__file__).resolve().parents[1] / 'docs/validation/acceptance.json').read_text())
        self.assertTrue(self.check(record))
