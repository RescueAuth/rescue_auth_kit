package com.rescueauth.v2.security

import androidx.biometric.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device authentication capability tiers. The detector uses an injected
 * authenticator checker so tests never touch real biometric hardware.
 */
class DeviceAuthCapabilityTest {

    /**
     * Maps BiometricManager authenticator constants to availability using the
     * same semantics as canAuthenticate(authenticator) == BIOMETRIC_SUCCESS.
     */
    private fun detectorFor(
        strong: Boolean = false,
        weak: Boolean = false,
        deviceCredential: Boolean = false,
    ): DeviceAuthCapability = DeviceAuthCapability.fromAuthenticatorCheck { authenticator ->
        when (authenticator) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG -> strong
            BiometricManager.Authenticators.BIOMETRIC_WEAK -> weak
            BiometricManager.Authenticators.DEVICE_CREDENTIAL -> deviceCredential
            else -> false
        }
    }

    @Test
    fun `strong biometric provides system slot capability`() {
        val capability = detectorFor(strong = true, deviceCredential = false)

        assertTrue(capability.strongBiometricAvailable)
        assertTrue(capability.hasSystemAuth)
        assertTrue(capability.canUseSystemSlot)
        assertTrue(capability.hasSecureLock)
        assertFalse(capability.hasWeakBiometricOnly)
        assertFalse(capability.hasNoSecureLock)
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
            capability.systemAuthenticatorMask,
        )
    }

    @Test
    fun `device credential alone provides system slot capability`() {
        val capability = detectorFor(deviceCredential = true)

        assertFalse(capability.strongBiometricAvailable)
        assertTrue(capability.deviceCredentialAvailable)
        assertTrue(capability.hasSystemAuth)
        assertTrue(capability.canUseSystemSlot)
        assertTrue(capability.hasSecureLock)
        assertFalse(capability.hasWeakBiometricOnly)
        assertEquals(
            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            capability.systemAuthenticatorMask,
        )
    }

    @Test
    fun `weak biometric alone is never treated as system slot capability`() {
        val capability = detectorFor(weak = true, deviceCredential = false)

        assertTrue(capability.weakBiometricAvailable)
        assertFalse(capability.strongBiometricAvailable)
        assertFalse(capability.hasSystemAuth)
        assertFalse(capability.canUseSystemSlot)
        assertTrue(capability.hasWeakBiometricOnly)
        assertFalse(capability.hasNoSecureLock)
        assertTrue(capability.hasSecureLock)
        assertNull(capability.systemAuthenticatorMask)
    }

    @Test
    fun `weak biometric with device credential still reports weak only and system slot via credential`() {
        val capability = detectorFor(weak = true, deviceCredential = true)

        assertTrue(capability.hasWeakBiometricOnly)
        assertTrue(capability.hasSystemAuth)
        assertTrue(capability.canUseSystemSlot)
        assertEquals(
            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            capability.systemAuthenticatorMask,
        )
    }

    @Test
    fun `no authenticator available reports no secure lock`() {
        val capability = detectorFor()

        assertFalse(capability.hasSystemAuth)
        assertFalse(capability.canUseSystemSlot)
        assertFalse(capability.hasWeakBiometricOnly)
        assertTrue(capability.hasNoSecureLock)
        assertFalse(capability.hasSecureLock)
        assertNull(capability.systemAuthenticatorMask)
    }

    @Test
    fun `strong and weak together keep weak only false`() {
        val capability = detectorFor(strong = true, weak = true, deviceCredential = false)

        assertTrue(capability.strongBiometricAvailable)
        assertTrue(capability.weakBiometricAvailable)
        assertTrue(capability.hasSystemAuth)
        assertFalse(capability.hasWeakBiometricOnly)
        assertFalse(capability.hasNoSecureLock)
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
            capability.systemAuthenticatorMask,
        )
    }
}
