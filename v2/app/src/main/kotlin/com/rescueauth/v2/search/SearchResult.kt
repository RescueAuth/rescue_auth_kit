package com.rescueauth.v2.search

/**
 * P7 Global Search — safe, type-labeled result model.
 *
 * **This object is the only thing a search result can ever be.** It is a
 * product/UI contract and is deliberately secret-free by construction:
 *
 * - [title] / [subtitle] are safe, non-secret metadata only.
 * - [navigationId] is a stable/current DB identity used to navigate, never a
 *   secret and never a search string key.
 * - There is no field here that can carry a TOTP secret, recovery plaintext,
 *   API key, SSH key, store/key password, env value or generic secret value.
 *
 * Because the model has no secret-bearing fields, `toString()`, debug logs,
 * Compose semantics and `contentDescription` are all safe without a post-hoc
 * string filter.
 */
sealed interface SearchResult {

    /** Safe primary line, e.g. "GitHub" or "deploy key". */
    val title: String

    /** Safe secondary context line, e.g. "alice@example.com". */
    val subtitle: String?

    /** The stable/current navigation identity (real DB id / stableId / name). */
    val navigationId: String

    /** Whether the underlying item is pinned (Account tier ranking helper). */
    val pinned: Boolean

    /** A stable result type label for grouping the result list by kind. */
    val typeLabel: String

    data class Provider(
        override val title: String,
        override val subtitle: String?,
        override val navigationId: String,
        override val pinned: Boolean = false,
        override val typeLabel: String = "provider",
        val accountCount: Int,
    ) : SearchResult

    data class Account(
        override val title: String,
        override val subtitle: String?,
        override val navigationId: String,
        override val pinned: Boolean,
        override val typeLabel: String = "account",
        val providerName: String,
    ) : SearchResult

    data class Totp(
        override val title: String,
        override val subtitle: String?,
        override val navigationId: String,
        override val pinned: Boolean,
        override val typeLabel: String = "totp",
        val accountId: String,
    ) : SearchResult

    data class RecoverySet(
        override val title: String,
        override val subtitle: String?,
        override val navigationId: String,
        override val pinned: Boolean,
        override val typeLabel: String = "recovery",
        val accountId: String,
    ) : SearchResult

    data class Developer(
        override val title: String,
        override val subtitle: String?,
        override val navigationId: String,
        override val pinned: Boolean = false,
        override val typeLabel: String = "developer",
        val developerType: String,
    ) : SearchResult
}
