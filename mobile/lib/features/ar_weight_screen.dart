import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../core/api.dart';
import '../core/ar_capabilities.dart';
import 'food_region_screen.dart';

/// Meal-flow AR weight (area × height, M12): capture → user taps the food and
/// MobileSAM cuts the outline on the device → user confirms → on-device plate
/// distance, top-view area and measured mean height (AI height only when the
/// device cannot measure it) → grams = area × height × category density.
/// The depth volume is kept only as a reference. Returns the AREA_HEIGHT
/// measurement; the server recomputes the weight on save.
class ArWeightScreen extends StatefulWidget {
  const ArWeightScreen({
    super.key,
    required this.foodName,
    this.foodId,
    this.api,
  });
  final String foodName;
  final String? foodId;

  /// Server API for the height/weight preview; null = no weight preview.
  final Api? api;
  @override
  State<ArWeightScreen> createState() => _ArWeightScreenState();
}

class _ArWeightScreenState extends State<ArWeightScreen> {
  static const channel = MethodChannel('foodify/ar_research');
  Map<String, dynamic>? preview, foodRegion, baseRegion, result;

  /// Area × height result: on-device scale + server height/weight preview.
  Map<String, dynamic>? scale, height;
  String heightError = '';
  String? framePath;
  String message = '';
  bool busy = false;

  Future<void> run(Future<void> Function() task) async {
    if (busy) return;
    setState(() {
      busy = true;
      message = '';
    });
    try {
      await task();
    } catch (e) {
      if (mounted) {
        setState(
          () => message = e is PlatformException
              ? e.message ?? e.code
              : e.toString(),
        );
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> capture() async {
    String? frame;
    try {
      final captured = await ArCapabilities.captureMeal(research: true);
      final path = captured?['path'];
      if (path is String) frame = File(path).parent.path;
    } catch (_) {
      // Export quality gate failed: fall back to the best frame of the newest session below.
    }
    frame ??= await bestFrameOfLatestSession();
    if (frame == null) {
      throw StateError('AR 촬영 자료를 확인하지 못했습니다. 밝은 곳에서 다시 촬영해 주세요.');
    }
    final data = Map<String, dynamic>.from(
      await channel.invokeMethod('prepare', {'framePath': frame}) as Map,
    );
    if (!mounted) return;
    setState(() {
      framePath = data['framePath'] as String;
      preview = data;
      foodRegion = null;
      baseRegion = null;
      result = null;
      scale = null;
      height = null;
      heightError = '';
      taps.clear();
      excludeMode = false;
      tapNote = '사진에서 음식을 눌러 주세요. 여러 조각이면 2~3곳을 누르면 전체가 잡힙니다.';
    });
    // Encode the photo for segmentation ahead of the first tap (about 1 s on the S23).
    channel
        .invokeMethod('prepareSegment', {'framePath': framePath})
        .catchError((Object e) {
          debugPrint('MobileSAM prepare failed: $e');
          return null;
        });
  }

  /// Tap points on the photo: [u, v, label], label 1 = food, 0 = not food.
  final List<List<num>> taps = [];
  bool excludeMode = false, segmenting = false;
  String tapNote = '';

  /// Each tap re-cuts the food with MobileSAM (all taps so far). The result is
  /// only a draft: volume waits until the user confirms it.
  Future<void> addTap(Offset position, Size size) async {
    if (busy || segmenting || preview == null) return;
    final u = (position.dx / size.width).clamp(0.0, 1.0);
    final v = (position.dy / size.height).clamp(0.0, 1.0);
    if (taps.length >= 20) {
      setState(() => tapNote = '점은 20개까지 찍을 수 있습니다. 점을 지우고 다시 눌러 주세요.');
      return;
    }
    setState(() => taps.add([u, v, excludeMode ? 0 : 1]));
    await segment();
  }

  Future<void> segment() async {
    if (!taps.any((t) => t[2] == 1)) {
      setState(() {
        foodRegion = null;
        result = null;
        scale = null;
        height = null;
        tapNote = '음식 부분을 한 곳 이상 눌러 주세요.';
      });
      return;
    }
    setState(() {
      segmenting = true;
      tapNote = '음식 경계를 따는 중…';
    });
    try {
      final cut = Map<String, dynamic>.from(
        await channel.invokeMethod('segmentPoints', {
              'framePath': framePath,
              'points': taps,
            })
            as Map,
      );
      if (!mounted) return;
      setState(() {
        foodRegion = {
          'schemaVersion': 1,
          'imageFingerprint': preview!['imageFingerprint'],
          'imageWidth': preview!['imageWidth'],
          'imageHeight': preview!['imageHeight'],
          'coordinateSpace': 'cpu_image_normalized',
          'frameFingerprint': preview!['frameFingerprint'],
          'foodPolygons': cut['foodPolygons'],
          'source': cut['source'],
          'reviewed': false,
          'volumeValidated': false,
        };
        result = null;
        scale = null;
        height = null;
        heightError = '';
        tapNote = tapResultNote(cut);
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        taps.removeLast();
        tapNote =
            '자르기 실패: ${e is PlatformException ? e.message ?? e.code : e}. 다른 곳을 누르거나 음식 영역을 직접 지정해 주세요.';
      });
    } finally {
      if (mounted) setState(() => segmenting = false);
    }
  }

  Future<void> undoTap() async {
    if (taps.isEmpty) return;
    setState(() => taps.removeLast());
    await segment();
  }

  void clearTaps() => setState(() {
    taps.clear();
    foodRegion = null;
    result = null;
    scale = null;
    height = null;
    tapNote = '사진에서 음식을 눌러 주세요. 여러 조각이면 2~3곳을 누르면 전체가 잡힙니다.';
  });

  /// Explains the cut after a tap.
  static String tapResultNote(Map<String, dynamic> cut) {
    final pieces = (cut['pieces'] as num? ?? 1).toInt();
    return '초록 선이 음식과 맞는지 확인해 주세요${pieces > 1 ? '(조각 $pieces개)' : ''}. '
        '빠진 조각이 있으면 그 조각을 더 누르고, 잘못 잡힌 곳은 "제외"를 켜고 누르세요.';
  }

  /// Volume is computed only for an outline the user confirmed or drew.
  bool get confirmed => foodRegion?['reviewed'] == true;

  Future<void> confirmRegion() async {
    setState(() {
      foodRegion = {...foodRegion!, 'reviewed': true};
      result = null;
    });
    await estimate();
  }

  Future<String?> bestFrameOfLatestSession() async {
    final sessions = (await channel.invokeMethod('sessions') as List)
        .map((e) => Map<String, dynamic>.from(e as Map))
        .toList();
    if (sessions.isEmpty) return null;
    final frames =
        (await channel.invokeMethod('frames', {'session': sessions.first['id']})
                as List)
            .map((e) => Map<String, dynamic>.from(e as Map))
            .toList();
    if (frames.isEmpty) return null;
    frames.sort(
      (a, b) => ((b['confidentFraction'] as num?) ?? 0).compareTo(
        (a['confidentFraction'] as num?) ?? 0,
      ),
    );
    return frames.first['path'] as String;
  }

  Future<void> editRegion(bool base) async {
    final data = await Navigator.push<Map<String, dynamic>>(
      context,
      MaterialPageRoute(
        builder: (_) => FoodRegionScreen(
          path: preview!['imagePath'] as String,
          initial: base ? baseRegion : foodRegion,
          title: base ? '접시 영역 지정' : '음식 영역 지정',
          guidance: base
              ? '음식을 포함한 평평한 접시 영역을 지정하세요. 음식 부분은 자동으로 제외됩니다.'
              : '${widget.foodName}의 테두리를 따라 점을 찍으세요. 접시 기준면은 음식 바로 바깥에서 자동으로 잡습니다.',
        ),
      ),
    );
    if (data != null && mounted) {
      setState(() {
        final value = data['clear'] == true ? null : data;
        if (base) {
          baseRegion = value;
        } else {
          foodRegion = value;
          taps.clear(); // a drawn outline replaces the tap cut
        }
        result = null;
      });
      // A drawn outline counts as confirmed (reviewed=true): recompute right away.
      if (confirmed) await estimate();
    }
  }

  Future<void> estimate() async {
    setState(() {
      result = null;
      scale = null;
      height = null;
      heightError = '';
    });
    final region = {
      'framePath': framePath,
      'foodName': widget.foodName,
      'foodRegion': foodRegion,
      'baseRegion': baseRegion,
    };
    // Depth volume: reference only (kept for later comparison with the scale).
    Map<String, dynamic>? depth;
    try {
      depth = Map<String, dynamic>.from(
        await channel.invokeMethod('estimate', region) as Map,
      );
    } catch (_) {
      depth = null;
    }
    if (mounted) setState(() => result = depth);
    final measured = Map<String, dynamic>.from(
      await channel.invokeMethod('scale', region) as Map,
    );
    if (!mounted) return;
    setState(() => scale = measured);
    final api = widget.api;
    if (api == null) {
      setState(() => heightError = '서버에 연결되지 않아 AI 높이를 받을 수 없습니다.');
      return;
    }
    // Height measured on the device (depth or feature points) is used first; no AI call then.
    final measuredHeight = measured['measuredHeightCm'];
    if (measuredHeight is num && measuredHeight > 0) {
      try {
        final answer = Map<String, dynamic>.from(
          await api.request(
                'POST',
                '/ar/preview',
                data: {
                  'foodName': widget.foodName,
                  'foodId': widget.foodId ?? '',
                  'areaCm2': measured['areaCm2'],
                  'heightCm': measuredHeight,
                  'distanceMm': measured['distanceMm'],
                },
              )
              as Map,
        );
        answer['heightMethod'] = measured['measuredHeightSource'];
        answer['heightSampleCount'] = measured['measuredHeightSamples'];
        if (mounted) setState(() => height = answer);
      } catch (e) {
        debugPrint('AR preview request failed: $e');
        if (mounted) {
          setState(() => heightError = '예상 중량 계산 실패: ${errorText(e)}');
        }
      }
      return;
    }
    try {
      final bytes = await File(preview!['imagePath'] as String).readAsBytes();
      final answer = Map<String, dynamic>.from(
        await api.request(
              'POST',
              '/ar/height',
              data: {
                'foodName': widget.foodName,
                'foodId': widget.foodId ?? '',
                'imageBase64': base64Encode(bytes),
                for (final key in const [
                  'distanceMm',
                  'mmPerPixel',
                  'photoWidthCm',
                  'photoHeightCm',
                  'areaCm2',
                ])
                  key: measured[key],
              },
            )
            as Map,
      );
      if (mounted) setState(() => height = answer);
    } catch (e) {
      if (mounted) {
        debugPrint('AR height request failed: $e');
        setState(() => heightError = 'AI 높이 추정 실패: ${errorText(e)}');
      }
    }
  }

  /// Explains a distance taken from ARCore feature points instead of plate depth (white bowls/desks).
  static String? distanceSourceNote(Object? source) => switch (source) {
    'feature_points_plate' => '접시 면 깊이가 부족해 주변 특징점으로 거리를 쟀습니다.',
    'feature_points_food' => '음식 위 특징점으로 거리를 쟀습니다. 음식 윗면 기준이라 면적이 조금 작게 나올 수 있습니다.',
    _ => null,
  };

  /// Where the mean height came from: device measurement (depth / feature points) or the AI answers.
  String heightLabel(Map<String, dynamic> h) => switch (h['heightMethod']) {
    'DEPTH' =>
      '깊이로 잰 평균 높이 ${fixed(h['heightCm'], 2)} cm (깊이 ${h['heightSampleCount'] ?? '-'}점)',
    'FEATURE_POINTS' =>
      '특징점으로 잰 평균 높이 ${fixed(h['heightCm'], 2)} cm (특징점 ${h['heightSampleCount'] ?? '-'}개)',
    _ =>
      'AI 평균 높이 ${fixed(h['heightCm'], 2)} cm '
          '(3회: ${[for (final s in (h['heightSamples'] as List? ?? [])) fixed(s, 2)].join(', ')})',
  };

  String fixed(Object? value, int digits) =>
      value is num ? value.toStringAsFixed(digits) : '-';

  /// Quality-passed volume first; a held observed volume is allowed but flagged.
  double? get volume {
    final passed = result?['volumeMl'];
    if (passed is num && passed > 0) return passed.toDouble();
    final observed = result?['observedVolumeMl'];
    return observed is num && observed > 0 ? observed.toDouble() : null;
  }

  bool get held => result?['volumeMl'] is! num;

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('AR 중량 측정')),
    body: ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(widget.foodName, style: Theme.of(context).textTheme.titleLarge),
        const Text(
          '평평한 접시에 담긴 음식 1가지를 바로 위에서 촬영하세요. 촬영 후 사진에서 음식을 눌러 윤곽을 잡습니다. 위에서 본 음식 면적 × 평균 높이(기기 측정, 없으면 AI) × 음식 분류별 밀도로 중량을 계산합니다(실측 검증 전 실험값).',
        ),
        const Text(
          '땅콩처럼 흩어진 음식은 조각 2~3곳을 누르면 조각마다 윤곽이 잡혀 빈틈이 빠집니다.',
          style: TextStyle(fontSize: 12),
        ),
        if (busy) const LinearProgressIndicator(),
        if (message.isNotEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(message, style: const TextStyle(color: Colors.red)),
          ),
        const SizedBox(height: 8),
        FilledButton.icon(
          onPressed: busy ? null : () => run(capture),
          icon: const Icon(Icons.camera_alt),
          label: Text(preview == null ? '① AR 촬영' : '① 다시 촬영'),
        ),
        if (preview != null) ...[
          const SizedBox(height: 8),
          SizedBox(
            height: 300,
            child: Center(
              child: AspectRatio(
                aspectRatio:
                    ((preview!['imageWidth'] as num?) ?? 4) /
                    ((preview!['imageHeight'] as num?) ?? 3),
                child: LayoutBuilder(
                  builder: (context, box) => GestureDetector(
                    key: const ValueKey('ar-photo-tap'),
                    onTapUp: (d) => addTap(d.localPosition, box.biggest),
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        Image.file(
                          File(preview!['imagePath'] as String),
                          fit: BoxFit.fill,
                        ),
                        CustomPaint(
                          painter: _RegionPainter(
                            foodRegion?['foodPolygons'] as List? ?? const [],
                            taps: List.of(taps),
                          ),
                        ),
                        if (segmenting)
                          const Center(child: CircularProgressIndicator()),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ),
          Wrap(
            spacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              SegmentedButton<bool>(
                segments: const [
                  ButtonSegment(
                    value: false,
                    label: Text('음식'),
                    icon: Icon(Icons.add_circle_outline),
                  ),
                  ButtonSegment(
                    value: true,
                    label: Text('제외'),
                    icon: Icon(Icons.remove_circle_outline),
                  ),
                ],
                selected: {excludeMode},
                onSelectionChanged: (v) =>
                    setState(() => excludeMode = v.first),
              ),
              TextButton(
                onPressed: busy || segmenting || taps.isEmpty ? null : undoTap,
                child: const Text('마지막 점 취소'),
              ),
              TextButton(
                onPressed: busy || segmenting || taps.isEmpty
                    ? null
                    : clearTaps,
                child: const Text('점 모두 지우기'),
              ),
            ],
          ),
          if (tapNote.isNotEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Text(tapNote),
            ),
          if (foodRegion != null && !confirmed)
            FilledButton.icon(
              onPressed: busy ? null : () => run(confirmRegion),
              icon: const Icon(Icons.check),
              label: const Text('윤곽이 맞아요 (중량 계산)'),
            ),
          if (confirmed)
            const Text('✓ 음식 윤곽 확인됨', style: TextStyle(color: Colors.green)),
          Wrap(
            spacing: 8,
            children: [
              OutlinedButton(
                onPressed: busy ? null : () => run(() => editRegion(false)),
                child: Text(
                  foodRegion == null ? '② 음식 영역 직접 지정' : '② 음식 영역 직접 수정',
                ),
              ),
              OutlinedButton(
                onPressed: busy ? null : () => run(() => editRegion(true)),
                child: Text(
                  baseRegion == null ? '접시 영역 직접 지정(선택)' : '접시 영역 직접 지정 ✓',
                ),
              ),
            ],
          ),
          FilledButton(
            onPressed: busy || !confirmed ? null : () => run(estimate),
            child: const Text('③ 중량 다시 계산'),
          ),
        ],
        if (preview != null && baseRegion == null)
          const Text(
            '접시 기준면: 음식 테두리 바로 바깥 띠에서 자동 계산. 부피가 이상하면 접시 영역을 직접 지정해 보세요.',
            style: TextStyle(fontSize: 12),
          ),
        if (scale != null) ...[
          const SizedBox(height: 8),
          Text(
            '음식 면적 ${fixed(scale!['areaCm2'], 1)} cm² · 접시까지 ${fixed((scale!['distanceMm'] as num?)?.toDouble() == null ? null : (scale!['distanceMm'] as num) / 10, 1)} cm',
          ),
          if (distanceSourceNote(scale!['distanceSource']) case final note?)
            Text(note, style: Theme.of(context).textTheme.bodySmall),
        ],
        if (busy && confirmed && scale != null && height == null)
          Text(
            scale?['measuredHeightCm'] is num
                ? '측정한 높이로 예상 중량을 계산하는 중…'
                : '높이를 측정하지 못해 AI가 음식 평균 높이를 추정하는 중…',
          ),
        if (heightError.isNotEmpty)
          Text(heightError, style: const TextStyle(color: Colors.red)),
        if (height != null) ...[
          Text(
            '예상 중량: ${fixed(height!['grams'], 1)} g',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          Text(
            '면적 ${fixed(height!['areaCm2'], 1)} cm² × ${heightLabel(height!)} '
            '= ${fixed(height!['volumeMl'], 1)} mL × ${height!['densityGroupLabel']} 밀도 ${height!['densityGPerMl']} g/mL'
            '${height!['majorCategory'] == null ? '' : ' (DB 분류: ${height!['majorCategory']})'}',
            style: const TextStyle(fontSize: 12),
          ),
          FilledButton(
            onPressed: busy
                ? null
                : () => Navigator.pop(context, {
                    'method': 'AREA_HEIGHT',
                    'areaCm2': height!['areaCm2'],
                    'heightCm': height!['heightCm'],
                    'heightSamples': height!['heightSamples'] ?? const [],
                    'heightMethod': height!['heightMethod'] ?? 'AI',
                    if (height!['heightSampleCount'] is int)
                      'heightSampleCount': height!['heightSampleCount'],
                    'distanceMm': height!['distanceMm'],
                    if (volume != null) 'depthVolumeMl': volume,
                    'qualityHold': false,
                    'holdReasons': const <String>[],
                  }),
            child: const Text('이 중량 적용'),
          ),
        ],
        if (result != null)
          Text(
            volume == null
                ? '참고: 깊이 부피를 계산하지 못했습니다.'
                : '참고: 깊이로 잰 부피 ${volume!.toStringAsFixed(1)} mL${held ? ' (품질 확인 필요)' : ''} — 중량 계산에는 쓰지 않습니다.',
            style: const TextStyle(fontSize: 12, color: Colors.grey),
          ),
      ],
    ),
  );
}

/// Draws the food outline (image-normalized polygons) over the AR photo.
class _RegionPainter extends CustomPainter {
  _RegionPainter(this.polygons, {this.taps = const []});
  final List polygons;

  /// Tap points [u, v, label]: red = food, blue = not food.
  final List<List<num>> taps;
  @override
  void paint(Canvas canvas, Size size) {
    for (final t in taps) {
      final center = Offset(t[0] * size.width, t[1] * size.height);
      canvas
        ..drawCircle(center, 7, Paint()..color = Colors.white)
        ..drawCircle(
          center,
          5,
          Paint()..color = t[2] == 1 ? Colors.redAccent : Colors.blueAccent,
        );
    }
    final stroke = Paint()
      ..color = Colors.greenAccent
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.5;
    final fill = Paint()..color = Colors.greenAccent.withValues(alpha: 0.18);
    for (final polygon in polygons) {
      final points = [
        for (final p in polygon as List)
          Offset(
            ((p as List)[0] as num).toDouble() * size.width,
            (p[1] as num).toDouble() * size.height,
          ),
      ];
      if (points.length < 3) continue;
      final path = Path()..addPolygon(points, true);
      canvas
        ..drawPath(path, fill)
        ..drawPath(path, stroke);
    }
  }

  @override
  bool shouldRepaint(_RegionPainter old) =>
      old.polygons != polygons || old.taps.length != taps.length || old.taps != taps;
}
