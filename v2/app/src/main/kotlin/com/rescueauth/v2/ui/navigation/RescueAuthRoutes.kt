package com.rescueauth.v2.ui.navigation

/**
 * Route definitions for the RescueAuth v2 app shell.
 *
 * Three **top-level product destinations** (per PRODUCT.md / Issue #20):
 * - [AUTHENTICATOR]
 * - [DEVELOPER]
 * - [SETTINGS]
 *
 * Nested routes are declared here as the **navigation contract**:
 * Authenticator: Provider → Account → TOTP / Recovery Codes; Developer:
 * list → entry detail → create/edit form.
 */
object RescueAuthRoutes {
    const val AUTHENTICATOR = "authenticator"
    const val DEVELOPER = "developer"
    const val SETTINGS = "settings"

    /** Phase 4 P1: Add TOTP sheet hosted above the Authenticator screen. */
    const val AUTHENTICATOR_ADD = "authenticator/add"

    // --- Phase 3D: Export / Import (Backup / Transfer) ---
    const val EXPORT = "export"
    const val IMPORT = "import"

    // --- Phase 5B: Legacy v1 `.rakvault` import (separate entry point) ---
    const val LEGACY_IMPORT = "legacy-import"

    // --- Authenticator structure ---
    // Phase 4 P3: the Account detail destination is wired (Recovery Codes).
    const val AUTHENTICATOR_ACCOUNT = "authenticator/account/{accountId}"
    const val AUTHENTICATOR_TOTP = "authenticator/totp/{credentialId}"
    const val AUTHENTICATOR_RECOVERY = "authenticator/recovery/{recoverySetId}"

    // --- Developer structure (Phase 4 P4) ---
    /** Detail of one Developer entry (by stableId). */
    const val DEVELOPER_ENTRY = "developer/entry/{entryId}"
    /** Create (ARG_EDIT_STABLE_ID = null) or edit (stableId set) form. */
    const val DEVELOPER_FORM = "developer/form?editStableId={editStableId}&type={formType}"

    // Argument keys for routes.
    const val ARG_PROVIDER_ID = "providerId"
    const val ARG_ACCOUNT_ID = "accountId"
    const val ARG_CREDENTIAL_ID = "credentialId"
    const val ARG_RECOVERY_SET_ID = "recoverySetId"
    const val ARG_ENTRY_ID = "entryId"
    const val ARG_EDIT_STABLE_ID = "editStableId"
    const val ARG_FORM_TYPE = "formType"

    /** Builds the Developer detail route for [stableId]. */
    fun developerEntry(stableId: String): String = "developer/entry/$stableId"

    /** Builds the Developer create route for a new entry of [type]. */
    fun developerAdd(type: String): String =
        "developer/form?editStableId=&type=$type"

    /** Builds the Developer edit route for [stableId]. */
    fun developerEdit(stableId: String): String =
        "developer/form?editStableId=$stableId&type=API_CREDENTIAL"
}
