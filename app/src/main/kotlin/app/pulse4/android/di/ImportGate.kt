package app.pulse4.android.di

import java.util.concurrent.locks.ReentrantLock

/**
 * Non-blocking single-flight guard for long network imports.
 *
 * A second caller that arrives while an import is running does NOT queue behind it (that piled up threads
 * and held IO workers for many seconds); [tryRun] returns [onBusy] immediately. The lock is reentrant so
 * an import entry point may call another one on the same thread (e.g. `runImport` -> `runWebImport`).
 * [runBlocking] is for operations that must not be dropped (they wait for the running import).
 */
class ImportGate {
    private val lock = ReentrantLock()

    val isBusy: Boolean get() = lock.isLocked

    inline fun <T> tryRun(onBusy: () -> T, block: () -> T): T {
        if (!acquire(wait = false)) return onBusy()
        try {
            return block()
        } finally {
            release()
        }
    }

    inline fun <T> runBlocking(block: () -> T): T {
        acquire(wait = true)
        try {
            return block()
        } finally {
            release()
        }
    }

    @PublishedApi internal fun acquire(wait: Boolean): Boolean {
        if (wait) {
            lock.lock()
            return true
        }
        return lock.tryLock()
    }

    @PublishedApi internal fun release() = lock.unlock()
}
