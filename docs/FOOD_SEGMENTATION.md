# 음식 영역 분할과 AR 부피 계산 연결

2026-10-29 기준 PC 실험 구현이다. S23 앱 적용과 전자저울 기반 정확도 평가는 다음 단계다.

## 구현한 흐름

1. Gemma 4 E2B가 음식명과 대략적인 위치 사각형을 출력한다.
2. MobileSAM이 사각형을 바탕으로 음식 경계를 픽셀 마스크로 추출한다.
3. 사진 위의 초록색 마스크를 검토한다. 누락·접시 포함은 음식/배경 점을 추가해 재분할하거나 수동 테두리로 교체한다.
4. 같은 평평한 접시 바닥 영역을 지정한다.
5. 원본 AR 깊이 좌표로 마스크를 옮기고, 기존 부피 계산·품질 판정을 적용한다.
6. 별도 보정 자료의 밀도가 있을 때 중량으로 환산한다.

Gemma는 위치 추정, MobileSAM은 경계 추출을 담당한다. MobileSAM 자체의 출력은 음식명이나 중량이 아닌 마스크다.
현재는 음식별로 실행하며, 여러 음식은 Gemma 결과의 food-index를 바꿔 처리한다.
겹친 음식, 가려진 부분, 곡면 그릇, 투명 용기 등의 정확도는 별도 평가가 필요하다.

## 설치

프로젝트 루트에서 Python 3.12 기준:

~~~powershell
python -m pip install --target data/private/mobile-sam/python -r scripts/requirements-mobile-sam.txt
python scripts/setup_mobile_sam.py
~~~

- 공식 저장소: ChaoningZhang/MobileSAM
- 고정 리비전: f706ad9c4eb7f219c00d9050e46328518ffb65d2
- 사전학습 가중치: weights/mobile_sam.pt, 40,728,226 bytes
- 원본 Git blob 검증과 SHA256 명세 저장, 실행 전 파일 해시 확인
- torch 2.7.1+cpu, torchvision 0.22.1+cpu, timm 1.0.15
- 별도 data/private/mobile-sam/ 폴더에 모델·소스·의존성 저장, Git 제외
- checkpoint는 CPU에서 weights_only=True로 읽는다.

## 일반 음식 사진으로 연결 시험

~~~powershell
python scripts/run_gemma4.py --image data/private/local-vlm/sample-food.jpg --locate --output data/private/mobile-sam/locations.json
python scripts/segment_food.py --image data/private/local-vlm/sample-food.jpg --gemma-result data/private/mobile-sam/locations.json --food-index 0 --output data/private/mobile-sam/result-01
~~~

Gemma 결과에 원본 사진 크기와 방향 보정 후 RGB 지문을 기록한다. 다른 사진의 위치 결과를 적용하면 오류를 반환한다.
출력 폴더에는 mask.png, overlay.jpg, annotation.json이 생성된다. 새 실행은 새 폴더에 저장한다.
일반 사진 단독 시험에는 AR 깊이가 없으므로 경계 생성 결과를 확인한다.

### 위치 또는 경계 보정

모델이 찾은 위치 대신 사람이 대략적인 사각형을 줄 수도 있다.
좌표는 원본 사진 왼쪽 위 (0,0), 오른쪽 아래 (1,1) 기준의 x0 y0 x1 y1이다.

~~~powershell
python scripts/segment_food.py --image data/private/local-vlm/sample-food.jpg --box 0.26 0.25 0.74 0.85 --foreground 0.5 0.55 --background 0.8 0.5 --name 밥 --output data/private/mobile-sam/manual-position-01
~~~

foreground는 포함할 음식 위치, background는 제외할 배경 위치다. 각각 여러 번 지정할 수 있다.
Gemma 위치 결과를 쓰면서 보정 점만 추가하는 것도 가능하다.
이는 초기 연구·오차 분석용 보정 경로이며 최종 일반 사용자 흐름은 앱 통합 단계에서 결정한다.

## AR 프레임 연결

먼저 S23의 원본 AR 세션을 pull_ar_session.py로 가져온다. 아래 FRAME은 가져온 frame-00 폴더 경로로 바꾼다.

~~~powershell
python scripts/ar_volume.py export-image --frame FRAME --output data/private/ar/frame.png
python scripts/run_gemma4.py --image data/private/ar/frame.png --locate --output data/private/ar/locations.json
python scripts/segment_food.py --frame FRAME --gemma-result data/private/ar/locations.json --food-index 0 --output data/private/ar/mask-01
python scripts/ar_volume.py annotate --frame FRAME --output data/private/ar/review.html
~~~

1. review.html을 열고 mask-01/annotation.json을 불러온다.
2. 초록색 음식 경계를 확인한다.
3. 접시 바닥 모드에서 음식이 놓인 평평한 받침면을 여러 곳 지정한다.
4. 경계가 부정확하면 음식 영역 초기화 후 수동으로 다시 그리거나, 보정 점으로 모델을 재실행한다.
5. '경계 검토 후 영역 지정 파일 저장'을 눌러 reviewed.json으로 보관한다.

~~~powershell
python scripts/ar_volume.py estimate --frame FRAME --annotation data/private/ar/reviewed.json --output data/private/ar/volume.json
~~~

기존 접시 영역 파일이 있으면 segment_food.py의 --annotation 옵션으로 재사용한다.
프레임 지문이 같은 파일만 적용한다. 모델 마스크는 검토 전 reviewed=false이며, 실험 도구는 관측 부피를 계산해도 최종 부피·중량 출력을 보류한다.
검토 후에도 깊이 부족·기준면 불안정 등의 기존 품질 판정이 적용된다.

## 이번 검증 결과

| 검증 | 결과 |
|---|---|
| 기존 AR·평가 테스트 | 19개 통과 |
| 마스크 좌표·입력·계산 테스트 | 7개 통과 |
| Gemma 응답·위치 검증 테스트 | 7개 통과 |
| 브라우저 연결 | 마스크 불러오기 → 검토 저장 → 부피 CLI 계산, 수동 영역 교체 통과 |
| 실제 밥 사진, 수동 위치+보정 점 | 초기화 약 0.87초, 추론 약 3.52초 |
| 실제 밥 사진, Gemma 위치 | Gemma 추론 23.571초, MobileSAM 초기화 약 0.88초·추론 약 3.47초 |

위 시간은 PC CPU에서 사진 한 장을 실행한 측정값이며 S23 속도·여러 음식 정확도와 구분한다.
수동 위치 결과는 큰 접시 영역을 제외하고 밥 영역을 추출했다. Gemma 위치만 사용한 결과는 밥 아래쪽 일부가 빠져 보정 필요성을 확인했다.
같은 Gemma 위치에 음식 점 (0.5, 0.8)과 배경 점 (0.8, 0.5)을 추가한 결과는 누락된 아래쪽 영역이 포함되는 것을 육안으로 확인했다. 이 보정 실행의 추론 시간은 약 3.21초이며, 정답 마스크 대비 정량 정확도는 별도 평가한다.
모델의 predictedIou는 실제 정답 마스크와 비교한 점수가 아니다. 1을 넘는 원시 예측값도 나올 수 있어 정확도 백분율로 표시하지 않는다.
이번 밥 사진은 영역 분할 시험용이며 AR 깊이·저울 값이 없어 부피와 중량 정확도를 평가하지 않았다.
부피 연결은 합성 깊이 자료에서 검증했다.

## 남은 단계

- 여러 음식 사진에서 위치 누락·마스크 경계 오차를 평가
- 실제 S23 사진·깊이 정렬 확인, 접시 기준면과 음식 부피 검증
- 음식별 실측 밀도 보정과 평가 자료 분리
- Android 실행 형식·메모리·속도 검증 후 촬영 흐름 통합
- 접시 기준면 선택 자동화와 재촬영 안내 개선

공식 근거: [MobileSAM 저장소](https://github.com/ChaoningZhang/MobileSAM), [PyTorch 설치 안내](https://pytorch.org/get-started/locally/).
