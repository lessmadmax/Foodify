import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

enum RegionStage { preparing, segmenting, reviewing, failed, complete }

bool validPolygon(List<Offset> points) {
  if (points.length < 3 ||
      points.length > 200 ||
      points.any(
        (p) =>
            !p.dx.isFinite ||
            !p.dy.isFinite ||
            p.dx < 0 ||
            p.dx > 1 ||
            p.dy < 0 ||
            p.dy > 1,
      )) {
    return false;
  }
  double cross(Offset a, Offset b, Offset c) =>
      (b.dx - a.dx) * (c.dy - a.dy) - (b.dy - a.dy) * (c.dx - a.dx);
  double area = 0;
  for (var i = 0; i < points.length; i++) {
    final next = (i + 1) % points.length;
    if ((points[i] - points[next]).distance < .00001) {
      return false;
    }
    area += points[i].dx * points[next].dy - points[next].dx * points[i].dy;
    for (var j = i + 1; j < points.length; j++) {
      final jn = (j + 1) % points.length;
      if (j == next || jn == i) {
        continue;
      }
      final a = points[i], b = points[next], c = points[j], d = points[jn];
      // Reject crossing and touching nonadjacent edges.
      if (cross(a, b, c) * cross(a, b, d) <= 0 &&
          cross(c, d, a) * cross(c, d, b) <= 0 &&
          Rect.fromPoints(
            a,
            b,
          ).inflate(.000001).overlaps(Rect.fromPoints(c, d).inflate(.000001))) {
        return false;
      }
    }
  }
  return area.abs() > .0001;
}

class RegionService {
  static const channel = MethodChannel('foodify/segmentation');
  Future<Map<String, dynamic>> prepare(String path) async =>
      Map<String, dynamic>.from(
        await channel.invokeMapMethod('prepare', {'path': path}) ?? {},
      );
  Future<Map<String, dynamic>> segment(String path, String fingerprint) async =>
      Map<String, dynamic>.from(
        await channel.invokeMapMethod('segment', {
              'path': path,
              'imageFingerprint': fingerprint,
            }) ??
            {},
      );
}

class RegionController extends ChangeNotifier {
  RegionController(this.path, this.service, {this.initial});
  final String path;
  final RegionService service;
  final Map<String, dynamic>? initial;
  RegionStage stage = RegionStage.preparing;
  Map<String, dynamic>? image;
  String? message;
  final polygons = <List<Offset>>[];
  final pending = <Offset>[];
  bool get busy =>
      stage == RegionStage.preparing || stage == RegionStage.segmenting;
  bool _running = false, _closed = false;
  int _generation = 0;
  String source = 'manual';

  List<List<Offset>> _readPolygons(dynamic value) {
    if (value is! List || value.isEmpty || value.length > 30) {
      throw const FormatException('음식 영역 개수를 확인해 주세요.');
    }
    return value.map<List<Offset>>((p) {
      if (p is! List || p.length > 200) {
        throw const FormatException('영역 좌표가 올바르지 않습니다.');
      }
      final points = p.map<Offset>((v) {
        if (v is! List || v.length != 2 || v[0] is! num || v[1] is! num) {
          throw const FormatException('영역 좌표가 올바르지 않습니다.');
        }
        return Offset((v[0] as num).toDouble(), (v[1] as num).toDouble());
      }).toList();
      if (!validPolygon(points)) {
        throw const FormatException('경계가 겹치거나 너무 작습니다.');
      }
      return points;
    }).toList();
  }

  Future<void> start() async {
    if (_running || _closed) {
      return;
    }
    _running = true;
    final generation = ++_generation;
    stage = RegionStage.preparing;
    message = null;
    notifyListeners();
    bool current() => !_closed && generation == _generation;
    try {
      final prepared = await service
          .prepare(path)
          .timeout(const Duration(seconds: 30));
      if (!current()) {
        return;
      }
      if (prepared['schemaVersion'] != 1 ||
          prepared['imageFingerprint'] is! String ||
          !RegExp(
            r'^[a-f0-9]{64}$',
          ).hasMatch(prepared['imageFingerprint'] as String) ||
          prepared['imageWidth'] is! int ||
          prepared['imageHeight'] is! int ||
          (prepared['imageWidth'] as int) <= 0 ||
          (prepared['imageHeight'] as int) <= 0 ||
          (prepared['imageWidth'] as int) * (prepared['imageHeight'] as int) >
              20000000 ||
          ![
            'cpu_image_normalized',
            'display_image_normalized',
          ].contains(prepared['coordinateSpace'])) {
        throw const FormatException('사진 좌표 정보를 확인할 수 없습니다.');
      }
      image = prepared;
      if (polygons.isEmpty &&
          initial != null &&
          initial!['imageFingerprint'] == prepared['imageFingerprint'] &&
          initial!['coordinateSpace'] == prepared['coordinateSpace'] &&
          initial!['imageWidth'] == prepared['imageWidth'] &&
          initial!['imageHeight'] == prepared['imageHeight']) {
        polygons.addAll(_readPolygons(initial!['foodPolygons']));
      }
      if (polygons.isNotEmpty || prepared['automaticAvailable'] != true) {
        stage = RegionStage.reviewing;
        message = prepared['automaticAvailable'] == true
            ? null
            : '자동 경계 추출은 Android 연동 준비 중입니다. 음식 테두리를 눌러 영역을 지정해 주세요.';
        return;
      }
      stage = RegionStage.segmenting;
      notifyListeners();
      final result = await service
          .segment(path, prepared['imageFingerprint'] as String)
          .timeout(const Duration(seconds: 120));
      if (!current()) {
        return;
      }
      if (result['schemaVersion'] != 1 ||
          result['imageFingerprint'] != prepared['imageFingerprint'] ||
          result['coordinateSpace'] != prepared['coordinateSpace'] ||
          result['imageWidth'] != prepared['imageWidth'] ||
          result['imageHeight'] != prepared['imageHeight']) {
        throw const FormatException('분석 결과와 현재 사진이 다릅니다.');
      }
      final received = _readPolygons(result['foodPolygons']);
      polygons
        ..clear()
        ..addAll(received);
      source = 'android_segmentation';
      stage = RegionStage.reviewing;
    } catch (e) {
      if (current()) {
        stage = RegionStage.failed;
        message = e is PlatformException
            ? e.message ?? '분석을 완료하지 못했습니다.'
            : e is FormatException
            ? e.message
            : '사진 처리가 지연되거나 실패했습니다. 다시 시도해 주세요.';
      }
    } finally {
      if (current()) {
        _running = false;
        notifyListeners();
      }
    }
  }

  void imageFailed() {
    if (_closed || image == null) {
      return;
    }
    _generation++;
    _running = false;
    image = null;
    stage = RegionStage.failed;
    message = '사진을 표시할 수 없습니다. 다시 시도하거나 사진을 선택해 주세요.';
    notifyListeners();
  }

  void editManually() {
    if (image == null || busy) {
      return;
    }
    stage = RegionStage.reviewing;
    message = null;
    source = 'manual';
    notifyListeners();
  }

  void add(Offset point) {
    if (stage != RegionStage.reviewing ||
        pending.length >= 200 ||
        point.dx < 0 ||
        point.dx > 1 ||
        point.dy < 0 ||
        point.dy > 1) {
      return;
    }
    pending.add(point);
    notifyListeners();
  }

  void undo() {
    if (!busy && pending.isNotEmpty) {
      pending.removeLast();
      notifyListeners();
    }
  }

  void finishPolygon() {
    if (stage != RegionStage.reviewing) {
      return;
    }
    if (!validPolygon(pending) || polygons.length >= 30) {
      message = '서로 교차하지 않는 꼭짓점 3개 이상으로 음식 테두리를 지정해 주세요.';
    } else {
      polygons.add(List.of(pending));
      pending.clear();
      source = 'manual';
      message = null;
    }
    notifyListeners();
  }

  void remove(int index) {
    if (!busy) {
      polygons.removeAt(index);
      source = 'manual';
      notifyListeners();
    }
  }

  Map<String, dynamic> finish() {
    if (stage != RegionStage.reviewing ||
        polygons.isEmpty ||
        pending.isNotEmpty ||
        image == null) {
      throw StateError('영역을 완성한 뒤 적용해 주세요.');
    }
    final result = <String, dynamic>{
      'schemaVersion': 1,
      'imageFingerprint': image!['imageFingerprint'],
      'imageWidth': image!['imageWidth'],
      'imageHeight': image!['imageHeight'],
      'coordinateSpace': image!['coordinateSpace'],
      if (image!['frameFingerprint'] != null)
        'frameFingerprint': image!['frameFingerprint'],
      'foodPolygons': polygons
          .map((p) => p.map((v) => [v.dx, v.dy]).toList())
          .toList(),
      'source': source,
      'reviewed': true,
      'volumeValidated': false,
    };
    stage = RegionStage.complete;
    notifyListeners();
    return result;
  }

  @override
  void dispose() {
    _closed = true;
    _generation++;
    super.dispose();
  }
}
