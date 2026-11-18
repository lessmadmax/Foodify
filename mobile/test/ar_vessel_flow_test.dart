import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:foodify/core/api.dart';
import 'package:foodify/features/ar_weight_screen.dart';

class _VesselApi extends Api {
  int detections = 0;
  bool unavailable = false;
  @override
  Future<dynamic> request(String method, String path, {dynamic data}) async {
    if (path == '/ar/vessel') {
      detections++;
      if (unavailable) throw StateError('offline');
      return {
        'vesselType': 'flat_plate',
        'confidence': 'high',
        'usable': true,
        'internalDepthCm': .8,
        'rimPoints': [
          [.1, .1],
          [.9, .1],
          [.9, .9],
          [.1, .9],
        ],
      };
    }
    if (path == '/ar/preview') {
      return {
        'areaCm2': data['areaCm2'],
        'heightCm': data['heightCm'],
        'distanceMm': data['distanceMm'],
        'grams': 20.0,
        'volumeMl': 30.0,
        'densityGroupLabel': '분류',
        'densityGPerMl': .7,
      };
    }
    throw StateError('Unexpected request: $path');
  }
}

void main() {
  for (final unavailable in [false, true]) {
    testWidgets('그릇 자동 연결·캐시·화면 비노출 unavailable=$unavailable', (tester) async {
      tester.view.physicalSize = const Size(1000, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final temp = Directory.systemTemp.createTempSync('foodify-vessel-');
      final file = File('${temp.path}/analysis.png');
      file.writeAsBytesSync(
        base64Decode(
          'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
        ),
      );
      addTearDown(() {
        // Windows image decoding may still hold the temporary file at teardown.
        try {
          temp.deleteSync(recursive: true);
        } on FileSystemException {
          /* OS temp cleanup */
        }
      });
      final calls = <Map>[];
      final api = _VesselApi()..unavailable = unavailable;
      const ar = MethodChannel('foodify/ar');
      const research = MethodChannel('foodify/ar_research');
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        ar,
        (_) async => {'path': file.path},
      );
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(research, (
        call,
      ) async {
        switch (call.method) {
          case 'prepare':
            return {
              'framePath': temp.path,
              'imagePath': file.path,
              'imageWidth': 640,
              'imageHeight': 480,
              'imageFingerprint': 'image',
              'frameFingerprint': 'frame',
            };
          case 'prepareSegment':
            return {};
          case 'segmentPoints':
            return {
              'foodPolygons': [
                [
                  [.4, .4],
                  [.6, .4],
                  [.6, .6],
                  [.4, .6],
                ],
              ],
              'source': 'tap_sam',
              'pieces': 1,
            };
          case 'estimate':
            return {'volumeMl': null};
          case 'scale':
            final input = Map.from(call.arguments as Map);
            calls.add(input);
            return {
              'areaCm2': 20.0,
              'measuredHeightCm': 1.5,
              'measuredHeightSource': 'DEPTH',
              'measuredHeightSamples': 30,
              'distanceMm': 308.0,
              'vesselReference': {
                'applied': !unavailable,
                'vesselType': 'flat_plate',
              },
            };
        }
        throw StateError(call.method);
      });
      addTearDown(() {
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          ar,
          null,
        );
        tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
          research,
          null,
        );
      });
      await tester.pumpWidget(
        MaterialApp(
          home: ArWeightScreen(foodName: '밥', api: api),
        ),
      );
      final dynamic state = tester.state(find.byType(ArWeightScreen));
      await tester.runAsync(() async {
        await state.capture();
        await state.addTap(const Offset(320, 240), const Size(640, 480));
        await state.confirmRegion();
      });
      await tester.pumpAndSettle();
      expect(api.detections, 1);
      expect((calls.single['vesselReference'] as Map)['usable'], !unavailable);
      expect(find.textContaining('접시 영역 직접'), findsNothing);
      expect(find.textContaining('flat_plate'), findsNothing);
      await tester.runAsync(() async => await state.estimate());
      await tester.pumpAndSettle();
      expect(api.detections, 1);
      expect(calls.length, 2);
      await tester.runAsync(() async {
        await state.capture();
        await state.addTap(const Offset(320, 240), const Size(640, 480));
        await state.confirmRegion();
      });
      await tester.pumpAndSettle();
      expect(api.detections, 2);
      expect(tester.takeException(), isNull);
    });
  }
}
