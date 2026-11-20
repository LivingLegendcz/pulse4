package app.pulse4.android.ui

import app.pulse4.domain.MetricType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaleWhileRevalidateTest {
    @Test
    fun lastResultCacheIsLruBounded() {
        val c = LastResultCache<String, Int>(maxEntries = 2)
        c.put("a", 1); c.put("b", 2)
        c.get("a") // touch -> "b" becomes eldest
        c.put("c", 3)
        assertEquals(2, c.size())
        assertEquals(1, c.get("a"))
        assertNull(c.get("b"))
        assertEquals(3, c.get("c"))
    }

    @Test
    fun initialStateIsCachedValueAsStaleOrLoading() {
        val c = LastResultCache<String, Int>()
        assertEquals(LoadState.Loading, initialLoadState("k", c))
        c.put("k", 7)
        assertEquals(LoadState.Ready(7, stale = true), initialLoadState("k", c))
        assertEquals(LoadState.Loading, initialLoadState("other", c))
    }

    @Test
    fun successStoresResultAndReturnsFresh() = runBlocking {
        val c = LastResultCache<String, Int>()
        val s = loadWithCache("k", c) { 42 }
        assertEquals(LoadState.Ready(42, stale = false), s)
        assertEquals(42, c.get("k"))
    }

    @Test
    fun revalidationReplacesStaleValue() = runBlocking {
        val c = LastResultCache<String, Int>()
        c.put("k", 1)
        val first = initialLoadState("k", c)
        assertEquals(1, first.valueOrNull())
        val refreshed = loadWithCache("k", c) { 2 }
        assertEquals(2, refreshed.valueOrNull())
        assertEquals(2, c.get("k"))
    }

    @Test
    fun failureWithoutCacheIsVisibleFailedStateNotEndlessLoading() = runBlocking {
        val c = LastResultCache<String, Int>()
        val s = loadWithCache<String, Int>("k", c) { error("db locked") }
        assertIs<LoadState.Failed>(s)
        assertTrue(s.message.contains("db locked"))
        assertNull(s.valueOrNull())
    }

    @Test
    fun failureKeepsStaleValueWhenAvailable() = runBlocking {
        val c = LastResultCache<String, Int>()
        c.put("k", 5)
        val s = loadWithCache<String, Int>("k", c) { error("boom") }
        assertEquals(LoadState.Ready(5, stale = true), s)
    }

    @Test
    fun metricsRangeQueryOnlyRequestsNeededTypes() {
        // HRV + steps are read from the observation table; weight is added as trend fallback.
        assertEquals(
            listOf(MetricType.HRV, MetricType.STEPS, MetricType.WEIGHT),
            metricsRangeQueryTypes(listOf(MetricType.HRV, MetricType.STEPS)),
        )
        // Body composition is served by withings.trend(), so it is not queried directly (only WEIGHT fallback).
        assertEquals(
            listOf(MetricType.WEIGHT),
            metricsRangeQueryTypes(listOf(MetricType.WEIGHT, MetricType.BODY_FAT, MetricType.MUSCLE_MASS)),
        )
        assertTrue(MetricType.SLEEP_DURATION !in metricsRangeQueryTypes(listOf(MetricType.HRV)))
    }
}
