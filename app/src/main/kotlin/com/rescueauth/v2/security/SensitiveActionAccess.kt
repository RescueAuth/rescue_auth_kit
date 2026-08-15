package com.rescueauth.v2.security

/**
 * Global access point for the production **sensitive-action re-auth gate**.
 *
 * [MainActivity] owns the production [SensitiveActionController] (hosted by
 * the Activity) and the [SensitiveActionGate], and registers the gate here so
 * the Compose routes (Export / Import, Developer detail) and their ViewModels
 * can reach the single orchestration path without each creating their own
 * BiometricPrompt (Issue #20 §3: one formal orchestration path).
 *
 * The gate is replaced on every Activity create and dropped on destroy, so a
 * pending authorization never survives Activity/process recreation (Issue #20
 * §6, §15). ViewModels capture the gate at route creation time; on recreation
 * a fresh gate is registered and the routes re-read it.
 *
 * Tests never use this singleton — they inject a fake prompt backend directly
 * into the ViewModel constructor (no real biometric hardware, no production
 * bypass).
 */
object SensitiveActionAccess {

    @Volatile
    var gate: SensitiveActionGate? = null

    /** Drops the registered gate (called on app teardown). */
    fun clear() {
        gate = null
    }
}
