package androidx.room
import kotlin.reflect.KClass
annotation class Database(val entities: Array<KClass<*>>, val version: Int, val exportSchema: Boolean = true)
annotation class Entity(val tableName: String = "", val indices: Array<Index> = [], val primaryKeys: Array<String> = [])
annotation class Index(vararg val value: String)
annotation class PrimaryKey
annotation class ColumnInfo(val typeAffinity: Int = 0) { companion object { const val BLOB = 4 } }
annotation class Dao
annotation class Insert(val onConflict: Int = 3)
annotation class Query(val value: String)
object OnConflictStrategy { const val REPLACE = 1; const val ABORT = 3; const val IGNORE = 5 }
abstract class RoomDatabase {
    fun <V> runInTransaction(c: java.util.concurrent.Callable<V>): V = c.call()
    fun runInTransaction(r: Runnable) { r.run() }
    class Builder<T : RoomDatabase> { fun openHelperFactory(f: Any): Builder<T> = this; fun build(): T = TODO() }
}
object Room { fun <T : RoomDatabase> databaseBuilder(c: android.content.Context, k: Class<T>, name: String): RoomDatabase.Builder<T> = TODO() }
