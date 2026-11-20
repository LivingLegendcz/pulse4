package app.pulse4.android

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pulse4.android.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @Test
    fun migration2To3_createsFamilyLinkAndPendingRequestTablesAndPreservesExistingData() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "migration_test_2_3.db"
        ctx.deleteDatabase(dbName)

        // Create a v2 database with existing contact, share_grant, grp, and setting tables.
        val v2Config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `contact` (`identity` TEXT NOT NULL, `displayName` TEXT NOT NULL, `publicKeyB64` TEXT NOT NULL, `fingerprint` TEXT NOT NULL, `verifiedAtMs` INTEGER NOT NULL, PRIMARY KEY(`identity`))")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `share_grant` (`id` TEXT NOT NULL, `revoked` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `grp` (`id` TEXT NOT NULL, `version` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `setting` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")

                    db.execSQL("INSERT INTO `contact` VALUES ('id-child-1', 'Dítě', 'pk', 'fp', 1000)")
                    db.execSQL("INSERT INTO `grp` VALUES ('grp-1', 1, '{\"id\":\"grp-1\",\"name\":\"Rodina\",\"ownerId\":\"me\",\"adminId\":\"me\",\"members\":[\"me\"],\"version\":1}')")
                    db.execSQL("INSERT INTO `setting` VALUES ('displayName', 'Rodič')")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(v2Config)
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_2_3.migrate(db)

        // Verify new tables exist and accept inserts
        db.execSQL("INSERT INTO `family_link` (`id`, `parentId`, `childId`, `status`, `version`, `json`) VALUES ('fl-1', 'me', 'id-child-1', 'VERIFIED', 1, '{}')")
        db.execSQL("INSERT INTO `pending_request` (`id`, `kind`, `fromId`, `toId`, `status`, `createdAtMs`, `json`) VALUES ('pr-1', 'FAMILY_LINK_REQUEST', 'me', 'id-child-1', 'PENDING', 1000, '{}')")

        db.query("SELECT COUNT(*) FROM `family_link`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM `pending_request`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        // Verify existing v2 data preserved
        db.query("SELECT `displayName` FROM `contact` WHERE `identity` = 'id-child-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Dítě", c.getString(0))
        }
        db.close()
        ctx.deleteDatabase(dbName)
    }

    @Test
    fun migration3To4_createsGuardianConsentTableAndPreservesExistingData() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "migration_test_3_4.db"
        ctx.deleteDatabase(dbName)

        val v3Config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS `share_grant` (`id` TEXT NOT NULL, `revoked` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `family_link` (`id` TEXT NOT NULL, `parentId` TEXT NOT NULL, `childId` TEXT NOT NULL, `status` TEXT NOT NULL, `version` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))")
                    db.execSQL("CREATE TABLE IF NOT EXISTS `setting` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
                    db.execSQL("INSERT INTO `share_grant` VALUES ('g-1', 0, '{}')")
                    db.execSQL("INSERT INTO `family_link` VALUES ('fl-1', 'parent', 'child', 'VERIFIED', 2, '{}')")
                    db.execSQL("INSERT INTO `setting` VALUES ('ownAgeProfile', 'MINOR')")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(v3Config)
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_3_4.migrate(db)
        // Running it twice must be harmless (CREATE ... IF NOT EXISTS).
        AppDatabase.MIGRATION_3_4.migrate(db)

        db.execSQL(
            "INSERT INTO `guardian_consent` (`requestId`, `role`, `peerId`, `subject`, `subjectId`, `linkId`, `permissionVersion`, `status`, `createdAtMs`, `expiresAtMs`, `decidedAtMs`, `requestJson`, `proposedGrantJson`, `decisionJson`) " +
                "VALUES ('r-1', 'CHILD_OUT', 'parent', 'SHARE_GRANT', 'g-1', NULL, 1, 'PENDING', 1000, 2000, NULL, '{}', NULL, NULL)"
        )
        db.query("SELECT COUNT(*) FROM `guardian_consent` WHERE `status` = 'PENDING'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT `name` FROM sqlite_master WHERE type = 'index' AND tbl_name = 'guardian_consent' AND name LIKE 'index_guardian_consent_%'").use { c ->
            assertEquals(2, c.count)
        }
        // Existing v3 data (grant, family link, legacy age setting) is untouched.
        db.query("SELECT `status` FROM `family_link` WHERE `id` = 'fl-1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("VERIFIED", c.getString(0))
        }
        db.query("SELECT `value` FROM `setting` WHERE `key` = 'ownAgeProfile'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("MINOR", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM `share_grant`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.close()
        ctx.deleteDatabase(dbName)
    }
}
