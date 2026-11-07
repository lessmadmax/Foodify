"""M03: API weight estimates with the food name fixed (recognition errors removed).

Reuses the exact request bodies of the earlier 11-record comparison
(data/private/ar/api-compare-requests/*.json, built by compare_api_weight.py from the
server prompt) and only inserts one extra text part right after the task text that
fixes the user-confirmed food name. Everything else (photo, AR summary, schema,
instructions, model) is identical, so the difference from summary.json isolates the
effect of recognition errors on weight.

Usage (project root):
    python scripts/compare_api_weight_fixed_name.py run      # API calls, resumable
    python scripts/compare_api_weight_fixed_name.py score    # summary + comparison
    python scripts/compare_api_weight_fixed_name.py dry-run  # print the inserted text only

Standard library only. Outputs go to data/private/ar/api-compare-fixed-name/ (Git-excluded).
"""
import copy
import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQ = ROOT / 'data' / 'private' / 'ar' / 'api-compare-requests'
OUT = ROOT / 'data' / 'private' / 'ar' / 'api-compare-fixed-name'
REPEAT = 3


def fixed_name_text(name):
    # The same sentence the server will send once M05 passes a confirmed name.
    return (f"사용자가 확인한 음식명: {name}. 사진 속 대상 음식은 이 한 가지입니다. "
            f"items는 1개만 반환하고 name은 '{name}'로 그대로 쓰세요. 음식 종류를 다시 판단하지 말고, "
            "사진과 측정 근거로 이 음식의 실제 보이는 양에 대한 grams·weightEstimate·assumptions만 추정하세요.")


def build(body, name):
    body = copy.deepcopy(body)
    parts = body['input'][0]['content']
    parts.insert(1, {'type': 'input_text', 'text': fixed_name_text(name)})
    return body


def call(body, key):
    data = json.dumps(body, ensure_ascii=False).encode('utf8')
    for attempt in range(4):
        request = urllib.request.Request('https://api.openai.com/v1/responses', data=data, method='POST',
                                         headers={'Authorization': 'Bearer ' + key,
                                                  'Content-Type': 'application/json'})
        try:
            start = time.time()
            with urllib.request.urlopen(request, timeout=120) as response:
                root = json.loads(response.read().decode('utf8'))
            return root, round((time.time() - start) * 1000)
        except urllib.error.HTTPError as error:
            if error.code in (429, 500, 502, 503, 504) and attempt < 3:
                time.sleep(5 * (attempt + 1))
                continue
            return {'error': f'HTTP {error.code}'}, None
        except (urllib.error.URLError, TimeoutError) as error:
            if attempt < 3:
                time.sleep(5 * (attempt + 1))
                continue
            return {'error': type(error).__name__}, None


def parsed_items(root):
    for message in root.get('output', []):
        for content in message.get('content', []):
            if content.get('type') == 'output_text':
                return json.loads(content['text']).get('items', [])
    return None


def run(dry):
    manifest = json.loads((REQ / 'manifest.json').read_text(encoding='utf8'))
    key = os.environ.get('OPENAI_API_KEY', '')
    if not dry and not key:
        sys.exit('OPENAI_API_KEY 환경변수가 없습니다.')
    (OUT / 'responses').mkdir(parents=True, exist_ok=True)
    total, n = len(manifest) * REPEAT, 0
    for entry in manifest:
        body = build(json.loads((REQ / entry['file']).read_text(encoding='utf8')), entry['foodName'])
        if dry:
            print(entry['file'], '->', body['input'][0]['content'][1]['text'])
            continue
        stem = Path(entry['file']).stem
        for i in range(REPEAT):
            n += 1
            target = OUT / 'responses' / f'{stem}__{i}.json'
            if target.exists():
                print(f'[{n}/{total}] {stem} #{i} 이미 있음')
                continue
            root, ms = call(body, key)
            target.write_text(json.dumps({'elapsedMs': ms, 'response': root}, ensure_ascii=False), encoding='utf8')
            items = parsed_items(root) if not root.get('error') else None
            print(f'[{n}/{total}] {stem} #{i} {ms}ms', [(x.get("name"), x.get("grams")) for x in items or []])


def rel(values, truth):
    values = [v for v in values if v is not None]
    return None if not values else abs(statistics.median(values) - truth) / truth * 100


def agg(values):
    values = [v for v in values if v is not None]
    return {'n': len(values), 'median': round(statistics.median(values), 1) if values else None,
            'mean': round(statistics.mean(values), 1) if values else None}


def score():
    manifest = json.loads((REQ / 'manifest.json').read_text(encoding='utf8'))
    old = json.loads((REQ / 'summary.json').read_text(encoding='utf8'))
    groups = {}
    for entry in manifest:
        stem = Path(entry['file']).stem
        g = groups.setdefault(entry['portionGroup'], {'food': entry['foodName'], 'w': entry['scaleGrams'],
                                                      'app': entry['appWouldSendDepth'], 'photo': [], 'ar': [],
                                                      'ranges': {'photo': [], 'ar': []}, 'names': set(),
                                                      'tokens': 0})
        for i in range(REPEAT):
            path = OUT / 'responses' / f'{stem}__{i}.json'
            if not path.exists():
                g[entry['condition']].append(None)
                continue
            wrapped = json.loads(path.read_text(encoding='utf8'))
            root = wrapped['response']
            items = parsed_items(root) if not root.get('error') else None
            grams = sum(x['grams'] for x in items if x.get('grams')) if items else None
            if items and not any(x.get('grams') for x in items):
                grams = None
            g[entry['condition']].append(grams)
            g['tokens'] += (root.get('usage') or {}).get('total_tokens', 0)
            for x in items or []:
                g['names'].add(x.get('name'))
                est = x.get('weightEstimate') or {}
                if est.get('lowerGrams') is not None and est.get('upperGrams') is not None:
                    g['ranges'][entry['condition']].append((est['lowerGrams'], est['upperGrams']))
    table = []
    for name, g in groups.items():
        o = old[name]
        # old run counted a weight only when the item name matched the food (recognition correct)
        old_recognized = sum(v is not None for v in o['photo'] + o['ar'])
        row = {'group': name, 'scaleGrams': g['w'], 'appSendsDepth': g['app'],
               'fixedPhoto': g['photo'], 'fixedAr': g['ar'], 'oldPhoto': o['photo'], 'oldAr': o['ar'],
               'fixedPhotoErr': rel(g['photo'], g['w']), 'fixedArErr': rel(g['ar'], g['w']),
               'oldPhotoErr': rel(o['photo'], g['w']), 'oldArErr': rel(o['ar'], g['w']),
               'oldRecognizedRuns': old_recognized,
               'rangeHits': sum(lo <= g['w'] <= hi for c in ('photo', 'ar') for lo, hi in g['ranges'][c]),
               'rangeCount': sum(len(g['ranges'][c]) for c in ('photo', 'ar')),
               'namesReturned': sorted(n for n in g['names'] if n), 'tokens': g['tokens']}
        row['fixedAppErr'] = row['fixedArErr'] if g['app'] else row['fixedPhotoErr']
        row['oldAppErr'] = row['oldArErr'] if g['app'] else row['oldPhotoErr']
        allvals = [v for v in g['photo'] + g['ar'] if v is not None]
        row['spreadGrams'] = (min(allvals), max(allvals)) if allvals else None
        table.append(row)
    summary = {k: agg([r[k] for r in table]) for k in
               ('fixedPhotoErr', 'fixedArErr', 'fixedAppErr', 'oldPhotoErr', 'oldArErr', 'oldAppErr')}
    by_food = {}
    for r in table:
        f = r['group'].split('-')[0]
        by_food.setdefault(f, {'fixedPhotoErr': [], 'fixedArErr': [], 'oldPhotoErr': [], 'oldArErr': []})
        for k in by_food[f]:
            by_food[f][k].append(r[k])
    summary['byFood'] = {f: {k: agg(v) for k, v in d.items()} for f, d in by_food.items()}
    hits = sum(r['rangeHits'] for r in table)
    count = sum(r['rangeCount'] for r in table)
    summary['rangeContainsScale'] = {'hits': hits, 'count': count}
    summary['nullRuns'] = sum(v is None for r in table for v in r['fixedPhoto'] + r['fixedAr'])
    summary['totalTokens'] = sum(r['tokens'] for r in table)
    (OUT / 'summary.json').write_text(json.dumps({'summary': summary, 'table': table}, ensure_ascii=False,
                                                 indent=1), encoding='utf8')
    f = lambda v: '    -' if v is None else f'{v:4.0f}%'
    print('기록         저울  | 고정 사진  고정 깊이 | 기존 사진  기존 깊이 | 반환 g (사진 / 깊이)')
    for r in table:
        print(f"{r['group']:<10}{r['scaleGrams']:5.0f}g | {f(r['fixedPhotoErr'])}     {f(r['fixedArErr'])}    | "
              f"{f(r['oldPhotoErr'])}     {f(r['oldArErr'])}    | {r['fixedPhoto']} / {r['fixedAr']}")
    print(json.dumps(summary, ensure_ascii=False, indent=1))


if __name__ == '__main__':
    mode = sys.argv[1] if len(sys.argv) > 1 else ''
    if mode == 'run':
        run(False)
    elif mode == 'dry-run':
        run(True)
    elif mode == 'score':
        score()
    else:
        sys.exit(__doc__)
