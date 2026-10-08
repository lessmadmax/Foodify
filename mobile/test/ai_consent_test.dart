import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:foodify/core/api.dart';
import 'package:foodify/features/ai_consent.dart';

class ConsentApi extends Api {
  bool accepted = false, fail = false;
  int grants = 0, withdrawals = 0;
  @override
  Future<dynamic> request(String method, String path, {dynamic data}) async {
    if (fail) throw StateError('연결 실패');
    if (method == 'PUT') {
      expect(data['version'], 'v1');
      accepted = true;
      grants++;
    }
    if (method == 'DELETE') {
      accepted = false;
      withdrawals++;
    }
    return {
      'version': 'v1',
      'accepted': accepted,
      'notice': '시험 안내',
      'acceptedAt': grants > 0 ? 1000 : null,
      'acceptedVersion': 'v1',
      'revokedAt': withdrawals > 0 ? 2000 : null,
    };
  }
}

void main() {
  testWidgets('최초 동의 후 재이용에는 안내 생략, 취소는 동의 저장 없이 종료', (tester) async {
    final api = ConsentApi();
    int proceed = 0;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [apiProvider.overrideWithValue(api)],
        child: MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: FilledButton(
                onPressed: () async {
                  if (await ensureAiConsent(context, api)) proceed++;
                },
                child: const Text('분석 시작'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('분석 시작'));
    await tester.pumpAndSettle();
    expect(find.text('시험 안내'), findsOneWidget);
    await tester.tap(find.text('나중에'));
    await tester.pumpAndSettle();
    expect(proceed, 0);
    expect(api.grants, 0);
    await tester.tap(find.text('분석 시작'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('안내를 확인했고 AI 전송에 동의합니다'));
    await tester.pumpAndSettle();
    expect(proceed, 1);
    expect(api.grants, 1);
    await tester.tap(find.text('분석 시작'));
    await tester.pumpAndSettle();
    expect(proceed, 2);
    expect(api.grants, 1);
    expect(find.text('시험 안내'), findsNothing);
  });
  testWidgets('설정에서 동의 철회 및 오류 시 재시도', (tester) async {
    final api = ConsentApi()..accepted = true;
    await tester.pumpWidget(
      ProviderScope(
        overrides: [apiProvider.overrideWithValue(api)],
        child: const MaterialApp(home: AiConsentScreen()),
      ),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('동의 철회'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('철회'));
    await tester.pumpAndSettle();
    expect(api.withdrawals, 1);
    expect(api.accepted, false);
    expect(find.textContaining('철회 시각:'), findsOneWidget);
    api.fail = true;
    await tester.tap(find.text('안내를 확인했고 AI 전송에 동의합니다'));
    await tester.pumpAndSettle();
    expect(find.textContaining('연결 실패'), findsOneWidget);
    expect(api.accepted, false);
  });
}
