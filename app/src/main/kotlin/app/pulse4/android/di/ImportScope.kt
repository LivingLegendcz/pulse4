package app.pulse4.android.di

/** Which providers an import run covers. Automatic runs use per-provider scopes; manual "sync all" uses [ALL]. */
enum class ImportScope(val garmin: Boolean, val withings: Boolean) {
    ALL(garmin = true, withings = true),
    GARMIN(garmin = true, withings = false),
    WITHINGS(garmin = false, withings = true),
}
