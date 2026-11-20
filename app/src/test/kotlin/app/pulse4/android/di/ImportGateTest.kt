package app.pulse4.android.di

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImportGateTest {
    @Test
    fun secondCallerIsSkippedImmediatelyWhileFirstRuns() {
        val gate = ImportGate()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<String> {
                gate.tryRun(onBusy = { "busy" }) {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    "ran"
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(gate.isBusy)
            val t0 = System.nanoTime()
            val second = pool.submit<String> { gate.tryRun(onBusy = { "busy" }) { "ran" } }.get(2, TimeUnit.SECONDS)
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            assertEquals("busy", second)
            assertTrue(elapsedMs < 1_000, "skipped caller must not wait for the running import (was $elapsedMs ms)")
            release.countDown()
            assertEquals("ran", first.get(5, TimeUnit.SECONDS))
            assertFalse(gate.isBusy)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun reentrantOnSameThreadSoNestedEntryPointsWork() {
        val gate = ImportGate()
        val r = gate.tryRun(onBusy = { "outer-busy" }) {
            gate.tryRun(onBusy = { "inner-busy" }) { "inner-ran" }
        }
        assertEquals("inner-ran", r)
        assertFalse(gate.isBusy)
    }

    @Test
    fun releasedAfterException() {
        val gate = ImportGate()
        assertFailsWith<IllegalStateException> { gate.tryRun(onBusy = { 0 }) { error("boom") } }
        assertFalse(gate.isBusy)
        assertEquals(1, gate.tryRun(onBusy = { 0 }) { 1 })
    }

    @Test
    fun runBlockingWaitsForRunningImportInsteadOfDropping() {
        val gate = ImportGate()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            pool.submit {
                gate.tryRun(onBusy = {}) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val waiter = pool.submit<String> { gate.runBlocking { "profile-switched" } }
            Thread.sleep(150)
            assertFalse(waiter.isDone, "blocking caller must wait while the import runs")
            release.countDown()
            assertEquals("profile-switched", waiter.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
