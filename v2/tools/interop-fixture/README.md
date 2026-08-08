# otpauth-migration Interop Fixture Tooling

This directory documents how the independent interoperability fixtures in
`core/src/test/kotlin/com/rescueauth/v2/migration/InteropFixtures.kt` were
produced.

## Why

The P2 migration adapter is an external-format compatibility layer backed by a
hand-written `MinimalProtobuf` decoder. The project's own synthetic
`ProtoFixture` can only prove the parser is internally self-consistent; to
prove interop with the real Google Authenticator export wire format we freeze
fixtures from **independent** sources.

## Sources (in priority order)

### A. Real Google Authenticator v6.0 exports (synthetic test accounts)

`InteropFixtures.GA_*` are the actual QR payloads exported by **Google
Authenticator v6.0**, taken from
[`krissrex/google-authenticator-exporter`](https://github.com/krissrex/google-authenticator-exporter)
`test-assets/test-qr-codes.json` (MIT license, `usingAppVersion: "6.0"`,
generated 2024-06-11). They contain only **disposable synthetic test
credentials** (`Hello!…`, `Test account 1/2`, `TOTPgenerator`, …) — no real
credential material.

An independent `protoc` + Python `google.protobuf` decode of those bytes (see
below) confirmed:

- the `OtpParameters` enum semantics (`type=2` = TOTP, `digits=1` = SIX,
  `digits=2` = EIGHT, `algorithm=4` = MD5);
- batch metadata (`batch_size` / `batch_index` / `batch_id`) is carried
  **inside the decoded `MigrationPayload`**, not in query parameters;
- Google emits **padded standard Base64** percent-encoded into `data`
  (`%3D%3D` for `==`, `%2F` for `/`).

### B. protoc-generated fixture (independent protobuf implementation)

`protoc --python_out=. google_auth_migration.proto` + the Python
`google.protobuf` runtime build `InteropFixtures.PROTOC_FIXTURE_*` from the
confirmed schema. Run:

```sh
protoc --python_out=. google_auth_migration.proto
python3 generate_fixture.py
```

The script prints the serialized bytes (hex), the standard-padded Base64
form, the URL-safe-no-padding form, and the expected decoded values that the
`InteropFixtures.PROTOC_FIXTURE_*` constants freeze.

> **Strict protocol note (final convergence):** the parser only accepts the
> **standard padded Base64** form (the real Google Authenticator wire form).
> The URL-safe-no-padding strings below are frozen as **negative contract**
> fixtures (`PROTOC_FIXTURE_URLSAFE_NOPAD`, `ALPHABET_DISTINGUISHING_URLSAFE`)
> that the parser must **reject** (`invalid-data-character`); they are kept in
> `InteropFixtures` so the reject behavior stays locked by tests.

`InteropFixtures.ALPHABET_DISTINGUISHING_*` is a single-entry payload chosen so
that its **standard** Base64 contains both `+` and `/` (and its URL-safe form
differs) — it locks the decoder's alphabet handling with data that genuinely
distinguishes standard from URL-safe Base64 (standard accepted, URL-safe
rejected).

## Verification

`InteropFixtureTest` asserts every decoded value (issuer / name / algorithm /
digits / secret Base32 / batch metadata) matches the expected values recorded
above, proving the real Google wire format round-trips through
`MigrationPayloadParser`.
