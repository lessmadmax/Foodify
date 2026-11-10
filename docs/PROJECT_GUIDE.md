# Foodify 프로젝트 안내 (현행 기준 문서)

기준일: 2026-11-08 · 기준 커밋: `807a1c5` (main)

이 문서와 [PROJECT_CHECKLIST.md](PROJECT_CHECKLIST.md)가 **현재 상태의 원본**이다. 이 문서는 구조·정책·실행·작업 카드를, 체크리스트는 항목별 진척과 완료 근거를 다룬다. 날짜가 붙은 실험 보고서는 당시 결과의 기록이며 수정하지 않는다(12장 목록).

2026-11-08에 아래 문서를 이 문서로 통합하고 삭제했다. 이전 내용은 Git 이력에 있다.
`HANDOFF_2026-11-04`, `ORCA_AGENT_GUIDE_2026-11-07`, `IMPLEMENTATION`, `ACCEPTANCE`(체크리스트로 이동), `AUTO_GOALS`, `AI_CONSENT`, `DIET_CHAT`, `UI_NUTRITION`, `WEIGHT_ESTIMATES`, `NUTRITION_DATABASE`, `LOCAL_S23`, `AR_CAPTURE_INTEGRATION`, `ANDROID_FOOD_REGIONS`, `FOOD101_SERVICE`.

---

## 1. 프로젝트 개요

**음식 사진 기반 식단 관리 앱** (졸업 프로젝트). 사용자가 한 끼를 찍으면 음식명·중량을 추정하고, 공공 영양 DB로 영양값을 계산해 목표와 비교하고 피드백을 준다.

| 구성 | 위치 | 기술 |
|---|---|---|
| Android 앱 | `mobile/` | Flutter(Dart) + Kotlin(ARCore, 기기 내 Food-101 분류). 대상 기기 Galaxy S23(SM-S911N, Android 16) |
| 서버 | `backend/` | Spring Boot 3.5, Java 21, Flyway, H2(로컬)/MySQL(운영 예정) |
| 실험 스크립트 | `scripts/` | Python. AR 부피, API 중량 비교, 모델 평가, 영양 데이터 정규화 |
| 문서 | `docs/` | 한국어. 실험 결과는 `docs/<주제>_<날짜>.md` |
| 비공개 자료 | `data/private/` | Git 제외. AR 원본, API 응답, 평가 결과 |
| 영양 원본 | `data/nutrition/raw/` | 식품안전나라 표준데이터 CSV 5개(cp949) → `processed/catalog.jsonl` |
| 음식 이미지 | `data/food-images/` | AI Hub 원본. Git 제외(11/08) |

---

## 2. 현재 분석 구조 (2026-11-08)

```
인식:   기기 내 Food-101 후보(상위 3개) → OpenAI(gpt-4o-mini)가 음식명 결정 → 사용자 확인·변경
중량:   일반 촬영 → API 추정 (기본값, 사용자 수정 전제)
        AR 중량 화면 → 위에서 본 면적(cm²) × AI 평균 높이(cm) × DB 대분류 밀도 (AREA_HEIGHT)
DB:     이름 정확 일치 → 원재료 대표식품명 → 음식 범주 추정 → 이름 포함 검색 (+ 사용자 선택, LLM 선택은 보조)
피드백: OpenAI API (챗봇으로 통합)
챗봇:   OpenAI API (S23 Gemma는 속도·품질 문제로 제외, 개발자 메뉴 실험으로만 유지)
```

11/04에 정한 목표 구조(분류 모델 우선, 음식별 계수)는 11/05~11/08 실험으로 위처럼 바뀌었다. 바뀐 근거는 8장에 있다.

---

## 3. 중량 추정

### 3.1 AR 중량 화면 (`method=AREA_HEIGHT`, 11/08부터 기본)

| 단계 | 위치 | 내용 |
|---|---|---|
| 1. 촬영 | `ar_weight_screen.dart`, `ArGuidance.kt`, `ArFusion.kt` | 음식 바로 위(기울기 10° 이내)에서 5초 동안 좌우로 천천히 이동. 여러 프레임 깊이를 카메라 자세로 정렬해 결합 |
| 2. 윤곽 초안 | 기기 `SamSegmenter`(`prepareSegment`, `segmentPoints`) | **11/10부터 사용자가 사진에서 음식을 누르면 MobileSAM(vit_t, ONNX)이 그 점으로 음식 마스크를 만든다**(`source=tap_sam`). 촬영 직후 사진을 한 번 인코딩해 두고(S23 약 1초), 누를 때마다 디코더만 다시 돈다(약 0.05초). '음식'/'제외' 모드, 마지막 점 취소, 점 모두 지우기. 여러 조각이면 2~3곳을 누르면 전체가 잡힌다. 떨어진 조각은 조각마다 볼록 다각형(최대 30개, 사진의 0.04% 미만 조각 제외)이라 알갱이 사이 빈틈이 빠진다. **AI 박스(`/ar/food-box`)는 쓰지 않는다**: S23 설탕 기록에서 박스가 음식 대신 접시 아래쪽을 가리켰고 3회 응답이 모두 사진 가운데의 둥근 좌표였다. 손으로 그리기는 예비로 남는다 |
| 3. **윤곽 확인** | `ar_weight_screen.dart`, `food_region_screen.dart` | 사용자가 "윤곽이 맞아요"를 누르거나 직접 그린 뒤에만 다음 단계 진행(필수) |
| 4. 축척·면적 | `ArVolume.scale` | 윤곽 바깥 띠(접시 영역을 그렸으면 그 영역에서 음식 제외)의 깊이 중앙값으로 거리를 재고, 면적(cm²)을 계산. 11/09부터 신뢰도 32 이상 깊이만 쓴다. 20점 미만이면(흰 그릇·흰 책상) **ARCore 특징점**으로 거리를 잰다: 접시 영역(없으면 음식 bbox에서 이미지 폭 15% 이내)의 점 6개 이상이면 `feature_points_plate`, 아니면 음식 위·주변 점으로 `feature_points_food`(음식 윗면 기준이라 면적이 조금 작음). 거리가 10~100cm 밖이면 서버로 보내지 않고 재촬영 안내 |
| 5. 높이 | 기기 `ArVolume.measuredHeight` → 서버 `POST /api/v1/ar/preview`. 없으면 `POST /api/v1/ar/height`, `OpenAiClient.estimateHeight` | **11/10부터 기기 측정 높이를 먼저 쓴다.** 접시 기준 거리에서 음식 윤곽 안의 신뢰 깊이(20점 이상, 윤곽의 30% 이상)로 평균 높이를 잰다(`DEPTH`). 부족하면 음식 위 ARCore 특징점 6개 이상으로 잰다(`FEATURE_POINTS`). 평균 1mm 미만이거나 거리를 음식 윗면에서 쟀으면 AI에게 평균 높이를 3회 묻고 중앙값을 쓴다(0~30cm). 예상 중량을 미리 반환 |
| 6. 저장 | `MealService.applyArMeasurement` | 서버가 `면적 × 높이 × DensityGroups 밀도`로 다시 계산. 깊이 기반 부피는 `depthVolumeMl`로 참고만 기록 |

저장되는 `weightMeasurement`: `method`, `areaCm2`, `heightCm`, `heightMethod`(`DEPTH`·`FEATURE_POINTS`·`AI`, 이전 기록은 없음=AI), `heightSource`, `heightSampleCount`, `heightSamples`, `volumeMl`, `distanceMm`, `depthVolumeMl`, `majorCategory`, `densityGroup`, `densityGPerMl`, `densityBasis`, `qualityHold`, `holdReasons`, `volumeValidated=false`. 이전 `AR_VOLUME`(깊이 부피 × 밀도) 기록도 읽을 수 있다.

**밀도 (`food/DensityGroups.java`, FAO/INFOODS Density Database v2.0)** — 음식별이 아니라 DB 대분류 단위로 관리한다.

| 그룹 | 밀도(g/mL) | 근거 | DB 대분류 예 |
|---|---:|---|---|
| RICE 밥 | 0.73 | Rice, boiled (KEN) | 밥류, 곡류 |
| NOODLE 면 | 0.59 | Pasta/noodles, boiled (KEN) | 면 및 만두류 |
| SOUP 국물 | 1.04 | Soups 9개 중앙값 | 국 및 탕류, 찌개, 죽 |
| SIDE 반찬 | 0.70 | 고형 혼합 요리·고기 조리 항목 중앙값 | 반찬류 |
| BAKERY 빵·과자 | 0.42 | Bread·Cake·Biscuits (FNDDS 4.1) | 빵 및 과자류 |
| NUT 견과 | 0.63 | Nuts (FNDDS 4.1) | 견과 및 종실류 |
| DRINK 음료·유제품 | 1.03 | Milk, liquid (UK 6th) | 음료, 유제품 |
| SAUCE 양념 | 1.12 | Sauce, soy (FNDDS 4.1) | 장류, 조미료 |
| DEFAULT 미확인 | 0.73 | 그룹 값 중앙값 | 매칭 실패 |

알려진 한계:
- 흩어진 음식(땅콩 등)은 윤곽이 빈틈까지 감싸 면적·중량이 과대해진다(M12에서 142%). 앱은 안내만 하며 보정 방법은 W03에서 실험한다.
- **기기 측정 높이는 실측 검증 전에 적용했다(11/10, 사용자 결정).** 기존 기록 분석에서 깊이로 잰 평균 높이는 필요한 높이보다 2~13배 작았다(쿠키-A 0.08 vs 1.06cm, 밥-C 1.24 vs 3.47cm, 커피땅콩-E 0.17 vs 0.78cm). 깊이 부피 방식의 오차는 89%로 AI 높이(63%)보다 컸다. 그래서 쿠키·밥처럼 얇거나 유리 그릇에 담긴 음식은 중량이 작게 나올 수 있다. 특징점 높이는 검증하지 않았고, 점이 음식 위에만 있어 흩어진 음식에서는 크게 나올 수 있다. 저장 기록의 `heightMethod`로 방식별 오차를 따로 모아 비교해야 한다.
- **윤곽 오차가 중량에 그대로 곱해진다.** 수동 윤곽 면적을 0.8배·1.2배로 바꾸면 오차 중앙값이 50%·94%로 변했다. 대안 두 가지를 실험했지만 채택하지 않았다(11/09, [AREA_OUTLINE_SENSITIVITY](AREA_OUTLINE_SENSITIVITY_2026-11-09.md)). AI에게 덮인 비율을 묻는 방식은 108%로 악화됐다. 깊이 부피는 윤곽에 둔감하지만 89%로 부정확했다(얇은 음식·유리 그릇). 오차의 큰 부분은 AI 높이 과대(흩어진 땅콩: 필요 0.3~0.8cm, 응답 1.5~2.9cm)다.
- 대분류가 틀리면 밀도가 틀린다. 11/05 실측에서 '아몬드'가 빵 항목에 매칭되어 0.42가 적용됐다(11/07 검색 수정으로 해결, 4장).
- 새 방식의 S23 실기기 저울 비교는 아직 없다(W02).
- **무늬 없는 면에서는 거리를 잴 수 없다 (11/09 S23).** 흰 책상 위 아몬드에서 접시 영역 깊이의 신뢰도가 거의 0이었다. 값은 0.9~5.5m에 흩어졌고, 거리를 270.6cm(실제 약 22cm)로 재서 면적 3141cm²가 나왔다. 서버가 면적 상한 2000cm²를 넘는다며 400으로 거절했다. 같은 장면에서 신뢰도 32 이상인 깊이 점은 음식 주변에 0~2개였다. 이전에 정상이던 세션에는 수백~수천 개 있었다. 원본: `data/private/ar/s23-height-error-2026-11-09/`. 조치: 위 4단계의 신뢰도 필터·범위 검사, 앱 오류 문구 한국어화(`errorText`). 흰 그릇이 대부분이라 접시 면에 의존하지 않도록, 촬영할 때 프레임마다 ARCore 특징점(`point-cloud.f32le` 세계 좌표 m·신뢰도, `point-cloud-ids.i32le`)을 저장하고 거리 대안으로 쓴다(신뢰도 0.1 이상, id별 최신값, 기준 프레임 카메라 좌표로 변환). 11/09 이전 기록에는 특징점 파일이 없다. **흰 그릇에서 실측 검증 전**이다.

### 3.2 일반 촬영·갤러리 (API 추정)

- `OpenAiClient.analyze`가 음식별 대표 중량 `grams`, 범위 `weightEstimate.lowerGrams/upperGrams`, 가정 `assumptions`(1~5개)를 구조화 응답으로 받는다. 프롬프트 버전 `v4-category-names`(11/09): 견과·과일·채소·버섯·해조·어패류처럼 종류끼리 비슷한 원재료는 세부 종류가 확실하지 않으면 범주 이름('견과류')으로 답하고, 코팅·당·양념이 보이면 이름에 드러내게 한다(예: 커피땅콩). 조리된 요리는 구체적인 요리명으로 답한다.
- 서버 검증(`WeightEstimate`): `0 < 하한 ≤ 대표값 ≤ 상한 ≤ 10000g`. 판단 근거가 없으면 세 값 모두 null과 `uncertainty`의 구체적 이유.
- AR 촬영이면 대표 사진 1장과 장면 깊이 요약(깊이 중앙값, 신뢰 픽셀 비율, 시간차, 초점거리·주점)을 "신뢰할 수 없는 입력 데이터"로 함께 보낸다. 장면 깊이는 음식 높이가 아니며 크기 판단의 보조 단서다.
- `CaptureEvidence`(11/04 변경): 깊이 지연은 기록만 한다(음수·60초 초과만 제외). 신뢰 깊이 5% 미만, 사진 시각 50ms 초과, 필드 오류, 사진 수 불일치는 업로드를 거부하지 않고 AR 요약만 뺀 뒤 `method=guided_photos`로 진행하며 사유를 `arExcluded.reasons`에 남긴다.
- **API 중량은 측정값이 아니다.** 오차 중앙값 85~126%로 "1인분 기준" 과대 추정이 원인이다(M02·M03). 화면에서 기본값으로만 쓰고 사용자가 고친다.

### 3.3 완료 판정

- 음식 항목은 중량이 있고, DB와 연결되고, 필수 영양값(kcal·탄수화물·단백질·지방)이 모두 있으면 자동으로 `COMPLETE`다. 확인 체크박스는 없다.
- 식단의 모든 음식이 `COMPLETE`일 때만 일간·주간 합계에 반영한다. 중량 null, DB 미연결, 영양값 누락은 `INCOMPLETE`로 남는다.
- 음식명이나 중량을 고치면 이전 추정 근거를 해제하고 영양값을 다시 계산한다. 같은 이름·중량의 유일한 기존 항목은 `weightEstimate`·`uncertainty`·`weightMeasurement`를 유지한다.

---

## 4. 음식 인식과 영양 DB 매칭

### 4.1 인식

- Android가 Food-101-93M(ONNX Runtime 1.23.2 CPU, 4스레드)으로 이미지별 상위 3개 후보를 계산해 `captureInfo.foodRecognition`에 담는다. 서버(`FoodRecognitionEvidence`)가 모델·버전·사진 번호·허용 음식명·점수를 검증하고 OpenAI 분석의 근거로 넘긴다. 첫 후보를 강제 적용하지 않는다.
- S23 성능: Top-1 44/50(88%), Top-3 49/50(98%), 평균 0.283초(모델 준비 제외). 모델 원본 `prithivMLmods/Food-101-93M`, 변환 `scripts/export_food101_android.py`. ONNX 파일(약 344MB)은 Git 제외이며 APK 크기가 약 683MB로 커진다.
- 101종 밖 음식, 다중 음식, 한식은 API와 사용자 보정이 처리한다. 커피땅콩→아몬드 같은 유사 음식 오인식이 남아 있다(W01).
- **낮은 점수 후보 제외 (11/08, W01)**: 사진의 1순위 점수가 `app.food-recognition.min-top-score`(기본 0.6, 환경변수 `FOOD_RECOGNITION_MIN_TOP_SCORE`)보다 낮으면, 분석 프롬프트에서 그 사진의 후보를 빼고 `candidatesExcluded=LOW_TOP_SCORE`와 "101종 밖일 가능성이 크니 사진만 근거로 판단" 안내를 보낸다(`FoodRecognitionEvidence.forPrompt`). 저장되는 `capture_info`에는 원래 후보와 점수가 그대로 남는다. 기준 근거(PC, 같은 모델): 분류 밖 음식 커피땅콩 5건 0.05~0.20, 밥 2건 0.15~0.20, 쿠키 3건 0.46~0.53(모두 오답), eval30 정답 29건 0.65~1.00, 오답 1건 0.37. 표본이 40건이라 기준은 잠정값이다.

### 4.2 검색 순서 (`FoodService.search` → `FoodCategoryService`)

1. **이름 정확 일치**와 등록 별칭. 검색 전에 공백·밑줄·하이픈을 정규화한다. 버거는 구체명 → 불고기/치즈/치킨 유형 → 햄버거 순으로 넓힌다.
2. **원재료 대표식품명 일치** (11/07): `category='원재료성 식품'`을 `식품대분류명 + 대표식품명`으로 묶는다. 이름이 대표식품명과 같을 때만 적용한다('아몬드쿠키'는 해당 없음).
   - 생것 항목이 있으면 생것만, 없으면 전체(견과). 이름 앞 상태 단어(말린·건조·볶은·삶은·구운·찐·데친·생)가 있으면 그 상태만.
   - 같은 대표식품명이 두 대분류에 있으면 항목이 많은 쪽, 같으면 매칭하지 않는다.
   - 결과 예: 바나나(생것) 77kcal, 말린 바나나 314kcal, 아몬드·땅콩 → 견과 및 종실류(NUT 0.63).
   - **원재료 대분류 이름** (11/09): 이름이 원재료 식품대분류명의 범주 단어와 같으면('견과류'·'견과' → 견과 및 종실류, '버섯류', '과일류' 등) 그 대분류 전체 중앙값을 쓴다(`samplePolicy=RAW_MAJOR`). 대표식품별로 생것이 있으면 생것만, 없으면 전체를 모은다. '및'으로 묶인 대분류는 단어별로 나누고 '기타'는 뺀다. 실제 DB: 견과류 549kcal(47건, 범위 47~713), 과일류 52kcal(174건), 채소류 29kcal(318건). 이전에는 '견과류'가 이름 포함 검색으로 멸치볶음_견과류에 걸렸다.
3. **음식 범주 추정**: 이름 끝부분을 DB 식품대분류명·대표식품명과 비교해 긴 분류명부터 적용한다(무청김치 → 김치류). 중간 문자열 매칭은 피한다(김치볶음밥 ≠ 김치류). 해당 범주의 계산 가능한 g 기준 음식을 100g으로 환산한 영양소별 **중앙값**을 쓰고, 일반 음식을 브랜드 항목보다 우선한다.
4. **이름 포함 검색**.

2·3단계 결과는 `matchType=CATEGORY_FALLBACK`, `category:` 접두어 ID, 표본 수, 구성 식품 ID, 자료원·데이터 해시를 남기며 `majorOf`가 대분류를 돌려줘 밀도와 연결된다. 남은 한계: 멸치는 상태 미지정 시 생것(108kcal), 달걀(50~333kcal)·소고기(102~484kcal)는 부위가 섞여 범위가 넓다(W05).

후보가 여러 개면 `OpenAiClient.chooseCandidates`가 후보 안에서만 고른다(보조). 실패해도 검색 후보와 사용자 선택은 유지된다.

**종류 고르기 (11/09, W01)**: 음식이 원재료(원재료 항목, 대표식품 중앙값, 대분류 중앙값)로 연결되면 `GET /foods/kinds?foodId=`가 같은 대분류의 다른 대표식품을 항목 수가 많은 순으로 최대 40개 돌려준다. 각 항목은 그 매칭을 재현하는 이름(`query`)과 계산된 음식을 담는다. 앱은 결과 카드에 8개를 칩으로 보이고 나머지는 '더 보기'로 연다. 칩을 누르면 음식명을 `query`로 바꾸므로 저장할 때 서버가 같은 범주로 다시 계산한다. 마지막 칩 '코팅·가공 식품에서 찾기'는 `GET /foods?query=…&scope=processed`(가공식품만 이름 포함 검색)로 커피땅콩 같은 가공 과자를 찾는다. 요리 항목에는 칩이 없다. 한계: 순서가 DB 항목 수 기준이라 견과에서는 밤·도토리·참깨가 먼저 나오고 아몬드는 7번째, 호두는 '더 보기' 안에 있다.

### 4.3 영양 DB 구조

`nutrition_datasets`(파일, SHA-256 ID) → `nutrition_source_rows`(원본 행, 모든 열 JSON) → `food_nutrition_versions`(버전별 영양값) → `foods`(검색용 현재값). `food_aliases`는 검색 표기, `food_portions`는 g/ml로 명시된 1회 참고량.

- 적재 결과(H2): 식품 85,337개, 영양 버전 110,939개, 원본 행 128,755개.
- 같은 식품은 최신 `데이터기준일자`를 우선한다. 같은 날짜에 값이 다르면 검색에서 보류한다.
- 계산은 `basis_unit=g`에서만 한다. 빈 칸은 NULL(0과 구분). ml·건강기능식품은 참고 보관만 한다.
- 식단 `items` JSON에 계산 당시 음식·영양값·자료원·버전 ID 스냅샷을 저장한다. DB 갱신만으로 과거 기록은 바뀌지 않고, 사용자가 수정하면 현재 기준으로 재계산한다.
- **공식 데이터를 대신하는 가짜 값은 넣지 않는다.**

---

## 5. 앱 기능 요약

| 기능 | 내용 | 주요 파일 |
|---|---|---|
| 회원·인증 | 이메일 가입·로그인, BCrypt, 만료 접근 토큰, 갱신 토큰 회전, 로그아웃·탈퇴, 소유권 검사, 로그인 IP당 분당 20회 제한 | `auth/*`, `screens.dart` |
| 자동 목표 | 만 나이·성별·키·몸무게·활동량 입력. Mifflin–St Jeor × 활동 계수(1.2/1.375/1.55/1.725), 탄단지 50:20:30(4/4/9 kcal/g). 19~78세 일반 성인 대상, 건너뛰기 가능. `POST /me/goals/preview`는 계산만, `PUT /me/goals`는 서버 재계산 후 저장. 피드백에는 목표 숫자만 전달하고 신체정보 원문은 제외 | `goal/GoalCalculator.java` |
| 촬영·업로드 | 사진 촬영(지원 기기에서 AR 보조 촬영 자동 진입) 또는 갤러리 최대 3장. AR 사진은 다른 사진과 섞지 않는다. 멱등 업로드(`Idempotency-Key`), 앱 내 전송 대기·재전송, 사진 JPEG 재인코딩·EXIF 제거, 로컬/S3 저장 | `PendingUpload`, `PhotoStore` |
| 음식 영역 보정 | 사진별 음식 다각형 수동 지정(선택 기능). `capture_info.regionDrafts`에 저장, 서버가 형식만 검증하고 AI 입력에서는 제외 | `food_region_screen.dart`, `RegionEvidence.java` |
| AI 전송 동의 | 첫 업로드·재분석·피드백 전에 서버의 회원별 동의 상태 확인. 미동의·철회·버전 변경이면 안내 화면. 설정에서 확인·철회. 채팅 추가로 재동의(v2-chat). 공개 운영용 개인정보 처리방침은 미확정 | `AiConsent.java`, `ai_consent.dart` |
| 결과 화면 | 4행 2열 영양표(kcal·탄·단·지), 한 끼 합계 → 분석 근거 → 음식별 결과와 보정. 미상은 0과 구분해 "계산 대기". 음식명 표시(빈 이름은 '음식명 확인 필요'), 음식 변경/선택, 원재료의 종류 칩·코팅·가공 식품 검색(4.2장), 계산 근거 펼치기. AI 중량·범위·가정은 화면에서 숨기고 보정 입력란은 접이식 | `nutrition_table.dart`, `screens.dart` |
| AR 중량 | 3.1장. 홈에서 진입 | `ar_weight_screen.dart` |
| 식단 챗봇 | 오늘·최근 7일 완료 식단 합계와 목표 차이, 최근 30끼 상세(이름·영양값만), 최근 대화 10쌍을 맥락으로 전달. Markdown 답변·복사. 질문 2,000자, 회원당 동시 1건, 같은 요청 키 재사용. 구 `/feedback`은 호환용 | `ChatService.java`, `chat_screen.dart` |
| 개발자 메뉴 | 설정 → 개발자 메뉴: AR 깊이 진단·실험, AR 연구 촬영(저울값 기록·보정/평가 분리·ZIP 내보내기), 기기 내 Gemma(실험) | `ArDiagnosticActivity.kt`, `ar_research_screen.dart`, `local_model_screen.dart` |

---

## 6. 서버 API·데이터

**엔드포인트** (`/api/v1`, 인증 필요. AI 엔드포인트는 AI 동의도 필요)

| 경로 | 용도 |
|---|---|
| `/auth/signup`, `/auth/login`, `/auth/refresh`, `/auth/logout` | 가입·로그인·갱신·로그아웃 |
| `GET·DELETE /me` | 회원 조회·탈퇴 |
| `PUT /me/goals`, `POST /me/goals/preview` | 목표 저장·미리보기 |
| `GET·PUT·DELETE /me/ai-consent` | AI 동의 조회·동의·철회 |
| `/meals`, `GET·PATCH·DELETE /meals/{id}` (PATCH: `version` + `items`) | 식단 업로드·목록·조회·수정·삭제 |
| `POST /meals/{id}/analyses`, `GET /analyses/{id}` | 재분석 요청, 분석 상태 |
| `GET /photos/{id}` | 소유권 확인 후 사진 응답 |
| `GET /foods` | 영양 DB 검색 (`scope=processed`면 가공식품만 이름 포함 검색) |
| `GET /foods/kinds?foodId=` | 원재료의 같은 대분류 다른 종류(종류 칩) |
| `GET /nutrition/summary` | 일간·주간 합계 |
| `POST /ar/food-box`, `POST /ar/height` | AR 윤곽 박스, AI 평균 높이 |
| `POST /ar/preview` | 기기에서 잰 높이로 예상 중량(AI 호출 없음, AI 동의 불필요) |
| `GET·POST·DELETE /chat` | 챗봇 대화 조회·질문·삭제 |
| `POST /feedback`, `GET /feedback/{id}` | 구 피드백(호환용) |
| `GET /health` | 상태 확인 |

- 업로드: multipart `images`(1~3), `eatenAt`(epoch ms), `captureInfo`(JSON 문자열), `Idempotency-Key` 헤더.
- 수정: 서버가 `nutrition`·`source`·`status`를 계산한다. 사용자가 보낸 영양 숫자는 쓰지 않는다. 버전이 다르면 409 `STALE_VERSION`.
- 상태: `QUEUED`, `RUNNING`, `COMPLETE`, `INCOMPLETE`, (분석 대체 시) `SUPERSEDED`. 일시 오류는 최대 3회 재시도, 월 요청 수 한도 초과는 `BUDGET_WAIT` 후 다음 달 재개, 3분 넘은 `RUNNING`은 복구.
- 날짜 범위는 양 끝 포함, 한국 시간 기준. 조회 응답의 저장 필드는 snake_case(`eaten_at`, `capture_info`).
- OpenAI Responses는 `store:false`, 모델 `gpt-4o-mini`(`OPENAI_MODEL`로 변경). 사용량은 `ai_usage` 테이블.
- Flyway: V1 초기, V2 분석 재시도, V3 영양 카탈로그, V4 AI 동의, V5 챗봇.
- OpenAPI: `/v3/api-docs`(Bearer 토큰 필요).

---

## 7. 확정 정책 (바꾸려면 사용자 승인 필요)

1. 윤곽은 사용자가 음식을 눌러 자르거나(MobileSAM) 직접 그린다. 사용자가 확인한 뒤에만 중량을 계산한다.
2. API에 중량을 직접 묻는 값은 측정값으로 쓰지 않는다(기본값·사용자 수정 전제).
3. AR 중량은 면적 × 평균 높이 × 대분류 밀도로 계산한다. 평균 높이는 기기 측정(깊이 → 특징점)을 먼저 쓰고, 없으면 AI 높이를 쓴다(11/10 변경). 깊이 부피는 참고값이다.
4. 밀도와 대체 영양값은 음식별이 아니라 **범주(대분류) 단위**로 관리한다. 사용자는 음식별 설계보다 범주 단위 설계를 선호한다.
5. 영양값은 공식 DB 값 또는 그 범주·원재료 중앙값만 쓴다. 가짜 값을 DB에 넣지 않는다.
6. `volumeValidated`는 실측 검증 전까지 false다.
7. 피드백·챗봇은 API를 쓴다. 기기 내 Gemma는 실험 기능으로만 남긴다.

---

## 8. 결정 경과와 근거

| 시기 | 내용 | 근거 |
|---|---|---|
| 09월 말 | Flutter·Spring Boot 기반, 회원·목표·촬영·업로드·OpenAI 분석·식단 수정. 공공 영양 DB 5종 적재 | 체크리스트 A~G |
| 10/04~07 | 체크리스트 99개 작성. 중량 대표값·범위, 음식명 정규화 검색, 근거 충족 시 자동 완료 | 3.2·3.3장 |
| 10/08~12 | AI 전송 동의, 식단 챗봇, 영양표 UI, 음식명 표시, 범주 대체 계산 | 4·5장 |
| 10월 | AR 진단(S23 깊이·신뢰도), 수동 음식 영역·접시 기준면, AR 연구 화면 | [AR_DIAGNOSTICS](AR_DIAGNOSTICS.md), [AR_S23_MEASUREMENT](AR_S23_MEASUREMENT.md) |
| 10/28~11/02 | Gemma 4 E2B S23 실행. 50종 세부명 일치 API 58% vs Gemma 16%. Food-101 서비스 적용(S23 Top-1 44/50) | [EVAL50](EVAL50_2026-11-02.md), [FOOD_CLASSIFIER_EVAL50](FOOD_CLASSIFIER_EVAL50_2026-11-02.md) |
| 11/04 | AR 실측 11건: 쿠키·밥 6건은 음식 깊이 신뢰도 0%(비스듬한 촬영·광택/투명 접시). 촬영 안내(기울기 10°) 추가 | [AR_DEPTH_IMPROVEMENT](AR_DEPTH_IMPROVEMENT_2026-11-04.md), [AR_RECOLLECTION_M11](AR_RECOLLECTION_M11.md) |
| 11/04 | M02·M03: API 중량 오차 중앙값 85~126%. 음식명을 고정해도 "1인분 기준" 과대 추정 → API 중량은 기본값 | [API_FIXED_NAME_WEIGHT](API_FIXED_NAME_WEIGHT_2026-11-04.md) |
| 11/04 | 피드백·챗봇 API 유지(S23 Gemma 15~23초, JSON 오류, 7일 합계 오해석) | 체크리스트 M08~M10 |
| 11/05 | M12: 면적을 주고 높이만 물은 뒤 면적 × 높이 × 밀도로 계산하면 중앙값 63%(밥 15%, 쿠키 34%), 흩어진 땅콩은 142% | [API_AREA_WEIGHT](API_AREA_WEIGHT_2026-11-05.md) |
| 11/05 | M13: 자동 윤곽은 10건 중 1건만 맞음(면적 비율 중앙값 0.25) → 사용자 윤곽 확인 필수 | [API_AUTO_OUTLINE](API_AUTO_OUTLINE_2026-11-05.md) |
| 11/05 | 서버 중량 = AR 부피 × DensityGroups로 통일. S23 땅콩 17g 실측: 부피 25.7mL는 맞았으나 '아몬드'가 빵에 매칭되어 10.8g(견과 밀도면 16.2g, 약 5%) | `data/private/ar/demo-confirm-2026-11-05/` |
| 11/07 | 원재료 대표식품명 매칭, 생것 우선, 상태 단어 인식 (`5291a55`) | 4.2장 |
| 11/08 | AR 중량을 면적 × AI 평균 높이 × 대분류 밀도로 전환 (`60fb8a8`). 서버 63개, Kotlin `ArVolumeTest` 14개, Flutter 53개 테스트 통과 | 3.1장 |

---

## 9. 실행 환경과 명령

| 항목 | 값·명령 |
|---|---|
| 작업 경로 | `C:\Users\DELL\Desktop\Lectures\2026-02\Finalproject\Foodify` (main) |
| 서버 테스트 | `cd backend; mvn test` (보고서 `target/surefire-reports`) |
| 서버 빌드·실행 | `mvn -q package -DskipTests` 후 `java -jar target/foodify-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,demo`. 실행 전 PowerShell `$env:OPENAI_MODEL='gpt-4o-mini'`. 포트 8080(127.0.0.1), H2 파일 `backend/data/foodify.mv.db`. backend 폴더에서 실행해야 기존 DB·사진을 이어 쓴다 |
| OpenAI 키 | Windows 사용자 환경변수 `OPENAI_API_KEY`. 프로세스에만 주입: `$env:OPENAI_API_KEY=[Environment]::GetEnvironmentVariable('OPENAI_API_KEY','User')`. 출력·파일 기록·커밋 금지. `.env`는 서버가 자동으로 읽지 않는다 |
| 앱 테스트·빌드 | `C:\flutter_windows_3.29.3-stable\flutter\bin\flutter.bat`. `cd mobile; flutter test; flutter build apk --debug --dart-define=API_BASE_URL=http://127.0.0.1:8080/api/v1` |
| Kotlin 테스트 | `cd mobile/android; ./gradlew.bat :app:testDebugUnitTest --tests "*ArVolumeTest*"` (결과 `mobile/build/app/test-results/`) |
| S23 설치 | `C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe -s R3CW20JY6JN install --user 0 -r mobile\build\app\outputs\flutter-apk\app-debug.apk` → `reverse tcp:8080 tcp:8080` → `shell am start --user 0 -n com.example.foodify/.MainActivity`. 일괄 스크립트 `scripts/build_install_s23.bat` |
| S23 연결 확인 | `adb devices`에 `R3CW20JY6JN device`, `reverse --list`에 `tcp:8080`, `Invoke-RestMethod http://127.0.0.1:8080/api/v1/health`가 UP. USB 재연결 후 reverse 다시 실행. 에뮬레이터 주소는 `10.0.2.2` |
| AR 세션 꺼내기 | `adb exec-out run-as com.example.foodify tar -C files/ar-diagnostics -cf - <세션ID>` |
| Python | PATH에 없음. `C:\Users\DELL\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe -X utf8` |
| 영양 적재 | `python scripts/prepare_nutrition.py` → backend에서 `java -jar ... --spring.profiles.active=local,demo --app.worker-enabled=false --server.port=0 --app.import-catalog=../data/nutrition/processed/catalog.jsonl --app.import-exit=true`. 서버를 멈추고 실행. 전체 원본 집합으로만 갱신 |
| 로컬 API 확인 | `@test.invalid` 테스트 계정으로 가입해 토큰 사용. 한글 쿼리는 curl 대신 Python `urllib` |
| 주의 | Git Bash의 `/sdcard`는 `MSYS_NO_PATHCONV=1`. 배치 파일은 ASCII로만 작성. 영양 CSV는 cp949 |

**MySQL·운영 전 점검** (아직 실행하지 않음)
- 로컬: `.env.example` → `.env`, `docker compose up -d mysql`, 서버에 `DB_URL/DB_USER/DB_PASSWORD`를 주고 `demo` 없이 실행.
- RDS는 private subnet, 서버 security group만 3306 허용. S3는 public access block·암호화, EC2 role에 대상 버킷 Get/Put/Delete만.
- 배포: `docker compose --env-file .env -f deploy/compose.yml up -d --build` (Caddy HTTPS).
- 예산 알림 2.5만·4만·5만원. AI 한도는 현재 요청 수 기준이며 금액 상한은 미구현.
- release 서명키는 `mobile/android/key.properties`(Git 제외). debug 키를 운영용으로 쓰지 않는다.

---

## 10. 공통 규칙과 공유 자원

1. **비밀값**: API 키를 출력·기록·커밋하지 않는다.
2. **비공개 자료**: `data/private/`는 원본을 수정하지 않고 결과를 새 폴더·새 파일로 저장한다.
3. **설계 먼저 질문**: 구현 전에 설계 세부를 선택지와 추천안으로 사용자에게 묻는다. 범주 단위 설계를 먼저 제안한다.
4. **커밋**: 사용자가 요청할 때만. `git status`로 범위를 확인하고 코드와 문서는 나눠서 커밋한다. 작업은 main에서 한다.
5. **언어**: 문서·사용자 안내·앱 문구는 한국어.
6. **유료 호출**: OpenAI 호출이 수십 회 이상이거나 AWS 자원을 만드는 작업은 호출 수·예상 비용을 먼저 알리고 승인받는다.
7. **실험 문서 형식**: 목적 → 방법 → 결과(표) → 해석 → 결론 → 한계. 수치는 원자료 경로와 함께 적는다.
8. **문서 갱신**: 결정·진척이 바뀌면 이 문서와 체크리스트를 함께 고친다. 새 실험은 날짜 붙은 새 문서로 쓰고 12장 목록에 한 줄 추가한다.

**공유 자원 (동시에 한 작업만)**
- 포트 8080 서버 프로세스: 재시작하면 다른 작업의 확인이 끊긴다. H2 파일은 실행 중 잠긴다.
- S23 기기: 설치·촬영·adb reverse. 촬영은 사용자가 직접 한다.
- OpenAI 키·비용.

---

## 11. 작업 카드

형식: 목표 / 배경 / 담당 파일 / 완료 기준 / 시작 전 사용자에게 물을 것. 우선순위 ★★★ 바로, ★★ 다음, ★ 나중.

### W01 ★★★ 음식 인식 오류 줄이기 (커피땅콩 → 아몬드) — M05
- **배경**: S23 실측에서 커피땅콩을 계속 '아몬드'로 인식. 이전 실험에서도 30회 중 21회 오인식. 지금은 '음식 변경'으로만 고칠 수 있다.
- **원인 (11/08 확인)**: Food-101 101종에 견과류가 없어 후보가 모두 틀린 음식(foie_gras, fried_rice 등)인데, 프롬프트가 후보를 우선 검토하게 했다. 커피땅콩은 코팅된 갈색 알갱이라 사진만으로도 아몬드·그래놀라·강정과 비슷하다.
- **진행 (11/08)**: 1순위 점수 0.6 미만이면 후보를 프롬프트에서 빼는 방식 구현(4.1장). 서버 테스트 66개 통과.
- **전후 비교 (11/08)**: 커피땅콩 5건 × 3회에서 정답(땅콩·견과류)은 2/15였다. 이전 사진 조건은 7/15였다. 후보 제외로는 해결되지 않았다. 오인식의 주원인은 gpt-4o-mini가 사진만 보고 판단하는 데 있다([W01_CANDIDATE_FILTER](W01_CANDIDATE_FILTER_2026-11-08.md)). 후보 제외는 안전장치로 유지한다. 다음 선택지: 분석 전 음식명 확인 단계, `detail high`·상위 모델, W12.
- **땅콩→아몬드 분석 (11/09)**: 견과가 또렷한 D·E·F 9회 중 견과로 본 것은 6회였고, 종류는 땅콩 2·아몬드 2·호두 2로 갈렸다. 원인: 껍질째 땅콩과 아몬드는 길쭉한 타원형에 갈색 줄무늬 겉껍질이라 실루엣이 거의 같고, 640×480 프레임에서 한 알이 10~15픽셀이라 구분 단서가 보이지 않는다. 프롬프트가 구체적인 이름을 요구한 것도 원인이다. 영양 영향: 땅콩 볶은것 567kcal와 아몬드 594kcal는 약 5% 차이이고 둘 다 NUT 밀도라 작다. 커피땅콩(가공식품 478kcal, 탄수화물 67.8g)을 그냥 견과로 보는 오차가 더 크다.
- **조치 (11/09)**: 프롬프트 `v4-category-names`(3.2장), 원재료 대분류 이름 매칭과 종류 칩·가공식품 검색(4.2장). 서버 70개, Flutter 56개 테스트 통과. S23에서 확인하는 일과, 커피땅콩 기록으로 프롬프트 효과를 비교하는 일(호출 15회)이 남았다.
- **담당 파일**: `screens.dart`의 분석 결과·음식명 확인 부분, `FoodRecognitionEvidence.java`, 관련 테스트. 분석 프롬프트(`OpenAiClient.java`) 수정이 필요하면 W02와 순서를 정한다.
- **완료 기준**: 선택한 방식 구현, 테스트 통과, 기존 기록(커피땅콩 A~F)이나 S23 재촬영으로 전후 비교.
- **물을 것**: 분석 전 음식명 확인 단계 vs 후보 2~3개 동시 표시 vs 프롬프트 수정 / 일반 촬영에도 적용할지.

### W02 ★★★ AREA_HEIGHT 실기기 검증 — M06
- **배경**: 11/08에 구현·자동 테스트 완료(`60fb8a8`). S23에서 저울값과 비교한 결과는 0건이다.
- **담당 파일**: `ar_weight_screen.dart`, `OpenAiClient.estimateHeight`, `AppController`(`/ar/height`), `MealService`, 새 문서 `docs/AR_AREA_HEIGHT_S23_<날짜>.md`.
- **완료 기준**: 덩어리 음식(밥·쿠키)부터 S23 측정 → 저울값 대비 오차, 높이 3회 편차, `depthVolumeMl`과의 비교를 표로 기록.
- **물을 것**: 측정 음식·제공량 / 높이 호출 수(3회 유지?) / 거리값 출처(깊이 중앙값 vs 평면 hit-test).
- **11/09 첫 시도**: 흰 책상 위 아몬드에서 거리 오측정(270.6cm)으로 실패. 신뢰도 필터·거리 범위 검사, ARCore 특징점 거리 대안 적용(3.1장). 다음 측정은 흰 그릇에서 저울값과 자로 잰 카메라 높이를 함께 기록해 깊이·특징점 거리를 비교한다.

### W03 ★★ 흩어진 음식의 면적 보정 (실험)
- **배경**: 덩어리 윤곽이 빈틈을 포함해 M12에서 142% 과대. AREA_HEIGHT가 기본 경로가 되어 중요도가 커졌다. 후보: 조각별 윤곽의 합, API에 덮인 비율(%) 질문, 색 분할.
- **담당 파일**: `scripts/` 새 스크립트(기존 `compare_api_weight_area.py`·`validate_auto_outline.py`는 읽기만), 새 문서 `docs/API_SCATTERED_FOOD_<날짜>.md`, `data/private/ar/<새 폴더>/`.
- **완료 기준**: 커피땅콩 기록 5건(A·C·D·E·F)으로 방식별 오차를 M12 표와 같은 형식으로 비교하고 앱 적용 방식을 추천.
- **물을 것**: 비교할 방식 / OpenAI 호출 수 한도.

### W04 ★★ 자동 윤곽 품질 개선 (실험)
- **배경**: M13에서 AI 박스 위치 부정확(이탈 2, 못 찾음 2), 깊이 다듬기가 접시 테두리·저울 창을 고름, 얇은 음식은 5mm 기준 때문에 일부만 잡힘.
- **담당 파일**: `scripts/` 새 스크립트, 새 문서. `ArVolume.foodFromBox` 변경은 실험 결과 후 사용자 승인을 받아 별도 진행.
- **완료 기준**: 같은 10건에서 윤곽 일치 건수·면적 비율을 M13과 비교.
- **11/09 진행**: 수동 윤곽 사각형을 입력으로 MobileSAM·GrabCut 비교(IoU 중앙값 둘 다 0.61). 흰 접시에서는 손 그리기보다 경계가 정확해 보였고, 꽃무늬 접시·유리 그릇·접시 면 선택에서 실패했다([SEGMENTATION_OUTLINE](SEGMENTATION_OUTLINE_2026-11-09.md)). **11/10 후속**: 마스크 기준 높이 재질문은 오차 89%(M12 63%)로 개선이 없었다. 마스크가 정확해도 AI 높이가 과대했다(땅콩 필요 0.8~1.0cm, 응답 1.2~3.6cm). AI 사각형 입력에서는 3건 맞음, 2건 일부 맞음(M13은 1건)이었다. S23 MobileSAM ONNX 처리 시간은 인코더 약 0.93초, 디코더 약 0.05초였다. 결론: 분할은 기기 적용이 가능하지만 중량을 개선하려면 높이 문제를 먼저 풀어야 한다.
- **11/10 앱 적용**: AR 윤곽 초안을 AI 박스 + MobileSAM으로 바꿨다(3.1장 2단계). 모델은 `mobile/android/app/src/main/assets/mobilesam/`(ONNX 2개는 Git 제외, `manifest.json`의 SHA-256로 첫 사용 시 검증), 변환은 `scripts/export_mobile_sam_android.py`. PC에서 ONNX + 기기와 같은 전처리 결과가 PyTorch와 일치함을 확인했다(마스크 IoU 0.85~0.997). `SamGeometryTest` 6개. **S23 실제 촬영 확인 전**이다. 남은 일: 화면을 눌러 점으로 다시 자르기(AI 박스가 틀렸을 때), 꽃무늬 접시·유리 그릇 대응.
- **11/10 누르기 방식으로 변경**: S23 설탕(무늬 접시) 첫 촬영에서 AI 박스가 접시 아래쪽을 가리켜 윤곽이 접시 반쪽으로 잡혔다(원본 `data/private/ar/s23-sugar-outline-2026-11-10/`). PC 재현에서 설탕 조각 1곳을 누르면 그 조각만, 2곳을 누르면 설탕 전체가 잡혔다. 그래서 AI 박스를 빼고 누르기만 쓴다(3.1장 2단계). `SamGeometryTest` 8개.
- **물을 것**: 후보(상위 모델 박스, AI 다각형·점, 색 분할, MobileSAM) / 호출 비용 한도.

### W05 ★★ 원재료 매칭 후속 정리
- **배경**: 멸치는 상태 미지정 시 생것, 달걀·소고기는 부위가 섞여 범위가 넓다. 가공식품은 대상이 아니다. 11/07 변경은 커밋됨(`5291a55`).
- **담당 파일**: `FoodCategoryService.java`, `FoodSearchTerms.java`, `backend/src/test/.../food/*`.
- **완료 기준**: 규칙 추가, 테스트, 실제 DB 검색 결과 이전/이후 표.
- **물을 것**: 부위 처리(소분류 '전체'·'육' 우선 등 범주 규칙) / 말려 먹는 재료의 기본 상태 규칙.

### W06 ★★ AR 재수집 데이터 분석 — M11
- **배경**: [재수집 절차](AR_RECOLLECTION_M11.md)(커피땅콩 평가 5그룹 + 밥 보정 3·평가 2그룹, 그룹당 2회). 사용자 촬영 필요. 기본 경로가 AREA_HEIGHT로 바뀌어 평가 대상을 다시 정해야 한다.
- **담당 파일**: `scripts/analyze_ar_sessions.py`, `evaluate_ar_volume.py` 또는 새 스크립트, 새 문서, `data/private/ar/<새 폴더>/`.
- **완료 기준**: 그룹별 중량 오차와 확보율. AREA_HEIGHT, 깊이 부피 × 밀도, 2.78 × 부피식 비교.
- **물을 것**: 촬영 일정 / 평가 방식(AREA_HEIGHT 포함 여부) / 밀도(DensityGroups vs `ArDensity`).

### W07 ★★ S23 실기기 통합 흐름 확인 — E08, B05, C06, D01, G03~G05, H01
- **목표**: 가입 → 목표 → 촬영 → 분석 → 보정 → 집계 → 챗봇 전체 흐름을 실기기에서 확인하고 증거를 남긴다. 첫 시연 합격 조건(0/10)의 증거가 된다.
- **담당 파일**: 코드 수정 없음. 결과 문서 `docs/S23_FLOW_<날짜>.md`. 버그는 해당 카드나 새 카드로 넘긴다.
- **완료 기준**: 단계별 성공/실패, 화면 캡처 경로, 발견 버그 목록.
- **물을 것**: 테스트할 식단·계정 / OpenAI 호출 허용 범위.

### W08 ★ MySQL 실제 검증 — K02, F09
- **담당 파일**: `compose.yml`, `deploy/`, `.env.example`, `application*.yml`의 MySQL 부분, 새 문서.
- **완료 기준**: Flyway 성공, 적재 건수가 H2와 일치, 검색 결과 동일, 테스트 결과 기록. 서버는 8080 대신 다른 포트 권장.
- **물을 것**: Docker 사용 가능 여부 / 포트.

### W09 ★ AWS 배포·비용 — K03~K09
- **목표**: 월 5만원 이하로 EC2·RDS·비공개 S3·HTTPS 배포, release 서명 APK.
- **선행**: W08. **자원 생성 전 반드시 사용자 승인(비용 발생)**.
- **물을 것**: 계정·리전·도메인·예산 알림 기준.

### W10 문서 관리 — 11/08 통합 완료
- 11/08: 운영 문서 14개를 이 문서로 통합, 체크리스트 재집계. 이후에는 각 카드가 끝날 때 이 문서와 체크리스트를 함께 갱신한다(10장 8번).

### W11 ★ 보안·복구 시험 — J04~J07
- **목표**: 악성 입력(대용량, 사진 속 지시문, 후보 밖 식품 ID), 단말 민감정보, 백업·복구 시험.
- **담당 파일**: `backend/src/test/`의 새 테스트 클래스, 새 문서.
- **완료 기준**: 시나리오별 결과 표, 발견 사항 목록.

### W12 ★ 한식 분류 모델 추가 학습 — M04
- **목표**: AI Hub 한식 이미지(`data/food-images`)로 Food-101 계열 모델 확장.
- **담당 파일**: `scripts/evaluate_food_classifiers.py`, `export_food101_android.py`, 새 학습 스크립트, 새 문서. 앱 연결은 결과 후 별도.
- **물을 것**: 클래스 목록, 학습 환경(GPU), 학습/평가 분할.

**파일 충돌표**

| 파일 | 담당 | 같이 필요할 수 있는 카드 |
|---|---|---|
| `ai/OpenAiClient.java` | W02 | W01(분석 프롬프트) |
| `mobile/lib/features/ar_weight_screen.dart` | W02 | W03·W04 결과 적용 시 |
| `mobile/lib/features/screens.dart` | W01 | W07에서 발견한 버그 |
| `food/FoodCategoryService.java` | W05 | 없음 |
| `meal/MealService.java` | W02 | W05(밀도 연동 확인만) |
| `docs/PROJECT_GUIDE.md`, `docs/PROJECT_CHECKLIST.md` | 각 카드 종료 시 | 동시에 고치지 않는다 |
| `scripts/` 기존 파일 | 읽기 공유 | 수정은 새 파일로 |

동시에 시작해도 되는 묶음: W01 + W03 + W05 (파일이 겹치지 않음). W02·W06·W07은 S23과 사용자 일정에 맞춰 하나씩 한다.

---

## 12. 실험·기록 문서 목록

당시 결과의 기록이다. 결론 중 현행에 반영된 내용은 위 장에 있다.

**AR 깊이·부피·중량**

| 문서 | 날짜 | 요지 |
|---|---|---|
| [AR_DIAGNOSTICS](AR_DIAGNOSTICS.md) | 10/05 | S23 AR 진단 화면과 저장 형식(depth.u16le, confidence.u8, YUV, metadata) |
| [AR_VOLUME_EXPERIMENT](AR_VOLUME_EXPERIMENT.md) | 10/28 | PC 부피 계산 도구(수동 영역 → 기준면 → 적분 → 밀도) |
| [FOOD_SEGMENTATION](FOOD_SEGMENTATION.md) | 10/29 | Gemma 위치 → MobileSAM 경계 → AR 부피 PC 연결 |
| [AR_S23_MEASUREMENT](AR_S23_MEASUREMENT.md) | 11/02 | AR 연구 화면 사용법, 기기 내 부피 계산, 보정/평가 분리, ZIP 구조 |
| [AR_DEPTH_IMPROVEMENT](AR_DEPTH_IMPROVEMENT_2026-11-04.md) | 11/04 | 깊이 재투영·프레임 결합 v3. 쿠키-A 관측률 0% → 20.9% |
| [PROVISIONAL_PEANUT_WEIGHT](PROVISIONAL_PEANUT_WEIGHT_2026-11-04.md) | 11/04 | 커피땅콩 임시식 0.73 × 관측 부피(2건, 폐기됨) |
| [PEANUT_ADDITIONAL_REVIEW](PEANUT_ADDITIONAL_REVIEW_2026-11-04.md) | 11/04 | 커피땅콩 D·E·F 검토. E만 품질 통과 |
| [AR_RECOLLECTION_M11](AR_RECOLLECTION_M11.md) | 11/04 | 촬영 규칙과 재수집 목록(수집 대기) |
| [API_FIXED_NAME_WEIGHT](API_FIXED_NAME_WEIGHT_2026-11-04.md) | 11/04 | M03 음식명 고정 API 중량 126%/194% |
| [API_AREA_WEIGHT](API_AREA_WEIGHT_2026-11-05.md) | 11/05 | M12 면적 제공·높이만 질문. 높이 방식 63% |
| [API_AUTO_OUTLINE](API_AUTO_OUTLINE_2026-11-05.md) | 11/05 | M13 자동 윤곽 10건 중 1건 일치 |
| [W01_CANDIDATE_FILTER](W01_CANDIDATE_FILTER_2026-11-08.md) | 11/08 | Food-101 낮은 점수 후보 제외 후 커피땅콩 정답 2/15 (개선 없음) |
| [SEGMENTATION_OUTLINE](SEGMENTATION_OUTLINE_2026-11-09.md) | 11/09 | MobileSAM·GrabCut 자동 윤곽: 흰 접시 쿠키·땅콩은 손 그리기보다 경계가 정확해 보임. 꽃무늬 접시·유리 그릇 실패. 높이를 마스크 기준으로 다시 물어야 중량 비교 가능 |
| [AREA_OUTLINE_SENSITIVITY](AREA_OUTLINE_SENSITIVITY_2026-11-09.md) | 11/09 | 윤곽 민감도: 덮인 비율 108%(악화), 깊이 부피 89%(윤곽에 둔감하나 부정확), 현재 63% |

**음식 인식·로컬 모델**

| 문서 | 날짜 | 요지 |
|---|---|---|
| [LOCAL_VLM](LOCAL_VLM.md) | 10/28 | PC SmolVLM 실행(채택 보류) |
| [GEMMA4](GEMMA4.md) | 10/29 | Gemma 4 E2B 선정·PC/Android 실행 설정 |
| [S23_VALIDATION](S23_VALIDATION_2026-10-30.md) | 10/30 | S23 Gemma CPU 14.94초 vs API 2.8~3.5초 |
| [GPU_MULTI_FOOD](GPU_MULTI_FOOD_2026-11-02.md) | 11/02 | S23 Gemma GPU 6장, 재사용 시 약 2.1초 |
| [API_GPU_COMPARISON](API_GPU_COMPARISON_2026-11-02.md) | 11/02 | 같은 6장 API·Gemma GPU 비교 |
| [EVAL50](EVAL50_2026-11-02.md) | 11/02 | 50종 세부명 일치 API 58%, Gemma 16% |
| [GEMMA_ACCURACY_DIAGNOSIS](GEMMA_ACCURACY_DIAGNOSIS_2026-11-02.md) | 11/02 | Gemma 저하 원인 분석 |
| [FOOD_CLASSIFIER_EVAL50](FOOD_CLASSIFIER_EVAL50_2026-11-02.md) | 11/02 | 분류 모델 PC Top-1 86~90% |
| [MODEL_COMPARISON_30](MODEL_COMPARISON_30_2026-11-02.md) | 11/02 | 30장: API 53%, Gemma 20%, Food-101 S23 93% |

**보고서 원본** (`docs/`): Spec 보고서 09-28·10-26 수정본, 중간보고서 10-26 수정본·11-02 모델 평가 반영본(docx/pdf/hwp), `spec-qa/`.

---

## 13. 변경 이력

- 2026-11-08: 운영·기능 문서 14개를 이 문서로 통합. 체크리스트 재집계(99개 중 완료 38), M12·M13 추가, ACCEPTANCE를 체크리스트로 이동. W02를 실기기 검증 카드로 갱신, W10 완료.
- 2026-11-08: W01 원인 분석과 Food-101 낮은 점수(0.6 미만) 후보 제외 구현(4.1장).
- 2026-11-10: AR 윤곽을 사용자 누르기 + MobileSAM으로 변경(AI 박스 제거, 촬영당 OpenAI 호출 1회 감소).
- 2026-11-10: AR 윤곽 초안을 AI 박스 + MobileSAM(기기 ONNX)으로 변경, 실패 시 깊이 다듬기.
- 2026-11-10: AR 높이를 기기 측정(깊이 → 특징점) 우선, AI는 대체로 변경. `POST /ar/preview`, `heightMethod` 기록 추가. 실측 검증 전 적용(사용자 결정).
- 2026-11-10: 분할 후속 실험(마스크 기준 높이 30회, AI 사각형 입력, S23 MobileSAM 처리 시간) 기록 추가.
- 2026-11-09: 자동 윤곽 분할(MobileSAM·GrabCut) PC 비교 기록 추가(W04).
- 2026-11-09: 윤곽 민감도 실험(덮인 비율·깊이 부피) 기록 추가. 둘 다 채택하지 않음.
- 2026-11-09: AR 거리 대안으로 ARCore 특징점 저장·사용(흰 그릇 대응).
- 2026-11-09: AR 거리 오측정(흰 책상, 270.6cm) 분석. `ArVolume.scale` 신뢰도 32 필터·10~100cm 범위 검사, AR 화면 오류 문구 한국어화.
- 2026-11-09: 땅콩→아몬드 분석. 원재료 범주 이름 프롬프트(v4), 원재료 대분류 매칭, 종류 칩·가공식품 검색(4.2장).
