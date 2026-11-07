import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../core/api.dart';
import '../core/ar_capabilities.dart';
import 'food_region_screen.dart';

/// Meal-flow AR weight: capture → food/plate region → on-device volume.
/// Returns {volumeMl, qualityHold, holdReasons}; the server applies the
/// category density, so no weight is computed here.
class ArWeightScreen extends StatefulWidget {
  const ArWeightScreen({super.key, required this.foodName, this.api});
  final String foodName;

  /// Server API for the AI food box; null = manual food region only.
  final Api? api;
  @override
  State<ArWeightScreen> createState() => _ArWeightScreenState();
}

class _ArWeightScreenState extends State<ArWeightScreen> {
  static const channel = MethodChannel('foodify/ar_research');
  Map<String, dynamic>? preview, foodRegion, baseRegion, result;
  String? framePath;
  String message = '';
  String autoNote = '';
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
      autoNote = '';
    });
    await autoRegion();
  }

  /// AI box (server) → depth refinement (device) → food region. The automatic
  /// outline is only a draft: volume waits until the user confirms or edits it.
  Future<void> autoRegion() async {
    final api = widget.api;
    if (api == null || preview == null) return;
    setState(() => autoNote = 'AI가 음식 위치를 찾는 중…');
    try {
      final bytes = await File(preview!['imagePath'] as String).readAsBytes();
      final located = Map<String, dynamic>.from(
        await api.request(
              'POST',
              '/ar/food-box',
              data: {
                'foodName': widget.foodName,
                'imageBase64': base64Encode(bytes),
              },
            )
            as Map,
      );
      if (located['found'] != true) {
        if (mounted) {
          setState(() => autoNote = 'AI가 음식을 찾지 못했습니다. 음식 영역을 직접 지정해 주세요.');
        }
        return;
      }
      final b = Map<String, dynamic>.from(located['box'] as Map);
      final refined = Map<String, dynamic>.from(
        await channel.invokeMethod('foodFromBox', {
              'framePath': framePath,
              'box': [b['x1'], b['y1'], b['x2'], b['y2']],
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
          'foodPolygons': refined['foodPolygons'],
          'source': refined['source'],
          'reviewed': false,
          'volumeValidated': false,
        };
        autoNote = refined['source'] == 'ai_box_depth'
            ? '자동 음식 영역(초안): AI 위치 + 깊이로 윤곽을 잡았습니다. 초록 선이 음식을 잘 감싸는지 확인해 주세요.'
            : '자동 음식 영역(초안): 깊이로 윤곽을 잡지 못해 AI 사각형을 표시했습니다(${refined['reason'] ?? ''}). 음식과 다르면 수정해 주세요.';
      });
    } catch (e) {
      if (mounted) {
        setState(
          () => autoNote =
              '자동 음식 영역 실패: ${e is PlatformException ? e.message ?? e.code : e}. 음식 영역을 직접 지정해 주세요.',
        );
      }
    }
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
        (await channel.invokeMethod('frames', {
                  'session': sessions.first['id'],
                })
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
        }
        result = null;
      });
      // A drawn outline counts as confirmed (reviewed=true): recompute right away.
      if (confirmed) await estimate();
    }
  }

  Future<void> estimate() async {
    final data = Map<String, dynamic>.from(
      await channel.invokeMethod('estimate', {
            'framePath': framePath,
            'foodName': widget.foodName,
            'foodRegion': foodRegion,
            'baseRegion': baseRegion,
          })
          as Map,
    );
    if (mounted) setState(() => result = data);
  }

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
        Text(
          widget.foodName,
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const Text(
          '평평한 무광 접시에 담긴 음식 1가지를 바로 위에서 촬영하세요. 부피 × 음식 분류별 밀도로 중량을 계산합니다(실측 검증 전 실험값).',
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
            height: 230,
            child: Center(
              child: AspectRatio(
                aspectRatio:
                    ((preview!['imageWidth'] as num?) ?? 4) /
                    ((preview!['imageHeight'] as num?) ?? 3),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    Image.file(
                      File(preview!['imagePath'] as String),
                      fit: BoxFit.fill,
                    ),
                    if (foodRegion != null)
                      CustomPaint(
                        painter: _RegionPainter(
                          foodRegion!['foodPolygons'] as List? ?? const [],
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
          if (autoNote.isNotEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Text(autoNote),
            ),
          if (foodRegion != null && !confirmed)
            FilledButton.icon(
              onPressed: busy ? null : () => run(confirmRegion),
              icon: const Icon(Icons.check),
              label: const Text('윤곽이 맞아요 (부피 계산)'),
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
            child: const Text('③ 부피 다시 계산'),
          ),
        ],
        if (preview != null && baseRegion == null)
          const Text(
            '접시 기준면: 음식 테두리 바로 바깥 띠에서 자동 계산. 부피가 이상하면 접시 영역을 직접 지정해 보세요.',
            style: TextStyle(fontSize: 12),
          ),
        if (result != null) ...[
          const SizedBox(height: 8),
          Text(
            volume == null
                ? '부피를 계산하지 못했습니다. 다시 촬영하거나 영역을 조정해 주세요.'
                : '측정 부피: ${volume!.toStringAsFixed(1)} mL${held ? ' (품질 확인 필요)' : ''}',
            style: Theme.of(context).textTheme.titleMedium,
          ),
          for (final reason in (result!['holdReasons'] as List? ?? []))
            Text('• $reason'),
          if (volume != null)
            FilledButton(
              onPressed: busy
                  ? null
                  : () => Navigator.pop(context, {
                      'volumeMl': volume,
                      'qualityHold': held,
                      'holdReasons': [
                        for (final r in (result!['holdReasons'] as List? ?? []))
                          r.toString(),
                      ],
                    }),
              child: const Text('이 부피로 중량 적용'),
            ),
        ],
      ],
    ),
  );
}

/// Draws the food outline (image-normalized polygons) over the AR photo.
class _RegionPainter extends CustomPainter {
  _RegionPainter(this.polygons);
  final List polygons;
  @override
  void paint(Canvas canvas, Size size) {
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
  bool shouldRepaint(_RegionPainter old) => old.polygons != polygons;
}
