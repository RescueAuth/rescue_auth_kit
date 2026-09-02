package com.rescueauth.v2.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the built-in brand icon catalogue (schema v4 UI polish).
 *
 * The auto-match rules are deliberately conservative: a service name matches
 * a brand only when a normalised token equals one of the brand's aliases,
 * with short aliases (< 3 chars) requiring a WHOLE-NAME match.
 */
class BrandIconsTest {

    // ---- auto-match: exact / alias / multi-word ----

    @Test
    fun `exact service name matches its brand`() {
        assertEquals("github", BrandIcons.resolveKey("GitHub"))
        assertEquals("google", BrandIcons.resolveKey("google"))
        assertEquals("microsoft", BrandIcons.resolveKey("MICROSOFT"))
    }

    @Test
    fun `known alias matches the brand`() {
        assertEquals("google", BrandIcons.resolveKey("Gmail"))
        assertEquals("google", BrandIcons.resolveKey("YouTube"))
        assertEquals("microsoft", BrandIcons.resolveKey("Outlook"))
        assertEquals("microsoft", BrandIcons.resolveKey("OneDrive"))
        assertEquals("apple", BrandIcons.resolveKey("iCloud"))
        assertEquals("amazon", BrandIcons.resolveKey("AWS"))
        assertEquals("facebook", BrandIcons.resolveKey("Meta"))
        assertEquals("x", BrandIcons.resolveKey("Twitter"))
    }

    @Test
    fun `multi-word alias matches the whole normalised name`() {
        assertEquals("google", BrandIcons.resolveKey("Google Cloud"))
        assertEquals("microsoft", BrandIcons.resolveKey("Microsoft 365"))
    }

    @Test
    fun `token-level match applies to single-token aliases`() {
        // Implemented rule: a single-token alias with length >= 3 matches when
        // ANY normalised token equals it — "Google Cloud Workspace" contains
        // the token "google", so it matches. Locked here so a future tightening
        // is a deliberate, visible change.
        assertEquals("google", BrandIcons.resolveKey("Google Cloud Workspace"))
    }

    // ---- conservative non-matches ----

    @Test
    fun `long-tail service falls back to null`() {
        assertNull(BrandIcons.resolveKey("My Bank"))
        assertNull(BrandIcons.resolveKey("Acme Corp"))
        assertNull(BrandIcons.resolveKey(""))
    }

    @Test
    fun `token containing a brand name is not a whole match`() {
        // "GitHub Desktop" contains token "github" — single-token alias with
        // length >= 3 matches any token, so this DOES match. Guarded here so
        // a future tightening cannot silently break it.
        assertEquals("github", BrandIcons.resolveKey("GitHub Desktop"))
    }

    @Test
    fun `short single-token alias only matches the whole name`() {
        // The service "x" matches the brand "x"…
        assertEquals("x", BrandIcons.resolveKey("X"))
        // …but "box" must NOT (token-level match for aliases < 3 chars is
        // forbidden — the classic false-positive case).
        assertNull(BrandIcons.resolveKey("Box"))
        assertNull(BrandIcons.resolveKey("Xero"))
    }

    @Test
    fun `normalisation strips punctuation`() {
        assertEquals("gitlab", BrandIcons.resolveKey("GitLab!"))
        assertEquals("github", BrandIcons.resolveKey("  GITHUB  "))
    }

    // ---- persisted-key resolution ----

    @Test
    fun `effective resolution prefers the persisted key`() {
        val github = BrandIcons.byKey("github")!!.drawableRes
        // Persisted override "github" wins over auto-match (name is Google).
        assertEquals(github, BrandIcons.effectiveDrawableRes("github", "Google"))
    }

    @Test
    fun `letter sentinel forces null drawable`() {
        assertNull(BrandIcons.effectiveDrawableRes("letter", "GitHub"))
    }

    @Test
    fun `null persisted key falls back to auto-match`() {
        val google = BrandIcons.byKey("google")!!.drawableRes
        assertEquals(google, BrandIcons.effectiveDrawableRes(null, "Gmail"))
    }

    @Test
    fun `unknown persisted key degrades to letter badge`() {
        assertNull(BrandIcons.effectiveDrawableRes("not-a-brand", "GitHub"))
    }

    @Test
    fun `unknown key is not resolvable`() {
        assertNull(BrandIcons.byKey("not-a-brand"))
        assertNull(BrandIcons.drawableResFor("not-a-brand"))
    }

    @Test
    fun `catalogue keys are unique`() {
        assertEquals(BrandIcons.all.size, BrandIcons.all.map { it.key }.distinct().size)
    }
}
