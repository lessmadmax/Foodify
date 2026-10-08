import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import '../core/api.dart';
import '../core/pending_upload.dart';
import '../core/ar_capabilities.dart';
import '../core/meal_review.dart';
import 'nutrition_table.dart';
import 'ai_consent.dart';
import 'chat_screen.dart';

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
  'AI_CONSENT_REQUIRED' =>
    'AI 전송 동의를 확인해 주세요. AI 분석 다시 요청을 누르면 안내를 확인할 수 있습니다.',
  'USER_REVIEW_REQUIRED' => '일부 음식의 중량 또는 영양정보 확인이 필요합니다. 아래에서 수정할 수 있습니다.',
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
        Text('하루 참고 목표', style: Theme.of(context).textTheme.titleLarge),
        NutritionTable(values: estimate!),
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
            if (v == 'settings') {
              Navigator.of(
                context,
              ).push(MaterialPageRoute(builder: (_) => const SettingsScreen()));
            } else if (v == 'delete') {
              deleteAccount();
            } else {
              run(() async {
                await ref.read(apiProvider).logout();
                if (context.mounted) context.go('/');
              });
            }
          },
          itemBuilder: (_) => const [
            PopupMenuItem(value: 'settings', child: Text('설정')),
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
                  Text('영양 합계', style: Theme.of(context).textTheme.titleLarge),
                  gap(),
                  NutritionTable(values: summary!['totals']),
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
              : () => Navigator.of(
                  context,
                ).push(MaterialPageRoute(builder: (_) => const ChatScreen())),
          child: const Text('식단 피드백 챗봇'),
        ),
        for (final p in pending)
          ListTile(
            title: const Text('전송 대기 중인 촬영'),
            trailing: IconButton(
              icon: const Icon(Icons.refresh),
              onPressed: busy
                  ? null
                  : () => run(() async {
                      if (!await ensureAiConsent(
                        context,
                        ref.read(apiProvider),
                      )) {
                        return;
                      }
                      await ref
                          .read(apiProvider)
                          .upload(
                            p.paths,
                            p.key,
                            p.eatenAt,
                            captureInfo: p.captureInfo,
                          );
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
                    : '식단 기록 · 음식 ${(m['items'] as List).length}개',
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

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('설정')),
    body: ListView(
      children: [
        ListTile(
          leading: const Icon(Icons.privacy_tip_outlined),
          title: const Text('AI 전송 안내 및 동의'),
          subtitle: const Text('안내 내용·동의 내역 확인 및 철회'),
          onTap: () => Navigator.of(
            context,
          ).push(MaterialPageRoute(builder: (_) => const AiConsentScreen())),
        ),
        ExpansionTile(
          title: const Text('개발자 메뉴'),
          leading: const Icon(Icons.developer_mode),
          children: [
            ListTile(
              title: const Text('AR 지원 확인'),
              onTap: () async {
                final result = await ArCapabilities.check();
                if (context.mounted) {
                  showDialog<void>(
                    context: context,
                    builder: (c) => AlertDialog(
                      title: const Text('AR 지원 정보'),
                      content: Text(
                        '${result['device'] ?? ''}\nARCore: ${result['arCore']}\n깊이: ${result['depth'] ?? '확인 필요'}',
                      ),
                      actions: [
                        TextButton(
                          onPressed: () => Navigator.pop(c),
                          child: const Text('닫기'),
                        ),
                      ],
                    ),
                  );
                }
              },
            ),
            ListTile(
              title: const Text('AR 깊이 진단 · 실험'),
              subtitle: const Text('개발용 깊이·신뢰도 확인 및 로컬 데이터 저장'),
              onTap: () async {
                try {
                  await ArCapabilities.openDiagnostics();
                } catch (e) {
                  if (context.mounted) notify(context, e);
                }
              },
            ),
          ],
        ),
      ],
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
  bool busy = false;
  PendingUpload? saved;
  String? arInfo;
  Map<String, dynamic> captureInfo = {
    'method': 'guided_photos',
    'volumeValidated': false,
  };
  Future<void> capture([ImageSource source = ImageSource.camera]) async {
    setState(() => busy = true);
    try {
      final image = await ImagePicker().pickImage(
        source: source,
        maxWidth: 1600,
        imageQuality: 85,
        requestFullMetadata: false,
      );
      if (image != null && mounted) {
        setState(() {
          paths.add(image.path);
          if (source == ImageSource.gallery) {
            captureInfo['method'] = 'gallery_photos';
          }
        });
      }
    } catch (e) {
      if (mounted) notify(context, e);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> capturePhoto() async {
    if (busy) return;
    setState(() => busy = true);
    try {
      if (paths.isEmpty) {
        final support = await ArCapabilities.check();
        if (!mounted) return;
        if (support['depth'] != false &&
            support['arCore'] != 'UNAVAILABLE' &&
            support['arCore'] != 'UNSUPPORTED_DEVICE_NOT_CAPABLE') {
          final fallback = await captureAr();
          if (!mounted || !fallback) return;
        }
      }
      if (mounted) {
        notify(context, '사진 기반 추정으로 촬영합니다.');
        await capture();
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<bool> captureAr() async {
    setState(() => busy = true);
    try {
      final result = await ArCapabilities.captureMeal();
      if (result?['fallback'] == true) return true;
      if (result != null && mounted) {
        final info = Map<String, dynamic>.from(
          jsonDecode(result['captureInfo'] as String),
        );
        setState(() {
          paths.add(result['path'] as String);
          captureInfo = info;
          arInfo = '거리 정보를 함께 확보했습니다. 중량은 AI 참고 추정값입니다.';
        });
      }
      return false;
    } catch (e) {
      if (mounted) notify(context, e);
      return true;
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> send() async {
    setState(() => busy = true);
    try {
      final api = ref.read(apiProvider);
      if (!await ensureAiConsent(context, api)) return;
      saved ??= await PendingUpload.save(
        (await api.storage.read(key: 'member'))!,
        paths,
        captureInfo: captureInfo,
      );
      final result = await api.upload(
        saved!.paths,
        saved!.key,
        saved!.eatenAt,
        captureInfo: saved!.captureInfo,
      );
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
        paths.isEmpty ? '한 끼를 촬영하거나 갤러리에서 선택하세요' : '선택한 사진을 확인하고 분석하세요',
        style: Theme.of(context).textTheme.titleLarge,
      ),
      gap(),
      const Text(
        '사진 촬영 시 지원 기기에서 거리 정보를 함께 수집합니다. 안내에 따라 잠시 휴대폰을 움직여 주세요. 갤러리 사진은 최대 3장까지 선택할 수 있습니다.',
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
      if (arInfo != null) Text(arInfo!),
      OutlinedButton.icon(
        onPressed:
            paths.length >= 3 ||
                busy ||
                saved != null ||
                captureInfo['method'] == 'ar_assisted_photo'
            ? null
            : capturePhoto,
        icon: const Icon(Icons.camera_alt),
        label: const Text('사진 촬영'),
      ),
      OutlinedButton.icon(
        onPressed:
            paths.length >= 3 ||
                busy ||
                saved != null ||
                captureInfo['method'] == 'ar_assisted_photo'
            ? null
            : () => capture(ImageSource.gallery),
        icon: const Icon(Icons.photo_library),
        label: const Text('갤러리에서 선택'),
      ),
      if (paths.isNotEmpty && saved == null)
        TextButton(
          onPressed: busy
              ? null
              : () => setState(() {
                  paths.clear();
                  arInfo = null;
                  captureInfo = {
                    'method': 'guided_photos',
                    'volumeValidated': false,
                  };
                }),
          child: const Text('선택 초기화'),
        ),
      const Text('갤러리 사진은 사진 기반 참고 추정입니다. 크기 판단이 어려우면 중량 입력을 요청할 수 있습니다.'),
      const Text(
        '음식 분석을 위해 사진과 첨부된 AR 깊이 요약을 외부 AI에 전송합니다. 최초 이용 시 안내와 동의를 진행합니다.',
      ),
      TextButton(
        onPressed: busy
            ? null
            : () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const AiConsentScreen()),
              ),
        child: const Text('AI 전송 안내 자세히 보기'),
      ),
      FilledButton(
        onPressed: paths.isEmpty || busy ? null : send,
        child: Text(busy ? '처리 중…' : '자동 저장하고 분석'),
      ),
      if (busy) const LinearProgressIndicator(),
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
      items[index].remove('weightEstimate');
      items[index].remove('uncertainty');
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

  void selectFood(int index, Map<String, dynamic> selected) {
    setState(() {
      items[index]['foodId'] = selected['id'];
      items[index]['name'] = selected['name'];
      items[index]['source'] = selected;
      items[index].remove('_editedName');
      items[index].remove('candidates');
      changed(index);
      formGeneration++;
    });
  }

  String foodLabel(Map food) =>
      '${food['name']}${(food['manufacturer'] ?? '').toString().trim().isEmpty ? '' : ' · ${food['manufacturer']}'}';

  Future<void> search(int index) async {
    final controller = TextEditingController();
    final query = await showDialog<String>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('음식 검색'),
        content: TextField(
          controller: controller,
          decoration: const InputDecoration(labelText: '음식 이름'),
        ),
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
          title: const Text('먹은 음식과 가장 가까운 항목을 선택하세요'),
          children: [
            if ((results as List).isEmpty)
              const Padding(
                padding: EdgeInsets.all(16),
                child: Text('일치하는 음식을 찾지 못했어요. 더 짧은 음식 이름이나 비슷한 음식으로 검색해 주세요.'),
              ),
            for (final f in results)
              SimpleDialogOption(
                onPressed: () => Navigator.pop(c, Map<String, dynamic>.from(f)),
                child: Text(
                  '${foodLabel(f)}${f['searchFallback'] == true ? '\n비슷한 음식입니다. 실제 음식과 비교해 주세요.' : ''}',
                ),
              ),
          ],
        ),
      );
      if (selected != null && mounted) {
        selectFood(index, selected);
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
    body: !dirty && ['QUEUED', 'RUNNING'].contains(meal?['status'])
        ? AnalysisLoading(
            queued: meal?['status'] == 'QUEUED',
            paused:
                meal?['status'] == 'QUEUED' &&
                meal?['analysisEnabled'] == false,
            error: error,
            onRetry: load,
          )
        : body([
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
              Card(
                key: const ValueKey('meal-total'),
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '한 끼 전체 영양정보',
                        style: Theme.of(context).textTheme.titleLarge,
                      ),
                      gap(),
                      NutritionTable(
                        values: dirty ? {} : mealNutritionTotals(items),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        dirty
                            ? '수정 내용을 저장하면 전체 영양정보가 갱신됩니다.'
                            : '현재 음식별 산출값의 합계입니다. 계산 대기 항목은 보정 후 합계가 표시됩니다.',
                      ),
                    ],
                  ),
                ),
              ),
              gap(),
              Text(
                stateLabel(meal!['status']),
                style: Theme.of(context).textTheme.titleLarge,
              ),
              gap(),
              const Text(
                '식전 제공량 기준 추정치입니다. 중량과 영양정보가 확인되면 자동으로 합계에 반영됩니다. 실제 먹은 양과 다르면 수정할 수 있습니다.',
              ),
              Text(
                meal!['capture_info']?['method'] == 'ar_assisted_photo'
                    ? '분석 방식: AR 깊이 요약을 보조 근거로 사용한 AI 추정 · 부피/밀도 실측 전'
                    : meal!['capture_info']?['method'] == 'gallery_photos'
                    ? '분석 방식: 갤러리 사진 기반 AI 추정'
                    : '분석 방식: 카메라 사진 기반 AI 추정',
              ),
              if (meal!['status'] == 'QUEUED' &&
                  meal!['analysisEnabled'] == false)
                const Text(
                  '서버의 자동 분석이 일시 중지되어 있습니다. 사진은 저장됐으며 분석이 활성화되면 처리됩니다.',
                ),
              if (meal!['status'] == 'QUEUED' &&
                  meal!['analysisEnabled'] != false)
                const Text('분석 순서를 기다리고 있습니다. 완료되면 결과가 자동 표시됩니다.'),
              for (final a
                  in (meal!['status'] == 'COMPLETE' || saveNotice != null
                          ? []
                          : meal!['analyses'])
                      as List)
                if (a['error_code'] != null)
                  Text(analysisHint(a['error_code'])),
              gap(),
              Text('음식별 영양정보', style: Theme.of(context).textTheme.titleLarge),
              for (var i = 0; i < items.length; i++)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '음식 ${i + 1}',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        gap(),
                        ExpansionTile(
                          title: const Text('섭취 정보 직접 수정'),
                          children: [
                            TextFormField(
                              enabled: !busy,
                              key: ValueKey('name-$formGeneration-$i'),
                              initialValue: items[i]['_editedName'] ?? '',
                              decoration: const InputDecoration(
                                labelText: '새 음식 이름 (직접 입력)',
                              ),
                              onChanged: (v) => setState(() {
                                items[i]['_editedName'] = v;
                                items[i]['name'] = v;
                                items[i].remove('candidates');
                                items[i]['foodId'] = '';
                                items[i]['source'] = null;
                                changed(i);
                              }),
                            ),
                            gap(),
                            TextFormField(
                              enabled: !busy,
                              key: ValueKey('grams-$formGeneration-$i'),
                              initialValue: items[i]['_editedGrams'] ?? '',
                              keyboardType:
                                  const TextInputType.numberWithOptions(
                                    decimal: true,
                                  ),
                              decoration: const InputDecoration(
                                labelText: '실제 먹은 중량 (g, 직접 입력)',
                              ),
                              onChanged: (v) => setState(() {
                                items[i]['_editedGrams'] = v;
                                items[i]['grams'] = double.tryParse(v);
                                changed(i);
                              }),
                            ),
                          ],
                        ),
                        TextButton(
                          onPressed: busy ? null : () => search(i),
                          child: Text(
                            (items[i]['foodId'] ?? '').toString().isEmpty
                                ? '음식 선택'
                                : '음식 변경',
                          ),
                        ),
                        NutritionTable(values: items[i]['nutrition'] ?? {}),
                        if ((items[i]['foodId'] ?? '').toString().isEmpty &&
                            (items[i]['candidates'] as List? ?? [])
                                .isNotEmpty) ...[
                          const Text('이 음식이 맞나요? 실제 음식과 가까운 후보를 선택해 주세요.'),
                          Wrap(
                            spacing: 8,
                            children: [
                              for (final candidate
                                  in (items[i]['candidates'] as List).take(5))
                                ActionChip(
                                  label: Text(foodLabel(candidate)),
                                  onPressed: busy
                                      ? null
                                      : () => selectFood(
                                          i,
                                          Map<String, dynamic>.from(candidate),
                                        ),
                                ),
                            ],
                          ),
                        ],
                        ExpansionTile(
                          key: ValueKey('evidence-$formGeneration-$i'),
                          title: const Text('계산 근거 보기'),
                          childrenPadding: const EdgeInsets.all(12),
                          expandedCrossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              '기준 음식: ${items[i]['source']?['name'] ?? '확인 필요'}',
                            ),
                            Text(
                              '자료원: ${items[i]['source']?['source'] ?? '확인 필요'}',
                            ),
                            Text(
                              '자료 버전: ${items[i]['source']?['source_version'] ?? '확인 필요'}',
                            ),
                            Text(
                              '기준량: ${items[i]['source']?['basis_grams'] ?? '확인 필요'} ${items[i]['source']?['basis_unit'] ?? ''}',
                            ),
                            const Text('영양값 = 기준 영양값 × 입력 중량 ÷ 기준 중량'),
                            if (dirty) const Text('수정된 값은 저장 후 계산 결과에 반영됩니다.'),
                          ],
                        ),
                        for (final issue in mealItemIssues(items[i]))
                          Text('• $issue'),
                        if (dirty && items[i]['nutrition'] == null)
                          const Text('입력값이 변경되었습니다. 저장하면 영양값을 다시 계산합니다.'),
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
              const Text(
                '입력한 중량과 선택한 음식의 영양정보로 다시 계산합니다. 사진 재분석은 아래 AI 분석 버튼을 이용해 주세요.',
              ),
              TextButton(
                onPressed:
                    busy ||
                        dirty ||
                        ['QUEUED', 'RUNNING'].contains(meal!['status'])
                    ? null
                    : () => action(() async {
                        if (!await ensureAiConsent(
                          context,
                          ref.read(apiProvider),
                        )) {
                          return;
                        }
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
