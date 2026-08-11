# RescueAuth v2 — Release Provisioning

> **Status: Release Provisioning Step 1 — App Identity / Version + Android
> Production Signing Infrastructure = IMPLEMENTED / PR OPEN**
>
> This is the first step toward the first public RescueAuth release. It **freezes
> the app identity and version** and **builds the production signing
> infrastructure**. It does **NOT** generate a production key, does NOT publish
> an APK, and does NOT run FTL.

---

## 1. Naming conventions — v2 generation name vs. release version

The internal/project name **RescueAuth v2** (also written `v2 rewrite`,
`V2.0 FEATURE COMPLETE`) is a **second-generation architecture / rewrite
generation** name. It is **not** the Android `versionName`.

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

> **REAL-DEVICE SIDE-BY-SIDE SMOKE = PENDING** — validated dynamically in a
> later signed-release smoke, not in this PR.

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

> Production Update Ed25519 key provisioning is a **later** release
> provisioning step and is **not** done in this PR.

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

1. a local **`v2/keystore.properties`** file (gitignored; template in
   `v2/keystore.properties.example`), or
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

## 9. Production signing key generation

The real production signing key is generated **offline by the user in a trusted
local environment** — CodeBuddy / CI does **not** run it. This section only
documents the operation.

```bash
# Generate the RescueAuth production signing keystore (OFFLINE, trusted host).
# Adjust -storepass / -keypass to strong random values. DO NOT commit these.
# Validity: long-term (>= 25 years). Algorithm: RSA 4096 / SHA-256 (modern
# Android/JDK production compatibility).
keytool -genkeypair \
  -v \
  -keystore rescueauth-release.jks \
  -alias rescueauth-v2 \
  -keyalg RSA \
  -keysize 4096 \
  -sigalg SHA256withRSA \
  -validity 10950 \
  -storepass CHANGE_ME \
  -keypass CHANGE_ME \
  -dname "CN=RescueAuth, OU=Mobile, O=RescueAuth, L=, S=, C=US"
```

- **Alias chosen: `rescueauth-v2`** (stable, simple). Though the public release
  version starts at `1.0.0`, the internal alias may use the generation name `v2`.
- Keep the keystore in a trusted, backed-up location **outside** the repo.
- Record the absolute path and the four config values into the local
  (gitignored) `v2/keystore.properties`, or export the four env vars on the
  production build host.

> `storePassword` / `keyPassword` in the command above are placeholders only.

---

## 10. CI behavior

Without production signing secrets, CI continues to support:

```
./gradlew :core:test
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest
./gradlew :app:assembleRelease
```

- `:app:assembleRelease` runs **unsigned** (no debug signing).
- A future production job injects an external keystore + secrets and produces a
  **signed** release artifact. Real CNB secrets are **not** configured in this
  PR.

---

## 11. Repository hygiene

A scan of the repo found **no production signing secrets**:

- no `*.jks` / `*.keystore` / `*.p12` / `*.pfx` committed,
- no `keystore.properties` committed,
- no `storePassword` / `keyPassword` literals in build config,
- no `signingConfig = signingConfigs.debug` fallback,
- no production Update private key committed.

The `storePassword` / `keyPassword` identifiers found in `v2/app/...` source are
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

## 15. Release status

| Item | Status |
|------|--------|
| RescueAuth v2 (generation name) | second-generation rewrite / project name |
| New App release version | **1.0.0** |
| V2.0 FEATURE COMPLETE | YES |
| V2.0 RELEASED | NO |
| Release Provisioning Step 1 (identity/version/signing infra) | IMPLEMENTED / PR OPEN |
| Production Android signing key | **NOT GENERATED** |
| Production Update Ed25519 key | **NOT GENERATED** |
| `rescueauth-updates` production infra | PENDING |
| Real-device side-by-side smoke | PENDING |
| FTL | PENDING |
