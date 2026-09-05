package com.lateropulsion.core.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutcomeTest {
    @Test
    fun `map and flatMap propagate success`() {
        val r = Outcome.success(2).map { it * 3 }.flatMap { Outcome.success(it + 1) }
        assertEquals(7, r.getOrThrow())
    }

    @Test
    fun `failure short-circuits and getOrThrow raises LpException`() {
        val f: Outcome<Int> = Outcome.failure(LpError.Validation("age", "must be positive"))
        val mapped = f.map { it + 1 }
        assertTrue(mapped.isFailure)
        val ex = assertThrows(LpException::class.java) { mapped.getOrThrow() }
        assertEquals("VALIDATION", ex.error.code)
    }

    @Test
    fun `runOutcome converts exceptions`() {
        val r = runOutcome { error("boom") }
        assertEquals("UNEXPECTED", r.errorOrNull()?.code)
    }

    @Test
    fun `ValidationErrors collects and reports first`() {
        val v = ValidationErrors()
            .require(false, "a", "bad a")
            .require(true, "b", "bad b")
            .require(false, "c", "bad c")
        assertEquals(2, v.toList().size)
        assertEquals("a", (v.toOutcome { 1 }.errorOrNull() as LpError.Validation).field)
    }

    @Test
    fun `NotFound redacts the id`() {
        val e = LpError.NotFound("Patient", "0123456789abcdef")
        assertEquals("Patient not found: 01234567…", e.message)
    }
}
