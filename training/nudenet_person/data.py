"""PERSON-only dataset, using SubHub-style top-left black letterboxing."""
import json
import random
from pathlib import Path
import numpy as np
from PIL import Image, ImageEnhance, ImageOps
import torch
from torch.utils.data import Dataset
from contract import parse_labels

class PersonDataset(Dataset):
    def __init__(self, root, split, size=320, augment=False):
        self.root, self.size, self.augment = Path(root), size, augment
        self.rows = [row for line in (self.root/'samples.jsonl').read_text(encoding='utf-8-sig').splitlines()
                     if line.strip() for row in [json.loads(line)] if row['split'] == split]
        if size < 64 or size % 32: raise ValueError('Input size must be a positive multiple of32, at least64')

    def __len__(self): return len(self.rows)

    def __getitem__(self, index):
        relative = Path(self.rows[index]['image'])
        with Image.open(self.root/relative) as value: image = value.convert('RGB')
        labels = np.array(parse_labels((self.root/'labels'/relative.relative_to('images').with_suffix('.txt')).read_text(encoding='utf-8-sig')),
                          dtype=np.float32).reshape(-1, 5)
        if self.augment and random.random() < .5:
            image = ImageOps.mirror(image)
            labels[:, 1] = 1-labels[:, 1]
        if self.augment:
            image = ImageEnhance.Brightness(image).enhance(random.uniform(.8, 1.2))
        width, height = image.size
        scale = min(self.size/width, self.size/height)
        content = (max(1, round(width*scale)), max(1, round(height*scale)))
        image = image.resize(content, Image.Resampling.BILINEAR)
        canvas = Image.new('RGB', (self.size, self.size), (0, 0, 0))
        canvas.paste(image, (0, 0))
        tensor = torch.from_numpy(np.asarray(canvas).copy()).permute(2, 0, 1).float()/255
        labels[:, [1, 3]] *= content[0]/self.size
        labels[:, [2, 4]] *= content[1]/self.size
        return tensor, torch.from_numpy(labels), content

def collate(items):
    images, labels, content = zip(*items)
    classes = torch.cat([row[:, :1] for row in labels])
    boxes = torch.cat([row[:, 1:] for row in labels])
    indexes = torch.cat([torch.full((len(row),), i, dtype=torch.long) for i, row in enumerate(labels)])
    return {'img':torch.stack(images), 'cls':classes, 'bboxes':boxes,
            'batch_idx':indexes, 'content':content}
