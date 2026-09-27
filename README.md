<img src="design/brand/shiyifang-logo.svg" width="80" height="80" alt="RescueAuth blue and gold key">

# RescueAuth · 拾遗坊

[English](README.md) | [简体中文](README.zh-CN.md)

A local-first, encrypted personal security vault for **Android 8.0+**. Keep
verification codes, recovery codes and developer secrets together, and move
them between devices with a manually exported, encrypted vault package.

## Release status

**Preparing the first native release: 1.0.0 · status checked 2026-09-27.**

The planned feature scope is implemented; the interface and navigation motion
have passed local regression checks.
The latest code baseline, `69127e6`, passed 420 core tests, 769 app JVM tests
and 90 Android instrumented tests. Debug builds passed; lint reported no errors
and still has warnings. See the [validation record](docs/UI_POLISH_REPORT.md).

The native app has **not been released**. Remaining work covers production
update signing and hosting, signing-key backup confirmation, and final cloud /
real-device verification of the release candidate. The
[release checklist](docs/RELEASE_PROVISIONING.md#release-readiness) tracks these
gates. “v2” names the native rewrite generation; its first Android version is
`1.0.0`, not `2.0.0`.

## What it does

| Area | Capabilities |
| --- | --- |
| Accounts | Services and accounts, TOTP countdown and copy, QR / `otpauth` import, Google Authenticator migration import, recovery-code groups and used-state tracking |
| Developer vault | Android signing keys and keystore files, API credentials, SSH keys, environment variable sets and generic secrets; generic fields can also store username/password pairs |
| Backup and migration | Manual `.rakpkg` export with a separate PIN for each package, full or selected export/import, preview and merge into the current vault, read-only import of legacy `.rakvault` backups |
| Everyday use | Search over non-sensitive labels, pinned accounts, deletion undo, English / Simplified Chinese, light / dark / system appearance |

Generic secret storage does not include system autofill or passkey management.
The full scope and boundaries are defined in [PRODUCT.md](PRODUCT.md).

## Your data

- Vault contents stay in a local SQLCipher database, protected by an Android
  Keystore-wrapped key. Unlock uses biometrics or the device screen lock.
- Export, protected secret access and opening an existing developer entry's
  full editor require fresh authentication. There is no global app master password.
- Backups are **manual**. Each exported package has its own PIN; imports merge
  with existing data. There is no account backend, cloud sync or automatic backup.
- Export a package before replacing your device, and keep its PIN separately.
  The old master password is used only when importing a legacy `.rakvault` file.
- Update checks are manual and verify a signed manifest. The app opens the
  release page externally; it does not download or silently install updates.

See the [security model](docs/THREAT_MODEL.md),
[package format](docs/PACKAGE_FORMAT.md) and
[update protocol](docs/UPDATE_PROTOCOL.md) for implementation details.

## Build and try it

Use **JDK 17**, Android SDK platform **35** and build-tools **35**. Set
`ANDROID_HOME` to the SDK location or set `sdk.dir` in an untracked
`local.properties`. Device use requires a configured screen lock.

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest :app:lintDebug

# With a connected test device or emulator:
./gradlew :app:connectedDebugAndroidTest
```

The local debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Debug APKs
are development builds signed with a debug key. The native app uses
`com.rescueauth.v2` (`versionName=1.0.0`, `versionCode=10000`).

## Build and release entry points

The primary repository is [CNB](https://cnb.cool/xincy22/rescue_auth_kit).
`main` is the development branch. Stable releases use immutable
`rescueauth-vX.Y.Z` tags after the release gates have been met.

| Purpose | CNB entry point | What it runs |
| --- | --- | --- |
| Try a debug APK | **Build debug RescueAuth** on `main` / feature / fix / auto branches | Build, validate and attach the debug APK; no unit tests or lint |
| Full regression | **Run full RescueAuth test suite** on any branch | Core + app JVM tests, lint, debug and AndroidTest APK builds; no secrets |
| Cloud device regression | **Run Firebase device tests** on `main` | A Firebase Test Lab virtual-device matrix; separate from JVM tests |
| Production candidate | Push an annotated `rescueauth-vX.Y.Z` tag | Release gates, production signing, APK verification and attachment |

The three buttons are manual and owner-only. Ordinary pushes and PR merges do
not automatically run tests. The tag pipeline does not yet publish the signed
update manifest; candidate verification and update-channel publication remain
separate steps. See [release provisioning](docs/RELEASE_PROVISIONING.md) and
[Firebase Test Lab](docs/FIREBASE_TEST_LAB.md).

## Repository and documentation

```text
app/              Native Android application, Compose UI and Android tests
core/             Platform-independent Kotlin logic and JVM tests
design/brand/     Final editable logo source and resource-generation guide
docs/             Product contracts, release guide, ADRs and implementation records
legacy-fixtures/  Frozen compatibility fixtures for legacy imports
release/          Public signing certificate metadata
scripts/          Build, verification and resource-generation tools
```

Local design drafts, screenshots, preview pages and build outputs are excluded
from version control. The final logo and the resources used by the app are retained.

- [Product scope](PRODUCT.md) · [Roadmap and milestones](ROADMAP.md)
- [Release checklist](docs/RELEASE_PROVISIONING.md#release-readiness)
- [Documentation index](docs/README.md) · [Maintenance contract](AGENTS.md)

## Migrating from legacy RescueAuth

The Flutter app is frozen under `legacy-v1.0.0`–`legacy-v1.2.0` tags. Its package
name is `com.xincy.rescue_auth_kit`; the native app has a separate identity.
Migration uses **Import from Legacy Rescue Auth** and a legacy `.rakvault`
backup. The old app's private storage is never read directly. See the
[legacy import contract](docs/LEGACY_IMPORT.md).
