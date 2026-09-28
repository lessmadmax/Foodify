# Foodify 구현 현황과 실행 안내

## 현재 구현 범위

이 저장소는 실제 서버·앱 구현을 포함한다. 전체 8주 계획의 최종 검증 완료 상태와 구분하여 사용한다.

| 영역 | 구현 | 추가 확인 |
|---|---|---|
| 회원 | BCrypt, 만료 접근 토큰, 갱신 토큰 회전, 로그아웃·탈퇴, 회원 소유권 검증 | 이메일 인증·비밀번호 복구는 공개 운영 단계 |
| 기록 | 사진 최대 3장, 멱등 업로드, 영속 작업, 상태 조회, 버전 기반 수정·삭제 | 갤럭시 네트워크 단절·종료 실험 |
| 분석 | OpenAI Responses, JSON Schema, 이미지 입력, 사용량 기록, 월 요청 수 제한 | 실제 API 키로 연결·한국 음식 성능 측정 |
| 영양 | DB 검색·OpenAI 후보 재순위·사용자 선택, 중량 계산·재계산, 자료원 스냅샷, 일간·주간 집계 | 공식 자료 적재·수록 메뉴 확인 |
| 피드백 | 사용자 요청 시 OpenAI 호출, 서버 집계·목표 차이, 기록 변경 감지 | 실제 출력 품질 평가 |
| 사진 | JPEG 재인코딩·EXIF 제거, 크기 제한, 로컬/S3 저장, 소유권 확인 후 응답, 삭제 재시도 | S3 권한과 카메라 회전 확인 |
| Android | 회원·목표·촬영·기록·보정·피드백 화면, 보안 토큰 보관, 업로드 대기, AR 기능 조회 채널 | 갤럭시 촬영·ARCore/Depth 지원 확인 |
| 배포 | 로컬 MySQL Compose, 서버 Dockerfile, Caddy HTTPS 및 RDS/S3 환경변수 구성 | AWS 생성·배포·TLS·복구·비용 점검 |

현재 자동 중량은 **사진에 대한 참고 추정**이다. 검증된 AR 부피·밀도 환산은 다음 실기기 실험 단계다. 음식 DB와 중량을 사용자가 확인한 항목을 완료 처리한다. 공식 영양 데이터와 실제 분석 결과를 대신하는 가짜 데이터는 기본 DB에 넣지 않는다.

## 서버 실행

Java 21과 Maven이 필요하다. 프로젝트 루트에서 다음과 같이 실행한다.

```powershell
cd backend
mvn test
mvn spring-boot:run "-Dspring-boot.run.profiles=local,demo"
```

`demo`는 로컬 H2 파일 DB를 사용하는 기능 확인 환경이다. 실제 MySQL 검증과 별개이며, 데이터는 `backend/data`에 저장한다. `local` 서버는 기본적으로 127.0.0.1에 바인딩된다.

MySQL 개발환경에서는 루트의 `.env.example`을 `.env`로 복사하고 비밀번호를 설정한 뒤 `docker compose up -d mysql`을 실행한다. 서버 프로세스에도 `DB_PASSWORD`를 환경변수로 설정하고 `demo` 없이 실행한다. Spring Boot는 루트 `.env`를 자동으로 읽지 않는다.

OpenAI는 서버 프로세스에 `OPENAI_API_KEY`를 설정한다. 키를 채팅·소스·앱에 넣지 않는다. 초기 모델은 `gpt-4.1-mini`, 변경은 `OPENAI_MODEL` 환경변수를 사용한다. 이 모델은 초기 연결 기준이며 성능 비교의 최종 선정 결과가 아니다.

Responses 요청은 `store:false`를 사용한다. 이는 Responses 객체 저장 설정이며 공급자의 모든 데이터 보존 정책을 무효화하는 옵션은 아니다.

## 앱 실행

```powershell
cd mobile
flutter pub get
flutter test
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080/api/v1
```

위 주소는 Android 에뮬레이터용이다. USB 갤럭시는 `adb reverse tcp:8080 tcp:8080` 후 `http://127.0.0.1:8080/api/v1`을 사용한다. 같은 Wi-Fi의 PC 주소를 사용할 경우 서버 바인딩·방화벽을 별도로 확인한다. HTTP는 debug manifest에서만 허용하며 운영 앱은 HTTPS를 사용한다.

AR 지원 조회는 `foodify/ar` 채널의 `capabilities`이다. 지원 서비스 설치 상태·깊이 지원 가능 여부·기종을 반환하며 `volumeValidated`는 실측 검증 전 false다.

## 영양 데이터 적재

공식 CSV/엑셀 자료를 검토하여 UTF-8 JSONL로 정규화한다. 한 줄에 다음 키를 사용한다.

`id, name, aliases, basisGrams, kcal, carbs, protein, fat, source, sourceVersion`

- 영양값은 반드시 `basisGrams`에 해당하는 값이어야 한다.
- 없는 영양값은 null로 둔다. 0과 구분한다.
- 1회 제공량 자료는 해당 제공량의 g 값이 확인된 경우에 변환한다.
- `id`에는 원본 식품 코드를, 자료원과 자료 버전을 함께 보관한다.
- 적재 명령은 `mvn spring-boot:run "-Dspring-boot.run.arguments=--app.import-foods=C:/path/foods.jsonl"`이다. 필요한 DB 프로필과 환경변수를 함께 지정한다.
- 재적재는 동일 코드를 갱신한다. 기존 기록의 영양 기준 스냅샷은 유지한다.

공식 다운로드: https://various.foodsafetykorea.go.kr/nutrient/general/down/list.do

## API 계약

서버의 `/v3/api-docs`는 로그인한 Bearer 토큰으로 조회할 수 있다. 업로드는 multipart `images`(1~3개), `eatenAt`(epoch ms), `captureInfo`(JSON 객체 문자열), `consent`(true), `Idempotency-Key` 헤더를 사용한다.

수정은 `PATCH /api/v1/meals/{id}`에 `version`과 `items` 배열을 전달한다. 음식 항목은 `name`, `foodId`, `grams`, `confirmed`를 사용한다. 서버가 `nutrition`, `source`, `status`를 계산한다. 사용자가 보낸 영양 숫자는 계산 근거로 사용하지 않는다.

상태는 `QUEUED`, `RUNNING`, `COMPLETE`, `INCOMPLETE`이다. 분석이 새 수정본으로 대체되면 작업은 `SUPERSEDED`로 표시한다. 저장 버전이 다르면 409 `STALE_VERSION`을 반환한다. 기록 날짜 범위는 양 끝을 포함하며 한국 시간으로 계산한다.

조회 응답의 저장 필드는 현재 `eaten_at`, `capture_info`, `error_code`와 같이 snake_case, 영양·입력 항목은 앱 코드의 계약에 따른다. API 이름을 변경할 때 앱과 함께 변경한다.

## 운영 전 점검

- Docker 실제 MySQL에서 전체 테스트를 반복한다. 기본 테스트는 H2 MySQL 호환 모드이며 동일 제품 검증을 대체하지 않는다.
- RDS는 private subnet, 서버 security group만 3306 접근 허용한다.
- S3는 public access block과 암호화를 적용한다. EC2 instance role에 대상 bucket의 Get/Put/Delete만 허용한다.
- 현재 S3 사진 전달은 서버 인증 프록시 방식이다. 임시 공개 URL 대신 매 요청 소유권을 확인한다.
- EC2 컨테이너가 instance role을 이용할 수 있도록 IMDSv2와 hop limit을 점검한다.
- `deploy/compose.yml`의 환경변수는 실제 자원 생성 후 설정한다. 배포 명령은 `docker compose --env-file .env -f deploy/compose.yml up -d --build`이다.
- 예산 알림은 AWS 콘솔에서 2.5만·4만·5만원으로 설정하고 크레딧·세금·스토리지·중지 후 자동 시작 비용을 확인한다.
- 현재 AI 한도는 요청 횟수 제한이다. 원화 비용 상한 보장은 토큰 단가·환율·실제 청구액을 확인한 뒤 별도 적용한다. `ai_usage`에서 입력·출력 토큰·시간·실패를 확인한다.
- 로그인은 단일 인스턴스 IP 기준 분당 20회 제한이다. 프록시 뒤에서는 여러 사용자가 한 IP로 묶일 수 있어 공개 운영 전 신뢰 프록시 설정을 점검한다.
- 데이터 백업과 S3 보존·삭제 정책, 삭제 대기 작업, 서버 재시작 후 복구를 실제 환경에서 시험한다.

## 다음 실험과 남은 구현

1. 실제 OpenAI 키로 한 끼 사진 1건을 분석하고 응답·처리시간·요금을 확인한다.
2. 공식 영양 자료를 정규화·적재하고 음식 후보 검색 정확도를 측정한다.
3. 갤럭시에서 촬영·ARCore 설치·카메라 권한·깊이 데이터를 확인한다.
4. 음식 영역 분리·부피 적분·밀도 환산과 실측 평가를 구현한다.
5. RAG 후보 선택 결과를 고정 평가 자료로 검증한다. 서버는 검색 후보에 포함된 식품 코드만 받아들인다.
6. 금액 기반 예산 제한을 보완한다. 현재는 월 요청 수 제한, 다음 달 대기 재개, 일시 오류 최대 3회 시도(5초·20초 간격), 명시적 재분석으로 동작한다.
7. 공개 배포용 서명키를 준비하고 release APK, 실기기 회귀, AWS 배포를 검증한다.

서명키는 `mobile/android/key.properties`의 `storeFile`, `storePassword`, `keyAlias`, `keyPassword`로 설정한다. 서명키 파일과 비밀번호는 버전 관리에서 제외한다. release 빌드는 이 설정이 있을 때 진행하며 debug 키를 운영용으로 대체 사용하지 않는다.

참고: https://developers.openai.com/api/docs/guides/images-vision
https://developers.openai.com/api/docs/guides/structured-outputs
