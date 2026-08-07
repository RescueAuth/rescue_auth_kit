package com.rescueauth.v2.ui.model

/**
 * Developer Vault entry categories (five types, KEEP per PRODUCT.md).
 *
 * This is a **UI-level** contract mirroring the five product types. It is not
 * a Room entity and not a package-domain record; persistence schemas must not
 * be exposed to Compose components.
 */
enum class DeveloperEntryType {
    ANDROID_SIGNING_KEY,
    API_CREDENTIAL,
    SSH_KEY,
    ENVIRONMENT_VARIABLE_SET,
    GENERIC_SECRET,
}

/**
 * Pure UI model for a Developer Vault entry.
 *
 * - [sensitiveFields] carry display placeholders only. Production values must
 *   never be passed as plaintext through the UI unless a reveal action has
 *   explicitly fetched them (a later slice).
 * - [title] is the human-readable label shown in the entry card.
 */
data class DeveloperEntryUi(
    val id: String,
    val type: DeveloperEntryType,
    val title: String,
    val subtitle: String? = null,
    val sensitiveFields: List<SensitiveFieldUi> = emptyList(),
    val isPinned: Boolean = false,
)

/**
 * A labelled sensitive value shown by [SensitiveValueRow] in hidden/reveal
 * states.
 */
data class SensitiveFieldUi(
    val label: String,
    val value: String,
    val isSensitive: Boolean = true,
)

/** Preview fixture only — never injected into production flows. */
object DeveloperPreviewData {
    val signingKey = DeveloperEntryUi(
        id = "preview-signing",
        type = DeveloperEntryType.ANDROID_SIGNING_KEY,
        title = "release keystore",
        subtitle = "com.example.app",
        sensitiveFields = listOf(
            SensitiveFieldUi(label = "storePassword", value = "••••••••"),
            SensitiveFieldUi(label = "keyPassword", value = "••••••••"),
        ),
    )
    val apiCredential = DeveloperEntryUi(
        id = "preview-api",
        type = DeveloperEntryType.API_CREDENTIAL,
        title = "Vault CI bot",
        subtitle = "github.com/example",
        sensitiveFields = listOf(
            SensitiveFieldUi(label = "apiKey", value = "••••••••"),
            SensitiveFieldUi(label = "apiSecret", value = "••••••••"),
        ),
    )
    val sshKey = DeveloperEntryUi(
        id = "preview-ssh",
        type = DeveloperEntryType.SSH_KEY,
        title = "deploy-2026",
        subtitle = "ed25519",
        sensitiveFields = listOf(
            SensitiveFieldUi(label = "privateKey", value = "••••••••"),
        ),
    )
    val envVarSet = DeveloperEntryUi(
        id = "preview-env",
        type = DeveloperEntryType.ENVIRONMENT_VARIABLE_SET,
        title = "CI variables",
        subtitle = "4 variables",
        sensitiveFields = listOf(
            SensitiveFieldUi(label = "AUTH_SECRET", value = "••••••••"),
        ),
    )
    val generic = DeveloperEntryUi(
        id = "preview-generic",
        type = DeveloperEntryType.GENERIC_SECRET,
        title = "Wi-Fi fallback",
        subtitle = "notes",
        sensitiveFields = listOf(
            SensitiveFieldUi(label = "password", value = "••••••••"),
        ),
    )
}
