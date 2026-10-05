package com.rescueauth.v2.update

/**
 * Build-time trust configuration for the update protocol.
 *
 * The production Ed25519 **public** key is provisioned via the Gradle
 * reviewed public-key file through BuildConfig. For unconfigured inputs, the
 * update check returns NOT_CONFIGURED and the Vault keeps working.
 *
 * This is a RELEASE PROVISIONING boundary: the private key is a CI secret only
 * and is never committed (Issue #20 §8, §27). See PHASE6_L2_REPORT.md.
 */
object UpdateTrustProvider {

    /**
     * Builds the trust config from [encodedPublicKey] (BuildConfig field).
     * A blank / placeholder value means "not configured".
     */
    fun fromBuildConfig(encodedPublicKey: String?): UpdateTrustConfig {
        val value = encodedPublicKey?.trim().orEmpty()
        if (value.isEmpty() || value.startsWith("TODO") || value.startsWith("not-configured")) {
            return UpdateTrustConfig.notConfigured()
        }
        return UpdateTrustConfig(value)
    }
}
