"""Update the retained Spec copy; preserve the user's original document."""
from pathlib import Path
from copy import deepcopy
from docx import Document
from docx.shared import Pt

ROOT=Path(__file__).resolve().parents[1]
source=ROOT.parent/'Spec 보고서 수정본.docx'
doc=Document(source)

changes={
26:'Foodify는 모바일 기기로 촬영한 한 끼 음식 사진을 분석하여 음식 종류와 제공량을 추정하고, 이에 따른 열량과 영양성분 및 개인화된 식단 피드백을 제공하는 애플리케이션이다. OpenAI API의 gpt-4o-mini를 사용할 모델로 선정했으며, 영양성분은 공공 데이터베이스의 기준값으로 서버에서 계산한다. Flutter Android 앱과 Java 21 Spring Boot 서버를 사용하고, 갤럭시 S23을 기준 시험 기기로 선정했다.',
27:'회원별 촬영 기록과 분석 결과를 자동 저장하고, 완료된 기록을 영양 합계에 반영한다. 사용자가 피드백을 요청하면 직접 입력한 목표와 최근 1일·7일 기록을 바탕으로 식생활 참고 안내를 제공한다. 음식 종류 수는 서비스 제한이 아닌 평가 자료의 범위로 관리한다. 자동 음식량 추정은 단순한 형상의 음식 3종으로 실측 검증을 시작하고, 검증된 조건부터 적용한다.',
35:'공식 영양 자료로 음식·원재료성 식품·가공식품·통합 식품영양성분·건강기능식품 CSV 5개를 확보했다. 음식 자료를 우선 검색 대상으로 삼고, 재료와 제품 자료는 입력 근거에 따라 연결한다. 원본 파일과 행, 기준량, 단위, 출처 및 기준일을 보존하여 영양값의 근거를 추적한다. 빈 값은 미상으로 관리하며 g와 ml를 구분한다.',
36:'Google 공식 지원 목록에서 갤럭시 S23의 ARCore 및 Depth API 지원을 확인했다. 이를 활용해 카메라 위치, 평면, 깊이 및 신뢰도 정보를 수집하는 실험을 진행한다. 지원 사실과 음식 부피 측정 정확도는 구분하며, 실제 기기의 Android 버전과 깊이 수집 결과를 추가 확인한다. 부피·중량 계산에는 음식 영역, 기준면, 높이 및 밀도 등의 근거를 결합한다. 출처: https://developers.google.com/ar/devices?hl=ko 및 https://developers.google.com/ar/develop/java/depth/raw-depth',
38:'Windows에서 Flutter와 Android SDK를 사용한다. 기준 시험 기기는 갤럭시 S23이며, USB 디버깅 연결과 APK 실행·카메라·AR 측정을 검증한다. 모바일은 Riverpod, go_router, Dio 및 운영체제 보안 저장소를 사용한다. 서버는 Java 21, Spring Boot, Flyway 기반이다. 로컬 검증에는 H2 MySQL 호환 모드를 사용하고 배포 DB는 AWS RDS MySQL로 계획한다.',
45:'기기 및 개발환경 확인\n갤럭시 S23을 기준으로 앱 설치·카메라 촬영을 확인한다. 공식 지원 조사 결과에 더해 ARCore 실행 상태와 실제 깊이 데이터 수집 가능 여부를 기기에서 검증한다.',
46:'음식 인식 기능 구현\n대표 이미지 최대 3장을 서버에서 OpenAI API로 전달하고 음식명·재료 후보·참고 중량·불확실 항목을 구조화해 받는다. gpt-4o-mini를 사용하도록 운영 환경변수를 지정하며, 응답 형식과 단위 검증은 서버에서 수행한다.',
47:'음식량 추정 실험\n음식 3종별 10가지 제공량을 각각 2회 촬영한다. 동일 제공량의 반복 촬영이 보정용과 검증용 양쪽에 섞이지 않도록 분리한다. 검증 중량 상대오차 중앙값 25% 이하, 자동 결과 확보율 80% 이상을 초기 채택 목표로 삼는다. 이는 실험 기준이며 전체 음식의 보장 정확도와 구분한다.',
48:'영양성분 연결 및 계산\nCSV를 정규화하고 중복·충돌·누락값을 점검한 뒤 버전별 영양 DB에 적재한다. 음식 후보를 검색하고 필요하면 검색 결과 안에서 LLM이 선택을 보조한다. 현재 구현은 g 기준 계산부터 제공하며 ml와 제공량 계산은 후속 확장한다.',
50:'영양값은 기준 영양량에 기록량과 기준량의 비율을 곱해 계산한다. 기록량과 기준량은 같은 단위를 사용한다. 음식 부피를 중량으로 환산할 때는 밀도 또는 검증된 환산 근거를 적용한다. 필수 영양값이나 양의 근거가 부족하면 확인 필요 상태를 유지하고 사용자 보정으로 이어간다.',
53:'다음 구성은 구현된 기본 흐름과 배포·AR 확장 계획을 함께 나타낸다.',
59:'   ├─ Kotlin ARCore 지원 확인 및 공간 정보 수집 확장',
69:'   ├─ 참고 중량 처리 및 사용자 보정',
74:'       ├─ [OpenAI API gpt-4o-mini 선정]',
75:'       ├─ [버전 관리형 공공 영양 DB]',
76:'       ├─ [로컬 H2 검증 / 배포 RDS MySQL 계획]',
77:'       └─ [로컬 사진 저장 / 비공개 S3 배포 계획]',
82:'촬영 접수 후 식단 기록과 분석 작업을 저장하고 서버 작업 처리기가 분석을 실행한다. 앱은 분석 화면에서 3초 간격으로 상태를 조회한다. 동일 업로드는 요청 식별자로 중복을 막고, 기록 버전을 비교하여 늦게 도착한 분석 결과가 사용자 수정값을 덮어쓰는 상황을 방지한다.',
83:'배포는 EC2의 Spring Boot 서버, RDS MySQL, 비공개 S3를 기본안으로 한다. 현재 로컬 DB와 사진 저장을 지원하고 S3 연결 코드를 준비했다. 실제 AWS 자원 생성과 배포 검증은 후속 단계이다. API 키는 서버 환경변수로 주입하며 배포 시 Parameter Store SecureString과 EC2 IAM 역할을 연결하는 방안을 적용할 예정이다.',
88:'모바일과 서버는 /api/v1 경로의 REST API로 통신한다. 일반 데이터는 JSON, 촬영 접수는 multipart 형식으로 전달한다. 배포는 HTTPS를 사용하고 로컬 개발 연결은 개발 설정으로 구분한다. 서버의 OpenAPI 명세를 공유하여 요청과 응답 형식을 관리한다.',
91:'분석은 DB에 상태를 저장하는 서버 내부 작업 처리기로 실행한다. 일시 오류는 최대 3회 시도하며 재시도 간격은 5초·20초이다. 서버 재시작 후 남은 작업을 복구하고, 월 요청 수 한도에 도달하면 대기 처리한다. 요청 수 제한은 원화 비용 상한과 구분하여 운영 시 실제 사용량을 확인한다.',
94:'2026년 9월 28일 기준, 회원 가입·로그인·토큰 갱신, 목표 설정, 사진 업로드, 분석 상태 관리, 음식 수정·영양 계산, 일간·주간 집계 및 요청 기반 피드백의 1차 코드를 구현했다. 공식 영양 CSV 변환기와 버전 관리형 DB 적재기를 추가하고 로컬 H2 환경에서 자료 적재를 검증했다. 서버 자동 테스트 16개와 변환 기본 테스트 3개가 통과했다. 실제 OpenAI 호출, S23 실기기, MySQL·RDS·S3 배포 및 자동 부피 추정은 별도 검증 항목이다.',
97:'Android를 우선 대상으로 하며 기준 시험 기기는 갤럭시 S23이다. Android·One UI 버전과 기기 실행 결과는 실기기 점검 시 기록한다.',
99:'한 끼 전체의 여러 음식을 분석 대상으로 한다. 음식 종류 수는 평가 범위로 관리하며 자동 양 추정은 음식 3종의 초기 실측 실험으로 검증한다.',
102:'이메일·비밀번호 가입과 로그인, 만료되는 접근 토큰·갱신 토큰, 회원별 접근 권한을 구현했다. 공개 운영 전 이메일 인증과 비밀번호 재설정을 확장한다.',
104:'영양 DB는 원본 파일 5개, 식품 85,337개, 영양값 버전 110,939개, 원본 행 128,755개로 구성한다. 현재 검색 대상은 충돌 없는 g 기준 일반 식품 66,833개이며 그중 필수 영양값 4종이 모두 있는 항목은 55,627개이다. 동일 최신 날짜의 값 충돌 식품 2개는 검색에서 보류한다. 이 수치는 다운로드한 파일 집합 기준이며 공식 전체 DB 규모를 의미하지 않는다.',
106:'다음 작업은 Android APK 빌드 오류 해결, S23 설치·촬영 검증, gpt-4o-mini 실제 이미지 분석 연결, 로컬 MySQL 검증 및 음식량 실측 실험이다. 기본 서비스 흐름과 데이터 계산을 우선 안정화한 뒤 검증된 AR 방식을 통합한다.',
107:'한 끼 사진 50세트 중 30세트는 개발용, 20세트는 최종 평가용으로 분리한다. 음식 인식 정확도 85% 이상과 검출 재현율 90% 이상을 초기 목표로 둔다. 중량은 저울 실측값과 비교하고 자동 추정과 사용자 보정 결과를 각각 보고한다. 영양 계산은 DB 기준값과 식의 일치를 검증하며 실제 음식 성분의 정확도와 구분한다.',
108:'첫 시연 이후에는 이메일 인증·비밀번호 재설정, 지원 기기 확대, 음식별 자동 양 추정 개선, ml·제공량 계산 확장, 식후 잔반 반영 및 스토어 공개 준비를 진행한다.',
113:'1인 개발로 주 20~30시간, 8주를 기준으로 계획한다. 아래 일정은 기능별 목표이며 현재 구현 진척을 완료 판정과 별도로 관리한다. 월 AWS·AI 예산은 5만원 이내를 목표로 실제 견적과 사용량을 확인한다.',
126:'사용자가 입력한 일일 열량·탄수화물·단백질·지방 목표와 최근 1일·7일의 완료된 기록을 비교한다. 목표가 비어 있으면 기록된 구성 중심으로 설명한다. 미완료 기록과 기록 누락 가능성을 함께 안내하고, 기록 수정 후에는 피드백 기준 시점을 표시하여 재생성을 안내한다.',
128:'S23의 Android·One UI 버전과 실기기 APK·카메라·깊이 수집 결과',
129:'음식별 깊이 품질, 기준면 및 밀도 환산 근거와 자동 양 추정의 검증 범위',
130:'g 기준 보정 흐름의 실기기 사용성 및 ml·제공량 입력 확장',
131:'한 끼 전체를 짧게 촬영하는 안내 흐름과 촬영 품질 기준',
132:'실제 MySQL·RDS 마이그레이션 검증 및 공개 운영용 이메일 인증·비밀번호 재설정',
133:'gpt-4o-mini 실제 호출의 인식 품질·지연·비용과 영양 DB 충돌 항목 검토',
134:'배포 예산, 서버 운영 시간 및 사용량 한도 설정',
135:'사진은 기록 삭제 시까지 보관하고 계정 삭제에도 연동한다. S3 환경에서 삭제와 접근 차단을 추가 검증한다.'
}
def set_text(p,text):
    props=deepcopy(p.runs[0]._r.rPr) if p.runs and p.runs[0]._r.rPr is not None else None
    p.clear(); run=p.add_run(text)
    if props is not None: run._r.insert(0,props)
for i,text in changes.items(): set_text(doc.paragraphs[i],text)

table_updates={
1:{1:['모바일 애플리케이션','Flutter, Riverpod, go_router, Dio'],2:['서버','Java 21, Spring Boot, Flyway'],4:['실기기','갤럭시 S23 선정, 실제 Android 버전 확인 예정'],7:['음식 이미지 분석','OpenAI API, gpt-4o-mini 선정'],8:['영양성분 데이터','공식 CSV 5개 확보, 출처·단위·버전 관리형 DB'],9:['데이터 저장','로컬 H2 검증, 배포 AWS RDS MySQL'],10:['배포 환경','EC2, HTTPS 프록시, 비공개 S3 계획']},
2:{1:['촬영','식전 한 끼 전체, 짧은 안내 촬영, 대표 이미지 최대 3장'],3:['음식량 추정','음식 3종 실측 실험 후 검증된 범위 적용, 사용자 보정 제공'],10:['회원 관리','이메일·비밀번호 가입, 토큰 인증 및 회원별 자료 접근 관리']},
6:{1:['1주 차','Android 실행, S23 연결, OpenAI 호출 및 데이터 확인','실기기 촬영·API 연결 결과'],2:['2주 차','음식 3종 양 추정 실험, DB·API 명세 확정','채택 범위와 보정 흐름'],3:['3주 차','회원·목표, 사진 업로드·자동 기록·상태 관리','회원별 저장 및 상태 복구'],4:['4주 차','한 끼 인식, 영양 DB 검색·중량 보정·계산','촬영부터 영양 계산까지 연결'],5:['5주 차','검증된 AR 통합, 재분석·복구·집계','오류·중복·집계 검증'],6:['6주 차','피드백·삭제, 클라우드 배포, 서명 APK','S23에서 배포 서버 시연'],7:['7주 차','고정 평가 자료, 보안·비용·복구 시험','정확도·지연·비용 결과'],8:['8주 차','회귀 테스트, 시연 리허설, 문서·발표','최종 APK와 운영 안내']}
}
for ti,rows in table_updates.items():
    for ri,values in rows.items():
        for cell,text in zip(doc.tables[ti].rows[ri].cells,values):
            set_text(cell.paragraphs[0],text)
            for extra in list(cell.paragraphs[1:]): extra._element.getparent().remove(extra._element)

doc.add_paragraph('마 영양 데이터베이스 구성',style='Heading 2')
doc.add_paragraph('식품 목록, 버전별 영양값, 원본 출처를 분리한다. 예를 들어 사용자가 김치찌개를 선택하면 foods에서 항목을 찾고 현재 영양값 버전을 연결하여 100g 기준값과 입력 중량으로 계산한다. 식단에는 계산 당시 버전과 수치의 복사본을 저장하므로 기준 DB가 갱신되어도 과거 기록의 근거를 유지한다.')
t=doc.add_table(rows=1, cols=2)
t.style=doc.tables[7].style
for c,text in zip(t.rows[0].cells,['테이블','역할']): c.text=text
for name,desc in [
 ('nutrition_datasets','CSV 파일명, 인코딩, 파일 해시, 원본 행 수와 적재 시각'),
 ('nutrition_source_rows','파일별 행 번호와 전체 원본 값, 연결된 영양값 버전'),
 ('foods','식품 코드·이름·분류·제조사, 현재 영양값과 검색 가능 상태'),
 ('food_nutrition_versions','기준량·단위, 열량·3대 영양소, 자료원·기준일별 이력'),
 ('food_aliases','음식명 검색용 표기와 별칭'),
 ('food_portions','근거가 명확한 1회 참고량과 단위')]:
    for c,text in zip(t.add_row().cells,[name,desc]): c.text=text
doc.add_paragraph('현재 영양 검색·계산은 g 기준으로 제공한다. ml 기준과 건강기능식품은 출처·영양값을 보존하고 후속 기능 확장에 활용한다. 누락된 필수 영양값은 NULL로 유지하며, 같은 최신 기준일에 값이 충돌하는 항목은 검토 대상으로 분리한다. 자동 병합은 동일 식품 코드와 분류를 기준으로 수행하고 최신 데이터기준일자를 우선한다.')
doc.add_paragraph('CSV 변환 결과는 JSONL로 생성하고 트랜잭션 적재기를 사용한다. 같은 자료를 다시 실행해도 식별자로 중복을 방지한다. 실제 배포 전 MySQL 검증을 별도로 수행하며 RDS에 동일 Flyway 마이그레이션을 적용한다.')
doc.add_paragraph('바 참고 자료',style='Heading 2')
for text in [
 '식품의약품안전처 K-FIND DB 다운로드: https://various.foodsafetykorea.go.kr/nutrient/general/down/list.do',
 'Google ARCore 지원 기기: https://developers.google.com/ar/devices?hl=ko',
 'Google ARCore Raw Depth: https://developers.google.com/ar/develop/java/depth/raw-depth',
 'OpenAI 운영 가이드: https://developers.openai.com/api/docs/guides/production-best-practices',
 'AWS Parameter Store: https://docs.aws.amazon.com/systems-manager/latest/userguide/what-is-a-parameter.html']:
    doc.add_paragraph(text)

# Preserve existing table geometry while ensuring the appended table has repeatable headers.
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
for table in doc.tables:
    header=table.rows[0]._tr.get_or_add_trPr()
    if header.find(qn('w:tblHeader')) is None: header.append(OxmlElement('w:tblHeader'))
    borders=OxmlElement('w:tblBorders')
    for edge in ['top','left','bottom','right','insideH','insideV']:
        item=OxmlElement('w:'+edge); item.set(qn('w:val'),'single'); item.set(qn('w:sz'),'4'); item.set(qn('w:color'),'D9D9D9'); borders.append(item)
    table._tbl.tblPr.append(borders)
    for cell in table.rows[0].cells:
        shade=OxmlElement('w:shd'); shade.set(qn('w:fill'),'E7E6E6'); cell._tc.get_or_add_tcPr().append(shade)
out=ROOT/'docs/Spec 보고서 2026-09-28 수정본.docx'
doc.save(out)
print(out)
