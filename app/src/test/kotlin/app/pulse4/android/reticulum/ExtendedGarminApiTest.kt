package app.pulse4.android.reticulum

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `NEW-13 extractTicket accepts ST tickets only and rejects invalid ticket strings`() {
        assertEquals(
            "ST-12345-abc_XYZ",
            ExtendedGarminApi.extractTicket("", "https://sso.garmin.com/sso/embed?ticket=ST-12345-abc_XYZ"),
        )
        assertEquals(
            "ST-999-cas",
            ExtendedGarminApi.extractTicket("""{"serviceTicketId":"ST-999-cas"}""", "https://sso.garmin.com/sso/signin"),
        )
        assertNull(
            ExtendedGarminApi.extractTicket("error ticket=invalid_placeholder", "https://sso.garmin.com/sso/signin"),
        )
        assertNull(
            ExtendedGarminApi.extractTicket("""{"serviceTicketId":"not-a-ticket"}""", "https://sso.garmin.com/sso/signin?ticket=12345"),
        )
    }

    @Test
    fun `NEW-13 resumeMfa resets KEY_LOGIN_FAILURES and cooldown on MFA success`() {
        val storeMap = ConcurrentHashMap<String, String>()
        storeMap[ExtendedGarminApi.KEY_LOGIN_FAILURES] = "1"
        storeMap[ExtendedGarminApi.KEY_LOGIN_COOLDOWN_UNTIL] = "1000"
        val storage = object : GarminSecretStorage {
            override fun getString(key: String): String? = storeMap[key]
            override fun putString(key: String, value: String) { storeMap[key] = value }
            override fun delete(key: String) { storeMap.remove(key) }
        }

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/sso/embed") { ex ->
                val body = "<html>ok</html>".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            server.createContext("/sso/signin") { ex ->
                val body = if (ex.requestMethod == "GET") {
                    """<form><input type="hidden" name="_csrf" value="csrf-123"/></form>""".toByteArray()
                } else {
                    """<form id="loginEnterMfaCode"><input type="hidden" name="_csrf" value="csrf-mfa"/></form>""".toByteArray()
                }
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            server.createContext("/sso/verifyMFA/loginEnterMfaCode") { ex ->
                val body = """<script>window.location="?ticket=ST-777-mfa-ok";</script>""".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            server.createContext("/oauth/token") { ex ->
                val body = """{"access_token":"acc-tok","refresh_token":"ref-tok","expires_in":3600}""".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            server.start()

            val base = "http://127.0.0.1:${server.address.port}"
            val api = ExtendedGarminApi(
                secretStorage = storage,
                ssoBaseUrl = "$base/sso",
                diTokenUrl = "$base/oauth/token",
                connectApiBaseUrl = base,
            )
            assertEquals(LoginResult.NeedsMfa, api.loginPassword("user@example.com", "secret"))
            assertEquals(LoginResult.Ok, api.resumeMfa("123456"))
            assertNull(storeMap[ExtendedGarminApi.KEY_LOGIN_FAILURES])
            assertNull(storeMap[ExtendedGarminApi.KEY_LOGIN_COOLDOWN_UNTIL])
            assertTrue(storeMap.containsKey("garmin.ext.tokens"))
        } finally {
            server.stop(0)
        }
    }
}
