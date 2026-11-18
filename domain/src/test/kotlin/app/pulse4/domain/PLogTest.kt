package app.pulse4.domain

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PLogTest {
    private val prevDebug = PLog.isDebug
    private val prevSink = PLog.sink

    @AfterTest
    fun tearDown() {
        PLog.isDebug = prevDebug
        PLog.sink = prevSink
    }

    @Test
    fun `redact scrubs emails, bearer tokens, secret key-values and truncates long strings`() {
        val raw = "User alice.smith+test@example.com failed with Bearer eyJhbGciOi.123 and password=supersecret " +
            "sessionid=abc123xyz " + "x".repeat(300)
        val out = PLog.redact(raw)

        assertFalse(out.contains("alice.smith"), "email leaked: $out")
        assertTrue(out.contains("<email>"), "missing email placeholder: $out")
        assertFalse(out.contains("eyJhbGciOi"), "bearer token leaked: $out")
        assertTrue(out.contains("Bearer <redacted>"), "missing bearer placeholder: $out")
        assertFalse(out.contains("supersecret"), "password leaked: $out")
        assertTrue(out.contains("password=<redacted>"), "missing password placeholder: $out")
        assertFalse(out.contains("abc123xyz"), "sessionid leaked: $out")
        assertTrue(out.endsWith("..."), "expected truncation: $out")
        assertTrue(out.length <= 203, "length exceeded cap: ${out.length}")
    }

    @Test
    fun `release mode suppresses d and i and redacts w and e`() {
        val captured = mutableListOf<Triple<PLog.Level, String, String>>()
        PLog.isDebug = false
        PLog.sink = PLog.Sink { level, tag, message, _ ->
            captured += Triple(level, tag, message)
        }

        PLog.d("Tag") { "debug secret user@example.com" }
        PLog.i("Tag") { "info secret user@example.com" }
        PLog.w("Tag") { "warn user@example.com" }
        PLog.e("Tag", IllegalStateException("boom")) { "err token=secret123" }

        assertEquals(2, captured.size)
        assertEquals(PLog.Level.WARN, captured[0].first)
        assertEquals("warn <email>", captured[0].third)
        assertEquals(PLog.Level.ERROR, captured[1].first)
        assertEquals("err token=<redacted>", captured[1].third)
    }

    @Test
    fun `debug mode emits d and i without redaction`() {
        val captured = mutableListOf<PLog.Level>()
        PLog.isDebug = true
        PLog.sink = PLog.Sink { level, _, _, _ -> captured += level }

        PLog.d("Tag") { "dbg" }
        PLog.i("Tag") { "inf" }
        assertEquals(listOf(PLog.Level.DEBUG, PLog.Level.INFO), captured)
    }
}
