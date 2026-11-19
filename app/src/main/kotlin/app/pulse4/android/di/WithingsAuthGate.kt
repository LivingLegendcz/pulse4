package app.pulse4.android.di

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes all operations that touch the Withings auth state (shared cookie jar in
 * [WithingsDirectAuth] and the persisted web session) and gives manual (user-initiated)
 * logins priority over background renewals.
 *
 * - Background renew / silent SSO use [background] (blocking) and should poll [manualPending]
 *   between HTTP calls, aborting early when a manual login is waiting.
 * - Manual logins use [manual] which waits at most `timeoutMs` and invokes `onWaiting` once if
 *   the lock is currently held, so the UI can say "Čekám na dokončení synchronizace…".
 * - Every successful session replacement bumps [generation]. Long-running background work
 *   (data import) captures the generation at start and uses [saveIfCurrent] so it never
 *   overwrites a session created by a manual login in the meantime.
 *
 * Lock ordering: the data-import lock in [WithingsService] may be held while acquiring this gate,
 * never the other way round (manual paths never take the import lock).
 */
class WithingsAuthGate {
    private val lock = ReentrantLock()
    private val manualWaiters = AtomicInteger(0)
    private val gen = AtomicLong(0L)

    /** True while a manual login is waiting for the gate; background work should abort ASAP. */
    fun manualPending(): Boolean = manualWaiters.get() > 0

    fun generation(): Long = gen.get()

    /** Marks that the persisted session was replaced (manual login, disconnect, profile change). */
    fun bumpGeneration(): Long = gen.incrementAndGet()

    fun <T> background(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Runs [block] under the gate on behalf of the user. Returns [GateResult.TimedOut] when the gate
     * could not be acquired within [timeoutMs] (a background operation ignored the abort request).
     */
    fun <T> manual(timeoutMs: Long, onWaiting: () -> Unit = {}, block: () -> T): GateResult<T> {
        manualWaiters.incrementAndGet()
        val acquired = try {
            if (lock.tryLock()) {
                true
            } else {
                runCatching { onWaiting() }
                try {
                    lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            }
        } finally {
            manualWaiters.decrementAndGet()
        }
        if (!acquired) return GateResult.TimedOut
        try {
            return GateResult.Done(block())
        } finally {
            lock.unlock()
        }
    }

    /**
     * Runs [save] under the gate only if no session replacement happened since [expectedGeneration].
     * Returns false when the write was dropped as stale.
     */
    fun saveIfCurrent(expectedGeneration: Long, save: () -> Unit): Boolean = background {
        if (gen.get() != expectedGeneration) {
            false
        } else {
            save()
            true
        }
    }

    sealed interface GateResult<out T> {
        data class Done<T>(val value: T) : GateResult<T>
        data object TimedOut : GateResult<Nothing>
    }

    companion object {
        /** One OkHttp call timeout (30 s) plus slack: enough for background work to notice the abort flag. */
        const val MANUAL_WAIT_TIMEOUT_MS = 45_000L
    }
}
