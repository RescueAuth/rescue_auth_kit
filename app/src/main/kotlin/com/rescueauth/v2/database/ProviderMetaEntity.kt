package com.rescueauth.v2.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Provider metadata (database schema v4).
 *
 * A Provider is still the virtual `serviceName` grouping of accounts (no
 * stableId, no identity change — see ProviderAccountRepository). This table
 * only carries per-provider DISPLAY metadata keyed by the provider name:
 *
 * - [iconKey] — the persisted icon override. `null` = AUTO (built-in brand
 *   auto-match by name, falling back to the letter badge). Non-null values
 *   are `BrandIcons` keys (e.g. "github") or the sentinel "letter" to force
 *   the letter badge even when a brand would auto-match.
 *
 * Rows are cascade-maintained by ProviderAccountRepository: renamed together
 * with the provider and deleted with the provider. The row is NOT part of the
 * merge identity of any account; losing it degrades gracefully to AUTO.
 */
@Entity(tableName = "provider_meta")
data class ProviderMetaEntity(
    @PrimaryKey val providerName: String,
    val iconKey: String? = null,
)

/** Sentinel persisted in [ProviderMetaEntity.iconKey] meaning "always letter". */
const val PROVIDER_ICON_LETTER = "letter"
