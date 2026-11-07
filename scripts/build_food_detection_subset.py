"""Build a small YOLO detection dataset (dish + Korean foods) from the AI Hub
"음식 이미지 및 영양정보 텍스트" zips, for upload to Kaggle.

Reads images straight from the source zips (no full extraction), keeps up to N images
per selected food code, applies EXIF orientation (AI Hub boxes are in the
EXIF-transposed orientation, checked on 43 samples), resizes to --max-side and writes
YOLO labels converted from the XML boxes.

Classes: 0 = dish (container), 1.. = selected food codes in classes-file order.

classes-file: one food per line, "code[,name]" (e.g. "01011001,쌀밥"). Lines starting
with # are ignored.

Usage (project root; needs Pillow, e.g. PYTHONPATH=data/private/gemma4/python):
    python scripts/build_food_detection_subset.py \
        --labels "data/food-images/raw/음식 이미지 및 영양정보 텍스트/Training/[라벨]음식분류_TRAIN.zip" \
        --sources "data/food-images/raw/음식 이미지 및 영양정보 텍스트/Training/[원천]음식분류_TRAIN_*.zip" \
        --classes data/private/food-detect/classes.txt --per-class 250 \
        --out data/private/food-detect/dataset

Re-running skips images already written, so source zips can be added (and deleted)
one at a time to save disk space. Finish with --zip to make the Kaggle upload archive.
"""
import argparse
import glob
import hashlib
import io
import json
import re
import shutil
import sys
import xml.etree.ElementTree as ET
import zipfile
from collections import Counter
from pathlib import Path

from PIL import Image, ImageOps

IMAGE_EXT = ('.jpg', '.jpeg', '.png')


def read_classes(path):
    codes, names = [], {}
    for line in Path(path).read_text(encoding='utf8').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        code, _, name = line.partition(',')
        code = code.strip()
        if not re.fullmatch(r'\d{8}', code):
            sys.exit(f'잘못된 음식 코드: {line}')
        if code in names:
            sys.exit(f'중복 코드: {code}')
        codes.append(code)
        names[code] = name.strip() or code
    return codes, names


def split_of(stem):
    # Deterministic 85/15 split independent of processing order.
    return 'val' if int(hashlib.sha1(stem.encode()).hexdigest(), 16) % 100 < 15 else 'train'


def yolo_lines(xml_bytes, class_index):
    root = ET.fromstring(xml_bytes)
    width = float(root.findtext('size/width'))
    height = float(root.findtext('size/height'))
    lines = []
    for obj in root.findall('object'):
        name = obj.findtext('name').strip()
        cls = 0 if name == 'dish' else class_index.get(name)
        if cls is None:
            continue  # food outside the selected classes: drop the box, keep the image
        box = obj.find('bndbox')
        x0, y0, x1, y1 = (float(box.findtext(k)) for k in ('xmin', 'ymin', 'xmax', 'ymax'))
        x0, x1 = sorted((max(0.0, x0), min(width, x1)))
        y0, y1 = sorted((max(0.0, y0), min(height, y1)))
        if x1 - x0 < 2 or y1 - y0 < 2:
            continue
        lines.append(f'{cls} {(x0 + x1) / 2 / width:.6f} {(y0 + y1) / 2 / height:.6f} '
                     f'{(x1 - x0) / width:.6f} {(y1 - y0) / height:.6f}')
    return (width, height), lines


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--labels', required=True)
    p.add_argument('--sources', required=True, help='glob of source image zips')
    p.add_argument('--classes', required=True)
    p.add_argument('--per-class', type=int, default=250)
    p.add_argument('--max-side', type=int, default=960)
    p.add_argument('--out', required=True)
    p.add_argument('--zip', action='store_true', help='also write <out>.zip for Kaggle upload')
    args = p.parse_args()

    codes, names = read_classes(args.classes)
    class_index = {code: i + 1 for i, code in enumerate(codes)}
    out = Path(args.out)
    for split in ('train', 'val'):
        (out / 'images' / split).mkdir(parents=True, exist_ok=True)
        (out / 'labels' / split).mkdir(parents=True, exist_ok=True)

    label_zip = zipfile.ZipFile(args.labels)
    xml_by_stem = {Path(n).stem: n for n in label_zip.namelist() if n.endswith('.xml')}

    done = Counter()
    for img in (out / 'images').glob('*/*.jpg'):
        code = img.stem.split('_')[2] if img.stem.count('_') >= 3 else ''
        if code in class_index:
            done[code] += 1

    stats = Counter()
    # AI Hub names contain "[원천]": escape everything except the * wildcard.
    pattern = ''.join(ch if ch == '*' else glob.escape(ch) for ch in args.sources)
    sources = sorted(glob.glob(pattern))
    if not sources:
        sys.exit('원천 zip을 찾지 못했습니다.')
    for source in sources:
        zf = zipfile.ZipFile(source)
        members = sorted(n for n in zf.namelist() if n.lower().endswith(IMAGE_EXT))
        by_code = {}
        for n in members:
            parts = n.split('/')
            if len(parts) >= 3 and parts[-2] in class_index:
                by_code.setdefault(parts[-2], []).append(n)
        for code, files in by_code.items():
            # Spread the pick over the whole class instead of the first N files.
            files.sort(key=lambda n: hashlib.sha1(n.encode()).hexdigest())
            for n in files:
                if done[code] >= args.per_class:
                    break
                stem = Path(n).stem
                target = out / 'images' / split_of(stem) / f'{stem}.jpg'
                if target.exists():
                    continue
                xml_name = xml_by_stem.get(stem)
                if xml_name is None:
                    stats['no_label'] += 1
                    continue
                (w, h), lines = yolo_lines(label_zip.read(xml_name), class_index)
                if not any(not line.startswith('0 ') for line in lines):
                    stats['no_food_box'] += 1
                    continue
                try:
                    image = ImageOps.exif_transpose(Image.open(io.BytesIO(zf.read(n)))).convert('RGB')
                except Exception:
                    stats['bad_image'] += 1
                    continue
                if image.size != (int(w), int(h)):
                    stats['size_mismatch'] += 1  # box frame unknown: skip rather than guess
                    continue
                image.thumbnail((args.max_side, args.max_side))
                image.save(target, quality=90)
                (out / 'labels' / split_of(stem) / f'{stem}.txt').write_text('\n'.join(lines) + '\n', encoding='utf8')
                done[code] += 1
                stats['written'] += 1
        print(f'{Path(source).name}: 누적 {sum(done.values())}장, 이번 {stats["written"]}장', flush=True)

    yaml = ['path: .', 'train: images/train', 'val: images/val', f'nc: {len(codes) + 1}', 'names:', '  0: dish']
    yaml += [f'  {class_index[c]}: "{names[c]}"' for c in codes]
    (out / 'data.yaml').write_text('\n'.join(yaml) + '\n', encoding='utf8')
    missing = [c for c in codes if done[c] == 0]
    short = {c: done[c] for c in codes if 0 < done[c] < args.per_class}
    report = {'perClass': {c: done[c] for c in codes}, 'missingCodes': missing, 'shortCodes': short,
              'skipped': {k: v for k, v in stats.items() if k != 'written'}}
    (out / 'build-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding='utf8')
    print(f'종류 {len(codes)}개 중 사진 없음 {len(missing)}개, 부족 {len(short)}개, 건너뜀 {report["skipped"]}')
    if args.zip:
        archive = shutil.make_archive(str(out), 'zip', out)
        print('업로드 파일:', archive)


if __name__ == '__main__':
    main()
