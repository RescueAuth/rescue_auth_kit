# RescueAuthKit

[English](README.md) | [中文](README.zh-CN.md)

RescueAuthKit is a small, opinionated **personal security vault** for Android:
reliable TOTP authenticator + recovery codes + developer secrets storage, with a
portable, encrypted, merge-first export/import format so you can move your vault
between devices without guessing which app supports what.

> **Note:** The repository is being rewritten as a **native Android app** under
> [`v2/`](v2/) (Kotlin + Room/SQLCipher, encrypted database, biometric unlock).
> The legacy Flutter app below is frozen at tag `legacy-v1.2.0` and kept for
> reference and one-time migration. See [`v2/AGENTS.md`](v2/AGENTS.md) for the
> v2 status and build commands.

## Branch / Version / Release Policy

> **`main` is development; tags are releases.**

### Development

`main` is the active development branch.

It may contain changes that have not been included in a stable release.

Do **not** treat current `main` as a stable release.

### Legacy RescueAuth

Legacy releases use:

```
legacy-vX.Y.Z
```

Examples:

```
legacy-v1.0.0
legacy-v1.0.1
legacy-v1.1.0
legacy-v1.2.0
```

Legacy applicationId:

```
com.xincy.rescue_auth_kit
```

### Current RescueAuth

Current releases use:

```
rescueauth-vX.Y.Z
```

Examples:

```
rescueauth-v1.0.0
rescueauth-v1.0.1
rescueauth-v1.1.0
...
```

Current applicationId:

```
com.rescueauth.v2
```

### Development

`main` is the active development branch and is not a stable release. Official
stable source snapshots are identified by tags.

### Build entry points

| Intent | Entry point |
|--------|-------------|
| Development (debug APK for real-device smoke) | Web trigger **"Build debug RescueAuth"** on `main` / feature / fix branches |
| Full regression test suite (manual, no secrets) | Web trigger **"Run full RescueAuth test suite"** on any branch |
| Firebase on-device device tests (manual, `main` only) | Web trigger **"Run Firebase device tests"** on `main` only |
| Formal production release | Push a `rescueauth-vX.Y.Z` **release tag** (tag-only pipeline) |

`main` / feature / fix branches are **never** production-released directly.

**Test trigger policy:** ordinary push / PR merge / main update does **not**
automatically run any RescueAuth test (including Firebase Test Lab). When you
need a full regression, trigger it manually via the **"Run full RescueAuth
test suite"** web trigger (core JVM tests, app Robolectric, lint,
`assembleDebug`, `assembleDebugAndroidTest`; no secrets). For on-device cloud
verification, use the **"Run Firebase device tests"** web trigger on `main`
only. Real-device smoke builds stay under **"Build debug RescueAuth"**; formal
releases stay under the `rescueauth-vX.Y.Z` **tag** pipeline.

## Why I built this

Most authenticator apps make migration the hardest part of the experience. This
project flips the priority:

- Your data lives in one encrypted local vault.
- Export/import are first-class features, not an afterthought.
- The goal is reliable phone-to-phone recovery and migration.

## What is special here

- Android-only, local-first, encrypted personal security vault (no account/login
  backend, no cloud sync).
- Three first-class capabilities: **Authenticator** (TOTP + recovery codes),
  **Developer Vault** (Android signing keys / API credentials / SSH keys / env
  vars / generic secrets) and **Portable Vault Package** (manual export,
  per-export PIN, merge-first import).
- Strong local encryption:
  - Android Keystore-wrapped VaultKey + SQLCipher database
  - Portable packages protected by a per-export PIN
- Merge-first import: importing a package merges into the current vault (no
  replace/restore-overwrite semantics).

## RescueAuth v2 (Android native rewrite)

- **Location**: `v2/` — Kotlin + Jetpack Compose + Room/SQLCipher.
- **Status**: phases 0/1/2 are closed and merged to `main` (encrypted DB,
  VaultKey/Keystore, secure session, auto-lock, mask + FLAG_SECURE); the
  database instrumentation tests (`RescueAuthDatabaseInstrumentedTest`, 6 cases)
  have **passed 6/6 on Firebase Test Lab** (MediumPhone.arm / API 33).
  **Phase 3 is in progress**: Phase 3A (Package + Merge Foundation) is
  implemented in PR #18 — logical export-package model, stable identity +
  semantic fingerprint, merge planner, schema v2 (see `v2/docs/PHASE3_REPORT.md`).
- **Build**: `cd v2 && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
  (needs JDK 17 + Android SDK 35). Instrumented tests run on Firebase Test Lab
  manually via the **"Run Firebase device tests"** web trigger on `main` only
  (see `docs/FIREBASE_TEST_LAB.md`).
- **Docs**: see `v2/docs/` (PHASE reports, ADRs, LEGACY_IMPORT, PACKAGE_FORMAT,
  THREAT_MODEL, UPDATE_PROTOCOL) and the formal roadmap in
  [`v2/ROADMAP.md`](v2/ROADMAP.md).

## Legacy Flutter app (v1.x, frozen)

The original cross-platform Flutter app, frozen at tag `legacy-v1.2.0`. It
remains fully functional but is no longer the active development target.

---

## Vault model

Starting with v1.1.0 the vault uses a three-tier account-centric model:

```
ServiceProvider (e.g. "GitHub")
└── Account (e.g. "user@example.com")
    └── Credential[] (TOTP | RecoveryCodes)
```

- One Provider can hold many Accounts.
- One Account can hold any mix of TOTP and RecoveryCodes credentials.
- Accounts can be renamed, moved to another provider, or merged into another
  account (the merge appends source credentials to the target, then deletes
  the source).

## Features

> The list below describes the **legacy Flutter v1.2.0 (frozen)** app. The v2
> target product scope lives in [`v2/ROADMAP.md`](v2/ROADMAP.md); current
> implementation status lives in [`v2/AGENTS.md`](v2/AGENTS.md).
- Providers list as the home tab; drill down into accounts and credentials.
- TOTP codes with live countdown and copy (rendered only on the account
  detail screen).
- Recovery codes: add, view, copy-all, edit codes in place, move to another
  account, delete.
- TOTP import:
  - Android: QR scan
  - Desktop: paste `otpauth://totp/...`
- Inline destination selector when importing a credential — choose
  provider+account up-front in three modes (new provider+account, existing
  provider+new account, existing account).
- Encrypted backup export and import (the core feature).
- Bilingual UI (English / Simplified Chinese).

## Supported Platforms (legacy v1.2.0, frozen)

- Windows desktop
- Android

Web is not supported (the vault uses local file IO).

> **v2 target platform: Android only**. Windows / macOS / Linux / iOS / Web
> clients are explicitly out of scope (see `v2/ROADMAP.md §1`). The list above
> describes the legacy Flutter app.

## Run locally

```bash
flutter pub get
flutter analyze
flutter test
flutter run -d windows
```

To run on Android:

```bash
flutter devices
flutter run -d <device-id>
```

## Backup / Restore (legacy v1.2.0 flow, frozen)

> v2 has moved to **Portable Vault Package**: manual Export (per-export PIN) +
> merge-first Import (no master password, no automatic backup). The flow below
> describes the legacy app.

1. Create and unlock the vault on Device A
2. Import a few TOTP entries and/or recovery codes
3. Export from the Settings tab
4. Import that vault file on Device B using the same master password
5. Confirm the same TOTP codes appear on both devices

## Upgrading from 1.0.x

A 1.0.x vault is migrated automatically on first unlock under 1.1.0:

- Each legacy TOTP entry becomes its own account; entries that share an
  issuer are grouped under a single provider.
- Each legacy recovery code set becomes its own account under a dedicated
  provider; you can merge or move them afterwards via the account menu.
- **Once 1.1.0 has written the vault file, older versions cannot open it.**
  Export a backup with 1.0.x first if you may need to roll back.

## Notes and limitations

- `otpauth-migration://` is not supported by the legacy v1 app (v2 adds
  **import-only** support as an External Import Adapter — see `v2/ROADMAP.md §4.3`).
- Forgetting the master password means the legacy vault cannot be decrypted.
- TOTP credentials are immutable by design — to change any field, delete
  and re-add the credential (kept for v2, see `v2/ROADMAP.md §4.2`).
