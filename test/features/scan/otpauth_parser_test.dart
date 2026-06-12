import 'package:flutter_test/flutter_test.dart';
import 'package:rescue_auth_kit/core/import/otpauth_parser.dart';
import 'package:rescue_auth_kit/core/vault/vault_models.dart';

void main() {
  test('parse otpauth totp uri', () {
    const uri =
        'otpauth://totp/GitHub:xincy?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=SHA1&digits=6&period=30';
    final parsed = parseOtpauthTotpUri(uri);

    expect(parsed.issuer, 'GitHub');
    expect(parsed.accountName, 'xincy');
    expect(parsed.digits, 6);
    expect(parsed.period, 30);
  });

  test('uses issuer from label when query issuer is missing', () {
    const uri = 'otpauth://totp/GitHub:xincy?secret=JBSWY3DPEHPK3PXP';
    final parsed = parseOtpauthTotpUri(uri);

    expect(parsed.issuer, 'GitHub');
    expect(parsed.accountName, 'xincy');
  });

  test('normalizes base32 secrets with spaces, dashes, and padding', () {
    const uri = 'otpauth://totp/GitHub:xincy?secret=jbsw-y3dp ehpk3pxp====';
    final parsed = parseOtpauthTotpUri(uri);

    expect(parsed.secretBase32, 'JBSWY3DPEHPK3PXP');
  });

  test('scheme and algorithm parsing are case-insensitive', () {
    const uri =
        'OTPAUTH://totp/GitHub:xincy?secret=JBSWY3DPEHPK3PXP&algorithm=sha256';
    final parsed = parseOtpauthTotpUri(uri);

    expect(parsed.algorithm, TotpHashAlgorithm.sha256);
  });

  test('throws for missing secret', () {
    expect(
      () => parseOtpauthTotpUri('otpauth://totp/GitHub:xincy'),
      throwsA(isA<OtpAuthParseException>()),
    );
  });

  test('throws for migration URI', () {
    expect(
      () => parseOtpauthTotpUri('otpauth-migration://offline?data=abc'),
      throwsA(isA<OtpAuthParseException>()),
    );
  });

  test('throws for invalid digits and period ranges', () {
    expect(
      () => parseOtpauthTotpUri(
        'otpauth://totp/GitHub:xincy?secret=JBSWY3DPEHPK3PXP&digits=5',
      ),
      throwsA(isA<OtpAuthParseException>()),
    );
    expect(
      () => parseOtpauthTotpUri(
        'otpauth://totp/GitHub:xincy?secret=JBSWY3DPEHPK3PXP&period=0',
      ),
      throwsA(isA<OtpAuthParseException>()),
    );
  });
}
