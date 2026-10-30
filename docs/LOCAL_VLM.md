# PC 로컬 멀티모달 모델 실행 (2026-10-28)

## 최신 결정

사용자 결정으로 로컬 모델은 **Gemma 4 E2B**로 선정했다. 실행 설정·검증 결과는 [Gemma 4 안내](GEMMA4.md)를 우선한다.
LiteRT-LM 형식 다운로드·SHA256 검증과 PC 음식 이미지 실제 추론을 완료했다. 밥 사진에서 '밥'을 반환했고 음식명 JSON 검증을 통과했다. 첫 분석 10.554초, 모델 시작 4.644초를 기록했다.
아래 SmolVLM 기록은 이전 실행 가능성 시험의 이력이다.

## 이번에 확인한 결과

Hugging Face의 사전학습 이미지 입력 모델을 내려받아 **PC 내부에서 실제 음식 사진 추론**을 실행했다.
시험용 기준 모델은 SmolVLM-500M-Instruct Q8_0이며, 음식 인식 최종 모델 선정은 평가 후 진행한다.
공식 llama.cpp CPU 실행 파일과 모델/이미지 프로젝터의 SHA256을 확인하고, 버전·출처를 기록했다.

| 조건 | 확인 내용 |
|---|---|
| PC | 메모리 약 16GB, Intel Iris Xe, CPU 4스레드로 실행 |
| 실행 환경 | llama.cpp b11946 Windows CPU x64 |
| 모델 | SmolVLM-500M-Instruct Q8_0, 이미지 프로젝터 Q8_0 |
| 다운로드 자료 | 실행 ZIP 약 24MB + 모델 약 437MB + 프로젝터 약 109MB |
| 사진 | 기존 AI Hub 원천 압축 자료에서 밥 사진 1장만 추출 |
| 입력 | EXIF 방향 보정, 최대 512×512 축소, 실제 입력 512×384 |
| 영어 음식명 요청 3회 | 16.217초, 15.105초, 15.897초. 중앙값 15.897초 |
| 모델 시작 | 첫 영어 시험 1.675초. 위 요청 시간과 별도 |
| 영어 응답 | 밥을 white rice로 인식. 접시·배경 설명도 함께 생성 |
| 한국어 음식명 요청 1회 | 13.327초. 영어로 응답하고 음식/접시 속성을 혼동 |
| 서버 공개 범위 | 임시 포트의 127.0.0.1로 실행, 시험 종료 시 프로세스 종료 |

**PC 실행 가능성은 확인했다. 현재 시험 모델은 한국어 지시 준수와 음식/접시 구분에 문제가 있어 서비스 채택을 보류한다.**
영어 프롬프트에서는 같은 출력이 반복되었으며, 한 장 반복 시험으로 일반적인 음식 인식 정확도를 판단하기는 어렵다.
이 결과를 API보다 빠르다는 근거로 사용하려면 같은 이미지·출력 길이·실행 조건의 비교가 필요하다.
S23의 실행 시간·메모리·발열은 별도 측정 대상이다.

## 저장 위치와 실행

프로젝트 루트의 data/private/local-vlm/에 자료를 보관한다. 이 경로는 Git에서 제외된다.

- runtime/: Windows CPU 실행 파일과 배포물의 라이선스 파일
- 모델 GGUF, 이미지 프로젝터 GGUF
- manifest.json: 모델 리비전, 런타임 버전, 파일 URL과 SHA256
- sample-food.jpg, sample-provenance.json: 시험 사진과 원천 파일·라벨 경로
- pc-smoke-test.json: 영어 프롬프트 3회 응답·시간·사용량
- pc-korean-smoke-test.json: 한국어 프롬프트 1회 결과
- 각 결과에 대응하는 server.log: 실행 진단 기록

기존 TRAIN 라벨은 객체 위치·클래스 ID 형식이며 음식명의 정답 텍스트와 구분한다.
이번 시험의 '밥' 판정은 사진을 직접 확인한 결과다. 전체 클래스 ID↔음식명 매핑과 정답 검수는 후속 평가에서 수행한다.
원본 데이터 이용조건에 따라 시험 사진을 관리하고, 저장소와 공개 보고서에 원본 사진을 추가할 때 이용조건을 확인한다.

Python에 Pillow가 필요하며 기존 AR 실험 의존성에 포함되어 있다.

~~~powershell
python -m pip install -r scripts/requirements-ar.txt
python scripts/setup_local_vlm.py
python scripts/run_local_vlm.py --image data/private/local-vlm/sample-food.jpg --output data/private/local-vlm/pc-smoke-test.json --repeat 3
~~~

PATH에 Python이 없다면 현재 PC의 다음 실행 파일을 사용한다.

~~~powershell
& 'C:/Users/DELL/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' scripts/run_local_vlm.py --image data/private/local-vlm/sample-food.jpg --output data/private/local-vlm/pc-smoke-test.json --repeat 3
~~~

다른 음식 사진은 --image에 경로를 지정하고 --output을 바꾼다. 기존 결과 파일은 같은 출력 경로로 실행할 때 갱신된다.
기본 프롬프트는 영어 음식명 요청이며 --prompt로 변경할 수 있다. --repeat는 1~10, --max-tokens는 1~512를 지원한다.
스크립트는 모델 시작 최대 120초, 개별 추론 최대 180초를 기다리고 종료·오류 여부를 결과와 로그에 남긴다.
사진은 로컬 주소로만 전달하며 운영체제 프록시를 우회한다. 모델 다운로드는 별도 준비 단계다.
입력 처리·이미지 인코딩·텍스트 생성이 합쳐진 요청 시간을 기록하고, 모델 시작 시간은 별도 기록한다.
메모리 최고 사용량과 발열 측정은 후속 단계다.

## 다음 작업

1. 한국어와 한식 인식에 적합한 추가 후보를 같은 사진 세트로 평가한다.
2. 음식 누락·접시 혼동·음식명·구조화 출력 여부와 처리 시간을 함께 비교한다.
3. PC에서 기준을 통과한 모델의 Android 실행 방식과 양자화 구성을 검증한다.
4. S23 실기기에서 메모리·추론 시간·발열·연속 실행·AR 카메라와의 자원 충돌을 확인한다.
5. 검증한 로컬 결과를 서버의 음식 검색·영양 계산 흐름으로 연결한다.

현재 Foodify 앱은 기존 API 분석을 사용한다. PC 모델 실행 도구는 모델 선정과 S23 이식을 위한 실험 단계다.

공식 출처:
- [원본 모델·모델 카드·Apache-2.0 표시](https://huggingface.co/HuggingFaceTB/SmolVLM-500M-Instruct)
- [ggml-org GGUF 배포](https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF)
- [llama.cpp 이미지 입력과 프로젝터 사용](https://github.com/ggml-org/llama.cpp/blob/master/docs/multimodal.md)
- [사용한 CPU 실행 파일 배포](https://github.com/ggml-org/llama.cpp/releases/tag/b11946)

모델 버전 고정: GGUF 리비전 72e986006ef53e37cdd3f6d4241c90b0f01df376.
