import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:foodify/features/screens.dart';

void main() {
  testWidgets('촬영·갤러리·AR 입력과 전송 동의 제공', (tester) async {
    tester.view.physicalSize = const Size(1000, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      const ProviderScope(child: MaterialApp(home: CaptureScreen())),
    );
    expect(find.text('사진 촬영'), findsOneWidget);
    expect(find.text('갤러리에서 선택'), findsOneWidget);
    expect(find.text('AR 보조 촬영으로 분석'), findsNothing);
    expect(find.text('AR 깊이 진단 · 실험'), findsNothing);
    expect(find.byWidgetPredicate((w) => w is OutlinedButton), findsNWidgets(2));
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNull,
    );
    expect(find.textContaining('외부 AI에 전송'), findsOneWidget);
    expect(find.byType(CheckboxListTile), findsNothing);
  });
  testWidgets('사진 촬영은 AR을 우선 사용하고 취소 시 기존 화면 유지', (tester) async {
    const channel = MethodChannel('foodify/ar');
    final calls = <String>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call.method);
          if (call.method == 'capabilities') {
            return {'arCore': 'SUPPORTED_INSTALLED', 'depth': true};
          }
          return null;
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );
    await tester.pumpWidget(
      const ProviderScope(child: MaterialApp(home: CaptureScreen())),
    );
    await tester.ensureVisible(find.text('사진 촬영'));
    await tester.tap(find.text('사진 촬영'));
    await tester.pumpAndSettle();
    expect(calls, ['capabilities', 'captureMeal']);
    expect(find.text('사진 촬영'), findsOneWidget);
  });
  testWidgets('설정 개발자 메뉴에서 진단 실행', (tester) async {
    const channel = MethodChannel('foodify/ar');
    String? method;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          method = call.method;
          return null;
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null),
    );
    await tester.pumpWidget(const MaterialApp(home: SettingsScreen()));
    expect(find.text('AR 깊이 진단 · 실험'), findsNothing);
    await tester.tap(find.text('개발자 메뉴'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('AR 깊이 진단 · 실험'));
    await tester.pumpAndSettle();
    expect(method, 'openDiagnostics');
  });
}
