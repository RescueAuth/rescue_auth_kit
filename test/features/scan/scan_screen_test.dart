import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rescue_auth_kit/features/scan/scan_screen.dart';
import 'package:rescue_auth_kit/l10n/app_localizations.dart';

void main() {
  testWidgets('fake scanner ignores empty values and returns first non-empty', (
    tester,
  ) async {
    String? result;
    late ScanResultHandler scan;

    await tester.pumpWidget(
      MaterialApp(
        localizationsDelegates: const [
          AppLocalizations.delegate,
          GlobalMaterialLocalizations.delegate,
          GlobalWidgetsLocalizations.delegate,
          GlobalCupertinoLocalizations.delegate,
        ],
        supportedLocales: const [Locale('en'), Locale('zh')],
        home: Builder(
          builder: (context) => FilledButton(
            onPressed: () async {
              result = await Navigator.of(context).push<String>(
                MaterialPageRoute(
                  builder: (_) => ScanScreen(
                    scanViewBuilder: (_, onScan) {
                      scan = onScan;
                      return const Text('fake scanner');
                    },
                  ),
                ),
              );
            },
            child: const Text('open'),
          ),
        ),
      ),
    );

    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    scan(null);
    await tester.pump();
    expect(result, isNull);
    expect(find.text('fake scanner'), findsOneWidget);

    scan('   ');
    await tester.pump();
    expect(result, isNull);
    expect(find.text('fake scanner'), findsOneWidget);

    scan('otpauth://totp/GitHub:xincy?secret=JBSWY3DPEHPK3PXP');
    scan('otpauth://totp/Other:xincy?secret=JBSWY3DPEHPK3PXP');
    await tester.pumpAndSettle();

    expect(result, 'otpauth://totp/GitHub:xincy?secret=JBSWY3DPEHPK3PXP');
  });
}
