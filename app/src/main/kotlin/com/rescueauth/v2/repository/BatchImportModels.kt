package com.rescueauth.v2.repository

/**
 * Batch TOTP import models (Phase 4 P2 — external otpauth-migration import
 * path). These are ordinary native TOTP inputs/outcomes; they deliberately do
 * NOT touch the Phase 3C Package/Merge domain (that stays on its own parallel
 * track). The migration feature can be deleted independently by removing its
 * parser + this repository API.
 */

/** One native TOTP input for batch import. */
data class TotpImportItem(
    val issuer: String,
    val accountName: String,
    val secretBase32: String,
    val algorithm: String,
    val digits: Int,
    val periodSeconds: Int,
)

enum class BatchEntryStatus {
    IMPORTED,
    DUPLICATE,
    UNSUPPORTED,
    INVALID,
}

/** Outcome of importing one [TotpImportItem]. */
data class TotpBatchImportEntry(
    val item: TotpImportItem,
    val status: BatchEntryStatus,
    val importedCredentialId: String?,
)

/** Aggregate result of a batch import. */
data class TotpBatchImportResult(
    val entries: List<TotpBatchImportEntry>,
) {
    val imported: List<TotpBatchImportEntry> get() = entries.filter { it.status == BatchEntryStatus.IMPORTED }
    val duplicates: List<TotpBatchImportEntry> get() = entries.filter { it.status == BatchEntryStatus.DUPLICATE }
    val unsupported: List<TotpBatchImportEntry> get() = entries.filter { it.status == BatchEntryStatus.UNSUPPORTED }
    val invalid: List<TotpBatchImportEntry> get() = entries.filter { it.status == BatchEntryStatus.INVALID }

    val importedCount: Int get() = imported.size
    val duplicateCount: Int get() = duplicates.size
    val unsupportedCount: Int get() = unsupported.size
    val invalidCount: Int get() = invalid.size
}
