import unittest
from compare_source_fingerprint import classify, digest, match_form, validated_path


class FingerprintTest(unittest.TestCase):
    def test_checkout_newlines_are_explicit_and_binary_is_not_normalized(self):
        expected = digest(b'a\r\nb\r\n')
        self.assertEqual('crlf-checkout', match_form(b'a\nb\n', expected, '.java'))
        self.assertIsNone(match_form(b'a\nb\n', expected, '.onnx'))
        self.assertIsNone(match_form(b'changed\n', expected, '.java'))

    def test_scope_and_injection_boundaries(self):
        self.assertEqual('app/src/main/a.java', str(validated_path('app/src/main/a.java')))
        for path in ('../secret', 'app/src/main/../secret', '/app/src/main/a',
                     'app/src/main/a\nHEAD:secret', 'app/src/main//a', 'app\\src\\main\\a',
                     'app/src/main/file.java:stream', 'app/src/main/a\tb'):
            with self.assertRaises(ValueError):
                validated_path(path)

    def test_classification_does_not_equate_worktree_to_commit(self):
        self.assertEqual('only-worktree-matches-frozen', classify('exact', None))
        self.assertEqual('only-head-matches-frozen', classify(None, 'crlf-checkout'))
        self.assertEqual('neither-matches-frozen', classify(None, None))
        self.assertEqual('both-match-frozen', classify('exact', 'crlf-checkout'))


if __name__ == '__main__':
    unittest.main()
