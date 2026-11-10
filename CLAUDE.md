# Foodify

음식 사진 기반 식단 관리 졸업프로젝트. Flutter Android 앱(`mobile/`, Galaxy S23 대상) + Spring Boot 서버(`backend/`, Java 21) + 실험 스크립트(`scripts/`).

## 먼저 읽을 문서
현행 기준 문서는 아래 두 개뿐이다. 날짜가 붙은 실험 보고서는 당시 기록이며 목록은 PROJECT_GUIDE 12장에 있다.
- 프로젝트 안내: [docs/PROJECT_GUIDE.md](docs/PROJECT_GUIDE.md) — 분석 구조·중량·DB 매칭·기능·API·확정 정책·결정 근거·실행 환경·작업 카드(W01~W12)
- 전체 진척: [docs/PROJECT_CHECKLIST.md](docs/PROJECT_CHECKLIST.md) — 99개 항목 + M 영역(M01~M13), 시연·평가 기준
- 결정·진척이 바뀌면 두 문서를 함께 고친다.

## 현재 분석 구조 (2026-11-10 기준)

### AR 중량 = 위에서 본 면적 × 평균 높이 × DB 대분류 밀도 (`method=AREA_HEIGHT`)
1. **촬영** (`ArDiagnosticActivity`): 바로 위(기울기 10° 이내)에서 5초. 프레임마다 깊이·신뢰도·카메라 자세와 함께 **ARCore 특징점**(`point-cloud.f32le` 세계 좌표 m·신뢰도, `point-cloud-ids.i32le`)을 저장한다. 11/09 이전 기록에는 특징점 파일이 없다.
2. **윤곽** (`ar_weight_screen.dart` → 기기 `SamSegmenter`): 사용자가 사진에서 음식을 누르면(음식/제외 점, 최대 20개) MobileSAM이 그 점으로 마스크를 만든다(`segmentPoints`, `source=tap_sam`). 사진 인코딩은 촬영 직후 1회(`prepareSegment`, S23 약 1초), 누를 때마다 디코더만 돈다(약 0.05초). 여러 조각이면 2~3곳을 눌러야 전체가 잡힌다. 떨어진 조각은 조각마다 볼록 다각형(`SamGeometry.polygons`, 최대 30개)이라 빈틈이 면적에서 빠진다. 손으로 그리기(`food_region_screen.dart`)는 예비. **사용자가 "윤곽이 맞아요"를 누르거나 직접 그린 뒤에만 계산한다.**
   - AI 박스(`/ar/food-box`)는 쓰지 않는다. gpt-4o-mini가 사진 가운데의 둥근 좌표를 주는 경향이 있어 위치가 자주 틀렸다(M13, 11/10 S23 설탕 기록).
   - 모델: `mobile/android/app/src/main/assets/mobilesam/` (ONNX 2개 약 45MB는 Git 제외, `manifest.json`의 SHA-256로 첫 사용 시 검증 후 `files/models/mobilesam/`에 복사). 변환: `scripts/export_mobile_sam_android.py`. 전처리는 SamPredictor와 같다(긴 변 1024, 평균·표준편차 정규화, 오른쪽·아래 0 패딩).
3. **거리·면적** (`ArVolume.scale`): 윤곽 바깥 띠(접시 영역을 그렸으면 그 영역에서 음식 제외)의 **신뢰도 32 이상** 깊이 중앙값. 20점 미만이면(흰 그릇·흰 책상) 특징점으로 잰다(`distanceSource=feature_points_plate`, 음식 위 점뿐이면 `feature_points_food`). 거리가 10~100cm 밖이면 서버로 보내지 않고 재촬영을 안내한다.
4. **평균 높이** (`ArVolume.measuredHeight`): 접시 기준 거리에서 음식 윤곽 안의 신뢰 깊이(20점·윤곽 30% 이상, `DEPTH`) → 부족하면 음식 위 특징점 6개 이상(`FEATURE_POINTS`)으로 잰다. 그러면 `POST /api/v1/ar/preview`로 예상 중량만 받는다(AI 호출 없음). 못 재면(1mm 미만, 거리가 음식 윗면 기준 등) `POST /api/v1/ar/height`로 gpt-4o-mini에게 평균 높이를 3회 묻고 중앙값을 쓴다(0~30cm).
   - **실측 검증 전에 사용자 결정으로 적용했다.** 기존 기록상 깊이 높이는 필요한 높이보다 2~13배 작았다(얇은 음식·유리 그릇은 중량 과소 가능). 특징점 높이는 미검증이고 흩어진 음식에서 클 수 있다. 저장 기록의 `heightMethod=DEPTH|FEATURE_POINTS|AI`로 방식별 오차를 모아 비교한다.
5. **저장** (`MealService.applyArMeasurement`): 서버가 `면적 × 높이 × 밀도`로 다시 계산하고 `heightMethod`·`heightSource`·`heightSampleCount`를 기록한다. 깊이 부피는 `depthVolumeMl`로 참고만 한다. 이전 `AR_VOLUME` 기록도 읽는다.
- 밀도: `DensityGroups`(밥 0.73, 면 0.59, 국 1.04, 반찬 0.70, 빵·과자 0.42, 견과 0.63, 음료 1.03, 소스 1.12, 미확인 0.73)
- 중량 오차의 가장 큰 원인은 **높이**다(AI 높이 과대·흔들림: 땅콩 필요 0.3~1.0cm, 응답 1.2~3.6cm). 덮인 비율 질문(108%)과 깊이 부피(89%)는 채택하지 않았다([AREA_OUTLINE_SENSITIVITY](docs/AREA_OUTLINE_SENSITIVITY_2026-11-09.md), [SEGMENTATION_OUTLINE](docs/SEGMENTATION_OUTLINE_2026-11-09.md)).

### 음식 인식 (일반 촬영·갤러리)
- 기기 Food-101 상위 3개 후보 → gpt-4o-mini가 음식명 결정(`OpenAiClient.analyze`, 프롬프트 `v4-category-names`) → 사용자 확인.
- Food-101 1순위 점수가 `app.food-recognition.min-top-score`(기본 0.6, 환경변수 `FOOD_RECOGNITION_MIN_TOP_SCORE`) 미만이면 그 사진의 후보를 프롬프트에서 빼고 "101종 밖일 가능성"을 안내한다(`FoodRecognitionEvidence.forPrompt`). 저장되는 `capture_info`에는 원래 후보가 남는다. 이것만으로 커피땅콩 오인식은 해결되지 않았다(2/15, [W01_CANDIDATE_FILTER](docs/W01_CANDIDATE_FILTER_2026-11-08.md)).
- 프롬프트 v4: 견과·과일·채소·버섯·해조·어패류처럼 비슷한 원재료는 종류가 확실하지 않으면 범주 이름('견과류')으로, 코팅·당·양념이 보이면 이름에 드러내게(커피땅콩) 한다. 조리된 요리는 구체적인 요리명.
- 일반 촬영의 API 중량은 기본값(사용자 수정 전제)이다. 피드백·챗봇은 API를 쓴다.

### 음식 검색 순서 (`FoodService.search` → `FoodCategoryService.fallback`)
1. 이름 정확 일치
2. 원재료 대표식품명 일치: 생것 우선. '말린·볶은·삶은…' 같은 상태 단어를 인식한다. 대분류가 겹치면 항목이 많은 쪽을 고른다.
3. 원재료 대분류 이름 일치: '견과류'·'견과' → 견과 및 종실류 전체 중앙값(`samplePolicy=RAW_MAJOR`, 대표식품별로 생것이 있으면 생것만). '및'으로 묶인 대분류는 단어별로 나누고 '기타'는 뺀다.
4. 음식 범주 추정(이름 끝 일치)
5. 이름 포함 검색
- 원재료로 연결된 음식은 결과 화면에 같은 대분류의 **종류 칩**(`GET /foods/kinds?foodId=`, 항목 수 순 최대 40개, 앱은 8개 + 더 보기)과 **코팅·가공 식품 검색**(`GET /foods?query=…&scope=processed`, 가공식품만 이름 포함)이 나온다. 칩을 누르면 음식명을 칩의 `query`로 바꿔 저장 시 같은 범주로 재계산된다.

## 자주 쓰는 명령
- 서버 테스트: `cd backend; mvn test` (결과 `backend/target/surefire-reports`)
- 서버 빌드·실행: `cd backend; mvn -q package -DskipTests` 후 PowerShell에서 `$env:OPENAI_API_KEY=[Environment]::GetEnvironmentVariable('OPENAI_API_KEY','User'); $env:OPENAI_MODEL='gpt-4o-mini'; java -jar target/foodify-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,demo`. backend 폴더에서 실행해야 H2 파일(`backend/data/foodify.mv.db`)을 이어 쓴다. 상세는 `docs/PROJECT_GUIDE.md` 9장.
  - 에이전트가 백그라운드로 띄운 서버는 **최대 2시간 뒤 자동 종료**된다. 오래 쓸 때는 사용자가 별도 PowerShell 창에서 실행한다.
  - 8080은 공유 자원이다. 재시작 전 사용 중인지 확인하고, 확인용 임시 서버는 `--server.port=8081 --app.worker-enabled=false`로 띄운 뒤 반드시 끈다(H2 파일 잠금).
- 앱 테스트·빌드: `cd mobile; flutter test; flutter build apk --debug --dart-define=API_BASE_URL=http://127.0.0.1:8080/api/v1` (flutter: `C:\flutter_windows_3.29.3-stable\flutter\bin\flutter.bat`). APK는 Food-101·MobileSAM 모델 포함 약 672MB.
- Kotlin 단위 테스트: `cd mobile/android; ./gradlew.bat :app:testDebugUnitTest --tests "*ArVolumeTest*" --tests "*SamGeometryTest*"` (결과: `mobile/build/app/test-results/testDebugUnitTest/`). `-q`로 오래 걸리면 `--console=plain`으로 백그라운드 실행.
- S23 설치: `C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe -s R3CW20JY6JN install --user 0 -r mobile\build\app\outputs\flutter-apk\app-debug.apk` 후 `adb reverse tcp:8080 tcp:8080`. USB 재연결 후 reverse 다시 실행.
- AR 세션 꺼내기: `adb exec-out run-as com.example.foodify tar -C files/ar-diagnostics -cf - <세션ID> > data/private/ar/<새 폴더>/<세션ID>.tar` (Git Bash는 `MSYS_NO_PATHCONV=1`). 화면 캡처는 Git Bash의 `adb exec-out screencap -p > 파일`(PowerShell 리다이렉션은 PNG를 깨뜨린다).
- 로컬 API 확인: `/api/v1/foods` 등은 인증이 필요하다(`GET /foods?query=`). `@test.invalid` 테스트 계정으로 가입해서 쓴다. AI 엔드포인트는 AI 동의(`PUT /me/ai-consent`)도 필요하다(`/ar/preview`는 불필요). 한글 쿼리는 curl 대신 Python `urllib`로 보낸다. 첫 검색은 범주 색인 생성으로 1~2분 걸린다.
- Python: PATH에 없음. `C:\Users\DELL\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe -X utf8` 사용. MobileSAM·Food-101 PC 실행은 `data/private/mobile-sam/python`·`data/private/food-classifier/python`을 `sys.path`에 넣는다(`scripts/compare_segmentation_outline.py` 참고). `onnx`·`onnxruntime`이 필요하면 스크래치 폴더에 `pip install --target`으로 설치하고 `sys.path` 끝에 붙인다(numpy 충돌 방지).

## 실험 스크립트 (11/08~11/10 추가, 결과는 `data/private/ar/<주제>-<날짜>/`)
- `compare_area_coverage_depth.py`: 덮인 비율 질문·깊이 부피의 윤곽 민감도(Kotlin 재생은 임시 단위 테스트)
- `compare_segmentation_outline.py`, `compare_segmentation_followup.py`: MobileSAM·GrabCut 윤곽, AI 박스 입력, 마스크 기준 높이 재질문
- `export_mobile_sam_android.py`: MobileSAM ONNX 변환
- 임시 코드(측정용 Kotlin 테스트·디버그 인텐트)는 쓰고 나서 지우고, 지운 뒤 `git diff`로 원래 파일이 그대로인지 확인한다.

## 규칙
- **모든 대답은 한국어로 한다.** 진행 안내, 결과 요약, 질문·선택지 문구까지 포함한다.
- `OPENAI_API_KEY`는 Windows 환경변수에 있다. 출력·파일 기록·커밋 금지.
- OpenAI 호출이 수십 회 이상인 실험은 호출 수·예상 비용을 먼저 알리고 승인받는다. AWS 자원 생성도 승인 필요.
- `data/private/`는 Git 제외 자료(AR 원본, API 응답, 평가 결과). 원본은 수정하지 않고 결과는 새 파일로 저장.
- 모델 파일(`assets/food101/*.onnx`, `assets/mobilesam/*.onnx`)은 Git에 올리지 않는다. `manifest.json`만 커밋한다.
- 공식 영양 데이터를 대신하는 가짜 값을 DB에 넣지 않는다. `volumeValidated`는 실측 검증 전 false 유지.
- 작업은 main 브랜치(`C:\Users\DELL\Desktop\Lectures\2026-02\Finalproject\Foodify`)에서만 한다. 별도 작업 브랜치를 만들지 않는다.
- 커밋은 사용자가 요청할 때만 한다. 커밋 전에 `git status`로 범위를 확인하고, 코드와 문서는 나눠서 커밋한다.
- 구현 전에 설계 세부를 사용자에게 묻는다(선택지 + 추천안). 음식별 설계보다 범주 단위 설계를 우선한다. 사용자가 "바로 진행"을 요청하면 기본값을 밝히고 진행하되, 기존 실험 결과가 반대 근거면 먼저 알린다.
- 새 실험은 날짜 붙은 문서(`docs/<주제>_<날짜>.md`, 목적 → 방법 → 결과 → 해석 → 결론 → 한계)로 쓰고 PROJECT_GUIDE 12장 목록에 한 줄 추가한다.
- 문서와 사용자 안내는 한국어로 작성한다.
