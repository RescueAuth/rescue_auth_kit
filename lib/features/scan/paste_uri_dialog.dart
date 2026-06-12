import 'package:flutter/material.dart';

import '../../l10n/app_localizations.dart';

Future<String?> showPasteOtpauthDialog(BuildContext context) async {
  return showDialog<String?>(
    context: context,
    builder: (_) => const _PasteOtpauthDialog(),
  );
}

class _PasteOtpauthDialog extends StatefulWidget {
  const _PasteOtpauthDialog();

  @override
  State<_PasteOtpauthDialog> createState() => _PasteOtpauthDialogState();
}

class _PasteOtpauthDialogState extends State<_PasteOtpauthDialog> {
  final _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return AlertDialog(
      title: Text(l10n.pasteDialogTitle),
      content: TextField(
        controller: _controller,
        minLines: 2,
        maxLines: 5,
        decoration: InputDecoration(
          hintText: l10n.pasteDialogHint,
          border: const OutlineInputBorder(),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: Text(l10n.dialogCancel),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, _controller.text),
          child: Text(l10n.dialogContinue),
        ),
      ],
    );
  }
}
