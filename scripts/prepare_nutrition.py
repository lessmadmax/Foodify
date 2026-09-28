"""Read-only CSV normalization. Output is a reproducible JSONL import, not a workbook.
Run from Foodify: python scripts/prepare_nutrition.py
"""
import csv
import hashlib
import io
import json
import re
from collections import Counter, defaultdict
from decimal import Decimal, InvalidOperation
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NUTRIENTS = dict(kcal='에너지(kcal)', carbs='탄수화물(g)', protein='단백질(g)', fat='지방(g)')

def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode()).hexdigest()

def amount(text):
    match = re.fullmatch(r'\s*(\d+(?:\.\d+)?)\s*(g|ml)\s*', text, re.I)
    return (float(match[1]), match[2].lower()) if match and Decimal(match[1]) > 0 else (None, None)

def number(text):
    if not text.strip():
        return None
    try:
        value = Decimal(text)
        if not value.is_finite() or value < 0 or value >= Decimal('1000000000'):
            raise ValueError('out of range')
        if value.as_tuple().exponent < -3:
            raise ValueError('precision exceeds database scale')
        return float(value)
    except (InvalidOperation, ValueError) as error:
        raise ValueError(f'Invalid nutrient: {text}') from error

def prepare(raw_dir, out_dir):
    out_dir.mkdir(parents=True, exist_ok=True)
    datasets, foods, versions, sources = [], {}, {}, []
    candidates = defaultdict(set)
    report = {'datasets': [], 'conflicts': [], 'unknownBasis': 0}
    for path in sorted(raw_dir.glob('*.csv')):
        content = path.read_bytes()
        try:
            text, encoding = content.decode('utf-8-sig'), 'utf-8-sig'
        except UnicodeDecodeError:
            text, encoding = content.decode('cp949'), 'cp949'
        dataset_id = hashlib.sha256(content).hexdigest()
        reader = csv.DictReader(io.StringIO(text))
        required = {'식품코드', '식품명', '데이터구분명', '출처명', '데이터기준일자', *NUTRIENTS.values()}
        if not required <= set(reader.fieldnames or []):
            raise ValueError(f'Missing columns: {path.name}')
        count, seen, duplicates = 0, set(), 0
        for line, row in enumerate(reader, 2):
            if None in row or any(v is None for v in row.values()):
                raise ValueError(f'Malformed CSV: {path.name}:{line}')
            count += 1
            fingerprint = digest(row)
            duplicates += fingerprint in seen
            seen.add(fingerprint)
            code, name, category = row['식품코드'].strip(), row['식품명'].strip(), row['데이터구분명'].strip()
            if not code or not name or len(name) > 200:
                raise ValueError(f'Invalid identity: {path.name}:{line}')
            food_id = 'nf-' + digest([category, code])[:40]
            raw_basis = row.get('영양성분함량기준량', row.get('영양성분제공단위량', ''))
            basis, unit = amount(raw_basis)
            report['unknownBasis'] += basis is None
            version = dict(foodId=food_id, name=name, basisAmount=basis, basisUnit=unit,
                           rawBasis=raw_basis, source=row['출처명'], sourceDate=row['데이터기준일자'],
                           manufacturer=row.get('업체명') or row.get('제조사명', ''),
                           **{key:number(row[col]) for key,col in NUTRIENTS.items()})
            # Preserve serving metadata even when it differs across otherwise identical versions.
            portion_text = row.get('1인(회)분량 참고량') or row.get('1회 섭취참고량') or ''
            pa, pu = amount(portion_text)
            version['portion'] = {'amount':pa, 'unit':pu} if pa else None
            version_id = digest(version)
            versions[version_id] = dict(type='version', id=version_id, **version)
            candidates[food_id].add(version_id)
            foods[food_id] = dict(type='food', id=food_id, originalCode=code, category=category)
            sources.append(dict(type='sourceRow', datasetId=dataset_id, rowNumber=line,
                                versionId=version_id, raw=row))
        datasets.append(dict(type='dataset', id=dataset_id, filename=path.name, encoding=encoding, rowCount=count))
        report['datasets'].append(dict(file=path.name, rows=count, exactDuplicates=duplicates))
    for food_id, ids in candidates.items():
        latest_date = max(versions[i]['sourceDate'] for i in ids)
        latest = sorted(i for i in ids if versions[i]['sourceDate'] == latest_date)
        # Distinguish actual nutrient conflicts from metadata-only differences.
        signatures = {digest({k:versions[i][k] for k in ['name','basisAmount','basisUnit',*NUTRIENTS]}) for i in latest}
        conflict = len(signatures) > 1
        if conflict:
            report['conflicts'].append(dict(foodId=food_id, versions=latest))
        selected = versions[latest[0]]
        food = foods[food_id]
        food.update(currentVersionId=selected['id'], name=selected['name'], manufacturer=selected['manufacturer'],
                    aliases=selected['name'].replace('_', ' '),
                    searchable=not conflict and selected['basisUnit']=='g' and food['category']!='건강기능식품')
    report.update(foods=len(foods), versions=len(versions), sourceRows=len(sources),
                  searchable=sum(f['searchable'] for f in foods.values()),
                  searchableComplete=sum(f['searchable'] and all(versions[f['currentVersionId']][n] is not None for n in NUTRIENTS) for f in foods.values()))
    with (out_dir/'catalog.jsonl').open('w',encoding='utf-8',newline='\n') as handle:
        for entry in [*datasets,*foods.values(),*versions.values(),*sources]:
            handle.write(json.dumps(entry,ensure_ascii=False)+'\n')
    (out_dir/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='conflicts'},ensure_ascii=False))
    print('Conflicting latest versions:',len(report['conflicts']))
    return report

if __name__ == '__main__':
    import sys
    sys.stdout.reconfigure(encoding='utf-8')
    prepare(ROOT/'data/nutrition/raw', ROOT/'data/nutrition/processed')
