package app.pulse4.android.reticulum

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Čekání na odpověď `/get` s **idle** timeoutem místo pevného stropu (R4-8).
 *
 * Pomalé, ale postupující stahování (multi-segment Resource až 2 MB) se nesmí utnout jen proto,
 * že trvá déle než pevný limit: dokud [lastActivityMs] hlásí nový progres, čeká se dál.
 * - dokud žádný Resource neběží: max. [responseTimeoutMs] od posledního progresu;
 * - během Resource přenosu: max. [idleTimeoutMs] bez progresu;
 * - [absoluteCapMs] je jen pojistka proti zaseknutému zdroji progresu.
 */
internal object IdleTimeoutWaiter {
    const val GET_IDLE_TIMEOUT_MS = 60_000L
    const val GET_ABSOLUTE_CAP_MS = 15 * 60_000L

    fun <T> await(
        future: CompletableFuture<T>,
        lastActivityMs: () -> Long?,
        responseTimeoutMs: Long,
        idleTimeoutMs: Long = GET_IDLE_TIMEOUT_MS,
        absoluteCapMs: Long = GET_ABSOLUTE_CAP_MS,
        pollMs: Long = 500L,
        nowMs: () -> Long = System::currentTimeMillis,
    ): T? {
        val startMs = nowMs()
        var lastProgressMs = startMs
        while (nowMs() - startMs < absoluteCapMs) {
            val result = runCatching { future.get(pollMs, TimeUnit.MILLISECONDS) }.getOrNull()
            if (result != null) return result
            if (future.isDone || future.isCancelled) return null
            val activity = lastActivityMs()
            if (activity != null && activity > lastProgressMs) lastProgressMs = activity
            val limit = if (activity != null) maxOf(responseTimeoutMs, idleTimeoutMs) else responseTimeoutMs
            if (nowMs() - lastProgressMs >= limit) return null
        }
        return null
    }
}
