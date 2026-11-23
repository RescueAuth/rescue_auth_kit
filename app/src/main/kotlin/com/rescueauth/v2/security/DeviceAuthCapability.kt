package com.rescueauth.v2.security

import androidx.biometric.BiometricManager

/**
 * Structured snapshot of the authentication capabilities available on the
 * current device.
 *
 * The detector is pure Kotlin and takes an injected [authenticatorChecker]:
 * the caller maps a BiometricManager authenticator constant to whether
 * canAuthenticate returned BIOMETRIC_SUCCESS. This keeps detection testable
 * without Robolectric or real biometric hardware.
 *
 * Security contract:
 * - BIOMETRIC_STRONG and DEVICE_CREDENTIAL are the only authenticators that
 *   can release an auth-bound Keystore key (system slot).
 * - BIOMETRIC_WEAK is recorded for UI branching only, never as a system slot
 *   and never as a BiometricPrompt allowed authenticator for Keystore auth.
 * - The type stores capability only; no UI wording lives in the detection
 *   layer.
 */
data class DeviceAuthCapability(
    val strongBiometricAvailable: Boolean,
    val weakBiometricAvailable: Boolean,
    val deviceCredentialAvailable: Boolean,
) {
    /** Strong biometric or system device credential exists. */
    val hasSystemAuth: Boolean
        get() = strongBiometricAvailable || deviceCredentialAvailable

    /** Alias used by slot-oriented callers. */
    val canUseSystemSlot: Boolean
        get() = hasSystemAuth

    /** A weak biometric exists while no strong biometric is available. */
    val hasWeakBiometricOnly: Boolean
        get() = weakBiometricAvailable && !strongBiometricAvailable

    /** No system authentication of any kind exists. */
    val hasNoSecureLock: Boolean
        get() = !strongBiometricAvailable && !weakBiometricAvailable && !deviceCredentialAvailable

    /** Any system authentication exists, including weak biometric only. */
    val hasSecureLock: Boolean
        get() = !hasNoSecureLock

    /**
     * Allowed authenticators for Keystore-bound prompts; null when no system
     * authenticator is usable. BIOMETRIC_WEAK is deliberately excluded.
     */
    val systemAuthenticatorMask: Int?
        get() {
            var mask = 0
            if (strongBiometricAvailable) {
                mask = mask or BiometricManager.Authenticators.BIOMETRIC_STRONG
            }
            if (deviceCredentialAvailable) {
                mask = mask or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            }
            return if (mask == 0) null else mask
        }

    companion object {
        /**
         * Builds a capability from an injected checker. [authenticatorChecker]
         * receives BiometricManager authenticator constants and returns true
         * when that authenticator canAuthenticate == BIOMETRIC_SUCCESS.
         */
        fun fromAuthenticatorCheck(
            authenticatorChecker: (Int) -> Boolean,
        ): DeviceAuthCapability = DeviceAuthCapability(
            strongBiometricAvailable = authenticatorChecker(
                BiometricManager.Authenticators.BIOMETRIC_STRONG,
            ),
            weakBiometricAvailable = authenticatorChecker(
                BiometricManager.Authenticators.BIOMETRIC_WEAK,
            ),
            deviceCredentialAvailable = authenticatorChecker(
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            ),
        )

        /**
         * Production adapter: queries Android's BiometricManager through an
         * injectable provider. Any platform failure resolves to the same
         * explicit device-credential fallback used by the legacy prompt code.
         */
        fun fromPlatformManager(
            managerProvider: () -> BiometricManager,
        ): DeviceAuthCapability = try {
            val manager = managerProvider()
            fromAuthenticatorCheck { authenticator ->
                manager.canAuthenticate(authenticator) == BiometricManager.BIOMETRIC_SUCCESS
            }
        } catch (e: Exception) {
            platformQueryFailureFallback()
        }

        /** Fallback capability when the platform query throws. */
        fun platformQueryFailureFallback(): DeviceAuthCapability = DeviceAuthCapability(
            strongBiometricAvailable = false,
            weakBiometricAvailable = false,
            deviceCredentialAvailable = true,
        )
    }
}
