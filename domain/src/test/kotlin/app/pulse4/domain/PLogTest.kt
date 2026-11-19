package app.pulse4.domain

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun `redact scrubs JSON tokens, camelCase secrets, service tickets, and cookies`() {
        val jsonRaw = """{"access_token":"tok-secret-1","refreshToken":"ref-secret-2","sessionToken":"sess-secret-3","authTicket":"auth-secret-4"}"""
        val jsonOut = PLog.redact(jsonRaw)
        assertFalse(jsonOut.contains("tok-secret-1"), "access_token leaked: $jsonOut")
        assertFalse(jsonOut.contains("ref-secret-2"), "refreshToken leaked: $jsonOut")
        assertFalse(jsonOut.contains("sess-secret-3"), "sessionToken leaked: $jsonOut")
        assertFalse(jsonOut.contains("auth-secret-4"), "authTicket leaked: $jsonOut")
        assertTrue(jsonOut.contains(""""access_token":"<redacted>""""), jsonOut)
        assertTrue(jsonOut.contains(""""refreshToken":"<redacted>""""), jsonOut)

        val kvRaw = "https://sso.garmin.com/sso/embed?ticket=ST-998877-AbCdEf_12&foo=ok sessionToken=cam1 refreshToken=cam2 authTicket=cam3"
        val kvOut = PLog.redact(kvRaw)
        assertFalse(kvOut.contains("ST-998877"), "ticket leaked: $kvOut")
        assertFalse(kvOut.contains("cam1"), "sessionToken leaked: $kvOut")
        assertFalse(kvOut.contains("cam2"), "refreshToken leaked: $kvOut")
        assertFalse(kvOut.contains("cam3"), "authTicket leaked: $kvOut")
        assertTrue(kvOut.contains("ticket=<redacted>&foo=ok"), kvOut)

        val cookieRaw = "Cookie: session_key=secret-cookie-val; w_uuid=12345\nSet-Cookie: JSESSIONID=xyz987; HttpOnly"
        val cookieOut = PLog.redact(cookieRaw)
        assertFalse(cookieOut.contains("secret-cookie-val"), "Cookie leaked: $cookieOut")
        assertFalse(cookieOut.contains("xyz987"), "Set-Cookie leaked: $cookieOut")
        assertTrue(cookieOut.contains("Cookie: <redacted>"), cookieOut)
        assertTrue(cookieOut.contains("Set-Cookie: <redacted>"), cookieOut)
    }

    @Test
    fun `release mode suppresses d and i, redacts w and e, and redacts throwable without stacktrace`() {
        val captured = mutableListOf<Triple<PLog.Level, String, Pair<String, Throwable?>>>()
        PLog.isDebug = false
        PLog.sink = PLog.Sink { level, tag, message, throwable ->
            captured += Triple(level, tag, message to throwable)
        }

        PLog.d("Tag") { "debug secret user@example.com" }
        PLog.i("Tag") { "info secret user@example.com" }
        PLog.w("Tag", IllegalArgumentException("bad Cookie: sid=secret-cookie")) { "warn user@example.com" }
        PLog.e("Tag", IllegalStateException("failed for alice@example.com with ticket=ST-12345-xyz")) { "err token=secret123" }

        assertEquals(2, captured.size)
        assertEquals(PLog.Level.WARN, captured[0].first)
        assertEquals("warn <email> (IllegalArgumentException: bad Cookie: <redacted>)", captured[0].third.first)
        assertNull(captured[0].third.second, "release mode must not forward raw throwable to sink")

        assertEquals(PLog.Level.ERROR, captured[1].first)
        assertFalse(captured[1].third.first.contains("alice@example.com"), captured[1].third.first)
        assertFalse(captured[1].third.first.contains("ST-12345"), captured[1].third.first)
        assertTrue(captured[1].third.first.contains("err token=<redacted>"), captured[1].third.first)
        assertTrue(captured[1].third.first.contains("IllegalStateException: failed for <email> with ticket=<redacted>"), captured[1].third.first)
        assertNull(captured[1].third.second, "release mode must not forward raw throwable to sink")
    }

    @Test
    fun `release mode redacts host port endpoints in w and e without mangling timestamps`() {
        val captured = mutableListOf<Pair<PLog.Level, String>>()
        PLog.isDebug = false
        PLog.sink = PLog.Sink { level, _, message, _ -> captured += level to message }

        PLog.w("ReticulumBridge") { "Gateway connect failed for 192.168.1.5:4242 at 18:45:00" }
        PLog.e("ReticulumBridge", java.io.IOException("connect node.example.com:4242 / [2001:db8::1]:4242")) {
            "TCP timeout localhost:4242"
        }

        assertEquals(2, captured.size)
        val warnMsg = captured[0].second
        assertFalse(warnMsg.contains("192.168.1.5"), warnMsg)
        assertFalse(warnMsg.contains(":4242"), warnMsg)
        assertTrue(warnMsg.contains("<host>:<port>"), warnMsg)
        assertTrue(warnMsg.contains("18:45:00"), "Timestamp should remain intact: $warnMsg")

        val errMsg = captured[1].second
        assertFalse(errMsg.contains("node.example.com"), errMsg)
        assertFalse(errMsg.contains("2001:db8::1"), errMsg)
        assertFalse(errMsg.contains("localhost:4242"), errMsg)
        assertTrue(errMsg.contains("<host>:<port>"), errMsg)
    }

    @Test
    fun `debug mode emits d and i without redaction and forwards throwable`() {
        val captured = mutableListOf<Pair<PLog.Level, Throwable?>>()
        PLog.isDebug = true
        PLog.sink = PLog.Sink { level, _, _, throwable -> captured += level to throwable }

        val err = IllegalStateException("debug details")
        PLog.d("Tag") { "dbg" }
        PLog.i("Tag") { "inf" }
        PLog.w("Tag", err) { "wrn" }
        assertEquals(
            listOf<Pair<PLog.Level, Throwable?>>(
                PLog.Level.DEBUG to null,
                PLog.Level.INFO to null,
                PLog.Level.WARN to err,
            ),
            captured,
        )
    }
}
