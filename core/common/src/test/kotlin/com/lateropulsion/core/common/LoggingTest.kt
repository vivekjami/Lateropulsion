package com.lateropulsion.core.common

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LoggingTest {
    private val captured = mutableListOf<Triple<LogLevel, String, Map<String, Any?>>>()
    private val sink = object : LogSink {
        override fun log(level: LogLevel, tag: String, message: String, fields: Map<String, Any?>, throwable: Throwable?) {
            captured += Triple(level, message, fields)
        }
    }

    @AfterEach
    fun tearDown() { LpLog.clearSinks(); LpLog.minLevel = LogLevel.DEBUG }

    @Test
    fun `REQ-SEC-004 PhiGuard rejects identifying keys`() {
        assertFalse(PhiGuard.isSafeKey("patient_name"))
        assertFalse(PhiGuard.isSafeKey("name"))
        assertFalse(PhiGuard.isSafeKey("firstName"))
        assertFalse(PhiGuard.isSafeKey("identity.name"))
        assertFalse(PhiGuard.isSafeKey("mrn"))
        assertFalse(PhiGuard.isSafeKey("contact.phone"))
        assertFalse(PhiGuard.isSafeKey("dob"))
        assertTrue(PhiGuard.isSafeKey("patient_id"))
        assertTrue(PhiGuard.isSafeKey("session_id"))
        assertTrue(PhiGuard.isSafeKey("theta_deg"))
        assertTrue(PhiGuard.isSafeKey("protocol_name_key")) // protocol names are not PHI
    }

    @Test
    fun `REQ-SEC-004 logging a PHI key throws in strict mode`() {
        LpLog.addSink(sink)
        assertThrows(IllegalStateException::class.java) { LpLog.i("T", "hello", "name" to "Someone") }
        assertTrue(captured.isEmpty())
    }

    @Test
    fun `structured fields reach the sink and level filter applies`() {
        LpLog.addSink(sink)
        LpLog.minLevel = LogLevel.INFO
        LpLog.d("T", "dropped")
        LpLog.w("T", "kept", "session_id" to "abc")
        assertEquals(1, captured.size)
        assertEquals("abc", captured[0].third["session_id"])
    }

    @Test
    fun `shortId keeps a correlatable prefix only`() {
        assertEquals("12345678…", Redaction.shortId("123456789"))
        assertEquals("short", Redaction.shortId("short"))
    }
}
