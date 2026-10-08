"""Deterministic evaluation fixtures, including clipping and empty negative labels."""
import unittest
import torch
from metrics import evaluate
from data import collate


class FixedModel:
    def __init__(self, rows): self.output = torch.tensor(rows, dtype=torch.float32).transpose(1, 2)
    def eval(self): return self
    def person_forward(self, images): return self.output


class MetricsTest(unittest.TestCase):
    def batch(self):
        return collate([
            (torch.zeros(3, 100, 100), torch.tensor([[0., .5, .5, 1., 1.]]), (100, 100)),
            (torch.zeros(3, 100, 100), torch.empty(0, 5), (100, 100)),
        ])

    def test_clipping_perfect_detection_and_padding_rejection(self):
        model = FixedModel([[[50, 50, 300, 300, .9]], [[150, 50, 20, 20, .9]]])
        result = evaluate(model, [self.batch()], 'cpu')
        self.assertEqual(result['ap50'], 1)
        self.assertEqual(result['recall'], 1)
        self.assertEqual(result['precision'], 1)
        self.assertEqual(result['negativeFalsePositiveRate'], 0)

    def test_false_positive_ranked_before_true(self):
        model = FixedModel([[[50, 50, 100, 100, .8]], [[50, 50, 100, 100, .9]]])
        result = evaluate(model, [self.batch()], 'cpu')
        self.assertEqual(result['ap50'], .5)
        self.assertEqual(result['precision'], .5)
        self.assertEqual(result['negativeFalsePositiveRate'], 1)

    def test_no_predictions(self):
        model = FixedModel([[[50, 50, 100, 100, 0]], [[50, 50, 100, 100, 0]]])
        result = evaluate(model, [self.batch()], 'cpu')
        self.assertEqual(result['ap50'], 0)
        self.assertEqual(result['recall'], 0)

    def test_all_negative_collate(self):
        batch = collate([(torch.zeros(3, 64, 64), torch.empty(0, 5), (64, 64))]*2)
        self.assertEqual(tuple(batch['bboxes'].shape), (0, 4))
        self.assertEqual(tuple(batch['cls'].shape), (0, 1))
        self.assertEqual(tuple(batch['batch_idx'].shape), (0,))


if __name__ == '__main__': unittest.main()
