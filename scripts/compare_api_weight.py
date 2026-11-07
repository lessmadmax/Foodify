"""Compare OpenAI weight estimates with scale labels on the S23 AR research records.

Uses the same prompt, schema, instructions and model default as the backend
(OpenAiClient.java is parsed at runtime so the prompt cannot drift). Each record is
sent twice with the identical photo: once as a plain photo and once with the AR depth
summary computed exactly like the app (ArCaptureExport.kt / DepthData.kt).

Usage (PowerShell, project root):
    $env:OPENAI_API_KEY = "..."      # the same key the server uses
    python scripts/compare_api_weight.py --repeat 3
    python scripts/compare_api_weight.py --dry-run   # build requests only, no API call

Standard library only. Results are written under data/private/ar/ (Git-excluded).
"""
import argparse
import base64
import json
import os
import re
import statistics
import struct
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AR = ROOT / 'data' / 'private' / 'ar'
JAVA = ROOT / 'backend/src/main/java/com/example/foodify/ai/OpenAiClient.java'
RECORDS = AR / 'added-review-20261104' / 'records.json'
SESSION_ROOTS = ['peanut-review-20261104', 'added-review-20261104', 'depth-improvement-20261104']
KEYWORDS = {'초코칩 쿠키': ['쿠키'], '밥': ['밥', '쌀'], '커피땅콩': ['땅콩', '피넛', '견과']}
CONFIDENCE_THRESHOLD = 128          # DepthData.CONFIDENCE_THRESHOLD
SERVER_MAX_DEPTH_AGE_MS = 100       # CaptureEvidence.java
SERVER_MAX_CAMERA_AGE_MS = 50
SERVER_MIN_CONFIDENT_FRACTION = 0.05


def java_string(literal):
    return json.loads('"' + literal + '"')


def load_prompt():
    src = JAVA.read_text(encoding='utf8')
    lines = src.splitlines()
    first = next(l for l in lines if 'parts.add(Map.of("type","input_text","text","한 끼 음식 사진' in l)
    task = java_string(re.search(r'"text","((?:[^"\\]|\\.)*)"\)\);', first).group(1))
    evidence_prefix = java_string(re.search(r'"촬영 근거((?:[^"\\]|\\.)*)"', src).group(0)[1:-1])
    suffix_line = next(l for l in lines if l.strip().startswith('+ "\\nAR가 있으면'))
    evidence_suffix = java_string(re.search(r'\+ "((?:[^"\\]|\\.)*)"', suffix_line).group(1))
    instructions = java_string(re.search(r'"instructions","((?:[^"\\]|\\.)*)"', src).group(1))
    return task, evidence_prefix, evidence_suffix, instructions


def schema():
    number = {'type': ['number', 'null']}
    weight = {'type': 'object', 'additionalProperties': False,
              'properties': {'lowerGrams': number, 'upperGrams': number,
                             'assumptions': {'type': 'array', 'items': {'type': 'string'}}},
              'required': ['lowerGrams', 'upperGrams', 'assumptions']}
    food = {'type': 'object', 'additionalProperties': False,
            'properties': {'name': {'type': 'string'}, 'grams': number, 'weightEstimate': weight,
                           'ingredients': {'type': 'array', 'items': {'type': 'string'}},
                           'uncertainty': {'type': 'string'}},
            'required': ['name', 'grams', 'weightEstimate', 'ingredients', 'uncertainty']}
    return {'type': 'object', 'additionalProperties': False,
            'properties': {'items': {'type': 'array', 'items': food}}, 'required': ['items']}


def frame_dir(rel):
    for name in SESSION_ROOTS:
        path = AR / name / 'sessions' / rel
        if (path / 'metadata.json').exists():
            return path
    raise FileNotFoundError(rel)


def ar_evidence(frame):
    """Mirror of ArCaptureExport.export + DepthData.stats for one frame."""
    meta = json.loads((frame / 'metadata.json').read_text(encoding='utf8'))
    depth = (frame / 'depth.u16le').read_bytes()
    conf = (frame / 'confidence.u8').read_bytes()
    mm = struct.unpack('<%dH' % (len(depth) // 2), depth)
    selected = sorted(d for d, c in zip(mm, conf) if d != 0 and c >= CONFIDENCE_THRESHOLD)
    pixels = meta['depthWidth'] * meta['depthHeight']
    coverage = meta['confidentPixels'] / pixels
    frame_t = int(meta['frameTimestampNs'])
    depth_age = abs(frame_t - int(meta['depthTimestampNs'])) / 1e6
    camera_age = abs(frame_t - int(meta['cameraTimestampNs'])) / 1e6
    intr = meta['cpuIntrinsics']
    ar = {'imageIndex': 0, 'sceneMedianDepthMm': selected[len(selected) // 2] if selected else None,
          'confidentPixelFraction': coverage, 'depthAgeMs': depth_age, 'cameraAgeMs': camera_age,
          'imageWidth': intr['imageDimensions'][0], 'imageHeight': intr['imageDimensions'][1],
          'focalLengthPixels': intr['focalLength'], 'principalPointPixels': intr['principalPoint']}
    reasons = []
    if ar['sceneMedianDepthMm'] is None:
        reasons.append('신뢰도 128 이상 깊이 없음')
    if coverage < SERVER_MIN_CONFIDENT_FRACTION:
        reasons.append(f'신뢰 깊이 비율 {coverage:.1%} < 5%')
    if depth_age > SERVER_MAX_DEPTH_AGE_MS:
        reasons.append(f'깊이 지연 {depth_age:.0f}ms > 100ms')
    if camera_age > SERVER_MAX_CAMERA_AGE_MS:
        reasons.append(f'사진 지연 {camera_age:.0f}ms > 50ms')
    return ar, reasons


def build_body(model, prompt, image, evidence):
    task, prefix, suffix, instructions = prompt
    parts = [{'type': 'input_text', 'text': task},
             {'type': 'input_image', 'image_url': 'data:image/jpeg;base64,' + base64.b64encode(image).decode(),
              'detail': 'auto'},
             {'type': 'input_text', 'text': prefix + json.dumps(evidence, ensure_ascii=False, separators=(',', ':')) + suffix}]
    return {'model': model, 'store': False, 'max_output_tokens': 2500, 'instructions': instructions,
            'input': [{'role': 'user', 'content': parts}],
            'text': {'format': {'type': 'json_schema', 'name': 'foodify_analysis', 'strict': True, 'schema': schema()}}}


def call(body, key):
    data = json.dumps(body, ensure_ascii=False).encode('utf8')
    for attempt in range(4):
        request = urllib.request.Request('https://api.openai.com/v1/responses', data=data, method='POST',
                                         headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
        try:
            start = time.time()
            with urllib.request.urlopen(request, timeout=90) as response:
                root = json.loads(response.read().decode('utf8'))
            for message in root.get('output', []):
                for content in message.get('content', []):
                    if content.get('type') == 'output_text':
                        return json.loads(content['text']), root.get('usage', {}), time.time() - start
            raise ValueError('no output_text')
        except urllib.error.HTTPError as error:
            if error.code in (429, 500, 502, 503, 504) and attempt < 3:
                time.sleep(5 * (attempt + 1))
                continue
            raise RuntimeError(f'HTTP {error.code}: {error.read().decode("utf8", "replace")[:300]}')


def target_grams(output, food):
    keys = KEYWORDS.get(food, [food])
    hits = [i for i in output.get('items', []) if any(k in i.get('name', '') for k in keys) and i.get('grams')]
    return (sum(i['grams'] for i in hits), [i['name'] for i in hits]) if hits else (None, [])


def summarize(rows):
    def rel(values, truth):
        values = [v for v in values if v is not None]
        return None if not values else abs(statistics.median(values) - truth) / truth * 100
    table = []
    for r in rows:
        photo = rel(r['photo'], r['scaleGrams'])
        ar = rel(r['ar'], r['scaleGrams'])
        app = ar if r['appWouldSendDepth'] else photo
        table.append({**{k: r[k] for k in ('portionGroup', 'scaleGrams', 'appWouldSendDepth')},
                      'photoMedianGrams': statistics.median([v for v in r['photo'] if v is not None]) if any(v is not None for v in r['photo']) else None,
                      'arMedianGrams': statistics.median([v for v in r['ar'] if v is not None]) if any(v is not None for v in r['ar']) else None,
                      'photoRelErr': photo, 'arRelErr': ar, 'currentAppRelErr': app})
    agg = {}
    for key in ('photoRelErr', 'arRelErr', 'currentAppRelErr'):
        vals = [t[key] for t in table if t[key] is not None]
        agg[key] = {'n': len(vals), 'median': statistics.median(vals) if vals else None,
                    'mean': statistics.mean(vals) if vals else None}
    return table, agg


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--repeat', type=int, default=3)
    parser.add_argument('--model', default=os.environ.get('OPENAI_MODEL', 'gpt-4o-mini'))
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    key = os.environ.get('OPENAI_API_KEY', '')
    if not args.dry_run and not key:
        sys.exit('OPENAI_API_KEY 환경변수를 설정하세요 (서버와 같은 키).')
    prompt = load_prompt()
    records = json.loads(RECORDS.read_text(encoding='utf8'))
    out_dir = AR / ('api-compare-' + datetime.now().strftime('%Y%m%d-%H%M'))
    rows, raw = [], []
    for record in records:
        frame = frame_dir(record['frameRelativePath'])
        image = (frame / 'analysis.jpg').read_bytes()
        ar, reasons = ar_evidence(frame)
        conditions = {'photo': {'method': 'guided_photos', 'volumeValidated': False}}
        if ar['sceneMedianDepthMm'] is not None:
            conditions['ar'] = {'method': 'ar_assisted_photo', 'volumeValidated': False, 'ar': ar}
        row = {'portionGroup': record['portionGroup'], 'foodName': record['foodName'],
               'scaleGrams': float(record['scaleGrams']), 'frame': record['frameRelativePath'],
               'appWouldSendDepth': not reasons, 'serverRejectReasons': reasons,
               'arEvidence': ar, 'photo': [], 'ar': []}
        print(f"{record['portionGroup']:<10} 저울 {record['scaleGrams']:>5}g  깊이전송 {'가능' if not reasons else '불가: ' + ', '.join(reasons)}")
        for name, evidence in conditions.items():
            body = build_body(args.model, prompt, image, evidence)
            if args.dry_run:
                print(f'    [{name}] 요청 {len(json.dumps(body)) // 1024}KB 준비 완료')
                continue
            for i in range(args.repeat):
                try:
                    output, usage, seconds = call(body, key)
                    grams, names = target_grams(output, record['foodName'])
                except Exception as error:  # keep failures in the denominator
                    output, usage, seconds, grams, names = {'error': str(error)}, {}, None, None, []
                row[name].append(grams)
                raw.append({'portionGroup': record['portionGroup'], 'condition': name, 'repeat': i,
                            'grams': grams, 'matchedItems': names, 'output': output, 'usage': usage, 'seconds': seconds})
                print(f'    [{name}] #{i + 1}: {grams} g  {names}')
        rows.append(row)
    if args.dry_run:
        return
    table, agg = summarize(rows)
    out_dir.mkdir(parents=True, exist_ok=True)
    result = {'model': args.model, 'repeat': args.repeat, 'createdAt': datetime.now().isoformat(),
              'note': '같은 사진. photo=사진만, ar=앱과 동일한 깊이 요약 포함. currentApp=서버 검증을 통과하면 ar, 아니면 photo.',
              'aggregate': agg, 'table': table, 'records': rows}
    (out_dir / 'results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
    (out_dir / 'raw-outputs.json').write_text(json.dumps(raw, ensure_ascii=False, indent=2), encoding='utf8')
    print('\n기록        저울   사진만  깊이포함  현재앱경로')
    for t in table:
        f = lambda v: '   -  ' if v is None else f'{v:5.0f}%'
        print(f"{t['portionGroup']:<10}{t['scaleGrams']:5.0f}g  {f(t['photoRelErr'])}  {f(t['arRelErr'])}  {f(t['currentAppRelErr'])}")
    for k, v in agg.items():
        print(f"{k}: n={v['n']} 상대오차 중앙값={v['median']}")
    print('\n저장:', out_dir)


if __name__ == '__main__':
    main()
