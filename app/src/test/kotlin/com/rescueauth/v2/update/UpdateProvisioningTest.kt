package com.rescueauth.v2.update

import com.rescueauth.v2.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class UpdateProvisioningTest {
    @Test fun compiledTrustVerifiesProvisioningProofAndRejectsTampering() {
        val key = java.util.Base64.getDecoder().decode(BuildConfig.UPDATE_PUBLIC_KEY)
        assertEquals(32, key.size)
        val verifier = UpdateManifestVerifier(key)
        // Public proof only; deliberately not a deployable update manifest.
        val proof = "RescueAuth update signing provisioning proof 2026-10-05; not an update manifest.\n".toByteArray()
        val signature = "kIqg/vCLp94naAmuQbHAKlRy+nICPUFnAQeCnsW3PfYt3B+9siJQQ1K7ckr9Pr6PXc4LnT0ozk+/HKX16B1EDw=="
        assertTrue(verifier.verify(proof, signature) is UpdateManifestVerifier.Result.Valid)
        proof[0] = 'X'.code.toByte()
        assertTrue(verifier.verify(proof, signature) is UpdateManifestVerifier.Result.Invalid)
    }

    @Test fun updateEndpointsAreFixedGithubReleaseAssets() {
        assertEquals("https://github.com/RescueAuth/rescue_auth_kit/releases/download/android-stable/latest.json", UpdateManifestSource.MANIFEST_URL)
        assertEquals(UpdateManifestSource.MANIFEST_URL + ".sig", UpdateManifestSource.SIGNATURE_URL)
    }
}
