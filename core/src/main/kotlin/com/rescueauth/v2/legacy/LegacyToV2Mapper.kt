package com.rescueauth.v2.legacy

import java.util.UUID

/**
 * Maps a validated [LegacyImportBundle] to the v2 database import model.
 *
 * ## Corrected mapping (phase 1 fix, see docs/LEGACY_IMPORT.md §5)
 *
 * ### Schema 1/2 — entry-centric (NEVER issuer-bucketed)
 *
 * Each legacy `totpEntries[i]` maps to **its own** `V2AuthAccount`:
 *
 * ```
 * serviceName = entry.issuer (trimmed; empty → "Untitled")
 * accountName = entry.accountName (trimmed; empty → fallback)
 * TotpCredential = entry's own secret/algorithm/digits/period verbatim
 * ```
 *
 * The same `serviceName` must only be used to **group rows in the UI**; it
 * must never merge accounts or drop any accountName. `recoveryCodeSets[i]`
 * maps to its own `AuthAccount` + `RecoveryCodeSet`.
 *
 * ### Schema 3 — account-centric (unchanged)
 *
 * Each legacy `Account` stays one `V2AuthAccount`; its TOTP / recoveryCodes
 * credentials become child rows.
 *
 * ### Invalid TOTP entries
 *
 * Entries flagged by [LegacyTotpValidator] as INVALID are **excluded** from
 * the resulting accounts and listed in [LegacyImportReport.notImported].
 * Their raw fields are preserved in the report for review.
 */
object LegacyToV2Mapper {

    /**
     * Maps [bundle] to v2 accounts + an explicit report of anything not
     * imported. Deterministic ordering: TOTP entries first (in source order),
     * then recovery code sets (in source order). `sortOrder` increments in
     * that order.
     */
    fun map(bundle: LegacyImportBundle): LegacyImportResult {
        val accounts = mutableListOf<V2AuthAccount>()
        val report = LegacyImportReport()

        var order = 0L
        val now = java.time.Instant.now().toString()

        // ------------------------------------------------------------------
        // Schema 3: one legacy Account -> ONE AuthAccount. TOTP + recovery
        // credentials sharing the same legacyAccountId are merged into that
        // single account. Schema 1/2 entries have no legacyAccountId and are
        // mapped entry-centric below.
        // ------------------------------------------------------------------
        if (bundle.schemaVersion == 3) {
            mapSchema3(bundle, accounts, report, now)
            // Recovery code sets for schema 3 are already merged by
            // mapSchema3; do not fall through to the entry-centric loop.
            report.totalTotpEntries = bundle.totpEntries.size
            report.totalRecoverySets = bundle.recoveryCodeSets.size
            return LegacyImportResult(accounts = accounts, report = report)
        }

        for (entry in bundle.totpEntries) {
            val validated = LegacyTotpValidator.validate(entry)
            if (validated.usability == LegacyImportBundle.TOTP_USABLE) {
                val accountId = UUID.randomUUID().toString()
                val credentialId = UUID.randomUUID().toString()
                val account = V2AuthAccount(
                    id = accountId,
                    serviceName = normalizedIssuer(validated.issuer),
                    accountName = normalizedAccountName(validated.accountName, validated.issuer),
                    sortOrder = order++,
                    createdAt = validated.createdAt ?: now,
                    updatedAt = validated.createdAt ?: now,
                    legacySourceId = validated.legacySourceId ?: validated.id,
                    totpCredentials = mutableListOf(
                        V2TotpCredential(
                            id = credentialId,
                            accountId = accountId,
                            secretBase32 = validated.secretBase32,
                            algorithm = validated.algorithm,
                            digits = validated.digits ?: 0,
                            periodSeconds = validated.period ?: 0,
                            createdAt = validated.createdAt ?: now,
                            legacySourceId = validated.legacySourceId ?: validated.id,
                        )
                    ),
                )
                accounts.add(account)
            } else {
                report.notImported.add(
                    NotImportedEntry(
                        sourceId = validated.legacySourceId ?: validated.id,
                        kind = "totp",
                        serviceName = normalizedIssuer(validated.issuer),
                        accountName = validated.accountName,
                        reason = validated.invalidationReason ?: "unknown",
                        // Preserve the RAW values for the report (never the
                        // silently-defaulted ones).
                        rawAlgorithm = validated.algorithm,
                        rawDigits = validated.digits,
                        rawPeriod = validated.period,
                    )
                )
            }
        }

        for (set in bundle.recoveryCodeSets) {
            val accountId = UUID.randomUUID().toString()
            val setId = UUID.randomUUID().toString()
            val title = set.title.trim().ifEmpty { "Recovery codes" }
            val created = set.createdAt ?: now
            val account = V2AuthAccount(
                id = accountId,
                serviceName = title,
                accountName = normalizedAccountName(set.title, null),
                sortOrder = order++,
                createdAt = created,
                updatedAt = created,
                legacySourceId = set.legacySourceId ?: set.id,
                recoveryCodeSets = mutableListOf(
                    V2RecoveryCodeSet(
                        id = setId,
                        accountId = accountId,
                        title = title,
                        createdAt = created,
                        legacySourceId = set.legacySourceId ?: set.id,
                        codes = set.codes.mapIndexed { i, code ->
                            V2RecoveryCode(
                                id = UUID.randomUUID().toString(),
                                setId = setId,
                                value = code,
                                status = "UNUSED",
                                sortOrder = i,
                            )
                        },
                    )
                ),
            )
            accounts.add(account)
        }

        report.totalTotpEntries = bundle.totpEntries.size
        report.totalRecoverySets = bundle.recoveryCodeSets.size
        return LegacyImportResult(accounts = accounts, report = report)
    }

    /**
     * Schema 3 mapping: group TOTP + recoveryCodes by legacyAccountId.
     */
    private fun mapSchema3(
        bundle: LegacyImportBundle,
        accounts: MutableList<V2AuthAccount>,
        report: LegacyImportReport,
        now: String,
    ) {
        var order = 0L
        // First pass: create one V2AuthAccount per legacy account.
        val accountByLegacyId = LinkedHashMap<String, V2AuthAccount>()
        val validTotp = bundle.totpEntries.map { LegacyTotpValidator.validate(it) }
        val validRecovery = bundle.recoveryCodeSets

        val legacyIds = LinkedHashSet<String>()
        validTotp.forEach { legacyIds.add(it.legacyAccountId ?: "") }
        validRecovery.forEach { legacyIds.add(it.legacyAccountId ?: "") }

        for (legacyId in legacyIds) {
            if (legacyId.isEmpty()) continue // should not happen for schema 3
            val firstTotp = validTotp.firstOrNull { it.legacyAccountId == legacyId }
            val firstRecovery = validRecovery.firstOrNull { it.legacyAccountId == legacyId }
            val created = firstTotp?.createdAt ?: firstRecovery?.createdAt ?: now
            val serviceName = if (firstTotp != null) {
                normalizedIssuer(firstTotp.issuer)
            } else {
                normalizedIssuer(firstRecovery?.title ?: "")
            }
            val accountName = if (firstTotp != null) {
                normalizedAccountName(firstTotp.accountName, firstTotp.issuer)
            } else {
                normalizedAccountName(firstRecovery?.title ?: "", null)
            }
            val account = V2AuthAccount(
                id = UUID.randomUUID().toString(),
                serviceName = serviceName,
                accountName = accountName,
                sortOrder = order++,
                createdAt = created,
                updatedAt = created,
                legacySourceId = legacyId,
            )
            accountByLegacyId[legacyId] = account
            accounts.add(account)
        }

        // Second pass: attach valid TOTP credentials.
        for (validated in validTotp) {
            val legacyId = validated.legacyAccountId ?: continue
            if (validated.usability == LegacyImportBundle.TOTP_USABLE) {
                val account = accountByLegacyId.getValue(legacyId)
                val accountId = account.id
                val credentialId = UUID.randomUUID().toString()
                val idx = account.totpCredentials.size
                account.totpCredentials.add(
                    V2TotpCredential(
                        id = credentialId,
                        accountId = accountId,
                        secretBase32 = validated.secretBase32,
                        algorithm = validated.algorithm,
                        digits = validated.digits ?: 0,
                        periodSeconds = validated.period ?: 0,
                        createdAt = validated.createdAt ?: now,
                        legacySourceId = validated.legacySourceId ?: validated.id,
                    )
                )
            } else {
                report.notImported.add(
                    NotImportedEntry(
                        sourceId = validated.legacySourceId ?: validated.id,
                        kind = "totp",
                        serviceName = normalizedIssuer(validated.issuer),
                        accountName = validated.accountName,
                        reason = validated.invalidationReason ?: "unknown",
                        rawAlgorithm = validated.algorithm,
                        rawDigits = validated.digits,
                        rawPeriod = validated.period,
                    )
                )
            }
        }

        // Third pass: attach recovery code sets.
        for (set in validRecovery) {
            val legacyId = set.legacyAccountId ?: continue
            val account = accountByLegacyId.getValue(legacyId)
            val accountId = account.id
            val setId = UUID.randomUUID().toString()
            val created = set.createdAt ?: now
            account.recoveryCodeSets.add(
                V2RecoveryCodeSet(
                    id = setId,
                    accountId = accountId,
                    title = set.title.trim().ifEmpty { "Recovery codes" },
                    createdAt = created,
                    legacySourceId = set.legacySourceId ?: set.id,
                    codes = set.codes.mapIndexed { i, code ->
                        V2RecoveryCode(
                            id = UUID.randomUUID().toString(),
                            setId = setId,
                            value = code,
                            status = "UNUSED",
                            sortOrder = i,
                        )
                    },
                )
            )
        }
    }

    private fun normalizedIssuer(issuer: String): String =
        issuer.trim().ifEmpty { "Untitled" }

    private fun normalizedAccountName(accountName: String, issuer: String?): String {
        val trimmed = accountName.trim()
        return when {
            trimmed.isNotEmpty() -> trimmed
            !issuer.isNullOrBlank() -> issuer.trim()
            else -> "Account"
        }
    }
}

/** Result of mapping a legacy bundle: the accounts to import + a report. */
data class LegacyImportResult(
    val accounts: List<V2AuthAccount>,
    val report: LegacyImportReport,
)

/**
 * Explicit "what was NOT imported and why" report. Everything listed here is
 * excluded from the database import; raw source values are preserved so the
 * UI can show the exact legacy data that needs manual review.
 */
data class LegacyImportReport(
    val notImported: MutableList<NotImportedEntry> = mutableListOf(),
    var totalTotpEntries: Int = 0,
    var totalRecoverySets: Int = 0,
) {
    val notImportedCount: Int get() = notImported.size
}

data class NotImportedEntry(
    val sourceId: String,
    val kind: String,
    val serviceName: String,
    val accountName: String,
    val reason: String,
    val rawAlgorithm: String,
    val rawDigits: Int?,
    val rawPeriod: Int?,
)
