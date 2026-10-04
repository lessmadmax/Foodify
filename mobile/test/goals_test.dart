import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:foodify/core/api.dart';
import 'package:foodify/features/screens.dart';

class GoalAdapter implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? stream,
    Future<void>? cancel,
  ) async {
    requests.add(options);
    return ResponseBody.fromString(
      options.path == '/me'
          ? '{"goals":{}}'
          : '{"kcal":2136,"carbs":267.0,"protein":106.8,"fat":71.2}',
      200,
      headers: {
        'content-type': ['application/json'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  setUp(() => FlutterSecureStorage.setMockInitialValues({}));
  testWidgets('프로필로 계산하고 입력 변경 시 이전 추정치 해제', (tester) async {
    tester.view.physicalSize = const Size(1000, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final adapter = GoalAdapter();
    final dio = Dio(BaseOptions(baseUrl: 'http://localhost'));
    dio.httpClientAdapter = adapter;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [apiProvider.overrideWithValue(Api(client: dio))],
        child: const MaterialApp(home: GoalsScreen()),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('관리 목적'), findsNothing);
    final inputs = find.byType(TextField);
    await tester.enterText(inputs.at(0), '30');
    await tester.enterText(inputs.at(1), '180');
    await tester.enterText(inputs.at(2), '80');
    await tester.tap(find.byType(DropdownButtonFormField<String>).at(0));
    await tester.pumpAndSettle();
    await tester.tap(find.text('남성').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byType(DropdownButtonFormField<String>).at(1));
    await tester.pumpAndSettle();
    await tester.tap(find.text('낮음 · 앉아서 생활, 운동 거의 없음').last);
    await tester.pumpAndSettle();
    await tester.tap(find.byType(CheckboxListTile));
    await tester.pumpAndSettle();
    await tester.tap(find.text('목표 자동 계산'));
    await tester.pumpAndSettle();
    expect(find.text('하루 참고 목표: 2136 kcal'), findsOneWidget);
    final request = adapter.requests.last;
    expect(request.path, '/me/goals/preview');
    expect(request.data['age'], 30);
    expect(request.data['activityLevel'], 'SEDENTARY');
    expect((request.data as Map).containsKey('purpose'), isFalse);
    await tester.enterText(inputs.at(2), '81');
    await tester.pumpAndSettle();
    expect(find.text('하루 참고 목표: 2136 kcal'), findsNothing);
    expect(find.text('이 목표 저장'), findsNothing);
  });
}
