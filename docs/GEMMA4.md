# Foodify 로컬 모델: Gemma 4 E2B

## 선정

사용자 결정에 따라 Foodify의 로컬 멀티모달 모델은 **Gemma 4 E2B instruction-tuned 모델**로 정한다.
음식명·재료 등 이미지 이해를 담당하고, 음식 영역 분할과 AR 부피·중량 계산은 각 기능에서 처리한다.
공공 영양 데이터 기반 수치 계산과 식단 기록 기능에 로컬 인식 결과를 연결하는 것을 목표로 한다.

| 항목 | 설정 |
|---|---|
| 원본 모델 | google/gemma-4-E2B-it |
| PC·Android 배포 저장소 | litert-community/gemma-4-E2B-it-litert-lm |
| 파일 | gemma-4-E2B-it.litertlm |
| 크기 | 2,588,147,712 bytes (약 2.59GB) |
| 배포 리비전 | 1caa527c1e88b55f046fcd8d2b4265606680663d |
| SHA256 | 181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c |
| PC 실행 패키지 | litert-lm-api 0.18.1 |
| 저장 위치 | data/private/gemma4/ (Git 제외) |
| Android 실행 코드 | LiteRT-LM 0.16.1 + Flutter 플랫폼 채널, 개발자 메뉴에서 실험 |

모델 파일 크기와 실제 실행 메모리 사용량은 별도로 측정한다. PC·S23의 성능 역시 각각 기록한다.
기존 SmolVLM 실행 결과는 이전 비교 자료로 보존한다.

## Android 개발자 기능 (2026-10-29)

앱의 **설정 → 개발자 메뉴 → 기기 내 음식 분석 · Gemma**에서 실행한다.

1. 아래 명령으로 APK를 설치하고, PC에서 검증한 모델을 S23의 Download 폴더에 복사한다.
2. 앱에서 **모델 파일 불러오기**를 눌러 해당 파일을 선택한다.
3. 앱은 파일을 비공개 저장소로 복사하면서 크기와 SHA256을 검증한다. 앱 저장 공간에 3GB 이상 여유를 확보한다. Download 원본과 앱 복사본은 별도 공간을 차지한다.
4. **갤러리에서 사진 선택 → 휴대폰에서 분석**을 누른다.
5. 음식명, 모델 초기화 시간, 사진 분석 시간, 분석 직후 앱 전체 PSS 메모리를 확인한다.

프로젝트 루트에서 PowerShell로 실행:

~~~powershell
$adb = 'C:/Users/DELL/AppData/Local/Android/Sdk/platform-tools/adb.exe'
& $adb devices
& $adb install -r 'mobile/build/app/outputs/flutter-apk/app-debug.apk'
& $adb push 'data/private/gemma4/gemma-4-E2B-it.litertlm' '/sdcard/Download/gemma-4-E2B-it.litertlm'
~~~

앱 실행용 로그인·식단 조회는 기존 서버 연결을 이용한다. 개발자 화면의 모델 추론은 선택한 사진을 기기 내부에서 처리한다.
일반 촬영·식단 분석에는 현재 기존 API 경로가 적용된다. 로컬 인식 결과를 영양 검색·기록 흐름에 연결하는 단계는 S23 실험 후 진행한다.

### 실행 설정과 측정 해석

- Android 런타임은 0.16.1로 고정한다. 배포 AAR의 Kotlin 메타데이터가 2.3이므로 Kotlin 2.3.0·AGP 8.13.2·Gradle 8.13으로 빌드 도구를 맞춘다. [AGP 공식 호환성 안내](https://developer.android.com/build/releases/agp-8-13-0-release-notes). PC 런타임은 0.18.1이므로 결과에는 런타임 버전을 함께 기록한다.
- 앱 최소 Android 버전은 8(API 26), 이 개발자 사진 분석은 ImageDecoder를 사용하는 Android 9(API 28) 이상이다. S23이 우선 평가 기기다.
- CPU 추론·CPU 이미지 인코더, 전체 토큰 한도 4096, 출력 한도 256, 이미지 1장, thinking 비활성화, seed 42를 사용한다.
- 사진 방향을 반영해 긴 변 512px 이하로 축소한다. 매 실행마다 새 대화를 만들고 완료 후 엔진을 닫는다.
- 응답은 음식명 목록 형식을 검증한다. 영양 수치는 기존 공공 DB 계산 흐름을 사용한다.
- 추론 단계가 3분을 초과하면 취소를 요청한다. 네이티브 초기화 시간과 취소 반응 시간은 실기기에서 확인한다.
- PSS는 분석 직후 앱 전체 메모리이며, PC 문서의 최고 작업 집합 메모리와 서로 다른 측정값이다.
- 모델 파일은 앱의 백업 제외 비공개 폴더에 보관한다. 앱 삭제 시 함께 제거된다.

### S23 검증 순서

1. 밥 사진으로 초기 실행 확인 후, 여러 음식이 함께 있는 사진으로 확장한다.
2. 같은 사진을 3회 실행하고 초기화·추론 시간을 각각 기록한다.
3. API 결과와 음식명·누락·중복을 비교하고 발열·앱 종료 여부를 확인한다.
4. 부족한 저장 공간, 파일 선택 취소, 다른 모델 파일, 앱 종료 후 재실행을 확인한다.
5. 인식 성능과 메모리가 확인되면 일반 촬영 흐름 통합과 GPU 비교를 진행한다.

### 현재 검증 결과

2026-10-29 기준:

- Flutter 정적 검사: 지적 사항 0건.
- Flutter 테스트: 41개 통과. 모델 선택 취소·검증 실패 화면 포함.
- Android 단위 테스트: 9개 통과. 기존 AR 깊이 처리 6개와 새 음식명 응답 검증 3개.
- debug APK 빌드 성공. APK에 arm64-v8a·x86_64 LiteRT-LM 실행 라이브러리가 포함되어 있으며 모델 파일은 별도 보관한다.
- APK 위치: mobile/build/app/outputs/flutter-apk/app-debug.apk
- USB 장치 목록이 비어 있어 S23 설치·모델 가져오기·실제 추론은 검증 대기 상태다.
- 코드·빌드 성공과 실기기 성능 검증을 구분한다. 여러 음식의 정답률, 초기화 시간, 발열과 반복 실행 안정성은 S23 자료로 평가한다.

공식 구현 근거: [LiteRT-LM Android 안내](https://developers.google.com/edge/litert-lm/android),
[0.16.1 Kotlin API](https://github.com/google-ai-edge/LiteRT-LM/tree/v0.16.1/kotlin/java/com/google/ai/edge/litertlm).

## PC 첫 실행 결과

2026-10-29 PC 위치 추정 모드 추가: run_gemma4.py --locate로 음식명과 정규화 bbox를 생성하고 MobileSAM에 전달할 수 있다. [음식 영역 분할 안내](FOOD_SEGMENTATION.md). Android 개발자 화면은 기존 음식명 분석 범위이며 위치·마스크의 Android 통합은 후속 단계다.

2026-10-28, 기존 AI Hub 원천 자료에서 추출한 밥 사진 한 장으로 확인했다.

| 항목 | 실측 결과 |
|---|---|
| 실행 상태 | COMPLETED |
| 실행 방식 | Windows CPU, 이미지 인코더 CPU |
| 입력 사진 | 방향 보정·축소 후 512×384 |
| 모델 시작 시간 | 4.644초 |
| 이미지 분석 시간 | 10.554초 |
| 반환 음식명 | 밥 |
| 응답 형식 | JSON 코드 블록을 제거한 뒤 음식 목록 검증 통과 |
| 최고 작업 집합 메모리 | 2409.52MiB (약 2.35GiB) |
| 실제 결과 파일 | data/private/gemma4/food-test.json |

모델 파일 전체 크기와 SHA256 검증, LiteRT-LM 설치, 네이티브 DLL 로딩을 완료했다.
최초 시도의 캐시 폴더 누락 오류를 수정하여 실행 도구에서 폴더를 자동 생성한다.
단일 사진의 실행 확인 결과이므로 음식 인식 정확도 전체나 S23 처리 시간을 대표하는 값과 구분한다.
이전 SmolVLM 시험과는 모델·런타임·프롬프트·출력 길이가 달라 공정한 속도 비교에는 동일 조건 평가가 필요하다.

현재 상태: **PC 실제 이미지 추론 완료 → 여러 음식 평가·S23 적용·앱 연동 진행 예정.**

## PC 준비

프로젝트 루트에서 실행한다. 현재 PC에는 패키지를 별도 폴더에 설치하여 기존 개발 환경과 분리한다.

~~~powershell
python -m pip install --target data/private/gemma4/python -r scripts/requirements-gemma4.txt
python scripts/setup_gemma4.py
~~~

Python이 PATH에 없다면 다음 실행 파일로 명령어를 실행한다.

~~~powershell
& 'C:/Users/DELL/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' scripts/setup_gemma4.py
~~~

다운로드 도구는 리비전과 SHA256을 고정한다. 전송이 중단되면 partial 파일부터 재개하며, 전체 길이와 해시가 일치해야 실제 모델 파일명으로 확정한다.
모델 다운로드는 네트워크를 사용하고, 이후 로컬 추론에서는 사진을 기기 내부에서 처리한다.

## PC 음식 사진 실행

~~~powershell
python scripts/run_gemma4.py --image data/private/local-vlm/sample-food.jpg --output data/private/gemma4/food-test.json --repeat 1
~~~

--image에 다른 사진 경로를 넣어 사용할 수 있다. 기본은 CPU 실행이며, GPU 시험은 --backend gpu --vision-backend gpu로 별도 실행한다.
기본 입력은 최대 512×512 사진과 한국어 음식명 JSON 요청이다. EXIF 방향을 보정하고 음식·접시·배경 구분을 요청한다.
새 대화로 반복 실행하며, 생각 과정 생성은 끄고 출력 길이·총 실행 시간을 제한한다.
형식 검증을 통과한 음식 이름과 원문, 처리 시간, 시작 시간, 프로세스 최고 작업 집합 메모리(MiB)를 결과에 남긴다.
메모리 수치는 Windows 프로세스 기준이며 GPU 메모리나 S23 사용량을 의미하지 않는다.
실행 실패와 시간 초과도 결과 JSON과 runtime.log로 확인한다.

응답 형식 검증:

~~~powershell
python -m unittest discover -s scripts -p test_gemma4.py -v
~~~

## 검증 순서

1. PC에서 음식 사진의 한국어 음식명 응답과 JSON 형식 검증
2. 동일 자료에서 처리 시간·모델 시작 시간·반복 결과 기록
3. 다양한 한식·여러 음식·접시 포함 사진으로 오류 평가
4. S23에서 모델 적재·이미지 추론·메모리·발열 검증
5. 앱 음식 검색·영양 계산과 연결하고 실패 복구 흐름 검증

## 공식 출처

- [원본 모델](https://huggingface.co/google/gemma-4-E2B-it)
- [LiteRT 배포 모델](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
- [Python 실행 안내](https://developers.google.com/edge/litert-lm/python)
- [Android Kotlin 실행 안내](https://developers.google.com/edge/litert-lm/android)
