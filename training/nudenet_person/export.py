"""Export one shared-backbone ONNX graph and check its original18-class output slice."""
import argparse
import json
from pathlib import Path
from contract import NAMES,sha256,verified_file

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,required=True)
    parser.add_argument('--head',type=Path,required=True)
    parser.add_argument('--head-sha256',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.output.exists(): raise ValueError('Output must be new')
    verified_file(args.head,args.head_sha256)
    import numpy as np
    import torch
    import onnx
    import onnxruntime as ort
    from joint import JointDetector,EXPORT_CONTRACT
    model=JointDetector(args.base)
    state=model.load_person(args.head)
    model.eval()
    if model.frozen_digest()!=state['frozenDigest']: raise RuntimeError('Frozen base differs')
    args.output.mkdir(parents=True)
    path=args.output/'nudenet_person.onnx'
    sample=torch.rand(1,3,320,320)
    model.assert_old_output_parity(sample)
    # Rebuild anchors inside the graph, not from a cached eager-mode input shape.
    model.base.model[-1].dynamic = True
    model.person.dynamic = True
    # The feature extractor is called once; each head receives the same feature tensors.
    torch.onnx.export(model,sample,str(path),opset_version=17,dynamo=False,
                      input_names=['images'],output_names=['detections'],
                      dynamic_axes={'images':{2:'height',3:'width'},'detections':{2:'candidates'}})
    graph=onnx.load(str(path))
    onnx.helper.set_model_props(graph,{'subhub_person_contract':EXPORT_CONTRACT,
                                     'subhub_class_names':json.dumps(NAMES,separators=(',',':'))})
    onnx.checker.check_model(graph)
    onnx.save(graph,str(path))
    session=ort.InferenceSession(str(path),providers=['CPUExecutionProvider'])
    errors=[]
    for height,width in ((320,320),(320,160),(160,320),(512,256),(256,512)):
        image=torch.rand(1,3,height,width)
        with torch.no_grad():
            expected=model(image).numpy()
            original=model.base(image)[0].numpy()
        actual=session.run(None,{'images':image.numpy()})[0]
        if actual.ndim!=3 or actual.shape[1]!=23 or not np.isfinite(actual).all():
            raise ValueError('Invalid joint output contract')
        np.testing.assert_allclose(actual,expected,atol=1e-3,rtol=1e-4)
        np.testing.assert_allclose(actual[:,:22,:original.shape[2]],original,atol=1e-3,rtol=1e-4)
        assert np.count_nonzero(actual[:,4:22,original.shape[2]:])==0
        assert np.count_nonzero(actual[:,22:,:original.shape[2]])==0
        errors.append(float(np.max(np.abs(actual-expected))))
    report={'onnxSha256':sha256(path),'baseSha256':sha256(args.base),'headSha256':sha256(args.head),
            'contract':EXPORT_CONTRACT,'names':NAMES,'rawOutput':'[1,23,2N]; old18 candidates first, PERSON candidates second',
            'parityShapes':5,'maxAbsoluteErrors':errors,'deploymentApproved':False}
    (args.output/'manifest.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(report,indent=2))

if __name__=='__main__': main()
