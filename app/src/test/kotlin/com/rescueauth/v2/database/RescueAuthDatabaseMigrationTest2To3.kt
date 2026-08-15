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
 * Room schema migration test: v2 → v3 (Phase 3C).
 *
 * Verifies that a v2 database (created from the exported v2 schema) upgrades
 * to v3:
 * - every pre-existing Authenticator / Recovery / ImportRecord row (with its
 *   stableId lineage) survives untouched (non-destructive migration);
 * - the new `developer_entry` table is created and usable;
 * - the unique index on `developer_entry.stableId` exists.
 *
 * `MigrationTestHelper` validates the migrated schema against the exported
 * v3 schema JSON (`app/schemas/.../3.json`).
 */
@RunWith(AndroidJUnit4::class)
class RescueAuthDatabaseMigrationTest2To3 {

    @get:Rule
    val helper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        RescueAuthDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migrate2To3KeepsExistingVaultDataAndAddsDeveloperTable() {
        // Create a v2 database from the exported v2 schema.
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO auth_account (id, serviceName, accountName, favorite, notes, sortOrder, createdAt, updatedAt, legacySourceId, stableId) " +
                    "VALUES ('acc-1', 'GitHub', 'alice@example.com', 0, NULL, 0, 't', 't', 'legacy-1', 'acc-1')"
            )
            execSQL(
                "INSERT INTO totp_credential (id, accountId, secretBase32, algorithm, digits, periodSeconds, createdAt, legacySourceId, stableId) " +
                    "VALUES ('totp-1', 'acc-1', 'JBSWY3DPEHPK3PXP', 'SHA1', 6, 30, 't', NULL, 'totp-1')"
            )
            execSQL(
                "INSERT INTO recovery_code_set (id, accountId, title, createdAt, legacySourceId, stableId) " +
                    "VALUES ('set-1', 'acc-1', 'Backup codes', 't', NULL, 'set-1')"
            )
            execSQL(
                "INSERT INTO recovery_code (id, setId, value, status, usedAt, sortOrder, stableId) " +
                    "VALUES ('c1', 'set-1', 'AAAA-BBBB', 'USED', 't2', 0, 'c1')"
            )
            execSQL(
                "INSERT INTO import_record (id, sourceType, sourceFingerprint, importedAt, itemCount, warningCount, stableId) " +
                    "VALUES ('imp-1', 'LEGACY_RAKVAULT', '1:1', 't', 1, 0, 'imp-1')"
            )
            close()
        }

        // Run the migration and open as v3.
        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, RescueAuthDatabase.MIGRATION_2_3)
        runBlocking {
            // Existing data preserved.
            val account = db.query("SELECT * FROM auth_account WHERE id = 'acc-1'").use { c ->
                c.moveToFirst()
                c.getString(c.getColumnIndex("stableId"))
            }
            assertEquals("acc-1", account)

            val totp = db.query("SELECT stableId FROM totp_credential WHERE id = 'totp-1'").use { c ->
                c.moveToFirst()
                c.getString(0)
            }
            assertEquals("totp-1", totp)

            val set = db.query("SELECT stableId FROM recovery_code_set WHERE id = 'set-1'").use { c ->
                c.moveToFirst()
                c.getString(0)
            }
            assertEquals("set-1", set)

            val code = db.query("SELECT status FROM recovery_code WHERE id = 'c1'").use { c ->
                c.moveToFirst()
                c.getString(0)
            }
            assertEquals("USED", code)

            val imp = db.query("SELECT stableId FROM import_record WHERE id = 'imp-1'").use { c ->
                c.moveToFirst()
                c.getString(0)
            }
            assertEquals("imp-1", imp)

            // The developer table is created and usable.
            db.execSQL(
                "INSERT INTO developer_entry (id, stableId, entryType, title, notes, payloadJson, createdAt, updatedAt, sortOrder) " +
                    "VALUES ('dev-1', 'dev-1', 'GENERIC_SECRET', 'token', NULL, '{}', 't', 't', 0)"
            )
            val dev = db.query("SELECT stableId FROM developer_entry WHERE id = 'dev-1'").use { c ->
                c.moveToFirst()
                c.getString(0)
            }
            assertEquals("dev-1", dev)

            // Unique index exists on developer_entry.stableId.
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='index_developer_entry_stableId'").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-test-v2-v3"
    }
}
