package com.rescueauth.v2.ui.screens.startup

import com.rescueauth.v2.security.DeviceAuthCapability

/**
 * Pure decision model for the unified first-run Backup PIN page (ADR-0015).
 *
 * Every first-run device reaches the same PIN surface. The device only changes
 * whether "set up later" is available, which copy is shown, and which vault
 * creation API is called. No decision here permits a single-slot fallback
 * after a dual-slot creation failure.
 */
data class FirstRunPinMode(
    val showSetUpLater: Boolean,
    val mustSetPinBeforeContinuing: Boolean,
    val copyVariant: Copy,
    val createBranchForPinSubmission: FirstRunCreationBranch,
    val createBranchForSetUpLater: FirstRunCreationBranch,
    val allowSingleSlotFallback: Boolean = false,
) {
    enum class Copy {
        STANDARD,
        NO_SECURE_LOCK,
        WEAK_BIOMETRIC,
    }

    companion object {
        fun from(capability: DeviceAuthCapability): FirstRunPinMode = resolveFirstRunPinMode(
            hasSystemAuth = capability.hasSystemAuth,
            hasWeakBiometricOnly = capability.hasWeakBiometricOnly,
            hasNoSecureLock = capability.hasNoSecureLock,
        )
    }
}

enum class FirstRunCreationBranch {
    DUAL_SLOT,
    BACKUP_PIN_ONLY,
    SYSTEM_ONLY,
}

/**
 * Capability-derived routing. [hasSystemAuth] means Strong biometric or device
 * credential is available (never Weak alone). [hasWeakBiometricOnly] selects
 * copy that avoids claiming Weak biometrics protect the Keystore.
 */
fun resolveFirstRunPinMode(
    hasSystemAuth: Boolean,
    hasWeakBiometricOnly: Boolean,
    hasNoSecureLock: Boolean,
): FirstRunPinMode {
    val copy = when {
        hasNoSecureLock -> FirstRunPinMode.Copy.NO_SECURE_LOCK
        hasWeakBiometricOnly -> FirstRunPinMode.Copy.WEAK_BIOMETRIC
        else -> FirstRunPinMode.Copy.STANDARD
    }
    return FirstRunPinMode(
        showSetUpLater = hasSystemAuth,
        mustSetPinBeforeContinuing = hasNoSecureLock,
        copyVariant = copy,
        createBranchForPinSubmission = if (hasSystemAuth) {
            FirstRunCreationBranch.DUAL_SLOT
        } else {
            FirstRunCreationBranch.BACKUP_PIN_ONLY
        },
        createBranchForSetUpLater = FirstRunCreationBranch.SYSTEM_ONLY,
        allowSingleSlotFallback = false,
    )
}
