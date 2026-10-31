package androidx.work
import android.content.Context
import java.util.concurrent.TimeUnit
class Data { fun getBoolean(k: String, d: Boolean): Boolean = d }
fun workDataOf(vararg p: Pair<String, Any?>): Data = Data()
class WorkerParameters { val inputData: Data = Data() }
abstract class ListenableWorker(val applicationContext: Context, params: WorkerParameters) {
    sealed class Result { companion object { fun success(): Result = TODO() } }
}
abstract class CoroutineWorker(ctx: Context, val params: WorkerParameters) : ListenableWorker(ctx, params) {
    abstract suspend fun doWork(): Result
    val inputData: Data get() = params.inputData
}
enum class NetworkType { CONNECTED }
class Constraints { class Builder { fun setRequiredNetworkType(t: NetworkType) = this; fun build() = Constraints() } }
enum class BackoffPolicy { EXPONENTIAL }
enum class ExistingPeriodicWorkPolicy { KEEP }
enum class ExistingWorkPolicy { KEEP }
interface WorkRequest
class PeriodicWorkRequest : WorkRequest
class OneTimeWorkRequest : WorkRequest
class PeriodicBuilder { fun setConstraints(c: Constraints) = this; fun setBackoffCriteria(p: BackoffPolicy, d: Long, u: TimeUnit) = this; fun build() = PeriodicWorkRequest() }
class OneTimeBuilder { fun setConstraints(c: Constraints) = this; fun setInputData(d: Data) = this; fun build() = OneTimeWorkRequest() }
inline fun <reified W : ListenableWorker> PeriodicWorkRequestBuilder(n: Long, u: TimeUnit) = PeriodicBuilder()
inline fun <reified W : ListenableWorker> OneTimeWorkRequestBuilder() = OneTimeBuilder()
class WorkManager {
    fun enqueueUniquePeriodicWork(n: String, p: ExistingPeriodicWorkPolicy, r: PeriodicWorkRequest) {}
    fun enqueueUniqueWork(n: String, p: ExistingWorkPolicy, r: OneTimeWorkRequest) {}
    companion object { fun getInstance(c: Context) = WorkManager() }
}
