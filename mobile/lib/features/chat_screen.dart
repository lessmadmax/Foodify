import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';
import '../core/api.dart';
import 'ai_consent.dart';

class ChatScreen extends ConsumerStatefulWidget {
  const ChatScreen({super.key});
  @override
  ConsumerState<ChatScreen> createState() => _ChatState();
}

class _ChatState extends ConsumerState<ChatScreen> with WidgetsBindingObserver {
  final input = TextEditingController();
  final scroll = ScrollController();
  List<dynamic> turns = [];
  bool loading = true, busy = false, fetching = false;
  String? error, pendingKey, pendingText;
  Timer? timer;
  bool get running => turns.any((t) => t['status'] == 'RUNNING');
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    Future.microtask(load);
    timer = Timer.periodic(const Duration(seconds: 5), (_) {
      if (running && !busy) load();
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && !busy) load();
  }

  @override
  void dispose() {
    timer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    input.dispose();
    scroll.dispose();
    super.dispose();
  }

  void bottom() => WidgetsBinding.instance.addPostFrameCallback((_) {
    if (mounted && scroll.hasClients) {
      scroll.jumpTo(scroll.position.maxScrollExtent);
    }
  });
  Future<void> load() async {
    if (fetching) return;
    if (!mounted) return;
    setState(() => fetching = true);
    try {
      final result = await ref.read(apiProvider).request('GET', '/chat');
      if (mounted) {
        setState(() {
          turns = result as List;
          loading = false;
        });
        bottom();
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          error = errorText(e);
          loading = false;
        });
      }
    } finally {
      if (mounted) setState(() => fetching = false);
    }
  }

  Future<void> send({Map? retry}) async {
    if (busy || running || loading || fetching) return;
    final message = retry?['question'] as String? ?? input.text.trim();
    if (message.isEmpty) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      if (!await ensureAiConsent(context, ref.read(apiProvider)) || !mounted) {
        return;
      }
      final key =
          retry?['request_key'] as String? ??
          (pendingText == message ? pendingKey : null) ??
          const Uuid().v4();
      pendingText = message;
      pendingKey = key;
      setState(() {});
      bottom();
      await ref
          .read(apiProvider)
          .request(
            'POST',
            '/chat',
            data: {'message': message, 'requestKey': key},
          );
      if (!mounted) return;
      if (retry == null) input.clear();
      pendingText = null;
      pendingKey = null;
      await load();
    } catch (e) {
      if (mounted) {
        setState(() => error = errorText(e));
        await load();
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> clear() async {
    final yes = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('대화를 삭제할까요?'),
        content: const Text('질문·답변과 답변 당시의 식단 근거를 삭제합니다. 식단 기록은 유지됩니다.'),
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
    if (yes != true || !mounted) return;
    setState(() => busy = true);
    try {
      await ref.read(apiProvider).request('DELETE', '/chat');
      if (mounted) {
        setState(() {
          turns = [];
          error = null;
          pendingKey = null;
          pendingText = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = errorText(e));
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Widget bubble(String text, bool user) => Align(
    alignment: user ? Alignment.centerRight : Alignment.centerLeft,
    child: Container(
      margin: const EdgeInsets.symmetric(vertical: 6),
      padding: const EdgeInsets.all(14),
      constraints: const BoxConstraints(maxWidth: 540),
      decoration: BoxDecoration(
        color: user
            ? Theme.of(context).colorScheme.primaryContainer
            : Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            user ? '나' : 'Foodify',
            style: const TextStyle(fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 6),
          SelectableText(text),
        ],
      ),
    ),
  );
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('식단 피드백 챗봇'),
      actions: [
        IconButton(
          tooltip: '대화 새로고침',
          onPressed: busy || fetching ? null : load,
          icon: const Icon(Icons.refresh),
        ),
        IconButton(
          tooltip: '대화 삭제',
          onPressed: busy || running || loading || fetching ? null : clear,
          icon: const Icon(Icons.delete_outline),
        ),
      ],
    ),
    body: SafeArea(
      child: Column(
        children: [
          const Padding(
            padding: EdgeInsets.all(12),
            child: Text(
              '오늘·최근 7일 기록과 목표를 바탕으로 답합니다. 매 질문마다 최신 기록을 확인하며, 일반적인 식생활 참고 안내를 제공합니다.',
            ),
          ),
          if (loading) const LinearProgressIndicator(),
          Expanded(
            child: ListView(
              controller: scroll,
              padding: const EdgeInsets.all(16),
              children: [
                if (!loading && turns.isEmpty) ...[
                  bubble(
                    '궁금한 점을 물어보세요. 예: 오늘 단백질은 충분해? 그럼 저녁은 어떻게 먹을까?',
                    false,
                  ),
                  const Text('최근 대화 50개를 표시하고, 답변에는 최근 10쌍의 대화 맥락을 사용합니다.'),
                ],
                for (final turn in turns) ...[
                  bubble(turn['question'] as String, true),
                  if (turn['status'] == 'COMPLETE') ...[
                    bubble(turn['answer'] as String, false),
                    Text(
                      '참고 기록: ${turn['from']} ~ ${turn['to']} · 미완료 ${turn['incompleteMeals']}건',
                    ),
                    Text(
                      '답변 기준: ${DateTime.fromMillisecondsSinceEpoch((turn['asOf'] as num).toInt()).toLocal().toString().split('.').first}\n기록을 수정하면 다음 답변부터 반영됩니다. 누락된 식사는 확인이 필요합니다.',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ] else if (turn['status'] == 'RUNNING')
                    const Text('답변을 작성하고 있어요…')
                  else
                    TextButton(
                      onPressed: busy || running
                          ? null
                          : () => send(retry: turn as Map),
                      child: const Text('답변 생성 실패 · 다시 시도'),
                    ),
                ],
                if (busy &&
                    pendingText != null &&
                    !turns.any((t) => t['request_key'] == pendingKey))
                  bubble(pendingText!, true),
                if (busy || running) const LinearProgressIndicator(),
              ],
            ),
          ),
          if (error != null)
            Padding(padding: const EdgeInsets.all(8), child: Text(error!)),
          Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Expanded(
                  child: TextField(
                    controller: input,
                    enabled: !busy && !running && !loading && !fetching,
                    minLines: 1,
                    maxLines: 4,
                    maxLength: 2000,
                    decoration: const InputDecoration(
                      hintText: '식단에 대해 물어보세요',
                      counterText: '',
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  tooltip: '전송',
                  onPressed: busy || running || loading || fetching
                      ? null
                      : () => send(),
                  icon: const Icon(Icons.send),
                ),
              ],
            ),
          ),
        ],
      ),
    ),
  );
}
