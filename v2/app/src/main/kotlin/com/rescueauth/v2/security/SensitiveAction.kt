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
    /** Export the entire Vault as an encrypted portable package. */
    EXPORT_FULL_VAULT,

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
}
