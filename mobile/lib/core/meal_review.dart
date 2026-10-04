/// Reasons derived from current inputs and the server's nutrition source snapshot.
List<String> mealItemIssues(Map<String, dynamic> item) {
  final issues = <String>[];
  if ((item['name'] ?? '').toString().trim().isEmpty) {
    issues.add('음식명을 입력해 주세요.');
  }
  final grams = item['grams'];
  final validGrams =
      grams is num && grams.isFinite && grams > 0 && grams <= 10000;
  if (!validGrams) issues.add('중량을 0보다 크고 10,000g 이하인 숫자로 입력해 주세요.');
  final hasFood = (item['foodId'] ?? '').toString().isNotEmpty;
  if (!hasFood) {
    issues.add('영양 DB 항목을 선택해 주세요.');
  } else if (item['source'] is Map) {
    final source = item['source'] as Map;
    if (source['basis_unit'] != 'g' || source['searchable'] == false) {
      issues.add('중량(g) 계산이 가능한 영양 DB 항목을 다시 선택해 주세요.');
    } else {
      final missing = <String>[
        for (final entry in {
          'kcal': '열량',
          'carbs': '탄수화물',
          'protein': '단백질',
          'fat': '지방',
        }.entries)
          if (source[entry.key] == null) entry.value,
      ];
      if (missing.isNotEmpty) {
        issues.add('선택한 DB에 ${missing.join(', ')} 값이 없습니다. 다른 DB 항목을 선택해 주세요.');
      }
    }
  } else if (validGrams && item['nutrition'] == null) {
    issues.add('영양 DB 근거를 확인할 수 없습니다. DB 항목을 다시 선택해 주세요.');
  }
  if (item['confirmed'] != true) {
    issues.add('음식·중량·DB 항목 확인 체크가 필요합니다.');
  }
  return issues;
}

String mealSaveSummary(Map<String, dynamic> meal) {
  final items = (meal['items'] as List);
  if (meal['status'] == 'COMPLETE') {
    return '저장 완료 · 영양 재계산 완료 · 일간/주간 합계에 반영됩니다.';
  }
  if (items.isEmpty) return '저장 완료 · 음식이 없어 분석 미완료로 보관됩니다. 음식을 추가해 주세요.';
  final count = items.where((item) => item['status'] != 'COMPLETE').length;
  return '저장 완료 · ${count == 0 ? items.length : count}개 음식의 확인이 필요합니다. 이 식단은 아직 합계에 반영되지 않습니다.';
}
