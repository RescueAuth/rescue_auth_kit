package com.rescueauth.v2.ui.startup

import com.rescueauth.v2.session.VaultUnlockOutcome
import com.rescueauth.v2.ui.screens.startup.FirstRunCreationBranch
import com.rescueauth.v2.ui.screens.startup.FirstRunPinMode
import com.rescueauth.v2.ui.screens.startup.resolveFirstRunPinMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * First-run PIN page seam. The decision is pure Kotlin: device capability plus
 * the user's action select exactly one vault-creation branch, and no failure
 * may silently degrade to a different branch.
 */
class UnifiedStartupBackupPinDecisionTest {

    @Test
    fun strongSystemAuthShowsLaterAndPINUsesDualSlot() {
        val mode = resolveFirstRunPinMode(
            hasSystemAuth = true,
            hasWeakBiometricOnly = false,
            hasNoSecureLock = false,
        )
        assertEquals(true, mode.showSetUpLater)
        assertEquals(false, mode.mustSetPinBeforeContinuing)
        assertEquals(FirstRunPinMode.Copy.STANDARD, mode.copyVariant)
        assertEquals(FirstRunCreationBranch.DUAL_SLOT, mode.createBranchForPinSubmission)
    }

    @Test
    fun noSecureLockHidesLaterAndBlocksUntilPINIsSet() {
        val mode = resolveFirstRunPinMode(
            hasSystemAuth = false,
            hasWeakBiometricOnly = false,
            hasNoSecureLock = true,
        )
        assertEquals(false, mode.showSetUpLater)
        assertEquals(true, mode.mustSetPinBeforeContinuing)
        assertEquals(FirstRunPinMode.Copy.NO_SECURE_LOCK, mode.copyVariant)
        assertEquals(FirstRunCreationBranch.BACKUP_PIN_ONLY, mode.createBranchForPinSubmission)
    }

    @Test
    fun weakOnlyDeviceUsesDeviceCredentialLanguageAndDualSlot() {
        // Weak biometric + device credential: System auth comes from the device
        // credential only; Weak must never be advertised as key protection.
        val mode = resolveFirstRunPinMode(
            hasSystemAuth = true,
            hasWeakBiometricOnly = true,
            hasNoSecureLock = false,
        )
        assertEquals(true, mode.showSetUpLater)
        assertEquals(FirstRunPinMode.Copy.WEAK_BIOMETRIC, mode.copyVariant)
        assertEquals(FirstRunCreationBranch.DUAL_SLOT, mode.createBranchForPinSubmission)
    }

    @Test
    fun systemAuthWithoutPINUsesSystemOnlyBranch() {
        val mode = resolveFirstRunPinMode(
            hasSystemAuth = true,
            hasWeakBiometricOnly = false,
            hasNoSecureLock = false,
        )
        assertEquals(FirstRunCreationBranch.SYSTEM_ONLY, mode.createBranchForSetUpLater)
    }

    @Test
    fun dualSlotFailureAlwaysReturnsFailureToPinPageWithoutFallback() {
        val mode = resolveFirstRunPinMode(true, false, false)
        val failure = VaultUnlockOutcome.VaultCorrupt
        assertEquals(FirstRunCreationBranch.DUAL_SLOT, mode.createBranchForPinSubmission)
        assertEquals(VaultUnlockOutcome.VaultCorrupt, failure)
        assertEquals(false, mode.allowSingleSlotFallback)
    }
}