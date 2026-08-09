// Frozen v1.2.0 producer fixture generator.
//
// THIS TOOL USES THE ACTUAL FROZEN v1.2.0 IMPLEMENTATION:
//   * lib/vault_crypto.dart — copied VERBATIM from tag v1.2.0
//     lib/core/crypto/vault_crypto.dart
//   * lib/vault_models.dart — copied VERBATIM from tag v1.2.0
//     lib/core/vault/vault_models.dart
//
// It constructs a synthetic (schema 3) VaultData through the frozen
// `VaultData.toJson()` model and encrypts it through the frozen
// `VaultCrypto.encryptToFile()` / `VaultFile.encode()` implementation — the
// exact production code path the v1 app uses to write a `.rakvault`.
//
// The durable ids below are deliberately FIXED (not random) so the same
// logical object can be re-encrypted into two different backup files (different
// random salt/nonce → different encrypted bytes) and the resulting v2 stableIds
// can be asserted identical (ADR-0010 durable-id-first contract).
//
// WARNING: fixtures contain ONLY synthetic, disposable test material — never
// real credentials. The keystore is 8 synthetic bytes (AAECAwQFBgc=).

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';

import 'package:legacy_fixture_frozen_v1/vault_crypto.dart';
import 'package:legacy_fixture_frozen_v1/vault_models.dart';

/// Fixed durable ids — the "same logical object" across the two backups.
const String PROVIDER_ID = 'prov-frozen-1';
const String ACCOUNT_ID = 'acc-frozen-1';
const String TOTP_CRED_ID = 'cred-frozen-totp';
const String RECOVERY_CRED_ID = 'cred-frozen-recovery';
const List<String> DEV_IDS = [
  'dev-frozen-android',
  'dev-frozen-api',
  'dev-frozen-ssh',
  'dev-frozen-env',
  'dev-frozen-generic',
];

String _b64e(List<int> b) => base64UrlEncode(b);

VaultData buildVault() {
  final now = DateTime.utc(2024, 1, 15, 10, 30, 0);

  final provider = ServiceProvider(
    id: PROVIDER_ID,
    name: 'GitHub',
    createdAt: now,
    updatedAt: now,
  );

  final totp = TotpCredential(
    id: TOTP_CRED_ID,
    createdAt: now,
    secretBase32: 'JBSWY3DPEHPK3PXP',
    algorithm: TotpHashAlgorithm.sha1,
    digits: 6,
    period: 30,
  );

  final recovery = RecoveryCodesCredential(
    id: RECOVERY_CRED_ID,
    createdAt: now.add(const Duration(minutes: 1)),
    codes: const ['AAAA-BBBB-CCCC', 'DDDD-EEEE-FFFF', '1111-2222-3333'],
  );

  final account = Account(
    id: ACCOUNT_ID,
    providerId: PROVIDER_ID,
    displayName: 'alice@example.com',
    createdAt: now,
    updatedAt: now,
    credentials: [totp, recovery],
  );

  final devEntries = <DeveloperEntry>[
    DeveloperEntry(
      id: DEV_IDS[0],
      type: DeveloperEntryType.androidSigningKey,
      title: 'Release signing',
      notes: 'prod keystore',
      createdAt: now,
      updatedAt: now,
      payload: const {
        'projectName': 'rescueauth',
        'packageName': 'com.rescueauth.app',
        'keystoreFileName': 'release.jks',
        'keystoreBytesBase64': 'AAECAwQFBgc=',
        'storePassword': 'store-pw',
        'keyAlias': 'release',
        'keyPassword': 'key-pw',
      },
    ),
    DeveloperEntry(
      id: DEV_IDS[1],
      type: DeveloperEntryType.apiCredential,
      title: 'Stripe API',
      notes: '',
      createdAt: now,
      updatedAt: now,
      payload: const {
        'serviceName': 'stripe',
        'accountName': 'alice',
        'apiKey': 'sk-test-123',
        'apiSecret': 'secret-abc',
      },
    ),
    DeveloperEntry(
      id: DEV_IDS[2],
      type: DeveloperEntryType.sshKey,
      title: 'Deploy key',
      notes: '',
      createdAt: now,
      updatedAt: now,
      payload: const {
        'keyName': 'work',
        'publicKey': 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA...',
        'privateKey': '-----BEGIN OPENSSH PRIVATE KEY-----\nMIIE...\n-----END OPENSSH PRIVATE KEY-----',
        'passphrase': 'phrase',
      },
    ),
    DeveloperEntry(
      id: DEV_IDS[3],
      type: DeveloperEntryType.envVarSet,
      title: 'Service env',
      notes: '',
      createdAt: now,
      updatedAt: now,
      payload: const {
        'projectName': 'service-a',
        'variables': [
          {'name': 'API_KEY', 'value': 'x'},
          {'name': 'URL', 'value': 'y'},
        ],
      },
    ),
    DeveloperEntry(
      id: DEV_IDS[4],
      type: DeveloperEntryType.genericSecret,
      title: 'Wifi',
      notes: '',
      createdAt: now,
      updatedAt: now,
      payload: const {
        'fields': [
          {'label': 'password', 'value': 'wifi-1'},
        ],
      },
    ),
  ];

  return VaultData(
    schemaVersion: vaultDataSchemaVersion,
    providers: [provider],
    accounts: [account],
    developerSettings: DeveloperSettings.enabled(),
    developerEntries: devEntries,
  );
}

/// Encrypts [vault] through the frozen v1.2.0 VaultCrypto path with a fresh
/// random salt + nonce and returns the full `.rakvault` envelope bytes.
Future<Uint8List> encryptThroughFrozenV1(VaultData vault) async {
  final crypto = VaultCrypto();
  final password = 'test-password-frozen';

  final salt = crypto.newSalt(); // 16 random bytes
  final kdf = VaultKdfParams.defaultParams(salt: salt);
  final key = await crypto.deriveKeyFromPassword(password: password, params: kdf);

  final clearJson = jsonEncode(vault.toJson());
  final clearBytes = Uint8List.fromList(utf8.encode(clearJson));

  final file = await crypto.encryptToFile(
    cleartextJsonBytes: clearBytes,
    key: key,
    kdfParams: kdf,
  );
  return file.encode();
}

Future<void> main(List<String> args) async {
  final outDir = args.isNotEmpty ? args[0] : 'fixtures';

  final vault = buildVault();
  // Produce TWO different encrypted backups of the SAME logical vault (same
  // durable ids, different random salt/nonce -> different encrypted bytes).
  final backup1 = await encryptThroughFrozenV1(vault);
  final backup2 = await encryptThroughFrozenV1(vault);

  if (backup1.length == backup2.length && backup1.toString() == backup2.toString()) {
    throw StateError('two independent encryptions must differ (salt/nonce)');
  }

  final dir = Directory(outDir);
  dir.createSync(recursive: true);
  File('$outDir/frozen_v1_producer_schema3.rakvault').writeAsBytesSync(backup1);
  File('$outDir/frozen_v1_producer_schema3_alt_backup.rakvault').writeAsBytesSync(backup2);

  final sha256 = await Sha256().hash(backup1);
  final sha256b = await Sha256().hash(backup2);
  final hex1 = sha256.bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  final hex2 = sha256b.bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
  stdout.writeln('frozen_v1_producer_schema3.rakvault      $hex1  ${backup1.length} bytes');
  stdout.writeln('frozen_v1_producer_schema3_alt_backup.rakvault $hex2  ${backup2.length} bytes');
  stdout.writeln('password: test-password-frozen');
  stdout.writeln('provenance: frozen v1.2.0 vault_crypto.dart + vault_models.dart (verbatim)');
}
