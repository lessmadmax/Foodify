import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';

/// Render stored assistant Markdown without changing its original content.
class ChatMarkdown extends StatelessWidget {
  const ChatMarkdown({super.key, required this.data});

  final String data;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final colors = theme.colorScheme;
    final body = theme.textTheme.bodyMedium!.copyWith(height: 1.6);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        MarkdownBody(
          data: data,
          selectable: true,
          fitContent: false,
          styleSheet: MarkdownStyleSheet.fromTheme(theme).copyWith(
            p: body,
            strong: const TextStyle(fontWeight: FontWeight.w700),
            h1: body.copyWith(fontSize: 22, fontWeight: FontWeight.w700),
            h2: body.copyWith(fontSize: 20, fontWeight: FontWeight.w700),
            h3: body.copyWith(fontSize: 18, fontWeight: FontWeight.w700),
            h4: body.copyWith(fontWeight: FontWeight.w700),
            h1Padding: const EdgeInsets.only(top: 12, bottom: 8),
            h2Padding: const EdgeInsets.only(top: 10, bottom: 6),
            h3Padding: const EdgeInsets.only(top: 8, bottom: 4),
            blockSpacing: 12,
            listIndent: 24,
            listBullet: body,
            blockquote: body.copyWith(color: colors.onSurfaceVariant),
            blockquoteDecoration: BoxDecoration(
              border: Border(left: BorderSide(color: colors.primary, width: 3)),
            ),
            code: body.copyWith(fontFamily: 'monospace', fontSize: 13),
            codeblockPadding: const EdgeInsets.all(12),
            codeblockDecoration: BoxDecoration(
              color: colors.surface,
              borderRadius: BorderRadius.circular(8),
            ),
            tableHead: body.copyWith(fontWeight: FontWeight.w700),
            tableBody: body,
            tableCellsPadding: const EdgeInsets.all(8),
            tableBorder: TableBorder.all(color: colors.outlineVariant),
            a: TextStyle(
              color: colors.primary,
              decoration: TextDecoration.underline,
            ),
          ),
          // Model-generated URLs must not fetch remote images or local files.
          imageBuilder: (uri, title, alt) => Text(
            alt?.isNotEmpty == true ? '[이미지: $alt]' : '[이미지]',
            style: body,
          ),
          onTapLink: (label, href, title) {
            if (href == null) return;
            showDialog<void>(
              context: context,
              builder: (dialogContext) => AlertDialog(
                title: const Text('참고 링크'),
                content: SelectableText(href),
                actions: [
                  TextButton(
                    onPressed: () => Navigator.pop(dialogContext),
                    child: const Text('닫기'),
                  ),
                  TextButton(
                    onPressed: () async {
                      await Clipboard.setData(ClipboardData(text: href));
                      if (dialogContext.mounted) Navigator.pop(dialogContext);
                    },
                    child: const Text('주소 복사'),
                  ),
                ],
              ),
            );
          },
        ),
        const SizedBox(height: 4),
        IconButton(
          tooltip: '답변 복사',
          visualDensity: VisualDensity.compact,
          iconSize: 18,
          icon: const Icon(Icons.copy_outlined),
          onPressed: () async {
            await Clipboard.setData(ClipboardData(text: data));
            if (context.mounted) {
              ScaffoldMessenger.of(
                context,
              ).showSnackBar(const SnackBar(content: Text('답변을 복사했어요.')));
            }
          },
        ),
      ],
    );
  }
}
