"""Compare diet-feedback generation: OpenAI (server path) vs Gemma 4 E2B (local).

Scenarios use the exact evidence JSON the server builds (AppController.evidence:
summary + revisions + goals) and the exact feedback prompt parsed from OpenAiClient.java.

    python scripts/eval_feedback_llm.py scenarios          # write the 20 scenarios
    python scripts/eval_feedback_llm.py gemma [gpu|cpu]    # local Gemma (PC, LiteRT-LM, GPU first)
    python scripts/eval_feedback_llm.py api                # OpenAI, needs OPENAI_API_KEY
    python scripts/eval_feedback_llm.py score              # automatic checks + review sheet

Results: data/private/feedback-eval/ (Git-excluded). Standard library only, except
litert_lm for the gemma step (bundled under data/private/gemma4/python).
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'data' / 'private' / 'feedback-eval'
JAVA = ROOT / 'backend/src/main/java/com/example/foodify/ai/OpenAiClient.java'
GEMMA_DIR = ROOT / 'data' / 'private' / 'gemma4'
GEMMA_MODEL = GEMMA_DIR / 'gemma-4-E2B-it.litertlm'
NUTRIENTS = ['kcal', 'carbs', 'protein', 'fat']
GOAL = {'kcal': 2000, 'carbs': 250, 'protein': 100, 'fat': 67}
CALCULATION = {'version': 'mifflin-maintenance-v1', 'activityFactor': 1.55, 'restingKcal': 1290,
               'macroPercent': {'carbs': 50, 'protein': 20, 'fat': 30},
               'note': '만 19~78세 일반 성인의 체중 유지 참고 추정치. 탄·단·지 50:20:30은 앱 기본 배분입니다.'}
JSON_FORMAT = ('\n\n설명이나 코드 블록 없이 다음 형식의 JSON만 출력하세요: '
               '{"summary":"한국어 요약","suggestions":["다음 식사 선택지", "..."]} '
               'suggestions는 최대 10개입니다.')
MEDICAL = ['진단', '처방', '복용', '약물', '당뇨', '고혈압', '고지혈', '질환', '질병', '치료', '병원에', '의사의 처방']
MISSING_WORDS = ['누락', '미완료', '기록되지', '기록이 없', '기록하지', '완료되지', '빠진', '확인 필요', '입력되지']


def java_string(literal):
    return json.loads('"' + literal + '"')


def server_prompt():
    src = JAVA.read_text(encoding='utf8')
    line = next(l for l in src.splitlines() if 'call("FEEDBACK"' in l)
    return java_string(re.search(r'"text","((?:[^"\\]|\\.)*)"\+evidence', line).group(1))


def evidence(sid, days, totals, complete, incomplete, goals=True, note=''):
    from_day, to_day = '2026-11-04', '2026-11-04'
    if days == 7:
        from_day = '2026-10-29'
    comparison = {}
    if goals:
        for n in NUTRIENTS:
            target = GOAL[n] * days
            comparison[n] = {'target': target, 'difference': round(totals[n] - target, 2),
                             'ratio': round(totals[n] / target, 3)}
    summary = {'from': from_day, 'to': to_day, 'totals': {n: round(totals[n], 2) for n in NUTRIENTS},
               'completedMeals': complete, 'incompleteMeals': incomplete, 'comparison': comparison}
    revisions = [{'id': f'meal-{sid}-{i}', 'version': 2, 'status': 'COMPLETE' if i < complete else 'INCOMPLETE'}
                 for i in range(complete + incomplete)]
    goal = {**GOAL, 'calculation': CALCULATION} if goals else {}
    return {'id': sid, 'days': days, 'note': note,
            'evidence': {'summary': summary, 'revisions': revisions, 'goals': goal}}


def scale(r, days=1, **override):
    """Per-day ratio r of the goal; overrides are per-day values. Totals cover all days."""
    t = {n: GOAL[n] * days * r for n in NUTRIENTS}
    t.update({k: v * days for k, v in override.items()})
    return t


def scenarios():
    z = {n: 0 for n in NUTRIENTS}
    return [
        evidence('S01', 1, scale(1.0), 3, 0, note='목표와 거의 일치'),
        evidence('S02', 1, scale(1.35), 3, 0, note='열량 35% 초과'),
        evidence('S03', 1, scale(0.5), 2, 0, note='열량 50% 부족'),
        evidence('S04', 1, scale(1.0, protein=55), 3, 0, note='단백질 부족'),
        evidence('S05', 1, scale(1.05, fat=110), 3, 0, note='지방 과다'),
        evidence('S06', 1, scale(1.1, carbs=360), 3, 0, note='탄수화물 과다'),
        evidence('S07', 1, scale(0.6), 2, 1, note='미완료 1끼 존재(실제 섭취 더 많을 수 있음)'),
        evidence('S08', 1, z, 0, 0, note='기록 없음(섭취 0과 구분해야 함)'),
        evidence('S09', 1, z, 0, 2, note='미완료만 존재'),
        evidence('S10', 1, scale(0.95), 3, 0, goals=False, note='목표 미설정'),
        evidence('S11', 1, scale(0.35), 1, 0, note='하루 1끼만 기록'),
        evidence('S12', 1, scale(2.0), 4, 0, note='열량 2배'),
        evidence('S13', 7, scale(1.0, days=7), 21, 0, note='7일 목표 근접'),
        evidence('S14', 7, scale(1.2, days=7), 19, 2, note='7일 20% 초과, 미완료 2'),
        evidence('S15', 7, scale(0.7, days=7), 14, 0, note='7일 30% 부족(기록 적음)'),
        evidence('S16', 7, scale(0.9, days=7), 10, 8, note='7일 미완료 다수'),
        evidence('S17', 7, scale(1.0, protein=60, fat=95, days=7), 21, 0, note='7일 단백질 부족·지방 과다'),
        evidence('S18', 7, scale(0.4, days=7), 6, 0, goals=False, note='7일 목표 미설정·기록 적음'),
        evidence('S19', 7, scale(1.05, carbs=330, protein=80, days=7), 20, 1, note='7일 탄수화물 과다'),
        evidence('S20', 1, scale(0.85, protein=110), 3, 0, note='열량 약간 부족·단백질 충분'),
    ]


def build_prompt(item, strict_api):
    text = server_prompt() + json.dumps(item['evidence'], ensure_ascii=False, separators=(',', ':'))
    return text if strict_api else text + JSON_FORMAT


def parse(text):
    text = text.strip()
    fence = chr(96) * 3
    if text.startswith(fence):
        text = text.split('\n', 1)[1] if '\n' in text else text[3:]
        text = text.rsplit(fence, 1)[0]
    data = json.loads(text)
    if (not isinstance(data, dict) or not isinstance(data.get('summary'), str)
            or not isinstance(data.get('suggestions'), list) or len(data['suggestions']) > 10
            or not all(isinstance(s, str) for s in data['suggestions'])):
        raise ValueError('summary/suggestions 형식 불일치')
    return data


def load_scenarios():
    path = OUT / 'scenarios.json'
    if not path.exists():
        OUT.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(scenarios(), ensure_ascii=False, indent=1), encoding='utf8')
    return json.loads(path.read_text(encoding='utf8'))


def run_gemma(backend='gpu'):
    """GPU first (PC Intel/DirectX via LiteRT-LM); falls back to CPU if the GPU engine cannot start."""
    sys.path.insert(0, str(GEMMA_DIR / 'python'))
    import litert_lm
    litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)
    items = load_scenarios()
    (GEMMA_DIR / 'cache').mkdir(exist_ok=True)
    order = ['gpu', 'cpu'] if backend == 'gpu' else ['cpu']
    engine, used, failures, startup = None, None, [], None
    for name in order:
        try:
            started = time.perf_counter()
            engine = litert_lm.Engine(str(GEMMA_MODEL),
                                      backend=litert_lm.Backend.GPU() if name == 'gpu' else litert_lm.Backend.CPU(),
                                      max_num_tokens=4096, cache_dir=str(GEMMA_DIR / 'cache'))
            engine.__enter__()
            startup, used = round(time.perf_counter() - started, 3), name
            break
        except Exception as error:  # record and try the next backend
            failures.append({'backend': name, 'error': str(error)})
            print(f'{name.upper()} 시작 실패: {error}', flush=True)
            engine = None
    if engine is None:
        sys.exit('Gemma 엔진을 시작하지 못했습니다.')
    print(f'Gemma 준비 ({used.upper()}) {startup}s', flush=True)
    results = []
    try:
        for item in items:
            with engine.create_conversation(
                    max_output_tokens=1024, automatic_tool_calling=False,
                    thinking_config=litert_lm.ThinkingConfig(enable_thinking=False, thinking_token_budget=0),
                    sampler_config=litert_lm.SamplerConfig(top_k=1, temperature=0, seed=42)) as conversation:
                tick = time.perf_counter()
                response = conversation.send_message(litert_lm.Contents.of(build_prompt(item, False)))
                seconds = round(time.perf_counter() - tick, 3)
            text = ''.join(p.get('text', '') for p in response.get('content', []) if p.get('type') == 'text')
            results.append({'id': item['id'], 'seconds': seconds, 'raw': text})
            print(f"{item['id']} {seconds}s", flush=True)
    finally:
        engine.__exit__(None, None, None)
    save('gemma', {'model': f'gemma-4-E2B-it (LiteRT-LM, PC {used.upper()})', 'backend': used,
                   'backendFailures': failures, 'startupSeconds': startup, 'results': results})


def run_api():
    key = os.environ.get('OPENAI_API_KEY', '')
    if not key:
        sys.exit('OPENAI_API_KEY 환경변수가 필요합니다.')
    model = os.environ.get('OPENAI_MODEL', 'gpt-4o-mini')
    src = JAVA.read_text(encoding='utf8')
    instructions = java_string(re.search(r'"instructions","((?:[^"\\]|\\.)*)"', src).group(1))
    schema = {'type': 'object', 'additionalProperties': False,
              'properties': {'summary': {'type': 'string'},
                             'suggestions': {'type': 'array', 'items': {'type': 'string'}}},
              'required': ['summary', 'suggestions']}
    results = []
    for item in load_scenarios():
        body = {'model': model, 'store': False, 'max_output_tokens': 2500, 'instructions': instructions,
                'input': [{'role': 'user', 'content': [{'type': 'input_text', 'text': build_prompt(item, True)}]}],
                'text': {'format': {'type': 'json_schema', 'name': 'foodify_feedback', 'strict': True, 'schema': schema}}}
        text, seconds, error = '', None, None
        for attempt in range(4):
            try:
                req = urllib.request.Request('https://api.openai.com/v1/responses',
                                             data=json.dumps(body, ensure_ascii=False).encode('utf8'),
                                             headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
                tick = time.perf_counter()
                with urllib.request.urlopen(req, timeout=90) as r:
                    root = json.loads(r.read().decode('utf8'))
                seconds = round(time.perf_counter() - tick, 3)
                text = ''.join(c.get('text', '') for o in root.get('output', []) for c in o.get('content', [])
                               if c.get('type') == 'output_text')
                break
            except urllib.error.HTTPError as e:
                error = f'HTTP {e.code}'
                if e.code in (429, 500, 502, 503, 504) and attempt < 3:
                    time.sleep(5 * (attempt + 1))
                    continue
                break
        results.append({'id': item['id'], 'seconds': seconds, 'raw': text, 'error': error if not text else None})
        print(f"{item['id']} {seconds}s {error or ''}", flush=True)
    save('api', {'model': model, 'results': results})


def save(name, data):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f'{name}.json').write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding='utf8')
    print('저장:', OUT / f'{name}.json')


def allowed_numbers(item):
    s = item['evidence']['summary']
    nums = {float(item['days']), float(s['completedMeals']), float(s['incompleteMeals']),
            float(s['completedMeals'] + s['incompleteMeals'])}
    for n in NUTRIENTS:
        t = s['totals'][n]
        nums |= {t, t / item['days']}
        if n in s['comparison']:
            c = s['comparison'][n]
            nums |= {c['target'], abs(c['difference']), c['ratio'] * 100, abs(c['ratio'] * 100 - 100),
                     c['target'] / item['days'], abs(c['difference']) / item['days']}
    goals = item['evidence']['goals']
    if goals:
        nums |= {float(goals[n]) for n in NUTRIENTS} | {50.0, 20.0, 30.0}
    return nums


def numbers_in(text):
    out = []
    for m in re.finditer(r'(?<![\d.])(\d{1,3}(?:,\d{3})+|\d+(?:\.\d+)?)', text):
        out.append(float(m.group(1).replace(',', '')))
    return out


def unsupported(text, allowed):
    bad = []
    for v in numbers_in(text):
        if v <= 10 and v == int(v):
            continue  # counts like "2끼", "3가지" are not checked
        if not any(abs(v - a) <= max(1.0, abs(a) * 0.02) for a in allowed):
            bad.append(v)
    return bad


def check(item, raw):
    row = {'jsonValid': False, 'unsupportedInSummary': [], 'unsupportedInSuggestions': [],
           'medicalTerms': [], 'missingMentioned': None, 'noGoalHandled': None, 'zeroVsMissing': None}
    try:
        data = parse(raw)
    except Exception as e:  # malformed output counts as a failure
        row['error'] = str(e)
        return row, None
    row['jsonValid'] = True
    allowed = allowed_numbers(item)
    whole = data['summary'] + ' ' + ' '.join(data['suggestions'])
    row['unsupportedInSummary'] = unsupported(data['summary'], allowed)
    row['unsupportedInSuggestions'] = unsupported(' '.join(data['suggestions']), allowed)
    row['medicalTerms'] = [w for w in MEDICAL if w in whole]
    s = item['evidence']['summary']
    if s['incompleteMeals'] > 0 or s['completedMeals'] == 0:
        row['missingMentioned'] = any(w in whole for w in MISSING_WORDS)
    if not item['evidence']['goals']:
        row['noGoalHandled'] = any(w in whole for w in ['목표가 설정되지', '목표를 설정', '목표 미설정', '목표가 없', '목표 설정', '목표는 명시', '목표가 명시', '명시되어 있지 않', '목표 정보가 없'])
    if s['completedMeals'] == 0:
        row['zeroVsMissing'] = not re.search(r'(?<![\d,.])0\s*kcal|(?<![\d,.])0\s*칼로리|총량이 0|합계가 0|(?<![\d,.])0이며|아무것도 (먹지|드시지)', whole)
    return row, data


def score():
    items = {i['id']: i for i in load_scenarios()}
    report, sheet = {}, ['# 피드백 비교 검토표', '', '자동 검사 결과와 함께 자연스러움·실용성은 직접 판단합니다.', '']
    runs = {}
    for name in ('api', 'gemma'):
        p = OUT / f'{name}.json'
        if p.exists():
            runs[name] = {r['id']: r for r in json.loads(p.read_text(encoding='utf8'))['results']}
    for name, results in runs.items():
        rows = {}
        for sid, item in items.items():
            row, _ = check(item, results.get(sid, {}).get('raw', ''))
            row['seconds'] = results.get(sid, {}).get('seconds')
            rows[sid] = row
        n = len(rows)
        flags = lambda key: sum(1 for r in rows.values() if r[key] is False)
        applicable = lambda key: sum(1 for r in rows.values() if r[key] is not None)
        secs = sorted(r['seconds'] for r in rows.values() if r['seconds'])
        report[name] = {
            'jsonValid': f"{sum(r['jsonValid'] for r in rows.values())}/{n}",
            'summaryWithUnsupportedNumbers': sum(1 for r in rows.values() if r['unsupportedInSummary']),
            'suggestionsWithUnsupportedNumbers': sum(1 for r in rows.values() if r['unsupportedInSuggestions']),
            'medicalTermHits': sum(1 for r in rows.values() if r['medicalTerms']),
            'missingRecordMentioned': f"{applicable('missingMentioned') - flags('missingMentioned')}/{applicable('missingMentioned')}",
            'noGoalHandled': f"{applicable('noGoalHandled') - flags('noGoalHandled')}/{applicable('noGoalHandled')}",
            'medianSeconds': secs[len(secs) // 2] if secs else None,
            'rows': rows}
    for sid, item in items.items():
        sheet += [f"## {sid} · {item['note']}", '']
        for name, results in runs.items():
            raw = results.get(sid, {}).get('raw', '')
            row = report[name]['rows'][sid]
            try:
                data = parse(raw)
                body = data['summary'] + '\n' + '\n'.join('- ' + s for s in data['suggestions'])
            except Exception:
                body = '(형식 오류) ' + raw[:500]
            marks = []
            if row['unsupportedInSummary']:
                marks.append(f"근거 없는 수치(요약): {row['unsupportedInSummary']}")
            if row['medicalTerms']:
                marks.append(f"의료 표현: {row['medicalTerms']}")
            if row['missingMentioned'] is False:
                marks.append('누락·미완료 언급 없음')
            if row['noGoalHandled'] is False:
                marks.append('목표 미설정 안내 없음')
            sheet += [f"**{name}** ({row['seconds']}s) {' / '.join(marks)}", '', body, '']
    (OUT / 'score.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding='utf8')
    (OUT / 'review.md').write_text('\n'.join(sheet), encoding='utf8')
    for name, r in report.items():
        print(name, {k: v for k, v in r.items() if k != 'rows'})
    print('검토표:', OUT / 'review.md')


if __name__ == '__main__':
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, 'reconfigure'):
            stream.reconfigure(encoding='utf-8')
    step = sys.argv[1] if len(sys.argv) > 1 else ''
    {'scenarios': lambda: print(len(load_scenarios()), '개 시나리오:', OUT / 'scenarios.json'),
     'gemma': lambda: run_gemma(sys.argv[2] if len(sys.argv) > 2 else 'gpu'), 'api': run_api, 'score': score}.get(step, lambda: sys.exit(__doc__))()
