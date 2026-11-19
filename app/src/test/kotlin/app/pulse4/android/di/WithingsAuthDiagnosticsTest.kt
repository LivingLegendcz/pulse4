package app.pulse4.android.di

import app.pulse4.withings.WithingsUserProfile
import app.pulse4.withings.WithingsWebSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WithingsAuthDiagnosticsTest {

    private val email = "jan.novak@example.com"
    private val password = "S3cret-Pa55!"
    private val cookie = "session_key=abcdef0123456789abcdef0123456789"
    private val token = "tok_9f8e7d6c5b4a39281706f5e4d3c2b1a0"

    private fun assertRedacted(text: String) {
        val lower = text.lowercase()
        for (secret in listOf(email, "jan.novak", password, cookie, "abcdef0123456789", token, "9f8e7d6c5b4a")) {
            assertFalse("leaked '$secret' in: $text", lower.contains(secret.lowercase()))
        }
        assertFalse("query string leaked: $text", text.contains("?"))
        assertFalse("'@' leaked: $text", text.contains("@"))
        assertFalse("cookie leaked: $text", lower.contains("session_key"))
    }

    @Test
    fun `urls are reduced to host and path and secrets never appear in export or persisted json`() {
        var persisted = ""
        val diag = WithingsAuthDiagnostics(persist = { persisted = it }, nowMs = { 1_700_000_000_000L })
        val flow = WithingsAuthDiagnostics.Flow.MANUAL_PASSWORD
        diag.mark(flow, "start")
        diag.step(flow, "GET login", "https://account.withings.com/new_workflow/login?r=https%3A%2F%2Fapp.withings.com%2F&email=$email&session_token=$token", 200, false)
        diag.step(flow, "POST password_check", "https://account.withings.com/new_workflow/password_check?password=$password#$cookie", 302, false)
        diag.step(flow, "GET user", "https://app.withings.com/u/$email/$token/dashboard", 200, true)
        diag.mark(flow, "step with $email")
        diag.result(flow, WithingsLoginResult.Error("Withings heslo odmítl pro $email", WithingsAuthFailureKind.INVALID_CREDENTIALS))
        diag.result(flow, WithingsLoginResult.NeedsMfa(email, "Kód byl odeslán na $email"))

        val export = diag.export()
        assertRedacted(export)
        assertRedacted(persisted)
        assertTrue(export.contains("account.withings.com/new_workflow/login HTTP 200 app=ne"))
        assertTrue(export.contains("account.withings.com/new_workflow/password_check HTTP 302"))
        assertTrue(export.contains("app.withings.com/u/<redacted>/<id>/dashboard HTTP 200 app=ano"))
        assertTrue(export.contains("→ Error(INVALID_CREDENTIALS)"))
        assertTrue(export.contains("→ NeedsMfa"))
    }

    @Test
    fun `ok result records only profile count`() {
        val diag = WithingsAuthDiagnostics()
        val ok = WithingsLoginResult.Ok(
            session = WithingsWebSession(cookies = cookie, sessionToken = token, primaryUserId = 7L, primaryUserName = "Jan", email = email),
            profiles = listOf(WithingsUserProfile(id = 7L, firstName = "Jan", lastName = "Novák", shortName = "JAN", gender = 0, birthdate = 0L, isPrimary = true, email = email)),
        )
        diag.result(WithingsAuthDiagnostics.Flow.SILENT_SSO, ok)
        val export = diag.export()
        assertRedacted(export)
        assertTrue(export.contains("SILENT_SSO result → Ok(profiles=1)"))
    }

    @Test
    fun `keeps at most 30 entries and survives reload from the store`() {
        var persisted: String? = null
        var t = 0L
        val diag = WithingsAuthDiagnostics(load = { persisted }, persist = { persisted = it }, nowMs = { t++ })
        repeat(45) { i -> diag.step(WithingsAuthDiagnostics.Flow.SILENT_SSO, "GET sso $i", "https://account.withings.com/x", 200, false) }
        assertEquals(WithingsAuthDiagnostics.MAX_ENTRIES, diag.entries().size)
        assertEquals("GET sso 15", diag.entries().first().step)

        val reloaded = WithingsAuthDiagnostics(load = { persisted }, persist = { persisted = it })
        assertEquals(30, reloaded.entries().size)
        assertEquals("GET sso 44", reloaded.entries().last().step)

        reloaded.clear()
        assertTrue(reloaded.entries().isEmpty())
        assertEquals("[]", persisted)
    }

    @Test
    fun `classifyPasswordLoginResponse treats email_check redirect as mfa`() {
        val r = WithingsDirectAuth.classifyPasswordLoginResponse(
            statusCode = 200,
            finalUrl = "https://account.withings.com/new_workflow/email_check?r=x",
            bodyText = "<html></html>",
            email = email,
        )
        assertTrue(r is WithingsLoginResult.NeedsMfa)
    }
}
