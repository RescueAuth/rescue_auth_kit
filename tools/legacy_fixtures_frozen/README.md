# Frozen v1.2.0 producer fixture tool

This tool generates legacy `.rakvault` fixtures using the **actual frozen
v1.2.0 implementation**, not a re-implementation:

- `lib/vault_crypto.dart` — copied **VERBATIM** from tag `v1.2.0`
  `lib/core/crypto/vault_crypto.dart`
- `lib/vault_models.dart` — copied **VERBATIM** from tag `v1.2.0`
  `lib/core/vault/vault_models.dart`

The generator builds a synthetic schema-3 `VaultData`, serializes it through the
frozen `VaultData.toJson()`, and encrypts it through the frozen
`VaultCrypto.encryptToFile()` / `VaultFile.encode()` — the exact production
code path the v1 app uses to write a `.rakvault`.

## Why

Phase 5A's Python fixtures (argon2-cffi + PyNaCl) and the Kotlin test encoder
are independent re-implementations. To lock **actual producer interoperability**
we need at least one fixture whose encrypted bytes were really produced by the
frozen v1 implementation. This tool produces it.

## Output

Two **different encrypted backups of the same logical vault** (same durable ids,
different random salt/nonce → different source fingerprints):

- `frozen_v1_producer_schema3.rakvault`
- `frozen_v1_producer_schema3_alt_backup.rakvault`

These two files are the fixture pair used by `LegacyDurableIdStableIdTest` to
prove the durable-id-first stableId contract: the same logical objects yield the
same v2 stableIds across different encrypted backups, even though the source
fingerprints differ.

## Run

```bash
dart pub get
dart run bin/generate_frozen_fixture.dart /tmp/out
```

Copy the two `.rakvault` files to both:
- `v2/legacy-fixtures/phase5a/` (canonical repo copies)
- `v2/core/src/test/resources/legacy-fixtures/phase5a/` (test resources)

Password: `test-password-frozen` (test-only).

## Regeneration note

Fixtures are frozen — do NOT regenerate casually. Regenerating with a different
random salt/nonce changes the SHA-256 and the byte-identical guard in
`LegacyFixtureIntegrityTest` (add these two files to that manifest when
regenerating).
