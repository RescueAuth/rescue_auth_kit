import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../../l10n/app_localizations.dart';

typedef ScanResultHandler = void Function(String? rawValue);
typedef ScanViewBuilder =
    Widget Function(BuildContext context, ScanResultHandler onScan);

class ScanScreen extends StatefulWidget {
  const ScanScreen({super.key, this.scanViewBuilder});

  final ScanViewBuilder? scanViewBuilder;

  @override
  State<ScanScreen> createState() => _ScanScreenState();
}

class _ScanScreenState extends State<ScanScreen> {
  final MobileScannerController _controller = MobileScannerController();
  bool _handled = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.addTotpSheetScan)),
      body:
          widget.scanViewBuilder?.call(context, _handleScan) ??
          MobileScanner(
            controller: _controller,
            onDetect: (result) {
              final raw = result.barcodes.isNotEmpty
                  ? result.barcodes.first.rawValue
                  : null;
              _handleScan(raw);
            },
          ),
    );
  }

  void _handleScan(String? raw) {
    if (_handled) return;
    if (raw == null || raw.trim().isEmpty) return;

    _handled = true;
    Navigator.of(context).pop(raw);
  }
}
