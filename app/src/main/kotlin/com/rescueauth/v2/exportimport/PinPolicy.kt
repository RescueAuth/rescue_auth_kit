package com.rescueauth.v2.exportimport

/**
 * Export / Import PIN Product Policy (Issue #1 §9).
 *
 * This is a **Product Policy constant** at the Android UI/orchestration layer
 * only — it is deliberately NOT written into the codec or the package format
 * (PACKAGE_FORMAT.md §PIN representation explicitly leaves PIN charset / length
 * as a product input decision). The codec accepts any PIN string; these rules
 * are the conservative minimum the Export UI enforces before it will derive a
 * package key.
 *
 * ## Export policy (what a user must type when creating a package)
 *
 * - **charset**: ASCII digits `0-9` only.
 * - **minimum length**: [MIN_PIN_LENGTH] (6) — empty and obviously weak short
 *   PINs are rejected.
 * - **maximum length**: [MAX_PIN_LENGTH] (128) — defensive cap for a
 *   human-entered secret.
 * - the user must enter the PIN twice and both entries must match
 *   (checked by [validateExportPin] via the `confirm` argument).
 *
 * ## Import policy (what a user may type to open a package)
 *
 * Import deliberately applies **only a non-empty check**. Any codec-legal PIN
 * from a historical or third-party package must be decodable, and the Android
 * Export UI policy must never reject a package that the codec itself accepts.
 * The length / charset rules above are an **Export UX product decision**, not
 * a package-format requirement (PACKAGE_FORMAT.md §PIN representation).
 *
 * ## Validation location
 *
 * All rule evaluation lives in this single object ([validateExportPin] /
 * [validateImportPin]) so the policy is not scattered across Composable code.
 * The UI layer only collects input and maps a returned [Reason] to a string
 * resource; the ViewModel re-checks the policy as defense-in-depth before any
 * snapshot / encode / write work. The policy is a single, easily changeable
 * constant so P4/P5 can tighten or adjust it without touching the wire format.
 */
object PinPolicy {

    /** Minimum Export PIN length. The codec has no minimum; this is a UX/product floor. */
    const val MIN_PIN_LENGTH = 6

    /** Maximum Export PIN length (defensive — a PIN is a human-entered secret). */
    const val MAX_PIN_LENGTH = 128

    /** Export PIN charset rule: only ASCII digits are allowed. */
    fun isAllowedExportCharacter(c: Char): Boolean = c in '0'..'9'

    /** Result of a PIN policy check. */
    sealed interface Validation {
        /** The PIN is acceptable for the given mode. */
        data object Valid : Validation

        /** The PIN was rejected for a specific [Reason]. */
        data class Invalid(val reason: Reason) : Validation
    }

    /** Why a PIN was rejected (mapped to a user-facing string by the UI). */
    enum class Reason {
        /** The field is empty. */
        EMPTY,

        /** Shorter than [MIN_PIN_LENGTH] (Export only). */
        TOO_SHORT,

        /** Longer than [MAX_PIN_LENGTH] (Export only). */
        TOO_LONG,

        /** Contains a character outside the Export charset (Export only). */
        NON_DIGIT,

        /** The PIN and its confirmation do not match (Export only). */
        MISMATCH,
    }

    /**
     * Validates a per-export PIN. [confirm] is the second entry; pass `null`
     * when no confirmation field exists (the ViewModel defense-in-depth path).
     */
    fun validateExportPin(pin: CharArray, confirm: CharArray?): Validation {
        if (pin.isEmpty()) return Validation.Invalid(Reason.EMPTY)
        if (pin.size < MIN_PIN_LENGTH) return Validation.Invalid(Reason.TOO_SHORT)
        if (pin.size > MAX_PIN_LENGTH) return Validation.Invalid(Reason.TOO_LONG)
        if (!pin.all { isAllowedExportCharacter(it) }) return Validation.Invalid(Reason.NON_DIGIT)
        if (confirm != null && !pin.contentEquals(confirm)) return Validation.Invalid(Reason.MISMATCH)
        return Validation.Valid
    }

    /**
     * Validates a package PIN at import time.
     *
     * Only an empty PIN is rejected (to avoid a useless decode attempt).
     * Length / charset rules are deliberately NOT applied: they are the Export
     * UI's product policy, not a package-format requirement, and import must be
     * able to decode any codec-legal package (including historical short or
     * non-numeric PINs) (PACKAGE_FORMAT.md §PIN representation).
     */
    fun validateImportPin(pin: CharArray): Validation {
        if (pin.isEmpty()) return Validation.Invalid(Reason.EMPTY)
        return Validation.Valid
    }

    /**
     * Backwards-compatible predicate for the Export policy only (no confirm).
     * @see validateExportPin
     */
    fun isValidPin(pin: CharArray): Boolean =
        validateExportPin(pin, null) is Validation.Valid
}
