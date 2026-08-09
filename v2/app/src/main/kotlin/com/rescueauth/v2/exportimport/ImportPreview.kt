package com.rescueauth.v2.exportimport

import com.rescueauth.v2.export.MergeDecision
import com.rescueauth.v2.export.MergePlan
import com.rescueauth.v2.export.SnapshotScope
import com.rescueauth.v2.export.VaultAndroidSigningKey
import com.rescueauth.v2.export.VaultApiCredential
import com.rescueauth.v2.export.VaultDeveloperEntry
import com.rescueauth.v2.export.VaultEnvironmentVariableSet
import com.rescueauth.v2.export.VaultGenericSecret
import com.rescueauth.v2.export.VaultPackagePayload
import com.rescueauth.v2.export.VaultSshKey

/**
 * Safe import preview model (Issue #1 §12).
 *
 * Built from the **decoded** [VaultPackagePayload] + the **preview** merge
 * plan against the current destination. It deliberately contains **no
 * secrets**: no TOTP secret, no recovery-code values, no API keys / SSH key
 * content / passwords / env values / generic values / keystore bytes. The UI
 * holds only this summary model (never the plaintext payload), so Compose
 * state never carries secrets.
 *
 * Developer Entries are represented by safe metadata only: type, title,
 * project/service/key name, plus counts — these are the non-secret logical
 * fields of the entry.
 *
 * [blocked] is true when the preview plan contains unresolved CONFLICTs or
 * recovery used/unused divergences — the user may Cancel / Back, never Import
 * (Issue #1 §13).
 */
data class ImportPreview(
    // --- package metadata (non-secret) ---
    val packageId: String,
    val createdAt: String,
    val sourceClient: String,
    val sourceAppVersion: String,
    val scope: SnapshotScope,
    // --- content summary ---
    val accounts: Int,
    val totpCredentials: Int,
    val recoverySets: Int,
    val recoveryCodes: Int,
    val developerSummary: DeveloperPreviewSummary,
    // --- merge summary ---
    val inserts: Int,
    val duplicates: Int,
    val conflicts: Int,
    val unchanged: Int,
    val stateDivergences: Int,
) {
    val blocked: Boolean get() = conflicts > 0 || stateDivergences > 0

    companion object {
        fun from(payload: VaultPackagePayload, plan: MergePlan): ImportPreview {
            val snapshot = payload.snapshot
            val developer = snapshot.developerEntries
            val summary = plan.summary
            return ImportPreview(
                packageId = payload.packageId,
                createdAt = payload.createdAt,
                sourceClient = payload.source.client,
                sourceAppVersion = payload.source.appVersion,
                scope = snapshot.scope,
                accounts = snapshot.accounts.size,
                totpCredentials = snapshot.accounts.sumOf { it.totpCredentials.size },
                recoverySets = snapshot.accounts.sumOf { it.recoveryCodeSets.size },
                recoveryCodes = snapshot.accounts.sumOf { it.recoveryCodeSets.sumOf { set -> set.codes.size } },
                developerSummary = DeveloperPreviewSummary.from(developer),
                inserts = summary.inserted,
                duplicates = summary.duplicates,
                conflicts = summary.conflicts,
                unchanged = summary.unchanged,
                stateDivergences = summary.stateDivergences,
            )
        }
    }
}

/**
 * Developer section summary for the preview — counts + safe metadata only.
 * [items] lists each entry's type and safe display fields (title /
 * project/service/key name). **No secret values appear anywhere.**
 */
data class DeveloperPreviewSummary(
    val signingKeys: Int,
    val apiCredentials: Int,
    val sshKeys: Int,
    val envVarSets: Int,
    val genericSecrets: Int,
    val items: List<DeveloperPreviewItem>,
) {
    val total: Int get() = signingKeys + apiCredentials + sshKeys + envVarSets + genericSecrets

    companion object {
        fun from(entries: List<VaultDeveloperEntry>): DeveloperPreviewSummary {
            var signing = 0
            var api = 0
            var ssh = 0
            var env = 0
            var generic = 0
            val items = entries.map { entry ->
                val typeName = when (entry) {
                    is VaultAndroidSigningKey -> "android_signing_key"
                    is VaultApiCredential -> "api_credential"
                    is VaultSshKey -> "ssh_key"
                    is VaultEnvironmentVariableSet -> "environment_variable_set"
                    is VaultGenericSecret -> "generic_secret"
                }
                val display = when (entry) {
                    is VaultAndroidSigningKey -> entry.projectName
                    is VaultApiCredential -> entry.serviceName
                    is VaultSshKey -> entry.keyName
                    is VaultEnvironmentVariableSet -> entry.projectName
                    is VaultGenericSecret -> entry.title
                }
                when (entry) {
                    is VaultAndroidSigningKey -> signing++
                    is VaultApiCredential -> api++
                    is VaultSshKey -> ssh++
                    is VaultEnvironmentVariableSet -> env++
                    is VaultGenericSecret -> generic++
                }
                DeveloperPreviewItem(
                    stableId = entry.stableId,
                    type = typeName,
                    displayName = display.ifBlank { entry.title },
                    title = entry.title,
                )
            }
            return DeveloperPreviewSummary(signing, api, ssh, env, generic, items)
        }
    }
}

/** One Developer Entry in the preview — safe metadata only, never secret values. */
data class DeveloperPreviewItem(
    val stableId: String,
    val type: String,
    val displayName: String,
    val title: String,
)

/** Machine-readable plan summary used by tests to assert the preview content. */
data class PlanCounts(
    val inserted: Int,
    val duplicates: Int,
    val conflicts: Int,
    val unchanged: Int,
    val stateDivergences: Int,
)
