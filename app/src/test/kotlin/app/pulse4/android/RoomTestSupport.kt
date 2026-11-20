package app.pulse4.android

import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import app.pulse4.android.data.AppDatabase
import java.io.File
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/** Room nad JDBC SQLite pro JVM testy (bez Androidu). */
internal fun <T> withJdbcRoomDb(block: (AppDatabase) -> T): T {
    val dbFile = File.createTempFile("pulse4-jdbc-test-", ".db").apply { delete() }
    val ctx = object : ContextWrapper(null) {
        override fun getApplicationContext() = this
        override fun getDatabasePath(name: String): File = File(name)
    }
    val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbFile.absolutePath)
        .setDriver(TestJdbcSqliteDriver())
        .allowMainThreadQueries()
        .build()
    try {
        return block(db)
    } finally {
        runCatching { db.close() }
        dbFile.delete()
        File("${dbFile.absolutePath}-wal").delete()
        File("${dbFile.absolutePath}-shm").delete()
    }
}

internal class TestJdbcSqliteDriver : SQLiteDriver {
    override fun open(fileName: String): SQLiteConnection {
        val url = if (fileName == ":memory:") "jdbc:sqlite::memory:" else "jdbc:sqlite:$fileName"
        return TestJdbcSqliteConnection(DriverManager.getConnection(url))
    }
}

internal class TestJdbcSqliteConnection(private val conn: java.sql.Connection) : SQLiteConnection {
    private var inTx = false
    override fun inTransaction(): Boolean = inTx
    override fun prepare(sql: String): SQLiteStatement {
        val upper = sql.trimStart().uppercase()
        return TestJdbcSqliteStatement(conn.prepareStatement(sql), onStep = {
            when {
                upper.startsWith("BEGIN") -> inTx = true
                upper.startsWith("COMMIT") || upper.startsWith("END") || upper.startsWith("ROLLBACK") -> inTx = false
            }
        })
    }
    override fun close() = conn.close()
}

internal class TestJdbcSqliteStatement(
    private val ps: PreparedStatement,
    private val onStep: () -> Unit,
) : SQLiteStatement {
    private var executed = false
    private var rs: ResultSet? = null
    private var cachedColumnNames: List<String>? = null
    private var firstStepPending = false
    private var hasPendingFirstRow = false
    private var eof = false

    private fun executeAndCacheColumns() {
        if (executed) return
        executed = true
        onStep()
        if (ps.execute()) {
            val r = ps.resultSet
            rs = r
            val md = r.metaData
            cachedColumnNames = (1..md.columnCount).map { md.getColumnName(it) }
            hasPendingFirstRow = r.next()
            eof = !hasPendingFirstRow
            firstStepPending = true
        } else {
            cachedColumnNames = emptyList()
            eof = true
        }
    }

    override fun bindBlob(index: Int, value: ByteArray) = ps.setBytes(index, value)
    override fun bindDouble(index: Int, value: Double) = ps.setDouble(index, value)
    override fun bindLong(index: Int, value: Long) = ps.setLong(index, value)
    override fun bindText(index: Int, value: String) = ps.setString(index, value)
    override fun bindNull(index: Int) = ps.setNull(index, Types.NULL)
    override fun getBlob(index: Int): ByteArray = rs?.getBytes(index + 1) ?: ByteArray(0)
    override fun getDouble(index: Int): Double = rs?.getDouble(index + 1) ?: 0.0
    override fun getLong(index: Int): Long = rs?.getLong(index + 1) ?: 0L
    override fun getText(index: Int): String = rs?.getString(index + 1) ?: ""
    override fun isNull(index: Int): Boolean = rs?.getObject(index + 1) == null
    override fun getColumnCount(): Int {
        if (cachedColumnNames == null) executeAndCacheColumns()
        return cachedColumnNames?.size ?: 0
    }
    override fun getColumnName(index: Int): String {
        if (cachedColumnNames == null) executeAndCacheColumns()
        return cachedColumnNames?.getOrNull(index) ?: ""
    }
    override fun getColumnType(index: Int): Int {
        val v = rs?.getObject(index + 1) ?: return 5
        return when (v) {
            is Int, is Long -> 1
            is Float, is Double -> 2
            is ByteArray -> 4
            else -> 3
        }
    }
    override fun step(): Boolean {
        if (!executed) {
            executeAndCacheColumns()
            firstStepPending = false
            return hasPendingFirstRow
        }
        if (firstStepPending) {
            firstStepPending = false
            return hasPendingFirstRow
        }
        if (eof) return false
        val next = rs?.next() == true
        if (!next) eof = true
        return next
    }
    override fun reset() {
        rs?.close(); rs = null; cachedColumnNames = null; executed = false
        firstStepPending = false; hasPendingFirstRow = false; eof = false
    }
    override fun clearBindings() = ps.clearParameters()
    override fun close() { rs?.close(); rs = null; ps.close() }
}
