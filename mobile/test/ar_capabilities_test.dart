import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:foodify/core/ar_capabilities.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('foodify/ar');
  tearDown(
    () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null),
  );
  test('AR 진단은 전용 플랫폼 채널로 실행', () async {
    String? called;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          called = call.method;
          return null;
        });
    await ArCapabilities.openDiagnostics();
    expect(called, 'openDiagnostics');
  });
  test('미지원 플랫폼에서는 안내 오류 반환', () async {
    await expectLater(ArCapabilities.openDiagnostics(), throwsStateError);
  });
  test('지원 여부 확인 실패도 앱을 중단하지 않음', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (_) async {
          throw PlatformException(code: 'UNAVAILABLE');
        });
    expect((await ArCapabilities.check())['arCore'], 'UNKNOWN');
  });
}
