"""Draw the current capture-to-result data flow, without changing app code."""
from pathlib import Path
import os, json, html, re
from PIL import ImageFont

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs'/'diagrams'
OUT.mkdir(exist_ok=True)
FONT=ImageFont.truetype(r'C:\Windows\Fonts\malgun.ttf',18)
def esc(s):return html.escape(s,quote=True)
def lines(s,width=400):
    result=[]
    for para in s.split('\n'):
        current=''
        for c in para:
            if FONT.getlength(current+c)>width:
                result.append(current);current=c
            else:current+=c
        result.append(current)
    return result

parts=[]
def text(x,y,s,size=18,cls='',weight=None):
    extra=f' font-weight="{weight}"' if weight else ''
    parts.append(f'<text x="{x}" y="{y}" font-size="{size}" class="{cls}"{extra}>{esc(s)}</text>')
def multiline(x,y,s,cls='',width=400):
    arr=lines(s,width)
    for i,line in enumerate(arr):text(x,y+25*i,line,18,cls)
    return y+25*len(arr)
def card(x,y,num,title,role,inp,out,note='',kind='device',h=258):
    parts.append(f'<g class="node {kind}"><rect x="{x}" y="{y}" width="460" height="{h}" rx="15"/>')
    parts.append(f'<rect class="stripe" x="{x}" y="{y}" width="7" height="{h}" rx="3"/>')
    text(x+22,y+31,f'{num}  {title}',23,'title',700)
    text(x+22,y+58,role,16,'sub')
    text(x+22,y+89,'입력',16,'label',700)
    yy=multiline(x+68,y+89,inp,width=368)
    yy+=10
    text(x+22,yy,'출력',16,'label',700)
    yy=multiline(x+68,yy,out,width=368)
    if note:
        yy+=9
        for line in lines(note,415):text(x+22,yy,line,16,'sub');yy+=23
    if yy>y+h-6:raise ValueError(f'Overflow {num}: {yy} > {y+h}')
    parts.append('</g>')
def arrow(d,dashed=False,label='',x=0,y=0):
    dash=' stroke-dasharray="8 6"' if dashed else ''
    parts.append(f'<path class="connector" d="{d}" marker-end="url(#arrow)"{dash}/>')
    if label:text(x,y,label,17,'flow-label',600)

W,H=1540,1840
parts.append('<rect class="canvas" width="1540" height="1840"/>')
text(40,44,'Foodify 사진 입력에서 결과 산출까지',32,'title',700)
text(40,78,'2026년 11월 12일 코드 기준  ·  각 단계의 입력과 출력  ·  단위 g / cm / cm² / mL',18,'sub')
text(40,124,'A. 최초 음식 사진 분석',25,'section',700)
text(40,154,'음식명·기본 중량과 영양 결과를 먼저 산출합니다. AR 측정은 이 결과에서 음식별로 선택합니다.',18,'sub')

# General photo flow: first row left-to-right, second row right-to-left.
xs=[40,540,1040]; y1,y2=181,500
card(xs[0],y1,'A1','음식 사진 촬영·선택','모바일 · 카메라 / 갤러리',
     '촬영 대상 장면 또는 갤러리 이미지',
     '사진 1~3장, 사진 경로 paths[]\n촬영 정보 captureInfo')
card(xs[1],y1,'A2','음식 분류 후보 산출','기기 · Food-101-93M / ONNX',
     '사진별 RGB 이미지 → 224×224',
     'foodRecognition.predictions[]\n사진별 {label, score} 상위 3개',
     '분류 실패 시 후보 없이 사진 분석을 계속합니다.')
card(xs[2],y1,'A3','업로드·검증·분석 접수','서버 · POST /api/v1/meals',
     '사진 + captureInfo + 멱등 키',
     '정리된 JPEG, 검증된 촬영 근거\n식단 ID · 분석 작업 QUEUED',
     'Top-1 < 0.6 후보는 AI 입력에서 제외합니다.',kind='server')
card(xs[2],y2,'A4','음식명·기본 중량 추정','외부 AI · 기본 gpt-4o-mini',
     '사진 + 유효 음식 후보 + 촬영 근거',
     'items[]: name, grams, ingredients\nweightEstimate{범위·가정}, uncertainty',
     'grams는 참고 추정값이며 판단 불가 시 null입니다.',kind='ai')
card(xs[1],y2,'A5','영양 DB 음식 연결','서버 · 검색 / 후보 선택 / 사용자 보정',
     '음식명 name, 재료 ingredients',
     'foodId · 식품 대분류 · 자료원\n기준 중량과 기준 영양값',
     '정확 일치 → 원재료·범주 → 포함 검색',kind='server')
card(xs[0],y2,'A6','영양 계산·결과 표시','서버 → 앱 · 자동 저장 후 확인·수정',
     'grams + foodId + DB 기준 영양값',
     '음식별 중량·kcal·탄수화물·단백질·지방\n계산 근거 · 항목/식단 완료 상태',
     '중량·DB·필수 영양값을 갖추면 완료 처리합니다.',kind='result')
arrow('M500 310 H534')
arrow('M1000 310 H1034')
arrow('M1270 439 V494',label='정리된 사진 + 유효 후보',x=1040,y=477)
arrow('M1040 629 H1006')
arrow('M540 629 H506')

# Optional AR route: carries the selected food's name/id from the initial result.
arrow('M270 758 V785 H20 V892 H270 V899',True,label='선택: 결과 화면에서 음식명·foodId를 전달하여 AR 중량 측정',x=300,y=809)
text(40,849,'B. 선택한 음식의 AR 중량 재산출',25,'section',700)
text(40,878,'별도 촬영 → 윤곽 확인 → 면적·높이 → 분류 밀도 환산 → 기존 영양 결과 갱신',18,'sub')
y3,y4=905,1270
card(xs[0],y3,'B1','AR 촬영·데이터 수집','기기 · ARCore / 약 5초 촬영',
     '선택 음식명·foodId + 현재 촬영 장면',
     '대표 사진, 프레임별 깊이(mm)·신뢰도\n카메라 자세·내부 파라미터 K\n특징점 · 타임스탬프',
     '음식명·foodId는 이후 중량 환산까지 유지합니다.',h=287)
card(xs[1],y3,'B2','음식 영역 분할·사용자 확인','기기 · MobileSAM + 사용자',
     '대표 사진 + 음식/제외 점(u,v,label)',
     'foodPolygons[] · reviewed=true\n사용자가 확인한 음식 윤곽',
     '손으로 직접 그린 윤곽도 사용할 수 있습니다.',h=287)
card(xs[2],y3,'B3','깊이 정렬·면적·높이 계산','기기 · ArFusion / ArVolume',
     '확인 윤곽 + 프레임별 깊이·신뢰도\n카메라 자세·K + 특징점·접시 영역',
     'areaCm2 · distanceMm · mmPerPixel\n사진 실제 크기 · measuredHeightCm?\n높이 출처 DEPTH / FEATURE_POINTS',
     '거리 실패: 재촬영 / 높이 실패: AI 대체',h=287)

card(xs[2],y4,'B4','적용할 평균 높이 결정','기기 높이 우선 / 측정 불가 시 AI 대체',
     '기기 높이 존재 → /ar/preview\n없음 → 사진·음식명·면적·축척\n/ar/height: AI 평균 높이 추정 3회',
     'heightCm · heightMethod\n기기 표본 수 또는 AI 높이 응답 목록',
     'AI는 성공 응답의 중앙값을 사용합니다.',kind='ai',h=287)
card(xs[1],y4,'B5','부피·중량 미리보기 계산','서버 · MealService / DensityGroups',
     '음식명·foodId + A(cm²) + h(cm)',
     '분류별 밀도 ρ(g/mL)\n부피 V=A×h(mL)\n중량 W=V×ρ(g)',
     '기기 높이 경로에서는 외부 AI 호출이 없습니다.',kind='server',h=287)
card(xs[0],y4,'B6','중량 적용·영양 결과 갱신','서버 → 앱 · PATCH /api/v1/meals/{id}',
     'arMeasurement{A,h,높이 방식·근거}\n선택 음식명·foodId · 식단 버전',
     '서버 재계산 grams · nutrition\nweightMeasurement · 완료 상태',
     '새 중량으로 기존 음식의 영양값을 갱신합니다.',kind='result',h=287)
arrow('M500 1046 H534')
arrow('M1000 1046 H1034')
arrow('M1270 1192 V1264',label='기기 높이 유무에 따라 분기',x=1040,y=1233)
arrow('M1040 1413 H1006')
arrow('M540 1413 H506')
parts.append('<rect class="formula" x="40" y="1595" width="1460" height="100" rx="14"/>')
text(65,1632,'공통 영양 계산',22,'title',700)
text(65,1669,'섭취 영양량 = DB 기준 영양량 × 최종 중량(g) ÷ DB 기준 중량(g)',24,'title',600)
text(40,1737,'읽는 방법',20,'title',700)
text(40,1770,'실선: 처리 순서   ·   점선: 사용자 선택 경로   ·   ?: 측정 불가 시 null인 값   ·   각 출력은 다음 단계의 입력으로 전달',18,'sub')
text(40,1803,'깊이 적분 부피 depthVolumeMl은 참고용입니다. 최종 중량 산식은 면적 × 평균 높이 × 밀도이며 검증 여부는 별도 기록합니다.',18,'sub')

style='''
text{font-family:"Malgun Gothic","Noto Sans KR",sans-serif;fill:#182534}
.canvas{fill:#fff}.node>rect:first-child{fill:#fff;stroke:#cbd5e1;stroke-width:1.6}
.stripe{fill:#3879b8}.server .stripe{fill:#54756b}.ai .stripe{fill:#b0843a}.result .stripe{fill:#4657a5}
.title{fill:#152637}.section{fill:#17384e}.sub{fill:#576675}.label{fill:#3879b8}
.connector{stroke:#718395;stroke-width:2.6;fill:none;stroke-linejoin:round}
.flow-label{fill:#5a6c7d}.formula{fill:#f1f5f9;stroke:#cbd5e1;stroke-width:1}
'''
inner='<defs><marker id="arrow" markerWidth="9" markerHeight="9" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" fill="#718395"/></marker></defs><style>'+style+'</style>'+''.join(parts)
def svg(viewbox,height):return f'<svg xmlns="http://www.w3.org/2000/svg" width="1540" height="{height}" viewBox="{viewbox}" role="img" aria-label="Foodify 사진 분석 및 AR 중량 산출 단계별 입력과 출력">{inner}</svg>'
svgs={
 'all':svg('0 0 1540 1840',1840),
 'photo':svg('0 100 1540 690',690),
 'ar':svg('0 825 1540 1015',1015),
}
for key,name in [('all','foodify-pipeline-io.svg'),('photo','foodify-photo-analysis-io.svg'),('ar','foodify-ar-weight-io.svg')]:
    (OUT/name).write_text(svgs[key],encoding='utf-8')

fields=[
 ('사진','JPEG 1~3장','일반 사진 분석과 AR 중량 촬영은 별도 입력입니다.'),
 ('음식 분류 후보','label, score','사진 전체 주 음식의 101종 제한 분류. 위치·중량을 출력하지 않습니다.'),
 ('AI 음식 결과','name, grams, weightEstimate, ingredients, uncertainty','대표 중량과 범위·가정. grams는 판단 불가 시 null입니다.'),
 ('DB 연결','foodId, majorCategory, 기준량, 영양값','음식 이름 자체와 DB 식품 ID를 구분합니다. 밀도는 DB 대분류를 이용합니다.'),
 ('윤곽','foodPolygons, reviewed','이미지 기준 정규화 좌표 0~1. 사용자 확인 후 기하 계산에 사용합니다.'),
 ('깊이·축척','distanceMm, mmPerPixel, fx, fy','깊이는 카메라 Z 방향 mm. 거리와 카메라 내부 파라미터로 면적을 계산합니다.'),
 ('면적·높이','areaCm2, heightCm, heightMethod','DEPTH / FEATURE_POINTS / AI. 음식 윗면 거리만 확보하면 기기 높이 대신 AI를 사용합니다.'),
 ('부피·중량','volumeMl, densityGPerMl, grams','1 cm³=1 mL. 부피와 중량은 소수 첫째 자리로 반올림하며 저장 시 서버가 다시 계산합니다.'),
 ('최종 영양·상태','nutrition{kcal, carbs, protein, fat}, status','중량·DB·필수 영양값을 갖춘 음식이 완료. 식단 내 모든 음식이 완료일 때 집계합니다.'),
]
rows=''.join('<tr>'+''.join('<td>'+esc(c)+'</td>' for c in row)+'</tr>' for row in fields)
page='''<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Foodify 단계별 입출력 다이어그램</title>
<style>
*{box-sizing:border-box}body{margin:0;padding:20px;background:var(--background,#fff);color:var(--foreground,#17212b);font-family:var(--font-sans,"Malgun Gothic",sans-serif)}
.toolbar{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-bottom:12px}.tabs{display:flex;gap:5px;flex-wrap:wrap}button,select{font:inherit;font-size:13px;border:1px solid var(--border,#d4dce4);border-radius:8px;padding:8px 12px;background:var(--card,#fff);color:var(--foreground,#17212b);cursor:pointer}button.active{background:var(--primary,#17384e);color:var(--primary-foreground,#fff)}button:hover{background:var(--accent,#edf3f8);color:var(--accent-foreground,#17384e)}.zoom{margin-left:auto}.intro{font-size:14px;line-height:1.7;color:var(--muted-foreground,#596674);margin:0 0 13px}.viewport{overflow:auto;border:1px solid var(--border,#d4dce4);border-radius:12px}.viewport svg{display:block;width:100%;height:auto;min-width:800px}.viewport .canvas{fill:var(--background,#fff)}.viewport .node>rect:first-child{fill:var(--card,#fff);stroke:var(--border,#cbd5e1)}.viewport text,.viewport .title{fill:var(--foreground,#182534)}.viewport .sub,.viewport .flow-label{fill:var(--muted-foreground,#576675)}.viewport .section{fill:var(--foreground,#17384e)}.viewport .formula{fill:var(--muted,#f1f5f9);stroke:var(--border,#cbd5e1)}details{margin-top:16px;border:1px solid var(--border,#d4dce4);border-radius:10px;padding:12px}summary{cursor:pointer;font-weight:600}table{width:100%;border-collapse:collapse;font-size:13px;line-height:1.7;margin-top:12px}th,td{padding:10px;text-align:left;border-bottom:1px solid var(--border,#d4dce4);vertical-align:top}th{background:var(--muted,#f1f5f9)}.tablewrap{overflow:auto}h1{font-size:20px;margin:0 0 8px}footer{font-size:12px;color:var(--muted-foreground,#596674);margin-top:12px}
</style><h1>음식 사진 촬영에서 결과 산출까지</h1><p class="intro">일반 사진 분석으로 결과를 먼저 만든 뒤, 사용자가 선택한 음식에 AR 중량 측정을 적용합니다. 각 상자에서 <b>입력 → 처리 → 출력</b>을 확인할 수 있습니다.</p>
<div class="toolbar"><div class="tabs"><button class="active" data-tab="all">전체 흐름</button><button data-tab="photo">일반 사진 분석</button><button data-tab="ar">AR 중량 측정</button></div><select class="zoom" aria-label="확대 비율"><option value="fit">폭에 맞춤</option><option value="100">100%</option><option value="125">125%</option></select><button id="download">현재 그림 SVG 저장</button></div><div class="viewport">'''+svgs['all']+'''</div><details><summary>데이터 필드·단위 설명</summary><div class="tablewrap"><table><thead><tr><th>구분</th><th>주요 출력값</th><th>해석</th></tr></thead><tbody>'''+rows+'''</tbody></table></div></details><footer>2026-11-12 작업 폴더 코드 기준 · 음식 종류에 공통으로 적용되는 처리 구조 · 외부 API를 호출하지 않고 작성했습니다.</footer>
<script>const diagrams='''+json.dumps(svgs,ensure_ascii=False)+''';let current='all';const box=document.querySelector('.viewport');const zoom=document.querySelector('.zoom');function applyZoom(){const s=box.querySelector('svg');s.style.width=zoom.value==='fit'?'100%':1540*Number(zoom.value)/100+'px';}document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>{current=b.dataset.tab;box.innerHTML=diagrams[current];document.querySelectorAll('[data-tab]').forEach(x=>x.classList.toggle('active',x===b));applyZoom();});zoom.onchange=applyZoom;document.querySelector('#download').onclick=()=>{const a=document.createElement('a');const u=URL.createObjectURL(new Blob([diagrams[current]],{type:'image/svg+xml;charset=utf-8'}));a.href=u;a.download='foodify-'+current+'-io.svg';a.click();setTimeout(()=>URL.revokeObjectURL(u),1000);};</script></html>'''
(OUT/'foodify-pipeline-io.html').write_text(page,encoding='utf-8')
scratch=os.environ.get('ORCA_CHAT_SCRATCH_DIR')
if scratch:
    name='foodify-pipeline-io-20261112.html'
    (Path(scratch)/name).write_text(page,encoding='utf-8')
    print('INLINE',name)
print('OUTPUT',OUT)
