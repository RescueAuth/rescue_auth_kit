package com.rescueauth.v2.repository

/**
 * P7 Global Search — safe Developer searchable metadata.
 *
 * Deliberately a **narrow projection**: it carries only the non-secret fields
 * that are safe to index and search (per P7 §6), extracted from the typed
 * logical payload by [DeveloperMappers]. No secret field (apiKey, apiSecret,
 * privateKey, passphrase, keystoreBase64, storePassword, keyPassword, env
 * value, generic value) is ever present here. Free-form `notes` are also
 * excluded by design (P7 §7).
 */
data class DeveloperSearchMetadata(
    val stableId: String,
    val title: String,
    val type: String,
    val projectName: String? = null,
    val packageName: String? = null,
    val keystoreFileName: String? = null,
    val keyAlias: String? = null,
    val serviceName: String? = null,
    val accountName: String? = null,
    val keyName: String? = null,
    val variableNames: List<String> = emptyList(),
    val fieldLabels: List<String> = emptyList(),
)
