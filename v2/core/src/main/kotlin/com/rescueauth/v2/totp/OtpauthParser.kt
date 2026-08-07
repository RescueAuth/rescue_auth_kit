package com.rescueauth.v2.totp

/**
 * Pure Kotlin `otpauth://` URI parser for TOTP (Phase 4 P1).
 *
 * Supported form: `otpauth://totp/{label}?{query}` where the label may be
 * `issuer:account`. Percent decoding is applied to the label and to query
 * values. The parser is deliberately isolated:
 *
 * - **no Room**, **no Android**, **no legacy models**, **no direct DB writes**;
 * - malformed / unsupported input fails with a typed [OtpauthParseException].
 *
 * Semantics follow the current product contract and old v1 behaviour:
 * - `issuer` query parameter wins over the `issuer:` label prefix;
 * - `algorithm` is case-insensitive (SHA1 default);
 * - `digits` / `period` are validated (6/7/8; positive integer);
 * - secret must be present and valid Base32;
 * - `HOTP` and `otpauth-migration` are rejected.
 */
object OtpauthParser {

    const val SCHEME = "otpauth"
    const val TYPE_TOTP = "totp"
    const val TYPE_HOTP = "hotp"

    class OtpauthParseException(message: String) : Exception(message)

    /**
     * Parses [uri] into a [ParsedTotp]. Throws [OtpauthParseException] with a
     * user-friendly reason on any malformed input.
     */
    fun parse(uri: String): ParsedTotp {
        val trimmed = uri.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) throw OtpauthParseException("not an otpauth URI")
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme != SCHEME) throw OtpauthParseException("unsupported URI scheme '$scheme'")

        val rest = trimmed.substring(schemeEnd + 3)
        val fragmentIdx = rest.indexOf('#')
        val withoutFragment = if (fragmentIdx >= 0) rest.substring(0, fragmentIdx) else rest

        val pathEnd = withoutFragment.indexOf('?')
        val path = if (pathEnd >= 0) withoutFragment.substring(0, pathEnd) else withoutFragment
        val query = if (pathEnd >= 0) withoutFragment.substring(pathEnd + 1) else ""

        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) throw OtpauthParseException("missing otpauth type")
        val type = segments[0].lowercase()
        if (type != TYPE_TOTP) {
            if (type == TYPE_HOTP) throw OtpauthParseException("HOTP is not supported")
            throw OtpauthParseException("unsupported otpauth type '$type'")
        }

        val labelRaw = segments.drop(1).joinToString("/")
        val label = percentDecode(labelRaw) ?: ""

        // issuer:account label prefix
        var issuerFromLabel: String? = null
        var account = label
        val colon = label.indexOf(':')
        if (colon >= 0) {
            val before = label.substring(0, colon)
            val after = label.substring(colon + 1)
            if (before.isNotEmpty()) {
                issuerFromLabel = before
                account = after
            }
        }

        val params = parseQuery(query)

        val secret = params["secret"]?.trim().orEmpty()
        if (secret.isEmpty()) throw OtpauthParseException("missing secret")
        if (!TotpCore.isValidBase32(secret)) {
            throw OtpauthParseException("invalid base32 secret")
        }

        val algorithm = params["algorithm"]?.trim()?.uppercase() ?: TotpCore.DEFAULT_ALGORITHM
        if (algorithm !in TotpCore.SUPPORTED_ALGORITHMS) {
            throw OtpauthParseException("unsupported algorithm '$algorithm'")
        }

        val digits = params["digits"]?.let { parsePositiveInt(it, "digits") }
            ?: TotpCore.DEFAULT_DIGITS
        if (digits !in TotpCore.SUPPORTED_DIGITS) {
            throw OtpauthParseException("invalid digits '$digits'")
        }

        val period = params["period"]?.let { parsePositiveInt(it, "period") }
            ?: TotpCore.DEFAULT_PERIOD_SECONDS
        if (period <= 0) throw OtpauthParseException("invalid period '$period'")

        // issuer query parameter wins over the label prefix.
        val issuer = params["issuer"]?.let { percentDecode(it) }?.ifBlank { null }
            ?: issuerFromLabel

        return ParsedTotp(
            issuer = issuer,
            accountName = account.ifBlank { null },
            secretBase32 = TotpCore.normalizeSecret(secret),
            algorithm = algorithm,
            digits = digits,
            periodSeconds = period,
        )
    }

    private fun parsePositiveInt(raw: String, name: String): Int {
        val value = raw.trim().toIntOrNull()
            ?: throw OtpauthParseException("invalid $name '$raw'")
        if (value <= 0) throw OtpauthParseException("invalid $name '$raw'")
        return value
    }

    /**
     * RFC 3986 percent-decoding for UTF-8 payloads. Malformed percent
     * sequences are left verbatim (lenient, matching typical v1 behaviour);
     * an encoded NUL is rejected.
     */
    fun percentDecode(input: String): String? {
        if (!input.contains('%')) return input
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%') {
                if (i + 2 >= input.length) return null // truncated escape
                val hi = input.getOrNull(i + 1)?.digitToIntOrNull(16)
                val lo = input.getOrNull(i + 2)?.digitToIntOrNull(16)
                if (hi == null || lo == null) {
                    // malformed escape: keep verbatim
                    bytes.write(c.code)
                    i++
                    continue
                }
                val b = (hi shl 4) or lo
                if (b == 0) return null
                bytes.write(b)
                i += 3
            } else {
                bytes.write(c.code)
                i++
            }
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            if (key.isEmpty()) continue
            val decodedKey = percentDecode(key) ?: continue
            val decodedValue = percentDecode(value) ?: continue
            out.putIfAbsent(decodedKey, decodedValue)
        }
        return out
    }
}
