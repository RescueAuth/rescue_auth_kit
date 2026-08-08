package com.rescueauth.v2.exportimport

/**
 * PIN policy for the Phase 3D manual Export flow (Issue #1 §9).
 *
 * This is a **Product Policy constant** at the Android UI/orchestration layer
 * only — it is deliberately NOT written into the codec or the package format
 * (PACKAGE_FORMAT.md §PIN representation explicitly leaves PIN charset / length
 * as a product input decision). The codec accepts any PIN string; this policy
 * is the conservative minimum the Export UI enforces before it will derive a
 * package key.
 *
 * The policy is a single, easily changeable constant so P4/P5 can tighten or
 * adjust it without touching the wire format.
 */
object PinPolicy {

    /** Minimum PIN length. The codec has no minimum; this is a UX/product floor. */
    const val MIN_PIN_LENGTH = 6

    /** Maximum PIN length (defensive — a PIN is a human-entered secret). */
    const val MAX_PIN_LENGTH = 128

    /** Only digits are allowed for the per-export PIN (simple, safe, testable). */
    fun isValidPin(pin: CharArray): Boolean {
        if (pin.size < MIN_PIN_LENGTH || pin.size > MAX_PIN_LENGTH) return false
        return pin.all { it.isDigit() }
    }
}
