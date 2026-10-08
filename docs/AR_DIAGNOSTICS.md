# S23 AR 깊이 진단

2026-10-05: **사진 촬영**에 AR 보조 촬영을 통합하고 진단은 설정의 개발자 메뉴로 이동했다. 대표 사진과 깊이 요약의 서버 전달 범위는 [연결 안내](AR_CAPTURE_INTEGRATION.md)를 참고한다. 아래 로컬 보관 설명은 개발자 진단 기준이다.

## 범위

홈 우측 메뉴 → 설정 → 개발자 메뉴의 **AR 깊이 진단 · 실험** 버튼으로 Kotlin ARCore 화면을 연다. 일반 촬영은 **사진 촬영** 버튼에서 AR을 자동 활용한다.
실시간 카메라, 중앙 카메라축 깊이(Z, mm), 추적 상태, 평면 개수, 깊이 유효 비율,
신뢰도 128/255 이상 비율과 원본 지도를 표시한다.
깊이 지도와 신뢰도 지도는 센서 방향의 별도 미리보기이며 카메라 위에 겹친 정렬 결과가 아니다.
현재 값은 음식 높이·부피·중량과 구분한다.

## 촬영

1. 카메라 권한을 허용하고 Google Play AR 서비스 설치·업데이트가 필요하면 안내를 따른다.
2. 밝은 실내에서 약 0.5m 거리에 질감이 있는 물체를 두고 천천히 옆으로 움직인다.
3. 추적 정상과 유효한 깊이 표시를 확인한다.
4. **5초 진단 데이터 저장**을 누르고 움직임을 유지한다.
5. 최대 10개 프레임을 수집한다. 깊이 timestamp가 같은 중복 프레임은 다시 저장하지 않는다.
   저장 개수와 실패 안내를 확인한다. 데이터 부족 시 0프레임으로 종료될 수 있다.

화면이 백그라운드로 가면 수집을 종료하고 이미 복사한 데이터의 쓰기를 마무리한다.
카메라와 AR 세션은 화면 수명주기에 맞춰 일시 정지·해제한다.
기존 일반 사진 촬영·식단 기록과 별개의 실험이다.

## 저장 형식

앱 비공개 내부 저장소 files/ar-diagnostics/{UUID}/에 보관한다.
서버나 OpenAI로 업로드하지 않는다. 원본 카메라 영상에는 주변 사물이 포함될 수 있다.
데이터 파일 총량은 약 200MiB까지 허용하며 한도 도달 시 저장 오류를 안내한다.

- session.json: 종료 이유, 시도·저장 프레임 수, 저장 오류, 기종
- frame-00/metadata.json: 아래 원본을 해석하기 위한 좌표·보정·시각 정보
- depth.u16le: 행 패딩 제거, little-endian uint16, 카메라 Z 깊이 mm, 0은 미확보
- confidence.u8: 깊이와 같은 크기, uint8 0~255
- camera-y.u8 / camera-u.u8 / camera-v.u8: YUV_420_888의 각 평면을 패딩 없이 저장

metadata에는 CPU/GPU 카메라 내부 파라미터, 카메라 pose, 각각의 timestamp,
GPU/depth 정규화 모서리에 대응하는 CPU 영상 좌표를 저장한다.
timestamp는 정밀도 보존을 위해 문자열이다. 카메라·깊이·AR 프레임 timestamp가 다를 수 있으며
후속 복원에서 시간 차이와 재투영 여부를 고려해야 한다.
pose는 ARCore 카메라 좌표계의 위치(m)와 quaternion x/y/z/w이다.
depth는 GPU 종횡비 기준이므로 CPU 영상과 단순히 같은 픽셀 인덱스로 합치지 않는다.
신뢰도 128은 초기 실험용 필터이며 50% 정확도 같은 의미가 아니다.
색상 지도는 0.2~2m를 빨강~파랑으로 표시하고 범위 밖은 끝 색으로 고정한다.

## 개발자 확인

Flutter: flutter test, flutter analyze
Android: mobile/android에서 .\gradlew.bat :app:testDebugUnitTest
APK: flutter build apk --debug --dart-define=API_BASE_URL=http://127.0.0.1:8080/api/v1

디버그 설치의 내부 파일은 adb shell run-as com.example.foodify ls files/ar-diagnostics로 확인한다.
데이터 반출은 필요한 진단 세션만 대상으로 수행한다. 계정 탈퇴가 아닌 앱 삭제 시 이 로컬 실험 데이터도 제거된다.
앱 계정 식단과 연결된 데이터가 아니므로 테스트 데이터 관리 기능은 후속 단계다.

## 다음 단계

- 크기를 아는 물체와 자로 실제 거리·높이 오차 측정
- 촬영 거리·조도·움직임별 깊이 확보율 평가
- 수동 음식 영역과 접시 받침면 지정
- 좌표 정렬·점군·부피 계산, 음식별 밀도 환산과 저울 실측

ARCore 문서:
https://developers.google.com/ar/develop/java/depth/raw-depth
https://developers.google.com/ar/reference/java/com/google/ar/core/Coordinates2d
https://developers.google.com/ar/develop/java/enable-arcore
