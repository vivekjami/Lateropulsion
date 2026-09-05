package com.lateropulsion.app

import com.lateropulsion.app.auth.PinHasher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PinHasherTest {
    @Test
    fun `REQ-SEC-003 PBKDF2 hash verifies and rejects`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("482915", salt)
        assertEquals(64, hash.length)
        assertTrue(PinHasher.verify("482915", salt, hash))
        assertFalse(PinHasher.verify("482916", salt, hash))
        assertFalse(PinHasher.verify("482915", PinHasher.newSalt(), hash))
    }

    @Test
    fun `salts are unique and hashing is deterministic per salt`() {
        val a = PinHasher.newSalt(); val b = PinHasher.newSalt()
        assertNotEquals(a, b)
        assertEquals(32, a.length)
        assertEquals(PinHasher.hash("123456", a), PinHasher.hash("123456", a))
        assertNotEquals(PinHasher.hash("123456", a), PinHasher.hash("123456", b))
    }
}
