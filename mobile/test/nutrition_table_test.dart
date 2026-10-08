import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:foodify/core/api.dart';
import 'package:foodify/features/nutrition_table.dart';
import 'package:foodify/features/screens.dart';

class AnalysisApi extends Api {
  String status = 'RUNNING';
  bool enabled = true;
  @override
  Future<dynamic> request(String method, String path, {dynamic data}) async => {
    'status': status,
    'analysisEnabled': enabled,
    'version': 1,
    'analyses': [],
    'items': status == 'COMPLETE'
        ? [
            {
              'name': '밥',
              'grams': 100,
              'foodId': 'rice',
              'status': 'COMPLETE',
              'nutrition': {'kcal': 150, 'carbs': 30, 'protein': 3, 'fat': 1},
            },
          ]
        : [],
  };
}

void main() {
  testWidgets('영양 표는 4행 2열이며 미상과 0을 구분한다', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: NutritionTable(
            values: {'kcal': 100, 'carbs': 0, 'protein': null, 'fat': 2.25},
          ),
        ),
      ),
    );
    final table = tester.widget<Table>(find.byType(Table));
    expect(table.children.length, 4);
    expect(table.children.every((row) => row.children.length == 2), isTrue);
    expect(find.text('0.0 g'), findsOneWidget);
    expect(find.text('계산 대기'), findsOneWidget);
  });

  test('합계는 모든 음식의 해당 영양값이 있을 때만 표시한다', () {
    expect(mealNutritionTotals([])['kcal'], isNull);
    final totals = mealNutritionTotals([
      {
        'nutrition': {'kcal': 100, 'carbs': 0, 'protein': 5, 'fat': 2},
      },
      {
        'nutrition': {'kcal': 200, 'carbs': 10, 'protein': 3, 'fat': null},
      },
    ]);
    expect(totals, {'kcal': 300, 'carbs': 10, 'protein': 8, 'fat': null});
  });

  testWidgets('분석 중 로딩 후 완료 시 상단 합계와 하단 음식 표 표시', (tester) async {
    final api = AnalysisApi();
    tester.view.physicalSize = const Size(1000, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [apiProvider.overrideWithValue(api)],
        child: const MaterialApp(home: MealScreen(id: 'test')),
      ),
    );
    await tester.pump();
    await tester.pump();
    expect(find.text('음식을 분석하고 있어요'), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    expect(find.byType(TextFormField), findsNothing);
    api.status = 'COMPLETE';
    await tester.pump(const Duration(seconds: 3));
    await tester.pumpAndSettle();
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(find.byType(NutritionTable), findsNWidgets(2));
    expect(find.text('150.0 kcal'), findsNWidgets(2));
    expect(
      tester.getTopLeft(find.text('한 끼 전체 영양정보')).dy,
      lessThan(tester.getTopLeft(find.text('음식별 영양정보')).dy),
    );
    await tester.pumpWidget(const SizedBox.shrink());
  });

  testWidgets('중지된 작업은 무한 로딩 대신 중지 안내 표시', (tester) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: AnalysisLoading(queued: true, paused: true)),
      ),
    );
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(find.text('분석이 일시 중지되어 있습니다'), findsOneWidget);
  });
}
