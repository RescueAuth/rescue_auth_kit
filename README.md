# RescueAuthKit

[English](README.md) | [中文](README.zh-CN.md)

RescueAuthKit is a small, opinionated 2FA vault focused on one thing: reliable
import/export so you can move your TOTP secrets and recovery codes between
devices without guessing which app supports what.

> **Note:** The repository is being rewritten as a **native Android app** under
> [`v2/`](v2/) (Kotlin + Room/SQLCipher, encrypted database, biometric unlock).
> The legacy Flutter app below is frozen at tag `v1.2.0` and kept for reference
> and one-time migration. See [`v2/AGENTS.md`](v2/AGENTS.md) for the v2 status
> and build commands.

## Why I built this

Most authenticator apps make migration the hardest part of the experience. This
project flips the priority:

- Your data lives in one encrypted vault file.
- Backup and restore are first-class features, not an afterthought.
- The goal is "phone <-> desktop" recovery that you can actually trust.

## What is special here

- Single encrypted vault file you can copy anywhere.
- Strong password-based encryption:
  - Argon2id for key derivation
  - XChaCha20-Poly1305 for authenticated encryption
- Cross-platform migration flow:
  - Export on one device, import on another, verify the same codes.

## RescueAuth v2 (Android native rewrite)

- **Location**: `v2/` — Kotlin + Jetpack Compose + Room/SQLCipher.
- **Status**: phases 0/1 + phase1-fix + phase 2 (encrypted DB, VaultKey/Keystore,
  secure session, auto-lock, mask + FLAG_SECURE) are merged to `main`;
  `phase2-blocker-hotfix` (PR #6) fixed the biometric-prompt startup crash and
  made platform tests buildable/truthful; SQLCipher native loading is fixed
  (PR #15). The database instrumentation tests
  (`RescueAuthDatabaseInstrumentedTest`, 6 cases) have **passed 6/6 on Firebase
  Test Lab** (MediumPhone.arm / API 33).
- **Build**: `cd v2 && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
  (needs JDK 17 + Android SDK 35). Instrumented tests run on Firebase Test Lab
  via `main` push (see `docs/FIREBASE_TEST_LAB.md`).
- **Docs**: see `v2/docs/` (PHASE reports, ADRs, LEGACY_IMPORT, BACKUP_FORMAT,
  THREAT_MODEL, UPDATE_PROTOCOL).

## Legacy Flutter app (v1.x, frozen)

The original cross-platform Flutter app, frozen at tag `v1.2.0`. It remains
fully functional but is no longer the active development target.

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

## Supported Platforms (current focus)

- Windows desktop
- Android

Web is not supported (the vault uses local file IO).

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

## Backup / Restore (the intended flow)

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

- `otpauth-migration://` is not supported.
- Forgetting the master password means the vault cannot be decrypted.
- TOTP credentials are immutable by design — to change any field, delete
  and re-add the credential.
