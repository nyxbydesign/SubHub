# NudeNet + PERSON, trained with a PERSON-only dataset

You do **not** need NudeNet's original dataset or annotations for its existing classes.
Training labels contain only `0: PERSON`. This kit freezes NudeNet's backbone, neck and
18-class head (including normalization statistics), and trains a separate person-box head
on the same frozen features. One exported model shares the expensive feature extraction.
It is not two independent networks and not ordinary 19-class fine-tuning.

The new head learns its own box regression: appending a person score to existing part-sized
boxes would not be enough. All old outputs are checked for preservation. Frozen features may
limit person accuracy; the extra head also has a compute cost. Neither speed nor accuracy is
guaranteed until measured. No trained weights or images are included.

Nothing auto-downloads datasets, uploads images, rents GPUs, modifies the phone or deploys a
model. You run training yourself. A synthetic smoke test is not a meaningful training run.

## 1. Environment

Run PowerShell from this directory, `training/nudenet_person`. Use Python 3.12 and a recent
NVIDIA driver. This machine's RTX 3060 has 12 GB VRAM, verified with `nvidia-smi`. Start with
320-pixel input and batch 8; actual memory use depends on the setup. Allow disk space for the
Python environment, your dataset, checkpoints and reports.

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install torch==2.6.0 torchvision==0.21.0 --index-url https://download.pytorch.org/whl/cu124
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
$env:YOLO_CONFIG_DIR = Join-Path $PWD 'artifacts/ultralytics-config'
.\.venv\Scripts\yolo.exe settings sync=False
.\.venv\Scripts\python.exe -c "import torch; print(torch.__version__); print(torch.cuda.is_available()); print(torch.cuda.get_device_name(0))"
.\.venv\Scripts\python.exe test_contract.py
.\.venv\Scripts\python.exe test_metrics.py
```

If CUDA is unavailable, fix the driver/wheels before a long run. `--device cpu` works for
checks, but is not recommended for full training. The [official PyTorch version instructions](https://pytorch.org/get-started/previous-versions/)
are the reference. Keep this environment separate from other projects; activation is unnecessary.

## 2. Official base checkpoint

```powershell
New-Item -ItemType Directory -Path artifacts -Force | Out-Null
gh release download v3.4-weights --repo notAI-tech/NudeNet --pattern 320n.pt --dir artifacts
Get-FileHash artifacts/320n.pt -Algorithm SHA256
```

Or download `320n.pt` from the [official release](https://github.com/notAI-tech/NudeNet/releases/tag/v3.4-weights).
Expected SHA-256:
`1d25e219d536dcd6994651020d3c7cba642d13990e6eef934ed7a8ba650fb582`.
The code checks this before loading. Stop on mismatch; do not replace the expected hash merely
to bypass the check. PyTorch model pickles can execute code, so load only this verified base.
New person checkpoints contain tensor/state dictionaries and use `weights_only=True` on load.
Only resume your own trusted runs.

```powershell
.\.venv\Scripts\python.exe smoke_test.py --base artifacts/320n.pt --export
```

This uses synthetic tensors for one gradient step, checks that only the new head changes,
checks exact preservation of old PyTorch outputs on three shapes, and checks checkpoint
round-trip and temporary ONNX export at five shapes. It uses no real images and produces no
useful trained person detector. Temporary synthetic weights/exports are discarded afterward.
Set `YOLO_CONFIG_DIR` again in each new PowerShell session; the command above keeps optional
Ultralytics settings local and disables its sync setting.

## 3. Data: only PERSON

Normal clothed-person datasets are sufficient to start; nudity labels are not needed. Use
images you are permitted to train on. Model licensing does not grant rights to arbitrary
images. Keep personal recordings local unless separately authorized. Never use sexual imagery
involving minors or uncertain ages. A normal nonsexual person dataset needs no NSFW material.

```text
data/person/
  samples.jsonl
  images/train/example.jpg
  images/val/example.jpg
  images/test/example.jpg
  labels/train/example.txt
  labels/val/example.txt
  labels/test/example.txt
```

Each annotation line is:

```text
0 x_center y_center width height
```

Coordinates are normalized to 0..1. **Class 0 means PERSON in your dataset**; export maps it
to output class 18. Every image needs a label file. Background/negative images use empty files.
Use unique stems so a JPG and PNG cannot accidentally share one label file.

Annotation rules:

- Annotate every visible person, not just the main subject, using one visible-body box each.
- Clip cropped portraits to the actual image/card. Do not invent unseen legs in another tile.
- Different people and separate image cards get separate boxes, even if a person appears twice.
- Include small thumbnails, grids, portraits, full bodies, seated poses, occlusions, varied
  clothing/appearance, screenshots and hard negatives. Generic single-person photos alone may
  not transfer to dense browser layouts.
- Human-review proposed annotations. Auto-labelled person boxes are suggestions, not independent truth.

Split by **source group**, approximately 80/10/10: all frames from one video/session, near-
duplicates, and original/crop families stay in one split. Do not randomly split adjacent frames.
Grouping identities/source collections can further reduce leakage. Hash checks catch exact
copies, not every near-duplicate.

`samples.jsonl` contains one record per image:

```json
{"image":"images/train/example.jpg","split":"train","group":"session-001","sha256":"REPLACE_WITH_IMAGE_SHA256","source":"documented source","license":"documented license or permission","training_permitted":true,"content_reviewed":true}
```

Use `Get-FileHash -Algorithm SHA256 <image>` for hashes. Permission/review fields record your
review, not automatic proof. Keep private source details out of Git.

```powershell
.\.venv\Scripts\python.exe prepare.py data/person --output artifacts/person.yaml
```

The validator checks image decoding, finite contained boxes, one-class labels, complete
manifests, hashes, source-group leakage and positive examples in each split. These minimum
counts are structural, not statistically sufficient. Include many independent examples of
your target scenes and enough held-out negatives to estimate false positives. With
`--inspect-incomplete`, it reports counts without writing a training definition.

## 4. Train the new head

```powershell
.\.venv\Scripts\python.exe train.py --base artifacts/320n.pt --dataset data/person --output runs/person-v1 --epochs 60 --batch 8 --imgsz 320 --device cuda:0
```

Defaults: AdamW, learning rate 0.0003, cosine decay, horizontal flips/brightness augmentation,
fixed seed, zero-worker loading for Windows, and mixed precision on CUDA. Images use top-left
black letterboxing and RGB/255, matching SubHub's geometry convention. Only person-head
parameters enter the optimizer. No old-class loss is computed, so unlabelled body parts are
not trained as negatives for the existing classifier.

Each epoch verifies the frozen-state digest and old-output parity, evaluates validation AP50,
and writes `last.pt`. An improved validation result also writes `best.pt`. Inspect
`metrics.jsonl` and sample detections. Low training loss does not establish good boxes.

Reduce batch 8→4→2 for CUDA OOM. Keep 320 initially: larger resolutions change the phone's
compute budget. If learning plateaus, improve target-like annotations or the person head;
**do not unfreeze NudeNet while claiming the old outputs are preserved**. The defaults are a
starting point, not an optimized recipe or a promised training duration.

### Resume a stopped run

```powershell
.\.venv\Scripts\python.exe train.py --base artifacts/320n.pt --dataset data/person --output runs/person-v1-resumed --resume runs/person-v1/last.pt --epochs 60 --batch 8 --imgsz 320 --device cuda:0
```

Use a new output directory. Resume checks identical image-manifest/label hashes, image size
and total epoch count, and restores optimizer/scheduler. Random ordering/augmentation is not
guaranteed bit-identical to an uninterrupted run. If the new run does not beat the previous
best, retain the previous `best.pt`. A changed dataset or schedule is a new experiment, not a
resume. Keep commands, environment versions, dataset audits and checkpoints for reproducibility.

## 5. Evaluate the untouched test set

```powershell
$headHash = (Get-FileHash runs/person-v1/best.pt -Algorithm SHA256).Hash.ToLowerInvariant()
.\.venv\Scripts\python.exe evaluate.py --base artifacts/320n.pt --head runs/person-v1/best.pt --head-sha256 $headHash --dataset data/person --output artifacts/person-v1-test.json --device cuda:0
```

Choose checkpoints using validation, not test results. If you repeatedly tune against test
results, make a new independent final holdout.

The report includes single-class AP50 (101-point interpolation), precision/recall at confidence
0.35, and the fraction of negative images with a false positive. NMS is 0.5 with at most 128
detections. This is **not full COCO mAP50–95**. Starting gates: AP50≥0.8, recall≥0.8, negative-
image FP rate≤0.1. Missing negatives cannot pass. These are explicit targets, not claimed
results; don't weaken them just to obtain a pass. Review individual examples and sample counts.

Old categories are protected by unchanged weights/state and raw-output parity, without an
old-class dataset. That guarantee concerns the same input tensors and original checkpoint;
downstream Android processing and converted graphs must be checked separately.

## 6. Export one shared-backbone model

```powershell
.\.venv\Scripts\python.exe export.py --base artifacts/320n.pt --head runs/person-v1/best.pt --head-sha256 $headHash --output artifacts/export-person-v1
```

Output: `nudenet_person.onnx` and a checksum/contract manifest. Export starts with FP32, opset17,
dynamic H/W and batch1. Five square/portrait/landscape shapes are checked in ONNX Runtime
against PyTorch, including the original 18-class slice.

Contract:

- Input: float32 `[1,3,H,W]`, RGB/255, dimensions multiples of32, top-left black letterboxing.
- Output: `[1,23,2N]`: `cx,cy,w,h` in input pixels plus19 probabilities; no objectness or embedded NMS.
- First N candidates: original18 scores and an exactly zero PERSON score.
- Second N: person boxes, exactly zero original18 scores, and a PERSON score.
- PERSON must receive separate NMS and act only as support for selected part detections.
- Metadata: `subhub_person_contract=nudenet18+PERSON:frozen-head-v1` and exact class names.

Original class order remains:

```text
0 FEMALE_GENITALIA_COVERED    1 FACE_FEMALE          2 BUTTOCKS_EXPOSED
3 FEMALE_BREAST_EXPOSED      4 FEMALE_GENITALIA_EXPOSED 5 MALE_BREAST_EXPOSED
6 ANUS_EXPOSED              7 FEET_EXPOSED         8 BELLY_COVERED
9 FEET_COVERED             10 ARMPITS_COVERED     11 ARMPITS_EXPOSED
12 FACE_MALE              13 BELLY_EXPOSED       14 MALE_GENITALIA_EXPOSED
15 ANUS_COVERED           16 FEMALE_BREAST_COVERED 17 BUTTOCKS_COVERED
18 PERSON
```

Do not switch to FP16/INT8 until calibration, parity, person accuracy and Pixel speed have
been checked. The graph shares one backbone but still adds head work. File size and desktop
timing do not predict mobile speed.

## 7. Android adoption — separate code and acceptance gate

The app ships the original18-class model. The second network is removed; model-neutral
`PersonBox`, `WholePersonGeometry` and `PersonCoveragePresentation` retain the body-rendering
logic. The whole-person preference is reserved; the bundled model covers detected areas.
**Do not overwrite the asset and assume arbitrary trained weights are automatically supported.**

Before integrating your exported model:

1. Validate metadata, checksums, class order, raw shape and original-output parity.
2. Extend strict22-feature validation to this specific23-feature contract, rejecting unrelated,
   transposed, objectness-based or end-to-end outputs.
3. Keep old-class decoding/policy unchanged. Decode row22 separately into source-frame boxes,
   undo the same top-left scale, clip to the source viewport and apply person-only NMS.
4. Supply `PersonBox` objects only as geometry for selected original detections. Do not insert
   them into part tracking, category penalties or original-class statistics.
5. Publish matched parts/body geometry from the **same inference and capture** together, keeping
   document, timestamp, coordinate basis and tracker IDs. Do not recreate a late second-model
   callback or provisional body-guess stage. Clear on source invalidation.
6. Preserve covered/exposed controls, settings locks, text and reverse-mode behavior.
7. Test output, geometry and source-scope guards. Build app and instrumentation APKs together,
   run native tests, and sign updates with the existing key. Keep the original model/APK for rollback.

These scripts let you train and export independently. Android model adoption still needs an
explicit integration/validation pass. Nothing auto-deploys or clears app data.

## 8. Pixel acceptance

Compare matched scenes/settings: first censor arrival, capture age, drops, preprocessing,
inference, postprocessing, publication delay, sustained throughput/heat, scrolling, stationary
resizing, text stability and false-positive flashes. Include dense grids, portraits, full bodies,
multiple people, reversals and app switches. Keep scroll-software changes constant for model
comparisons. Faster inference cannot repair incorrect camera coordinates. Require your own
perceived verdict before calling a candidate an improvement.

## Troubleshooting

| Problem | Action |
| --- | --- |
| Base hash differs | Verify official provenance; do not disable the check. |
| Frozen state or old-output parity fails | Stop: preservation is broken; do not export/deploy. |
| Asked for old body-part labels | Wrong workflow: this kit's training labels are only class0 PERSON. |
| Boxes bridge adjacent cards | Add correctly separated grid/portrait examples; inspect annotations. |
| Person accuracy plateaus | Improve data/head; frozen features can limit learning. |
| CUDA missing / out of memory | Check driver/wheels; reduce batch before input resolution. |
| No negative test images | Add permitted backgrounds/hard negatives with empty labels. |
| ONNX shape/parity fails | Keep pinned versions/raw export; don't weaken contract checks. |
| Phone slower despite good desktop results | Benchmark the actual provider, graph and input dimensions. |

References: [NudeNet specification](https://raw.githubusercontent.com/notAI-tech/NudeNet/v3/README.md),
[pinned detection head](https://github.com/ultralytics/ultralytics/blob/v8.3.128/ultralytics/nn/modules/head.py),
[pinned loss](https://github.com/ultralytics/ultralytics/blob/v8.3.128/ultralytics/utils/loss.py).
Review upstream model/code licenses and dataset permissions before distributing outputs.
