# Foodify

Flutter Android 앱과 Spring Boot 서버로 구성한 음식 사진 기반 식단 관리 프로젝트입니다.

- 앱: 이메일 로그인, 영양 목표, 사진 촬영, 업로드 재전송, 식단 결과 확인·보정, 집계, 요청 기반 피드백
- 서버: 회원 인증·소유권 검사, MySQL/Flyway, OpenAI Responses, 영양 DB 계산, 로컬/S3 사진 보관
- 문서: [실행 및 구현 현황](docs/IMPLEMENTATION.md), [검증 체크리스트](docs/ACCEPTANCE.md)

## 빠른 로컬 확인

```powershell
cd backend
mvn test
mvn spring-boot:run "-Dspring-boot.run.profiles=local,demo"
```

다른 터미널:

```powershell
cd mobile
flutter pub get
flutter test
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080/api/v1
```

`demo`는 H2 파일 DB이며 영양 자료를 가짜 값으로 채우지 않습니다. 실제 분석에는 서버의 `OPENAI_API_KEY`, 영양 계산에는 검증한 공공 영양 자료가 필요합니다. 실제 AWS 자원은 아직 생성하지 않았습니다.

**개발 단계:** 사용자 보정 기반 흐름을 구현했습니다. 갤럭시 실측 AR 부피 추정, 실제 음식 정확도 평가, 운영 배포는 후속 검증 단계입니다.
