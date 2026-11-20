package app.pulse4.android.ui

import app.pulse4.domain.PLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small in-memory LRU of the last successful result per key. Screens show the cached value instantly on tab
 * entry and refresh it in the background (stale-while-revalidate), so re-entering a tab never starts from an
 * empty "loading" state. Process memory only; nothing is persisted.
 */
class LastResultCache<K : Any, V : Any>(private val maxEntries: Int = 8) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    @Synchronized fun get(key: K): V? = map[key]
    @Synchronized fun put(key: K, value: V) { map[key] = value }
    @Synchronized fun clear() = map.clear()
    @Synchronized fun size(): Int = map.size
}

/** State of a background load: nothing yet, a value (possibly stale, being refreshed) or a visible failure. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ready<T>(val value: T, val stale: Boolean) : LoadState<T>
    data class Failed(val message: String) : LoadState<Nothing>
}

/** The value of this state if there is one (also while refreshing), otherwise null. */
fun <T> LoadState<T>.valueOrNull(): T? = (this as? LoadState.Ready<T>)?.value

/**
 * Runs [block] on IO and returns the new state. On success the result is stored in [cache]. On failure the
 * previous cached value (if any) stays visible as a stale value; only with nothing to show does it become
 * [LoadState.Failed] so the UI can offer a retry instead of a spinner that never ends.
 */
suspend fun <K : Any, V : Any> loadWithCache(
    key: K,
    cache: LastResultCache<K, V>,
    tag: String = "Pulse4",
    block: () -> V,
): LoadState<V> = withContext(Dispatchers.IO) {
    try {
        val v = block()
        cache.put(key, v)
        LoadState.Ready(v, stale = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        PLog.e(tag, e) { "load failed: ${e.javaClass.simpleName}" }
        cache.get(key)?.let { LoadState.Ready(it, stale = true) }
            ?: LoadState.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
    }
}

/** Initial UI state for [key]: the cached value as stale, or loading. */
fun <K : Any, V : Any> initialLoadState(key: K, cache: LastResultCache<K, V>): LoadState<V> =
    cache.get(key)?.let { LoadState.Ready(it, stale = true) } ?: LoadState.Loading
