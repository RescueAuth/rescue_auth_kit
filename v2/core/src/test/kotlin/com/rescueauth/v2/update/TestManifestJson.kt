package com.rescueauth.v2.update

/**
 * TEST-ONLY builder for schema-v1 manifest JSON strings.
 *
 * Produces exact JSON bytes so tests can sign the exact bytes and mutate them
 * byte-for-byte (verifying that the signature covers the exact raw bytes).
 */
object TestManifestJson {

    const val SCHEMA_VERSION = 1
    const val CHANNEL = "stable"
    const val VERSION_NAME = "1.1.0"
    const val VERSION_CODE = 10100L
    const val MIN_SUPPORTED = 10000L
    const val PUBLISHED_AT = "2026-08-10T12:00:00Z"
    const val APK_URL = "https://cnb.cool/xincy22/rescueauth-updates/-/git/raw/main/android/stable/rescueauth-1.1.0.apk"
    const val APK_SIZE = 1_234_567L
    const val APK_SHA256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val RELEASE_NOTES_URL = "https://cnb.cool/xincy22/rescueauth-updates/-/git/raw/main/android/stable/1.1.0.html"
    const val SEVERITY = "NORMAL"

    fun build(
        schemaVersion: Int = SCHEMA_VERSION,
        channel: String = CHANNEL,
        versionName: String = VERSION_NAME,
        versionCode: Long = VERSION_CODE,
        minSupportedVersionCode: Long = MIN_SUPPORTED,
        publishedAt: String = PUBLISHED_AT,
        apkUrl: String = APK_URL,
        apkSizeBytes: Long = APK_SIZE,
        apkSha256: String = APK_SHA256,
        releaseNotesUrl: String = RELEASE_NOTES_URL,
        severity: String = SEVERITY,
        extraFields: Map<String, String> = emptyMap(),
    ): String {
        val fields = mutableMapOf<String, String>()
        fields["schemaVersion"] = schemaVersion.toString()
        fields["channel"] = jsonStr(channel)
        fields["versionName"] = jsonStr(versionName)
        fields["versionCode"] = versionCode.toString()
        fields["minSupportedVersionCode"] = minSupportedVersionCode.toString()
        fields["publishedAt"] = jsonStr(publishedAt)
        fields["apkUrl"] = jsonStr(apkUrl)
        fields["apkSizeBytes"] = apkSizeBytes.toString()
        fields["apkSha256"] = jsonStr(apkSha256)
        fields["releaseNotesUrl"] = jsonStr(releaseNotesUrl)
        fields["severity"] = jsonStr(severity)
        fields.putAll(extraFields)

        val body = fields.entries.joinToString(",") { (k, v) -> "\"$k\":$v" }
        return "{ $body }"
    }

    /** A valid, default manifest's UTF-8 bytes. */
    fun defaultBytes(): ByteArray = build().toByteArray()

    fun jsonStr(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
