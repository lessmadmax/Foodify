"""Automatic food outline by segmentation (2026-11-09): MobileSAM and GrabCut vs the reviewed manual outline.

Same 10 records as M12/M13 (밥-B excluded), each on its record frame (the frame the manual outline was
drawn on). The position prompt is the manual outline's bounding box widened by 10% per side, so this
measures boundary quality only; wrong AI boxes (M13) are a separate problem.

Methods:
    sam      MobileSAM vit_t (data/private/mobile-sam), box prompt, highest-scoring of 3 masks
    grabcut  OpenCV GrabCut initialised with the same rectangle, 5 iterations

Metrics: IoU with the manual outline mask, mask area / manual area, and the area x height weight with
M12's distance and AI heights (median of 3) and DensityGroups density, vs the scale value. A scattered
food mask leaves gaps out, so an area below the manual outline can be correct.

Usage (project root):  python scripts/compare_segmentation_outline.py
Outputs: data/private/ar/segmentation-outline-2026-11-09/ (Git-excluded). Raw sessions are only read.
"""
import json
import statistics
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT / 'data/private/mobile-sam/python'))
sys.path.insert(0, str(ROOT / 'data/private/mobile-sam/source'))
sys.path.insert(0, str(HERE))

import cv2  # noqa: E402
import numpy as np  # noqa: E402
from PIL import Image, ImageDraw  # noqa: E402
from ar_volume import read_cpu_image  # noqa: E402
import compare_api_weight_area as m12  # noqa: E402
from segment_food import verify_model  # noqa: E402

OUT = m12.AR / 'segmentation-outline-2026-11-09'
MARGIN = 0.10


def manual_mask(polygons, w, h):
    canvas = Image.new('L', (w, h), 0)
    draw = ImageDraw.Draw(canvas)
    for poly in polygons:
        draw.polygon([(x * w, y * h) for x, y in poly], fill=1)
    return np.array(canvas, dtype=bool)


def prompt_box(mask):
    ys, xs = np.nonzero(mask)
    h, w = mask.shape
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    dx, dy = (x1 - x0) * MARGIN, (y1 - y0) * MARGIN
    return [max(0, x0 - dx), max(0, y0 - dy), min(w - 1, x1 + dx), min(h - 1, y1 + dy)]


def grabcut(rgb, box):
    x0, y0, x1, y1 = map(int, box)
    mask = np.zeros(rgb.shape[:2], np.uint8)
    bgd, fgd = np.zeros((1, 65), np.float64), np.zeros((1, 65), np.float64)
    cv2.grabCut(cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR), mask, (x0, y0, x1 - x0, y1 - y0), bgd, fgd, 5,
                cv2.GC_INIT_WITH_RECT)
    return (mask == cv2.GC_FGD) | (mask == cv2.GC_PR_FGD)


def overlay(rgb, mask, manual):
    pixels = rgb.copy()
    pixels[mask] = (pixels[mask] * .5 + np.array([0, 220, 90]) * .5).astype(np.uint8)
    edge = manual ^ cv2.erode(manual.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool)
    pixels[edge] = (255, 0, 255)
    return Image.fromarray(pixels)


def main():
    import torch
    from mobile_sam import SamPredictor, sam_model_registry
    verify_model()
    torch.set_num_threads(4)
    model = sam_model_registry['vit_t']()
    model.load_state_dict(torch.load(ROOT / 'data/private/mobile-sam/source/weights/mobile_sam.pt',
                                     map_location='cpu', weights_only=True))
    model.eval()
    predictor = SamPredictor(model)
    geo = {g['group']: g for g in json.loads((m12.OUT / 'geometry.json').read_text(encoding='utf8'))}
    heights = {r['group']: [h for h in r['heights'] if h is not None]
               for r in json.loads((m12.OUT / 'summary.json').read_text(encoding='utf8'))['table']}
    (OUT / 'overlay').mkdir(parents=True, exist_ok=True)
    table = []
    for record in json.loads(m12.RECORDS.read_text(encoding='utf8')):
        group = record['portionGroup']
        if group in m12.EXCLUDED:
            continue
        g = geo[group]
        rgb = np.asarray(read_cpu_image(m12.frame_dir(record['frameRelativePath'])).convert('RGB'))
        h, w = rgb.shape[:2]
        manual = manual_mask(record['foodRegion']['foodPolygons'], w, h)
        box = prompt_box(manual)
        started = time.perf_counter()
        with torch.inference_mode():
            predictor.set_image(rgb)
            masks, scores, _ = predictor.predict(box=np.array(box), multimask_output=True)
        sam_seconds = time.perf_counter() - started
        started = time.perf_counter()
        gc_mask = grabcut(rgb, box)
        gc_seconds = time.perf_counter() - started
        results = {'sam': (masks[int(np.argmax(scores))], sam_seconds), 'grabcut': (gc_mask, gc_seconds)}
        px_cm2 = (g['distanceMm'] / g['fx']) * (g['distanceMm'] / g['fy']) / 100 * (g['imageW'] * g['imageH']) / (w * h)
        h_med = statistics.median(heights[group])
        density = m12.DENSITY[g['food']]
        row = {'group': group, 'scaleGrams': g['scaleGrams'], 'manualAreaCm2': round(manual.sum() * px_cm2, 1),
               'manualErr': abs(manual.sum() * px_cm2 * h_med * density - g['scaleGrams']) / g['scaleGrams'] * 100,
               'box': [round(v, 1) for v in box]}
        for name, (mask, seconds) in results.items():
            inter, union = (mask & manual).sum(), (mask | manual).sum()
            area = mask.sum() * px_cm2
            grams = area * h_med * density
            row[name] = {'iou': round(inter / union, 3) if union else 0.0, 'areaRatio': round(mask.sum() / manual.sum(), 2),
                         'areaCm2': round(area, 1), 'grams': round(grams, 1),
                         'err': abs(grams - g['scaleGrams']) / g['scaleGrams'] * 100, 'seconds': round(seconds, 2)}
            overlay(rgb, mask, manual).save(OUT / 'overlay' / f'{group}__{name}.jpg', quality=88)
        table.append(row)
        print(f"{group:<8} 수동 {row['manualAreaCm2']:5.1f}cm2 {row['manualErr']:4.0f}% | "
              + ' | '.join(f"{n} IoU {row[n]['iou']:.2f} 면적비 {row[n]['areaRatio']:.2f} 오차 {row[n]['err']:4.0f}% "
                           f"{row[n]['seconds']}s" for n in results))

    def agg(values):
        return {'n': len(values), 'median': round(statistics.median(values), 2), 'mean': round(statistics.mean(values), 2)}
    summary = {'manualErr': agg([r['manualErr'] for r in table])}
    for name in ('sam', 'grabcut'):
        for key in ('iou', 'areaRatio', 'err'):
            summary[f'{name}_{key}'] = agg([r[name][key] for r in table])
    (OUT / 'summary.json').write_text(json.dumps({'summary': summary, 'table': table}, ensure_ascii=False, indent=1),
                                      encoding='utf8')
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    for name in ('sam', 'grabcut'):
        tiles = [Image.open(OUT / 'overlay' / f"{r['group']}__{name}.jpg") for r in table]
        tw, th = tiles[0].width // 2, tiles[0].height // 2
        sheet = Image.new('RGB', (tw * 5, th * 2))
        for i, tile in enumerate(tiles):
            sheet.paste(tile.resize((tw, th)), ((i % 5) * tw, (i // 5) * th))
        sheet.save(OUT / f'sheet-{name}.jpg', quality=88)


if __name__ == '__main__':
    main()
