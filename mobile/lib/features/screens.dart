import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import '../core/api.dart';
import '../core/pending_upload.dart';
import '../core/ar_capabilities.dart';
import '../core/meal_review.dart';

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

String analysisHint(dynamic code) => switch (code) {
  'USER_REVIEW_REQUIRED' => '참고 추정치가 준비됐습니다. 음식·중량·DB 항목 확인 후 저장하면 합계에 반영됩니다.',
  'OPENAI_NOT_CONFIGURED' => '서버 API 키 설정이 필요합니다.',
  'OPENAI_HTTP_401' => '서버의 API 인증 설정을 확인해 주세요.',
  'OPENAI_HTTP_429' => 'AI 요청 한도 또는 결제 잔액을 확인해 주세요.',
  'AI_BUDGET_REACHED' => '이번 달 분석 요청 한도에 도달했습니다.',
  _ => '분석을 완료하지 못했습니다. 다시 분석을 요청해 주세요. ($code)',
};

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
    for (final k in ['age', 'heightCm', 'weightKg']) k: TextEditingController(),
  };
  String? sex, activity;
  bool busy = false, loading = true, generalAdult = false;
  Map<String, dynamic>? estimate;
  @override
  void initState() {
    super.initState();
    Future.microtask(() async {
      try {
        final me = await ref.read(apiProvider).request('GET', '/me');
        if (mounted) {
          setState(() {
            final goals = Map<String, dynamic>.from(me['goals']);
            final profile = goals['profile'];
            if (profile is Map) {
              for (final k in fields.keys) {
                fields[k]!.text = profile[k]?.toString() ?? '';
              }
              sex = profile['sex'];
              activity = profile['activityLevel'];
              generalAdult = profile['generalAdultConfirmed'] == true;
              estimate = goals;
            }
          });
        }
      } catch (e) {
        if (mounted) notify(context, e);
      } finally {
        if (mounted) setState(() => loading = false);
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

  Map<String, dynamic> input() {
    final age = int.tryParse(fields['age']!.text.trim());
    final height = double.tryParse(fields['heightCm']!.text.trim());
    final weight = double.tryParse(fields['weightKg']!.text.trim());
    if (age == null ||
        height == null ||
        weight == null ||
        !height.isFinite ||
        !weight.isFinite ||
        sex == null ||
        activity == null) {
      throw StateError('나이·성별·키·몸무게·활동량을 확인해 주세요.');
    }
    if (age < 19 || age > 78 || !generalAdult) {
      throw StateError(
        '현재 자동 계산은 만 19~78세 일반 성인 기준입니다. 적용 범위를 확인하거나 기록부터 시작해 주세요.',
      );
    }
    return {
      'age': age,
      'sex': sex,
      'heightCm': height,
      'weightKg': weight,
      'activityLevel': activity,
      'generalAdultConfirmed': generalAdult,
    };
  }

  Future<void> calculate({bool save = false}) async {
    setState(() => busy = true);
    try {
      final result = await ref
          .read(apiProvider)
          .request(
            save ? 'PUT' : 'POST',
            save ? '/me/goals' : '/me/goals/preview',
            data: input(),
          );
      if (mounted) {
        setState(() => estimate = Map<String, dynamic>.from(result));
        if (save) context.go('/home');
      }
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
      const Text('신체정보와 평소 활동량으로 하루 체중 유지 열량과 3대 영양소 목표를 계산합니다.'),
      const Text('정보는 목표 저장 시 계정에 저장됩니다. 신체정보 원문은 피드백 AI에 전달하지 않습니다.'),
      if (loading) const LinearProgressIndicator(),
      gap(),
      for (final e in {
        'age': '만 나이 (세)',
        'heightCm': '키 (cm)',
        'weightKg': '몸무게 (kg)',
      }.entries) ...[
        TextField(
          enabled: !busy && !loading,
          controller: fields[e.key],
          keyboardType: TextInputType.numberWithOptions(
            decimal: e.key != 'age',
          ),
          decoration: InputDecoration(labelText: e.value),
          onChanged: (_) => setState(() => estimate = null),
        ),
        gap(),
      ],
      DropdownButtonFormField<String>(
        value: sex,
        decoration: const InputDecoration(labelText: '계산식에 사용할 성별'),
        items: const [
          DropdownMenuItem(value: 'MALE', child: Text('남성')),
          DropdownMenuItem(value: 'FEMALE', child: Text('여성')),
        ],
        onChanged: busy || loading
            ? null
            : (v) => setState(() {
                sex = v;
                estimate = null;
              }),
      ),
      gap(),
      DropdownButtonFormField<String>(
        value: activity,
        isExpanded: true,
        decoration: const InputDecoration(labelText: '평소 활동량'),
        items: const [
          DropdownMenuItem(
            value: 'SEDENTARY',
            child: Text('낮음 · 앉아서 생활, 운동 거의 없음'),
          ),
          DropdownMenuItem(value: 'LIGHT', child: Text('가벼움 · 가벼운 운동 주 1~3일')),
          DropdownMenuItem(
            value: 'MODERATE',
            child: Text('보통 · 중간 강도 운동 주 3~5일'),
          ),
          DropdownMenuItem(
            value: 'ACTIVE',
            child: Text('높음 · 높은 활동량, 운동 주 6~7일'),
          ),
        ],
        onChanged: busy || loading
            ? null
            : (v) => setState(() {
                activity = v;
                estimate = null;
              }),
      ),
      gap(),
      const Text(
        '자동 계산 적용 범위: 만 19~78세 일반 성인. 성장기·임신·수유·질환별 영양 관리는 별도 기준이 필요합니다. 해당하는 경우 기록 기능부터 이용해 주세요.',
      ),
      CheckboxListTile(
        contentPadding: EdgeInsets.zero,
        title: const Text('일반 성인 기준이 적용되며 별도 영양 처방이 필요한 상태가 아님을 확인했습니다'),
        value: generalAdult,
        onChanged: busy || loading
            ? null
            : (v) => setState(() {
                generalAdult = v ?? false;
                estimate = null;
              }),
      ),
      FilledButton(
        onPressed: busy || loading ? null : () => calculate(),
        child: Text(busy ? '계산 중…' : '목표 자동 계산'),
      ),
      if (estimate != null) ...[
        gap(),
        Text(
          '하루 참고 목표: ${estimate!['kcal']} kcal',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        Text(
          '탄수화물 ${estimate!['carbs']} g · 단백질 ${estimate!['protein']} g · 지방 ${estimate!['fat']} g',
        ),
        const Text(
          'Mifflin–St Jeor 안정 시 대사량 × 활동 계수. 탄·단·지 열량 배분 50:20:30은 앱 기본값이며, 개인별 처방값이 아닙니다.',
        ),
        gap(),
        FilledButton(
          onPressed: busy || loading ? null : () => calculate(save: true),
          child: const Text('이 목표 저장'),
        ),
      ],
      TextButton(
        onPressed: busy ? null : () => context.go('/home'),
        child: const Text('목표 설정은 나중에 · 기록부터 시작'),
      ),
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
                if (mounted) {
                  setState(
                    () => arInfo =
                        '${result['device'] ?? ''}\nARCore: ${result['arCore']} · 깊이: ${result['depth'] ?? '확인 필요'}\n${result['reason'] ?? ''}',
                  );
                }
              },
        child: const Text('이 기기의 AR 지원 확인'),
      ),
      OutlinedButton.icon(
        onPressed: busy
            ? null
            : () async {
                try {
                  await ArCapabilities.openDiagnostics();
                } catch (e) {
                  if (context.mounted) notify(context, e);
                }
              },
        icon: const Icon(Icons.view_in_ar),
        label: const Text('AR 깊이 진단 · 실험'),
      ),
      const Text(
        'AR 진단은 깊이·신뢰도를 확인하는 별도 실험입니다. 저장 데이터는 기기에 보관되며 식단 분석에 자동 반영되지 않습니다.',
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
  String? saveNotice;
  bool saving = false, conflict = false;
  int loadGeneration = 0, formGeneration = 0;
  bool busy = false, dirty = false, loading = false;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    Future.microtask(load);
    timer = Timer.periodic(const Duration(seconds: 3), (_) {
      if (!busy && !dirty && ['QUEUED', 'RUNNING'].contains(meal?['status'])) {
        load();
      }
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
    if (loading || busy) return;
    loading = true;
    final generation = ++loadGeneration;
    try {
      final result = await ref
          .read(apiProvider)
          .request('GET', '/meals/${widget.id}');
      if (mounted && !dirty && !busy && generation == loadGeneration) {
        setState(() => applyMeal(result));
      }
    } catch (e) {
      if (mounted && generation == loadGeneration) {
        setState(() => error = errorText(e));
      }
    } finally {
      loading = false;
    }
  }

  void applyMeal(dynamic result) {
    meal = Map<String, dynamic>.from(result);
    items = (result['items'] as List)
        .map((i) => Map<String, dynamic>.from(i))
        .toList();
    formGeneration++;
    error = null;
  }

  void changed([int? index]) {
    dirty = true;
    saveNotice = null;
    if (index != null) {
      items[index]['nutrition'] = null;
      items[index]['confirmed'] = false;
    }
  }

  Future<void> saveMeal() async {
    if (busy || meal == null) return;
    FocusScope.of(context).unfocus();
    for (var i = 0; i < items.length; i++) {
      final name = (items[i]['name'] ?? '').toString().trim();
      final grams = items[i]['grams'];
      if (name.isEmpty ||
          name.length > 200 ||
          (grams != null &&
              (grams is! num ||
                  !grams.isFinite ||
                  grams <= 0 ||
                  grams > 10000))) {
        final message = name.isEmpty || name.length > 200
            ? '${i + 1}번째 음식명을 1~200자로 입력해 주세요.'
            : '${i + 1}번째 중량을 0보다 크고 10,000g 이하로 입력해 주세요.';
        setState(() => error = message);
        notify(context, message);
        return;
      }
    }
    setState(() {
      busy = true;
      saving = true;
      error = null;
      saveNotice = null;
      conflict = false;
    });
    ++loadGeneration; // Invalidate reads that started before this write.
    final payload = {
      'version': meal!['version'],
      'items': [
        for (final item in items)
          {
            'name': item['name'],
            'grams': item['grams'],
            'foodId': item['foodId'],
            'confirmed': item['confirmed'] == true,
          },
      ],
    };
    try {
      final result = await ref
          .read(apiProvider)
          .request('PATCH', '/meals/${widget.id}', data: payload);
      if (!mounted) return;
      setState(() {
        applyMeal(result);
        dirty = false;
        saveNotice = mealSaveSummary(meal!);
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(saveNotice!)));
    } catch (e) {
      if (!mounted) return;
      setState(() {
        error = '저장 실패 · ${errorText(e)}';
        conflict = errorText(e).contains('기록이 변경되었습니다');
      });
      notify(context, error!);
    } finally {
      if (mounted) {
        setState(() {
          busy = false;
          saving = false;
        });
      }
    }
  }

  Future<void> reloadConflict() async {
    final yes = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('최신 기록을 불러올까요?'),
        content: const Text('현재 화면의 저장되지 않은 수정 내용은 최신 서버 기록으로 바뀝니다.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(c, false),
            child: const Text('계속 수정'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(c, true),
            child: const Text('최신 기록 불러오기'),
          ),
        ],
      ),
    );
    if (yes == true && mounted) {
      setState(() {
        dirty = false;
        conflict = false;
        saveNotice = null;
      });
      await load();
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
          items[index]['source'] = selected;
          changed(index);
          formGeneration++;
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
      if (mounted) {
        setState(() => busy = false);
        await load();
      }
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
          onPressed: dirty || busy ? null : load,
          icon: const Icon(Icons.refresh),
        ),
      ],
    ),
    body: body([
      if (error != null) Text(error!),
      if (conflict)
        TextButton(
          onPressed: busy ? null : reloadConflict,
          child: const Text('최신 기록 불러오기'),
        ),
      if (saveNotice != null)
        Text(saveNotice!, key: const ValueKey('save-notice')),
      if (meal == null) const LinearProgressIndicator(),
      if (meal != null) ...[
        Text(
          stateLabel(meal!['status']),
          style: Theme.of(context).textTheme.titleLarge,
        ),
        gap(),
        const Text('식전 제공량 기준 추정치입니다. 실제 먹은 중량과 영양 DB 항목을 확인한 후 저장하세요.'),
        if (meal!['status'] == 'QUEUED' && meal!['analysisEnabled'] == false)
          const Text('서버의 자동 분석이 일시 중지되어 있습니다. 사진은 저장됐으며 분석이 활성화되면 처리됩니다.'),
        if (meal!['status'] == 'QUEUED' && meal!['analysisEnabled'] != false)
          const Text('분석 순서를 기다리고 있습니다. 완료되면 결과가 자동 표시됩니다.'),
        for (final a
            in (meal!['status'] == 'COMPLETE' || saveNotice != null
                    ? []
                    : meal!['analyses'])
                as List)
          if (a['error_code'] != null) Text(analysisHint(a['error_code'])),
        gap(),
        for (var i = 0; i < items.length; i++)
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  TextFormField(
                    enabled: !busy,
                    key: ValueKey(
                      'name-$formGeneration-$i-${items[i]['foodId']}',
                    ),
                    initialValue: items[i]['name'],
                    decoration: const InputDecoration(labelText: '음식명'),
                    onChanged: (v) => setState(() {
                      items[i]['name'] = v;
                      changed(i);
                    }),
                  ),
                  gap(),
                  TextFormField(
                    enabled: !busy,
                    key: ValueKey('grams-$formGeneration-$i'),
                    initialValue: items[i]['grams']?.toString() ?? '',
                    keyboardType: const TextInputType.numberWithOptions(
                      decimal: true,
                    ),
                    decoration: const InputDecoration(labelText: '먹은 중량 (g)'),
                    onChanged: (v) => setState(() {
                      items[i]['grams'] = double.tryParse(v);
                      changed(i);
                    }),
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
                      '참고 추정: ${items[i]['nutrition']['kcal']} kcal\n탄수화물 ${items[i]['nutrition']['carbs']} g · 단백질 ${items[i]['nutrition']['protein']} g · 지방 ${items[i]['nutrition']['fat']} g\n자료원: ${items[i]['source']?['source'] ?? ''}',
                    ),
                  for (final issue in mealItemIssues(items[i]))
                    Text('• $issue'),
                  if (dirty && items[i]['nutrition'] == null)
                    const Text('입력값이 변경되었습니다. 저장하면 영양값을 다시 계산합니다.'),
                  if ((items[i]['uncertainty'] ?? '').toString().isNotEmpty)
                    Text('추정 참고: ${items[i]['uncertainty']}'),
                  CheckboxListTile(
                    contentPadding: EdgeInsets.zero,
                    title: const Text('음식·중량·DB 항목을 확인했습니다'),
                    value: items[i]['confirmed'] == true,
                    onChanged: busy
                        ? null
                        : (v) => setState(() {
                            items[i]['confirmed'] = v;
                            changed();
                          }),
                  ),
                  TextButton(
                    onPressed: busy
                        ? null
                        : () => setState(() {
                            items.removeAt(i);
                            changed();
                            formGeneration++;
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
                  changed();
                }),
          child: const Text('음식 추가'),
        ),
        FilledButton(
          onPressed: busy ? null : saveMeal,
          child: Text(saving ? '저장 및 영양 재계산 중…' : '수정 저장 · 영양 재계산'),
        ),
        if (saving) const LinearProgressIndicator(),
        if (saveNotice != null) Text(saveNotice!),
        if (error != null) Text(error!),
        const Text('이 버튼은 입력한 중량과 DB 자료로 계산합니다. 사진 재분석은 아래 AI 분석 버튼을 이용해 주세요.'),
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
