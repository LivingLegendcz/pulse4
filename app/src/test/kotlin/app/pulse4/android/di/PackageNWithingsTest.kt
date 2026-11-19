package app.pulse4.android.di

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.pulse4.android.net.OkHttpGateway
import app.pulse4.android.security.SecretRecoveryState
import app.pulse4.android.security.SecretSealer
import app.pulse4.withings.WithingsExtendResult
import app.pulse4.withings.WithingsUserProfile
import app.pulse4.withings.WithingsWebClient
import app.pulse4.withings.WithingsWebSession
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Package N (re-audit #4): R4-4 manual login must not stop imports, R4-5 disconnect off the UI thread with a
 * bounded wait + abort signal, R4-9 stored password never shown / remember choice persisted / exact code
 * lengths / background password login aborts between requests.
 */
class PackageNWithingsTest {

    private class InMemoryAuthStore : WithingsAuthStateStore {
        val map = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun delete(key: String) { map.remove(key) }
    }

    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        SecretRecoveryState.clear()
        tempDirs.forEach { it.deleteRecursively() }
    }

    private val validSession = WithingsWebSession(
        cookies = "session_key=abc",
        sessionToken = "tok",
        primaryUserId = 42L,
        primaryUserName = "Test",
    )

    private fun ok() = WithingsLoginResult.Ok(
        session = validSession,
        profiles = listOf(WithingsUserProfile(id = 42L, firstName = "T", lastName = "", shortName = "T", gender = 1, birthdate = 0L, isPrimary = true)),
    )

    private fun coordinator(
        store: InMemoryAuthStore,
        session: () -> WithingsWebSession?,
        now: () -> Long = { 50_000_000L },
        sso: () -> WithingsLoginResult = { WithingsLoginResult.Error("SSO expired", WithingsAuthFailureKind.OTHER) },
        login: (String, String) -> WithingsLoginResult = { _, _ -> ok() },
        hasCookies: Boolean = true,
        shouldAbort: () -> Boolean = { false },
    ) = WithingsSessionCoordinator(
        store = store,
        nowMs = now,
        isDirectEnabled = { true },
        loadSession = session,
        saveSession = {},
        extendSession = { _, _ -> WithingsExtendResult.Unauthorized },
        hasUsableCookies = { hasCookies },
        trySilentSsoRefresh = sso,
        loginPassword = login,
        onSilentRefreshOk = { _, _ -> },
        shouldAbort = shouldAbort,
    )

    // ---------------- R4-4 ----------------

    @Test
    fun `R4-4 cancelled or wrong code during manual login never pauses auto relogin`() {
        val store = InMemoryAuthStore()
        val coord = coordinator(store, session = { validSession })

        coord.recordManualAuthResult(WithingsLoginResult.NeedsMfa("u@example.com", "code please"))
        assertFalse(coord.isAutoReloginPaused())
        // Wrong / expired code from resumeMfa comes back as OTHER.
        coord.recordManualAuthResult(WithingsLoginResult.Error("Neplatný kód", WithingsAuthFailureKind.OTHER))
        assertFalse(coord.isAutoReloginPaused())
        coord.recordManualAuthResult(WithingsLoginResult.Error("offline", WithingsAuthFailureKind.NETWORK))
        assertFalse(coord.isAutoReloginPaused())

        // Even without a valid session, NeedsMfa / OTHER from a manual attempt does not pause.
        val coordNoSession = coordinator(store, session = { null })
        coordNoSession.recordManualAuthResult(WithingsLoginResult.NeedsMfa("u@example.com", "code please"))
        coordNoSession.recordManualAuthResult(WithingsLoginResult.Error("x", WithingsAuthFailureKind.OTHER))
        assertFalse(coordNoSession.isAutoReloginPaused())
    }

    @Test
    fun `R4-4 invalid credentials or rate limit pause only when the session is not valid`() {
        val store = InMemoryAuthStore()
        val withSession = coordinator(store, session = { validSession })
        withSession.recordManualAuthResult(WithingsLoginResult.Error("bad", WithingsAuthFailureKind.INVALID_CREDENTIALS), "typo")
        assertFalse(withSession.isAutoReloginPaused())
        withSession.recordManualAuthResult(WithingsLoginResult.Error("429", WithingsAuthFailureKind.RATE_LIMITED))
        assertFalse(withSession.isAutoReloginPaused())

        val noSession = coordinator(store, session = { null })
        noSession.recordManualAuthResult(WithingsLoginResult.Error("429", WithingsAuthFailureKind.RATE_LIMITED))
        assertEquals(WithingsSessionCoordinator.REASON_RATE_LIMITED, noSession.pausedReason())
        noSession.clearAuthPauseAndBackoff()
        noSession.recordManualAuthResult(WithingsLoginResult.Error("bad", WithingsAuthFailureKind.INVALID_CREDENTIALS), "typo")
        assertEquals(WithingsSessionCoordinator.REASON_INVALID_CREDENTIALS, noSession.pausedReason())
    }

    @Test
    fun `R4-4 typo in a newly typed password keeps the stored password, rejected stored password is deleted`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "good-stored")
        val coord = coordinator(store, session = { validSession })

        coord.recordManualAuthResult(WithingsLoginResult.Error("bad", WithingsAuthFailureKind.INVALID_CREDENTIALS), attemptedPassword = "go0d-typo")
        assertEquals("good-stored", store.getString(WithingsSessionCoordinator.KEY_PASSWORD))

        coord.recordManualAuthResult(WithingsLoginResult.Error("bad", WithingsAuthFailureKind.INVALID_CREDENTIALS), attemptedPassword = "good-stored")
        assertNull(store.getString(WithingsSessionCoordinator.KEY_PASSWORD))
    }

    @Test
    fun `R4-4 after a cancelled manual code prompt background renewal still runs`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "u@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "pw")
        var ssoCalls = 0
        val coord = coordinator(store, session = { validSession }, sso = { ssoCalls++; ok() })
        coord.recordManualAuthResult(WithingsLoginResult.NeedsMfa("u@example.com", "code"))
        assertTrue(coord.renewSession(forceExtend = true))
        assertEquals(1, ssoCalls)
    }

    // ---------------- R4-9: background password login aborts between requests ----------------

    @Test
    fun `R4-9 aborted background password attempt does not consume the 6h throttle nor count as failure`() {
        val store = InMemoryAuthStore()
        store.putString(WithingsSessionCoordinator.KEY_EMAIL, "u@example.com")
        store.putString(WithingsSessionCoordinator.KEY_PASSWORD, "pw")
        val abort = AtomicBoolean(false)
        var logins = 0
        val coord = coordinator(
            store,
            session = { null },
            hasCookies = false,
            login = { _, _ ->
                logins++
                if (abort.get()) WithingsLoginResult.Error(WithingsDirectAuth.ABORTED_MESSAGE, WithingsAuthFailureKind.ABORTED) else ok()
            },
        )
        abort.set(true)
        assertFalse(coord.tryAutoReLogin())
        assertEquals(1, logins)
        assertNull(store.getString(WithingsSessionCoordinator.KEY_LAST_PASSWORD_ATTEMPT_MS))
        assertNull(store.getString(WithingsSessionCoordinator.KEY_OTHER_AUTH_FAILURES))
        assertFalse(coord.isAutoReloginPaused())

        // Manual login finished: the next background renewal may use the stored password right away.
        abort.set(false)
        assertTrue(coord.tryAutoReLogin())
        assertEquals(2, logins)
        assertEquals("pw", store.getString(WithingsSessionCoordinator.KEY_PASSWORD))
    }

    private fun htmlResponse(chain: Interceptor.Chain, body: String = "<html></html>"): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("text/html".toMediaType()))
            .build()

    @Test
    fun `R4-9 WithingsDirectAuth loginPassword polls abort between requests and never posts the password`() {
        val requests = mutableListOf<String>()
        val postedPassword = AtomicBoolean(false)
        val abortAfterRequests = AtomicInteger(Int.MAX_VALUE)
        val auth = WithingsDirectAuth(
            webClient = WithingsWebClient(OkHttpGateway()),
            clientCustomizer = { b ->
                b.addInterceptor { chain ->
                    val req = chain.request()
                    requests += "${req.method} ${req.url.encodedPath}"
                    req.body?.let { body ->
                        val buf = Buffer()
                        body.writeTo(buf)
                        if (buf.readUtf8().contains("secret-pass")) postedPassword.set(true)
                    }
                    htmlResponse(chain)
                }
            },
        )

        // Abort already pending: nothing is sent at all.
        val r0 = auth.loginPassword("u@example.com", "secret-pass", background = true, shouldAbort = { true })
        assertEquals(WithingsAuthFailureKind.ABORTED, (r0 as WithingsLoginResult.Error).kind)
        assertTrue(requests.isEmpty())

        // Abort raised after the first GET: the password page / POST must not follow.
        abortAfterRequests.set(1)
        val r1 = auth.loginPassword(
            "u@example.com", "secret-pass", background = true,
            shouldAbort = { requests.size >= abortAfterRequests.get() },
        )
        assertEquals(WithingsAuthFailureKind.ABORTED, (r1 as WithingsLoginResult.Error).kind)
        assertEquals(listOf("GET /new_workflow/login"), requests)

        // Abort raised after the password page GET: still no POST with the password.
        requests.clear()
        abortAfterRequests.set(2)
        val r2 = auth.loginPassword(
            "u@example.com", "secret-pass", background = true,
            shouldAbort = { requests.size >= abortAfterRequests.get() },
        )
        assertEquals(WithingsAuthFailureKind.ABORTED, (r2 as WithingsLoginResult.Error).kind)
        assertEquals(listOf("GET /new_workflow/login", "GET /new_workflow/password_check"), requests)
        assertFalse(postedPassword.get())
    }

    @Test
    fun `R4-9 code prompt is typed by workflow step and TOTP is described as authenticator app`() {
        val requests = mutableListOf<String>()
        val auth = WithingsDirectAuth(
            webClient = WithingsWebClient(OkHttpGateway()),
            clientCustomizer = { b ->
                b.addInterceptor { chain ->
                    val req = chain.request()
                    requests += "${req.method} ${req.url.encodedPath}"
                    if (req.method == "POST") {
                        // Password accepted, Withings redirects to the TOTP step.
                        Response.Builder()
                            .request(req.newBuilder().url("https://account.withings.com/new_workflow/totp?r=x").build())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("<form><input name=\"code\"></form>".toResponseBody("text/html".toMediaType()))
                            .build()
                    } else {
                        htmlResponse(chain)
                    }
                }
            },
        )
        val r = auth.loginPassword("u@example.com", "pw")
        val mfa = r as WithingsLoginResult.NeedsMfa
        assertEquals(WithingsCodeKind.TOTP, mfa.codeKind)
        assertTrue(mfa.message.contains("ověřovací aplikace (TOTP)"))
        assertFalse(mfa.message.contains("SMS"))
        assertEquals(WithingsCodeKind.TOTP, auth.pendingCodeKind)
    }

    @Test
    fun `R4-9 code length is exact per kind - e-mail 8, TOTP 6`() {
        assertEquals(WithingsCodeKind.EMAIL, WithingsCodeKind.fromUrl("https://account.withings.com/new_workflow/email_check?r=x"))
        assertEquals(WithingsCodeKind.TOTP, WithingsCodeKind.fromUrl("https://account.withings.com/new_workflow/totp?r=x"))
        assertEquals(WithingsCodeKind.SMS, WithingsCodeKind.fromUrl("https://account.withings.com/new_workflow/phone_check"))

        assertFalse(WithingsCodeKind.EMAIL.accepts("123456"))
        assertFalse(WithingsCodeKind.EMAIL.accepts("1234567"))
        assertTrue(WithingsCodeKind.EMAIL.accepts("12345678"))
        assertTrue(WithingsCodeKind.EMAIL.accepts("1234-5678"))
        assertFalse(WithingsCodeKind.EMAIL.accepts("123456789"))
        assertTrue(WithingsCodeKind.TOTP.accepts("123456"))
        assertFalse(WithingsCodeKind.TOTP.accepts("12345678"))
        assertFalse(WithingsCodeKind.TOTP.accepts("12345a"))
    }

    // ---------------- R4-9: stored password + remember choice ----------------

    @Test
    fun `R4-9 password vault resolves stored password without exposing it and persists the remember choice`() {
        val store = InMemoryAuthStore()
        val vault = WithingsPasswordVault(store)
        assertFalse(vault.hasStoredPassword())
        assertTrue("default remember = on", vault.rememberPreference())

        vault.onLoginSucceeded("first-pass", remember = true)
        assertTrue(vault.hasStoredPassword())
        assertEquals("first-pass", vault.resolve(null))
        assertEquals("typed", vault.resolve("typed"))
        assertNull(vault.resolve(""))

        // Code-only login (no password in this attempt) keeps the stored password.
        vault.onLoginSucceeded(null, remember = true)
        assertEquals("first-pass", store.getString(WithingsPasswordVault.KEY_PASSWORD))

        // Unticking "Pamatovat heslo" is persisted and immediately removes the stored password.
        vault.setRememberPreference(false)
        assertFalse(vault.rememberPreference())
        assertFalse(vault.hasStoredPassword())
        assertFalse(WithingsPasswordVault(store).rememberPreference())

        // Login with remember off never stores the password.
        vault.onLoginSucceeded("second-pass", remember = false)
        assertFalse(vault.hasStoredPassword())
        assertFalse(vault.rememberPreference())

        vault.onLoginSucceeded("third-pass", remember = true)
        assertTrue(WithingsPasswordVault(store).rememberPreference())
        assertEquals("third-pass", store.getString(WithingsPasswordVault.KEY_PASSWORD))
    }

    // ---------------- R4-5: disconnect with bounded wait + abort signal ----------------

    private fun serviceLocator(): ServiceLocator {
        val dir = Files.createTempDirectory("pulse4-pkg-n-").toFile().also { tempDirs += it }
        val prefs = InMemoryPrefs()
        val ctx = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = dir
            override fun getFilesDir(): File = dir
            override fun getDatabasePath(name: String): File = File(dir, name)
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
        val sealer = object : SecretSealer {
            override fun seal(plain: ByteArray): ByteArray = ByteArray(12) { 1 } + plain + ByteArray(16) { 2 }
            override fun open(sealed: ByteArray): ByteArray = sealed.copyOfRange(12, sealed.size - 16)
            override fun hasMasterKey(): Boolean = true
        }
        return ServiceLocator(ctx, sealerOverride = sealer)
    }

    @Test
    fun `R4-5 disconnect signals abort to a background renewal and completes without waiting for it`() {
        val sl = serviceLocator()
        val withings = sl.withings
        sl.secrets.putString(WithingsPasswordVault.KEY_PASSWORD, "pw")
        sl.secrets.putString(WithingsService.WEB_SESSION_KEY, "{}")

        val bgHolding = CountDownLatch(1)
        val bgSawAbort = AtomicBoolean(false)
        val bg = Thread {
            withings.authGate.background {
                bgHolding.countDown()
                // Fake slow renewal polling the abort flag between "HTTP calls" (max 20 s).
                repeat(400) {
                    if (withings.authGate.manualPending()) { bgSawAbort.set(true); return@background }
                    Thread.sleep(50)
                }
            }
        }
        bg.start()
        assertTrue(bgHolding.await(2, TimeUnit.SECONDS))

        val t0 = System.nanoTime()
        assertTrue(withings.disconnect())
        val waitedMs = (System.nanoTime() - t0) / 1_000_000L
        assertTrue("disconnect waited $waitedMs ms", waitedMs < WithingsAuthGate.DISCONNECT_WAIT_TIMEOUT_MS)
        assertTrue(bgSawAbort.get())
        assertNull(sl.secrets.getString(WithingsPasswordVault.KEY_PASSWORD))
        assertNull(sl.secrets.getString(WithingsService.WEB_SESSION_KEY))
        bg.join(2_000)
    }

    @Test
    fun `R4-5 disconnect gives up after the bounded wait when background work ignores abort`() {
        val sl = serviceLocator()
        val withings = sl.withings
        sl.secrets.putString(WithingsPasswordVault.KEY_PASSWORD, "pw")

        val bgHolding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bg = Thread {
            withings.authGate.background {
                bgHolding.countDown()
                release.await(20, TimeUnit.SECONDS) // ignores the abort flag
            }
        }
        bg.start()
        assertTrue(bgHolding.await(2, TimeUnit.SECONDS))

        val t0 = System.nanoTime()
        assertFalse(withings.disconnect())
        val waitedMs = (System.nanoTime() - t0) / 1_000_000L
        assertTrue("waited $waitedMs ms", waitedMs >= WithingsAuthGate.DISCONNECT_WAIT_TIMEOUT_MS - 200)
        assertTrue("waited $waitedMs ms", waitedMs < WithingsAuthGate.DISCONNECT_WAIT_TIMEOUT_MS + 3_000)
        // Nothing was half-deleted.
        assertEquals("pw", sl.secrets.getString(WithingsPasswordVault.KEY_PASSWORD))
        release.countDown()
        bg.join(2_000)
    }

    @Test
    fun `R4-5 disconnect is never called on the UI thread`() {
        val root = File("src/main/kotlin/app/pulse4/android/ui")
        assertTrue(root.exists())
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val t = line.trim()
                if (t.contains("sl.withings.disconnect()") && !t.contains("withContext(Dispatchers.IO)")) "${f.name}:${i + 1}: $t" else null
            }
        }.toList()
        assertTrue("disconnect() outside withContext(Dispatchers.IO): $offenders", offenders.isEmpty())
    }

    private class InMemoryPrefs : SharedPreferences {
        private val data = java.util.concurrent.ConcurrentHashMap<String, Any>()
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private val removals = LinkedHashSet<String>()
            private var clearAll = false
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) removals.add(key) }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) data.clear()
                removals.forEach { data.remove(it) }
                pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
            }
        }
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
