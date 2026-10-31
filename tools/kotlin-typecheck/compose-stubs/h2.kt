package app.pulse4.android.di
sealed interface HcResult {
    data class Done(val upserts: Int, val deletions: Int, val mode: app.pulse4.withings.HealthConnectMapper.Plan) : HcResult
    data object Unavailable : HcResult
    data object NoPermission : HcResult
}
class HealthConnectSource(context: android.content.Context, sl: ServiceLocator) {
    val permissions: Set<String> = emptySet(); val optionalPermissions: Set<String> = emptySet()
    fun available() = false
    suspend fun sync(nowMs: Long, background: Boolean): HcResult = HcResult.Unavailable
}
