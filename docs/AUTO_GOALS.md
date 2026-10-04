# 자동 영양 목표와 로컬 분석 (2026-10-04)

## 입력과 저장

관리 목적 대신 만 나이, 계산식용 성별, 키(cm), 몸무게(kg), 활동량을 입력한다.
POST /api/v1/me/goals/preview는 계산만 수행한다. PUT /api/v1/me/goals는 동일 입력을 서버에서 다시 계산하여 회원 goals JSON에 결과, profile, calculation을 저장한다.
기존 수동 목표는 새 목표 저장 전까지 집계 기준으로 보존한다. 기존 purpose는 조회·피드백에서 제외하며 새 목표 저장 시 제거된다.
피드백에는 목표 숫자와 계산 기준만 전달하고 신체정보 profile 원문은 제외한다.

## 계산 기준

- Mifflin–St Jeor 안정 시 에너지 소비량: 10 × kg + 6.25 × cm − 5 × 나이 + 성별 상수(남성 +5, 여성 −161).
- 활동 계수: 낮음 1.2, 가벼움 1.375, 보통 1.55, 높음 1.725.
- 하루 체중 유지 참고 열량 = 안정 시 소비량 × 활동 계수, 정수 반올림.
- 탄수화물·단백질·지방 열량 배분 50:20:30은 앱 기본값이다. 각각 4/4/9 kcal/g로 환산하고 소수 첫째 자리까지 표시한다.
- 자동 계산은 원 연구 대상인 만 19~78세 일반 성인을 우선 지원한다. 임신·수유·질환별 처방 등 별도 기준 필요 여부를 사용자가 확인한다. 목표를 건너뛰고 기록부터 시작할 수 있다.
- 입력 범위(키 100~250cm, 몸무게 25~350kg)는 오입력 방지 범위이며 정확도 보장을 뜻하지 않는다.

자료:
- 원 연구: https://pubmed.ncbi.nlm.nih.gov/2305711/
- 활동 계수 안내: https://www.hhs.texas.gov/sites/default/files/documents/common-comparative-standards-for-rds-ltc-settings.pdf
- 영양소 에너지 배분 범위: https://www.nationalacademies.org/read/10872/chapter/7

## 음식 사진 추정

worker-enabled=false이면 업로드는 저장되고 분석 대기 상태가 유지된다. 응답 analysisEnabled와 화면 안내로 중지 사유를 표시한다.
분석을 활성화하면 gpt-4o-mini가 음식·중량을 추정하고 DB 후보 선택을 보조한다. 중량과 완전한 DB 영양 자료가 확보되면 확인 전에도 참고 영양값을 계산한다.
사용자가 확인한 완료 식단부터 집계한다. DB 후보나 영양값이 부족하면 중량·DB 선택 보정을 안내한다.
공식 모델 문서: https://developers.openai.com/api/docs/models/gpt-4o-mini

## S23 로컬 연결

PC 로컬 서버는 127.0.0.1:8080, 앱 API_BASE_URL은 http://127.0.0.1:8080/api/v1이다.
adb -s 기기일련번호 reverse tcp:8080 tcp:8080으로 휴대폰 로컬 포트 요청을 USB를 통해 PC 포트에 전달한다.
같은 Wi-Fi나 공개 서버 없이 시험할 수 있으며 USB 재연결 시 reverse 설정을 다시 확인한다.
로컬 사진 분석도 외부 OpenAI 통신과 API 요금이 발생한다.
