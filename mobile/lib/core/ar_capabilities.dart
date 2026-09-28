import 'package:flutter/services.dart';

class ArCapabilities {
  static const _channel = MethodChannel('foodify/ar');
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
