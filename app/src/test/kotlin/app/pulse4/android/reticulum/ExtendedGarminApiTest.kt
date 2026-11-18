package app.pulse4.android.reticulum

import kotlin.test.Test
import kotlin.test.assertEquals

class ExtendedGarminApiTest {
    @Test
    fun `ticket classification wins even if html mentions locked or incorrect`() {
        val result = ExtendedGarminApi.classifySsoLoginResponse(
            code = 200,
            body = "<html><body>If your account is locked... ticket=ST-12345</body></html>",
            finalUrl = "https://sso.garmin.com/sso/embed?ticket=ST-12345",
            hasTicket = true,
        )
        assertEquals(ExtendedGarminApi.Companion.SsoLoginClassification.TICKET, result)
    }

    @Test
    fun `mfa classification wins before error keywords`() {
        val result = ExtendedGarminApi.classifySsoLoginResponse(
            code = 200,
            body = """<form id="mfa-code">If incorrect code, account may be locked</form>""",
            finalUrl = "https://sso.garmin.com/sso/verifyMFA/loginEnterMfaCode",
            hasTicket = false,
        )
        assertEquals(ExtendedGarminApi.Companion.SsoLoginClassification.MFA, result)
    }

    @Test
    fun `rate limit 429 or 403 wins over everything`() {
        val result = ExtendedGarminApi.classifySsoLoginResponse(
            code = 429,
            body = "Too Many Requests",
            finalUrl = "https://sso.garmin.com/sso/signin",
            hasTicket = false,
        )
        assertEquals(ExtendedGarminApi.Companion.SsoLoginClassification.RATE_LIMITED, result)
    }

    @Test
    fun `unauthorized when no ticket or mfa and error keyword present`() {
        val result = ExtendedGarminApi.classifySsoLoginResponse(
            code = 200,
            body = "Invalid credentials",
            finalUrl = "https://sso.garmin.com/sso/signin",
            hasTicket = false,
        )
        assertEquals(ExtendedGarminApi.Companion.SsoLoginClassification.UNAUTHORIZED, result)
    }
}
