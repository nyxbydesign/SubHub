"""Synthetic gradient/parity test. No real images, meaningful training, or deployment."""
import argparse
import json
import tempfile
from pathlib import Path

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,required=True)
    parser.add_argument('--export',action='store_true',help='Also verify temporary ONNX export at five shapes')
    args=parser.parse_args()
    import torch
    from joint import JointDetector
    torch.set_num_threads(2)
    torch.manual_seed(7)
    model=JointDetector(args.base)
    before=model.frozen_digest()
    images=torch.rand(2,3,320,320)
    model.assert_old_output_parity(images[:1])
    head_before={k:v.detach().clone() for k,v in model.person.state_dict().items()}
    optimizer=torch.optim.AdamW([p for p in model.person.parameters() if p.requires_grad],lr=.001)
    criterion=model.criterion()
    batch={'img':images,'cls':torch.zeros(2,1),'batch_idx':torch.tensor([0,1]),
           'bboxes':torch.tensor([[.4,.5,.3,.7],[.6,.5,.3,.6]])}
    model.train()
    assert not model.base.training
    loss,_=criterion(model.person_forward(images),batch)
    loss.sum().backward()
    assert all(p.grad is None for p in model.base.parameters())
    optimizer.step()
    assert model.frozen_digest()==before
    assert any(not torch.equal(head_before[k],v) for k,v in model.person.state_dict().items())
    for h,w in ((320,320),(320,160),(160,320)):
        model.assert_old_output_parity(torch.rand(1,3,h,w))
    with tempfile.TemporaryDirectory() as directory:
        checkpoint=Path(directory)/'head.pt'
        model.save_person(checkpoint,frozenDigest=before)
        restored=JointDetector(args.base)
        restored.load_person(checkpoint)
        assert restored.frozen_digest()==before
        restored.assert_old_output_parity(images[:1])
        torch.testing.assert_close(restored(images[:1]),model(images[:1]),rtol=0,atol=0)
        if args.export:
            import subprocess
            import sys
            from contract import sha256
            subprocess.run([sys.executable,str(Path(__file__).with_name('export.py')),
                            '--base',str(args.base),'--head',str(checkpoint),
                            '--head-sha256',sha256(checkpoint),'--output',str(Path(directory)/'export')],check=True)
    print(json.dumps({'syntheticStepPassed':True,'loss':float(loss.sum().detach()),
                      'frozenStateUnchanged':True,'oldOutputParityShapes':3,'checkpointRoundTrip':True,
                      'accuracyClaim':False}))

if __name__=='__main__': main()
