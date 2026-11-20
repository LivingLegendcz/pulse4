package app.pulse4.ui

/** Data providers whose automatic import interval is configurable in Settings. */
enum class SyncProvider(val key: String, val defaultMinutes: Int) {
    GARMIN("garmin", 15),
    WITHINGS("withings", 60),
}

/**
 * Pure rules for the per-provider automatic import interval. Manual "Sync now" buttons never consult this.
 *
 * Stored value semantics (minutes): absent = provider default, [OFF] (0) = automatic import disabled,
 * anything else is clamped to [MIN_MINUTES]..[MAX_MINUTES]. WorkManager cannot run periodic work more often than
 * every 15 minutes, so 15 is also the practical floor on every code path.
 */
object SyncSchedule {
    const val OFF = 0
    const val MIN_MINUTES = 15
    const val MAX_MINUTES = 24 * 60

    /** Choices offered in Settings (order = display order). */
    val OPTIONS: List<Int> = listOf(15, 30, 60, 180, 360, OFF)

    /** Maps a raw stored value (possibly missing / hand-edited / from a backup) to a valid interval. */
    fun normalize(provider: SyncProvider, stored: Int?): Int = when {
        stored == null -> provider.defaultMinutes
        stored == OFF -> OFF
        stored < 0 -> provider.defaultMinutes
        else -> stored.coerceIn(MIN_MINUTES, MAX_MINUTES)
    }

    fun intervalMs(minutes: Int): Long = minutes * 60_000L

    fun label(minutes: Int): String = when {
        minutes <= OFF -> "Vypnuto"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}
