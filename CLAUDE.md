# Foodify

음식 사진 기반 식단 관리 졸업프로젝트. Flutter Android 앱(`mobile/`, Galaxy S23 대상) + Spring Boot 서버(`backend/`, Java 21) + 실험 스크립트(`scripts/`).

## 먼저 읽을 문서
- 멀티 에이전트(Orca) 공통 안내: [docs/ORCA_AGENT_GUIDE_2026-11-07.md](docs/ORCA_AGENT_GUIDE_2026-11-07.md) — 프로젝트 전체 경과·정책·공유 자원·작업 카드(W01~W12)·담당 파일
- 이전 인계: [docs/HANDOFF_2026-11-04.md](docs/HANDOFF_2026-11-04.md) — 확정한 분석 구조, 근거 수치, 바꾼 파일, 바로 할 일, 실행 환경 주의점
- 전체 진척: [docs/PROJECT_CHECKLIST.md](docs/PROJECT_CHECKLIST.md) — 상단 "분석 구조 결정"과 M 영역(M01~M11)이 최신

## 현재 분석 구조 (2026-11-08 기준)
- **AR 중량 = 위에서 본 면적 × AI 평균 높이 × DB 대분류 밀도** (`method=AREA_HEIGHT`, M12 방식)
  - 자동 윤곽(AI 박스 + 깊이 다듬기)은 초안이다. 사용자가 "윤곽이 맞아요"를 누르거나 직접 그린 뒤에만 계산한다.
  - 기기 `ArVolume.scale`: 윤곽 바깥 띠(접시 영역을 그렸으면 그 영역에서 음식 제외)의 깊이 중앙값으로 거리를 재고, 면적(cm²)을 구한다.
  - 서버 `POST /api/v1/ar/height`: gpt-4o-mini에게 평균 높이만 3회 동시에 묻고 중앙값을 쓴다(0~30cm). 예상 중량을 미리 돌려준다.
  - 저장할 때 `MealService.applyArMeasurement`가 중량을 다시 계산한다. 깊이 부피는 `depthVolumeMl`로 참고만 한다. 이전 `AR_VOLUME` 기록도 읽는다.
  - 밀도: `DensityGroups`(밥 0.73, 면 0.59, 국 1.04, 반찬 0.70, 빵·과자 0.42, 견과 0.63, 음료 1.03, 소스 1.12, 미확인 0.73)
- **음식 검색 순서** (`FoodService.search` → `FoodCategoryService.fallback`)
  1. 이름 정확 일치
  2. 원재료 대표식품명 일치: 생것 우선. '말린·볶은·삶은…' 같은 상태 단어를 인식한다. 대분류가 겹치면 항목이 많은 쪽을 고른다.
  3. 음식 범주 추정(이름 끝 일치)
  4. 이름 포함 검색
- 일반 촬영의 API 중량은 기본값(사용자 수정 전제)이다. 피드백·챗봇은 API를 쓴다.

## 자주 쓰는 명령
- 서버 테스트: `cd backend; mvn test`
- 서버 실행: `docs/LOCAL_S23.md` 참고 (`--spring.profiles.active=local,demo`)
- 서버 빌드: `cd backend; mvn -q package -DskipTests` 후 `java -jar target/foodify-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,demo` (PowerShell `$env:OPENAI_MODEL='gpt-4o-mini'`)
- 앱 테스트·빌드: `cd mobile; flutter test; flutter build apk --debug --dart-define=API_BASE_URL=http://127.0.0.1:8080/api/v1` (flutter: `C:\flutter_windows_3.29.3-stable\flutter\bin\flutter.bat`)
- Kotlin 단위 테스트: `cd mobile/android; ./gradlew.bat :app:testDebugUnitTest --tests "*ArVolumeTest*"` (결과: `mobile/build/app/test-results/`)
- S23 설치: `C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe install --user 0 -r mobile\build\app\outputs\flutter-apk\app-debug.apk` 후 `adb reverse tcp:8080 tcp:8080`
- 로컬 API 확인: `/api/v1/foods` 등은 인증이 필요하다. `@test.invalid` 테스트 계정으로 가입해서 쓴다. AI 엔드포인트는 AI 동의(`PUT /me/ai-consent`)도 필요하다. 한글 쿼리는 curl 대신 Python `urllib`로 보낸다.
- Python: PATH에 없음. `C:\Users\DELL\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe` 사용

## 규칙
- `OPENAI_API_KEY`는 Windows 환경변수에 있다. 출력·파일 기록·커밋 금지.
- `data/private/`는 Git 제외 자료(AR 원본, API 응답, 평가 결과). 원본은 수정하지 않고 결과는 새 파일로 저장.
- 공식 영양 데이터를 대신하는 가짜 값을 DB에 넣지 않는다. `volumeValidated`는 실측 검증 전 false 유지.
- 커밋은 사용자가 요청할 때만 한다. 커밋 전에 `git status`로 범위를 확인하고, 코드와 문서는 나눠서 커밋한다.
- 구현 전에 설계 세부를 사용자에게 묻는다(선택지 + 추천안). 음식별 설계보다 범주 단위 설계를 우선한다.
- 문서와 사용자 안내는 한국어로 작성한다.
