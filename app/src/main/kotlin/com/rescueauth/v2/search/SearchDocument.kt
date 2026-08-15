package com.rescueauth.v2.search

import com.rescueauth.v2.search.SearchMatcher.MatchRank

/**
 * P7 Global Search — an in-memory, **safe** projection document.
 *
 * One [SearchDocument] is produced per searchable item (a Provider grouping,
 * an Account, a TOTP credential, a Recovery Code Set, or a Developer entry).
 * It carries only:
 *
 * - a stable/current navigation identity ([navigationId]),
 * - a result [type],
 * - safe [title] / [subtitle],
 * - and an explicit [searchableTokens] list of **non-secret** metadata.
 *
 * The document is the *only* input to the in-memory matcher. It is designed so
 * secret-bearing fields cannot be attached: there is no generic payload field
 * and the builder ([SearchIndex.build]) picks safe metadata explicitly.
 */
data class SearchDocument(
    val type: SearchType,
    val navigationId: String,
    val title: String,
    val subtitle: String?,
    val searchableTokens: List<String>,
    val pinned: Boolean = false,
    /** Extra identity needed for navigation (e.g. owning accountId for TOTP). */
    val accountId: String? = null,
    val developerType: String? = null,
    val providerName: String? = null,
    val accountCount: Int = 0,
) {
    /** Produces the safe, secret-free UI result for this document. */
    fun toResult(): SearchResult = when (type) {
        SearchType.PROVIDER -> SearchResult.Provider(
            title = title,
            subtitle = subtitle,
            navigationId = navigationId,
            pinned = pinned,
            accountCount = accountCount,
        )
        SearchType.ACCOUNT -> SearchResult.Account(
            title = title,
            subtitle = subtitle,
            navigationId = navigationId,
            pinned = pinned,
            providerName = providerName.orEmpty(),
        )
        SearchType.TOTP -> SearchResult.Totp(
            title = title,
            subtitle = subtitle,
            navigationId = navigationId,
            pinned = pinned,
            accountId = accountId.orEmpty(),
        )
        SearchType.RECOVERY_SET -> SearchResult.RecoverySet(
            title = title,
            subtitle = subtitle,
            navigationId = navigationId,
            pinned = pinned,
            accountId = accountId.orEmpty(),
        )
        SearchType.DEVELOPER -> SearchResult.Developer(
            title = title,
            subtitle = subtitle,
            navigationId = navigationId,
            pinned = pinned,
            developerType = developerType.orEmpty(),
        )
    }

    /** Best match rank for [query], or null if the document does not match. */
    fun match(query: String): MatchRank? =
        SearchMatcher.matchRank(query, searchableTokens)
}

enum class SearchType {
    PROVIDER,
    ACCOUNT,
    TOTP,
    RECOVERY_SET,
    DEVELOPER,
}
