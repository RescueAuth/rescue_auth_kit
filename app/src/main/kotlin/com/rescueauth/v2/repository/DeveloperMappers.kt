package com.rescueauth.v2.repository

import com.rescueauth.v2.database.DeveloperEntryEntity
import com.rescueauth.v2.domain.DeveloperEntry
import com.rescueauth.v2.domain.DeveloperEntryType
import com.rescueauth.v2.export.VaultDeveloperEntry
import kotlinx.serialization.json.Json

/**
 * The ONLY bridge between Room persistence and the shared logical Developer
 * Entry model (AGENTS 架构边界 3 / ROADMAP §9).
 *
 * - [toEntity]: logical `VaultDeveloperEntry` (validated package data) →
 *   Room row. The type-specific payload (incl. every secret) is JSON-encoded
 *   into [DeveloperEntryEntity.payloadJson] using the platform-neutral
 *   sealed serializer (self-describing via the polymorphic `type`
 *   discriminator). Nothing here writes logs or sidecar files.
 * - [toLogical]: Room row → logical `VaultDeveloperEntry`. Used to rebuild a
 *   destination snapshot for merge planning (phase 3C destination reads) and
 *   by persistence round-trip tests. The decoded object is what the shared
 *   merge core consumes — never a Room entity.
 * - [toDomain]: Room row → metadata-only domain read model (no secrets
 *   cross the domain boundary; Developer reveal/CRUD is a later slice).
 *
 * JSON configuration mirrors the package codec (encodeDefaults + explicit
 * nulls) so encode→decode is an exact round trip of every logical field.
 */
internal object DeveloperMappers {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = true
    }

    fun toEntity(entry: VaultDeveloperEntry, sortOrder: Long): DeveloperEntryEntity =
        DeveloperEntryEntity(
            id = entry.stableId,
            stableId = entry.stableId,
            entryType = entryTypeName(entry),
            title = entry.title,
            notes = entry.notes,
            payloadJson = json.encodeToString(VaultDeveloperEntry.serializer(), entry),
            createdAt = entry.createdAt,
            updatedAt = entry.updatedAt,
            sortOrder = sortOrder,
        )

    fun toLogical(entity: DeveloperEntryEntity): VaultDeveloperEntry =
        json.decodeFromString(VaultDeveloperEntry.serializer(), entity.payloadJson)

    /**
     * P7 Global Search — extracts ONLY the safe, non-secret metadata from the
     * typed logical payload. No secret field, notes, or publicKey is ever
     * indexed (P7 §6/§7). This is the single source for Developer search docs.
     */
    fun toSearchMetadata(entry: VaultDeveloperEntry): DeveloperSearchMetadata =
        when (entry) {
            is com.rescueauth.v2.export.VaultAndroidSigningKey -> DeveloperSearchMetadata(
                stableId = entry.stableId,
                title = entry.title,
                type = com.rescueauth.v2.domain.DeveloperEntryType.ANDROID_SIGNING_KEY.name,
                projectName = entry.projectName,
                packageName = entry.packageName,
                keystoreFileName = entry.keystoreFileName,
                keyAlias = entry.keyAlias,
            )
            is com.rescueauth.v2.export.VaultApiCredential -> DeveloperSearchMetadata(
                stableId = entry.stableId,
                title = entry.title,
                type = com.rescueauth.v2.domain.DeveloperEntryType.API_CREDENTIAL.name,
                serviceName = entry.serviceName,
                accountName = entry.accountName,
            )
            is com.rescueauth.v2.export.VaultSshKey -> DeveloperSearchMetadata(
                stableId = entry.stableId,
                title = entry.title,
                type = com.rescueauth.v2.domain.DeveloperEntryType.SSH_KEY.name,
                keyName = entry.keyName,
            )
            is com.rescueauth.v2.export.VaultEnvironmentVariableSet -> DeveloperSearchMetadata(
                stableId = entry.stableId,
                title = entry.title,
                type = com.rescueauth.v2.domain.DeveloperEntryType.ENVIRONMENT_VARIABLE_SET.name,
                projectName = entry.projectName,
                variableNames = entry.variables.map { it.key },
            )
            is com.rescueauth.v2.export.VaultGenericSecret -> DeveloperSearchMetadata(
                stableId = entry.stableId,
                title = entry.title,
                type = com.rescueauth.v2.domain.DeveloperEntryType.GENERIC_SECRET.name,
                fieldLabels = entry.fields.map { it.key },
            )
        }

    fun toDomain(entity: DeveloperEntryEntity): DeveloperEntry = DeveloperEntry(
        id = entity.id,
        stableId = entity.stableId,
        type = DeveloperEntryType.valueOf(entity.entryType),
        title = entity.title,
        notes = entity.notes,
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        sortOrder = entity.sortOrder,
    )

    private fun entryTypeName(entry: VaultDeveloperEntry): String = when (entry) {
        is com.rescueauth.v2.export.VaultAndroidSigningKey -> DeveloperEntryType.ANDROID_SIGNING_KEY.name
        is com.rescueauth.v2.export.VaultApiCredential -> DeveloperEntryType.API_CREDENTIAL.name
        is com.rescueauth.v2.export.VaultSshKey -> DeveloperEntryType.SSH_KEY.name
        is com.rescueauth.v2.export.VaultEnvironmentVariableSet -> DeveloperEntryType.ENVIRONMENT_VARIABLE_SET.name
        is com.rescueauth.v2.export.VaultGenericSecret -> DeveloperEntryType.GENERIC_SECRET.name
    }
}
