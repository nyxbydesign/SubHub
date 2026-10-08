"""One frozen NudeNet backbone/neck, its untouched18-class head, and one trainable PERSON head."""
import copy
import hashlib
from types import SimpleNamespace
import torch
from torch import nn
from ultralytics import YOLO
from ultralytics.utils.loss import v8DetectionLoss
from contract import BASE_SHA256, NAMES, verified_file

EXPORT_CONTRACT = 'nudenet18+PERSON:frozen-head-v1'


class JointDetector(nn.Module):
    def __init__(self, base_path):
        super().__init__()
        verified_file(base_path, BASE_SHA256)
        self.base = YOLO(str(base_path), task='detect').model.float().eval()
        if [self.base.names[i] for i in range(18)] != NAMES[:18] or len(self.base.names) != 18:
            raise ValueError('Base checkpoint class order mismatch')
        if self.base.model[-1].end2end:
            raise ValueError('Only the pinned raw YOLOv8 detection head is supported')
        for parameter in self.base.parameters(): parameter.requires_grad_(False)
        self.person = copy.deepcopy(self.base.model[-1])
        self.person.nc = 1
        self.person.no = 4 * self.person.reg_max + 1
        for branch in self.person.cv3:
            old = branch[-1]
            replacement = nn.Conv2d(old.in_channels, 1, old.kernel_size, old.stride, old.padding)
            with torch.no_grad():
                replacement.weight.copy_(old.weight.mean(dim=0, keepdim=True))
                replacement.bias.fill_(-4.5)
            branch[-1] = replacement
        for name, parameter in self.person.named_parameters():
            parameter.requires_grad_(not name.startswith('dfl.'))
        self.args = SimpleNamespace(box=7.5, cls=0.5, dfl=1.5)
        self.train(False)

    @property
    def model(self):
        # v8DetectionLoss inspects model[-1]; the loss sees ONLY the one-class head.
        return [self.person]

    def train(self, mode=True):
        super().train(mode)
        self.base.eval()  # Frozen parameters alone do not freeze BatchNorm running statistics.
        self.person.train(mode)
        return self

    def _apply(self, function):
        super()._apply(function)
        # Detect keeps these tensors outside registered buffers; mirror Ultralytics BaseModel.
        for name in ('stride', 'anchors', 'strides'):
            setattr(self.person, name, function(getattr(self.person, name)))
        return self

    def features(self, image):
        saved = []
        value = image
        for layer in self.base.model[:-1]:
            if layer.f != -1:
                value = saved[layer.f] if isinstance(layer.f, int) else [
                    value if index == -1 else saved[index] for index in layer.f]
            value = layer(value)
            saved.append(value if layer.i in self.base.save else None)
        return [value if index == -1 else saved[index] for index in self.base.model[-1].f]

    def person_forward(self, image):
        with torch.no_grad(): features = self.features(image)
        return self.person(list(features))

    def forward(self, image):
        features = self.features(image)
        old = self.base.model[-1](list(features))
        person = self.person(list(features))
        old = old[0] if isinstance(old, tuple) else old
        person = person[0] if isinstance(person, tuple) else person
        # Separate candidate sets preserve old class competition/NMS exactly. PERSON never
        # competes with parts on a shared regressed box; its own head learns full-body extent.
        old_padded = torch.cat((old, torch.zeros_like(old[:, :1])), dim=1)
        person_padded = torch.cat((person[:, :4], person[:, 4:5].expand(-1, 18, -1)*0,
                                   person[:, 4:5]), dim=1)
        return torch.cat((old_padded, person_padded), dim=2)

    def frozen_digest(self):
        digest = hashlib.sha256()
        for name, value in self.base.state_dict().items():
            digest.update(name.encode())
            digest.update(value.detach().cpu().contiguous().numpy().tobytes())
        return digest.hexdigest()

    def load_person(self, path):
        state = torch.load(path, map_location='cpu', weights_only=True)
        if state.get('baseSha256') != BASE_SHA256 or state.get('contract') != EXPORT_CONTRACT:
            raise ValueError('Head checkpoint belongs to a different base/contract')
        self.person.load_state_dict(state['person'], strict=True)
        return state

    def save_person(self, path, **state):
        torch.save(dict(baseSha256=BASE_SHA256, contract=EXPORT_CONTRACT,
                        person=self.person.state_dict(), **state), path)

    def criterion(self):
        return v8DetectionLoss(self)

    @torch.no_grad()
    def assert_old_output_parity(self, image):
        self.eval()
        reference = self.base(image)[0]
        combined = self(image)
        torch.testing.assert_close(combined[:, :22, :reference.shape[2]], reference, rtol=0, atol=0)
        assert torch.count_nonzero(combined[:, 4:22, reference.shape[2]:]) == 0
        assert torch.count_nonzero(combined[:, 22:, :reference.shape[2]]) == 0
        return reference.shape[2]
