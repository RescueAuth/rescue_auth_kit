package com.rescueauth.v2.domain

/**
 * Developer Vault entry read model (Phase 3C).
 *
 * Deliberately a plain value object that mirrors the portable logical
 * [com.rescueauth.v2.export.VaultDeveloperEntry] semantics without leaking
 * Room entities to the UI (AGENTS 架构边界 3). The five formal Developer
 * Entry types are each represented by [type] plus the type-specific payload.
 *
 * This model is read-only for now; Developer CRUD UI is Phase 4 P4/P6.
 */
data class DeveloperEntry(
    val id: String,
    val stableId: String,
    val type: DeveloperEntryType,
    val title: String,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val sortOrder: Long,
)

/** The five formal Developer Entry types (ROADMAP §6). */
enum class DeveloperEntryType {
    ANDROID_SIGNING_KEY,
    API_CREDENTIAL,
    SSH_KEY,
    ENVIRONMENT_VARIABLE_SET,
    GENERIC_SECRET,
}
