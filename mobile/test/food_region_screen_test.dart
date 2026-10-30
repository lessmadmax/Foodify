import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:foodify/features/food_region_screen.dart';
import 'food_regions_test.dart' show FakeRegions;

void main() {
  testWidgets('실패 이유와 재시도 버튼 표시', (tester) async {
    final service = FakeRegions(failPrepare: true);
    await tester.pumpWidget(
      MaterialApp(
        home: FoodRegionScreen(path: 'missing', service: service),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('사진 읽기 실패'), findsOneWidget);
    expect(find.text('직접 영역 지정'), findsNothing);
    await tester.tap(find.text('다시 시도'));
    await tester.pumpAndSettle();
    expect(service.prepares, 2);
  });
  testWidgets('경계를 그려 촬영 화면으로 보정 결과 전달', (tester) async {
    tester.view.physicalSize = const Size(1000, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final folder = Directory.systemTemp.createTempSync('foodify-region-');
    final file = File('${folder.path}/pixel.png')
      ..writeAsBytesSync(
        base64Decode(
          'iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAE0lEQVR4nGP8//8/AwwwwVl4OQCWbgMF7ZjH1AAAAABJRU5ErkJggg==',
        ),
      );
    addTearDown(() => folder.deleteSync(recursive: true));
    Map<String, dynamic>? saved;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () async {
                saved = await Navigator.of(context).push<Map<String, dynamic>>(
                  MaterialPageRoute(
                    builder: (_) => FoodRegionScreen(
                      path: file.path,
                      service: FakeRegions(),
                    ),
                  ),
                );
              },
              child: const Text('열기'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('열기'));
    await tester.pumpAndSettle();
    final rect = tester.getRect(find.byKey(const Key('region-canvas')));
    for (final p in [
      const Offset(.15, .15),
      const Offset(.8, .15),
      const Offset(.5, .8),
    ]) {
      await tester.tapAt(
        rect.topLeft + Offset(rect.width * p.dx, rect.height * p.dy),
      );
      await tester.pump();
    }
    await tester.ensureVisible(find.text('영역 완성'));
    await tester.tap(find.text('영역 완성'));
    await tester.pump();
    expect(find.text('지정 영역 1'), findsOneWidget);
    await tester.ensureVisible(find.text('보정 적용'));
    await tester.tap(find.text('보정 적용'));
    await tester.pumpAndSettle();
    expect(saved?['reviewed'], true);
    expect((saved?['foodPolygons'] as List).length, 1);
    expect(saved?['volumeValidated'], false);
  });
}
