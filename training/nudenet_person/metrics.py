"""Transparent single-class AP50 and fixed-threshold metrics, not a full COCO evaluator."""
import numpy as np
import torch
from torchvision.ops import box_iou, nms

def xyxy(boxes):
    center, extent = boxes[:, :2], boxes[:, 2:4]/2
    return torch.cat((center-extent, center+extent), dim=1)

@torch.no_grad()
def evaluate(model, loader, device, threshold=.35):
    model.eval()
    detections, truths, negatives, negative_false = [], 0, 0, 0
    for batch in loader:
        image = batch['img'].to(device)
        output = model.person_forward(image)
        output = output[0] if isinstance(output, tuple) else output
        for index, prediction in enumerate(output):
            candidates = prediction.T
            candidates = candidates[candidates[:, 4] >= .001]
            candidates = candidates[candidates[:, 4].argsort(descending=True)[:4096]]
            boxes = xyxy(candidates[:, :4])
            width, height = batch['content'][index]
            boxes[:, [0, 2]] = boxes[:, [0, 2]].clamp(0, width)
            boxes[:, [1, 3]] = boxes[:, [1, 3]].clamp(0, height)
            valid = (boxes[:, 2]>boxes[:, 0]) & (boxes[:, 3]>boxes[:, 1])
            boxes, scores = boxes[valid], candidates[valid, 4]
            keep = nms(boxes, scores, .5)[:128]
            boxes, scores = boxes[keep], scores[keep]
            expected = xyxy(batch['bboxes'][batch['batch_idx']==index].to(device)*image.shape[-1])
            truths += len(expected)
            if not len(expected):
                negatives += 1
                negative_false += int(bool((scores >= threshold).any()))
            overlap = box_iou(boxes, expected)
            used = set()
            for row, score in enumerate(scores):
                correct = False
                for target in overlap[row].argsort(descending=True).tolist():
                    if overlap[row, target] < .5: break
                    if target not in used:
                        used.add(target); correct = True; break
                detections.append((float(score), correct))
    if not truths: raise ValueError('No held-out PERSON truth boxes')
    detections.sort(key=lambda row: -row[0])
    true = np.cumsum([int(row[1]) for row in detections])
    false = np.cumsum([int(not row[1]) for row in detections])
    recall = true/truths
    precision = true/np.maximum(1, true+false)
    ap = np.mean([float(np.max(precision[recall>=level])) if np.any(recall>=level) else 0
                  for level in np.linspace(0, 1, 101)])
    selected = [row for row in detections if row[0]>=threshold]
    tp = sum(row[1] for row in selected)
    return {'ap50':float(ap), 'recall':tp/truths, 'precision':tp/max(1,len(selected)),
            'threshold':threshold, 'groundTruthBoxes':truths, 'negativeImages':negatives,
            'negativeFalsePositiveImages':negative_false,
            'negativeFalsePositiveRate':negative_false/negatives if negatives else None,
            'limits':'Single-class IoU0.5,101-point interpolated AP,NMS0.5,max128detections; not COCO mAP50-95.'}
