import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../core/api.dart';

Future<bool> ensureAiConsent(BuildContext context, Api api) async {
  final state = await api.request('GET', '/me/ai-consent');
  if (!context.mounted) return false;
  if (state['accepted'] == true) return true;
  return await Navigator.of(context).push<bool>(
        MaterialPageRoute(
          builder: (_) =>
              AiConsentScreen(initial: Map<String, dynamic>.from(state)),
        ),
      ) ==
      true;
}

class AiConsentScreen extends ConsumerStatefulWidget {
  const AiConsentScreen({super.key, this.initial});
  final Map<String, dynamic>? initial;
  @override
  ConsumerState<AiConsentScreen> createState() => _AiConsentState();
}

class _AiConsentState extends ConsumerState<AiConsentScreen> {
  Map<String, dynamic>? state;
  bool busy = false;
  String? error;
  @override
  void initState() {
    super.initState();
    state = widget.initial;
    if (state == null) Future.microtask(load);
  }

  Future<void> load() async {
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final result = await ref
          .read(apiProvider)
          .request('GET', '/me/ai-consent');
      if (mounted) setState(() => state = Map<String, dynamic>.from(result));
    } catch (e) {
      if (mounted) setState(() => error = errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> change(bool accept) async {
    if (busy || state == null) return;
    if (!accept) {
      final yes = await showDialog<bool>(
        context: context,
        builder: (c) => AlertDialog(
          title: const Text('AI 전송 동의를 철회할까요?'),
          content: const Text(
            '이후 AI 분석·피드백에는 다시 동의가 필요합니다. 기존 기록은 유지되며 이미 전송된 요청은 처리될 수 있습니다.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(c, false),
              child: const Text('취소'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(c, true),
              child: const Text('철회'),
            ),
          ],
        ),
      );
      if (yes != true || !mounted) return;
    }
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final result = await ref
          .read(apiProvider)
          .request(
            accept ? 'PUT' : 'DELETE',
            '/me/ai-consent',
            data: accept
                ? {'version': state!['version'], 'accepted': true}
                : null,
          );
      if (!mounted) return;
      setState(() => state = Map<String, dynamic>.from(result));
      if (accept) Navigator.pop(context, true);
    } catch (e) {
      if (mounted) setState(() => error = errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  String time(dynamic value) => value is num
      ? DateTime.fromMillisecondsSinceEpoch(
          value.toInt(),
        ).toLocal().toString().split('.').first
      : '기록 없음';
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('AI 전송 안내 및 동의')),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        if (busy) const LinearProgressIndicator(),
        if (state != null) ...[
          Text(
            state!['accepted'] == true ? '동의 완료' : 'AI 기능 이용 전 안내',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 12),
          Text('안내 버전: ${state!['version']}'),
          if (state!['acceptedAt'] != null)
            Text(
              '동의 시각: ${time(state!['acceptedAt'])} · 버전 ${state!['acceptedVersion']}',
            ),
          if (state!['revokedAt'] != null)
            Text('철회 시각: ${time(state!['revokedAt'])}'),
          const SizedBox(height: 20),
          SelectableText(state!['notice'] as String),
          const SizedBox(height: 20),
          if (state!['accepted'] == true)
            OutlinedButton(
              onPressed: busy ? null : () => change(false),
              child: const Text('동의 철회'),
            )
          else ...[
            FilledButton(
              onPressed: busy ? null : () => change(true),
              child: const Text('안내를 확인했고 AI 전송에 동의합니다'),
            ),
            TextButton(
              onPressed: busy ? null : () => Navigator.pop(context, false),
              child: const Text('나중에'),
            ),
          ],
        ],
        if (error != null) ...[
          Text(error!),
          TextButton(
            onPressed: busy ? null : load,
            child: const Text('최신 안내 다시 불러오기'),
          ),
        ],
      ],
    ),
  );
}
