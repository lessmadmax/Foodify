import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:foodify/features/chat_markdown.dart';

void main() {
  Future<void> show(WidgetTester tester, String data) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: SizedBox(width: 320, child: ChatMarkdown(data: data)),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('굵은 글씨와 제목 및 목록을 기호 대신 서식으로 표시', (tester) async {
    await show(
      tester,
      '## 식단 요약\n\n오늘 **단백질**을 챙겨보세요.\n\n- 달걀\n- 두부\n\n1. 아침 기록\n2. 저녁 기록',
    );
    final texts = tester.widgetList<SelectableText>(
      find.byType(SelectableText),
    );
    expect(
      texts.any((t) => t.textSpan?.toPlainText().contains('**') ?? false),
      false,
    );
    final spans = <TextSpan>[];
    for (final t in texts) {
      t.textSpan?.visitChildren((s) {
        if (s is TextSpan) spans.add(s);
        return true;
      });
    }
    expect(
      spans.any(
        (s) => s.text == '단백질' && s.style?.fontWeight == FontWeight.w700,
      ),
      true,
    );
    expect(find.text('식단 요약', findRichText: true), findsWidgets);
    expect(find.text('달걀', findRichText: true), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('표 코드 인용 및 긴 답변을 좁은 화면에 표시', (tester) async {
    await show(
      tester,
      '> 참고 안내\n\n| 영양소 | 양 |\n|---|---|\n| 단백질 | 20g |\n\n`**그대로**`\n\n```text\n${'long_value_' * 50}\n```\n\n${'균형 있는 식사 안내입니다. ' * 50}',
    );
    expect(find.byType(Table), findsOneWidget);
    expect(find.text('**그대로**', findRichText: true), findsWidgets);
    expect(tester.takeException(), isNull);
  });

  testWidgets('이미지 자동 로딩 차단 및 링크 주소 확인', (tester) async {
    await show(
      tester,
      '![사진](https://example.com/tracker.png)\n\n[자료](https://example.com/nutrition)',
    );
    expect(find.byType(Image), findsNothing);
    expect(find.text('[이미지: 사진]'), findsOneWidget);
    final selectable = tester.widgetList<SelectableText>(
      find.byType(SelectableText),
    );
    final links = <TextSpan>[];
    for (final t in selectable) {
      t.textSpan?.visitChildren((s) {
        if (s is TextSpan && s.recognizer != null) links.add(s);
        return true;
      });
    }
    expect(links, isNotEmpty);
    await tester.tap(find.text('자료', findRichText: true).last);
    await tester.pumpAndSettle();
    expect(find.text('참고 링크'), findsOneWidget);
    expect(find.text('https://example.com/nutrition'), findsOneWidget);
  });

  testWidgets('답변 원문 복사', (tester) async {
    String? copied;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          copied = (call.arguments as Map)['text'] as String;
        }
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    await show(tester, '**단백질** 20g');
    await tester.tap(find.byTooltip('답변 복사'));
    await tester.pumpAndSettle();
    expect(copied, '**단백질** 20g');
    expect(find.text('답변을 복사했어요.'), findsOneWidget);
  });
}
