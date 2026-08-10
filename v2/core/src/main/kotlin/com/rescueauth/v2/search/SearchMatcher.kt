package com.rescueauth.v2.search

import java.util.Locale

/**
 * P7 Global Search matcher — pure, deterministic, Unicode-safe.
 *
 * ## Contract
 *
 * - Empty / blank query → no results (handled by the caller, this class just
 *   reports "no match" for an empty token list).
 * - Matching is **case-insensitive** using [Locale.ROOT] so Turkish-I style
 *   locale anomalies are impossible.
 * - Multi-token queries use whitespace split + **AND** semantics: every query
 *   token must be found (as a substring) inside at least one of the candidate
 *   searchable tokens. Example: `"google work"` only returns documents whose
 *   safe metadata contains both `google` and `work`.
 * - Matching is a stable `contains`/`startsWith`/`equals` over explicitly
 *   safe searchable metadata only. There is **no** fuzzy search, Levenshtein,
 *   semantic embedding, regex, or pinyin engine.
 *
 * ## Ranking (deterministic, no usage tracking)
 *
 * Per document, the best (highest) rank among all query tokens wins:
 *
 * 1. [MatchRank.EXACT] — a searchable token equals a query token
 * 2. [MatchRank.PREFIX] — a searchable token starts with a query token
 * 3. [MatchRank.SUBSTRING] — a searchable token contains a query token
 * 4. [MatchRank.TOKEN] — all query tokens present but none is a substring of a
 *    single token (multi-token AND satisfied across separate tokens)
 *
 * Documents that do not satisfy the AND condition are rejected.
 */
object SearchMatcher {

    /** Match tiers, from best to worst. */
    enum class MatchRank {
        SUBSTRING,
        PREFIX,
        EXACT,
    }

    /** Case-fold [s] using [Locale.ROOT]. This is the single, stable
     *  case-insensitivity contract — never locale-sensitive. */
    fun normalize(s: String): String = s.lowercase(Locale.ROOT)

    /** Splits a query into whitespace-separated, normalized tokens (non-empty). */
    fun queryTokens(query: String): List<String> =
        normalize(query.trim())
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

    /**
     * Returns the best [MatchRank] if every query token is found as a substring
     * of at least one (normalized) [searchableTokens] entry, otherwise `null`.
     *
     * Candidates are normalized internally with [normalize]; they do not need
     * to be pre-normalized by the caller.
     */
    fun matchRank(query: String, searchableTokens: List<String>): MatchRank? {
        val tokens = queryTokens(query)
        if (tokens.isEmpty() || searchableTokens.isEmpty()) return null
        val candidates = searchableTokens.map { normalize(it) }

        // AND semantics: every query token must match at least one candidate.
        var best: MatchRank? = null
        for (q in tokens) {
            var tokenBest: MatchRank? = null
            for (c in candidates) {
                val r = singleTokenRank(c, q) ?: continue
                if (tokenBest == null || r.ordinal > tokenBest.ordinal) {
                    tokenBest = r
                }
            }
            if (tokenBest == null) return null // this query token not present → no match
            if (best == null || tokenBest.ordinal > best.ordinal) best = tokenBest
        }
        return best
    }

    /** Rank of one (normalized) candidate token against one (normalized) query token. */
    private fun singleTokenRank(candidate: String, queryToken: String): MatchRank? {
        if (!candidate.contains(queryToken)) return null
        return when {
            candidate == queryToken -> MatchRank.EXACT
            candidate.startsWith(queryToken) -> MatchRank.PREFIX
            else -> MatchRank.SUBSTRING
        }
    }
}
