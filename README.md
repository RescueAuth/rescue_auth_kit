# RescueAuthKit

[English](README.md) | [中文](README.zh-CN.md)

RescueAuthKit is a small, opinionated **personal security vault** for Android:
reliable TOTP authenticator + recovery codes + developer secrets storage, with a
portable, encrypted, merge-first export/import format so you can move your vault
between devices without guessing which app supports what.

The repository is a **native Android app** (Kotlin + Jetpack Compose + Room/SQLCipher,
encrypted database, biometric unlock). The former Flutter rewrite history is frozen
under the `legacy-vX.Y.Z` tags and kept for reference and one-time migration only.

## Project structure

```
.
├── app/                  # Android application module (Compose UI + Room)
│   └── src/
│       ├── main/         # app source (com.rescueauth.v2)
│       ├── test/         # Robolectric unit tests
│       └── androidTest/  # instrumented tests (Firebase Test Lab)
├── core/                 # Platform-agnostic Kotlin core (codec, totp, import...)
│   └── src/main/kotlin/com/rescueauth/v2/
├── docs/                 # Design reports (PHASE*, ADR, THREAT_MODEL, ...)
├── gradle/               # Gradle wrapper + version catalog
├── legacy-fixtures/      # Frozen legacy v1 fixtures for import tests
├── release/              # Public Android signing certificate metadata
├── scripts/              # Build / release / CI helper scripts
├── tools/                # Icon + interop fixture generators
├── build.gradle.kts      # Root build file
├── settings.gradle.kts   # includes :app and :core
├── AGENTS.md             # Maintenance contract for AI / human maintainers
├── PRODUCT.md            # Product scope
└── ROADMAP.md            # Roadmap & phase tracking
```

## Branch / Version / Release Policy

> **`main` is development; tags are releases.**

### Development

`main` is the active development branch and is not a stable release. It may
contain changes that have not been included in a stable release. Do **not**
treat current `main` as a stable release.

### Legacy RescueAuth

The legacy Flutter app is frozen and preserved under tags:

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

Current applicationId:

```
com.rescueauth.v2
```

Official stable source snapshots are identified by tags.

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
only. **"Build debug RescueAuth"** is a FAST real-device smoke APK factory: it
only runs `assembleDebug`, validates the produced APK, and uploads it — it does
**not** run core/Robolectric tests or lint, so it must **not** be treated as
evidence that the full regression has passed. Formal releases stay under the
`rescueauth-vX.Y.Z` **tag** pipeline.

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

## Build

Requires JDK 17 + Android SDK 35.

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

- Core JVM tests: `./gradlew :core:test`
- App Robolectric unit tests: `./gradlew :app:testDebugUnitTest`
- Lint: `./gradlew :app:lintDebug`
- Instrumented tests run on Firebase Test Lab manually via the **"Run Firebase
  device tests"** web trigger on `main` only (see `docs/FIREBASE_TEST_LAB.md`).

## Docs

- **Maintenance contract**: [`AGENTS.md`](AGENTS.md)
- **Product scope**: [`PRODUCT.md`](PRODUCT.md)
- **Roadmap & phases**: [`ROADMAP.md`](ROADMAP.md)
- **Documentation index & currency rules**: [`docs/README.md`](docs/README.md)
- **Design / phase reports & ADRs**: [`docs/`](docs/)
  (PHASE reports, ADRs, PACKAGE_FORMAT, THREAT_MODEL, UPDATE_PROTOCOL,
  LEGACY_IMPORT, RELEASE_PROVISIONING, FIREBASE_TEST_LAB)

## Legacy Flutter app (v1.x, frozen)

The original cross-platform Flutter app is frozen at tag `legacy-v1.2.0` and
preserved under the `legacy-vX.Y.Z` tags. It is no longer the active development
target; it is kept for reference and one-time migration into the v2 native app.
