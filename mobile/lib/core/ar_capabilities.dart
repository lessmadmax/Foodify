import 'package:flutter/services.dart';

class ArCapabilities {
  static const _channel = MethodChannel('foodify/ar');
  static Future<Map<String, dynamic>?> captureMeal() async {
    try {
      return await _channel.invokeMapMethod<String, dynamic>('captureMeal');
    } on MissingPluginException {
      throw StateError('AR 보조 촬영은 Android 앱에서 사용할 수 있습니다.');
    }
  }

  static Future<void> openDiagnostics() async {
    try {
      await _channel.invokeMethod<void>('openDiagnostics');
    } on MissingPluginException {
      throw StateError('AR 진단은 Android 앱에서 사용할 수 있습니다.');
    }
  }

  static Future<Map<String, dynamic>> check() async {
    try {
      final result = await _channel.invokeMapMethod<String, dynamic>(
        'capabilities',
      );
      return result ?? {'arCore': 'UNKNOWN', 'depth': null};
    } on PlatformException {
      return {'arCore': 'UNKNOWN', 'depth': null};
    } on MissingPluginException {
      return {'arCore': 'UNAVAILABLE', 'depth': null};
    }
  }
}
