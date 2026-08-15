package com.rescueauth.v2.migration

/**
 * In-memory multi-QR migration batch session (Phase 4 P2).
 *
 * Google Authenticator can split one export across several QR codes. Each QR
 * carries batch metadata (batchId / batchIndex / batchSize). When
 * batchSize > 1 the scanner enters a temporary collection session:
 *
 * - parts are accumulated per (batchId, index);
 * - the same frame scanned twice is idempotent (same index + same payload);
 * - parts are collected out of order;
 * - when all indexes `0..batchSize-1` are present the session yields the
 *   complete candidate list.
 *
 * The session is deliberately **in-memory only** (no persistence): an app
 * kill mid-batch simply discards the partial collection.
 *
 * ## Strictness
 *
 * - a part from a different batchId is rejected;
 * - a part with an inconsistent batchSize is rejected;
 * - an index outside `0..batchSize-1` is rejected;
 * - the same index re-scanned with a *different* payload is rejected.
 *
 * Rejections return a typed [BatchError] with a stable reason token; they
 * never throw and never drop previously accepted parts.
 */
class MigrationBatchSession {

    class BatchError(val reason: String) : Exception("batch error ($reason)")

    private data class AcceptedPart(
        val batchId: Int,
        val batchSize: Int,
        val index: Int,
        val candidates: List<MigrationTotpCandidate>,
        val raw: String,
    )

    /** Human-readable progress, e.g. `2 / 4`. */
    data class Progress(val collected: Int, val total: Int)

    private var activeBatchId: Int? = null
    private var activeBatchSize: Int = 0
    private val parts = LinkedHashMap<Int, AcceptedPart>()

    val isActive: Boolean get() = activeBatchId != null
    val collectedCount: Int get() = parts.size

    /** Progress of the active session, or null when idle. */
    val progress: Progress?
        get() = if (activeBatchId == null || activeBatchSize <= 0) {
            null
        } else {
            Progress(collected = parts.size, total = activeBatchSize)
        }

    /** True when every index of the active batch has been collected. */
    val isComplete: Boolean
        get() = activeBatchId != null && activeBatchSize > 0 && parts.size == activeBatchSize

    /**
     * Resets the session to idle (camera page left / cancel). Accepted parts
     * are dropped (memory-only).
     */
    fun reset() {
        activeBatchId = null
        activeBatchSize = 0
        parts.clear()
    }

    /**
     * Adds one scanned part. Throws [BatchError] on any violation (the
     * caller surfaces the reason to the user); a repeated identical part is
     * a no-op.
     *
     * @return the accumulated candidate list (never null once complete).
     */
    fun addPart(
        batchId: Int,
        batchIndex: Int,
        batchSize: Int,
        candidates: List<MigrationTotpCandidate>,
        rawPayload: String,
    ): List<MigrationTotpCandidate> {
        if (batchSize < 1) throw BatchError("invalid-batch-size")
        if (batchIndex < 0 || batchIndex >= batchSize) throw BatchError("batch-index-out-of-range")

        val current = activeBatchId
        if (current == null) {
            activeBatchId = batchId
            activeBatchSize = batchSize
        } else {
            if (current != batchId) throw BatchError("batch-id-mismatch")
            if (activeBatchSize != batchSize) throw BatchError("batch-size-mismatch")
        }

        val existing = parts[batchIndex]
        if (existing != null) {
            if (existing.raw != rawPayload) {
                throw BatchError("duplicate-index-different-payload")
            }
            // Repeated identical part: idempotent no-op.
            return if (isComplete) allCandidates() else emptyList()
        }

        parts[batchIndex] = AcceptedPart(
            batchId = batchId,
            batchSize = batchSize,
            index = batchIndex,
            candidates = candidates,
            raw = rawPayload,
        )
        return if (isComplete) allCandidates() else emptyList()
    }

    private fun allCandidates(): List<MigrationTotpCandidate> {
        val ordered = parts.values.sortedBy { it.index }
        val out = ArrayList<MigrationTotpCandidate>()
        for (part in ordered) out += part.candidates
        return out
    }
}
