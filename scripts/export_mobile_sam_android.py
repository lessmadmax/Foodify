"""Export MobileSAM (vit_t) to ONNX for the Android app (2026-11-10).

encoder: image_encoder only, input 1x3x1024x1024 already normalized and zero-padded on the device
         (SamGeometry.input); output image embeddings 1x256x64x64.
decoder: mobile_sam.utils.onnx.SamOnnxModel(return_single_mask=True); box prompt = 2 corner points with
         labels 2 and 3; orig_im_size gives masks at the photo size (logits, > 0 = food).
Checked against torch SamPredictor on the M12 records (box prompt): mask IoU 0.85~0.997.

Needs the MobileSAM setup (scripts/setup_mobile_sam.py) and the `onnx` package on the path.
Usage (project root):  python scripts/export_mobile_sam_android.py
Output: data/private/mobile-sam/onnx/ (Git-excluded). Copy both files to
mobile/android/app/src/main/assets/mobilesam/ and update manifest.json (bytes, sha256).
"""
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'data/private/mobile-sam'
sys.path.insert(0, str(ROOT / 'python'))
sys.path.insert(0, str(ROOT / 'source'))

import torch  # noqa: E402
from mobile_sam import sam_model_registry  # noqa: E402
from mobile_sam.utils.onnx import SamOnnxModel  # noqa: E402


class Encoder(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, image):
        return self.model.image_encoder(image)


def main():
    model = sam_model_registry['vit_t']()
    model.load_state_dict(torch.load(ROOT / 'source/weights/mobile_sam.pt', map_location='cpu', weights_only=True))
    model.eval()
    out = ROOT / 'onnx'
    out.mkdir(exist_ok=True)
    with torch.inference_mode():
        torch.onnx.export(Encoder(model), torch.zeros(1, 3, 1024, 1024), str(out / 'mobile_sam_encoder.onnx'),
                          opset_version=17, input_names=['image'], output_names=['embeddings'], dynamo=False)
    inputs = {'image_embeddings': torch.zeros(1, 256, 64, 64),
              'point_coords': torch.randint(0, 1024, (1, 5, 2), dtype=torch.float),
              'point_labels': torch.randint(0, 4, (1, 5), dtype=torch.float),
              'mask_input': torch.zeros(1, 1, 256, 256), 'has_mask_input': torch.tensor([0.0]),
              'orig_im_size': torch.tensor([480, 640], dtype=torch.float)}
    torch.onnx.export(SamOnnxModel(model, return_single_mask=True), tuple(inputs.values()),
                      str(out / 'mobile_sam_decoder.onnx'), opset_version=17, input_names=list(inputs),
                      output_names=['masks', 'iou_predictions', 'low_res_masks'],
                      dynamic_axes={'point_coords': {1: 'n'}, 'point_labels': {1: 'n'}}, dynamo=False)
    for f in sorted(out.iterdir()):
        print(f.name, f.stat().st_size)


if __name__ == '__main__':
    main()
