package com.rescueauth.v2.security

/**
 * A high-risk sensitive action that requires **fresh** Biometric / Device
 * Credential re-authentication even while the Vault is already unlocked
 * (ROADMAP §5.6 / ADR-0006).
 *
 * ## Freshness / one-shot semantics (Phase 4 P4)
 *
 * A successful re-auth authorizes **exactly one** pending [SensitiveAction].
 * The [SensitiveActionGate] consumes that authorization immediately when the
 * pending action is executed, so an authorization can never leak across
 * actions or over time:
 *
 * - no 5-minute freshness window, no "already verified this session" cache,
 *   no global `authenticated = true`;
 * - each high-risk action triggers exactly one new BiometricPrompt.
 *
 * This enum is **not** part of the portable package format and is **not**
 * persisted anywhere (no SavedStateHandle / Bundle / DataStore / Room /
 * navigation arguments). It only ever lives in memory between the moment the
 * user requests a sensitive action and the moment it is authorized / denied.
 */
enum class SensitiveAction {
    /**
     * Export a portable package (any scope: Entire Vault / Authenticator /
     * Developer / Selected Items). Renamed from the earlier EXPORT_FULL_VAULT
     * because P5 makes every `.rakpkg` export scope a sensitive action
     * (Issue #20 §7).
     */
    EXPORT_PACKAGE,

    /** Reveal an API Credential secret (apiKey / apiSecret). */
    REVEAL_API_SECRET,

    /** Copy an API Credential secret to the clipboard. */
    COPY_API_SECRET,

    /** Reveal an SSH private key. */
    REVEAL_SSH_PRIVATE_KEY,

    /** Copy an SSH private key to the clipboard. */
    COPY_SSH_PRIVATE_KEY,

    /** Reveal an SSH passphrase. */
    REVEAL_SSH_PASSPHRASE,

    /** Copy an SSH passphrase to the clipboard. */
    COPY_SSH_PASSPHRASE,

    /** Reveal a Generic Secret field value. */
    REVEAL_GENERIC_SECRET,

    /** Copy a Generic Secret field value to the clipboard. */
    COPY_GENERIC_SECRET,

    // --- Phase 4 P6: Android Signing Key & Environment Variable Set ---

    /** Reveal an Android Signing Key store password. */
    REVEAL_SIGNING_STORE_PASSWORD,

    /** Copy an Android Signing Key store password to the clipboard. */
    COPY_SIGNING_STORE_PASSWORD,

    /** Reveal an Android Signing Key key password. */
    REVEAL_SIGNING_KEY_PASSWORD,

    /** Copy an Android Signing Key key password to the clipboard. */
    COPY_SIGNING_KEY_PASSWORD,

    /** Export the raw Android Signing Key keystore bytes via SAF. */
    EXPORT_SIGNING_KEYSTORE,

    /** Copy a key.properties-like snippet (all four signing fields). */
    COPY_SIGNING_KEY_PROPERTIES,

    /** Reveal an Environment Variable Set value. */
    REVEAL_ENV_VAR_VALUE,

    /** Copy an Environment Variable Set value to the clipboard. */
    COPY_ENV_VAR_VALUE,
}
