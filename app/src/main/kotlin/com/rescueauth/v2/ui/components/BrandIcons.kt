package com.rescueauth.v2.ui.components

import androidx.annotation.DrawableRes
import com.rescueauth.v2.R

/**
 * Built-in brand icon catalogue (Phase: UI polish).
 *
 * Assets are monochrome VectorDrawables converted from simple-icons
 * (https://simpleicons.org, CC0). They are tinted at the usage site to match
 * the auto badge palette, so no per-brand colour assets are needed.
 *
 * Resolution is deliberately conservative: a service name matches a brand
 * only when a normalised TOKEN equals one of the brand's aliases. This keeps
 * long-tail services on the letter badge instead of false-matching (e.g.
 * "Box" must NOT match the brand "x").
 */
object BrandIcons {

    /** Keys that may appear in persisted `provider_meta.iconKey`. */
    data class Brand(
        val key: String,
        val label: String,
        @DrawableRes val drawableRes: Int,
        val aliases: Set<String>,
    )

    val all: List<Brand> = listOf(
        Brand(
            key = "github",
            label = "GitHub",
            drawableRes = R.drawable.ic_brand_github,
            aliases = setOf("github", "github actions"),
        ),
        Brand(
            key = "google",
            label = "Google",
            drawableRes = R.drawable.ic_brand_google,
            aliases = setOf("google", "gmail", "google cloud", "youtube"),
        ),
        Brand(
            key = "microsoft",
            label = "Microsoft",
            drawableRes = R.drawable.ic_brand_microsoft,
            aliases = setOf("microsoft", "outlook", "onedrive", "office365", "microsoft 365"),
        ),
        Brand(
            key = "apple",
            label = "Apple",
            drawableRes = R.drawable.ic_brand_apple,
            aliases = setOf("apple", "icloud", "apple id"),
        ),
        Brand(
            key = "amazon",
            label = "Amazon",
            drawableRes = R.drawable.ic_brand_amazon,
            aliases = setOf("amazon", "amazon web services", "aws"),
        ),
        Brand(
            key = "facebook",
            label = "Facebook",
            drawableRes = R.drawable.ic_brand_facebook,
            aliases = setOf("facebook", "meta"),
        ),
        Brand(
            key = "x",
            label = "X",
            drawableRes = R.drawable.ic_brand_x,
            aliases = setOf("x", "twitter"),
        ),
        Brand(
            key = "discord",
            label = "Discord",
            drawableRes = R.drawable.ic_brand_discord,
            aliases = setOf("discord"),
        ),
        Brand(
            key = "gitlab",
            label = "GitLab",
            drawableRes = R.drawable.ic_brand_gitlab,
            aliases = setOf("gitlab"),
        ),
        Brand(
            key = "dropbox",
            label = "Dropbox",
            drawableRes = R.drawable.ic_brand_dropbox,
            aliases = setOf("dropbox"),
        ),
        Brand(
            key = "steam",
            label = "Steam",
            drawableRes = R.drawable.ic_brand_steam,
            aliases = setOf("steam"),
        ),
        Brand(
            key = "reddit",
            label = "Reddit",
            drawableRes = R.drawable.ic_brand_reddit,
            aliases = setOf("reddit"),
        ),
        Brand(
            key = "binance",
            label = "Binance",
            drawableRes = R.drawable.ic_brand_binance,
            aliases = setOf("binance"),
        ),
        Brand(
            key = "coinbase",
            label = "Coinbase",
            drawableRes = R.drawable.ic_brand_coinbase,
            aliases = setOf("coinbase"),
        ),
        Brand(
            key = "slack",
            label = "Slack",
            drawableRes = R.drawable.ic_brand_slack,
            aliases = setOf("slack"),
        ),
    )

    private val byKey: Map<String, Brand> = all.associateBy { it.key }

    /** Returns the brand for a persisted icon key, or null (unknown/auto). */
    fun byKey(key: String?): Brand? = key?.let { byKey[it] }

    /**
     * Auto-resolves a brand for [serviceName]. Normalisation lowercases,
     * strips non-alphanumerics and splits into tokens; a match requires a
     * token to equal an alias token. Aliases shorter than 3 characters only
     * match when the WHOLE normalised name equals the alias (so the service
     * "x" matches, but "box" does not).
     */
    fun resolveKey(serviceName: String): String? {
        val normalised = serviceName.lowercase()
        if (normalised.isBlank()) return null
        val tokens = normalised.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        val whole = tokens.joinToString(" ")
        for (brand in all) {
            for (alias in brand.aliases) {
                val aliasTokens = alias.split(' ')
                if (aliasTokens.size == 1 && alias.length < 3) {
                    // Short single-token alias ("x"): whole-name match only.
                    if (whole == alias) return brand.key
                } else if (tokens.size == aliasTokens.size && whole == alias) {
                    // Multi-word aliases ("aws", "google cloud") match exactly.
                    return brand.key
                } else if (aliasTokens.size == 1 && alias.length >= 3 && alias in tokens) {
                    return brand.key
                }
            }
        }
        return null
    }

    @DrawableRes
    fun drawableResFor(key: String?): Int? = byKey(key)?.drawableRes

    /**
     * Resolves the drawable to display for a provider: the persisted override
     * wins ("letter" sentinel forces null = letter badge), otherwise the
     * built-in auto-match by service name. Null result always means "draw the
     * letter badge".
     */
    @DrawableRes
    fun effectiveDrawableRes(persistedKey: String?, serviceName: String): Int? {
        if (persistedKey == com.rescueauth.v2.database.PROVIDER_ICON_LETTER) return null
        val key = persistedKey ?: resolveKey(serviceName)
        return drawableResFor(key)
    }
}
