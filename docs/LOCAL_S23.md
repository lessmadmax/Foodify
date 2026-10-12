# S23 로컬 실행과 대체 영양 매칭

## 연결 원리

앱의 주소는 http://127.0.0.1:8080/api/v1 이다. 휴대폰의 localhost를 ADB reverse로 PC의 8080 포트에 연결한다. USB 연결, 디버깅 승인, PC 서버 실행을 유지해야 한다. 같은 Wi-Fi나 공유기 포트 개방은 필요하지 않다. USB 재연결 후 reverse를 다시 실행한다.

## PowerShell 창 1: 서버

```powershell
Set-Location 'C:\Users\DELL\Desktop\Lectures\2026-02\Finalproject\Foodify\backend'
java -version
mvn package
if ($LASTEXITCODE -ne 0) { throw '서버 빌드 실패' }
java -jar target/foodify-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,demo
```

Java 21 필요. 코드를 수정하지 않았으면 빌드 명령을 생략하고 마지막 명령만 실행한다. 창을 계속 열어 두며 종료는 Ctrl+C. backend 폴더에서 실행해야 기존 data/foodify 파일 DB와 사진을 이어서 사용한다. 기존 DB를 지우거나 다시 적재할 필요가 없다. 이미 서버가 실행 중이면 중복 실행하지 않는다.

API 키가 없다면 서버 실행 전에 안전한 입력창으로 설정한다. 키를 코드/APK/Git에 넣거나 화면에 출력하지 않는다. .env 파일은 서버가 자동으로 읽도록 구성되어 있지 않다.

```powershell
if (-not $env:OPENAI_API_KEY) {
    $foodifySecret = Read-Host 'OpenAI API 키' -AsSecureString
    $env:OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', $foodifySecret).Password
    Remove-Variable foodifySecret
}
$env:OPENAI_MODEL = 'gpt-4o-mini'
```

## PowerShell 창 2: 연결 확인

S23의 USB 디버깅을 켜고 케이블 연결 후 잠금을 풀어 디버깅을 승인한다.

```powershell
$foodifyAdb = 'C:\Users\DELL\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $foodifyAdb devices
& $foodifyAdb -s R3CW20JY6JN reverse tcp:8080 tcp:8080
& $foodifyAdb -s R3CW20JY6JN reverse --list
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/health'
```

devices에 R3CW20JY6JN device, reverse에 tcp:8080 tcp:8080, health에 UP이 표시되어야 한다. health는 PC 서버 확인이며 앱 로그인/기록 조회로 실제 앱 통신도 확인한다. AI 키가 없어도 health는 UP일 수 있다.

## 앱 수정 시 APK 빌드 및 설치

```powershell
Set-Location 'C:\Users\DELL\Desktop\Lectures\2026-02\Finalproject\Foodify\mobile'
& 'C:\flutter_windows_3.29.3-stable\flutter\bin\flutter.bat' build apk --debug --dart-define=API_BASE_URL=http://127.0.0.1:8080/api/v1
if ($LASTEXITCODE -ne 0) { throw 'APK 빌드 실패' }
& $foodifyAdb -s R3CW20JY6JN install --user 0 -r 'build\app\outputs\flutter-apk\app-debug.apk'
if ($LASTEXITCODE -ne 0) { throw 'APK 설치 실패' }
& $foodifyAdb -s R3CW20JY6JN reverse tcp:8080 tcp:8080
& $foodifyAdb -s R3CW20JY6JN shell am force-stop --user 0 com.example.foodify
& $foodifyAdb -s R3CW20JY6JN shell am start --user 0 -n com.example.foodify/.MainActivity
```

- -r은 앱 데이터를 유지하는 업데이트 설치다. APK는 로컬 시험용 debug 빌드다.
- 서버 주소 변경 시 재빌드 필요. 에뮬레이터용 10.0.2.2와 S23 USB 주소를 구별한다.
- devices가 비어 있으면 케이블/디버깅 확인, unauthorized이면 휴대폰에서 승인.
- health가 실패하면 서버 창 오류 확인. 포트 점유 확인은 Get-NetTCPConnection -LocalPort 8080 -State Listen. 다른 Java 프로세스를 일괄 종료하지 않는다.
- health는 정상인데 앱이 실패하면 reverse를 다시 적용하고 앱을 재실행한다.

## DB 상위 범주 기반 대체 계산

정확한 음식명 및 등록된 별칭의 계산 가능한 데이터가 우선이다. 없으면 FoodCategoryService가 공공 원본 자료의 식품대분류명 및 대표식품명에서 상위 범주를 찾는다. 특정 음식명과 특정 DB ID를 연결하는 고정 목록은 제거했다.

분류 인덱스는 기존 nutrition_source_rows와 food_nutrition_versions, foods에서 생성한다. 최신 원본에 분류가 없으면 분류가 있는 이전 버전의 메타데이터를 사용하며 영양값은 foods의 현재 값을 사용한다. 원본 중복 행은 식품 ID 단위로 합친다. 첫 검색 때 인덱스를 만들고 영양 자료 재적재 후 갱신한다.

음식명 끝부분과 DB 분류명을 비교하고 긴 분류명/좁은 대표식품 범주부터 적용한다. 예: 미등록 무청김치 → 김치류, 미등록 버섯볶음밥 → 볶음밥(해당 DB 분류가 있을 때). 김치볶음밥을 김치류로 묶는 중간 문자열 매칭을 피한다. 해당없음/기타/한 글자/복합 분류는 자동 추론 대상에서 제외한다. 의미를 자유롭게 이해하는 모델이 아니라 DB 명칭 기반 계층 검색이므로, 비정형 표현이나 DB 분류와 연결되지 않는 이름은 추가 보정이 필요하다.

해당 범주의 계산 가능한 음식(g 기준, 양수 기준량, 누락/음수 없는 영양값)을 모은다. 일반 음식 자료가 있으면 브랜드 항목보다 우선한다. 각 데이터를 100g으로 환산한 뒤 영양소별 중앙값을 계산한다. 1건이면 그 1건만 사용했다는 표본 수가 표시된다. 실제 조리 음식의 정확도나 통계적 신뢰구간을 보장하는 수치는 아니다.

source에 CATEGORY_FALLBACK, 범주명, MEDIAN_PER_100G, 표본 수, 구성 식품 ID, 원본 자료원, 범위, 데이터 해시 버전을 남긴다. category: 접두어의 ID는 합성된 범주 계산 근거이며 공공 DB 개별 항목 코드와 구분한다. 원래 음식명과 대체 안내를 유지하고 사용자 지정 개별 항목은 보존한다. 유효 중량이 있어야 완료된다. 범주 정보가 없거나 계산 가능한 표본이 없으면 기존 후보 검색/보정 경로를 사용한다. 과거 기록의 저장된 결과는 일괄 수정하지 않는다.
