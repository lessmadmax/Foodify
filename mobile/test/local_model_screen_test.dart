import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:foodify/features/local_model_screen.dart';

void main() {
  const channel = MethodChannel('foodify/local_model');
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('분석은 검증된 모델과 사진이 준비될 때 활성화된다', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          channel,
          (call) async => {'ready': false, 'busy': false, 'stage': '대기'},
        );
    await tester.pumpWidget(const MaterialApp(home: LocalModelScreen()));
    await tester.pumpAndSettle();
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNull,
    );
    expect(find.text('모델 파일 불러오기'), findsOneWidget);
  });

  testWidgets('파일 선택 취소 후 다시 모델을 선택할 수 있다', (tester) async {
    var imports = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'importModel') {
            imports++;
            return null;
          }
          return {'ready': false, 'busy': false, 'stage': '대기'};
        });
    await tester.pumpWidget(const MaterialApp(home: LocalModelScreen()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('모델 파일 불러오기'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('모델 파일 불러오기'));
    await tester.pumpAndSettle();
    expect(imports, 2);
    expect(find.byType(LinearProgressIndicator), findsNothing);
  });

  testWidgets('모델 검증 실패 이유가 표시된다', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'importModel') {
            throw PlatformException(
              code: 'IMPORT_FAILED',
              message: '모델 무결성 검증에 실패했습니다.',
            );
          }
          return {'ready': false, 'busy': false, 'stage': '대기'};
        });
    await tester.pumpWidget(const MaterialApp(home: LocalModelScreen()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('모델 파일 불러오기'));
    await tester.pumpAndSettle();
    expect(find.text('모델 무결성 검증에 실패했습니다.'), findsOneWidget);
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
      isNull,
    );
  });
  testWidgets('메모리 해제 후 모델 파일은 검증 상태를 유지한다', (tester) async {
    var loaded = true;
    var released = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'releaseModel') {
            loaded = false;
            released++;
          }
          return {'ready': true, 'busy': false, 'modelLoaded': loaded};
        });
    await tester.pumpWidget(const MaterialApp(home: LocalModelScreen()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('모델 메모리 해제'));
    await tester.pumpAndSettle();
    expect(released, 1);
    expect(find.text('모델 메모리 해제'), findsNothing);
    expect(find.text('첫 분석 시 모델을 초기화합니다.'), findsOneWidget);
    expect(find.text('모델 검증 완료 · 약 2.59GB'), findsOneWidget);
  });
}
