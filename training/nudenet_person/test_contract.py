import json
from pathlib import Path
import re
import tempfile
import unittest
from contract import NAMES, contained, parse_labels, sha256, validate_dataset, verified_file, write_yaml

class ContractTest(unittest.TestCase):
    def test_order_matches_android(self):
        root=Path(__file__).resolve().parents[2]
        source=(root/'app/src/main/java/com/subhub/app/detection/NudeNetClassCatalog.java').read_text()
        block=source.split('CLASS_NAMES =',1)[1].split('));',1)[0]
        self.assertEqual(NAMES[:18],re.findall(r'"([A-Z_]+)"',block))
        self.assertEqual('PERSON',NAMES[18])

    def test_label_validation(self):
        self.assertEqual([],parse_labels(''))
        self.assertEqual(0,parse_labels('0 .5 .5 1 1')[0][0])
        for invalid in ('18 .5 .5 1 1','1.0 .5 .5 1 1','0 nan .5 .1 .1',
                        '0 .01 .5 .2 .2','0 .5 .5 0 .2','0 .5 .5 .2 .2 7'):
            with self.subTest(invalid=invalid),self.assertRaises(ValueError): parse_labels(invalid)

    def make_dataset(self,root):
        rows=[]
        for i,split in enumerate(('train','val','test')):
            image=root/'images'/split/'example.jpg'
            image.parent.mkdir(parents=True)
            image.write_bytes(bytes([i])) # Filesystem contract test, not an image-decoder test.
            label=root/'labels'/split/'example.txt'
            label.parent.mkdir(parents=True)
            label.write_text('0 .5 .5 .2 .2\n')
            rows.append(dict(image=image.relative_to(root).as_posix(),split=split,group=str(i),
                             source='synthetic-test',license='test-only',training_permitted=True,
                             content_reviewed=True,sha256=sha256(image)))
        self.write_manifest(root,rows)
        return rows

    def write_manifest(self,root,rows):
        (root/'samples.jsonl').write_text('\n'.join(json.dumps(r) for r in rows),encoding='utf-8')

    def test_valid_dataset_and_yaml(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            self.make_dataset(root)
            report=validate_dataset(root)
            self.assertEqual([1],report['classCounts']['test'])
            write_yaml(root,root/'data.yaml')
            self.assertIn('0: "PERSON"',(root/'data.yaml').read_text())

    def test_group_leakage(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); rows=self.make_dataset(root)
            rows[1]['group']=rows[0]['group']; self.write_manifest(root,rows)
            with self.assertRaises(ValueError): validate_dataset(root)

    def test_digest_and_permission(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); rows=self.make_dataset(root)
            rows[0]['training_permitted']=False; self.write_manifest(root,rows)
            with self.assertRaises(ValueError): validate_dataset(root)
            rows[0]['training_permitted']=True; rows[0]['sha256']='0'*64; self.write_manifest(root,rows)
            with self.assertRaises(ValueError): validate_dataset(root)

    def test_unmanifested_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); self.make_dataset(root)
            (root/'labels/test/orphan.txt').write_text('')
            with self.assertRaises(ValueError): validate_dataset(root)

    def test_missing_class(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); self.make_dataset(root)
            (root/'labels/test/example.txt').write_text('')
            with self.assertRaises(ValueError): validate_dataset(root)
            self.assertEqual([0],validate_dataset(root,require_all=False)['classCounts']['test'])

    def test_path_escape_and_weight_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); child=root/'child'; child.mkdir()
            path=root/'weights'; path.write_bytes(b'fixture')
            with self.assertRaises(ValueError): contained(child,'../weights')
            with self.assertRaises(ValueError): verified_file(path,'0'*64)
            self.assertEqual(path,verified_file(path,sha256(path)))

if __name__=='__main__': unittest.main()
