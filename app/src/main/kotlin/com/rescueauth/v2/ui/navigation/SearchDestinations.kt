package com.rescueauth.v2.ui.navigation

import android.net.Uri
import com.rescueauth.v2.search.SearchResult

/** Navigation uses safe identities, never a query, code or secret payload. */
internal fun SearchResult.vaultDestination(): String = when (this) {
    is SearchResult.Provider -> RescueAuthRoutes.AUTHENTICATOR_PROVIDER
        .replace("{providerName}", Uri.encode(navigationId))
    is SearchResult.Account -> RescueAuthRoutes.AUTHENTICATOR_ACCOUNT_DETAIL
        .replace("{accountId}", Uri.encode(navigationId))
    is SearchResult.Totp -> RescueAuthRoutes.AUTHENTICATOR_ACCOUNT_DETAIL
        .replace("{accountId}", Uri.encode(accountId))
    is SearchResult.RecoverySet -> RescueAuthRoutes.AUTHENTICATOR_ACCOUNT_RECOVERY
        .replace("{accountId}", Uri.encode(accountId))
    is SearchResult.Developer -> RescueAuthRoutes.developerEntry(Uri.encode(navigationId))
}
