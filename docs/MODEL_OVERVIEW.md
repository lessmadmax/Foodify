# Foodify 모델 전체 정리

2026년 11월 12일 작업 폴더 기준. 기본 서비스의 학습 모델은 **Food-101-93M, MobileSAM vit_t, gpt-4o-mini** 세 가지다. 외부 API 모델명은 `OPENAI_MODEL`로 변경할 수 있으며 코드 기본값은 `gpt-4o-mini`다. 아래 목록은 서비스 모델과 실험·비교 이력을 구분한다.

| 모델 | 종류 | 실행 위치와 상태 | 입력 → 출력 | 역할 및 사용 목적 |
|---|---|---|---|---|
| `prithivMLmods/Food-101-93M` | Food-101로 추가 학습한 SigLIP 계열 이미지 분류 모델. 로컬 config의 architecture는 `SiglipForImageClassification` | Android CPU, ONNX Runtime. 서비스 사용 | 사진별 224×224 RGB → 101개 분류 점수 중 상위 3개 `{label, score}` | 음식 전용 분류 근거를 API에 제공한다. 주 음식 후보를 좁히는 역할이며, 음식 위치·마스크·중량은 출력하지 않는다. |
| `MobileSAM vit_t` | SAM을 경량화한 프롬프트 기반 이미지 분할 모델 | Android CPU, ONNX 인코더·디코더. AR 윤곽에 사용 | 대표 사진 + 음식·제외 점 → 음식 픽셀 마스크 → 조각별 다각형 | 면적 계산에 필요한 음식 경계를 얻는다. 사진 인코딩은 캐시하고 점 변경 시 디코더를 다시 실행한다. 음식 이름·밀도·중량·영양값을 판정하지 않는다. |
| `gpt-4o-mini` | 이미지·텍스트 입력을 이해하는 범용 멀티모달 언어모델 | 서버에서 OpenAI API 호출. 서비스 기본 모델 | 사진·후보·촬영 근거 → 음식명·참고 중량·범위·가정 / DB 후보 → foodId / 사진·축척·면적 → 평균 높이 / 식단 집계·대화 → 답변 | 분류 밖 음식과 다중 음식 해석, 기본 제공량 추정, DB 후보 선택 보조, 기기 측정 실패 시 높이 대체, 기록 기반 대화를 담당한다. 이 기능들은 같은 모델의 서로 다른 호출이며 별도 학습 모델이 아니다. |
| `google/gemma-4-E2B-it` | 지시 학습된 로컬 멀티모달 생성 모델 | PC·S23, LiteRT-LM. 개발자 실험 메뉴에 유지 | 사진·질문 → 음식명 등 생성 응답 | 외부 API 의존도를 줄일 수 있는지 로컬 실행·속도·음식명 품질을 평가했다. 현재 기본 식단 분석이나 챗봇 모델로 채택하지 않았다. |
| `nateraw/food` | Food-101로 추가 학습한 ViT 이미지 분류 모델 | PC 비교 평가. 서비스 미채택 | 사진 → 음식 클래스 점수·상위 후보 | 음식 분류 모델 선정 과정에서 Food-101-93M과 같은 고정 사진으로 비교했다. |
| `SmolVLM-500M-Instruct Q8_0` | 양자화된 소형 멀티모달 생성 모델 | PC CPU, llama.cpp. 과거 실행 가능성 시험, 서비스 미채택 | 사진·질문 → 생성 텍스트 | 로컬 이미지 이해의 실행 가능성을 확인했다. 한국어 지시 준수와 음식·배경 구분에 한계가 있어 후속 로컬 검토를 Gemma로 전환했다. |

## 서비스 모델별 동작

**Food-101-93M**은 사진 전체의 주 음식을 101개 클래스 중에서 고른다. 이미지 전처리 후 로짓을 Softmax 점수로 변환하여 상위 3개를 서버에 전달한다. 1순위 점수가 기본 0.6 미만이면 해당 사진의 후보를 AI 프롬프트에서 제외한다. 점수는 보정된 정답 확률이 아니며, 후보 3개가 모두 사진에 존재한다는 뜻도 아니다. 다중 음식 검출 모델이나 개방형 음식 판별 모델로 해석하면 안 된다.

**MobileSAM**은 이미지의 의미 이름보다 사용자가 지정한 영역을 찾는 데 사용한다. 사진을 한 번 인코딩한 뒤 음식·제외 점을 포함한 프롬프트로 마스크를 계산한다. 마스크를 연결된 조각별 볼록 다각형으로 바꾸며 작은 조각을 제거한다. 사용자 확인 또는 직접 그린 윤곽을 받은 뒤에 실제 면적을 계산한다. 분할 정확도와 중량 정확도는 별도로 검증해야 한다.

**gpt-4o-mini**의 호출 목적은 다음과 같다.

1. 사진 분석: 음식명·참고 중량·예상 범위·가정·불확실성을 구조화 JSON으로 생성한다.
2. DB 연결 보조: 서버가 검색한 후보 목록 안에서 적합한 `foodId`를 선택한다.
3. 높이 대체: 기기에서 평균 높이를 구하지 못할 때 사진·음식명·면적·축척을 바탕으로 평균 높이를 추정한다. 3회 병렬 요청의 성공 응답 중앙값을 사용한다.
4. 챗봇·피드백: 서버가 제공한 오늘·최근 7일 집계, 목표와 최근 대화를 근거로 안내한다.

`locateFood` 및 `/ar/food-box` 구현도 남아 있지만, 현재 기본 AR 윤곽 흐름은 사용자 탭과 MobileSAM을 사용한다. 따라서 AI 위치 박스를 현재 필수 단계로 표시하지 않는다.

## 모델과 구분할 구성 요소

| 구성 요소 | 분류 | 역할 |
|---|---|---|
| ARCore | 공간 인식 SDK | 깊이·신뢰도·카메라 자세·내부 파라미터·특징점을 제공한다. 프로젝트가 독립적으로 선택·학습한 음식 중량 모델이 아니다. |
| ONNX Runtime | 추론 런타임 | Food-101과 MobileSAM의 ONNX 모델을 실행한다. |
| LiteRT-LM / llama.cpp | 추론 런타임 | 실험용 로컬 생성 모델을 실행한다. |
| GrabCut | 영상 분할 알고리즘 | 과거 윤곽 비교 실험에 사용했다. 현재 기본 AR 윤곽 모델이 아니다. |
| `ArFusion`, `ArVolume` | 기하 처리 코드 | 깊이 정렬과 거리·면적·평균 높이를 계산한다. |
| `DensityGroups`, `MealService` | 분류별 환산 규칙·산술 처리 | DB 대분류 밀도를 골라 `면적 × 높이 × 밀도`로 중량을 계산한다. |
| 공공 영양 DB, `FoodService` | 기준 데이터·비례 계산 | `기준 영양량 × 기록 중량 ÷ 기준 중량`으로 영양값을 계산한다. |

## 확인 근거

- `mobile/android/app/src/main/assets/food101/manifest.json` 및 로컬 모델 `config.json`
- `mobile/android/app/src/main/assets/mobilesam/manifest.json`, `SamSegmenter.kt`, `SamGeometry.kt`
- `backend/src/main/resources/application.yml`, `ai/OpenAiClient.java`, `ai/AnalysisWorker.java`
- `meal/MealService.java`, `food/FoodService.java`, `food/DensityGroups.java`
- `docs/PROJECT_GUIDE.md`, `docs/GEMMA4.md`, `docs/LOCAL_VLM.md`, `docs/FOOD_CLASSIFIER_EVAL50_2026-11-02.md`
