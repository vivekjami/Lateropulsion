package com.lateropulsion.core.common

import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnglesTest {
    @Test
    fun `wrapDeg stays in half-open range and preserves angle`() = runBlocking {
        checkAll(Arb.double(-100_000.0, 100_000.0)) { d ->
            val w = Angles.wrapDeg(d)
            assertTrue(w > -180.0 && w <= 180.0, "wrapped $d -> $w")
            val diff = ((d - w) / 360.0)
            assertEquals(Math.rint(diff), diff, 1e-6)
        }
    }

    @Test
    fun `boundary values`() {
        assertEquals(180.0, Angles.wrapDeg(180.0))
        assertEquals(180.0, Angles.wrapDeg(-180.0))
        assertEquals(-170.0, Angles.wrapDeg(190.0), 1e-12)
        assertEquals(10.0, Angles.diffDeg(-175.0, 175.0), 1e-12)
    }

    @Test
    fun `deg rad round trip`() {
        assertEquals(37.5, Angles.radToDeg(Angles.degToRad(37.5)), 1e-12)
    }
}
