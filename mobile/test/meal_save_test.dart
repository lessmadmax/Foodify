import 'dart:async';
import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:foodify/core/api.dart';
import 'package:foodify/core/meal_review.dart';
import 'package:foodify/features/screens.dart';

Map<String, dynamic> record({
  int version = 1,
  bool complete = false,
  double grams = 100,
}) => {
  'id': 'meal-test',
  'version': version,
  'status': complete ? 'COMPLETE' : 'INCOMPLETE',
  'analyses': [
    {'error_code': 'USER_REVIEW_REQUIRED'},
  ],
  'items': [
    <String, dynamic>{
      'name': '시험 음식',
      'grams': grams,
      'foodId': 'food',
      'confirmed': complete,
      'status': complete ? 'COMPLETE' : 'NEEDS_REVIEW',
      'nutrition': {'kcal': grams * 2, 'carbs': 30, 'protein': 10, 'fat': 5},
      'source': <String, dynamic>{
        'basis_unit': 'g',
        'searchable': true,
        'kcal': 200,
        'carbs': 30,
        'protein': 10,
        'fat': 5,
        'source': '시험 자료',
      },
    },
  ],
};

class MealApi extends Api {
  int gets = 0, patches = 0;
  dynamic payload;
  Completer<dynamic>? slowRead;
  final write = Completer<dynamic>();
  @override
  Future<dynamic> request(String method, String path, {dynamic data}) async {
    if (method == 'GET') {
      gets++;
      if (gets > 1 && slowRead != null) return slowRead!.future;
      return jsonDecode(jsonEncode(record()));
    }
    if (method == 'PATCH') {
      patches++;
      payload = data;
      return write.future;
    }
    throw StateError('Unexpected request');
  }
}

Future<void> mountMeal(WidgetTester tester, MealApi api) async {
  tester.view.physicalSize = const Size(1000, 2400);
  tester.view.devicePixelRatio = 1;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(() async => tester.pumpWidget(const SizedBox.shrink()));
  await tester.pumpWidget(
    ProviderScope(
      overrides: [apiProvider.overrideWithValue(api)],
      child: const MaterialApp(home: MealScreen(id: 'meal-test')),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('미완료 이유에 빠진 영양소와 확인 체크를 구분', () {
    final item = Map<String, dynamic>.from((record()['items'] as List).first);
    item['source']['fat'] = null;
    final reasons = mealItemIssues(item);
    expect(reasons.any((r) => r.contains('지방')), isTrue);
    expect(reasons.any((r) => r.contains('체크')), isTrue);
    item['grams'] = null;
    item['foodId'] = '';
    expect(mealItemIssues(item).any((r) => r.contains('중량')), isTrue);
    expect(mealItemIssues(item).any((r) => r.contains('DB 항목을 선택')), isTrue);
  });
  test('빈 식단 저장은 완료와 구분', () {
    expect(
      mealSaveSummary({'status': 'INCOMPLETE', 'items': []}),
      contains('음식이 없어'),
    );
  });
  testWidgets('저장 중 표시·중복 클릭 차단·응답 직접 반영', (tester) async {
    final api = MealApi();
    await mountMeal(tester, api);
    await tester.tap(find.text('수정 저장 · 영양 재계산'));
    await tester.pump();
    expect(find.text('저장 및 영양 재계산 중…'), findsOneWidget);
    final button = tester.widget<FilledButton>(
      find.widgetWithText(FilledButton, '저장 및 영양 재계산 중…'),
    );
    expect(button.onPressed, isNull);
    expect(
      tester.widget<TextFormField>(find.byType(TextFormField).first).enabled,
      isFalse,
    );
    api.write.complete(record(version: 2, complete: true, grams: 150));
    await tester.pumpAndSettle();
    expect(api.patches, 1);
    expect(api.gets, 1);
    expect(find.textContaining('참고 추정: 300.0 kcal'), findsOneWidget);
    expect(find.textContaining('저장 완료 · 영양 재계산 완료'), findsWidgets);
    expect(find.textContaining('참고 추정치가 준비됐습니다'), findsNothing);
  });
  testWidgets('미완료 저장도 성공 및 부족 정보 표시', (tester) async {
    final api = MealApi();
    await mountMeal(tester, api);
    await tester.tap(find.text('수정 저장 · 영양 재계산'));
    await tester.pump();
    final response = record(version: 2);
    final item = (response['items'] as List).first;
    item['nutrition'] = null;
    item['source']['protein'] = null;
    api.write.complete(response);
    await tester.pumpAndSettle();
    expect(find.textContaining('1개 음식의 확인이 필요'), findsWidgets);
    expect(find.textContaining('선택한 DB에 단백질 값이 없습니다'), findsOneWidget);
  });
  testWidgets('저장 실패 시 입력 유지 및 실패 안내', (tester) async {
    final api = MealApi();
    await mountMeal(tester, api);
    await tester.enterText(find.byType(TextFormField).at(1), '175');
    await tester.tap(find.text('수정 저장 · 영양 재계산'));
    await tester.pump();
    api.write.completeError(
      DioException(requestOptions: RequestOptions(path: '/meals/meal-test')),
    );
    await tester.pumpAndSettle();
    expect(find.textContaining('저장 실패'), findsWidgets);
    expect(api.payload['items'][0]['grams'], 175);
    final input = tester.widget<EditableText>(find.byType(EditableText).at(1));
    expect(double.tryParse(input.controller.text), 175);
    expect(find.text('수정 저장 · 영양 재계산'), findsOneWidget);
  });
  testWidgets('저장 이전의 늦은 조회가 저장 결과를 덮어쓰지 않음', (tester) async {
    final api = MealApi()..slowRead = Completer<dynamic>();
    await mountMeal(tester, api);
    await tester.tap(find.byIcon(Icons.refresh));
    await tester.pump();
    await tester.tap(find.text('수정 저장 · 영양 재계산'));
    await tester.pump();
    api.write.complete(record(version: 2, complete: true, grams: 200));
    await tester.pumpAndSettle();
    api.slowRead!.complete(record());
    await tester.pumpAndSettle();
    expect(find.textContaining('참고 추정: 400.0 kcal'), findsOneWidget);
    expect(api.gets, 2);
  });
  testWidgets('중량 변경 시 이전 영양값과 확인 상태 해제', (tester) async {
    final api = MealApi();
    await mountMeal(tester, api);
    await tester.tap(find.byType(CheckboxListTile));
    await tester.enterText(find.byType(TextFormField).at(1), '200');
    await tester.pumpAndSettle();
    expect(find.textContaining('참고 추정:'), findsNothing);
    expect(
      tester.widget<CheckboxListTile>(find.byType(CheckboxListTile)).value,
      isFalse,
    );
    expect(find.textContaining('입력값이 변경되었습니다'), findsOneWidget);
  });
}
