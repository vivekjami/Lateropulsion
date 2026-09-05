package com.lateropulsion.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DisplayIdFormatTest {
    @Test
    fun `REQ-PAT-002 formats LP-YYYY-NNNN`() {
        assertEquals("LP-2026-0007", DisplayIdFormat.format(2026, 7))
        assertEquals("LP-2026-12345", DisplayIdFormat.format(2026, 12345))
    }

    @Test
    fun `REQ-PAT-002 parses and validates`() {
        assertEquals(2026 to 7, DisplayIdFormat.parse("lp-2026-0007"))
        assertTrue(DisplayIdFormat.isValid(" LP-2026-0007 "))
        assertNull(DisplayIdFormat.parse("LP-26-0007"))
        assertNull(DisplayIdFormat.parse("LP-2026-0000"))
        assertFalse(DisplayIdFormat.isValid("XX-2026-0001"))
    }

    @Test
    fun `rejects nonsense years and sequences`() {
        assertThrows(IllegalArgumentException::class.java) { DisplayIdFormat.format(1999, 1) }
        assertThrows(IllegalArgumentException::class.java) { DisplayIdFormat.format(2026, 0) }
    }
}
