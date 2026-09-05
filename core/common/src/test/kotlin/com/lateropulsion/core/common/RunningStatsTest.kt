package com.lateropulsion.core.common

import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class RunningStatsTest {
    @Test
    fun `REQ-MET-001 Welford matches two-pass statistics`() = runBlocking {
        checkAll(Arb.list(Arb.double(-90.0, 90.0), 2..500)) { xs ->
            val s = RunningStats()
            xs.forEach(s::add)
            val mean = xs.average()
            val variance = xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1)
            assertEquals(mean, s.mean, 1e-9)
            assertEquals(variance, s.variance, 1e-6)
            assertEquals(xs.sumOf { abs(it) } / xs.size, s.meanAbs, 1e-9)
            assertEquals(sqrt(xs.sumOf { it * it } / xs.size), s.rms, 1e-9)
            assertEquals(xs.maxOf { abs(it) }, s.maxAbs, 0.0)
            assertEquals(xs.min(), s.min, 0.0)
            assertEquals(xs.max(), s.max, 0.0)
        }
    }

    @Test
    fun `empty and single sample edge cases`() {
        val s = RunningStats()
        assertTrue(s.variance.isNaN())
        assertTrue(s.meanAbs.isNaN())
        s.add(3.0)
        assertTrue(s.variance.isNaN())
        assertEquals(3.0, s.mean)
        assertEquals(3.0, s.rms)
    }

    @Test
    fun `linear regression recovers slope and intercept`() {
        val r = LinearRegression()
        for (i in 0 until 100) r.add(i.toDouble(), 2.5 + 0.75 * i)
        assertEquals(0.75, r.slope, 1e-9)
        assertEquals(2.5, r.intercept, 1e-9)
    }
}
