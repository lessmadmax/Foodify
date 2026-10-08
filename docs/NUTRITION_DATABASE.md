# Foodify 영양 데이터베이스

## 구성

`nutrition_datasets`(파일) → `nutrition_source_rows`(원본 행) → `food_nutrition_versions`(버전별 영양값) → `foods`(검색용 식품).
`food_aliases`는 식품의 검색 표기를, `food_portions`는 영양값 버전의 근거 있는 1회 참고량을 보관한다.

- 원본 CSV는 `data/nutrition/raw`에 보존한다. 파일 SHA-256이 데이터셋 ID이며 원본 행의 모든 열도 JSON으로 보관한다.
- 같은 자료의 반복 행은 출처 이력으로 보존하되 식품과 영양값은 재사용한다. 따라서 출처 행 수와 식품 수는 다르다.
- 식품 ID는 분류와 원본 식품 코드로 결정한다. 영양값 ID는 이름·기준량·단위·영양값·출처·기준일·제조사·참고량의 해시이다.
- 동일 식품은 최신 `데이터기준일자`를 우선한다. 같은 최신 날짜에 서로 다른 영양값 또는 이름·단위가 있으면 검색에서 보류한다. 이는 실제 품질을 보장하는 순위가 아니며 충돌 목록을 검토해야 한다.
- `foods`의 영양값 열은 기존 API와 호환하는 현재값 복사본이다. 원본 근거는 버전 테이블이며 `current_version_id`로 연결한다. 기존 수동 입력 데이터는 legacy 분류를 유지한다.
- 기존 `basis_grams` 열은 호환성상 유지한다. 카탈로그의 공식 기준량은 `food_nutrition_versions.basis_amount/basis_unit`이다. 계산은 `basis_unit=g`에서만 허용한다. 미상 기준량의 호환성용 값 1은 계산에 사용하지 않는다.
- 영양값의 빈 칸은 NULL이며 실제 0과 구분한다. g와 ml는 별도 단위이다. 현재 API는 g 자료만 검색·계산하고 ml와 건강기능식품은 참고 보관한다.
- 일부 필수 영양값이 없는 항목도 검색 후보로 보존하며 결과는 확인 필요 상태로 반환한다. 합계에는 완료된 식단만 포함한다.
- 별칭은 원문 이름의 밑줄을 공백으로 바꾼 표기로 시작한다. 검색 시 공백·밑줄·하이픈을 정규화하고, 버거는 구체적 이름 → 불고기/치즈/치킨 유형 → 햄버거 순으로 확장한다. 검색 후보에 축약 사용 여부를 표시한다. 다른 음식의 의미상 동의어와 조리 방식별 검색 고도화는 후속 작업이다.
- 명확하게 g/ml로 쓰인 1인(회)분량 참고량만 `food_portions`에 적재한다. 제품 전체 중량을 1인분으로 간주하지 않는다. 건강기능식품의 복잡한 섭취 단위는 원본 행으로 보관한다.
- 식단의 `items` JSON에는 계산 당시 음식·영양값·자료원·버전 ID의 스냅샷을 보관한다. 공공 DB 갱신만으로 기존 기록이 바뀌지 않으며, 사용자가 기록을 수정하면 현재 기준값으로 재계산한다.

## 실행

프로젝트 루트에서 Python 3으로 실행:

```powershell
python scripts/prepare_nutrition.py
python scripts/test_prepare_nutrition.py
```

전체 원본 폴더를 함께 변환한다. 출력은 `data/nutrition/processed/catalog.jsonl`과 `report.json`이다. 개별 부분 파일만으로 갱신하면 현재값이 이전 버전으로 돌아갈 수 있으므로 전체 원본 집합을 유지한다.

백엔드 폴더에서:

```powershell
mvn package
java -jar target/foodify-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local,demo --app.worker-enabled=false --server.port=0 --app.import-catalog=../data/nutrition/processed/catalog.jsonl --app.import-exit=true
```

demo는 H2 파일 DB `backend/data/foodify.mv.db`를 사용한다. 외부 AI 작업은 비활성화한다. 일반 서버 실행 시에도 local,demo 프로필을 쓰면 같은 데이터를 조회한다.

실제 MySQL은 demo 프로필을 제외하고 DB_URL/DB_USER/DB_PASSWORD를 설정한다. RDS 적용 전 백업 및 MySQL 마이그레이션·적재 테스트가 필요하다. 이번 H2 검증은 MySQL 실검증과 구분한다.

적재는 하나의 트랜잭션으로 실행하고 오류 시 롤백한다. 동일 파일 재실행 시 dataset/food/version/source-row의 식별자로 중복을 막는다. 적재할 때는 앱 서버를 중지하고 실행한다. 현재 자료 규모에 맞춘 오프라인 단일 실행 방식이며 향후 자료가 커지면 임시 테이블·배치 전환을 검토한다.

## 보관과 보안

CSV 및 변환 데이터는 Git 제외 대상으로 설정했다. 원본은 수동으로 별도 백업하고 출처·이용조건을 함께 관리한다. 데이터 파일이나 보고서에는 API 키를 포함하지 않는다.
