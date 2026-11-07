import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:foodify/features/ar_research_screen.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('foodify/ar_research');
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );
  testWidgets('서버 없이 실측 기록 화면 진입', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'sessions' || call.method == 'records') return [];
          throw StateError('예상하지 않은 호출: ${call.method}');
        });
    tester.view.physicalSize = const Size(1000, 5000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(const MaterialApp(home: ArResearchScreen()));
    await tester.pumpAndSettle();
    expect(find.text('전자저울 실측 자료 수집'), findsOneWidget);
    expect(find.text('저장된 실측 기록 0건'), findsOneWidget);
    expect(
      tester
          .widget<FilledButton>(find.widgetWithText(FilledButton, '실측 기록 저장'))
          .onPressed,
      isNull,
    );
  });
  testWidgets('깊이 계산 미완료여도 저울값과 용도를 저장', (tester) async {
    tester.view.physicalSize = const Size(1000, 5000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final temp = Directory.systemTemp.createTempSync('foodify-ar-test');
    addTearDown(() => temp.deleteSync(recursive: true));
    final image = File('${temp.path}/pixel.png')
      ..writeAsBytesSync(
        base64Decode(
          'iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAE0lEQVR4nGP8//8/AwwwwVl4OQCWbgMF7ZjH1AAAAABJRU5ErkJggg==',
        ),
      );
    Map? saved;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'sessions':
              return [
                {'id': '11111111-1111-4111-8111-111111111111', 'savedAt': 1},
              ];
            case 'records':
              return saved == null
                  ? []
                  : [
                      {
                        ...saved!,
                        'id': 'r1',
                        'framePath':
                            '/alias/11111111-1111-4111-8111-111111111111/frame-00',
                        'result': {'volumeMl': null, 'holdReasons': []},
                      },
                    ];
            case 'frames':
              return [
                {
                  'id': 'frame-00',
                  'path': '/app/11111111-1111-4111-8111-111111111111/frame-00',
                  'depthAgeMs': 0,
                },
              ];
            case 'prepare':
              return {
                'imagePath': image.path,
                'framePath':
                    '/app/11111111-1111-4111-8111-111111111111/frame-00',
              };
            case 'save':
              saved = call.arguments as Map;
              return {
                'result': {
                  'volumeMl': null,
                  'holdReasons': ['음식 영역과 접시 기준면을 지정해 주세요.'],
                },
              };
            default:
              throw StateError(call.method);
          }
        });
    await tester.pumpWidget(const MaterialApp(home: ArResearchScreen()));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).at(0), '밥');
    await tester.enterText(find.byType(TextField).at(1), '밥-A');
    await tester.enterText(find.byType(TextField).at(2), '123.4');
    await tester.ensureVisible(find.text('실측 기록 저장'));
    await tester.tap(find.text('실측 기록 저장'));
    await tester.pumpAndSettle();
    expect(saved?['scaleGrams'], 123.4);
    expect(saved?['split'], 'CALIBRATION');
    expect(saved?['foodRegion'], isNull);
    expect(find.text('음식 영역과 접시 기준면을 지정해 주세요.'), findsNothing);
    expect(find.text('• 음식 영역과 접시 기준면을 지정해 주세요.'), findsOneWidget);
    await tester.tap(find.text('밥 · 123.4 g'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });
}
