package app.pulse4.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import app.pulse4.android.security.KeyVault
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        ObservationRow::class, SourceRecordRow::class, ActivityRow::class, TrackRow::class, AreaStateRow::class,
        ContactRow::class, GrantRow::class, GroupRow::class, OutboxRow::class, ReceiptRow::class, ReceivedRow::class,
        SentRow::class, PlanRevisionRow::class, ProposalRow::class, ExportRow::class, TemplateRow::class, SettingRow::class, ExcludedRow::class,
        FamilyLinkRow::class, PendingRequestRow::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun observations(): ObservationDao
    abstract fun sources(): SourceDao
    abstract fun activities(): ActivityDao
    abstract fun areaStates(): AreaStateDao
    abstract fun contacts(): ContactDao
    abstract fun grants(): GrantDao
    abstract fun outbox(): OutboxDao
    abstract fun inbox(): InboxDao
    abstract fun plans(): PlanDao
    abstract fun settings(): SettingDao
    abstract fun exclusions(): ExclusionDao
    abstract fun familyLinks(): FamilyLinkDao
    abstract fun pendingRequests(): PendingRequestDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activity ADD COLUMN title TEXT")
                db.execSQL("ALTER TABLE activity ADD COLUMN distanceM REAL")
                db.execSQL("ALTER TABLE activity ADD COLUMN movingTimeS REAL")
                db.execSQL("ALTER TABLE activity ADD COLUMN ascentM REAL")
                db.execSQL("ALTER TABLE activity ADD COLUMN avgHeartRate INTEGER")
                db.execSQL("ALTER TABLE activity ADD COLUMN maxHeartRate INTEGER")
                db.execSQL("ALTER TABLE activity ADD COLUMN caloriesKcal REAL")
                db.execSQL("ALTER TABLE activity ADD COLUMN aerobicTe REAL")
                db.execSQL("ALTER TABLE activity ADD COLUMN anaerobicTe REAL")
                db.execSQL("ALTER TABLE activity ADD COLUMN trainingLoad REAL")
                db.execSQL("ALTER TABLE activity ADD COLUMN avgPowerW REAL")
                db.execSQL("ALTER TABLE activity ADD COLUMN avgCadence REAL")
                db.execSQL("ALTER TABLE activity ADD COLUMN zoneId TEXT")
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `family_link` (`id` TEXT NOT NULL, `parentId` TEXT NOT NULL, `childId` TEXT NOT NULL, `status` TEXT NOT NULL, `version` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_request` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `fromId` TEXT NOT NULL, `toId` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        fun open(context: Context, vault: KeyVault): AppDatabase {
            System.loadLibrary("sqlcipher")
            // Heslo předáváme jako bajty; SupportOpenHelperFactory je po otevření vynuluje podle `clearPassphrase`.
            val factory = SupportOpenHelperFactory(vault.databasePassphrase())
            return Room.databaseBuilder(context, AppDatabase::class.java, "pulse4.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .allowMainThreadQueries()
                // Žádné destruktivní migrace: archiv uživatele se nikdy nemaže kvůli změně schématu.
                .build()
        }
    }
}
