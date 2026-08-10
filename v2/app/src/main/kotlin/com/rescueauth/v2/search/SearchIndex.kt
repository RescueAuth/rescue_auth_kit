package com.rescueauth.v2.search

import com.rescueauth.v2.domain.AuthAccount
import com.rescueauth.v2.domain.RecoveryCodeSet
import com.rescueauth.v2.domain.TotpCredential
import com.rescueauth.v2.repository.DeveloperSearchMetadata
import com.rescueauth.v2.search.SearchMatcher.MatchRank

/**
 * P7 Global Search — builds the in-memory **safe projection** index and runs
 * deterministic matching.
 *
 * ## Why in-memory
 *
 * This vault is a small personal local dataset (P7 §8). We therefore build a
 * pure in-memory [SearchDocument] projection from the real Room Flows and
 * match against it with [SearchMatcher]. There is **no** plaintext search
 * index, no DataStore index, no external DB, no FTS table, no AppSearch and no
 * background indexing service. Secrets are never projected into a document.
 *
 * ## What is searchable (P7 §6)
 *
 * - **Provider**: the `serviceName` grouping name.
 * - **Account**: account name + provider name.
 * - **TOTP**: provider / account display context only (no algorithm/digits/
 *   period text unless already present as display — kept minimal).
 * - **Recovery Set**: safe set title + provider/account context.
 * - **Developer**: per-type safe metadata (title / projectName / packageName /
 *   keystoreFileName / keyAlias / serviceName / accountName / keyName /
 *   variable names / generic field labels).
 *
 * ## What is NEVER searchable (P7 §7)
 *
 * TOTP secretBase32, current TOTP code, recovery plaintext, apiKey, apiSecret,
 * SSH private key, SSH passphrase, signing storePassword/keyPassword, keystore
 * bytes/base64, key.properties snippet, env value, generic value, SSH
 * publicKey, and free-form Developer notes. We never run a `payloadJson LIKE`
 * query.
 *
 * ## Ranking (P7 §10)
 *
 * Within a query, each document keeps its best [MatchRank]. Results are grouped
 * by type in a fixed order and sorted by rank (best first), then by pinned
 * Account, then by stable title. This is deterministic and never records usage
 * or history.
 */
class SearchIndex(
    private val accounts: List<AuthAccount>,
    private val totpCredentials: List<TotpCredential>,
    private val recoverySets: List<RecoveryCodeSet>,
    private val developerMetadata: List<DeveloperSearchMetadata>,
) {
    private val documents: List<SearchDocument> = buildDocuments()

    private fun buildDocuments(): List<SearchDocument> {
        val out = ArrayList<SearchDocument>()
        val accountById = accounts.associateBy { it.id }

        // Provider = serviceName grouping (P7 §16). Only one result per
        // distinct serviceName.
        val providerAccounts = accounts.groupBy { it.serviceName }
        for ((service, group) in providerAccounts) {
            out += SearchDocument(
                type = SearchType.PROVIDER,
                navigationId = service,
                title = service,
                subtitle = null,
                searchableTokens = listOf(service),
                pinned = false,
                accountCount = group.size,
            )
        }

        // Account.
        for (a in accounts) {
            out += SearchDocument(
                type = SearchType.ACCOUNT,
                navigationId = a.id,
                title = a.accountName,
                subtitle = a.serviceName,
                searchableTokens = listOf(a.accountName, a.serviceName),
                pinned = a.favorite,
                providerName = a.serviceName,
            )
        }

        // TOTP — display context only.
        for (t in totpCredentials) {
            val account = accountById[t.accountId]
            val provider = account?.serviceName ?: "Unknown"
            val accountName = account?.accountName ?: ""
            out += SearchDocument(
                type = SearchType.TOTP,
                navigationId = t.id,
                title = provider,
                subtitle = accountName,
                searchableTokens = listOf(provider, accountName),
                pinned = account?.favorite ?: false,
                accountId = t.accountId,
            )
        }

        // Recovery Set — safe title + provider/account context.
        for (set in recoverySets) {
            val account = accountById[set.accountId]
            val provider = account?.serviceName ?: ""
            val accountName = account?.accountName ?: ""
            out += SearchDocument(
                type = SearchType.RECOVERY_SET,
                navigationId = set.id,
                title = set.title,
                subtitle = listOf(provider, accountName).filter { it.isNotEmpty() }.joinToString(" · "),
                searchableTokens = listOf(set.title, provider, accountName),
                pinned = account?.favorite ?: false,
                accountId = set.accountId,
            )
        }

        // Developer — safe metadata only.
        for (d in developerMetadata) {
            val tokens = buildList {
                add(d.title)
                d.projectName?.let { add(it) }
                d.packageName?.let { add(it) }
                d.keystoreFileName?.let { add(it) }
                d.keyAlias?.let { add(it) }
                d.serviceName?.let { add(it) }
                d.accountName?.let { add(it) }
                d.keyName?.let { add(it) }
                addAll(d.variableNames)
                addAll(d.fieldLabels)
            }.filter { it.isNotBlank() }
            out += SearchDocument(
                type = SearchType.DEVELOPER,
                navigationId = d.stableId,
                title = d.title,
                subtitle = safeDeveloperSubtitle(d),
                searchableTokens = tokens,
                pinned = false,
                developerType = d.type,
            )
        }
        return out
    }

    private fun safeDeveloperSubtitle(d: DeveloperSearchMetadata): String? =
        when (d.type) {
            "ANDROID_SIGNING_KEY" -> d.packageName
            "API_CREDENTIAL" -> listOf(d.serviceName, d.accountName).filter { !it.isNullOrBlank() }.joinToString(" · ")
            "SSH_KEY" -> d.keyName
            "ENVIRONMENT_VARIABLE_SET" -> d.projectName
            "GENERIC_SECRET" -> null
            else -> null
        }

    /**
     * Runs [query] against the in-memory projection and returns deterministic,
     * type-grouped results. Empty query → empty list.
     */
    fun search(query: String): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val matched = documents.mapNotNull { doc ->
            doc.match(trimmed)?.let { rank -> doc to rank }
        }

        // Fixed type grouping order: Provider, Account, TOTP, Recovery, Developer.
        // Within a group: rank (best first), then pinned, then stable title.
        val typeOrder = mapOf(
            SearchType.PROVIDER to 0,
            SearchType.ACCOUNT to 1,
            SearchType.TOTP to 2,
            SearchType.RECOVERY_SET to 3,
            SearchType.DEVELOPER to 4,
        )
        return matched
            .sortedWith(
                compareBy<Pair<SearchDocument, MatchRank>> { typeOrder[it.first.type] }
                    .thenByDescending { it.second }
                    .thenByDescending { it.first.pinned }
                    .thenBy { it.first.title.lowercase() }
                    .thenBy { it.first.navigationId },
            )
            .map { it.first.toResult() }
    }
}
