"""Exercise the pinned Pillow decoder and augmentation path with synthetic images."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image
import torch

from data import PersonDataset


class DataTest(unittest.TestCase):
    def dataset(self, root, pixels, extension, augment=False):
        image = root / 'images/train/sample' / ('image.' + extension)
        image.parent.mkdir(parents=True)
        Image.fromarray(pixels).save(image)
        label = root / 'labels/train/sample/image.txt'
        label.parent.mkdir(parents=True)
        label.write_text('0 .25 .5 .5 1\n', encoding='utf-8')
        (root / 'samples.jsonl').write_text(json.dumps({
            'image': image.relative_to(root).as_posix(), 'split': 'train',
        }) + '\n', encoding='utf-8')
        return PersonDataset(root, 'train', size=64, augment=augment)

    def test_grayscale_png_becomes_rgb_with_top_left_padding(self):
        with tempfile.TemporaryDirectory() as directory:
            dataset = self.dataset(Path(directory), np.full((20, 40), 128, np.uint8), 'png')
            image, labels, content = dataset[0]
        self.assertEqual(content, (64, 32))
        self.assertEqual(tuple(image.shape), (3, 64, 64))
        torch.testing.assert_close(image[:, :32], torch.full((3, 32, 64), 128 / 255))
        self.assertEqual(torch.count_nonzero(image[:, 32:]).item(), 0)
        torch.testing.assert_close(labels, torch.tensor([[0., .25, .25, .5, .5]]))

    def test_jpeg_decodes_and_augmentation_mirrors_image_and_box(self):
        pixels = np.zeros((20, 40, 3), np.uint8)
        pixels[:, :20, 0] = 200
        pixels[:, 20:, 2] = 200
        with tempfile.TemporaryDirectory() as directory:
            dataset = self.dataset(Path(directory), pixels, 'jpg')
            original, _, content = dataset[0]
            dataset.augment = True
            with patch('data.random.random', return_value=0), patch('data.random.uniform', return_value=1):
                mirrored, labels, mirrored_content = dataset[0]
        self.assertEqual(content, mirrored_content)
        self.assertGreater(original[0, 10, 10].item(), .7)
        self.assertGreater(original[2, 10, 54].item(), .7)
        torch.testing.assert_close(mirrored[:, :32], original[:, :32].flip(-1), rtol=0, atol=0)
        self.assertEqual(torch.count_nonzero(mirrored[:, 32:]).item(), 0)
        torch.testing.assert_close(labels, torch.tensor([[0., .75, .25, .5, .5]]))


if __name__ == '__main__':
    unittest.main()
