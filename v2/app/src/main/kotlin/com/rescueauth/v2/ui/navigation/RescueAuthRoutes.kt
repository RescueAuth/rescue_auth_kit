package com.rescueauth.v2.ui.navigation

/**
 * Route definitions for the RescueAuth v2 app shell.
 *
 * Three **top-level product destinations** (per PRODUCT.md / Issue #20):
 * - [AUTHENTICATOR]
 * - [DEVELOPER]
 * - [SETTINGS]
 *
 * Nested routes are declared here as the **future navigation contract**
 * (Authenticator: Provider → Account → TOTP / Recovery Codes; Developer:
 * five entry types). They are not wired into the NavHost in this foundation PR
 * — no new product module is added, and the detail screens belong to later
 * vertical slices.
 */
object RescueAuthRoutes {
    const val AUTHENTICATOR = "authenticator"
    const val DEVELOPER = "developer"
    const val SETTINGS = "settings"

    /** Phase 4 P1: Add TOTP sheet hosted above the Authenticator screen. */
    const val AUTHENTICATOR_ADD = "authenticator/add"

    // --- Authenticator future structure (contract only) ---
    const val AUTHENTICATOR_ACCOUNT = "authenticator/account/{providerId}/{accountId}"
    const val AUTHENTICATOR_TOTP = "authenticator/totp/{credentialId}"
    const val AUTHENTICATOR_RECOVERY = "authenticator/recovery/{recoverySetId}"

    // --- Developer future structure (contract only) ---
    const val DEVELOPER_ENTRY = "developer/entry/{entryId}"

    // Argument keys for future routes.
    const val ARG_PROVIDER_ID = "providerId"
    const val ARG_ACCOUNT_ID = "accountId"
    const val ARG_CREDENTIAL_ID = "credentialId"
    const val ARG_RECOVERY_SET_ID = "recoverySetId"
    const val ARG_ENTRY_ID = "entryId"
}
