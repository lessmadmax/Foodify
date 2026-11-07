"""Fit a provisional mass mapping from explicitly unvalidated observed raw surface volume.

This offline experiment leaves app quality gates unchanged. Its feature is observed
partial mesh volume, not a validated complete food volume. No service model is written.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import numpy as np
from ar_volume import points_from_depth, polygon_mask
from analyze_ar_sessions import fuse


def provisional_plane(points):
    if len(points) < 20:
        raise ValueError('At least 20 observed plate points are required.')
    rng = np.random.default_rng(20261028)
    best = np.zeros(len(points), dtype=bool)
    for _ in range(200):
        a, b, c = points[rng.choice(len(points), 3, replace=False)]
        normal = np.cross(b-a, c-a)
        length = np.linalg.norm(normal)
        if length < 1e-8:
            continue
        candidate = np.abs((points-a) @ (normal/length)) <= 5
        if candidate.sum() > best.sum():
            best = candidate
    if best.sum() < 20:
        raise ValueError('No plane hypothesis with 20 observed points.')
    selected = points[best]
    center = selected.mean(axis=0)
    _, singular, vt = np.linalg.svd(selected-center, full_matrices=False)
    if singular[1]/math.sqrt(len(selected)) < 5:
        raise ValueError('Plate observations are approximately collinear.')
    normal = vt[-1]
    offset = -float(center @ normal)
    if offset < 0:
        normal, offset = -normal, -offset
    return normal, offset, float(best.mean()), float(np.sqrt(np.mean((selected @ normal+offset)**2)))


def observed_feature(record, root):
    reference = root/'sessions'/record['frameRelativePath']
    meta, depth, confidence, uv, source_ids = fuse(reference, sorted(reference.parent.glob('frame-*'))[:10])
    points, _ = points_from_depth(meta, depth)
    food = polygon_mask(uv, record['foodRegion']['foodPolygons'])
    plate = polygon_mask(uv, record['baseRegion']['foodPolygons']) & ~food
    valid = (depth > 0) & (confidence >= 128) & (uv >= 0).all(-1) & (uv <= 1).all(-1)
    normal, offset, inliers, rmse = provisional_plane(points[plate & valid])
    height = points @ normal+offset
    volume, area, triangles, accepted = 0., 0., 0, 0
    for offsets in (((0, 0), (1, 0), (0, 1)), ((1, 0), (1, 1), (0, 1))):
        def parts(a):
            return [a[y:y+depth.shape[0]-1, x:x+depth.shape[1]-1] for x, y in offsets]
        eligible = np.logical_and.reduce(parts(food))
        triangles += int(eligible.sum())
        p = np.stack(parts(points), axis=-2)
        heights = np.stack(parts(height), axis=-1)
        zs = np.stack(parts(depth), axis=-1)
        usable = (eligible & np.logical_and.reduce(parts(valid)) & (heights >= -5).all(-1)
                  & (heights <= 300).all(-1) & (np.ptp(zs, axis=-1) <= 30))
        projected = np.abs(np.cross(p[..., 1, :]-p[..., 0, :], p[..., 2, :]-p[..., 0, :]) @ normal)/2
        area += float(projected[usable].sum())
        volume += float((projected*np.maximum(heights, 0).mean(-1))[usable].sum())
        accepted += int(usable.sum())
    if volume <= 0 or not math.isfinite(volume):
        raise ValueError('A positive observed volume is required for this provisional fit.')
    coverage = float(valid[food].mean())
    below = float((height[food & valid] < -5).mean()) if (food & valid).any() else 1.
    holds = []
    if inliers < .8:
        holds.append('기준면 내점 비율 80% 미만: 이번 임시 계산에서만 이 조건을 완화함')
    if coverage < .8:
        holds.append('음식 유효 깊이 비율 80% 미만')
    mesh = accepted/triangles if triangles else 0.
    if mesh < .8:
        holds.append('적분 가능한 삼각형 비율 80% 미만: 결과는 부분 관측 부피')
    if below > .05:
        holds.append('기준면 아래로 추정된 음식 점 5% 초과')
    digest = hashlib.sha256()
    digest.update(json.dumps(record, sort_keys=True, ensure_ascii=False).encode('utf8'))
    for source in source_ids:
        for name in ['metadata.json', 'depth.u16le', 'confidence.u8']:
            digest.update((reference.parent/source/name).read_bytes())
    return {
        'recordId': record['id'], 'portionGroup': record['portionGroup'], 'foodName': record['foodName'],
        'scaleGrams': record['scaleGrams'], 'observedPartialVolumeMl': volume/1000,
        'foodDepthCoverage': coverage, 'meshCoverage': mesh, 'basePlaneInlierFraction': inliers,
        'basePlaneRmseMm': rmse, 'basePlaneNormal': normal.tolist(), 'basePlaneOffsetMm': offset,
        'belowPlaneFoodFraction': below, 'sourceFrames': source_ids, 'sourceDigestSha256': digest.hexdigest(),
        'experimentalHoldReasons': holds,
    }


def fit(records):
    if not records or any(not math.isfinite(r['scaleGrams']) or r['scaleGrams'] <= 0
                          or not math.isfinite(r['observedPartialVolumeMl']) or r['observedPartialVolumeMl'] <= 0 for r in records):
        raise ValueError('Positive finite volume and scale labels are required.')
    coefficient = sum(r['observedPartialVolumeMl']*r['scaleGrams'] for r in records)/sum(r['observedPartialVolumeMl']**2 for r in records)
    rows = []
    for r in records:
        prediction = coefficient*r['observedPartialVolumeMl']
        rows.append({**r, 'individualCoefficient': r['scaleGrams']/r['observedPartialVolumeMl'],
                     'fittedGrams': prediction, 'absoluteErrorGrams': abs(prediction-r['scaleGrams']),
                     'relativeErrorPercent': abs(prediction-r['scaleGrams'])/r['scaleGrams']*100})
    return {'schemaVersion': 1, 'status': 'PROVISIONAL_UNVALIDATED', 'foodName': records[0]['foodName'],
            'feature': 'observed_partial_raw_depth_mesh_volume_ml', 'coefficientGramsPerObservedMl': coefficient,
            'interceptGrams': 0, 'fitMethod': 'zero_intercept_least_squares', 'calibrationSamples': len(rows),
            'independentValidationSamples': 0, 'volumeValidated': False, 'serviceApplicable': False,
            'trainingMaeGrams': sum(r['absoluteErrorGrams'] for r in rows)/len(rows), 'records': rows,
            'assumptions': ['동일 음식과 동일한 관측 부피 산출 절차에 한정',
                            '깊이 누락 부분을 복원하거나 완전한 음식 부피로 환산하지 않음',
                            '기준면 가설의 80% 내점 조건을 임시 분석에서 완화',
                            '저울값은 부피 계산이나 기준면 선택에 사용하지 않고 마지막 계수 적합에만 사용',
                            '두 보정 자료의 잔차는 독립 정확도 평가가 아님']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--food', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    records = json.loads((args.root/'records.json').read_text(encoding='utf8'))
    candidates = [r for r in records if r['foodName'] == args.food and r['split'] == 'CALIBRATION']
    groups = [r['portionGroup'] for r in candidates]
    if len(groups) != len(set(groups)):
        raise ValueError('This provisional tool requires one observation per portion group.')
    result = fit([observed_feature(r, args.root) for r in candidates])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
