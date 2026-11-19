package app.pulse4.android.di

import app.pulse4.withings.WithingsExtendResult
import app.pulse4.withings.WithingsUserProfile
import app.pulse4.withings.WithingsWebSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manual login must not be blocked behind a background renewal holding the auth gate:
 * the background silent SSO polls [WithingsAuthGate.manualPending] between (fake) HTTP calls and
 * aborts; if it doesn't, the manual path gives up after its timeout instead of waiting forever.
 */
class WithingsAuthGateTest {

    private class InMemoryAuthStore : WithingsAuthStateStore {
        private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun delete(key: String) { map.remove(key) }
    }

    private val ok = WithingsLoginResult.Ok(
        session = WithingsWebSession(cookies = "c", sessionToken = null, primaryUserId = 1L, primaryUserName = "A"),
        profiles = listOf(WithingsUserProfile(id = 1L, firstName = "A", lastName = "", shortName = "A", gender = 1, birthdate = 0L, isPrimary = true)),
    )

    @Test
    fun `manual login preempts background silent SSO that polls the abort flag`() {
        val gate = WithingsAuthGate()
        val store = InMemoryAuthStore()
        val ssoStarted = CountDownLatch(1)
        val fakeHttpCalls = AtomicInteger(0)
        val coord = WithingsSessionCoordinator(
            store = store,
            isDirectEnabled = { true },
            loadSession = { null },
            saveSession = {},
            extendSession = { _, _ -> WithingsExtendResult.Unauthorized },
            hasUsableCookies = { true },
            // Fake slow SSO: up to 100 "HTTP calls" of 50 ms each (5 s), checking the abort flag between calls,
            // exactly like WithingsDirectAuth.trySilentSsoRefresh(shouldAbort).
            trySilentSsoRefresh = {
                ssoStarted.countDown()
                var aborted = false
                for (i in 0 until 100) {
                    if (gate.manualPending()) { aborted = true; break }
                    fakeHttpCalls.incrementAndGet()
                    Thread.sleep(50)
                }
                if (aborted) WithingsLoginResult.Error(WithingsDirectAuth.ABORTED_MESSAGE) else WithingsLoginResult.Error("SSO failed")
            },
            loginPassword = { _, _ -> ok },
            onSilentRefreshOk = { _, _ -> },
            shouldAbort = gate::manualPending,
        )

        val bgResult = AtomicBoolean(true)
        val bg = Thread { bgResult.set(gate.background { coord.renewSession(forceExtend = true) }) }
        bg.start()
        assertTrue(ssoStarted.await(2, TimeUnit.SECONDS))

        val waited = AtomicBoolean(false)
        val t0 = System.nanoTime()
        val res = gate.manual(timeoutMs = 3_000L, onWaiting = { waited.set(true) }) { "manual-done" }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        bg.join(5_000L)

        assertEquals(WithingsAuthGate.GateResult.Done("manual-done"), res)
        assertTrue("onWaiting must be called while the gate is held", waited.get())
        assertTrue("manual login waited too long: $elapsedMs ms", elapsedMs < 1_000L)
        assertTrue("background should have aborted early", fakeHttpCalls.get() < 100)
        assertFalse(bgResult.get())
        // Abort is not a failure: no circuit-breaker count, no pause, no network backoff.
        assertNull(store.getString(WithingsSessionCoordinator.KEY_OTHER_AUTH_FAILURES))
        assertFalse(coord.isAutoReloginPaused())
        assertEquals(0L, coord.netBackoffUntilMs())
    }

    @Test
    fun `manual login gives up after timeout when background ignores the abort flag`() {
        val gate = WithingsAuthGate()
        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bg = Thread {
            gate.background {
                holding.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        bg.start()
        assertTrue(holding.await(2, TimeUnit.SECONDS))

        var ran = false
        val t0 = System.nanoTime()
        val res = gate.manual(timeoutMs = 300L) { ran = true }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        release.countDown()
        bg.join(2_000L)

        assertEquals(WithingsAuthGate.GateResult.TimedOut, res)
        assertFalse(ran)
        assertTrue("timeout not honoured: $elapsedMs ms", elapsedMs in 250L..2_000L)
        assertFalse(gate.manualPending())
    }

    @Test
    fun `manual path is reentrant and does not call onWaiting when gate is free`() {
        val gate = WithingsAuthGate()
        var waited = false
        val res = gate.manual(timeoutMs = 100L, onWaiting = { waited = true }) {
            // completeWebLogin() takes the gate again from inside a manual login
            gate.background { gate.bumpGeneration() }
        }
        assertEquals(WithingsAuthGate.GateResult.Done(1L), res)
        assertFalse(waited)
    }

    @Test
    fun `stale background session save is dropped after a manual login`() {
        val gate = WithingsAuthGate()
        val startGen = gate.generation()
        var saved = 0
        assertTrue(gate.saveIfCurrent(startGen) { saved++ })
        gate.bumpGeneration() // manual login replaced the session
        assertFalse(gate.saveIfCurrent(startGen) { saved++ })
        assertEquals(1, saved)
    }
}
