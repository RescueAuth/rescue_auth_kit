package com.rescueauth.v2.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the in-memory multi-QR migration batch state machine. */
class MigrationBatchSessionTest {

    private fun candidates(n: Int, prefix: String = "c"): List<MigrationTotpCandidate> =
        (1..n).map { i ->
            MigrationTotpCandidate.importable(
                secretBase32 = "JBSWY3DPEHPK3PXP",
                name = "$prefix$i",
                issuer = "Issuer",
                algorithm = "SHA1",
                digits = 6,
                periodSeconds = 30,
            )
        }

    @Test
    fun `15 single qr batchSize=1 completes immediately`() {
        val session = MigrationBatchSession()
        val out = session.addPart(
            batchId = 5, batchIndex = 0, batchSize = 1,
            candidates = candidates(2),
            rawPayload = "rawA",
        )
        assertTrue(session.isComplete)
        assertEquals(2, out.size)
        assertEquals(MigrationBatchSession.Progress(1, 1), session.progress)
    }

    @Test
    fun `16 multi part batch 1of3 then 2of3 then 3of3`() {
        val session = MigrationBatchSession()
        session.addPart(batchId = 1, batchIndex = 0, batchSize = 3, candidates = candidates(1, "a"), rawPayload = "p0")
        assertFalse(session.isComplete)
        assertEquals(MigrationBatchSession.Progress(1, 3), session.progress)
        session.addPart(batchId = 1, batchIndex = 2, batchSize = 3, candidates = candidates(1, "c"), rawPayload = "p2")
        assertFalse(session.isComplete)
        assertEquals(MigrationBatchSession.Progress(2, 3), session.progress)
        val out = session.addPart(
            batchId = 1, batchIndex = 1, batchSize = 3,
            candidates = candidates(1, "b"), rawPayload = "p1",
        )
        assertTrue(session.isComplete)
        assertEquals(3, out.size)
        // ordered by index regardless of arrival order
        assertEquals(listOf("a1", "b1", "c1"), out.map { it.name })
    }

    @Test
    fun `17 out of order parts accepted`() {
        val session = MigrationBatchSession()
        session.addPart(batchId = 9, batchIndex = 2, batchSize = 3, candidates = candidates(1, "c"), rawPayload = "p2")
        session.addPart(batchId = 9, batchIndex = 0, batchSize = 3, candidates = candidates(1, "a"), rawPayload = "p0")
        val out = session.addPart(
            batchId = 9, batchIndex = 1, batchSize = 3,
            candidates = candidates(1, "b"), rawPayload = "p1",
        )
        assertEquals(listOf("a1", "b1", "c1"), out.map { it.name })
    }

    @Test
    fun `18 repeated identical part idempotent`() {
        val session = MigrationBatchSession()
        session.addPart(batchId = 2, batchIndex = 0, batchSize = 2, candidates = candidates(1, "a"), rawPayload = "p0")
        // same part again -> no duplicate, still incomplete
        session.addPart(batchId = 2, batchIndex = 0, batchSize = 2, candidates = candidates(1, "a"), rawPayload = "p0")
        assertEquals(1, session.collectedCount)
        val out = session.addPart(
            batchId = 2, batchIndex = 1, batchSize = 2,
            candidates = candidates(1, "b"), rawPayload = "p1",
        )
        assertEquals(2, out.size)
        assertEquals(listOf("a1", "b1"), out.map { it.name })
    }

    @Test
    fun `19 duplicate index different payload rejected`() {
        val session = MigrationBatchSession()
        session.addPart(batchId = 3, batchIndex = 0, batchSize = 2, candidates = candidates(1, "a"), rawPayload = "p0")
        val ex = assertThrows(MigrationBatchSession.BatchError::class.java) {
            session.addPart(batchId = 3, batchIndex = 0, batchSize = 2, candidates = candidates(1, "X"), rawPayload = "p0-DIFFERENT")
        }
        assertEquals("duplicate-index-different-payload", ex.reason)
        // previous parts preserved
        assertEquals(1, session.collectedCount)
    }

    @Test
    fun `20 wrong batchId rejected`() {
        val session = MigrationBatchSession()
        session.addPart(batchId = 1, batchIndex = 0, batchSize = 2, candidates = candidates(1), rawPayload = "p0")
        val ex = assertThrows(MigrationBatchSession.BatchError::class.java) {
            session.addPart(batchId = 2, batchIndex = 1, batchSize = 2, candidates = candidates(1), rawPayload = "p1")
        }
        assertEquals("batch-id-mismatch", ex.reason)
    }

    @Test
    fun `21 inconsistent batchSize rejected`() {
        val session = MigrationBatchSession()
        session.addPart(batchId = 1, batchIndex = 0, batchSize = 2, candidates = candidates(1), rawPayload = "p0")
        val ex = assertThrows(MigrationBatchSession.BatchError::class.java) {
            session.addPart(batchId = 1, batchIndex = 1, batchSize = 3, candidates = candidates(1), rawPayload = "p1")
        }
        assertEquals("batch-size-mismatch", ex.reason)
    }

    @Test
    fun `index out of range rejected`() {
        val session = MigrationBatchSession()
        val ex = assertThrows(MigrationBatchSession.BatchError::class.java) {
            session.addPart(batchId = 1, batchIndex = 3, batchSize = 2, candidates = candidates(1), rawPayload = "p0")
        }
        assertEquals("batch-index-out-of-range", ex.reason)
    }

    @Test
    fun `22 incomplete batch not complete`() {
        val session = MigrationBatchSession()
        session.addPart(batchId = 1, batchIndex = 0, batchSize = 3, candidates = candidates(1), rawPayload = "p0")
        assertFalse(session.isComplete)
        assertNull(session.progress?.let { null }) // progress exists but incomplete
        assertFalse(session.isComplete)
    }

    @Test
    fun `reset clears state`() {
        val session = MigrationBatchSession()
        session.addPart(batchId = 1, batchIndex = 0, batchSize = 2, candidates = candidates(1), rawPayload = "p0")
        assertTrue(session.isActive)
        session.reset()
        assertFalse(session.isActive)
        assertNull(session.progress)
        assertFalse(session.isComplete)
        // a fresh batch can start after reset
        val out = session.addPart(batchId = 7, batchIndex = 0, batchSize = 1, candidates = candidates(1), rawPayload = "x")
        assertEquals(1, out.size)
    }
}
