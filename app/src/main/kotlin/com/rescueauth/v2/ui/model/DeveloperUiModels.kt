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
 * Pure UI model for a Developer Vault entry (list presentation).
 *
 * - [stableId] is the stable logical record ID (survives export/import); it is
 *   safe non-secret metadata.
 * - [sensitiveFields] carry display placeholders only. Production values must
 *   never be passed as plaintext through the UI unless a reveal action has
 *   explicitly fetched them through the SensitiveActionGate (Phase 4 P4).
 * - [title] / [subtitle] are the human-readable, non-secret labels shown in
 *   the entry card.
 */
data class DeveloperEntryUi(
    val id: String,
    val stableId: String,
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

/**
 * UI model for one Developer entry detail screen (per-type, Phase 4 P4).
 *
 * Carries only non-secret metadata — secret values are fetched from the
 * repository only after a fresh re-auth and live in the ViewModel's in-memory
 * reveal map (never in SavedStateHandle / Bundle / rememberSaveable /
 * navigation arguments, Issue #20 §21).
 */
sealed interface DeveloperDetailUi {
    val stableId: String
    val title: String

    data class ApiCredential(
        override val stableId: String,
        override val title: String,
        val serviceName: String,
        val accountName: String,
        val notes: String? = null,
    ) : DeveloperDetailUi

    data class SshKey(
        override val stableId: String,
        override val title: String,
        val keyName: String,
        val publicKeyPresent: Boolean,
        val notes: String? = null,
    ) : DeveloperDetailUi

    data class GenericSecret(
        override val stableId: String,
        override val title: String,
        val fieldLabels: List<String>,
        val notes: String? = null,
    ) : DeveloperDetailUi

    data class AndroidSigningKey(
        override val stableId: String,
        override val title: String,
        val projectName: String,
        val packageName: String,
        val keystoreFileName: String,
        val keyAlias: String,
        val notes: String? = null,
    ) : DeveloperDetailUi

    data class EnvironmentVariableSet(
        override val stableId: String,
        override val title: String,
        val projectName: String,
        val variableNames: List<String>,
        val notes: String? = null,
    ) : DeveloperDetailUi
}

/**
 * A sensitive value row state for one secret field of a Developer entry.
 *
 * [isRevealed] is in-memory only and cleared on leaving the screen / session
 * lock / manual hide. The actual plaintext lives only in the ViewModel reveal
 * map after a fresh re-auth.
 */
data class RevealStateUi(
    val label: String,
    val isRevealed: Boolean = false,
)

/**
 * Preview fixture only — never injected into production flows.
 */
object DeveloperPreviewData {
    val signingKey = DeveloperEntryUi(
        id = "preview-signing",
        stableId = "preview-signing",
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
        stableId = "preview-api",
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
        stableId = "preview-ssh",
        type = DeveloperEntryType.SSH_KEY,
        title = "deploy-2026",
        subtitle = "ed25519",
        sensitiveFields = listOf(
            SensitiveFieldUi(label = "privateKey", value = "••••••••"),
        ),
    )
    val envVarSet = DeveloperEntryUi(
        id = "preview-env",
        stableId = "preview-env",
        type = DeveloperEntryType.ENVIRONMENT_VARIABLE_SET,
        title = "CI variables",
        subtitle = "4 variables",
        sensitiveFields = listOf(
            SensitiveFieldUi(label = "AUTH_SECRET", value = "••••••••"),
        ),
    )
    val generic = DeveloperEntryUi(
        id = "preview-generic",
        stableId = "preview-generic",
        type = DeveloperEntryType.GENERIC_SECRET,
        title = "Wi-Fi fallback",
        subtitle = "notes",
        sensitiveFields = listOf(
            SensitiveFieldUi(label = "password", value = "••••••••"),
        ),
    )
}
