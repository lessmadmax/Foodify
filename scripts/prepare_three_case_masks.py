"""사용자 승인에 따라 저울 표시창만 가림. 원본과 사진 좌표·크기는 보존."""
import base64
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'data/private/ar/three-cases-2026-11-18'
BOXES = {0: (320, 108, 385, 169), 1: (278, 73, 333, 122), 2: (264, 32, 330, 84),
         8: (450, 198, 475, 287), 9: (451, 215, 477, 289), 10: (439, 213, 467, 288)}


if __name__ == '__main__':
    path = OUT / 'manifest.json'
    manifest = json.loads(path.read_text(encoding='utf-8'))
    if manifest.get('imageLeakReviewComplete'):
        raise SystemExit('Already finalized; no changes made.')
    if list(OUT.glob('*__api_*__*.json')):
        raise SystemExit('Responses exist; refuse to change inputs.')
    backup = OUT / 'manifest-unmasked-before-review.json'
    if not backup.exists():
        backup.write_bytes(path.read_bytes())
    images = OUT / 'masked'
    images.mkdir(exist_ok=True)
    sheet = Image.new('RGB', (960, 1040), 'white')
    draw = ImageDraw.Draw(sheet)
    for i, s in enumerate(manifest['samples']):
        original = Path(s['sourceFrame']) / 'analysis.jpg'
        image = Image.open(original).convert('RGB')
        assert image.size == (640, 480)
        if i in BOXES:
            ImageDraw.Draw(image).rectangle(BOXES[i], fill=(110, 110, 110))
        target = images / f'{i:02d}.png'
        image.save(target)  # 무손실 저장, 회전·크기 변경 없음
        s['maskedImage'] = str(target)
        s['maskBox'] = BOXES.get(i)
        s['maskedImageSha256'] = hashlib.sha256(target.read_bytes()).hexdigest()
        encoded = 'data:image/png;base64,' + base64.b64encode(target.read_bytes()).decode()
        for c in ('api_photo', 'api_ar'):
            part = next(x for x in s['requests'][c]['input'][0]['content'] if x['type'] == 'input_image')
            part['image_url'] = encoded
        thumb = image.resize((320, 240))
        x, y = i % 3 * 320, i // 3 * 260
        sheet.paste(thumb, (x, y + 20))
        draw.text((x + 5, y + 3), str(i), fill='black')
    manifest['maskingPrepared'] = True
    manifest['imageLeakReviewComplete'] = False  # 최종 시각 확인 후 별도로 확정
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding='utf-8')
    sheet.save(OUT / 'masked-contact.png')
    print('11 masked/copied images prepared. Original images unchanged. Visual review pending.')
