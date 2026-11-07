# 음식 전용 분류 모델 검증 2026-11-02

기존 Gemma/API 평가에 사용한 Food-101 validation의 같은 원본 사진 50장으로 PC CPU에서 두 음식 분류 모델을 시험했다. 분류당 1장, 총 50개 서로 다른 정답 분류다. 원본 manifest의 파일·행·SHA256를 유지했다. 모델에는 정답 라벨을 제공하지 않았다.

|모델|Top-1|Top-3|PC 평균 처리 시간|
|---|---:|---:|---:|
|nateraw/food|43/50 (86%)|49/50 (98%)|0.347초|
|prithivMLmods/Food-101-93M|45/50 (90%)|48/50 (96%)|0.543초|

이전 Gemma 8/50(16%), 기존 API 29/50(58%)와 같은 원본 자료다. 그러나 분류 모델은 음식 101개 후보 중 하나를 고르는 폐쇄형 분류이고, 이전 두 모델은 음식명을 자유 생성했다. 학습 데이터와 과제 조건이 달라 모델의 일반적 우열을 확정하는 비교는 아니다.

## 실행 조건
- PyTorch 2.7.1+cpu, Transformers 4.57.1, PC CPU 4스레드, eval 및 inference_mode.
- 각 모델의 AutoImageProcessor 공식 설정을 사용했다. 512px 생성 모델 입력과 전처리 경로·해상도가 다르다.
- 모델 로딩과 다운로드, 별도 1회 준비 실행은 시간 평균에서 제외했다. 이미지 전처리와 모델 추론·상위 후보 계산을 포함한다.
- Top-1은 최상위 분류와 정답의 일치, Top-3는 상위 3개 안에 정답 포함 여부다. 자동 비교 시 구두점·공백만 정규화하였다.
- 공개 가중치를 사용하며 trust_remote_code를 사용하지 않았다. 가중치 버전과 SHA256를 provenance.json에 저장했다.

## 판정과 한계
Food-101-93M은 45/50으로 이번 표본의 Top-1이 가장 높다. nateraw/food는 Top-3 49/50으로 후보 제안용 가능성을 보였다. 학습한 101종 밖의 음식을 인식하거나 미지 음식을 거부하는 능력, 여러 음식의 검출·분할, 중량 추정은 이번 시험으로 검증되지 않았다.
두 모델은 Food-101로 추가 학습된 모델이다. 공개 모델의 전체 학습·모델 선택 과정과 이번 검증 사진 간 중복 가능성까지 독립적으로 확인한 상태는 아니므로 최종 미사용 평가나 한국 음식 전체 성능으로 해석하지 않는다. 실제 한식·스마트폰 사진의 새 자료로 추가 검증해야 한다.
PC 시간은 S23 시간과 직접 비교할 수 없다. Android 실행 형식 변환·S23 실측은 후속 단계다. Top-3가 높아도 점수가 보정된 확률이라는 의미는 아니며 세 후보의 음식이 모두 사진에 존재한다는 의미도 아니다.

## 50종 개별 출력
|정답|nateraw 첫 후보|판정|Food-101-93M 첫 후보|판정|
|---|---|---|---|---|
|beignets|beignets|일치|beignets|일치|
|prime_rib|prime_rib|일치|prime_rib|일치|
|ramen|ramen|일치|ramen|일치|
|hamburger|hamburger|일치|hamburger|일치|
|bruschetta|bruschetta|일치|bruschetta|일치|
|chicken_wings|chicken_wings|일치|chicken_wings|일치|
|pork_chop|pork_chop|일치|pork_chop|일치|
|greek_salad|beet_salad|미일치|greek_salad|일치|
|sashimi|sashimi|일치|sashimi|일치|
|risotto|risotto|일치|risotto|일치|
|pizza|pizza|일치|pizza|일치|
|eggs_benedict|eggs_benedict|일치|eggs_benedict|일치|
|guacamole|guacamole|일치|guacamole|일치|
|chocolate_cake|chocolate_cake|일치|chocolate_cake|일치|
|miso_soup|miso_soup|일치|miso_soup|일치|
|apple_pie|cheesecake|미일치|strawberry_shortcake|미일치|
|fried_calamari|fried_calamari|일치|fried_calamari|일치|
|fried_rice|fried_rice|일치|fried_rice|일치|
|panna_cotta|panna_cotta|일치|panna_cotta|일치|
|hummus|hummus|일치|hummus|일치|
|carrot_cake|carrot_cake|일치|carrot_cake|일치|
|beet_salad|beet_salad|일치|beet_salad|일치|
|garlic_bread|garlic_bread|일치|garlic_bread|일치|
|hot_dog|hot_dog|일치|hot_dog|일치|
|steak|steak|일치|steak|일치|
|tiramisu|tiramisu|일치|tiramisu|일치|
|fish_and_chips|fish_and_chips|일치|fish_and_chips|일치|
|caesar_salad|caesar_salad|일치|caesar_salad|일치|
|tuna_tartare|tuna_tartare|일치|bruschetta|미일치|
|pancakes|pancakes|일치|pancakes|일치|
|chocolate_mousse|chocolate_mousse|일치|chocolate_mousse|일치|
|lasagna|macaroni_and_cheese|미일치|lasagna|일치|
|pad_thai|pad_thai|일치|pad_thai|일치|
|cheesecake|strawberry_shortcake|미일치|cheesecake|일치|
|baklava|baklava|일치|baklava|일치|
|ice_cream|frozen_yogurt|미일치|frozen_yogurt|미일치|
|red_velvet_cake|red_velvet_cake|일치|red_velvet_cake|일치|
|tacos|tacos|일치|tacos|일치|
|foie_gras|foie_gras|일치|foie_gras|일치|
|grilled_cheese_sandwich|grilled_cheese_sandwich|일치|grilled_cheese_sandwich|일치|
|frozen_yogurt|frozen_yogurt|일치|frozen_yogurt|일치|
|seaweed_salad|seaweed_salad|일치|seaweed_salad|일치|
|breakfast_burrito|breakfast_burrito|일치|breakfast_burrito|일치|
|crab_cakes|crab_cakes|일치|crab_cakes|일치|
|strawberry_shortcake|strawberry_shortcake|일치|strawberry_shortcake|일치|
|beef_tartare|beef_tartare|일치|beef_tartare|일치|
|mussels|mussels|일치|mussels|일치|
|dumplings|clam_chowder|미일치|clam_chowder|미일치|
|creme_brulee|creme_brulee|일치|creme_brulee|일치|
|falafel|crab_cakes|미일치|crab_cakes|미일치|

## 재현 자료
- nateraw/food revision 8991abd49ea01ebf502aeda51d4f12a59c603e01
- prithivMLmods/Food-101-93M revision addcbf20006b2ec0eb1bdff313ef732cd8634f11
- 원시 결과: data/private/food-classifier/<모델명>/eval50-results.json
- 모델 버전·해시: 같은 폴더 provenance.json
- 실행 도구: scripts/evaluate_food_classifiers.py
- 모델 출처: https://huggingface.co/nateraw/food , https://huggingface.co/prithivMLmods/Food-101-93M
