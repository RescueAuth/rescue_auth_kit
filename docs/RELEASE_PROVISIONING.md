# 拾遗坊 / RescueAuth — Release preparation and provisioning

> **Status: preparing native release 1.0.0; not published. Checked 2026-09-27.**
>
> Application identity and Android signing infrastructure are established.
> Android signing identity / CNB secret provisioning was recorded on 2026-08-11.
> The current CNB production path is **tag-only**; debug builds, full regression
> and Firebase Test Lab use separate manual buttons. Production update trust
> configuration, update-channel publishing and final device acceptance remain open.
> Start with [the release checklist and evidence](#release-readiness).

---

## 1. Naming conventions — v2 generation name vs. release version

The internal/project name **RescueAuth v2** (also written `v2 rewrite`,
`V2.0 FEATURE COMPLETE`) is a **second-generation architecture / rewrite
generation** name. It is **not** the Android `versionName`.

The product name is **拾遗坊** in Chinese and **RescueAuth** in English.
The repository remains `rescue_auth_kit`.

- **RescueAuth v2** = second-generation rewrite / project generation name.
- **V2.0 FEATURE COMPLETE = YES** = the product feature scope of the
  second-generation rewrite is complete. It does **not** mean the Android
  `versionName` must be `2.0.0`.
- **Android release version** = `versionName "1.0.0"`, `versionCode 10000`.

> Never write `V2.0 FEATURE COMPLETE` as "versionName 2.0.0". They are
> different concepts.

---

## 2. Application identity (frozen)

Two independent Android applications can be installed side-by-side because
their **applicationId** values differ. Application identity is **not** the same
as signing identity (see §6).

| App | Generation | applicationId | namespace | First release version |
|-----|-----------|---------------|-----------|----------------------|
| Legacy RescueAuth v1 | legacy / v1 | `com.xincy.rescue_auth_kit` | `com.xincy.rescue_auth_kit` | existing legacy line (frozen at `v1.2.0`) |
| **New RescueAuth** | **v2** | **`com.rescueauth.v2`** | **`com.rescueauth.v2`** | **`1.0.0` (versionCode `10000`)** |

**Frozen identity (new RescueAuth, long-term stable):**

```
applicationId = "com.rescueauth.v2"
namespace     = "com.rescueauth.v2"
versionName   = "1.0.0"
versionCode   = 10000
```

- The new App's applicationId is already `com.rescueauth.v2`, independent and
  non-colliding with Legacy `com.xincy.rescue_auth_kit`, so **it is kept as-is
  (no change)**. applicationId is now frozen as the long-term installation
  identity: future `1.0.1`, `1.1.0`, `2.x`… keep the same applicationId.
- The new App has its **own independent release version sequence starting at
  1.0.0**. It is **not** an overlay upgrade of Legacy v1.2.0.

### Side-by-side install (HARD REQUIREMENT)

Because `com.rescueauth.v2` ≠ `com.xincy.rescue_auth_kit`, Legacy v1 and the new
RescueAuth can be installed on the same device, each with its own independent
app sandbox. Static proof:

- different `applicationId` (different install identity),
- different Android sandbox identity,
- independent signing plan (see §6).

> **REAL-DEVICE SIDE-BY-SIDE SMOKE = PENDING** — the final signed candidate
> must be exercised alongside the legacy app before release acceptance.

---

## 3. versionCode reasoning

- Legacy v1 frozen versionCode (root Flutter project `pubspec.yaml`):
  `1.2.0+5` → **versionName 1.2.0, versionCode 5**.
- Previous new-app versionName / versionCode (v2 `app/build.gradle.kts`):
  **1.0.0 / 10000** (unchanged this round).
- **Final versionName = 1.0.0, final versionCode = 10000.**

Because the new App has a different applicationId, it does **not** need a
`versionCode` greater than Legacy's to "overwrite" it. `versionCode` only needs
to be monotonically increasing **within the new App's own release line**. The
existing `10000` is a sensible baseline and is **kept** — no meaningless churn
to `1`/`100`/`200` just to make numbers "look matched".

### Future versionCode policy (non-binding)

The only hard rule is:

> For every formal upgrade of the **same applicationId**:
> `new versionCode > previous versionCode`.

`versionName` is display metadata only and is **never** parsed as the
version-comparison authority. Update Check compares **only `versionCode`**.
A non-enforced illustrative example:

```
1.0.0 → 10000
1.0.1 → 10001
1.1.0 → future greater integer
```

---

## 4. Update protocol / version interaction

- The **L2 Update Check belongs to the new App**. `latest.json` `versionName`
  follows the new App's own release line (starting `1.0.0`).
- `manifest.versionCode` corresponds only to the **new App's** applicationId
  release line.
- The new App's update check **never compares against Legacy v1's version**, and
  never concludes it is "older" than Legacy "1.2.0" via `versionName`. The two
  have different applicationIds and are independent release sequences.
- Update Check compares only: `new App current versionCode` vs. `new App
  manifest versionCode`.

> Production Update Ed25519 provisioning remains a release gate. The checked-in
> build defaults to an empty `UPDATE_PUBLIC_KEY`, and the current tag pipeline
> does not provision this value or publish `latest.json` / `latest.json.sig`.
> The local debug build checked on 2026-09-27 has no configured update key.

---

## 5. About version display

The About page shows the runtime version from **build/package metadata**
(`BuildConfig.VERSION_NAME` / `BuildConfig.VERSION_CODE`), injected into the
UI. It is **not** hard-coded as `"1.0.0"` in UI strings. There is no change to
the Update protocol (comparison authority stays `versionCode`).

---

## 6. Signing identity — separate from application identity

**Decision:** Legacy v1 signing identity ≠ new RescueAuth signing identity. The
new RescueAuth will use a **brand-new** production Android signing key.

Important distinction:

- **application identity** (`applicationId`) is what actually enables
  side-by-side install.
- **signing identity** (the production keystore) governs **overlay upgrades
  within the same applicationId**.

Different signing keys alone do **not** enable side-by-side install.

### Long-term signing rule

Once the new production key signs RescueAuth `1.0.0`, **every later release**
(`1.0.1`, `1.1.0`, `1.x`, `2.x`…) with the same applicationId **must use the
same signing identity** — otherwise Android cannot overlay-upgrade. Therefore
the signing keystore **must be preserved securely long-term**:

- primary offline encrypted copy,
- independent encrypted backup.

The RescueAuth App itself must **never** auto-hold its own APK signing private
key.

---

## 7. Production signing infrastructure

The production **private key lives entirely outside the repo**. The build
loads the signing config at Gradle configuration time from **one of two**
sources (both optional):

1. a local **`keystore.properties`** file (gitignored; template in
   `keystore.properties.example`), or
2. environment variables / Gradle `-P` properties:
   - `RESCUEAUTH_STORE_FILE`
   - `RESCUEAUTH_STORE_PASSWORD`
   - `RESCUEAUTH_KEY_ALIAS`
   - `RESCUEAUTH_KEY_PASSWORD`

Logic fields: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

### Behavior matrix

| Condition | Release build behavior |
|-----------|------------------------|
| All four config fields present + keystore exists | `release` is **signed** with the new RescueAuth production key |
| Any field missing / keystore missing | `release` builds **unsigned** (never debug-signing fallback) |

Normal dev / CI (`./gradlew :app:assembleRelease` without secrets) continues to
produce an **unsigned release** and stays green.

**Explicit signed-release intent:** run
`./gradlew :app:validateReleaseSigning :app:assembleRelease`. If the config is
incomplete or the keystore is missing, `validateReleaseSigning` **fails clearly**
(no NPE, no FileNotFound mystery, no silent fallback, no silent debug signing).

### Explicitly forbidden

```
release { signingConfig = signingConfigs.debug }
```

Never: fall back to debug signing, auto-generate a keystore, use a test keystore
as the production keystore, or silently fake-sign.

---

## 8. Signing secret loading policy

Production signing secrets must **never** enter:

- git,
- Gradle source literals,
- `BuildConfig`,
- `strings.xml`,
- `AndroidManifest`,
- Room / DataStore,
- test fixtures,
- real examples in docs,
- logs,
- CI output.

`keystore.properties` and keystore binaries are gitignored:

```
/keystore.properties
*.jks
*.keystore
*.p12
*.pfx
```

The `.example` template contains only placeholders
(`storePassword=CHANGE_ME`, `keyPassword=CHANGE_ME`) and is committed.

> Note: the project has its own **Android Signing Key / Developer Vault**
> feature whose tests use synthetic in-memory keystore fixtures. These are byte
> arrays in test sources and are **not** affected by the ignore rules.

---

## 9. Production signing identity

The real production signing key was generated on a trusted local host on
2026-08-11. The private key exists only inside a password-protected PKCS12
container outside this repository. The local primary copy must be backed up
independently before public release.

```bash
# Historical provisioning command shape. Passwords came from a 48-byte CSPRNG
# source and were supplied through environment-backed keytool password options;
# they were never command-line literals and are intentionally omitted here.
keytool -genkeypair \
  -v \
  -storetype PKCS12 \
  -keystore rescueauth-v2-release.p12 \
  -alias rescueauth-v2 \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity 14600 \
  -storepass:env RESCUEAUTH_STORE_PASSWORD \
  -keypass:env RESCUEAUTH_KEY_PASSWORD \
  -dname "CN=RescueAuth,OU=Release,O=xincy22"
```

- **Alias chosen: `rescueauth-v2`** (stable, simple). Though the public release
  version starts at `1.0.0`, the internal alias may use the generation name `v2`.
- **Store type:** PKCS12.
- **Alias:** `rescueauth-v2`.
- **Key:** RSA 4096; certificate signature `SHA256withRSA`.
- **Subject:** `CN=RescueAuth, OU=Release, O=xincy22`.
- **Validity:** 2026-08-11T03:32:22Z through 2066-08-01T03:32:22Z.
- **Certificate SHA-256:**
  `2C56E6B764F5664DFD34EA7BFB38F07F1B991055093754E4104714C527584944`.
- The machine-readable public assertion is
  `release/android-signing-certificate.txt`. It contains no secret material.
- Independent offline backup: **PENDING USER ACTION**.

---

## 10. CI behavior

Without production signing secrets, normal CI continues to support:

```
./gradlew :core:test
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:assembleRelease
```

- `:app:assembleRelease` runs **unsigned** (no debug signing).
- The Secret Repository file `android-signing.yml` supplies only these variables
  to the **`tag_push`** production signing stage:
  `RESCUEAUTH_KEYSTORE_BASE64`, `RESCUEAUTH_STORE_PASSWORD`,
  `RESCUEAUTH_KEY_PASSWORD`, and `RESCUEAUTH_KEY_ALIAS`.
- Production release is **tag-only** (see §10a below): it runs on the
  `tag_push` event from a `rescueauth-vX.Y.Z` tag. `main` and feature/fix
  branches are never production-released. The former main-only production web
  trigger has been removed.
- `scripts/validate-release-tag.sh` (run as the FIRST stage, before any secret
  import) FAILS CLOSED unless `CNB_BRANCH` matches
  `^rescueauth-v[0-9]+\.[0-9]+\.[0-9]+$` (rejects legacy `legacy-vX.Y.Z` tags,
  `main`, feature branches and arbitrary strings).
- `scripts/build-production-apk.sh` reconstructs a `0600` PKCS12 under a runner
  temp directory, validates its public certificate fingerprint, runs
  `validateReleaseSigning` + `assembleRelease`, verifies the APK signer and
  manifest identity/version/debuggable state, then unlinks the temporary store.
  It derives the release version from the tag and asserts the APK `versionName`
  equals it (tag `rescueauth-v1.0.0` -> `versionName 1.0.0`). `versionCode` is
  read from the actual Gradle build, never from the tag.
- The job does not upload the PKCS12 or its Base64 transport value. A signed APK
  is attached to the release tag's commit after verification.

### First production-signed candidate audit (local, not published)

This is the **2026-08-11 provisioning artifact**, not a signed build of the
current UI baseline. Its checksum is retained as historical evidence only.

- Artifact: `RescueAuth-1.0.0-production-signed.apk`.
- APK SHA-256:
  `71D32E2DF3DFAD426DA8A4873AD4058C2A4BF354A28966DC096A06366BA4D931`.
- `apksigner verify --verbose --print-certs`: PASS; one signer; APK Signature
  Scheme v2.
- Signer certificate SHA-256 matches the pinned production certificate: PASS.
- `applicationId=com.rescueauth.v2`, `versionName=1.0.0`, `versionCode=10000`,
  `debuggable=false`: PASS.
- 16 KiB zip alignment verification: PASS.
- Real-device side-by-side smoke: PENDING.

---

## 10a. Build & Release Workflow (formal)

Long-term policy: **`main` is development; tags identify production candidates
and releases.** Publishing a tag starts the signing and attachment pipeline;
stable-channel publication still requires the checks below. See also the
root [build and release entry points](../README.md#build-and-release-entry-points).

### Git / version model

- `main` = continuous development branch. Never treated as a stable release.
- feature/fix branch = work in progress.
- Stable release = the **immutable** commit snapshot a release tag points to.
- No `develop` / `release/*` / `hotfix/*` / complex Git Flow.
- Published release tags must never be moved, reused, rewritten, deleted, or
  re-pointed at another commit.

### Tag namespaces

| Namespace | App | Example | Entry into new production path? |
|-----------|-----|---------|---------------------------------|
| Legacy `legacy-vX.Y.Z` | legacy `com.xincy.rescue_auth_kit` | `legacy-v1.0.0`, `legacy-v1.1.0`, `legacy-v1.2.0` | **No** — preserved, rejected |
| New namespaced `rescueauth-vX.Y.Z` | current `com.rescueauth.v2` | `rescueauth-v1.0.0` | **Yes** — the only accepted production tag |

`rescueauth-` is a Git **tag namespace**, not part of the Android `versionName`.
Tag `rescueauth-v1.0.0` corresponds to Android `versionName 1.0.0`. The new App's
independent release line starts at `1.0.0`.

### Daily development — Debug Pipeline

Web trigger **"Build debug RescueAuth"** (`web_trigger_debug_apk`), available to
`owner` on `main` / feature / fix branches:

```
assembleDebug (no core / Robolectric tests or lint)
  -> debug signing (standard Android debug key)
  -> verify applicationId=com.rescueauth.v2, debuggable=true, valid debug sig
  -> RescueAuth-<versionName>-debug-<shortCommit>.apk
  -> commit attachment (downloadable, single APK)
```

- No production Secret Repo import. No production signing password / keystore.
- No `validateReleaseSigning` / `assembleRelease`.
- Used for development and real-device `.rakvault` smoke. A successful debug
  build is not evidence that the full regression suite passed.

### Manual regression entry points

- **Run full RescueAuth test suite** (`web_trigger_full_test`, any branch):
  core JVM + app Robolectric tests, lint, `assembleDebug` and
  `assembleDebugAndroidTest`; no secret imports and no FTL execution.
- **Run Firebase device tests** (`web_trigger_firebase_test`, `main` only):
  build and validate the debug / AndroidTest APKs, then run the configured
  single virtual-device matrix. FTL credentials are confined to that stage.
- All three buttons are owner-only. Ordinary branch push, PR merge and `main`
  updates do not automatically run these suites. Configuration lives in
  [`.cnb.yml`](../.cnb.yml) and [`.cnb/web_trigger.yml`](../.cnb/web_trigger.yml).

### Formal production release — Tag-only pipeline

1. Complete the [pre-tag gates](#release-readiness) for the chosen `main`
   commit, including update configuration and debug / device regression.
2. Confirm `versionName` / `versionCode` in `app/build.gradle.kts`.
3. Owner creates an **annotated** release tag, e.g. `git tag -a rescueauth-v1.0.0`,
   and pushes it.
4. `tag_push` pipeline runs `scripts/validate-release-tag.sh` FIRST (FAIL CLOSED
   unless the tag matches `^rescueauth-v[0-9]+\.[0-9]+\.[0-9]+$`).
5. `build-production-apk.sh` re-validates the tag, derives the version, imports
   the Secret Repo **only in the signing stage**, reconstructs the temporary
   PKCS12, runs `validateReleaseSigning` + `assembleRelease`.
6. `apksigner verify` + exactly-one-signer + pinned cert fingerprint + APK
   identity assertions (applicationId, versionName == tag version, versionCode,
   debuggable=false).
7. Artifact `RescueAuth-X.Y.Z.apk` is attached to the release tag's commit.
8. Download that exact attachment, verify its checksum and signer, and complete
   the signed-candidate real-device smoke.
9. Publish release notes and the stable update channel only after candidate
   acceptance. The current pipeline stops at step 7; signed update-manifest
   publication remains to be implemented and verified.

### Tag/version assertion (long-term contract)

- `tag -> versionName`: tag `rescueauth-vX.Y.Z` must equal Android `versionName`
  `X.Y.Z`. Mismatch FAILS (not hardcoded to `1.0.0`).
- `versionCode`: taken from the actual Gradle build, reported, never derived
  from the tag. Must stay monotonically increasing in the new App's own line.
- Legacy tags (namespace `legacy-vX.Y.Z`) are rejected by the production path.

### Regression tests

Offline (no Android SDK, no secrets):

```
bash scripts/test/test-release-version.sh       # tag parsing / version extraction
bash scripts/test/test-validate-release-tag.sh  # production tag gate FAIL CLOSED
bash scripts/test/test-build-debug-apk.sh       # debug contract (no secrets, naming)
bash scripts/test/test-build-production-apk.sh  # apksigner parser regression
```

---

## 11. Repository hygiene

A scan of the repo found **no production signing secrets**:

- no `*.jks` / `*.keystore` / `*.p12` / `*.pfx` committed,
- no `keystore.properties` committed,
- no `storePassword` / `keyPassword` literals in build config,
- no `signingConfig = signingConfigs.debug` fallback,
- no production Update private key committed.

The `storePassword` / `keyPassword` identifiers found in `app/...` source are
the **Developer Vault** feature (the app stores user-entered signing-key
metadata as Vault data) — they are **not** the build's production signing
config and are untouched.

---

## 12. Schema / protocol expectations (this step)

| Item | Change |
|------|--------|
| Room schema | NO |
| Native package format | NO |
| Legacy format | NO |
| Update protocol schema | NO |
| Crypto | NO |
| applicationId | NO (already correct: `com.rescueauth.v2`) |
| versionName | FROZEN to `1.0.0` |

---

## 13. Package / component collision audit

- No `FileProvider` / `ContentProvider` authorities are declared in either
  manifest → no fixed-authority string collision between Legacy v1 and the new
  App.
- Provider authorities should (and do) derive from `${applicationId}`-safe,
  app-sandbox-local contexts when providers are added later.
- Room DB name is `rescueauth_v2.db` (independent sandbox).
- Android Keystore Vault alias `vault_key_wrap` (AndroidKeyStore) is the Vault's
  internal key — separate from APK production signing.
- The new App and Legacy have independent app sandboxes (different
  applicationIds), so Vault aliases / Room DB / DataStore do not collide.

---

## 14. Migration & side-by-side strategy

Legacy v1 will **not** be overwritten by the new App. The real migration flow:

```
Legacy v1 remains installed
  → export .rakvault
  → new RescueAuth
  → Import from Legacy RescueAuth
  → compare data
  → continue keeping Legacy as fallback
```

The portable package (`.rakpkg`) is a **platform-neutral logical package**; the
`applicationId` freeze does not affect package format, package crypto, `stableId`,
merge, selected export, restore, or native import. Legacy `.rakvault` import
continues to work. The new App never tries to directly read another app's private
sandbox (no shared UID, no root, no adb-only migration, no ContentProvider
bridge).

---

<a id="release-readiness"></a>

## 15. Release readiness — 1.0.0

**Current stage: release preparation. `V2.0 FEATURE COMPLETE = YES`;
`V2.0 RELEASED = NO`.** This section owns the release gates and acceptance
evidence; phase progress stays in [ROADMAP.md](../ROADMAP.md).

### Verified baseline

The code baseline is `69127e65b6ad1aff0f1d9783fd6d5648a6942711`, checked on
2026-09-27. Subsequent source or release-configuration changes need validation
against the new candidate revision.

| Area | Evidence and limit |
| --- | --- |
| Product and UI | Planned feature scope complete; final brand assets, theme / Dock, shared page templates, floating inputs, synchronized page / Dock motion, the simplified welcome screen and the transparent Chinese wordmark are in the baseline. Local review artifacts remain outside version control. See [UI_POLISH_REPORT.md](UI_POLISH_REPORT.md). |
| Local regression | Core 420/420 + app JVM 769/769; Android instrumented 90/90 on an Android 15 / API 35 emulator. No failures or skips. |
| Build and static checks | Debug + AndroidTest APKs built; lint: 0 errors, 254 warnings, 1 information item. Eleven brand checks passed. This is not a production APK acceptance record. |
| Android production identity | Step 1 merged; Step 2 provisioned on 2026-08-11. Public certificate metadata is in [release/android-signing-certificate.txt](../release/android-signing-certificate.txt). The old local signed APK in §10 does not cover the current source. |
| CI entry points | Manual debug, full regression and FTL entries are configured. Production signing and APK attachment run only for `rescueauth-vX.Y.Z` tags. Update-manifest publication is not wired into this pipeline. |
| Cloud / real-device evidence | Historical database-only FTL 6/6 PASS is retained. Final FTL regression and physical-device signed-candidate smoke have no current acceptance record. |
| Release publication | No `rescueauth-v*` tag was present in the remote tag check on 2026-09-27; native `1.0.0` remains unpublished. |

### Before creating the production tag

- [ ] **Update signing and trusted configuration.** Provision the independent
  production Ed25519 manifest key, keep its private material in the designated
  secret storage, and supply its public key through `UPDATE_PUBLIC_KEY`. Verify
  a correctly signed manifest and rejection of tampered / incorrectly signed
  bytes with the candidate configuration. Android APK signing identity stays
  unchanged. See [UPDATE_PROTOCOL.md](UPDATE_PROTOCOL.md).
- [ ] **Update hosting and publishing path.** Verify public raw-file access for
  the fixed `rescueauth-updates` endpoints, versioned APK and release-page URLs,
  and the workflow that signs and publishes the exact manifest bytes. Repository
  existence alone is not acceptance; retain fetch / signature / checksum evidence.
- [ ] **Independent signing-key backup.** Confirm recovery of the established
  Android signing identity from an independent offline backup. Record completion
  without passwords, private keys or secret storage locations in the repository.
- [ ] **Final source regression.** Run the manual full suite and `main`-only FTL
  entry for the chosen revision; record the CNB run and FTL matrix results. Review
  remaining lint warnings. Exercise unlock / lock / background return, account
  and developer CRUD, fresh re-auth, and native / legacy migration on a physical
  device using disposable test data. Local emulator results do not substitute for
  Keystore and biometric behavior on that device.
- [ ] **Candidate identity.** Record the source revision and expected
  `applicationId=com.rescueauth.v2`, `versionName=1.0.0`, `versionCode=10000`;
  confirm the tag/version match and release notes. Only then create the annotated
  production tag via the owner release workflow in §10a.

### After the tag build, before stable-channel publication

- [ ] **Exact signed artifact.** Verify the CI-produced attachment's SHA-256,
  pinned signer, package identity, version, `debuggable=false` and 16 KiB
  alignment. Re-download it from its hosted URL and verify the same bytes.
- [ ] **Signed real-device smoke.** Install that exact artifact; verify legacy
  side-by-side installation, migration / export-import round-trip, biometric /
  screen-lock behavior, Keystore authentication validity and sensitive editor
  access. Record device / OS and results without real vault data.
- [ ] **Publish and verify the update channel.** Publish the versioned APK and
  release notes first. Only after verification publish `latest.json` with its
  matching `.sig`, then verify the client update-check flow from the hosted
  endpoints. Do not overwrite historical APKs or move an existing release tag.
- [ ] **Close the release.** Record the tag, CI run, artifact hash, signer,
  release URL, FTL / device results and update-check result here. Update the
  README, `ROADMAP.md` and `AGENTS.md` to released only after these gates pass.

Cloud execution, secret provisioning and production publication have not been
performed by this documentation update. Clipboard auto-clear and further
accessibility / animation polish remain deferred under `ROADMAP.md §18`.
