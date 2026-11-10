"""Model/dataset contract. Pure stdlib; importing never starts training or networking."""
import hashlib
import json
import math
from pathlib import Path

NAMES = [
    'FEMALE_GENITALIA_COVERED', 'FACE_FEMALE', 'BUTTOCKS_EXPOSED',
    'FEMALE_BREAST_EXPOSED', 'FEMALE_GENITALIA_EXPOSED', 'MALE_BREAST_EXPOSED',
    'ANUS_EXPOSED', 'FEET_EXPOSED', 'BELLY_COVERED', 'FEET_COVERED',
    'ARMPITS_COVERED', 'ARMPITS_EXPOSED', 'FACE_MALE', 'BELLY_EXPOSED',
    'MALE_GENITALIA_EXPOSED', 'ANUS_COVERED', 'FEMALE_BREAST_COVERED',
    'BUTTOCKS_COVERED', 'PERSON',
]
BASE_URL = 'https://github.com/notAI-tech/NudeNet/releases/download/v3.4-weights/320n.pt'
BASE_SHA256 = '1d25e219d536dcd6994651020d3c7cba642d13990e6eef934ed7a8ba650fb582'


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def verified_file(path, expected):
    path = Path(path).resolve(strict=True)
    if len(expected) != 64 or sha256(path) != expected.lower():
        raise ValueError(f'Checksum mismatch: {path.name}')
    return path


def contained(root, relative):
    relative = Path(relative)
    if relative.is_absolute():
        raise ValueError('Manifest paths must be relative')
    result = (root / relative).resolve(strict=True)
    if not result.is_relative_to(root.resolve()):
        raise ValueError('Dataset path leaves its root')
    return result


def parse_labels(text):
    rows = []
    for line in text.splitlines():
        if not line.strip():
            continue
        fields = line.split()
        if len(fields) != 5:
            raise ValueError('Expected class x_center y_center width height')
        if fields[0] != '0':
            raise ValueError('Training labels contain only class0: PERSON (export remaps it to18)')
        x, y, w, h = map(float, fields[1:])
        if not all(math.isfinite(v) for v in (x, y, w, h)) or not (0 < w <= 1 and 0 < h <= 1):
            raise ValueError('Non-finite or invalid box extent')
        if min(x-w/2, y-h/2) < -1e-6 or max(x+w/2, y+h/2) > 1+1e-6:
            raise ValueError('Box leaves image; clip visible person extent before annotation')
        rows.append((int(fields[0]), x, y, w, h))
    return rows


def validate_dataset(root, require_all=True, verify_images=False):
    root = Path(root).resolve(strict=True)
    manifest = root/'samples.jsonl'
    rows = [json.loads(line) for line in manifest.read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    if not rows:
        raise ValueError('Empty dataset')
    groups, hashes, images, label_paths = {}, {}, set(), set()
    counts = {split: [0] for split in ('train', 'val', 'test')}
    negatives = {split: 0 for split in counts}
    label_hashes = {}
    sizes = {split: 0 for split in counts}
    for row in rows:
        split = row['split']
        if split not in counts or not isinstance(row.get('group'), str) or not row['group'].strip():
            raise ValueError('Every image needs train/val/test and a nonempty source group')
        if not row.get('source') or not row.get('license') or row.get('training_permitted') is not True:
            raise ValueError('Missing source/license/training permission declaration')
        if row.get('content_reviewed') is not True:
            raise ValueError('Human review for lawful, permitted training content is required')
        image = contained(root, row['image'])
        expected_prefix = Path('images')/split
        relative = Path(row['image'])
        if not relative.is_relative_to(expected_prefix) or image.suffix.lower() not in {'.jpg','.jpeg','.png'}:
            raise ValueError('Use images/<split>/*.jpg or *.png')
        label_relative = Path('labels')/relative.relative_to('images').with_suffix('.txt')
        label = contained(root, label_relative)
        if image in images or label in label_paths:
            raise ValueError('Duplicate image or ambiguous label filename')
        images.add(image)
        label_paths.add(label)
        digest = sha256(image)
        if row.get('sha256', '').lower() != digest:
            raise ValueError(f'Image digest mismatch: {relative}')
        for mapping, key in ((groups, row['group']), (hashes, digest)):
            if key in mapping and mapping[key] != split:
                raise ValueError('Source-group or exact-image leakage between splits')
            mapping[key] = split
        if verify_images:
            from PIL import Image
            with Image.open(image) as decoded:
                decoded.verify()
            with Image.open(image) as decoded:
                if min(decoded.size) < 16:
                    raise ValueError('Image is too small')
        labels = parse_labels(label.read_text(encoding='utf-8-sig'))
        if not labels: negatives[split] += 1
        label_hashes[str(label_relative)] = sha256(label)
        for label_row in labels:
            counts[split][label_row[0]] += 1
        sizes[split] += 1
    discovered_images = {p.resolve() for p in (root/'images').rglob('*') if p.is_file() and p.suffix.lower() in {'.jpg','.jpeg','.png'}}
    discovered_labels = {p.resolve() for p in (root/'labels').rglob('*.txt')}
    if discovered_images != images or discovered_labels != label_paths:
        raise ValueError('Unmanifested/orphan images or labels found')
    if any(v == 0 for v in sizes.values()):
        raise ValueError('All three splits are required')
    if require_all and any(0 in values for values in counts.values()):
        raise ValueError('Every split must contain PERSON annotations')
    return {'images':sizes, 'classCounts':counts, 'negativeImages':negatives,
            'manifestSha256':sha256(manifest), 'labelsSha256':hashlib.sha256(
                json.dumps(label_hashes,sort_keys=True).encode()).hexdigest()}


def write_yaml(root, destination):
    # JSON-quoted path/names are also valid YAML scalars.
    lines = ['path: '+json.dumps(str(Path(root).resolve())), 'train: images/train',
             'val: images/val', 'test: images/test', 'names:']
    lines += ['  0: "PERSON"']
    Path(destination).write_text('\n'.join(lines)+'\n', encoding='utf-8')
