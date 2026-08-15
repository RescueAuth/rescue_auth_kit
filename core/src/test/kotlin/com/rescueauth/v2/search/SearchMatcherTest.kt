package com.rescueauth.v2.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchMatcherTest {

    @Test
    fun `empty query produces no match`() {
        assertNull(SearchMatcher.matchRank("", listOf("github")))
        assertNull(SearchMatcher.matchRank("   ", listOf("github")))
    }

    @Test
    fun `case-insensitive substring match`() {
        // Query 'GitHub' vs candidate 'github' is an EXACT match after folding.
        assertEquals(
            SearchMatcher.MatchRank.EXACT,
            SearchMatcher.matchRank("GitHub", listOf("github")),
        )
        // A partial token inside a longer candidate is a SUBSTRING.
        assertEquals(
            SearchMatcher.MatchRank.SUBSTRING,
            SearchMatcher.matchRank("hub", listOf("github")),
        )
    }

    @Test
    fun `exact match ranks highest`() {
        assertEquals(
            SearchMatcher.MatchRank.EXACT,
            SearchMatcher.matchRank("github", listOf("github")),
        )
    }

    @Test
    fun `prefix match ranks above substring`() {
        assertEquals(
            SearchMatcher.MatchRank.PREFIX,
            SearchMatcher.matchRank("git", listOf("github")),
        )
    }

    @Test
    fun `no match returns null`() {
        assertNull(SearchMatcher.matchRank("zzz", listOf("github")))
    }

    @Test
    fun `multi-token AND semantics`() {
        // both tokens present in one candidate; 'google' is a prefix of the
        // candidate so the best rank is PREFIX.
        assertEquals(
            SearchMatcher.MatchRank.PREFIX,
            SearchMatcher.matchRank("google work", listOf("google workspace")),
        )
        // one token missing -> no match
        assertNull(SearchMatcher.matchRank("google missing", listOf("google workspace")))
    }

    @Test
    fun `unicode safe case folding is locale independent`() {
        // Turkish-I anomaly guard: 'I'.lowercase() must NOT become 'ı' in ROOT.
        val folded = SearchMatcher.normalize("I")
        assertEquals("i", folded)
    }

    @Test
    fun `whitespace split and AND across separate tokens`() {
        // 'google work' -> both 'google' and 'work' present across candidates.
        // Each query token matches a different candidate with EXACT rank, so
        // the AND is satisfied at EXACT.
        assertEquals(
            SearchMatcher.MatchRank.EXACT,
            SearchMatcher.matchRank("google work", listOf("google", "work")),
        )
    }
}
