"""Binary masks in the original CPU image coordinate system."""
import base64
import binascii
import io

import numpy as np
from PIL import Image


def encode_mask(mask, source):
    mask = np.asarray(mask)
    if mask.ndim != 2 or mask.size > 20_000_000 or not mask.any():
        raise ValueError("비어 있지 않은 2차원 음식 마스크가 필요합니다.")
    output = io.BytesIO()
    Image.fromarray(mask.astype(bool).astype(np.uint8) * 255).save(output, format="PNG")
    return {"encoding": "png_base64", "width": mask.shape[1], "height": mask.shape[0],
            "data": base64.b64encode(output.getvalue()).decode("ascii"),
            "source": source, "reviewed": False}


def decode_mask(spec, dimensions):
    if spec.get("encoding") != "png_base64":
        raise ValueError("지원하지 않는 마스크 형식입니다.")
    expected = tuple(dimensions)
    if (len(expected) != 2 or min(expected) < 1 or np.prod(expected) > 20_000_000
            or (spec.get("width"), spec.get("height")) != expected):
        raise ValueError("음식 마스크와 원본 사진 크기가 다릅니다.")
    data = spec.get("data", "")
    if not isinstance(data, str) or len(data) > 28_000_000:
        raise ValueError("마스크 파일 크기를 확인하세요.")
    try:
        raw = base64.b64decode(data, validate=True)
        with Image.open(io.BytesIO(raw)) as image:
            if image.format != "PNG" or image.mode != "L" or image.size != expected:
                raise ValueError("원본 크기의 흑백 PNG 마스크가 필요합니다.")
            pixels = np.asarray(image)
            if not np.isin(pixels, [0, 255]).all():
                raise ValueError("마스크 픽셀은 0 또는 255여야 합니다.")
            return pixels == 255
    except (binascii.Error, OSError) as error:
        raise ValueError("음식 마스크 파일을 읽을 수 없습니다.") from error


def sample_mask(mask, uv):
    """Nearest pixel lookup; no interpolation across food/background boundaries."""
    height, width = mask.shape
    valid = np.isfinite(uv).all(axis=-1) & (uv >= 0).all(axis=-1) & (uv < 1).all(axis=-1)
    safe = np.where(valid[..., None], uv, 0)
    x = np.clip((safe[..., 0] * width).astype(int), 0, width - 1)
    y = np.clip((safe[..., 1] * height).astype(int), 0, height - 1)
    return mask[y, x] & valid
