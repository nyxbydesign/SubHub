"""Train ONLY a person head on frozen NudeNet features; labels are class0 PERSON only."""
import argparse
import json
import random
from pathlib import Path
from contract import sha256, validate_dataset

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,required=True)
    parser.add_argument('--dataset',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--resume',type=Path)
    parser.add_argument('--epochs',type=int,default=60)
    parser.add_argument('--batch',type=int,default=8)
    parser.add_argument('--imgsz',type=int,default=320)
    parser.add_argument('--lr',type=float,default=.0003)
    parser.add_argument('--device',default='cuda:0')
    args=parser.parse_args()
    if args.output.exists(): raise ValueError('Choose a NEW run directory, also when resuming')
    if args.epochs<1 or args.batch<1 or args.lr<=0: raise ValueError('Invalid training settings')
    audit=validate_dataset(args.dataset,verify_images=True)
    import torch
    import ultralytics
    from torch.utils.data import DataLoader
    from data import PersonDataset, collate
    from joint import JointDetector
    from metrics import evaluate
    if ultralytics.__version__!='8.3.128': raise ValueError('Use pinned Ultralytics8.3.128')
    device=torch.device(args.device)
    if device.type=='cuda' and not torch.cuda.is_available(): raise ValueError('CUDA unavailable; fix environment first')
    torch.manual_seed(42); random.seed(42)
    model=JointDetector(args.base).to(device)
    frozen=model.frozen_digest()
    probe=torch.rand(1,3,args.imgsz,args.imgsz,device=device)
    model.assert_old_output_parity(probe)
    optimizer=torch.optim.AdamW([p for p in model.person.parameters() if p.requires_grad],lr=args.lr,weight_decay=.0001)
    scheduler=torch.optim.lr_scheduler.CosineAnnealingLR(optimizer,T_max=args.epochs,eta_min=args.lr*.1)
    start,best=0,-1.0
    if args.resume:
        state=model.load_person(args.resume)
        if state['datasetAudit']!=audit or state['imgsz']!=args.imgsz or state['totalEpochs']!=args.epochs:
            raise ValueError('Resume requires identical data, input size, and total epoch count')
        optimizer.load_state_dict(state['optimizer']); scheduler.load_state_dict(state['scheduler'])
        start,best=state['epoch']+1,state['bestAP50']
    if start>=args.epochs: raise ValueError('This run already reached its requested epoch count')
    train=DataLoader(PersonDataset(args.dataset,'train',args.imgsz,True),batch_size=args.batch,
                     shuffle=True,num_workers=0,collate_fn=collate)
    validation=DataLoader(PersonDataset(args.dataset,'val',args.imgsz),batch_size=args.batch,
                          shuffle=False,num_workers=0,collate_fn=collate)
    criterion=model.criterion()
    scaler=torch.amp.GradScaler('cuda',enabled=device.type=='cuda')
    args.output.mkdir(parents=True)
    (args.output/'dataset-audit.json').write_text(json.dumps(audit,indent=2)+'\n',encoding='utf-8')
    for epoch in range(start,args.epochs):
        model.train(); total=0.0
        for batch in train:
            batch={k:v.to(device) if isinstance(v,torch.Tensor) else v for k,v in batch.items()}
            optimizer.zero_grad(set_to_none=True)
            with torch.amp.autocast(device_type=device.type,enabled=device.type=='cuda'):
                predictions=model.person_forward(batch['img'])
                loss,_=criterion(predictions,batch)
                loss=loss.sum()
            if not torch.isfinite(loss): raise ValueError('Non-finite loss; no checkpoint is approved')
            scaler.scale(loss).backward()
            scaler.unscale_(optimizer)
            torch.nn.utils.clip_grad_norm_(model.person.parameters(),10)
            scaler.step(optimizer); scaler.update()
            total+=float(loss.detach())
        scheduler.step()
        if model.frozen_digest()!=frozen: raise RuntimeError('Original NudeNet state changed; stop')
        model.assert_old_output_parity(probe)
        metrics=evaluate(model,validation,device)
        improved=metrics['ap50']>best
        best=max(best,metrics['ap50'])
        state=dict(epoch=epoch,bestAP50=best,optimizer=optimizer.state_dict(),scheduler=scheduler.state_dict(),
                   datasetAudit=audit,imgsz=args.imgsz,totalEpochs=args.epochs,frozenDigest=frozen)
        model.save_person(args.output/'last.pt',**state)
        if improved: model.save_person(args.output/'best.pt',**state)
        row={'epoch':epoch+1,'loss':total/max(1,len(train)),'validation':metrics,'frozenStateUnchanged':True}
        with (args.output/'metrics.jsonl').open('a',encoding='utf-8') as stream: stream.write(json.dumps(row)+'\n')
        print(json.dumps(row),flush=True)
    best_file=args.output/'best.pt'
    print(json.dumps({'best':str(best_file) if best_file.exists() else 'Use best.pt from previous resumed run',
                      'lastSha256':sha256(args.output/'last.pt'),'deploymentApproved':False}))

if __name__=='__main__': main()
