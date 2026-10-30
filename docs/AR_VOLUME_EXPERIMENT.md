# AR 부피·중량 실험 도구 (2026-10-28)

2026-10-29 추가: [음식 영역 분할 연결](FOOD_SEGMENTATION.md). Gemma 위치 추정 → MobileSAM 마스크 생성 → 경계 검토 → 기존 부피 계산을 PC에서 연결했다. 실제 사진의 마스크 생성과 합성 깊이 계산을 각각 검증했으며, S23의 실제 중량 오차는 실측 평가 단계다.

## 현재 완료한 범위

PC에서 S23 원본 프레임을 읽고 **음식·접시 바닥 수동 지정 → 좌표 정렬 → 받침면 추정 → 관측 표면 부피 적분 → 근거 있는 밀도로 중량 환산**하는 도구다.
일반 사용자의 앱 화면이나 식단 계산에 연결하기 전 검증 단계다. 기존 APK의 중량은 여전히 사진·AR 요약을 참고한 AI 추정값이다.

- scripts/pull_ar_session.py: 선택한 진단 세션만 USB로 가져온다.
- scripts/ar_volume.py annotate: 오프라인 사진 영역 지정 HTML을 만든다.
- scripts/ar_volume.py estimate: 부피·중량·품질 지표 JSON을 만든다.
- scripts/evaluate_ar_volume.py: 보정 자료에서 환산 밀도를 구하고 별도 평가 자료로 오차와 결과 확보율을 계산한다.
- 합성 기하와 평가 분리 규칙 테스트 19개 통과. 실제 음식 정확도는 실측 평가 대상이다.

## 실행 준비

Python 3.11 이상 환경에서 프로젝트 루트 기준:

~~~powershell
python -m pip install -r scripts/requirements-ar.txt
$adb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
python scripts/pull_ar_session.py --adb $adb
~~~

현재 PC에서 확인한 Python 경로는 다음과 같다. PATH에 Python이 없다면 python 대신 이 실행 파일을 사용한다.

~~~powershell
& 'C:/Users/DELL/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' scripts/pull_ar_session.py --adb $adb
~~~

S23 USB 디버깅 연결과 Foodify 디버그 APK가 필요하다. 목록에 나온 세션 UUID를 복사한다.
식단 사진과 별개로 저장된 개발자 AR 세션을 대상으로 한다.

~~~powershell
python scripts/pull_ar_session.py --adb $adb --session '<세션 UUID>'
~~~

기본 저장 위치는 Git에서 제외된 data/private/ar/<세션 UUID>/frame-00/다.
Windows PowerShell의 바이너리 출력 변환을 피하도록 Python이 원본 바이트를 직접 저장한다.
기존 세션 폴더가 있으면 별도 출력 위치를 --output으로 지정한다.

## 음식과 접시 영역 지정

~~~powershell
python scripts/ar_volume.py annotate --frame 'data/private/ar/<세션 UUID>/frame-00' --output 'data/private/ar/annotation.html'
~~~

1. 생성한 HTML을 브라우저로 연다.
2. 초록색 음식 영역으로 **한 음식의 테두리**를 순서대로 클릭하고 영역 완성을 누른다.
3. 파란색 접시 바닥 영역으로 음식 주변의 **같은 평평한 받침면**을 여러 곳 지정한다.
4. 접시 테두리나 식탁은 음식이 놓인 면과 높이가 다를 수 있으므로 실제 받침면을 고른다.
5. 음식명과 전자저울 중량을 입력한다. 별도 보정 자료의 밀도와 근거가 있으면 함께 입력한다.
6. '영역 지정 파일 저장'으로 내려받은 JSON을 data/private/ar/annotation.json으로 옮긴다.

음식·기준면 영역은 서로 겹치지 않게 하고, 기준면 영역은 접시 한쪽의 좁은 선에 몰리지 않도록 한다.
사진은 CPU 원본 방향으로 표시된다. 화면 회전이나 잘라내기 후의 좌표를 혼합하지 않는다.
저장 JSON은 원본 프레임의 지문을 포함하여 다른 프레임에 잘못 적용하는 것을 검출한다.
HTML에는 촬영 사진이 포함되어 있으므로 실험 자료 폴더에 보관한다.

~~~powershell
python scripts/ar_volume.py estimate --frame 'data/private/ar/<세션 UUID>/frame-00' --annotation 'data/private/ar/annotation.json' --output 'data/private/ar/result.json'
~~~

## 계산과 결과 해석

저장된 깊이→CPU 영상 좌표 대응과 카메라 내부 파라미터를 이용해 카메라 좌표계의 3차원 점을 복원한다.
접시 바닥 점에 RANSAC과 최소제곱 평면 맞춤을 적용한 뒤, 음식 표면의 인접 점을 삼각형으로 연결한다.
삼각형을 받침면에 수직 투영한 면적에 평균 음식 높이를 곱해 합산한다. mm³를 1,000으로 나누면 mL다.
밀도(g/mL)가 확보되면 부피에 곱해 g으로 환산한다. 저울 값은 오차 비교용이며 단일 평가 사진의 밀도를 역산하는 데 사용하지 않는다.

| 결과 | 의미 |
|---|---|
| observedVolumeMl | 품질 판정과 별개로 유효 삼각형에서 적분한 관측 부피 |
| volumeMl | 초기 품질 기준을 통과했을 때만 제공하는 실험 부피 |
| estimatedGrams | 실험 부피와 근거 있는 밀도를 모두 확보했을 때의 참고 중량 |
| validFoodPixelFraction | 지정한 음식 영역에서 신뢰도 128 이상·유효 깊이 비율 |
| meshCoverage | 음식 내부 후보 삼각형 중 적분 가능한 삼각형 비율 |
| basePlaneRmseMm | 선택된 기준면 점의 평면 맞춤 잔차 |
| holdReasons | 시간차·깊이 부족·기준면·촬영 각도 등의 보류 사유 |
| volumeValidated | 항상 false. 실측 검증 완료와 구분 |

초기 실험 기준: 신뢰도 128 이상, 음식 깊이/삼각형 비율 각 80% 이상, 기준면 점 20개 이상,
평면 잔차 3mm 이하, 카메라-평면 기울기 30도 이하, 깊이 시각차 100ms/사진 50ms 이하.
이 수치는 실험 필터이며 정확도를 보장하는 수치와 구분한다.

### 적용 조건과 한계

- 초기 대상은 평평한 접시에 놓인 단순한 음식이다. 그릇 내부 곡면·국물·투명 용기는 별도 모델이 필요하다.
- 단일 시점에서 보이는 음식 표면만 적분한다. 가려진 옆면·바닥, 구멍과 빠진 경계는 실측과 비교한다.
- 누락 깊이는 미확보 상태로 유지한다. 일부 누락이 허용되므로 통과 결과에도 과소 추정 가능성이 있다.
- 삼각형 확보율은 픽셀 연결 비율이며 실제 부피의 80%를 측정했다는 의미와 구분한다.
- 손으로 선택한 영역의 정확도와 실제 카메라·깊이 정렬은 S23 자료로 추가 검증한다.
- 평면 적합도가 좋아도 접시 가장자리처럼 잘못된 높이를 지정할 수 있으므로 받침면 선택을 확인한다.

## 보정·평가 자료 분리

음식 3종, 음식별 서로 다른 제공량 10개, 각 제공량 2회 촬영을 시작점으로 한다.
제공량별 5개를 보정, 나머지 5개를 평가로 나누고 같은 제공량의 반복 촬영은 같은 분할에 둔다.
같은 제공량은 전역에서 고유한 portion_id를 사용한다.
CSV는 다음 열을 사용한다. 경로는 CSV 위치 기준이다.

~~~csv
portion_id,food,split,scale_grams,result_path
foodA-001,평가음식A,calibration,100,foodA-001-1.json
foodA-001,평가음식A,calibration,100,foodA-001-2.json
foodA-006,평가음식A,evaluation,150,foodA-006-1.json
~~~

위는 열 구성 예시다. 실제 보정용 제공량 3개 이상을 준비해야 밀도를 계산한다.
각 결과 JSON은 estimate로 생성한다. 분석 보류 결과도 CSV에 포함하여 결과 확보율에 반영한다.
프레임 식별자 중복과 동일 제공량의 분할·실측값 충돌은 오류로 처리한다.
제공량별 반복 촬영의 부피 중앙값을 사용하고, 보정용 자료만으로 절편 0의 최소제곱 환산 계수를 계산한다.

~~~powershell
python scripts/evaluate_ar_volume.py --manifest data/private/ar/evaluation.csv --output data/private/ar/evaluation-report.json
python -m unittest discover -s scripts -p 'test_*ar_volume.py' -v
~~~

평가 보고서는 음식별 MAE(g), 상대오차 중앙값(%), 제공량별 중량 확보율, 프레임별 부피 확보율을 출력한다.
음식별 중앙 상대오차 25% 이하·확보율 80% 이상은 기존 계획의 채택 목표이며 실제 결과를 확인한 후 판단한다.
동일 자료의 사진 기반 중량과 비교하는 최종 보고서는 후속 단계다.

## 다음 구현 순서

1. S23 원본 프레임·저울 자료로 정렬, 받침면, 부피 오차 확인
2. 음식별 보정 계수와 촬영 조건 확정
3. 음식 마스크 자동화 후 수동 마스크 결과와 비교
4. 검증한 계산을 모바일 촬영 흐름에 통합
5. Hugging Face 사전학습 멀티모달 모델 PC 평가 → S23 실행 검증

공식 기술 근거:
- [ARCore 깊이 영상과 좌표 변환](https://developers.google.com/ar/develop/java/depth/developer-guide)
- [ARCore Raw Depth의 신뢰도와 누락 깊이](https://developers.google.com/ar/develop/java/depth/raw-depth)

실험 알고리즘·임계값·중량 환산은 Foodify 연구 구현이며 ARCore가 제공하는 자동 음식 부피 기능과 구분한다.
