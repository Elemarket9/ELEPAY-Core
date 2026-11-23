#!/usr/bin/env python3
import unittest
from assert_trufflehog_scan import assert_scanned


class TrufflehogScanVerifierTest(unittest.TestCase):
    def test_clean_real_scan(self):
        self.assertEqual(assert_scanned(
            'info finished scanning {"chunks":7,"bytes":4082,"verified_secrets":0}'
        ), (7, 4082))

    def test_zero_byte_scan_fails(self):
        with self.assertRaises(ValueError):
            assert_scanned('info finished scanning {"chunks":0,"bytes":0}')

    def test_unresolved_git_ref_fails_even_if_earlier_work_was_scanned(self):
        with self.assertRaises(ValueError):
            assert_scanned(
                'error encountered errors during scan: unable to resolve commit\n'
                'info finished scanning {"chunks":12,"bytes":890}'
            )

    def test_missing_metrics_fails(self):
        with self.assertRaises(ValueError):
            assert_scanned("Scan completed.")


if __name__ == "__main__":
    unittest.main()
