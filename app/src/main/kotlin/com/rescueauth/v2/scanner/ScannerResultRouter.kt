package com.rescueauth.v2.scanner

import com.rescueauth.v2.migration.MigrationPayloadParser
import com.rescueauth.v2.migration.MigrationParseResult
import com.rescueauth.v2.totp.OtpauthParser
import com.rescueauth.v2.totp.ParsedTotp

/**
 * Routes a raw scanned QR string to the correct parser (Phase 4 P2).
 *
 * Pure Kotlin, no camera / Compose / Room dependency — unit-testable on the
 * JVM. Camera output only ever becomes a [String]; routing decides what it
 * means:
 *
 * ```
 * raw String
 *   ├─ otpauth://totp/...       → OtpauthParser (existing P1 parser, reused)
 *   ├─ otpauth-migration://...  → MigrationPayloadParser (P2 adapter)
 *   └─ anything else            → NotSupportedQr
 * ```
 */
object ScannerResultRouter {

    sealed interface ScanResult {
        /** A normal otpauth:// TOTP URI. */
        data class Totp(val parsed: ParsedTotp) : ScanResult

        /** An otpauth-migration:// payload (single or multi-batch). */
        data class Migration(val parsed: MigrationParseResult, val raw: String) : ScanResult

        /** Scanned text is not a supported QR (not otpauth / migration). */
        data object NotSupported : ScanResult

        /** Scanned text looks like otpauth:// but is malformed. */
        data class MalformedOtpauth(val reason: String) : ScanResult

        /** Scanned text looks like otpauth-migration:// but is malformed. */
        data class MalformedMigration(val reason: String) : ScanResult
    }

    /**
     * Routes [raw] to the matching parser. Never throws — every outcome is a
     * typed [ScanResult] so the scanner / ViewModel can show distinct error
     * UI without leaking exception internals.
     */
    fun route(raw: String): ScanResult {
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("otpauth://") -> {
                try {
                    ScanResult.Totp(OtpauthParser.parse(trimmed))
                } catch (e: OtpauthParser.OtpauthParseException) {
                    ScanResult.MalformedOtpauth(userOtpauthMessage(e.message))
                }
            }
            lower.startsWith("otpauth-migration://") -> {
                try {
                    ScanResult.Migration(MigrationPayloadParser.parseUri(trimmed), trimmed)
                } catch (e: MigrationPayloadParser.MigrationParseException) {
                    ScanResult.MalformedMigration(userMigrationMessage(e.reason))
                }
            }
            else -> ScanResult.NotSupported
        }
    }

    private fun userOtpauthMessage(message: String?): String = when {
        message == null -> "Invalid otpauth URI"
        message.contains("HOTP", ignoreCase = true) -> "HOTP is not supported"
        message.contains("missing secret") -> "Missing secret"
        message.contains("invalid base32", ignoreCase = true) -> "Invalid secret"
        message.contains("unsupported algorithm", ignoreCase = true) -> "Unsupported algorithm"
        message.contains("invalid digits", ignoreCase = true) -> "Invalid digits"
        message.contains("invalid period", ignoreCase = true) -> "Invalid period"
        else -> "Invalid otpauth URI"
    }

    private fun userMigrationMessage(reason: String): String = when (reason) {
        "missing-data" -> "Missing migration data"
        "malformed-base64", "invalid-data-character", "empty-data" -> "Malformed migration payload"
        "unsupported-scheme", "unsupported-host", "not-an-otpauth-migration-uri" -> "Not a migration QR"
        else -> "Malformed migration payload"
    }
}
