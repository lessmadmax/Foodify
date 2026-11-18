"""세 조건 결과 집계. API 표본별 중앙값과 반복 응답 통계를 분리한다."""
import csv
import json
import math
from pathlib import Path
import statistics
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import compare_weight_three_cases as experiment
import compare_api_weight as source

OUT = experiment.OUT


def finite(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) and 0 < v <= 10000


def metrics(pairs, total):
    errors = [abs(v-t) for v,t in pairs]
    return {'valid': len(pairs), 'total': total, 'coveragePct': len(pairs)/total*100,
            'maeGrams': statistics.mean(errors) if errors else None,
            'medianRelativeErrorPct': statistics.median(abs(v-t)/t*100 for v,t in pairs) if pairs else None}


if __name__ == '__main__':
    manifest = json.loads((OUT / 'manifest.json').read_text(encoding='utf-8'))
    foods = {r['portionGroup']: r['foodName'] for r in experiment.records()}
    samples = manifest['samples']
    review_path = OUT / 'target-item-review.json'
    review = json.loads(review_path.read_text(encoding='utf-8')) if review_path.exists() else {}
    result = {'samples': [], 'conditions': {}, 'apiResponses': 0, 'inputTokens': 0, 'outputTokens': 0,
              'httpOrParseErrors': 0, 'modelSnapshots': []}
    models = set()
    arithmetic = {r['sampleId']: r for r in csv.DictReader((OUT / 'arithmetic.tsv').open(encoding='utf-8'), delimiter='\t')}
    calls = {'api_photo': [], 'api_ar': []}
    for s in samples:
        group = s['sampleId'];truth = s['truthGrams']
        row = {'sampleId': group, 'foodName': foods[group], 'truthGrams': truth}
        for condition in calls:
            values, details = [], []
            for repeat in range(1,4):
                p = OUT / f'{group}__{condition}__{repeat}.json'
                if not p.exists():
                    raise SystemExit('Missing response: ' + str(p))
                r = json.loads(p.read_text(encoding='utf-8'));root=r.get('response', {})
                result['apiResponses'] += 1
                result['httpOrParseErrors'] += int('error' in r)
                usage=root.get('usage', {})
                result['inputTokens'] += usage.get('input_tokens',0)
                result['outputTokens'] += usage.get('output_tokens',0)
                if root.get('model'): models.add(root['model'])
                parsed = r.get('parsed', {})
                keyword_grams, names = source.target_grams(parsed, foods[group])
                if p.stem in review:
                    index = review[p.stem]['targetIndex']
                    target = parsed.get('items', [])[index] if index is not None else None
                    grams = target.get('grams') if target else None
                    names = [target.get('name')] if target else []
                else:
                    grams = keyword_grams
                grams = grams if finite(grams) else None
                values.append(grams)
                calls[condition].append((grams,truth,r['elapsedMs']))
                details.append({'grams':grams,'matchedNames':names,'keywordGrams':keyword_grams,
                                'review':review.get(p.stem),'items':parsed.get('items',[]),'error':r.get('error')})
            valid=[v for v in values if v is not None]
            row[condition]={'valuesGrams':values,'medianGrams':statistics.median(valid) if valid else None,
                            'rangeGrams':[min(valid),max(valid)] if valid else None,'details':details}
        ar=arithmetic[group]
        row['arithmetic']={'status':ar['status'],'grams':float(ar['grams']) if ar['status']=='ESTIMATE' else None,
                           'reason':ar['reason']}
        result['samples'].append(row)
        print(group,truth, row['api_photo']['valuesGrams'],row['api_ar']['valuesGrams'], row['arithmetic']['grams'])
    result['modelSnapshots']=sorted(models)
    result['estimatedUsdNoCacheDiscount']=result['inputTokens']*.15/1e6+result['outputTokens']*.60/1e6
    for c in ('api_photo','api_ar','arithmetic'):
        get=lambda r:r[c]['grams'] if c=='arithmetic' else r[c]['medianGrams']
        pairs=[(get(r),r['truthGrams']) for r in result['samples'] if get(r) is not None]
        result['conditions'][c]=metrics(pairs,len(samples))
        if c in calls:
            result['conditions'][c]['perCall']=metrics([(v,t) for v,t,_ in calls[c] if v is not None],len(calls[c]))
            result['conditions'][c]['meanElapsedMs']=statistics.mean(ms for _,_,ms in calls[c])
    common=[r for r in result['samples'] if r['arithmetic']['grams'] is not None and
            r['api_photo']['medianGrams'] is not None and r['api_ar']['medianGrams'] is not None]
    result['commonSamples']=[r['sampleId'] for r in common]
    result['commonMetrics']={c:metrics([((r[c]['grams'] if c=='arithmetic' else r[c]['medianGrams']),r['truthGrams'])
                                      for r in common],len(common)) for c in result['conditions']} if common else {}
    (OUT/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k!='samples'},ensure_ascii=False,indent=2))
