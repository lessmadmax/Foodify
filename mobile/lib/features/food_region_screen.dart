import 'dart:io';
import 'package:flutter/material.dart';
import '../core/food_regions.dart';

class FoodRegionScreen extends StatefulWidget {
  const FoodRegionScreen({
    super.key,
    required this.path,
    this.initial,
    this.service,
    this.title = '음식 영역 보정',
    this.guidance,
  });
  final String path;
  final String title;
  final String? guidance;
  final Map<String, dynamic>? initial;
  final RegionService? service;
  @override
  State<FoodRegionScreen> createState() => _FoodRegionScreenState();
}

class _FoodRegionScreenState extends State<FoodRegionScreen> {
  late final RegionController controller;
  @override
  void initState() {
    super.initState();
    controller = RegionController(
      widget.path,
      widget.service ?? RegionService(),
      initial: widget.initial,
    );
    controller.start();
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: controller,
    builder: (context, _) {
      final c = controller;
      final title = switch (c.stage) {
        RegionStage.preparing => '사진 준비 중',
        RegionStage.segmenting => '음식 경계 찾는 중',
        RegionStage.reviewing => '음식 경계 확인·보정',
        RegionStage.failed => '영역 분석 확인 필요',
        RegionStage.complete => '보정 적용 완료',
      };
      return Scaffold(
        appBar: AppBar(title: Text(widget.title)),
        body: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Text(title, style: Theme.of(context).textTheme.titleLarge),
            const Text('사진 준비 → 경계 추출 → 확인·보정 → 적용'),
            if (c.busy) const LinearProgressIndicator(),
            if (c.message != null)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Text(
                  widget.guidance ?? c.message!,
                  key: const Key('region-message'),
                ),
              ),
            if (c.stage == RegionStage.failed)
              Wrap(
                spacing: 8,
                children: [
                  FilledButton(onPressed: c.start, child: const Text('다시 시도')),
                  if (c.image != null)
                    OutlinedButton(
                      onPressed: c.editManually,
                      child: const Text('직접 영역 지정'),
                    ),
                  TextButton(
                    onPressed: () => Navigator.pop(context),
                    child: const Text('촬영 화면으로 돌아가기'),
                  ),
                ],
              ),
            if (c.image != null) ...[
              const SizedBox(height: 12),
              AspectRatio(
                aspectRatio:
                    (c.image!['imageWidth'] as int) /
                    (c.image!['imageHeight'] as int),
                child: LayoutBuilder(
                  builder: (context, size) => GestureDetector(
                    key: const Key('region-canvas'),
                    onTapDown: c.stage == RegionStage.reviewing
                        ? (event) => c.add(
                            Offset(
                              event.localPosition.dx / size.maxWidth,
                              event.localPosition.dy / size.maxHeight,
                            ),
                          )
                        : null,
                    child: Stack(
                      fit: StackFit.expand,
                      children: [
                        Image.file(
                          File(widget.path),
                          fit: BoxFit.fill,
                          errorBuilder: (_, error, stack) {
                            WidgetsBinding.instance.addPostFrameCallback((_) {
                              if (mounted) {
                                c.imageFailed();
                              }
                            });
                            return const Center(child: Text('사진을 표시할 수 없습니다.'));
                          },
                        ),
                        CustomPaint(
                          painter: _RegionPainter(c.polygons, c.pending),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
            if (c.stage == RegionStage.reviewing) ...[
              Text(
                widget.guidance ??
                    '음식 하나의 테두리를 순서대로 누르고 영역 완성을 선택하세요. 다른 음식은 별도 영역으로 지정합니다.',
              ),
              Wrap(
                spacing: 8,
                children: [
                  OutlinedButton(
                    onPressed: c.finishPolygon,
                    child: const Text('영역 완성'),
                  ),
                  TextButton(
                    onPressed: c.pending.isEmpty ? null : c.undo,
                    child: const Text('마지막 점 취소'),
                  ),
                ],
              ),
              for (var i = 0; i < c.polygons.length; i++)
                ListTile(
                  title: Text('지정 영역 ${i + 1}'),
                  trailing: IconButton(
                    tooltip: '영역 삭제',
                    onPressed: () => c.remove(i),
                    icon: const Icon(Icons.delete_outline),
                  ),
                ),
              Text(
                widget.guidance == null
                    ? '보정한 경계는 사진과 함께 저장됩니다. 실제 부피·중량 계산 연결은 검증 단계입니다.'
                    : '영역을 적용한 뒤 AR 중량 측정 화면에서 부피를 계산하고 실측값을 저장하세요.',
              ),
              if (widget.initial != null)
                TextButton(
                  onPressed: () =>
                      Navigator.pop(context, <String, dynamic>{'clear': true}),
                  child: const Text('저장된 보정 제거'),
                ),
              FilledButton(
                onPressed: c.polygons.isEmpty || c.pending.isNotEmpty
                    ? null
                    : () => Navigator.pop(context, c.finish()),
                child: const Text('보정 적용'),
              ),
            ],
          ],
        ),
      );
    },
  );
}

class _RegionPainter extends CustomPainter {
  _RegionPainter(this.polygons, this.pending);
  final List<List<Offset>> polygons;
  final List<Offset> pending;
  @override
  void paint(Canvas canvas, Size size) {
    for (final polygon in [...polygons, if (pending.isNotEmpty) pending]) {
      final path = Path();
      for (var i = 0; i < polygon.length; i++) {
        final p = Offset(
          polygon[i].dx * size.width,
          polygon[i].dy * size.height,
        );
        if (i == 0) {
          path.moveTo(p.dx, p.dy);
        } else {
          path.lineTo(p.dx, p.dy);
        }
        canvas.drawCircle(p, 4, Paint()..color = Colors.green.shade800);
      }
      if (!identical(polygon, pending)) {
        path.close();
        canvas.drawPath(
          path,
          Paint()..color = Colors.green.withValues(alpha: .25),
        );
      }
      canvas.drawPath(
        path,
        Paint()
          ..color = Colors.green.shade800
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _RegionPainter oldDelegate) => true;
}
