"""Evaluate the new PERSON head only; original NudeNet preservation uses state/output parity."""
import argparse
import json
from pathlib import Path
from contract import validate_dataset, verified_file

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,required=True)
    parser.add_argument('--head',type=Path,required=True)
    parser.add_argument('--head-sha256',required=True)
    parser.add_argument('--dataset',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--device',default='cuda:0')
    parser.add_argument('--min-ap50',type=float,default=.8)
    parser.add_argument('--min-recall',type=float,default=.8)
    parser.add_argument('--max-negative-fp-rate',type=float,default=.1)
    args=parser.parse_args()
    if args.output.exists(): raise ValueError('Choose a new report path')
    verified_file(args.head,args.head_sha256)
    audit=validate_dataset(args.dataset,verify_images=True)
    import torch
    from torch.utils.data import DataLoader
    from joint import JointDetector
    from data import PersonDataset,collate
    from metrics import evaluate
    model=JointDetector(args.base).to(args.device)
    original=model.frozen_digest()
    state=model.load_person(args.head)
    if state['datasetAudit']!=audit: raise ValueError('Dataset differs from the trained manifest/labels')
    for h,w in ((320,320),(320,160),(160,320)):
        model.assert_old_output_parity(torch.rand(1,3,h,w,device=args.device))
    if original!=model.frozen_digest() or original!=state['frozenDigest']:
        raise RuntimeError('Frozen NudeNet preservation check failed')
    loader=DataLoader(PersonDataset(args.dataset,'test',state['imgsz']),batch_size=8,
                      num_workers=0,collate_fn=collate)
    report=evaluate(model,loader,args.device)
    report['gatePassed']=(report['ap50']>=args.min_ap50 and report['recall']>=args.min_recall
                           and report['negativeFalsePositiveRate'] is not None
                           and report['negativeFalsePositiveRate']<=args.max_negative_fp_rate)
    report['oldOutputParityPassed']=True
    report['deploymentApproved']=False
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,indent=2))
    if not report['gatePassed']: raise SystemExit(2)

if __name__=='__main__': main()
