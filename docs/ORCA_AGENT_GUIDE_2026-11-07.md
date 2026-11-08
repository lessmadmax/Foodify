# Foodify 멀티 에이전트 작업 안내 (2026-11-07)

Orca에서 여러 AI 에이전트가 Foodify를 나눠 작업할 때 **모든 에이전트가 먼저 읽는 공통 문서**다. 프로젝트 전체 배경, 지금까지의 결정과 근거, 공통 규칙, 실행 환경, 남은 작업 카드를 담았다.

- 진척의 원본은 [PROJECT_CHECKLIST.md](PROJECT_CHECKLIST.md)다. 이 문서와 충돌하면 코드와 최근 검증 결과를 확인하고 둘 다 고친다. 단, 체크리스트 수정은 문서 담당 카드(W10)만 한다.
- 11/04 이전 세부 내용은 [HANDOFF_2026-11-04.md](HANDOFF_2026-11-04.md)에 있다.
- 에이전트는 자기 카드의 "담당 파일"만 수정한다. 다른 카드의 파일이 필요하면 오케스트레이터에게 요청한다.

---

## 1. 프로젝트 개요

**음식 사진 기반 식단 관리 앱** (졸업 프로젝트). 사용자가 한 끼를 찍으면 음식명·중량을 추정하고, 공공 영양 DB로 영양값을 계산해 목표와 비교하고 피드백을 준다.

| 구성 | 위치 | 기술 |
|---|---|---|
| Android 앱 | `mobile/` | Flutter(Dart) + Kotlin(ARCore, 기기 내 모델). 대상 기기 Galaxy S23(SM-S911N, Android 16) |
| 서버 | `backend/` | Spring Boot 3.5, Java 21, Flyway, H2(로컬)/MySQL(운영 예정) |
| 실험 스크립트 | `scripts/` | Python. AR 부피, API 비교, 모델 평가, 영양 데이터 정규화 |
| 문서 | `docs/` | 한국어. 실험 결과는 `docs/<주제>_<날짜>.md` |
| 비공개 자료 | `data/private/` | Git 제외. AR 원본, API 응답, 평가 결과 |
| 영양 원본 | `data/nutrition/raw/` | 식품안전나라 표준데이터 CSV 5개(cp949) → `processed/catalog.jsonl` |

### 현재 분석 구조 (11/04 결정, 11/05~07 보완)

```
인식:   기기 내 Food-101 분류 후보 → OpenAI(gpt-4o-mini)가 음식명 결정 → 사용자 확인·변경
중량:   일반 촬영은 API 추정(기본값, 사용자 수정 전제)
        AR 중량 화면: AR 부피(mL) × DB 대분류 밀도(DensityGroups) → 서버 계산
        음식 윤곽은 AI 박스 + 깊이 다듬기 초안을 사용자가 반드시 확인한 뒤 계산
DB:     이름 정확 일치 → 원재료 대표식품명 → 음식 범주 추정 → 이름 포함 검색 (+ 사용자 선택)
피드백: OpenAI API (S23 Gemma는 속도·품질 문제로 제외)
챗봇:   OpenAI API
```

---

## 2. 저장소 지도 (자주 건드리는 파일)

### 서버 `backend/src/main/java/com/example/foodify/`
| 파일 | 역할 |
|---|---|
| `api/AppController.java` | REST 엔드포인트(`/api/v1`): `/me`, `/me/goals`, `/foods`, `/meals`, `/analyses`, `/photos`, `/nutrition/summary`, `/ar/food-box`, `/feedback` |
| `api/ChatController.java`, `ChatService.java` | 식단 챗봇 `/api/v1/chat` |
| `auth/*` | 가입·로그인·갱신 토큰 회전·요청 제한(BCrypt) |
| `ai/OpenAiClient.java` | OpenAI Responses API. 사진 분석, DB 후보 재순위, 피드백, `locateFood`(AR 음식 박스) |
| `ai/AnalysisWorker.java` | DB 영속 작업 큐로 사진 분석 실행 |
| `ai/CaptureEvidence.java`, `RegionEvidence.java`, `FoodRecognitionEvidence.java` | 앱이 보낸 촬영 정보(AR 요약·영역·기기 분류 후보) 검증·정규화 |
| `food/FoodService.java` | 음식 검색(`search`)과 영양 계산(`calculate`) |
| `food/FoodCategoryService.java` | 범주 추정(대표식품·대분류 중앙값), 원재료 대표식품명 매칭, `majorOf` |
| `food/DensityGroups.java` | 대분류 → 밀도: RICE 0.73, NOODLE 0.59, SOUP 1.04, SIDE 0.70, BAKERY 0.42, NUT 0.63, DRINK 1.03, SAUCE 1.12, DEFAULT 0.73 |
| `meal/MealService.java` | 식단 수정·재계산, `applyArMeasurement`(부피 × 밀도) |
| `goal/GoalCalculator.java` | Mifflin–St Jeor × 활동 계수 자동 목표 |
| `storage/PhotoStore.java` | JPEG 재인코딩·EXIF 제거, 로컬/S3 |
| `resources/db/migration/V1~V5` | Flyway 스키마 |

### 앱 `mobile/`
| 파일 | 역할 |
|---|---|
| `lib/features/screens.dart` | 로그인·목표·촬영·식단·홈 등 주요 화면(큰 파일) |
| `lib/features/ar_weight_screen.dart` | AR 중량 화면: 촬영 → 자동 윤곽 초안 → **윤곽 확인 버튼** → 부피 계산 → 서버 저장 |
| `lib/features/food_region_screen.dart`, `lib/core/food_regions.dart` | 음식·접시 윤곽 수동 그리기·수정 |
| `lib/features/ar_research_screen.dart` | 연구용 AR 촬영(저울값 기록, 보정·평가 분리) |
| `lib/features/local_model_screen.dart`, `feedback_benchmark.dart` | 개발자 메뉴 Gemma 실험 |
| `lib/core/food_classifier.dart` | 기기 내 Food-101 분류 채널 |
| `android/.../ar/ArVolume.kt` | 기준면 적합, `foodFromBox`(박스 주변 기준면 → 5mm 이상 솟은 최대 덩어리 → 볼록 껍질), 부피 계산 |
| `android/.../ar/ArFusion.kt`, `DepthData.kt`, `ArGuidance.kt`, `ArDensity.kt` | 프레임 결합, 깊이 처리, 기울기·이동 안내, 연구용 밀도표 |
| `android/.../local/*` | Food-101 분류, Gemma(LiteRT-LM), MobileSAM 브리지 |

### 스크립트 `scripts/` (주요)
`prepare_nutrition.py`(영양 정규화), `ar_volume.py`·`evaluate_ar_volume.py`·`analyze_ar_sessions.py`·`pull_ar_session.py`(AR), `compare_api_weight*.py`(API 중량 실험 M02·M03·M12), `validate_auto_outline.py`(M13), `evaluate_food_classifiers.py`, `eval_feedback_llm.py`, `fit_provisional_weight.py`.

---

## 3. 진행 경과와 결정 기록

| 시기 | 내용 | 근거 문서 |
|---|---|---|
| 09월 말 | Flutter·Spring Boot 기반, 회원·목표·촬영·업로드·OpenAI 분석·식단 수정 구현. 공공 영양 DB 5종 적재(식품 85,337개, 영양 버전 110,939개) | [IMPLEMENTATION.md](IMPLEMENTATION.md), [NUTRITION_DATABASE.md](NUTRITION_DATABASE.md) |
| 10/04~07 | 전체 체크리스트 99개 작성. 중량 대표값·범위, 음식명 정규화 검색, 근거 충족 시 자동 완료 | [PROJECT_CHECKLIST.md](PROJECT_CHECKLIST.md), [WEIGHT_ESTIMATES.md](WEIGHT_ESTIMATES.md) |
| 10월 | AR 진단(S23 깊이·신뢰도), 수동 음식 영역·접시 기준면, 프레임 결합, AR 요약을 분석 근거로 전달 | [AR_DIAGNOSTICS.md](AR_DIAGNOSTICS.md), [AR_CAPTURE_INTEGRATION.md](AR_CAPTURE_INTEGRATION.md), [ANDROID_FOOD_REGIONS.md](ANDROID_FOOD_REGIONS.md) |
| 10/30~11/02 | Gemma 4 E2B S23 실행, 50종 API vs Gemma(세부명 일치 58% vs 16%), Food-101 분류 서비스 적용(S23 Top-1 44/50, 0.28초) | [GEMMA4.md](GEMMA4.md), [EVAL50_2026-11-02.md](EVAL50_2026-11-02.md), [FOOD101_SERVICE.md](FOOD101_SERVICE.md) |
| 11/04 | 분석 구조 결정. 피드백·챗봇은 API 유지(Gemma 15~23초, JSON 오류). AR 촬영 안내(기울기 10°) | [HANDOFF_2026-11-04.md](HANDOFF_2026-11-04.md) |
| 11/04 | M03: 음식명을 고정해도 API 중량 오차 중앙값 126% — "1인분 기준" 추정이 원인 | [API_FIXED_NAME_WEIGHT_2026-11-04.md](API_FIXED_NAME_WEIGHT_2026-11-04.md) |
| 11/05 | M12: 면적을 주고 **높이만** 물은 뒤 면적 × 높이 × 밀도로 계산하면 중앙값 63%(밥 15%, 쿠키 34%). 흩어진 음식(땅콩)은 142%로 악화 | [API_AREA_WEIGHT_2026-11-05.md](API_AREA_WEIGHT_2026-11-05.md) |
| 11/05 | M13: 자동 윤곽(AI 박스 + 깊이 다듬기)은 10건 중 1건만 맞음. 면적 비율 중앙값 0.25 → **사용자 윤곽 확인 필수**로 앱 변경 | [API_AUTO_OUTLINE_2026-11-05.md](API_AUTO_OUTLINE_2026-11-05.md) |
| 11/05 | 서버 중량 = AR 부피 × DB 대분류 밀도(DensityGroups)로 통일 | `MealService.applyArMeasurement` |
| 11/05 | S23 아몬드(실제 땅콩) 실측: 접시 위 17g → 10.8g. 부피(25.7mL)는 맞았지만 '아몬드'가 '기타빵_카라멜아몬드'로 매칭되어 빵 밀도 0.42 적용. 견과 0.63이면 16.2g(약 5%) | `data/private/ar/demo-confirm-2026-11-05/` |
| 11/07 | **검색 매칭 수정**(미커밋): 원재료 대표식품명 매칭 추가, 생것 우선, 상태 단어('말린·볶은·삶은…') 인식, 대분류 중복 시 항목 많은 쪽 | 아래 4장 |

### 확정된 정책 (바꾸려면 사용자 승인 필요)
1. 자동 윤곽은 초안일 뿐이다. 사용자가 "윤곽이 맞아요"를 누르거나 직접 그린 뒤에만 부피를 계산한다.
2. 중량을 API에 직접 묻는 방식은 측정값으로 쓰지 않는다(기본값·사용자 수정 전제).
3. 밀도는 음식별이 아니라 **대분류 단위**(DensityGroups)로 관리한다. 사용자는 음식별 설계보다 범주 단위 설계를 선호한다.
4. 영양값은 공식 DB 값 또는 그 범주·원재료 중앙값만 쓴다. 가짜 값을 DB에 넣지 않는다.
5. `volumeValidated`는 실측 검증 전까지 false.
6. 피드백·챗봇은 API. 기기 내 Gemma는 실험 기능으로만 남긴다.

---

## 4. 최근 변경 상세 (11/07, 미커밋)

파일: `backend/.../food/FoodCategoryService.java`, `backend/src/test/.../food/FoodFallbackTest.java` (서버 테스트 전체 통과, FoodFallbackTest 10개)

- 검색 순서: 이름 정확 일치 → **원재료 대표식품명 일치** → 음식 범주 추정(이름 끝 일치) → 이름 포함 검색.
- 원재료(`foods.category='원재료성 식품'`)를 `식품대분류명 + 대표식품명`으로 묶는다. 이름이 대표식품명과 같을 때만 적용한다('아몬드쿠키'는 해당 없음).
- 상태(`식품세분류명`): 생것 항목이 있으면 생것만, 없으면 전체(견과). 이름 앞 상태 단어(말린·건조·볶은·삶은·구운·찐·데친·생)가 있으면 그 상태만. 그 상태가 없으면 다음 단계로 넘어간다.
- 같은 대표식품명이 두 대분류에 있으면 항목이 많은 쪽, 같으면 매칭하지 않는다.
- 결과는 `matchType=CATEGORY_FALLBACK`, `samplePolicy=RAW_REPRESENTATIVE`, `rawState`, `majorCategory`, 이름 예 '바나나(생것)'. 상태별로 id가 다르고 `majorOf`가 대분류를 돌려줘 밀도가 이어진다.
- 실제 DB 확인: 아몬드·땅콩 → 견과 및 종실류(NUT 0.63), 바나나 77kcal(생것), 말린 바나나 314kcal, 고구마 → 감자 및 전분류. 기존 김치·배추김치·쌀밥·땅콩조림 결과는 같음.
- 남은 한계: 멸치는 상태 미지정 시 생것(108kcal), 달걀(50~333kcal)·소고기(102~484kcal)는 부위가 섞여 범위가 넓다.

---

## 5. 공통 규칙 (모든 에이전트)

1. **비밀값**: `OPENAI_API_KEY`는 Windows 사용자 환경변수에 있다. 출력·파일 기록·커밋 금지. PowerShell에서 `[Environment]::GetEnvironmentVariable('OPENAI_API_KEY','User')`로 프로세스에만 주입한다.
2. **비공개 자료**: `data/private/`는 Git 제외. 원본은 수정하지 않고 결과는 새 폴더·새 파일로 저장한다.
3. **세부 사항 먼저 질문**: 구현 전에 설계 세부를 사용자에게 묻는다(선택지 + 추천안). 범주 단위 설계를 우선 제안한다.
4. **커밋**: 사용자가 요청할 때만. 범위를 확인하고 자기 카드 파일만 포함한다. 커밋 메시지 끝에 기존 저장소 관례를 따른다.
5. **언어**: 문서·사용자 안내·앱 문구는 한국어.
6. **유료 호출**: OpenAI 호출이 수십 회 이상이거나 AWS 자원을 만드는 작업은 호출 수·예상 비용을 먼저 알리고 승인받는다.
7. **실험 문서 형식**: 목적 → 방법 → 결과(표) → 해석 → 결론 → 한계. 수치는 원자료 경로와 함께 적는다.
8. **공유 자원은 한 번에 한 에이전트**(6장 참고).

---

## 6. 실행 환경과 공유 자원

| 항목 | 값·명령 |
|---|---|
| 서버 테스트 | `cd backend; mvn test` (보고서: `target/surefire-reports`) |
| 서버 빌드·실행 | `mvn -q package -DskipTests` 후 `java -jar target/foodify-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,demo` (PowerShell에서 `$env:OPENAI_MODEL='gpt-4o-mini'`). 포트 8080, H2 파일 `backend/data/foodify.mv.db` |
| 앱 테스트·빌드 | Flutter `C:\flutter_windows_3.29.3-stable\flutter\bin\flutter.bat`. `cd mobile; flutter test; flutter build apk --debug --dart-define=API_BASE_URL=http://127.0.0.1:8080/api/v1` |
| S23 설치 | `adb.exe`(`C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools`), 시리얼 `R3CW20JY6JN`: `install --user 0 -r mobile\build\app\outputs\flutter-apk\app-debug.apk` 후 `reverse tcp:8080 tcp:8080` |
| 앱 AR 세션 꺼내기 | `adb exec-out run-as com.example.foodify tar -C files/ar-diagnostics -cf - <세션ID>` |
| Python | PATH에 없음. `C:\Users\DELL\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe -X utf8` (Pillow 있음) |
| Git Bash 주의 | `/sdcard` 경로는 `MSYS_NO_PATHCONV=1` 필요. 한글 쿼리 curl은 인코딩이 깨지므로 Python `urllib`로 호출 |
| 배치 파일 | ASCII로만 작성(`chcp 65001` + 한글 조합 오류) |
| 영양 CSV | cp949 인코딩 |
| `/api/v1/foods` | 인증 필요. 로컬 확인은 `@test.invalid` 테스트 계정으로 가입 후 토큰 사용 |

**공유 자원 (동시에 한 에이전트만, 오케스트레이터가 배정)**
- **포트 8080 서버 프로세스**: 재시작하면 다른 에이전트의 확인이 끊긴다. H2 파일은 실행 중 잠긴다.
- **S23 기기**: 설치·촬영·adb reverse. 촬영은 사용자가 직접 한다.
- **OpenAI 키·비용**.
- **`docs/PROJECT_CHECKLIST.md`**: W10만 수정. 다른 카드는 결과를 자기 문서와 보고에 남긴다.

---

## 7. 작업 카드

형식: 목표 / 배경 / 담당 파일(이 카드만 수정) / 선행 / 완료 기준 / 시작 전 사용자에게 물을 것.
우선순위: ★★★ 바로, ★★ 다음, ★ 나중.

### W01 ★★★ 음식 인식 오류 줄이기 (땅콩 → 아몬드) — M05 일부
- **목표**: 비슷한 음식(커피땅콩/아몬드)을 잘못 고르는 문제를 줄인다.
- **배경**: S23 실측에서 커피땅콩을 계속 '아몬드'로 인식. 이전 실험에서도 커피땅콩 30회 중 21회 오인식. 현재는 '음식 변경'으로만 고칠 수 있다.
- **담당 파일**: `mobile/lib/features/screens.dart`의 분석 결과·음식명 확인 부분, `backend/.../ai/FoodRecognitionEvidence.java`, 관련 테스트. `OpenAiClient.java`의 분석 프롬프트 수정이 필요하면 W02와 순서를 정한다.
- **선행**: 없음.
- **완료 기준**: 선택한 방식 구현, 테스트 통과, 기존 기록(커피땅콩 A~F)이나 S23 재촬영으로 전후 비교.
- **물을 것**: 분석 전 음식명 확인 단계 vs 후보 2~3개 동시 표시 vs 프롬프트 수정 / 확인 단계가 일반 촬영에도 적용되는지.

### W02 ★★★ 높이 방식의 앱·서버 연결 — M06
- **목표**: 면적 × AI 평균 높이 × 대분류 밀도 방식을 AR 깊이 부피의 대안 경로로 연결한다(깊이 품질이 낮을 때).
- **배경**: M12에서 한 덩어리 음식 오차 15~34%로 가장 정확. 윤곽 품질이 정확도를 좌우하므로 확인된 윤곽만 사용.
- **담당 파일**: `backend/.../ai/OpenAiClient.java`(높이 질문 메서드), `api/AppController.java`(새 엔드포인트), `meal/MealService.java`(중량 경로 기록), `mobile/lib/features/ar_weight_screen.dart`, 관련 테스트.
- **선행**: 없음(W03 결과가 나오면 흩어진 음식 처리 추가).
- **완료 기준**: 확인된 윤곽 → 높이 응답 → 서버 계산 → 결과에 경로(`AR_VOLUME`/`AREA_HEIGHT`)·근거 표시. 서버·Flutter 테스트 통과, S23에서 1건 이상 저울값과 비교.
- **물을 것**: 깊이 부피와의 선택 규칙(품질 보류 시만? 둘 다 표시?) / 호출 횟수(1회 vs 3회 중앙값) / 프롬프트 문구 / 거리값 출처(평면 hit-test vs 깊이 중앙값).

### W03 ★★ 흩어진 음식의 면적 보정 (실험)
- **목표**: 땅콩처럼 흩어진 음식의 윤곽 안 빈틈 때문에 면적·중량이 과대해지는 문제(M12에서 142%)를 줄인다.
- **배경**: 덩어리 윤곽은 빈틈을 포함한다. 후보는 조각별 윤곽의 합, API에 덮인 비율(%) 질문, 색 분할.
- **담당 파일**: `scripts/` 새 스크립트(기존 `compare_api_weight_area.py`, `validate_auto_outline.py`는 가져다 쓰기만), 새 문서 `docs/API_SCATTERED_FOOD_<날짜>.md`, 결과 `data/private/ar/<새 폴더>/`.
- **선행**: 없음. 커피땅콩 기록 5건(A·C·D·E·F) 사용.
- **완료 기준**: 방식별 중량 오차를 M12 결과와 같은 표로 비교하고 앱 적용 방식을 추천.
- **물을 것**: 비교할 방식 / OpenAI 호출 수 한도.

### W04 ★★ 자동 윤곽 품질 개선 (실험)
- **목표**: 사용자가 고칠 일이 적도록 자동 윤곽 초안을 개선한다.
- **배경**: M13: AI 박스(gpt-4o-mini) 위치 부정확(음식 이탈 2건, 못 찾음 2건), 깊이 다듬기가 접시 테두리·저울 창을 고름, 얇은 음식은 5mm 기준 때문에 일부만 잡힘.
- **담당 파일**: `scripts/` 새 스크립트, 새 문서. 실험 결과가 좋으면 `ArVolume.kt`(`foodFromBox`) 변경은 사용자 승인 후 별도로 진행.
- **선행**: 없음. `scripts/validate_auto_outline.py`의 재현 경로 재사용.
- **완료 기준**: 같은 10건에서 윤곽 일치 건수·면적 비율을 M13과 비교.
- **물을 것**: 후보(상위 모델 박스, AI 다각형·점, 색 분할, MobileSAM) / 호출 비용 한도.

### W05 ★★ 원재료 매칭 후속 정리
- **목표**: 11/07 검색 매칭의 남은 한계를 줄인다.
- **배경**: 멸치는 상태 미지정 시 생것, 달걀·소고기는 부위(식품소분류명)가 섞여 범위가 넓다. 원재료가 아닌 가공식품은 매칭 대상이 아니다.
- **담당 파일**: `backend/.../food/FoodCategoryService.java`, `FoodSearchTerms.java`, `backend/src/test/.../food/*`.
- **선행**: 11/07 변경 커밋 여부를 사용자에게 확인.
- **완료 기준**: 규칙 추가, 테스트, 실제 DB 검색 결과 표(이전/이후).
- **물을 것**: 부위 처리(소분류 '전체'·'육' 우선 등 범주 규칙) / 말려 먹는 재료의 기본 상태 규칙을 둘지.

### W06 ★★ AR 재수집 데이터 분석 — M11
- **목표**: 촬영 규칙(수직 10°·무광 평판·좌우 이동)으로 다시 모은 기록으로 부피·밀도 방식을 독립 평가한다.
- **배경**: 커피땅콩 평가 5그룹(15~100g) + 밥 보정 3·평가 2그룹, 그룹당 2회. 사용자가 촬영해야 한다.
- **담당 파일**: `scripts/analyze_ar_sessions.py`, `evaluate_ar_volume.py` 또는 새 스크립트, 새 문서, `data/private/ar/<새 폴더>/`.
- **선행**: 사용자 촬영. S23 사용은 오케스트레이터가 배정.
- **완료 기준**: 그룹별 부피·밀도 중량 오차와 확보율, 기존 2.78 × 부피식과 비교.
- **물을 것**: 촬영 일정 / 평가에 쓸 밀도(DensityGroups vs ArDensity).

### W07 ★★ S23 실기기 통합 흐름 확인 — E08, B05, C06, D01, G03~G05, H01
- **목표**: 가입 → 목표 → 촬영 → 분석 → 보정 → 집계 → 피드백 전체 흐름을 실기기에서 확인하고 증거를 남긴다.
- **담당 파일**: 코드 수정 없음. 결과는 새 문서 `docs/S23_FLOW_<날짜>.md`. 버그는 해당 카드나 새 카드로 넘긴다.
- **선행**: 서버·S23 배정, 사용자 조작.
- **완료 기준**: 단계별 성공/실패, 화면 캡처 경로, 발견 버그 목록.
- **물을 것**: 테스트할 식단·계정 / OpenAI 호출 허용 범위.

### W08 ★ MySQL 실제 검증 — K02, F09
- **목표**: H2가 아닌 실제 MySQL에서 마이그레이션·전체 영양 적재·검색·재적재를 검증한다.
- **담당 파일**: `compose.yml`, `deploy/`, `.env.example`, `backend/src/main/resources/application*.yml`(MySQL 부분), 새 문서.
- **선행**: Docker 환경. 서버 프로세스는 8080 대신 다른 포트 사용 권장.
- **완료 기준**: Flyway 성공, 적재 건수가 H2와 일치, 검색 결과 동일, 테스트 결과 기록.
- **물을 것**: Docker 사용 가능 여부 / 포트.

### W09 ★ AWS 배포·비용 — K03~K09
- **목표**: 월 5만원 이하로 EC2·RDS·비공개 S3·HTTPS 배포, release 서명 APK.
- **담당 파일**: `deploy/`, 새 운영 문서.
- **선행**: W08. **자원 생성 전 반드시 사용자 승인(비용 발생)**.
- **물을 것**: 계정·리전·도메인·예산 알림 기준.

### W10 ★★★ 문서·체크리스트 갱신 (문서 단독 담당)
- **목표**: 체크리스트를 최신으로 만든다.
- **반영할 것**: M12, M13, 윤곽 확인 필수(앱), 서버 밀도 통일, 아몬드 실측, 11/07 검색 매칭(F07·M07), 99개 집계 재산정, IMPLEMENTATION.md의 오래된 설명(모델명 gpt-4.1-mini, 사용자 확인 체크 등).
- **담당 파일**: `docs/PROJECT_CHECKLIST.md`, `docs/IMPLEMENTATION.md`, `docs/NUTRITION_DATABASE.md`, `docs/AR_CAPTURE_INTEGRATION.md`, `CLAUDE.md`의 문서 링크.
- **선행**: 없음. 다른 카드가 끝나면 그 결과를 이어서 반영한다.
- **완료 기준**: 상단 현황·영역별 건수·변경 이력이 서로 맞고, 각 완료 항목에 테스트명·산출물 경로·날짜가 있다.
- **물을 것**: 새 작업 ID 부여 방식(M12~ 연번).

### W11 ★ 보안·복구 시험 — J04~J07
- **목표**: 악성 입력(대용량, 사진 속 지시문, 후보 밖 식품 ID), 단말 민감정보, 백업·복구 시험.
- **담당 파일**: `backend/src/test/` 새 테스트 클래스만(본 코드 수정이 필요하면 담당 카드와 조율), 새 문서.
- **완료 기준**: 시나리오별 결과 표, 발견 사항 목록.

### W12 ★ 한식 분류 모델 추가 학습 — M04
- **목표**: AI Hub 한식 이미지(`data/food-images`)로 Food-101 계열 모델을 확장한다.
- **담당 파일**: `scripts/evaluate_food_classifiers.py`, `export_food101_android.py`, 새 학습 스크립트, 새 문서. 앱 연결(`FoodClassifierBridge.kt`, `food_classifier.dart`)은 결과 후 별도.
- **물을 것**: 클래스 목록, 학습 환경(GPU), 학습/평가 분할.

### 카드 간 파일 충돌표

| 파일 | 담당 | 같이 필요할 수 있는 카드 |
|---|---|---|
| `ai/OpenAiClient.java` | W02 | W01(분석 프롬프트) → W02 다음에 진행 |
| `mobile/lib/features/ar_weight_screen.dart` | W02 | W03·W04 결과 적용 시 |
| `mobile/lib/features/screens.dart` | W01 | W07에서 발견한 버그 |
| `food/FoodCategoryService.java` | W05 | 없음 |
| `meal/MealService.java` | W02 | W05(밀도 연동 확인만) |
| `docs/PROJECT_CHECKLIST.md` | W10 | 모든 카드(결과 전달만) |
| `scripts/` 기존 파일 | 읽기 공유 | 수정은 새 파일로 |

**동시에 시작해도 되는 묶음**: W01 + W03 + W05 + W10 (파일이 겹치지 않음). W02는 W01의 프롬프트 수정 여부가 정해진 뒤 시작한다. W06·W07은 S23·사용자 일정에 맞춰 하나씩 한다.

---

## 8. 에이전트 시작 프롬프트 (복사해서 사용)

```
당신은 Foodify 프로젝트의 <카드 ID: 제목> 담당 에이전트다.
1. CLAUDE.md, docs/ORCA_AGENT_GUIDE_2026-11-07.md(특히 3·5·6장과 <카드 ID>)를 먼저 읽어라.
2. 담당 파일 외에는 수정하지 마라. 필요하면 이유와 함께 오케스트레이터에게 요청하라.
3. 구현 전에 카드의 "물을 것"을 선택지와 추천안으로 사용자에게 물어라.
4. 서버 재시작, S23 사용, OpenAI 대량 호출, AWS 자원 생성은 오케스트레이터 배정과 사용자 승인 후에만 한다.
5. 커밋하지 마라(사용자 요청 시에만).
6. 끝나면 보고하라: 바꾼 파일, 테스트 결과(명령과 통과 수), 산출물 경로, 남은 문제, PROJECT_CHECKLIST.md에 반영할 내용.
```

## 9. 현재 상태 스냅샷 (2026-11-07)

- 최근 커밋: `3c9fdf3 modify: 중량 측정 개선`. 미커밋 변경: `FoodCategoryService.java`, `FoodFallbackTest.java`(11/07 검색 매칭), 이 문서.
- 서버: 11/07 빌드로 로컬 8080에서 실행 중일 수 있다. 시작 전 `netstat -ano | findstr :8080`으로 확인한다.
- S23: 윤곽 확인 필수 화면이 들어간 APK가 설치되어 있다. 11/07 검색 매칭은 서버 변경이라 앱 재설치는 필요 없다.
- 로컬 H2에 검색 확인용 `@test.invalid` 테스트 계정 2개가 있다.
- 첫 시연 합격 조건: 0/10 기록(체크리스트 하단). 실기기 증거를 W07에서 쌓는다.
