import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:foodify/core/food_regions.dart';

Map<String, dynamic> metadata({bool automatic = false}) => {
  'schemaVersion': 1,
  'imageFingerprint': 'a' * 64,
  'imageWidth': 100,
  'imageHeight': 100,
  'coordinateSpace': 'display_image_normalized',
  'automaticAvailable': automatic,
};

class FakeRegions extends RegionService {
  FakeRegions({this.automatic = false, this.failPrepare = false, this.reply});
  bool automatic, failPrepare;
  Map<String, dynamic>? reply;
  Completer<Map<String, dynamic>>? waiting;
  int prepares = 0;
  @override
  Future<Map<String, dynamic>> prepare(String path) async {
    prepares++;
    if (waiting != null) {
      return waiting!.future;
    }
    if (failPrepare) {
      throw PlatformException(code: 'FAILED', message: '사진 읽기 실패');
    }
    return metadata(automatic: automatic);
  }

  @override
  Future<Map<String, dynamic>> segment(String path, String fingerprint) async {
    if (reply != null) {
      return reply!;
    }
    throw PlatformException(code: 'FAILED', message: '영역 추출 실패');
  }
}

void triangle(RegionController c) {
  c.add(const Offset(.1, .1));
  c.add(const Offset(.8, .1));
  c.add(const Offset(.5, .8));
  c.finishPolygon();
}

void main() {
  test('교차 경계와 퇴화 영역 거부', () {
    expect(
      validPolygon([
        const Offset(.1, .1),
        const Offset(.8, .8),
        const Offset(.1, .8),
        const Offset(.8, .1),
      ]),
      false,
    );
    expect(
      validPolygon([
        const Offset(.1, .1),
        const Offset(.3, .3),
        const Offset(.5, .5),
      ]),
      false,
    );
  });
  test('준비된 수동 보정 저장과 복원', () async {
    final c = RegionController('photo', FakeRegions());
    await c.start();
    expect(c.stage, RegionStage.reviewing);
    triangle(c);
    final draft = c.finish();
    expect(draft['volumeValidated'], false);
    final restored = RegionController('photo', FakeRegions(), initial: draft);
    await restored.start();
    expect(restored.polygons.length, 1);
    c.dispose();
    restored.dispose();
  });
  test('사진 준비 실패 후 재시도', () async {
    final service = FakeRegions(failPrepare: true);
    final c = RegionController('photo', service);
    await c.start();
    expect(c.stage, RegionStage.failed);
    expect(c.image, isNull);
    service.failPrepare = false;
    await c.start();
    expect(c.stage, RegionStage.reviewing);
    c.dispose();
  });
  test('자동 분할 실패에서 수동 보정으로 전환', () async {
    final c = RegionController('photo', FakeRegions(automatic: true));
    await c.start();
    expect(c.stage, RegionStage.failed);
    c.editManually();
    triangle(c);
    expect(c.finish()['source'], 'manual');
    c.dispose();
  });
  test('다른 사진의 자동 결과 거부', () async {
    final c = RegionController(
      'photo',
      FakeRegions(
        automatic: true,
        reply: {
          ...metadata(),
          'imageFingerprint': 'b' * 64,
          'foodPolygons': [
            [
              [.1, .1],
              [.8, .1],
              [.5, .8],
            ],
          ],
        },
      ),
    );
    await c.start();
    expect(c.stage, RegionStage.failed);
    expect(c.polygons, isEmpty);
    c.dispose();
  });
  test('늦은 결과와 중복 실행 차단', () async {
    final service = FakeRegions()..waiting = Completer();
    final c = RegionController('photo', service);
    final task = c.start();
    await c.start();
    expect(service.prepares, 1);
    c.dispose();
    service.waiting!.complete(metadata());
    await task;
  });
  test('미완성 영역은 적용 차단', () async {
    final c = RegionController('photo', FakeRegions());
    await c.start();
    triangle(c);
    c.add(const Offset(.2, .2));
    expect(c.finish, throwsStateError);
    c.dispose();
  });
}
