"""Offline research tool for saved Foodify AR frames; never updates meal records."""
import argparse
import base64
import io
import hashlib
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image
from food_mask import decode_mask, sample_mask


def frame_fingerprint(folder):
    digest = hashlib.sha256()
    for name in ("metadata.json", "depth.u16le", "confidence.u8"):
        digest.update((Path(folder) / name).read_bytes())
    return digest.hexdigest()


def read_frame(folder):
    folder = Path(folder)
    meta = json.loads((folder / "metadata.json").read_text(encoding="utf-8"))
    if meta.get("schemaVersion") != 1 or meta.get("depthUnit") != "millimetres_camera_z":
        raise ValueError("지원하는 깊이 메타데이터가 필요합니다.")
    w, h = int(meta["depthWidth"]), int(meta["depthHeight"])
    if min(w, h) < 2 or w * h > 4_000_000:
        raise ValueError("깊이 영상 크기를 확인하세요.")
    depth = np.frombuffer((folder / "depth.u16le").read_bytes(), dtype="<u2")
    confidence = np.frombuffer((folder / "confidence.u8").read_bytes(), dtype=np.uint8)
    if depth.size != w * h or confidence.size != w * h:
        raise ValueError("깊이/신뢰도 파일 길이가 메타데이터와 다릅니다.")
    return meta, depth.reshape(h, w).astype(float), confidence.reshape(h, w)


def cpu_coordinates(meta):
    """Depth pixel centres -> CPU normalized image using recorded affine mapping."""
    h, w = int(meta["depthHeight"]), int(meta["depthWidth"])
    source = np.asarray(meta["depthNormalizedCorners"], float).reshape(4, 2)
    target = np.asarray(meta["correspondingCpuImageNormalizedCorners"], float).reshape(4, 2)
    if not np.isfinite(source).all() or not np.isfinite(target).all():
        raise ValueError("좌표 변환 값이 유효하지 않습니다.")
    matrix, _, rank, _ = np.linalg.lstsq(np.c_[source, np.ones(4)], target, rcond=None)
    if rank < 3 or np.max(np.abs(np.c_[source, np.ones(4)] @ matrix - target)) > 1e-4:
        raise ValueError("저장된 좌표 변환을 재구성할 수 없습니다.")
    if abs(np.linalg.det(matrix[:2])) < 1e-8:
        raise ValueError("좌표 변환 면적이 0입니다.")
    y, x = np.mgrid[:h, :w]
    return np.stack(((x + .5) / w, (y + .5) / h, np.ones_like(x)), axis=-1) @ matrix


def points_from_depth(meta, depth):
    uv = cpu_coordinates(meta)
    intr = meta["cpuIntrinsics"]
    dimensions = np.asarray(intr["imageDimensions"], float)
    focal = np.asarray(intr["focalLength"], float)
    principal = np.asarray(intr["principalPoint"], float)
    if (dimensions.shape != (2,) or focal.shape != (2,) or principal.shape != (2,)
            or not np.isfinite(np.r_[dimensions, focal, principal]).all()
            or np.any(dimensions <= 0) or np.any(focal <= 0)):
        raise ValueError("카메라 내부 파라미터를 확인하세요.")
    xy = (uv * dimensions - principal) / focal
    return np.concatenate((xy, np.ones((*depth.shape, 1))), axis=-1) * depth[..., None], uv


def polygon_mask(uv, polygons):
    """Union of polygons in original CPU normalized coordinates, independent of display rotation."""
    mask = np.zeros(uv.shape[:2], dtype=bool)
    for polygon in polygons:
        vertices = np.asarray(polygon, float)
        if (vertices.ndim != 2 or vertices.shape[1] != 2 or len(vertices) < 3
                or not np.isfinite(vertices).all() or np.any(vertices < 0) or np.any(vertices > 1)):
            raise ValueError("영역은 0~1 좌표의 꼭짓점 3개 이상으로 지정하세요.")
        inside = np.zeros_like(mask)
        x, y = uv[..., 0], uv[..., 1]
        previous = vertices[-1]
        for current in vertices:
            x1, y1 = previous
            x2, y2 = current
            if y1 != y2:
                cross_x = (x2 - x1) * (y - y1) / (y2 - y1) + x1
                inside ^= ((y1 > y) != (y2 > y)) & (x < cross_x)
            previous = current
        mask |= inside
    return mask


def fit_plane(points):
    """Deterministic RANSAC followed by orthogonal least-squares, units mm."""
    if len(points) < 20:
        raise ValueError("접시 기준면의 유효 깊이가 20개 이상 필요합니다.")
    if len(points) > 20000:
        points = points[np.linspace(0, len(points) - 1, 20000, dtype=int)]
    rng = np.random.default_rng(20261028)
    best = np.zeros(len(points), dtype=bool)
    for _ in range(200):
        a, b, c = points[rng.choice(len(points), 3, replace=False)]
        normal = np.cross(b - a, c - a)
        length = np.linalg.norm(normal)
        if length < 1e-8:
            continue
        normal /= length
        inliers = np.abs((points - a) @ normal) <= 5
        if inliers.sum() > best.sum():
            best = inliers
    if best.sum() < 20 or best.mean() < .8:
        raise ValueError("접시 기준면이 불안정합니다. 평평한 받침면을 다시 지정하세요.")
    selected = points[best]
    center = selected.mean(axis=0)
    _, singular, vt = np.linalg.svd(selected - center, full_matrices=False)
    if singular[1] / math.sqrt(len(selected)) < 5:
        raise ValueError("기준면 점이 한 줄에 몰려 있습니다. 넓게 분산된 영역을 지정하세요.")
    normal = vt[-1]
    offset = -float(center @ normal)
    if offset < 0:  # Positive height points towards the camera.
        normal, offset = -normal, -offset
    rmse = float(np.sqrt(np.mean((selected @ normal + offset) ** 2)))
    return normal, offset, rmse, float(best.mean())


def estimate(meta, depth, confidence, annotation):
    if annotation.get("schemaVersion") != 1 or annotation.get("coordinateSpace") != "cpu_image_normalized":
        raise ValueError("원본 사진 좌표의 영역 지정 파일이 필요합니다.")
    points, uv = points_from_depth(meta, depth)
    mask_spec = annotation.get("foodMask")
    method = "segmented_mask_depth_mesh_v1" if mask_spec is not None else "manual_mask_depth_mesh_v1"
    food = (sample_mask(decode_mask(mask_spec, meta["cpuIntrinsics"]["imageDimensions"]), uv)
            if mask_spec is not None else polygon_mask(uv, annotation.get("foodPolygons", [])))
    base = polygon_mask(uv, annotation.get("basePolygons", []))
    if not food.any() or not base.any():
        raise ValueError("음식 영역과 접시 기준면을 각각 지정하세요.")
    if np.any(food & base):
        raise ValueError("음식 영역과 기준면이 겹칩니다.")
    valid = (depth > 0) & (confidence >= 128) & (uv >= 0).all(axis=-1) & (uv <= 1).all(axis=-1)
    try:
        normal, offset, rmse, inlier_ratio = fit_plane(points[base & valid])
    except ValueError as error:
        return {
            "schemaVersion": 1, "method": method, "volumeValidated": False,
            "status": "REVIEW_REQUIRED", "holdReasons": [str(error)],
            "foodName": annotation.get("foodName", ""), "observedVolumeMl": None,
            "volumeMl": None, "estimatedGrams": None,
            "validFoodPixelFraction": float(valid[food].mean()),
            "scaleGrams": annotation.get("scaleGrams"),
        }
    height = points @ normal + offset
    reasons = []
    if mask_spec is not None and mask_spec.get("reviewed") is not True:
        reasons.append("생성된 음식 경계 검토 필요")
    if meta.get("trackingState") != "TRACKING":
        reasons.append("카메라 추적 불안정")
    frame_time = int(meta["frameTimestampNs"])
    for key, limit in (("depthTimestampNs", 100), ("cameraTimestampNs", 50), ("confidenceTimestampNs", 100)):
        if abs(frame_time - int(meta[key])) / 1e6 > limit:
            reasons.append(key + " 시간차 초과")
    if int(meta["depthTimestampNs"]) != int(meta["confidenceTimestampNs"]):
        reasons.append("깊이와 신뢰도 시각 불일치")
    tilt = math.degrees(math.acos(float(np.clip(abs(normal[2]), 0, 1))))
    if tilt > 30:
        reasons.append("상단 촬영 권장 각도(30도) 초과")
    if rmse > 3:
        reasons.append("접시 평면 잔차 3mm 초과")
    pixel_coverage = float(valid[food].mean())
    if pixel_coverage < .8:
        reasons.append("음식 유효 깊이 비율 80% 미만")
    if np.any(valid & food & (height > 300)):
        reasons.append("음식 높이 300mm 초과")
    below = float(np.mean(height[food & valid] < -5)) if (food & valid).any() else 1.
    if below > .05:
        reasons.append("기준면 아래의 음식 점 5% 초과")
    # Surface mesh projected orthogonally onto support plane. No hole interpolation.
    # Adjacent pixels with a large depth jump are not joined across an edge.
    volume_mm3, area_mm2, total_triangles, accepted_triangles = 0., 0., 0, 0
    for offsets in (((0, 0), (1, 0), (0, 1)), ((1, 0), (1, 1), (0, 1))):
        def slices(array):
            return [array[y:y + depth.shape[0] - 1, x:x + depth.shape[1] - 1] for x, y in offsets]
        food_parts, valid_parts = slices(food), slices(valid)
        eligible = np.logical_and.reduce(food_parts)
        total_triangles += int(eligible.sum())
        p = np.stack(slices(points), axis=-2)
        heights = np.stack(slices(height), axis=-1)
        zs = np.stack(slices(depth), axis=-1)
        good = (eligible & np.logical_and.reduce(valid_parts)
                & (heights >= -5).all(axis=-1) & (heights <= 300).all(axis=-1)
                & (np.ptp(zs, axis=-1) <= 30))
        projected_area = np.abs(np.cross(p[..., 1, :] - p[..., 0, :], p[..., 2, :] - p[..., 0, :]) @ normal) / 2
        area_mm2 += float(projected_area[good].sum())
        volume_mm3 += float((projected_area * np.maximum(heights, 0).mean(axis=-1))[good].sum())
        accepted_triangles += int(good.sum())
    mesh_coverage = accepted_triangles / total_triangles if total_triangles else 0.
    if mesh_coverage < .8:
        reasons.append("부피 적분 가능 삼각형 비율 80% 미만")
    if area_mm2 <= 0 or volume_mm3 <= 0:
        reasons.append("양의 음식 부피 미확보")
    volume = volume_mm3 / 1000
    density = annotation.get("densityGPerMl")
    source = annotation.get("densitySource")
    if density is not None:
        if isinstance(density, bool) or not isinstance(density, (float, int)) or not math.isfinite(density) or not 0 < density <= 5 or not isinstance(source, str) or not source.strip():
            raise ValueError("밀도는 0 초과 5 이하의 수치와 환산 근거를 함께 입력하세요.")
    weight = volume * density if density is not None and not reasons else None
    scale = annotation.get("scaleGrams")
    if scale is not None and (isinstance(scale, bool) or not isinstance(scale, (float, int)) or not math.isfinite(scale) or scale <= 0):
        raise ValueError("전자저울 중량은 양수여야 합니다.")
    return {
        "schemaVersion": 1, "method": method, "volumeValidated": False,
        "status": "REVIEW_REQUIRED" if reasons else "EXPERIMENT_ESTIMATE",
        "holdReasons": reasons, "foodName": annotation.get("foodName", ""),
        "observedVolumeMl": round(volume, 3),
        "volumeMl": round(volume, 3) if not reasons else None,
        "projectedObservedAreaCm2": round(area_mm2 / 100, 3),
        "validFoodPixelFraction": pixel_coverage, "meshCoverage": mesh_coverage,
        "basePlaneRmseMm": rmse, "basePlaneInlierFraction": inlier_ratio,
        "basePlaneNormal": normal.tolist(), "basePlaneOffsetMm": offset, "cameraTiltDegrees": tilt,
        "densityGPerMl": density, "densitySource": source, "estimatedGrams": weight,
        "scaleGrams": scale,
        "absoluteErrorGrams": abs(weight - scale) if weight is not None and scale is not None else None,
        "relativeErrorPercent": abs(weight - scale) / scale * 100 if weight is not None and scale is not None else None,
        "limitations": ["단일 시점의 관측 표면을 적분한 실험값",
                       "가려진 옆면·바닥과 깊이 누락 영역의 부피는 복원하지 않음",
                       "평평한 접시와 상단 촬영 조건에서 실측 검증 필요"],
    }


def read_cpu_image(folder):
    meta, _, _ = read_frame(folder)
    w, h = meta["cpuIntrinsics"]["imageDimensions"]
    if w % 2 or h % 2 or w * h > 20_000_000:
        raise ValueError("지원하지 않는 카메라 크기입니다.")
    folder = Path(folder)
    planes = []
    for filename, width, height in (("camera-y.u8", w, h), ("camera-u.u8", w//2, h//2), ("camera-v.u8", w//2, h//2)):
        raw = (folder / filename).read_bytes()
        if len(raw) != width * height:
            raise ValueError("카메라 평면 파일 길이가 다릅니다.")
        plane = np.frombuffer(raw, np.uint8).reshape(height, width).astype(float)
        planes.append(plane if width == w else plane.repeat(2, 0).repeat(2, 1))
    y, u, v = planes
    y, u, v = (y - 16) * 1.164, u - 128, v - 128
    rgb = np.clip(np.stack((y + 1.596*v, y - .392*u - .813*v, y + 2.017*u), -1), 0, 255).astype(np.uint8)
    return Image.fromarray(rgb)


def make_annotation_page(folder, output):
    image = io.BytesIO()
    rgb = read_cpu_image(folder)
    rgb.save(image, format="JPEG", quality=90)
    template = Path(__file__).with_name("ar_annotation.html").read_text(encoding="utf-8")
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    template = template.replace("__FRAME_FINGERPRINT__", frame_fingerprint(folder))
    output.write_text(template.replace("__IMAGE_DATA__", base64.b64encode(image.getvalue()).decode()), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description="Foodify AR 수동 영역 부피 실험")
    sub = parser.add_subparsers(dest="command", required=True)
    page = sub.add_parser("annotate")
    page.add_argument("--frame", required=True, type=Path)
    page.add_argument("--output", required=True, type=Path)
    export = sub.add_parser("export-image")
    export.add_argument("--frame", required=True, type=Path)
    export.add_argument("--output", required=True, type=Path)
    calc = sub.add_parser("estimate")
    calc.add_argument("--frame", required=True, type=Path)
    calc.add_argument("--annotation", required=True, type=Path)
    calc.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        if args.command == "annotate":
            make_annotation_page(args.frame, args.output)
        elif args.command == "export-image":
            if args.output.suffix.lower() != ".png":
                raise ValueError("원본 좌표와 픽셀을 유지하는 PNG 경로를 지정하세요.")
            args.output.parent.mkdir(parents=True, exist_ok=True)
            read_cpu_image(args.frame).save(args.output, format="PNG")
        else:
            meta, depth, confidence = read_frame(args.frame)
            annotation = json.loads(args.annotation.read_text(encoding="utf-8-sig"))
            if annotation.get("frameFingerprint") != frame_fingerprint(args.frame):
                raise ValueError("영역을 지정한 사진과 계산할 AR 프레임이 다릅니다.")
            result = estimate(meta, depth, confidence, annotation)
            result["frameFingerprint"] = frame_fingerprint(args.frame)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
            print(result["status"])
        print(args.output)
    except (ValueError, KeyError, OSError) as error:
        parser.exit(2, f"입력 확인 필요: {error}\n")


if __name__ == "__main__":
    main()
