import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../core/ar_capabilities.dart';
import 'food_region_screen.dart';

/// Local-only AR research workflow; scale labels remain separate from geometry.
class ArResearchScreen extends StatefulWidget {
  const ArResearchScreen({super.key});
  @override
  State<ArResearchScreen> createState() => _ArResearchState();
}

class _ArResearchState extends State<ArResearchScreen> {
  static const channel = MethodChannel('foodify/ar_research');
  final food = TextEditingController(),
      group = TextEditingController(),
      scale = TextEditingController();
  List<Map<String, dynamic>> sessions = [], frames = [], records = [];
  String? session, framePath;
  String split = 'CALIBRATION', message = '';
  bool busy = false;
  Map<String, dynamic>? preview, foodRegion, baseRegion, result, summary;
  @override
  void initState() {
    super.initState();
    run(refresh);
  }

  @override
  void dispose() {
    food.dispose();
    group.dispose();
    scale.dispose();
    super.dispose();
  }

  Future<dynamic> call(String name, [Map<String, dynamic>? args]) =>
      channel.invokeMethod(name, args);
  List<Map<String, dynamic>> maps(dynamic value) =>
      (value as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();
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

  Future<void> refresh() async {
    final s = maps(await call('sessions'));
    final r = maps(await call('records'));
    if (!mounted) return;
    setState(() {
      sessions = s;
      records = r;
    });
    if (session == null && s.isNotEmpty) {
      await selectSession(s.first['id'] as String);
    }
  }

  Future<void> selectSession(String id) async {
    final list = maps(await call('frames', {'session': id}));
    if (!mounted) return;
    setState(() {
      session = id;
      frames = list;
      framePath = null;
      preview = null;
      foodRegion = null;
      baseRegion = null;
      result = null;
    });
    if (list.isNotEmpty) await selectFrame(list.first['path'] as String);
  }

  Future<void> selectFrame(String path) async {
    final data = Map<String, dynamic>.from(
      await call('prepare', {'framePath': path}) as Map,
    );
    if (!mounted) return;
    setState(() {
      framePath = data['framePath'] as String? ?? path;
      preview = data;
      foodRegion = null;
      baseRegion = null;
      result = null;
    });
  }

  Future<void> capture() async {
    Object? failure;
    Map<String, dynamic>? captured;
    try {
      captured = await ArCapabilities.captureMeal(research: true);
    } catch (e) {
      failure = e;
    }
    // Raw sessions are retained even when the automatic frame quality gate fails.
    session = null;
    await refresh();
    if (captured?['path'] is String) {
      final directory = File(captured!['path'] as String).parent;
      await selectSession(
        directory.parent.uri.pathSegments.where((s) => s.isNotEmpty).last,
      );
      await selectFrame(directory.path);
    }
    if (failure != null && mounted) {
      setState(() => message = '촬영 자료를 확인해 주세요. $failure');
    }
  }

  Future<void> editRegion(bool baseline) async {
    final data = await Navigator.push<Map<String, dynamic>>(
      context,
      MaterialPageRoute(
        builder: (_) => FoodRegionScreen(
          path: preview!['imagePath'] as String,
          initial: baseline ? baseRegion : foodRegion,
          title: baseline ? '접시 기준면 지정' : '음식 영역 지정',
          guidance: baseline
              ? '음식을 포함한 접시 영역을 지정하세요. 음식 부분은 자동으로 제외하고 남은 표면에서 기준면을 찾습니다. 평평한 접시 안쪽을 중심으로 지정하고 테두리·곡면·식탁을 제외하면 계산에 도움이 됩니다.'
              : '한 종류의 음식만 지정하세요. 접시·그림자·배경을 제외하고 보이는 음식 경계를 따라 점을 찍으세요.',
        ),
      ),
    );
    if (data != null && mounted) {
      setState(() {
        if (baseline) {
          baseRegion = data['clear'] == true ? null : data;
        } else {
          foodRegion = data['clear'] == true ? null : data;
        }
        result = null;
      });
    }
  }

  Map<String, dynamic> input() => {
    'framePath': framePath,
    'foodName': food.text.trim(),
    'portionGroup': group.text.trim(),
    'split': split,
    'scaleGrams': double.tryParse(scale.text),
    'foodRegion': foodRegion,
    'baseRegion': baseRegion,
  };
  Future<void> estimate() async {
    final data = Map<String, dynamic>.from(
      await call('estimate', input()) as Map,
    );
    if (mounted) setState(() => result = data);
  }

  Future<void> save() async {
    if (food.text.trim().isEmpty ||
        group.text.trim().isEmpty ||
        (double.tryParse(scale.text) ?? 0) <= 0) {
      throw StateError('음식명, 제공량 그룹, 전자저울의 음식 중량(g)을 입력해 주세요.');
    }
    final data = Map<String, dynamic>.from(await call('save', input()) as Map);
    await refresh();
    if (mounted) {
      setState(() {
        result = Map<String, dynamic>.from(data['result'] as Map);
        message = '휴대폰에 실측 기록을 저장했습니다.';
      });
    }
  }

  Future<void> openRecord(Map<String, dynamic> record) async {
    final path = record['framePath'] as String;
    await selectSession(
      File(path).parent.uri.pathSegments.where((s) => s.isNotEmpty).last,
    );
    await selectFrame(path);
    if (!mounted) return;
    setState(() {
      food.text = record['foodName'] as String;
      group.text = record['portionGroup'] as String;
      scale.text = record['scaleGrams'].toString();
      split = record['split'] as String;
      foodRegion = record['foodRegion'] == null
          ? null
          : Map<String, dynamic>.from(record['foodRegion'] as Map);
      baseRegion = record['baseRegion'] == null
          ? null
          : Map<String, dynamic>.from(record['baseRegion'] as Map);
      result = Map<String, dynamic>.from(record['result'] as Map);
    });
  }

  String number(dynamic value, String unit) =>
      value is num ? '${value.toStringAsFixed(2)} $unit' : '확인 필요';
  String signedPercent(dynamic value) => value is num
      ? '${value >= 0 ? '+' : ''}${value.toStringAsFixed(0)}%'
      : '확인 필요';
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('AR 중량 측정'),
      actions: [
        IconButton(
          onPressed: busy ? null : () => run(refresh),
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text(
          '전자저울 실측 자료 수집',
          style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold),
        ),
        const Text(
          'S23 내부에서 촬영·영역 지정·부피 계산·실측값 저장을 진행합니다. 평평한 접시의 무게를 영점 처리한 뒤 음식만의 중량을 입력하세요. 실험 결과는 식단 기록과 별도로 보관됩니다.',
        ),
        if (busy) const LinearProgressIndicator(),
        if (message.isNotEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 12),
            child: Text(message, key: const Key('ar-research-message')),
          ),
        FilledButton.icon(
          onPressed: busy ? null : () => run(capture),
          icon: const Icon(Icons.camera_alt),
          label: const Text('AR 촬영'),
        ),
        if (sessions.isNotEmpty)
          DropdownButtonFormField<String>(
            value: session,
            isExpanded: true,
            decoration: const InputDecoration(labelText: '저장된 촬영'),
            items: sessions
                .map(
                  (s) => DropdownMenuItem(
                    value: s['id'] as String,
                    child: Text(
                      '${DateTime.fromMillisecondsSinceEpoch(s['savedAt'] as int).toLocal()} · ${(s['id'] as String).substring(0, 8)}',
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                )
                .toList(),
            onChanged: busy
                ? null
                : (v) {
                    if (v != null) run(() => selectSession(v));
                  },
          ),
        if (frames.isNotEmpty)
          DropdownButtonFormField<String>(
            value: framePath,
            isExpanded: true,
            decoration: const InputDecoration(labelText: '프레임'),
            items: frames
                .map(
                  (f) => DropdownMenuItem(
                    value: f['path'] as String,
                    child: Text(
                      '${f['id']} · 깊이 지연 ${number(f['depthAgeMs'], 'ms')}',
                    ),
                  ),
                )
                .toList(),
            onChanged: busy
                ? null
                : (v) {
                    if (v != null) run(() => selectFrame(v));
                  },
          ),
        if (preview != null) ...[
          const SizedBox(height: 12),
          Image.file(
            File(preview!['imagePath'] as String),
            height: 230,
            fit: BoxFit.contain,
          ),
          Wrap(
            spacing: 8,
            children: [
              OutlinedButton(
                onPressed: busy ? null : () => run(() => editRegion(false)),
                child: Text(foodRegion == null ? '음식 영역 지정' : '음식 영역 수정 ✓'),
              ),
              OutlinedButton(
                onPressed: busy ? null : () => run(() => editRegion(true)),
                child: Text(baseRegion == null ? '접시 기준면 지정' : '접시 기준면 수정 ✓'),
              ),
            ],
          ),
        ],
        TextField(
          controller: food,
          enabled: !busy,
          decoration: const InputDecoration(
            labelText: '음식명 · 같은 음식은 표기를 통일하세요',
          ),
        ),
        TextField(
          controller: group,
          enabled: !busy,
          decoration: const InputDecoration(labelText: '제공량 그룹 · 예: 밥-100g-A'),
        ),
        const Text('같은 음식량의 반복 촬영은 동일한 그룹으로 저장하세요. 음식량을 바꾼 뒤 새 그룹을 사용하세요.'),
        DropdownButtonFormField<String>(
          value: split,
          decoration: const InputDecoration(labelText: '자료 용도'),
          items: const [
            DropdownMenuItem(value: 'CALIBRATION', child: Text('보정용 · 환산식 생성')),
            DropdownMenuItem(value: 'EVALUATION', child: Text('평가용 · 새로운 제공량')),
          ],
          onChanged: busy
              ? null
              : (v) => setState(() {
                  split = v!;
                }),
        ),
        TextField(
          controller: scale,
          enabled: !busy,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
          decoration: const InputDecoration(labelText: '전자저울 음식 중량 (g)'),
        ),
        FilledButton(
          onPressed: busy || preview == null ? null : () => run(estimate),
          child: const Text('부피 계산'),
        ),
        FilledButton(
          onPressed: busy || preview == null ? null : () => run(save),
          child: const Text('실측 기록 저장'),
        ),
        const Text(
          '영역 지정 전이나 깊이 품질이 부족한 촬영도 실측값을 저장할 수 있습니다. 같은 프레임·음식·그룹·용도로 저장하면 해당 기록을 갱신합니다.',
        ),
        if (result != null)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    result!['volumeMl'] == null ? '계산 확인 필요' : '실험용 부피 계산 완료',
                  ),
                  Text('부피: ${number(result!['volumeMl'], 'mL')}'),
                  if (result!['registeredFrameCount'] != null)
                    Text(
                      '촬영 ${result!['registeredFrameCount']}프레임의 깊이를 정렬해 결합했습니다.',
                    ),
                  if (result!['validFoodPixelFraction'] is num)
                    Text(
                      '음식 영역 유효 깊이: ${((result!['validFoodPixelFraction'] as num) * 100).toStringAsFixed(1)}%',
                    ),
                  if (result!['baseSelectionPolicy'] ==
                      'selected_plate_minus_food')
                    const Text('접시 영역에서 음식 부분을 자동 제외했습니다.'),
                  Text('환산 중량: ${number(result!['estimatedGrams'], 'g')}'),
                  if (result!['calibrationMessage'] != null)
                    Text(result!['calibrationMessage'] as String),
                  if (result!['observedVolumeMl'] != null &&
                      result!['volumeMl'] == null)
                    Text(
                      '품질 검토 전 관측 부피: ${number(result!['observedVolumeMl'], 'mL')}',
                    ),
                  Text(
                    '실측 대비 오차: ${number(result!['absoluteErrorGrams'], 'g')}',
                  ),
                  if (result!['tableDensityGPerMl'] is num) ...[
                    Text(
                      '밀도표 추정 중량: ${number(result!['tableEstimatedGrams'], 'g')}'
                      ' (${result!['tableDensitySourceFood']} ${result!['tableDensityGPerMl']} g/mL)',
                    ),
                    if (result!['tableEstimatedGrams'] == null &&
                        result!['tableEstimatedObservedGrams'] != null)
                      Text(
                        '품질 검토 전 관측 부피 기준: ${number(result!['tableEstimatedObservedGrams'], 'g')}',
                      ),
                    if (result!['tableSignedErrorPercent'] is num)
                      Text(
                        '밀도표 실측 대비: ${signedPercent(result!['tableSignedErrorPercent'])}',
                      )
                    else if (result!['tableObservedSignedErrorPercent'] is num)
                      Text(
                        '밀도표 실측 대비(관측 부피 기준): ${signedPercent(result!['tableObservedSignedErrorPercent'])}',
                      ),
                  ] else if (result!['tableDensityMessage'] != null)
                    Text('밀도표: ${result!['tableDensityMessage']}'),
                  for (final reason in (result!['holdReasons'] as List? ?? []))
                    Text('• $reason'),
                  const Text(
                    '환산 중량은 음식별 보정 자료를 확보한 뒤 표시됩니다. 실제 정확도 검증이 필요한 실험값입니다.',
                  ),
                ],
              ),
            ),
          ),
        const Divider(),
        const Text('음식별 보정·평가', style: TextStyle(fontSize: 20)),
        const Text(
          '품질을 통과한 서로 다른 보정 제공량 3그룹 이상으로 부피→중량 환산식을 생성합니다. 평가는 보정에 쓰지 않은 제공량을 사용합니다.',
        ),
        OutlinedButton(
          onPressed: busy
              ? null
              : () => run(() async {
                  await call('calibrate', {'foodName': food.text.trim()});
                  final s = Map<String, dynamic>.from(
                    await call('summary', {'foodName': food.text.trim()})
                        as Map,
                  );
                  if (mounted) {
                    setState(() {
                      summary = s;
                      message = '음식별 환산식을 생성했습니다.';
                    });
                  }
                }),
          child: const Text('현재 음식의 환산식 생성'),
        ),
        OutlinedButton(
          onPressed: busy
              ? null
              : () => run(() async {
                  final s = Map<String, dynamic>.from(
                    await call('summary', {'foodName': food.text.trim()})
                        as Map,
                  );
                  if (mounted) {
                    setState(() => summary = s);
                  }
                }),
          child: const Text('현재 음식의 평가 결과'),
        ),
        if (summary != null)
          Text(
            '${summary!['message'] ?? ''}\n평가 그룹: ${summary!['evaluationGroups'] ?? 0} · 유효 그룹: ${summary!['validGroups'] ?? 0}\n평균 절대오차: ${number(summary!['maeGrams'], 'g')}\n중앙 상대오차: ${number(summary!['medianRelativeErrorPercent'], '%')}',
          ),
        const Divider(),
        Text(
          '저장된 실측 기록 ${records.length}건',
          style: const TextStyle(fontSize: 20),
        ),
        OutlinedButton.icon(
          onPressed: busy || records.isEmpty
              ? null
              : () => run(() async {
                  final answer = await call('export', {
                    'ids': records.map((r) => r['id']).toList(),
                  });
                  if (mounted) {
                    setState(
                      () => message = answer['saved'] == true
                          ? 'ZIP 파일을 저장했습니다. 사진·깊이·영역·실측값이 포함됩니다.'
                          : '내보내기를 취소했습니다.',
                    );
                  }
                }),
          icon: const Icon(Icons.save_alt),
          label: const Text('모든 실측 자료 ZIP 내보내기'),
        ),
        const Text('자료는 앱 내부에 보관됩니다. 앱 삭제 전에 ZIP으로 백업하세요.'),
        for (final r in records)
          ListTile(
            contentPadding: EdgeInsets.zero,
            title: Text('${r['foodName']} · ${r['scaleGrams']} g'),
            subtitle: Text(
              '${r['portionGroup']} · ${r['split'] == 'CALIBRATION' ? '보정용' : '평가용'}',
            ),
            onTap: busy ? null : () => run(() => openRecord(r)),
            trailing: IconButton(
              icon: const Icon(Icons.delete_outline),
              onPressed: busy
                  ? null
                  : () async {
                      final yes = await showDialog<bool>(
                        context: context,
                        builder: (c) => AlertDialog(
                          title: const Text('실측 기록 삭제'),
                          content: const Text(
                            '실측값과 지정 영역을 삭제합니다. 원본 촬영 자료는 보관됩니다.',
                          ),
                          actions: [
                            TextButton(
                              onPressed: () => Navigator.pop(c, false),
                              child: const Text('취소'),
                            ),
                            TextButton(
                              onPressed: () => Navigator.pop(c, true),
                              child: const Text('삭제'),
                            ),
                          ],
                        ),
                      );
                      if (yes == true) {
                        run(() async {
                          await call('delete', {'id': r['id']});
                          await refresh();
                        });
                      }
                    },
            ),
          ),
      ],
    ),
  );
}
