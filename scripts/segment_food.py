"""PC MobileSAM research inference; inputs and masks use original image coordinates."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1] / "data/private/mobile-sam"
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "source"))

import numpy as np
from PIL import Image, ImageOps
from ar_volume import frame_fingerprint, read_cpu_image
from food_mask import encode_mask


def validate_box(box):
    if (not isinstance(box, (list, tuple)) or len(box) != 4
            or any(type(v) not in (int, float) for v in box)):
        raise ValueError("음식 위치는 숫자 4개로 지정하세요.")
    box = np.asarray(box, dtype=float)
    if (box.shape != (4,) or not np.isfinite(box).all() or (box < 0).any()
            or (box > 1).any() or box[0] >= box[2] or box[1] >= box[3]):
        raise ValueError("음식 위치는 0~1 범위의 x0 y0 x1 y1 좌표로 지정하세요.")
    return box


def verify_model():
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    for name, entry in manifest["files"].items():
        path = (ROOT / "source" / name).resolve()
        if not path.is_relative_to((ROOT / "source").resolve()):
            raise ValueError("모델 경로를 확인하세요.")
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            raise ValueError("모델 파일 검증 실패. setup_mobile_sam.py를 다시 실행하세요.")
    return manifest


def main():
    parser = argparse.ArgumentParser(description="음식 위치를 바탕으로 MobileSAM 마스크 생성")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--frame", type=Path)
    source.add_argument("--image", type=Path, help="일반 사진 단독 분할 시험용")
    parser.add_argument("--annotation", type=Path, help="같은 AR 프레임의 접시·음식 영역 JSON")
    position = parser.add_mutually_exclusive_group(required=True)
    position.add_argument("--box", nargs=4, type=float, metavar=("X0", "Y0", "X1", "Y1"))
    position.add_argument("--gemma-result", type=Path, help="같은 사진의 Gemma --locate 결과")
    parser.add_argument("--food-index", type=int, default=0)
    parser.add_argument("--foreground", nargs=2, type=float, action="append", default=[])
    parser.add_argument("--background", nargs=2, type=float, action="append", default=[])
    parser.add_argument("--name", default="")
    parser.add_argument("--output", type=Path, required=True, help="새 출력 폴더")
    args = parser.parse_args()
    try:
        if args.output.exists():
            raise ValueError("출력 폴더가 이미 있습니다. 새 경로를 지정하세요.")
        if args.frame:
            image = read_cpu_image(args.frame)
            fingerprint = frame_fingerprint(args.frame)
        else:
            with Image.open(args.image) as original:
                if original.width * original.height > 20_000_000:
                    raise ValueError("사진은 2천만 픽셀 이하로 준비하세요.")
                image = ImageOps.exif_transpose(original).convert("RGB")
            fingerprint = "image-only:" + hashlib.sha256(image.tobytes()).hexdigest()
        width, height = image.size
        name = args.name
        if args.gemma_result:
            report = json.loads(args.gemma_result.read_text(encoding="utf-8"))
            if (report.get("task") != "locate" or report.get("status") != "COMPLETED"
                    or report.get("originalImageSize") != [width, height]
                    or report.get("imageFingerprint") != "image-only:" + hashlib.sha256(image.tobytes()).hexdigest()):
                raise ValueError("Gemma 위치 결과와 원본 사진이 다릅니다.")
            runs = report.get("runs", [])
            if not runs or runs[-1].get("schemaValid") is not True:
                raise ValueError("Gemma 위치 응답의 형식 검증이 필요합니다.")
            foods = runs[-1].get("foods", [])
            if not 0 <= args.food_index < len(foods):
                raise ValueError("음식 번호를 확인하세요.")
            selected_food = foods[args.food_index]
            box = validate_box(selected_food["bbox"])
            name = name or selected_food["name"]
        else:
            box = validate_box(args.box)
        annotation = json.loads(args.annotation.read_text(encoding="utf-8-sig")) if args.annotation else {}
        if annotation and (annotation.get("frameFingerprint") != fingerprint
                           or annotation.get("coordinateSpace") != "cpu_image_normalized"):
            raise ValueError("영역 지정 파일과 사진의 좌표·프레임이 다릅니다.")
        points = np.asarray(args.foreground + args.background, dtype=float).reshape(-1, 2)
        if not np.isfinite(points).all() or (points < 0).any() or (points >= 1).any():
            raise ValueError("보정 점은 0 이상 1 미만의 좌표를 사용하세요.")
        manifest = verify_model()
        import torch
        from mobile_sam import SamPredictor, sam_model_registry
        torch.set_num_threads(4)
        started = time.perf_counter()
        model = sam_model_registry["vit_t"]()
        state = torch.load(ROOT / "source/weights/mobile_sam.pt", map_location="cpu", weights_only=True)
        model.load_state_dict(state)
        model.eval()
        predictor = SamPredictor(model)
        initialized = time.perf_counter()
        with torch.inference_mode():
            predictor.set_image(np.asarray(image))
            masks, scores, _ = predictor.predict(
                point_coords=points * [width, height] if len(points) else None,
                point_labels=np.array([1]*len(args.foreground) + [0]*len(args.background)) if len(points) else None,
                box=box * [width, height, width, height], multimask_output=True,
            )
        selected = int(np.argmax(scores))
        mask = masks[selected]
        ended = time.perf_counter()
        spec = encode_mask(mask, "MobileSAM/vit_t")
        annotation.update(schemaVersion=1, frameFingerprint=fingerprint,
                          coordinateSpace="cpu_image_normalized", foodMask=spec,
                          foodName=name or annotation.get("foodName", ""),
                          basePolygons=annotation.get("basePolygons", []),
                          segmentation={
                              "revision": manifest["revision"], "box": box.tolist(),
                              "positionSource": "gemma" if args.gemma_result else "manual",
                              "foregroundPoints": args.foreground, "backgroundPoints": args.background,
                              "predictedIou": float(scores[selected]), "candidateScores": scores.tolist(),
                              "initializationSeconds": initialized-started, "inferenceSeconds": ended-initialized,
                              "foregroundPixelFraction": float(mask.mean()),
                              "note": "모델 점수는 실제 정확도와 구분하며 경계 검토와 실측 비교가 필요합니다.",
                          })
        args.output.mkdir(parents=True)
        Image.fromarray(mask.astype(np.uint8)*255).save(args.output/"mask.png")
        pixels = np.array(image)
        pixels[mask] = (pixels[mask]*.55 + np.array([0, 210, 90])*.45).astype(np.uint8)
        Image.fromarray(pixels).save(args.output/"overlay.jpg", quality=90)
        (args.output/"annotation.json").write_text(
            json.dumps(annotation, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
        print(json.dumps(annotation["segmentation"], ensure_ascii=False))
        print(args.output)
    except (ValueError, OSError, KeyError, ImportError, RuntimeError) as error:
        parser.exit(2, f"마스크 생성 실패: {error}\n")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    main()
