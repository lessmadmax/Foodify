import 'dart:io';
import 'package:flutter/services.dart';

class FoodClassifier {
  static const channel = MethodChannel('foodify/food_classifier');
  static Future<Map<String, dynamic>?> classify(List<String> paths) async {
    if (!Platform.isAndroid) return null;
    final result = await channel
        .invokeMapMethod<String, dynamic>('classify', {'paths': paths})
        .timeout(const Duration(seconds: 90));
    return result;
  }
}
