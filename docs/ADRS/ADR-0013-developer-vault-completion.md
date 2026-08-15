# ADR-0013 — Developer Vault Completion: keystore size contract & env-var identity

**Status**: Accepted (Phase 4 P6)
**Date**: 2026-08-10

## Context

Phase 4 P6 adds the final two Developer Entry types (Android Signing Key,
Environment Variable Set) to production Android daily use. Two security-relevant
decisions needed a documented, non-invented contract:

1. **Keystore SAF import size boundary.** The importer must bound the raw
   keystore file it reads, but must not invent a third size rule unrelated to
   the existing logical/package contract.
2. **Env-var reveal/copy target identity.** Each env value is a secret; the
   fresh re-auth target must stably identify a variable without using a mutable
   list index (which reorders on edit) and without changing the package format.

## Decision

### 1. Keystore size boundary is DERIVED from the shared per-asset contract

The shared logical/package validator already caps a stored keystore's
`keystoreBase64` at
`PackageValidator.MAX_KEYSTORE_BASE64_LENGTH = 12 MiB` of base64 text. Since
base64 encodes 3 raw bytes as 4 chars, the maximum raw-byte count that can ever
encode to ≤ that base64 cap is `(12 MiB / 4) * 3 = 9 MiB`. P6 therefore exposes
`DeveloperRepository.MAX_KEYSTORE_RAW_BYTES = (12 MiB / 4) * 3` and the SAF
importer rejects any raw file larger than this before buffering it. This
guarantees any stored keystore is always exportable through the package path —
there is never a "validator accepts but Full Export can never encode" state.

- No third, independent size constant is invented.
- The importer reads incrementally (64 KiB buffer) and rejects on `cap + 1`
  actually read — it never trusts `OpenableColumns.SIZE`.

### 2. Env-var target identity is `stableId + immutable field key`

Env values live in a list; a raw list index is not a durable security identity
because edit / reorder can change it. Variables have **no durable child stableId
in the logical model** (only the set has a stableId). Therefore P6 binds each
variable's fresh re-auth to the **Developer entry `stableId` + an immutable
field key `"var:<name>"`** (analogous to the existing `"field:<label>"` for
Generic Secret). The name is metadata; the target is captured at request time and
never re-resolved from a changing list, so a prompt pending while a row is
mutated cannot reveal a different variable.

This adds **no** package-format change: `VaultEnvironmentVariableSet` /
`VaultKeyValue` and the sealed logical model are untouched. The security binding
is purely an in-memory `SensitiveActionTarget.DeveloperField` request contract.

## Consequences

- **Positive**: one bounded-read path shared with the package contract; env-var
  auth is stable and precise (Entry A / variable A auth can never reveal/copy
  Entry B / variable B); no schema or package churn.
- **Trade-off**: `"var:<name>"` uses the (metadata) name as the field key; if a
  user renames a variable, a new reveal requires a fresh re-auth (same as
  Generic Secret label keys). This is acceptable — the name is stable within a
  single request and reveal state is in-memory only.
- **Non-goals** (unchanged): no key generation, no APK signing, no keytool
  automation, no project-file mutation.
