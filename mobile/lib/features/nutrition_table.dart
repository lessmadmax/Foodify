import 'package:flutter/material.dart';

/// Four nutrient rows, each with a label and value. Unknown is distinct from zero.
class NutritionTable extends StatelessWidget {
  const NutritionTable({super.key, required this.values});
  final Map<dynamic, dynamic> values;

  static const nutrients = [
    ('kcal', '칼로리', 'kcal'),
    ('carbs', '탄수화물', 'g'),
    ('protein', '단백질', 'g'),
    ('fat', '지방', 'g'),
  ];

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Table(
      columnWidths: const {0: FlexColumnWidth(), 1: FlexColumnWidth()},
      defaultVerticalAlignment: TableCellVerticalAlignment.middle,
      border: TableBorder.all(color: colors.outlineVariant),
      children: [
        for (final (key, label, unit) in nutrients)
          TableRow(
            children: [
              Container(
                color: colors.primaryContainer,
                padding: const EdgeInsets.all(12),
                child: Text(
                  label,
                  style: TextStyle(color: colors.onPrimaryContainer),
                ),
              ),
              Padding(
                padding: const EdgeInsets.all(12),
                child: Text(
                  values[key] is num
                      ? '${(values[key] as num).toStringAsFixed(1)} $unit'
                      : '계산 대기',
                  textAlign: TextAlign.end,
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
              ),
            ],
          ),
      ],
    );
  }
}

Map<String, dynamic> mealNutritionTotals(List<Map<String, dynamic>> items) => {
  for (final (key, _, _) in NutritionTable.nutrients)
    key: items.isNotEmpty && items.every((i) => i['nutrition']?[key] is num)
        ? items.fold<num>(0, (sum, i) => sum + (i['nutrition'][key] as num))
        : null,
};

class AnalysisLoading extends StatelessWidget {
  const AnalysisLoading({
    super.key,
    this.queued = false,
    this.paused = false,
    this.error,
    this.onRetry,
  });
  final bool queued, paused;
  final String? error;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) => Center(
    child: SingleChildScrollView(
      padding: const EdgeInsets.all(32),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (paused)
            const Icon(Icons.pause_circle_outline, size: 56)
          else
            const CircularProgressIndicator(),
          const SizedBox(height: 24),
          Text(
            paused
                ? '분석이 일시 중지되어 있습니다'
                : queued
                ? '분석 순서를 기다리고 있어요'
                : '음식을 분석하고 있어요',
            style: Theme.of(context).textTheme.titleLarge,
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 12),
          Text(
            paused
                ? '사진은 저장됐습니다. 서버의 자동 분석이 활성화되면 이어서 처리됩니다.'
                : '음식 종류와 중량을 추정하고 영양정보를 확인합니다.\n완료되면 결과가 자동으로 표시됩니다.',
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 12),
          const Text(
            '다른 화면으로 이동해도 서버에서 분석이 이어집니다.',
            textAlign: TextAlign.center,
          ),
          if (error != null) ...[
            const SizedBox(height: 16),
            Text(error!, textAlign: TextAlign.center),
            TextButton(onPressed: onRetry, child: const Text('상태 다시 확인')),
          ],
        ],
      ),
    ),
  );
}
