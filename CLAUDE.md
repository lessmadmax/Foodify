# Foodify

음식 사진 기반 식단 관리 졸업프로젝트. Flutter Android 앱(`mobile/`, Galaxy S23 대상) + Spring Boot 서버(`backend/`, Java 21) + 실험 스크립트(`scripts/`).

## 먼저 읽을 문서
- 최근 인계: [docs/HANDOFF_2026-11-04.md](docs/HANDOFF_2026-11-04.md) — 확정한 분석 구조, 근거 수치, 바꾼 파일, 바로 할 일, 실행 환경 주의점
- 전체 진척: [docs/PROJECT_CHECKLIST.md](docs/PROJECT_CHECKLIST.md) — 상단 "분석 구조 결정"과 M 영역(M01~M11)이 최신

## 자주 쓰는 명령
- 서버 테스트: `cd backend; mvn test`
- 서버 실행: `docs/LOCAL_S23.md` 참고 (`--spring.profiles.active=local,demo`)
- 앱 테스트·빌드: `cd mobile; flutter test; flutter build apk --debug --dart-define=API_BASE_URL=http://127.0.0.1:8080/api/v1`
- S23 설치: `C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r mobile\build\app\outputs\flutter-apk\app-debug.apk` 후 `adb reverse tcp:8080 tcp:8080`
- Python: PATH에 없음. `C:\Users\DELL\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe` 사용

## 규칙
- `OPENAI_API_KEY`는 Windows 환경변수에 있다. 출력·파일 기록·커밋 금지.
- `data/private/`는 Git 제외 자료(AR 원본, API 응답, 평가 결과). 원본은 수정하지 않고 결과는 새 파일로 저장.
- 공식 영양 데이터를 대신하는 가짜 값을 DB에 넣지 않는다. `volumeValidated`는 실측 검증 전 false 유지.
- 작업 폴더에 이전부터 커밋하지 않은 변경이 많다. 커밋할 때 범위를 확인한다.
- 문서와 사용자 안내는 한국어로 작성한다.
