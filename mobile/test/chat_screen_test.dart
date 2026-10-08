import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:foodify/core/api.dart';
import 'package:foodify/features/chat_screen.dart';

class ChatApi extends Api {
  final turns = <Map<String, dynamic>>[];
  final requests = <dynamic>[];
  Completer<void>? wait;
  bool fail = false;
  @override
  Future<dynamic> request(String method, String path, {dynamic data}) async {
    if (path == '/me/ai-consent') return {'accepted': true};
    if (method == 'GET') return List.of(turns);
    if (method == 'DELETE') {
      turns.clear();
      return null;
    }
    requests.add(data);
    if (wait != null) await wait!.future;
    if (fail) throw StateError('시험 연결 오류');
    final turn = {
      'id': 'turn-${turns.length}',
      'request_key': data['requestKey'],
      'question': data['message'],
      'answer': '저녁에는 단백질 식품을 곁들여 보세요.',
      'status': 'COMPLETE',
      'from': '2026-10-02',
      'to': '2026-10-08',
      'asOf': 1000,
      'incompleteMeals': 1,
    };
    turns.add(turn);
    return turn;
  }
}

void main() {
  testWidgets('채팅 전송·중복 방지·답변 근거·후속 질문·대화 삭제', (tester) async {
    final api = ChatApi()..wait = Completer<void>();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [apiProvider.overrideWithValue(api)],
        child: const MaterialApp(home: ChatScreen()),
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '오늘 단백질 충분해?');
    await tester.tap(find.byTooltip('전송'));
    await tester.pump();
    await tester.pump();
    expect(api.requests.length, 1);
    expect(tester.widget<TextField>(find.byType(TextField)).enabled, false);
    api.wait!.complete();
    await tester.pumpAndSettle();
    expect(find.textContaining('저녁에는 단백질'), findsOneWidget);
    expect(find.textContaining('미완료 1건'), findsOneWidget);
    api.wait = null;
    await tester.enterText(find.byType(TextField), '그럼 저녁은?');
    await tester.tap(find.byTooltip('전송'));
    await tester.pumpAndSettle();
    expect(api.requests.length, 2);
    expect(api.requests[0]['requestKey'], isNot(api.requests[1]['requestKey']));
    await tester.tap(find.byTooltip('대화 삭제'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('삭제'));
    await tester.pumpAndSettle();
    expect(api.turns, isEmpty);
    await tester.pumpWidget(const SizedBox.shrink());
  });
  testWidgets('전송 실패 시 입력과 재전송 키 유지', (tester) async {
    final api = ChatApi()..fail = true;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [apiProvider.overrideWithValue(api)],
        child: const MaterialApp(home: ChatScreen()),
      ),
    );
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '다음 식사 추천');
    await tester.tap(find.byTooltip('전송'));
    await tester.pumpAndSettle();
    expect(find.textContaining('시험 연결 오류'), findsOneWidget);
    api.fail = false;
    await tester.tap(find.byTooltip('전송'));
    await tester.pumpAndSettle();
    expect(api.requests[0]['requestKey'], api.requests[1]['requestKey']);
    await tester.pumpWidget(const SizedBox.shrink());
  });
}
