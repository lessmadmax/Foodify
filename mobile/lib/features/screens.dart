import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import '../core/api.dart';
import '../core/pending_upload.dart';
import '../core/ar_capabilities.dart';

String date(DateTime d) =>
    '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
String stateLabel(dynamic s) => switch (s) {
  'QUEUED' => '접수',
  'RUNNING' => '분석 중',
  'COMPLETE' => '완료',
  _ => '분석 미완료 · 확인 필요',
};
void notify(BuildContext c, Object e) =>
    ScaffoldMessenger.of(c).showSnackBar(SnackBar(content: Text(errorText(e))));
Widget body(List<Widget> children) => Center(
  child: ConstrainedBox(
    constraints: const BoxConstraints(maxWidth: 680),
    child: ListView(padding: const EdgeInsets.all(20), children: children),
  ),
);
Widget gap() => const SizedBox(height: 16);

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});
  @override
  ConsumerState<LoginScreen> createState() => _LoginState();
}

class _LoginState extends ConsumerState<LoginScreen> {
  final email = TextEditingController(), password = TextEditingController();
  bool signup = false, busy = false;
  @override
  void initState() {
    super.initState();
    Future.microtask(() async {
      final api = ref.read(apiProvider);
      if (await api.storage.read(key: 'access') != null) {
        try {
          await api.request('GET', '/me');
          if (mounted) context.go('/home');
        } catch (_) {}
      }
    });
  }

  @override
  void dispose() {
    email.dispose();
    password.dispose();
    super.dispose();
  }

  Future<void> submit() async {
    setState(() => busy = true);
    try {
      await ref
          .read(apiProvider)
          .authenticate(email.text.trim(), password.text, signup: signup);
      if (mounted) context.go(signup ? '/goals' : '/home');
    } catch (e) {
      if (mounted) notify(context, e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Foodify')),
    body: body([
      const Icon(Icons.restaurant_menu, size: 64),
      gap(),
      Text(
        '한 끼를 기록하고\n내 식단을 이해하세요',
        style: Theme.of(context).textTheme.headlineMedium,
      ),
      gap(),
      TextField(
        controller: email,
        keyboardType: TextInputType.emailAddress,
        decoration: const InputDecoration(labelText: '이메일'),
      ),
      gap(),
      TextField(
        controller: password,
        obscureText: true,
        decoration: const InputDecoration(labelText: '비밀번호 · 10자 이상'),
      ),
      gap(),
      FilledButton(
        onPressed: busy ? null : submit,
        child: Text(
          busy
              ? '처리 중…'
              : signup
              ? '회원 가입'
              : '로그인',
        ),
      ),
      TextButton(
        onPressed: busy ? null : () => setState(() => signup = !signup),
        child: Text(signup ? '로그인으로 돌아가기' : '처음이라면 회원 가입'),
      ),
    ]),
  );
}

class GoalsScreen extends ConsumerStatefulWidget {
  const GoalsScreen({super.key});
  @override
  ConsumerState<GoalsScreen> createState() => _GoalsState();
}

class _GoalsState extends ConsumerState<GoalsScreen> {
  final fields = {
    for (final k in ['purpose', 'kcal', 'carbs', 'protein', 'fat'])
      k: TextEditingController(),
  };
  bool busy = false;
  @override
  void initState() {
    super.initState();
    Future.microtask(() async {
      try {
        final me = await ref.read(apiProvider).request('GET', '/me');
        if (mounted) {
          setState(() {
            for (final k in fields.keys) {
              fields[k]!.text = me['goals'][k]?.toString() ?? '';
            }
          });
        }
      } catch (e) {
        if (mounted) notify(context, e);
      }
    });
  }

  @override
  void dispose() {
    for (final c in fields.values) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> save() async {
    setState(() => busy = true);
    try {
      final data = <String, dynamic>{'purpose': fields['purpose']!.text};
      for (final k in ['kcal', 'carbs', 'protein', 'fat']) {
        final text = fields[k]!.text.trim();
        final n = double.tryParse(text);
        if (text.isNotEmpty && (n == null || !n.isFinite || n <= 0)) {
          throw StateError('양수인 목표 수치를 입력해 주세요.');
        }
        data[k] = n;
      }
      await ref.read(apiProvider).request('PUT', '/me/goals', data: data);
      if (mounted) context.go('/home');
    } catch (e) {
      if (mounted) notify(context, e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('내 영양 목표')),
    body: body([
      const Text('직접 정한 일일 목표를 입력하세요. 비워둔 항목은 기록 중심으로 안내합니다.'),
      gap(),
      for (final e in {
        'purpose': '관리 목적',
        'kcal': '열량 (kcal)',
        'carbs': '탄수화물 (g)',
        'protein': '단백질 (g)',
        'fat': '지방 (g)',
      }.entries) ...[
        TextField(
          controller: fields[e.key],
          keyboardType: e.key == 'purpose'
              ? TextInputType.text
              : const TextInputType.numberWithOptions(decimal: true),
          decoration: InputDecoration(labelText: e.value),
        ),
        gap(),
      ],
      FilledButton(onPressed: busy ? null : save, child: const Text('목표 저장')),
    ]),
  );
}

class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});
  @override
  ConsumerState<HomeScreen> createState() => _HomeState();
}

class _HomeState extends ConsumerState<HomeScreen> {
  List<dynamic> meals = [];
  List<PendingUpload> pending = [];
  Map<String, dynamic>? summary;
  dynamic feedback;
  String? error;
  bool busy = false;
  int days = 1;
  @override
  void initState() {
    super.initState();
    Future.microtask(load);
  }

  Future<void> load() async {
    try {
      final api = ref.read(apiProvider), today = DateTime.now();
      final list = await api.request('GET', '/meals');
      final totals = await api.request(
        'GET',
        '/nutrition/summary?from=${date(today.subtract(Duration(days: days - 1)))}&to=${date(today)}',
      );
      final uploads = await PendingUpload.load(
        await api.storage.read(key: 'member') ?? '',
      );
      if (mounted) {
        setState(() {
          meals = list;
          summary = Map<String, dynamic>.from(totals);
          pending = uploads;
          error = null;
          feedback = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = errorText(e));
    }
  }

  Future<void> run(Future<void> Function() action) async {
    setState(() => busy = true);
    try {
      await action();
    } catch (e) {
      if (mounted) notify(context, e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> deleteAccount() async {
    final yes = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('계정과 기록 삭제'),
        content: const Text('식단·사진·목표·로그인 정보가 삭제됩니다.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c, false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(c, true),
            child: const Text('삭제'),
          ),
        ],
      ),
    );
    if (yes == true) {
      await run(() async {
        final api = ref.read(apiProvider);
        await api.request('DELETE', '/me');
        for (final p in pending) {
          await p.remove();
        }
        await api.storage.deleteAll();
        if (mounted) context.go('/');
      });
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('Foodify · 식단 기록'),
      actions: [
        IconButton(
          onPressed: busy
              ? null
              : () => context.push('/goals').then((_) => load()),
          icon: const Icon(Icons.tune),
          tooltip: '목표',
        ),
        PopupMenuButton<String>(
          onSelected: (v) {
            if (v == 'delete') {
              deleteAccount();
            } else {
              run(() async {
                await ref.read(apiProvider).logout();
                if (context.mounted) context.go('/');
              });
            }
          },
          itemBuilder: (_) => const [
            PopupMenuItem(value: 'logout', child: Text('로그아웃')),
            PopupMenuItem(value: 'delete', child: Text('계정 삭제')),
          ],
        ),
      ],
    ),
    floatingActionButton: FloatingActionButton.extended(
      onPressed: () => context.push('/capture').then((_) => load()),
      icon: const Icon(Icons.camera_alt),
      label: const Text('한 끼 촬영'),
    ),
    body: RefreshIndicator(
      onRefresh: load,
      child: body([
        SegmentedButton<int>(
          segments: const [
            ButtonSegment(value: 1, label: Text('오늘')),
            ButtonSegment(value: 7, label: Text('최근 7일')),
          ],
          selected: {days},
          onSelectionChanged: (s) {
            setState(() => days = s.first);
            load();
          },
        ),
        gap(),
        if (error != null) Text(error!),
        if (summary != null)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(18),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '${summary!['totals']['kcal']} kcal',
                    style: Theme.of(context).textTheme.headlineMedium,
                  ),
                  Text(
                    '탄수화물 ${summary!['totals']['carbs']}g · 단백질 ${summary!['totals']['protein']}g · 지방 ${summary!['totals']['fat']}g',
                  ),
                  Text(
                    '완료 ${summary!['completedMeals']}건 · 미완료 ${summary!['incompleteMeals']}건',
                  ),
                  const Text('기록된 완료 식단의 합계입니다.'),
                ],
              ),
            ),
          ),
        gap(),
        FilledButton.tonal(
          onPressed: busy
              ? null
              : () => run(() async {
                  final now = DateTime.now();
                  final result = await ref
                      .read(apiProvider)
                      .request(
                        'POST',
                        '/feedback',
                        data: {
                          'from': date(now.subtract(Duration(days: days - 1))),
                          'to': date(now),
                        },
                      );
                  if (mounted) setState(() => feedback = result['content']);
                }),
          child: const Text('내 식단 피드백 요청'),
        ),
        if (feedback != null)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                '${feedback['summary']}\n\n${(feedback['suggestions'] as List).join('\n')}',
              ),
            ),
          ),
        for (final p in pending)
          ListTile(
            title: const Text('전송 대기 중인 촬영'),
            trailing: IconButton(
              icon: const Icon(Icons.refresh),
              onPressed: busy
                  ? null
                  : () => run(() async {
                      await ref
                          .read(apiProvider)
                          .upload(p.paths, p.key, p.eatenAt);
                      await p.remove();
                      await load();
                    }),
            ),
          ),
        gap(),
        Text('최근 식단', style: Theme.of(context).textTheme.titleLarge),
        if (meals.isEmpty)
          const Padding(
            padding: EdgeInsets.all(24),
            child: Text('첫 식사를 촬영해 기록해 보세요.'),
          ),
        for (final m in meals)
          Card(
            child: ListTile(
              leading: const Icon(Icons.restaurant),
              title: Text(
                (m['items'] as List).isEmpty
                    ? '분석할 식단'
                    : (m['items'] as List).map((i) => i['name']).join(', '),
              ),
              subtitle: Text(
                '${date(DateTime.fromMillisecondsSinceEpoch(m['eaten_at']))} · ${stateLabel(m['status'])}',
              ),
              onTap: () => context.push('/meal/${m['id']}').then((_) => load()),
            ),
          ),
        const SizedBox(height: 90),
      ]),
    ),
  );
}

class CaptureScreen extends ConsumerStatefulWidget {
  const CaptureScreen({super.key});
  @override
  ConsumerState<CaptureScreen> createState() => _CaptureState();
}

class _CaptureState extends ConsumerState<CaptureScreen> {
  final paths = <String>[];
  bool consent = false, busy = false;
  PendingUpload? saved;
  String? arInfo;
  Future<void> capture() async {
    try {
      final image = await ImagePicker().pickImage(
        source: ImageSource.camera,
        maxWidth: 1600,
        imageQuality: 85,
        requestFullMetadata: false,
      );
      if (image != null && mounted) setState(() => paths.add(image.path));
    } catch (e) {
      if (mounted) notify(context, e);
    }
  }

  Future<void> send() async {
    setState(() => busy = true);
    try {
      final api = ref.read(apiProvider);
      saved ??= await PendingUpload.save(
        (await api.storage.read(key: 'member'))!,
        paths,
      );
      final result = await api.upload(saved!.paths, saved!.key, saved!.eatenAt);
      await saved!.remove();
      if (mounted) context.go('/meal/${result['id']}');
    } catch (e) {
      if (mounted) notify(context, e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('한 끼 촬영')),
    body: body([
      Text(
        paths.isEmpty ? '1. 한 끼 전체를 위에서 촬영하세요' : '2. 다른 각도에서 높이가 보이도록 촬영하세요',
        style: Theme.of(context).textTheme.titleLarge,
      ),
      gap(),
      const Text(
        '최대 3장 · 밝은 곳에서 촬영하세요. 현재 사진 기반 분석이며 AR 자동 부피 측정은 실기기 검증 후 연결됩니다.',
      ),
      gap(),
      Wrap(
        spacing: 8,
        children: [
          for (final p in paths)
            Image.file(File(p), width: 130, height: 130, fit: BoxFit.cover),
        ],
      ),
      gap(),
      TextButton(
        onPressed: busy
            ? null
            : () async {
                final result = await ArCapabilities.check();
                if (mounted)
                  setState(
                    () => arInfo =
                        '${result['device'] ?? ''}\nARCore: ${result['arCore']} · 깊이: ${result['depth'] ?? '확인 필요'}\n${result['reason'] ?? ''}',
                  );
              },
        child: const Text('이 기기의 AR 지원 확인'),
      ),
      if (arInfo != null) Text(arInfo!),
      OutlinedButton.icon(
        onPressed: paths.length >= 3 || busy || saved != null ? null : capture,
        icon: const Icon(Icons.camera_alt),
        label: const Text('사진 촬영'),
      ),
      CheckboxListTile(
        value: consent,
        onChanged: busy ? null : (v) => setState(() => consent = v ?? false),
        title: const Text('사진을 OpenAI API에 전달하여 분석하는 데 동의합니다.'),
        subtitle: const Text(
          '사진은 식단 삭제 시까지 서버에 보관합니다. 분석 결과는 추정치이며 확인·수정할 수 있습니다.',
        ),
      ),
      FilledButton(
        onPressed: paths.isEmpty || !consent || busy ? null : send,
        child: Text(busy ? '저장 중…' : '자동 저장하고 분석'),
      ),
      if (saved != null) const Text('전송 실패 시 홈에서 다시 전송할 수 있습니다.'),
    ]),
  );
}

class MealScreen extends ConsumerStatefulWidget {
  final String id;
  const MealScreen({super.key, required this.id});
  @override
  ConsumerState<MealScreen> createState() => _MealState();
}

class _MealState extends ConsumerState<MealScreen> with WidgetsBindingObserver {
  Map<String, dynamic>? meal;
  List<Map<String, dynamic>> items = [];
  Timer? timer;
  String? error;
  bool busy = false, dirty = false, loading = false;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    Future.microtask(load);
    timer = Timer.periodic(const Duration(seconds: 3), (_) {
      if (!dirty && ['QUEUED', 'RUNNING'].contains(meal?['status'])) load();
    });
  }

  @override
  void dispose() {
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && !dirty) load();
  }

  Future<void> load() async {
    if (loading) return;
    loading = true;
    try {
      final result = await ref
          .read(apiProvider)
          .request('GET', '/meals/${widget.id}');
      if (mounted && !dirty) {
        setState(() {
          meal = Map<String, dynamic>.from(result);
          items = (result['items'] as List)
              .map((i) => Map<String, dynamic>.from(i))
              .toList();
          error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = errorText(e));
    } finally {
      loading = false;
    }
  }

  Future<void> search(int index) async {
    final controller = TextEditingController(text: items[index]['name']);
    final query = await showDialog<String>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('영양 DB 검색'),
        content: TextField(controller: controller),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(c, controller.text),
            child: const Text('검색'),
          ),
        ],
      ),
    );
    if (query == null || !mounted) return;
    try {
      final results = await ref
          .read(apiProvider)
          .request('GET', '/foods?query=${Uri.encodeQueryComponent(query)}');
      if (!mounted) return;
      final selected = await showDialog<Map<String, dynamic>>(
        context: context,
        builder: (c) => SimpleDialog(
          title: const Text('계산 기준 음식 선택'),
          children: [
            if ((results as List).isEmpty)
              const Padding(
                padding: EdgeInsets.all(16),
                child: Text('일치 자료가 없습니다. 공식 영양 자료 적재 상태를 확인해 주세요.'),
              ),
            for (final f in results)
              SimpleDialogOption(
                onPressed: () => Navigator.pop(c, Map<String, dynamic>.from(f)),
                child: Text(
                  '${f['name']} · ${f['basis_grams']}g 기준\n${f['source']}',
                ),
              ),
          ],
        ),
      );
      if (selected != null && mounted) {
        setState(() {
          items[index]['foodId'] = selected['id'];
          items[index]['name'] = selected['name'];
          dirty = true;
        });
      }
    } catch (e) {
      if (mounted) notify(context, e);
    }
  }

  Future<void> action(Future<void> Function() f) async {
    setState(() => busy = true);
    try {
      await f();
    } catch (e) {
      if (mounted) notify(context, e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('음식 분석 결과'),
      actions: [
        IconButton(
          onPressed: () => context.go('/home'),
          icon: const Icon(Icons.home),
        ),
        IconButton(
          onPressed: dirty ? null : load,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: body([
      if (error != null) Text(error!),
      if (meal == null) const LinearProgressIndicator(),
      if (meal != null) ...[
        Text(
          stateLabel(meal!['status']),
          style: Theme.of(context).textTheme.titleLarge,
        ),
        gap(),
        const Text('식전 제공량 기준 추정치입니다. 실제 먹은 중량과 영양 DB 항목을 확인한 후 저장하세요.'),
        for (final a in meal!['analyses'] as List)
          if (a['error_code'] != null) Text('분석 안내: ${a['error_code']}'),
        gap(),
        for (var i = 0; i < items.length; i++)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  TextFormField(
                    key: ValueKey(
                      'name-${meal!['version']}-$i-${items[i]['foodId']}',
                    ),
                    initialValue: items[i]['name'],
                    decoration: const InputDecoration(labelText: '음식명'),
                    onChanged: (v) {
                      items[i]['name'] = v;
                      dirty = true;
                    },
                  ),
                  gap(),
                  TextFormField(
                    key: ValueKey('grams-${meal!['version']}-$i'),
                    initialValue: items[i]['grams']?.toString() ?? '',
                    keyboardType: const TextInputType.numberWithOptions(
                      decimal: true,
                    ),
                    decoration: const InputDecoration(labelText: '먹은 중량 (g)'),
                    onChanged: (v) {
                      items[i]['grams'] = double.tryParse(v);
                      dirty = true;
                    },
                  ),
                  TextButton(
                    onPressed: busy ? null : () => search(i),
                    child: Text(
                      (items[i]['foodId'] ?? '').toString().isEmpty
                          ? '영양 DB 항목 선택'
                          : 'DB 항목: ${items[i]['foodId']}',
                    ),
                  ),
                  if (items[i]['nutrition'] != null)
                    Text(
                      '${items[i]['nutrition']['kcal']} kcal · ${items[i]['source']?['source'] ?? ''}',
                    ),
                  CheckboxListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('음식·중량·DB 항목을 확인했습니다'),
                    value: items[i]['confirmed'] == true,
                    onChanged: busy
                        ? null
                        : (v) => setState(() {
                            items[i]['confirmed'] = v;
                            dirty = true;
                          }),
                  ),
                  TextButton(
                    onPressed: busy
                        ? null
                        : () => setState(() {
                            items.removeAt(i);
                            dirty = true;
                          }),
                    child: const Text('이 음식 삭제'),
                  ),
                ],
              ),
            ),
          ),
        OutlinedButton(
          onPressed: busy
              ? null
              : () => setState(() {
                  items.add({
                    'name': '',
                    'grams': null,
                    'foodId': '',
                    'confirmed': false,
                  });
                  dirty = true;
                }),
          child: const Text('음식 추가'),
        ),
        FilledButton(
          onPressed: busy
              ? null
              : () => action(() async {
                  await ref
                      .read(apiProvider)
                      .request(
                        'PATCH',
                        '/meals/${widget.id}',
                        data: {'version': meal!['version'], 'items': items},
                      );
                  dirty = false;
                  await load();
                }),
          child: const Text('수정 저장 · 영양 재계산'),
        ),
        TextButton(
          onPressed:
              busy || dirty || ['QUEUED', 'RUNNING'].contains(meal!['status'])
              ? null
              : () => action(() async {
                  await ref
                      .read(apiProvider)
                      .request('POST', '/meals/${widget.id}/analyses');
                  await load();
                }),
          child: const Text('AI 분석 다시 요청'),
        ),
        TextButton(
          onPressed: busy
              ? null
              : () => action(() async {
                  final yes = await showDialog<bool>(
                    context: context,
                    builder: (c) => AlertDialog(
                      title: const Text('이 식단과 사진을 삭제할까요?'),
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
                    await ref
                        .read(apiProvider)
                        .request('DELETE', '/meals/${widget.id}');
                    if (context.mounted) context.go('/home');
                  }
                }),
          child: const Text('식단 삭제'),
        ),
      ],
    ]),
  );
}
