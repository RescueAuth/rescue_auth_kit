# Phase 4 P6 — Developer Vault Completion Report

**Status**: P6 IMPLEMENTED / PR OPEN
**P1–P5 CLOSED · P6 IMPLEMENTED · P7/P8 NOT STARTED · Phase 5A/5B CLOSED**

P6 completes the Developer Vault **Android daily-use closure**: the final two
Developer Entry types (Android Signing Key, Environment Variable Set) gain
full production CRUD/UI, and all five types are now first-class, usable on the
device. **No new storage architecture** was introduced — P6 reuses the existing
Phase 3C single-table Developer persistence and the Phase 4 P4 Sensitive Action
gate (see §1).

---

## 1. Existing Developer persistence / logical model audit

Confirmed and **reused as-is** (no schema upgrade, no second table):

- **Room**: single `developer_entry` table + typed `payloadJson`
  (`DeveloperEntryEntity`, schema v3). No new SigningKey / EnvVar table.
- **Mapper**: `DeveloperMappers` remains the ONLY bridge between Room and the
  shared logical model; `Composable` never parses `payloadJson`.
- **Logical payload**: `VaultDeveloperEntry` sealed hierarchy already carries all
  five types (`VaultAndroidSigningKey` / `VaultEnvironmentVariableSet` among
  them) with the exact required fields.
- **Repository**: `DeveloperRepository` extended with the two new types; no
  second storage path.
- **ViewModels**: `DeveloperDetailViewModel` / `DeveloperFormViewModel` extended
  for the two new types.
- **SensitiveActionGate / SensitiveActionRequest**: reused with the existing
  `DeveloperField` target (stableId + fieldKey) and one-shot semantics.
- **P5 selective export/import** and **Phase 5B legacy five-type migration** are
  untouched; P6 only adds the UI/CRUD the legacy-mapped rows were waiting for.

---

## 2. Android Signing Key CRUD

`DeveloperRepository.createAndroidSigningKey` / `editAndroidSigningKey` /
`delete` + metadata-only list + detail. Formal fields supported: `title`,
`notes`, `projectName`, `packageName`, `keystoreFileName`, keystore raw bytes
(base64-stored), `storePassword`, `keyAlias`, `keyPassword`.

- **Create**: `Developer → Add → Android Signing Key → metadata/form → Import
  Keystore File (SAF OpenDocument) → bounded read → save`.
- **List**: shows only safe metadata (title / projectName / packageName /
  keystoreFileName / entry type). Never shows storePassword / keyPassword /
  keystore bytes.
- **Detail**: safe metadata + keyAlias (as metadata); storePassword / keyPassword
  masked by default; keystore binary never shown as base64/plaintext.

## 3. Keystore SAF import behavior & size boundary

- **Opaque exact bytes**: the selected file is read into a bounded buffer and
  stored base64-encoded as-is. No normalization, no re-encode, no parse/rebuild.
- **No MIME / extension trust**: SAF `OpenDocument` with accept-any MIME; the
  file type is never a security gate. `.jks` / `.keystore` are UX hints only.
- **Size boundary (reuse, no third rule)**: derived from the shared logical /
  package per-asset contract
  `PackageValidator.MAX_KEYSTORE_BASE64_LENGTH` (12 MiB of base64) into raw
  bytes: `(12 MiB / 4) * 3 = 9 MiB` raw. The importer reads incrementally and
  rejects once the cap + 1 is exceeded (never trusts `OpenableColumns.SIZE`).
- **Empty / cancel / read-error / oversized / choose-another** all map to a
  neutral UX message; no secret bytes leak.
- **No unbounded `readBytes()`** — a 64 KiB incremental buffer is used.

## 4. Keystore exact-byte handling

Stored keystore base64 is produced by `java.util.Base64.getEncoder()`
(RFC 4648) in the repository only. The exact raw bytes are recovered via
`Base64.getDecoder()` for export. Tests assert `byte-for-byte` equality across
persistence close/reopen and package round-trips.

## 5. Signing password re-auth behavior

- `REVEAL_SIGNING_STORE_PASSWORD` / `REVEAL_SIGNING_KEY_PASSWORD` each require a
  **fresh** one-shot re-auth bound to `stableId + "storePassword"/"keyPassword"`.
- `COPY_SIGNING_STORE_PASSWORD` / `COPY_SIGNING_KEY_PASSWORD` each require their
  **own** fresh re-auth (never bypassed by reveal state).
- Entry A auth can never reveal/copy Entry B (target binding).
- No auth cache; every hidden→reveal repeats the fresh re-auth.

## 6. Keystore export behavior

`EXPORT_SIGNING_KEYSTORE` → fresh re-auth → on success the exact raw bytes are
fetched from the repository and emitted as an event carrying the bytes +
suggested filename (`keystoreFileName`); the route then launches SAF
`CreateDocument` and writes them. On **auth cancel / fail / unavailable** no
output document is created and no export buffer is constructed. On write failure
best-effort SAF cleanup is attempted.

Keystore export is deliberately **NOT** wired to the Native `.rakpkg` Package
PIN — `.rakpkg` is an encrypted portable backup; `.jks/.keystore` export is a
raw signing keystore export protected by fresh local re-auth.

## 7. key.properties-like copy

`COPY_SIGNING_KEY_PROPERTIES` → fresh re-auth → in-memory snippet built from the
four stored fields in a neutral format:

```
storeFile=<keystoreFileName>
storePassword=<storePassword>
keyAlias=<keyAlias>
keyPassword=<keyPassword>
```

- No assumption about `../android/` or any Gradle/OS path.
- The snippet is never persisted (no Room / DataStore / file) and is written to
  the clipboard only after a successful one-shot authorization.
- UI hint: *storeFile path may need adjustment in your project.*
- Clipboard auto-clear remains DEFERRED (per P6 scope).

## 8. Environment Variable Set CRUD

`DeveloperRepository.createEnvironmentVariableSet` / `editEnvironmentVariableSet`
/ `delete` + list + detail + edit. Dynamic rows support add / remove / edit
name / edit value. `variables [{name, value}]` ordering is preserved per the
logical model. `name` is metadata; `value` is an opaque secret.

## 9. Variable validation semantics

- Existing logical validator (PackageValidator) requires non-blank variable keys.
- P6 repository adds: **name non-blank**, **exact duplicate names within one set
  → validation error (case-sensitive)**, **case preserved (no uppercase /
  lowercase normalization)**.
- **value is never trimmed / re-cased / rewritten** (opaque secret).
- **name** leading/trailing whitespace is trimmed per the existing form
  convention (matching Generic Secret field labels), documented and test-locked.
- No shell-specific regex is invented; no `[A-Z_][A-Z0-9_]*` requirement.

## 10. Env value reveal/copy security

Each value is hidden by default. `REVEAL_ENV_VAR_VALUE` and `COPY_ENV_VAR_VALUE`
each require a **separate** fresh re-auth. The target binds the Developer entry
`stableId` + an immutable field key `"var:<name>"` (not a list index), so an
edit/reorder can never swap which value a pending auth covers. A prompt pending
while a row is mutated cannot reveal a different variable (the request target is
immutable). No package-format change.

## 11. stableId / edit semantics

Create mints a new stableId; edit preserves stableId + createdAt and only bumps
updatedAt. Metadata edits never delete/recreate. Replace-keystore keeps the SAME
logical Developer stableId — only the payload changes, so future package merges
treat it by the existing Developer conflict semantics.

## 12. Session / plaintext lifecycle

Reveal state is **in-memory only** — never in SavedStateHandle / Bundle /
rememberSaveable / DataStore / Room. Revealed passwords and env values are
cleared on: leaving the detail screen, manual hide, session lock, and process /
Activity recreation. Keystore bytes never enter navigation routes, clipboard,
logs, or accessibility text; the form's temporary import buffer is released on
cancel / successful save / replace.

## 13. Legacy-imported entry compatibility

Phase 5B rows (legacy-mapped Android Signing Key / Env Var Set already persisted
in the DB) open through the P6 repository/UI path: they appear in the Developer
list, open in detail, edit, export keystore, and reveal/copy env values — without
re-running the Legacy parser (integration test `legacy-mapped … opens through
repository read path`).

## 14. Native Full / Developer / Selected package compatibility

Signing Key / Env Var Set created/edited through P6 enter Full Vault,
Developer-only, and Selected-items package exports with exact round-trips:
keystore bytes byte-for-byte equal; env names/order/values fully preserved;
same-stableId changed payload remains a CONFLICT. No change to
`PortablePackageCodec` / package format / `MergePlanner` /
`VaultSnapshotSelector`.

## 15. Accessibility / i18n

New `contentDescription` and labels added in **en + zh-CN** for: Android Signing
Key, Keystore File, Import/Replace/Export Keystore, Store Password, Key Alias,
Key Password, Copy key.properties, Environment Variable Set, Variable Name,
Variable Value, Add Variable, Reveal/Hide/Copy, Authenticate to continue,
destructive confirmations, and file-too-large / read-failure. `contentDescription`
never contains storePassword / keyPassword / env value / keystore bytes /
key.properties snippet. State is never conveyed by color alone.

## 16. Changed files

- `app/…/repository/DeveloperRepository.kt` (Signing Key + Env Var CRUD,
  validation, derived keystore cap)
- `app/…/security/SensitiveAction.kt` (P6 actions)
- `app/…/ui/model/DeveloperUiModels.kt` (AndroidSigningKey / EnvironmentVariableSet detail)
- `app/…/ui/developer/DeveloperDetailViewModel.kt` (P6 reveal/copy/export/properties)
- `app/…/ui/developer/DeveloperFormViewModel.kt` (P6 form types)
- `app/…/ui/developer/DeveloperRoute.kt` (wiring, SAF export launcher)
- `app/…/ui/screens/developer/DeveloperAddSheet.kt` (five types)
- `app/…/ui/screens/developer/DeveloperDetailScreen.kt` (P6 detail sections)
- `app/…/ui/screens/developer/DeveloperFormScreen.kt` (P6 form + SAF keystore import)
- `app/…/ui/screens/developer/DeveloperScreen.kt` (five types in list)
- `app/src/main/res/values/strings.xml`, `values-zh-rCN/strings.xml`
- Tests: `DeveloperSigningEnvRepositoryTest`, `DeveloperSigningEnvDetailViewModelTest`,
  `DeveloperP6PackageIntegrationTest`, updated `DeveloperScreenTest`

## 17. Tests

- **Repository** (P6 §18 tests 1–4, §19 tests 19–26/33): create with synthetic
  keystore bytes, exact-byte close/reopen, edit preserves stableId,
  replace-keystore preserves stableId, list never exposes passwords/bytes,
  oversized rejection, env ordering, close/reopen, edit stableId,
  duplicate-name (case-sensitive), value non-normalization, name
  case-preservation, delete removes full set, requires ≥1 variable,
  no new stableId on edit.
- **Detail ViewModel** (P6 §18 tests 5–18, §19 tests 27–32): reveal/copy each
  require fresh re-auth; store/key password and env value reveal/copy are
  independent; Entry A auth cannot reveal Entry B; keystore export requires
  fresh re-auth, emits exact bytes, cancel creates no output; Copy key.properties
  uses the correct four fields; session lock clears revealed state; process
  recreation does not restore reveal state; env variable A auth cannot reveal/copy
  variable B; env values hidden by default.
- **Package / Legacy integration** (P6 §20 tests 34–45): Signing Key Full /
  Developer / Selected round-trips with byte-for-byte equality; Env Var Full /
  Developer / Selected with names/order/values preserved; legacy-mapped signing
  and env rows open through the P6 path; same-stableId changed payload remains a
  CONFLICT; reimport idempotent.
- Full `:core:test` and `:app:testDebugUnitTest` suites pass.

## 18. Release build status

- `:core:test` ✅
- `:app:testDebugUnitTest` ✅
- `:app:lintDebug` ✅
- `:app:assembleDebug` ✅
- `:app:assembleDebugAndroidTest` ✅
- `:app:assembleRelease` ✅ (unsigned APK allowed)

## 19. FTL expectation

Not run (PR branch; FTL runs only on `main` push with the Firebase secret scope).
No new instrumented test was added that requires a physical device; existing
instrumented tests are unchanged.

## 20. Parallel conflict check

Provider & Account management (a parallel PR) touches Authenticator repository /
UI / Provider-Account hierarchy — P6 does not modify those product semantics and
does not touch `AuthenticatorRoute` / Account management / Provider hierarchy.
Only minimal string / route / ROADMAP / AGENTS / CHANGELOG touchpoints may
overlap; P6 wiring is kept minimal to ease rebase.

## 21. Remaining P7 / P8 scope

- **P7 (NOT STARTED)**: global Search + Pin/Unpin.
- **P8 (NOT STARTED)**: Delete Undo 收口 across all types; P6 keeps destructive
  confirmation for Signing Key / Env Var (no new Undo subsystem).

## 22. Out of scope this round (deliberately NOT implemented)

Search, Pin/Unpin, P8 Delete Undo, Provider/Account management, cloud,
automatic backup, clipboard auto-clear, SSH agent, key generation, APK signing,
keytool automation, project file mutation, Generic arbitrary-file vault,
production signing config.
