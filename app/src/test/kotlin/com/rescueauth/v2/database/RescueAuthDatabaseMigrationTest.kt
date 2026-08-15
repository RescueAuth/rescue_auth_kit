package com.rescueauth.v2.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room schema migration test: v1 → v2 (Phase 3A).
 *
 * Verifies that a v1 database (created from the exported v1 schema) upgrades
 * to v2 preserving all user data:
 * - every row's `stableId` is backfilled from its `id` (the Room primary key
 *   is the stable logical ID for pre-Phase-3A rows);
 * - the unique indexes on `stableId` are created;
 * - the obsolete `backup_record` table is dropped.
 *
 * `MigrationTestHelper` is the Room-provided tool: it validates the migrated
 * schema against the exported v2 schema JSON (`app/schemas/.../2.json`) and
 * runs on the host JVM via Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class RescueAuthDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        RescueAuthDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migrate1To2PreservesDataAndDropsBackupTable() {
        // Create a v1 database from the exported v1 schema.
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO auth_account (id, serviceName, accountName, favorite, notes, sortOrder, createdAt, updatedAt, legacySourceId) " +
                    "VALUES ('acc-1', 'GitHub', 'alice@example.com', 0, NULL, 0, 't', 't', 'legacy-1')"
            )
            execSQL(
                "INSERT INTO totp_credential (id, accountId, secretBase32, algorithm, digits, periodSeconds, createdAt, legacySourceId) " +
                    "VALUES ('totp-1', 'acc-1', 'JBSWY3DPEHPK3PXP', 'SHA1', 6, 30, 't', NULL)"
            )
            execSQL(
                "INSERT INTO recovery_code_set (id, accountId, title, createdAt, legacySourceId) " +
                    "VALUES ('set-1', 'acc-1', 'Backup codes', 't', NULL)"
            )
            execSQL(
                "INSERT INTO recovery_code (id, setId, value, status, usedAt, sortOrder) " +
                    "VALUES ('c1', 'set-1', 'AAAA-BBBB', 'UNUSED', NULL, 0)"
            )
            execSQL(
                "INSERT INTO import_record (id, sourceType, sourceFingerprint, importedAt, itemCount, warningCount) " +
                    "VALUES ('imp-1', 'LEGACY_RAKVAULT', '1:1', 't', 1, 0)"
            )
            execSQL(
                "INSERT INTO backup_record (id, createdAt, reason, uri, sizeBytes, sha256, status, errorCode) " +
                    "VALUES ('bk-1', 't', 'CHANGE', 'file:///x', 100, 'abc', 'SUCCESS', NULL)"
            )
            close()
        }

        // Run the migration and open as v2.
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, RescueAuthDatabase.MIGRATION_1_2)
        runBlocking {
            // Data preserved.
            val account = db.query("SELECT * FROM auth_account WHERE id = 'acc-1'").use { c ->
                c.moveToFirst()
                c.getColumnIndex("stableId").let { c.getString(it) }
            }
            assertEquals("acc-1", account)

            val totpStable = db.query("SELECT stableId FROM totp_credential WHERE id = 'totp-1'").use { c ->
                c.moveToFirst()
                c.getString(0)
            }
            assertEquals("totp-1", totpStable)

            val setStable = db.query("SELECT stableId FROM recovery_code_set WHERE id = 'set-1'").use { c ->
                c.moveToFirst()
                c.getString(0)
            }
            assertEquals("set-1", setStable)

            val codeStable = db.query("SELECT stableId FROM recovery_code WHERE id = 'c1'").use { c ->
                c.moveToFirst()
                c.getString(0)
            }
            assertEquals("c1", codeStable)

            val impStable = db.query("SELECT stableId FROM import_record WHERE id = 'imp-1'").use { c ->
                c.moveToFirst()
                c.getString(0)
            }
            assertEquals("imp-1", impStable)

            // backup_record is gone.
            val backupCount = try {
                db.query("SELECT COUNT(*) FROM backup_record").use { c ->
                    c.moveToFirst()
                    c.getInt(0)
                }
            } catch (e: Exception) {
                -1
            }
            assertEquals(-1, backupCount)

            // Unique indexes exist on stableId.
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='index_auth_account_stableId'").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-test-v1-v2"
    }
}
