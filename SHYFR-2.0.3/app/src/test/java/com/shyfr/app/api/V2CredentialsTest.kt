package com.shyfr.app.api

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class V2CredentialsTest {
    @Test fun saltIsDeterministicAndCaseInsensitive() {
        assertArrayEquals(V2Credentials.saltFor("Alice"), V2Credentials.saltFor(" alice "))
        assertFalse(V2Credentials.saltFor("alice").contentEquals(V2Credentials.saltFor("bob")))
        assertEquals(16, V2Credentials.saltFor("alice").size)
    }
    @Test fun secretIsAcceptedByServerFormat() {
        val s = V2Credentials.secretFromRoot(ByteArray(32) { it.toByte() })
        assertEquals(43, s.length); assertTrue(s.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertEquals(32, Base64.getUrlDecoder().decode(s).size)     // serwer wymaga 32..128 bajtów
        assertEquals(s, V2Credentials.secretFromRoot(ByteArray(32) { it.toByte() }))
        assertNotEquals(s, V2Credentials.secretFromRoot(ByteArray(32) { 1 }))
    }
}
