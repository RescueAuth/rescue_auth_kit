package com.rescueauth.v2.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The immutable, parsed and **verified** update manifest (UPDATE_PROTOCOL.md
 * schema v1).
 *
 * ## Trust boundary
 *
 * An instance of [UpdateManifest] is only ever constructed AFTER its exact raw
 * bytes have been Ed25519-verified against the configured trusted public key
 * ([UpdateManifestVerifier]). The parser [UpdateManifestParser.parse] must
 * never be fed untrusted bytes; the caller is responsible for the
 * `fetch → size-check → Ed25519 verify → parse` ordering.
 *
 * ## Schema v1 (frozen in UPDATE_PROTOCOL.md)
 *
 * ```
 * {
 *   "schemaVersion": 1,
 *   "channel": "stable",
 *   "versionName": "...",
 *   "versionCode": ...,
 *   "minSupportedVersionCode": ...,
 *   "publishedAt": "...",          // ISO-8601
 *   "apkUrl": "...",               // HTTPS
 *   "apkSizeBytes": ...,
 *   "apkSha256": "...",            // exactly 64 hex chars
 *   "releaseNotesUrl": "...",      // HTTPS
 *   "severity": "NORMAL" | "SECURITY"
 * }
 * ```
 *
 * Unknown additive fields are tolerated for forward compatibility. Missing
 * required fields / wrong types are rejected.
 */
@Serializable
data class UpdateManifest(
    val schemaVersion: Int,
    val channel: String,
    val versionName: String,
    val versionCode: Long,
    val minSupportedVersionCode: Long,
    val publishedAt: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val releaseNotesUrl: String,
    val severity: Severity,
)

/**
 * Update severity — a deliberately minimal enum (frozen this round).
 *
 * - [NORMAL]: a normal new-version prompt.
 * - [SECURITY]: a more prominent "security update available" prompt.
 *
 * Even [SECURITY] NEVER locks the Vault, forbids app usage, auto-downloads or
 * auto-installs; it is only a stronger recommendation (UPDATE_PROTOCOL.md).
 */
enum class Severity {
    NORMAL,
    SECURITY;

    companion object {
        fun fromString(value: String): Severity? = when (value) {
            "NORMAL" -> NORMAL
            "SECURITY" -> SECURITY
            else -> null
        }
    }
}

/**
 * Parses + strictly validates a verified manifest's JSON bytes.
 *
 * ## Ordering contract
 *
 * Call this ONLY with the exact raw bytes that were already Ed25519-verified.
 * The signature covers the exact raw bytes; reformatting / re-serializing the
 * same logical JSON invalidates the original signature, so this parser reads
 * the exact verified bytes and never re-serializes.
 *
 * ## Strictness
 *
 * - `schemaVersion` must be `1`.
 * - `channel` must be `stable`.
 * - Unknown additive fields are ignored (forward compatible).
 * - Required fields present but with a wrong type → rejected.
 * - Required field missing → rejected.
 *
 * @throws ManifestParseException with a stable [ManifestParseException.Kind]
 *   for every failure category so the UI can map it to the update state
 *   machine error taxonomy.
 */
object UpdateManifestParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    private val REQUIRED_KEYS = listOf(
        "schemaVersion", "channel", "versionName", "versionCode",
        "minSupportedVersionCode", "publishedAt", "apkUrl", "apkSizeBytes",
        "apkSha256", "releaseNotesUrl", "severity",
    )

    fun parse(bytes: ByteArray): UpdateManifest {
        val root: JsonObject = try {
            json.parseToJsonElement(bytes.decodeToString()).jsonObject
        } catch (e: Exception) {
            throw ManifestParseException(ManifestParseException.Kind.MALFORMED_JSON, e)
        }

        for (key in REQUIRED_KEYS) {
            if (!root.containsKey(key)) {
                throw ManifestParseException(ManifestParseException.Kind.MISSING_REQUIRED_FIELD, "missing key: $key")
            }
        }

        val schemaVersion = root.longOf("schemaVersion")
            ?: throw ManifestParseException(ManifestParseException.Kind.WRONG_FIELD_TYPE, "schemaVersion")
        if (schemaVersion != 1L) {
            throw ManifestParseException(ManifestParseException.Kind.UNSUPPORTED_SCHEMA, "schemaVersion=$schemaVersion")
        }

        val channel = root.strOf("channel")
            ?: throw ManifestParseException(ManifestParseException.Kind.WRONG_FIELD_TYPE, "channel")
        if (channel != "stable") {
            throw ManifestParseException(ManifestParseException.Kind.CHANNEL_UNSUPPORTED, "channel=$channel")
        }

        val versionName = root.strOf("versionName")
            ?: throw ManifestParseException(ManifestParseException.Kind.WRONG_FIELD_TYPE, "versionName")
        if (versionName.isBlank()) {
            throw ManifestParseException(ManifestParseException.Kind.VERSION_NAME_BLANK, "versionName blank")
        }

        val versionCode = root.longOf("versionCode")
            ?: throw ManifestParseException(ManifestParseException.Kind.WRONG_FIELD_TYPE, "versionCode")
        if (versionCode <= 0) {
            throw ManifestParseException(ManifestParseException.Kind.VERSION_CODE_NOT_POSITIVE, "versionCode=$versionCode")
        }

        val minSupported = root.longOf("minSupportedVersionCode")
            ?: throw ManifestParseException(ManifestParseException.Kind.WRONG_FIELD_TYPE, "minSupportedVersionCode")
        if (minSupported <= 0) {
            throw ManifestParseException(ManifestParseException.Kind.MIN_SUPPORTED_NOT_POSITIVE, "minSupported=$minSupported")
        }
        if (minSupported > versionCode) {
            throw ManifestParseException(ManifestParseException.Kind.MIN_SUPPORTED_ABOVE_LATEST, "minSupported=$minSupported > latest=$versionCode")
        }

        val publishedAt = root.strOf("publishedAt")
            ?: throw ManifestParseException(ManifestParseException.Kind.WRONG_FIELD_TYPE, "publishedAt")
        if (!Iso8601.isValid(publishedAt)) {
            throw ManifestParseException(ManifestParseException.Kind.INVALID_PUBLISHED_AT, "publishedAt=$publishedAt")
        }

        val apkUrl = root.strOf("apkUrl")
            ?: throw ManifestParseException(ManifestParseException.Kind.WRONG_FIELD_TYPE, "apkUrl")
        if (!UrlPolicy.isSecureHttpUrl(apkUrl)) {
            throw ManifestParseException(ManifestParseException.Kind.APK_URL_NOT_HTTPS, "apkUrl=$apkUrl")
        }

        val apkSizeBytes = root.longOf("apkSizeBytes")
            ?: throw ManifestParseException(ManifestParseException.Kind.WRONG_FIELD_TYPE, "apkSizeBytes")
        if (apkSizeBytes <= 0 || apkSizeBytes > APK_SIZE_MAX) {
            throw ManifestParseException(ManifestParseException.Kind.APK_SIZE_NOT_POSITIVE, "apkSizeBytes=$apkSizeBytes")
        }

        val apkSha256 = root.strOf("apkSha256")
            ?: throw ManifestParseException(ManifestParseException.Kind.WRONG_FIELD_TYPE, "apkSha256")
        if (!Sha256.isCanonicalHex(apkSha256)) {
            throw ManifestParseException(ManifestParseException.Kind.APK_SHA256_INVALID, "apkSha256=$apkSha256")
        }

        val releaseNotesUrl = root.strOf("releaseNotesUrl")
            ?: throw ManifestParseException(ManifestParseException.Kind.WRONG_FIELD_TYPE, "releaseNotesUrl")
        if (!UrlPolicy.isSecureHttpUrl(releaseNotesUrl)) {
            throw ManifestParseException(ManifestParseException.Kind.RELEASE_NOTES_URL_NOT_HTTPS, "releaseNotesUrl=$releaseNotesUrl")
        }

        val severityRaw = root.strOf("severity")
            ?: throw ManifestParseException(ManifestParseException.Kind.WRONG_FIELD_TYPE, "severity")
        val severity = Severity.fromString(severityRaw)
            ?: throw ManifestParseException(ManifestParseException.Kind.SEVERITY_UNKNOWN, "severity=$severityRaw")

        return UpdateManifest(
            schemaVersion = 1,
            channel = "stable",
            versionName = versionName,
            versionCode = versionCode,
            minSupportedVersionCode = minSupported,
            publishedAt = publishedAt,
            apkUrl = apkUrl,
            apkSizeBytes = apkSizeBytes,
            apkSha256 = apkSha256,
            releaseNotesUrl = releaseNotesUrl,
            severity = severity,
        )
    }

    private fun JsonObject.strOf(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.content

    private fun JsonObject.longOf(key: String): Long? =
        this[key]?.jsonPrimitive?.let { it.intOrNull?.toLong() ?: it.longOrNull }
}

/** Upper bound for apkSizeBytes (positive, bounded). */
const val APK_SIZE_MAX: Long = 5_000_000_000L

/**
 * A structured failure from manifest parsing/validation. The UI maps [Kind] to
 * the update state machine's error taxonomy.
 */
class ManifestParseException(val kind: Kind, cause: Any? = null) :
    RuntimeException(cause?.toString(), cause as? Throwable) {

    enum class Kind {
        MALFORMED_JSON,
        MISSING_REQUIRED_FIELD,
        WRONG_FIELD_TYPE,
        UNSUPPORTED_SCHEMA,
        CHANNEL_UNSUPPORTED,
        VERSION_NAME_BLANK,
        VERSION_CODE_NOT_POSITIVE,
        MIN_SUPPORTED_NOT_POSITIVE,
        MIN_SUPPORTED_ABOVE_LATEST,
        INVALID_PUBLISHED_AT,
        APK_URL_NOT_HTTPS,
        APK_SIZE_NOT_POSITIVE,
        APK_SHA256_INVALID,
        RELEASE_NOTES_URL_NOT_HTTPS,
        SEVERITY_UNKNOWN,
    }
}
