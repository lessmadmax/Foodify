"""Fit food-specific density on calibration portions; score held-out portions."""
import argparse
import csv
import json
import math
from pathlib import Path
from statistics import median


def evaluate(rows):
    groups, seen_frames, identities = {}, set(), {}
    for row in rows:
        portion = row["portion_id"].strip()
        food = row["food"].strip()
        split = row["split"]
        scale = float(row["scale_grams"])
        if not portion or not food or split not in ("calibration", "evaluation") or not math.isfinite(scale) or scale <= 0:
            raise ValueError("제공량 ID, 음식, 분할, 양수 실측 중량을 확인하세요.")
        identity = (food, split, scale)
        if portion in identities and identities[portion] != identity:
            raise ValueError("같은 제공량 ID의 음식·분할·실측값은 같아야 합니다.")
        identities[portion] = identity
        result = row["result"]
        fingerprint = result.get("frameFingerprint")
        if not isinstance(fingerprint, str) or len(fingerprint) != 64 or fingerprint in seen_frames:
            raise ValueError("동일 프레임을 중복 사용했거나 프레임 식별자가 없습니다.")
        seen_frames.add(fingerprint)
        group = groups.setdefault(portion, {"food": food, "split": split, "scale": scale, "volumes": [], "frames": 0, "validFrames": 0})
        group["frames"] += 1
        volume = result.get("volumeMl")
        if result.get("status") == "EXPERIMENT_ESTIMATE" and isinstance(volume, (int, float)) and math.isfinite(volume) and volume > 0:
            group["volumes"].append(volume)
            group["validFrames"] += 1
    food_reports = {}
    for food in sorted({group["food"] for group in groups.values()}):
        related = [dict(group, portionId=portion) for portion, group in groups.items() if group["food"] == food]
        calibration = [group for group in related if group["split"] == "calibration" and group["volumes"]]
        validation = [group for group in related if group["split"] == "evaluation"]
        density = None
        if len(calibration) >= 3:
            density = sum(median(g["volumes"])*g["scale"] for g in calibration) / sum(median(g["volumes"])**2 for g in calibration)
            if not 0 < density <= 5:
                density = None
        scored = []
        for group in validation:
            prediction = median(group["volumes"])*density if density is not None and group["volumes"] else None
            error = abs(prediction-group["scale"]) if prediction is not None else None
            scored.append({"portionId": group["portionId"], "scaleGrams": group["scale"],
                           "predictedGrams": prediction, "absoluteErrorGrams": error,
                           "relativeErrorPercent": error/group["scale"]*100 if error is not None else None})
        errors = [item["absoluteErrorGrams"] for item in scored if item["absoluteErrorGrams"] is not None]
        relatives = [item["relativeErrorPercent"] for item in scored if item["relativeErrorPercent"] is not None]
        frame_count = sum(g["frames"] for g in validation)
        valid_frames = sum(g["validFrames"] for g in validation)
        food_reports[food] = {
            "calibrationPortionsWithVolume": len(calibration), "evaluationPortions": len(validation),
            "densityGPerMl": density, "densitySource": "calibration portions only, origin-constrained least squares",
            "meanAbsoluteErrorGrams": sum(errors)/len(errors) if errors else None,
            "medianRelativeErrorPercent": median(relatives) if relatives else None,
            "portionEstimateRate": len(errors)/len(validation) if validation else None,
            "frameVolumeAvailabilityRate": valid_frames/frame_count if frame_count else None,
            "portions": scored,
        }
    return {"schemaVersion": 1, "volumeValidated": False, "foods": food_reports,
            "note": "실험 결과 요약입니다. 보정 제공량 3개 이상이 필요하며 자동 채택 판정은 별도 수행합니다."}


def main():
    parser = argparse.ArgumentParser(description="AR 제공량별 보정·평가")
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        with args.manifest.open(encoding="utf-8-sig", newline="") as source:
            rows = list(csv.DictReader(source))
        if not rows:
            raise ValueError("평가 자료를 입력하세요.")
        for row in rows:
            path = args.manifest.parent / row["result_path"]
            row["result"] = json.loads(path.read_text(encoding="utf-8"))
        report = evaluate(rows)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
        print(args.output)
    except (ValueError, KeyError, OSError) as error:
        parser.exit(2, f"평가 입력 확인 필요: {error}\n")


if __name__ == "__main__":
    main()
