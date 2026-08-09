package com.rescueauth.v2.exportimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export / Import PIN Product Policy boundary tests (Issue #1 §9).
 *
 * Locks the final policy contract:
 * - Export charset = digits only, MIN 6, MAX 128, confirm must match.
 * - Import only rejects an empty PIN — any other codec-legal PIN (including
 *   historical short / non-numeric ones) must remain decodable. The Export UI
 *   policy is deliberately NOT a package-format requirement.
 */
class PinPolicyTest {

    private fun exportOk(pin: String, confirm: String = pin): Boolean =
        PinPolicy.validateExportPin(pin.toCharArray(), confirm.toCharArray()) is PinPolicy.Validation.Valid

    private fun exportReason(pin: String, confirm: String = pin): PinPolicy.Reason =
        (PinPolicy.validateExportPin(pin.toCharArray(), confirm.toCharArray()) as PinPolicy.Validation.Invalid).reason

    // ------------------------------------------------------------------
    // Export: valid
    // ------------------------------------------------------------------

    @Test
    fun `export accepts a 6-digit numeric PIN`() {
        assertTrue(exportOk("123456"))
    }

    @Test
    fun `export accepts exactly the minimum length`() {
        val min = PinPolicy.MIN_PIN_LENGTH
        val pin = "9".repeat(min)
        assertTrue(exportOk(pin))
    }

    @Test
    fun `export accepts exactly the maximum length`() {
        val max = PinPolicy.MAX_PIN_LENGTH
        val pin = "9".repeat(max)
        assertTrue(exportOk(pin))
    }

    @Test
    fun `export accepts long numeric PINs`() {
        assertTrue(exportOk("123456789012345678901234"))
    }

    // ------------------------------------------------------------------
    // Export: empty / weak-short rejected
    // ------------------------------------------------------------------

    @Test
    fun `empty PIN is rejected`() {
        assertEquals(PinPolicy.Reason.EMPTY, exportReason(""))
    }

    @Test
    fun `too-short PIN is rejected with TOO_SHORT reason`() {
        assertEquals(PinPolicy.Reason.TOO_SHORT, exportReason("12345"))
    }

    @Test
    fun `just-below-minimum is rejected`() {
        val min = PinPolicy.MIN_PIN_LENGTH
        assertFalse(exportOk("1".repeat(min - 1)))
    }

    @Test
    fun `over-maximum PIN is rejected`() {
        val max = PinPolicy.MAX_PIN_LENGTH
        assertEquals(PinPolicy.Reason.TOO_LONG, exportReason("1".repeat(max + 1)))
    }

    // ------------------------------------------------------------------
    // Export: charset
    // ------------------------------------------------------------------

    @Test
    fun `non-digit characters are rejected`() {
        assertEquals(PinPolicy.Reason.NON_DIGIT, exportReason("abcdef"))
        assertEquals(PinPolicy.Reason.NON_DIGIT, exportReason("12345a"))
        assertEquals(PinPolicy.Reason.NON_DIGIT, exportReason("12 3456"))
        assertEquals(PinPolicy.Reason.NON_DIGIT, exportReason("１２３４５６")) // full-width digits
        assertEquals(PinPolicy.Reason.NON_DIGIT, exportReason("12345-"))
    }

    @Test
    fun `only ASCII digits are in the allowed charset`() {
        for (c in '0'..'9') assertTrue(PinPolicy.isAllowedExportCharacter(c))
        assertFalse(PinPolicy.isAllowedExportCharacter('a'))
        assertFalse(PinPolicy.isAllowedExportCharacter(' '))
        assertFalse(PinPolicy.isAllowedExportCharacter('-'))
        assertFalse(PinPolicy.isAllowedExportCharacter('＋'))
    }

    // ------------------------------------------------------------------
    // Export: confirm mismatch
    // ------------------------------------------------------------------

    @Test
    fun `confirm mismatch is rejected with MISMATCH reason`() {
        assertEquals(PinPolicy.Reason.MISMATCH, exportReason("123456", "654321"))
    }

    @Test
    fun `confirm equal but different representation is accepted`() {
        val p = charArrayOf('1', '2', '3', '4', '5', '6')
        val c = "123456".toCharArray()
        assertTrue(PinPolicy.validateExportPin(p, c) is PinPolicy.Validation.Valid)
    }

    // ------------------------------------------------------------------
    // Import: only empty rejected — any codec-legal PIN stays decodable
    // ------------------------------------------------------------------

    @Test
    fun `import accepts short numeric PINs that export would reject`() {
        // Historical / third-party package with a 4-digit PIN must import.
        assertTrue(PinPolicy.validateImportPin("1234".toCharArray()) is PinPolicy.Validation.Valid)
    }

    @Test
    fun `import accepts non-numeric PINs that export would reject`() {
        // Historical package with a passphrase-style PIN must import.
        assertTrue(PinPolicy.validateImportPin("correct-horse-battery".toCharArray()) is PinPolicy.Validation.Valid)
    }

    @Test
    fun `import accepts single-character PIN`() {
        assertTrue(PinPolicy.validateImportPin("7".toCharArray()) is PinPolicy.Validation.Valid)
    }

    @Test
    fun `import accepts max-length PIN`() {
        val long = "x".repeat(300)
        assertTrue(PinPolicy.validateImportPin(long.toCharArray()) is PinPolicy.Validation.Valid)
    }

    @Test
    fun `import rejects empty PIN`() {
        assertEquals(
            PinPolicy.Reason.EMPTY,
            (PinPolicy.validateImportPin(CharArray(0)) as PinPolicy.Validation.Invalid).reason,
        )
    }

    // ------------------------------------------------------------------
    // Policy must not be scattered: constants live only in PinPolicy
    // ------------------------------------------------------------------

    @Test
    fun `policy constants are exactly the locked contract`() {
        assertEquals(6, PinPolicy.MIN_PIN_LENGTH)
        assertEquals(128, PinPolicy.MAX_PIN_LENGTH)
    }

    @Test
    fun `isValidPin matches the export policy`() {
        assertTrue(PinPolicy.isValidPin("123456".toCharArray()))
        assertFalse(PinPolicy.isValidPin("12345".toCharArray()))
        assertFalse(PinPolicy.isValidPin("abc123".toCharArray()))
        assertFalse(PinPolicy.isValidPin(CharArray(0)))
    }
}
