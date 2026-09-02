package com.rescueauth.v2.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * v2 encrypted database (schema version 4).
 *
 * Encryption: SQLCipher (Zetetic `sqlcipher-android`), opened with a
 * `SupportOpenHelperFactory` that receives the unwrapped [VaultKey] as the
 * SQLCipher passphrase. The database file is therefore fully encrypted; the
 * key is provided only while the session is UNLOCKED.
 *
 * 16 KB page-size compatibility (Android 15+): the SQLCipher native library
 * does not embed a hard-coded page size dependency; the .so is zipaligned to
 * 16 KB by AGP 8.7+ (`useLegacyPackaging=false`). The SQLCipher page size
 * itself stays at its default 4096, which is independent of the OS page size.
 *
 * Locking policy: on `lock()` the app closes every connection via
 * [closeDatabase] and zeroes the in-memory key copy. A stale open handle is
 * never used after locking — all access goes through the repository, which
 * re-checks the session state.
 */
@Database(
    entities = [
        AuthAccountEntity::class,
        TotpCredentialEntity::class,
        RecoveryCodeSetEntity::class,
        RecoveryCodeEntity::class,
        ImportRecordEntity::class,
        DeveloperEntryEntity::class,
        ProviderMetaEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class RescueAuthDatabase : RoomDatabase() {

    abstract fun authAccountDao(): AuthAccountDao
    abstract fun totpCredentialDao(): TotpCredentialDao
    abstract fun recoveryCodeSetDao(): RecoveryCodeSetDao
    abstract fun recoveryCodeDao(): RecoveryCodeDao
    abstract fun importRecordDao(): ImportRecordDao
    abstract fun developerEntryDao(): DeveloperEntryDao
    abstract fun providerMetaDao(): ProviderMetaDao

    companion object {
        private const val TAG = "RescueAuthDatabase"

        const val DB_NAME = "rescueauth_v2.db"
        const val SCHEMA_VERSION = 4

        /**
         * Builds the encrypted database. [vaultKey] must be the unwrapped
         * 32-byte VaultKey. The helper factory is created per-open so a new
         * key (or re-unwrap) takes effect.
         *
         * ## Key mode (schema polish 2026-09 — open-latency fix)
         *
         * The VaultKey is a cryptographically random 256-bit key wrapped by
         * the Android Keystore. Because the input is already full-entropy,
         * running SQLCipher's passphrase path (PBKDF2-HMAC-SHA512, 256,000
         * iterations — the SQLCipher 4 default) over it on EVERY open adds
         * ~0.5-2s of wall time per unlock with ZERO entropy gain. Zetetic's
         * documented pattern for hardware-backed random keys is the raw-key
         * syntax `x'<64 hex chars>'`, which keys the database directly and
         * skips the KDF entirely (open drops to milliseconds).
         *
         * A database created before this change was keyed via the passphrase
         * path, so [build] probes the file first: if the raw key does not
         * open it, the database is opened once with the legacy passphrase
         * bytes and converted in place with `PRAGMA rekey = "x'...'"` (a
         * one-time full-file re-encryption; the vault is small, so this is
         * sub-second). After the migration every open is raw-key.
         *
         * The probe is stateless by design: no persisted "migrated" flag can
         * drift out of sync with the actual file bytes, and the cost of a
         * successful raw-key probe is a few milliseconds.
         */
        fun build(context: Context, vaultKey: ByteArray): RescueAuthDatabase {
            // SQLCipher 4.17.0 does not load its native core automatically
            // (see SQLCipherNativeLoader). Every database entry point must
            // guarantee libsqlcipher.so is present before opening a
            // connection; the loader is idempotent per process.
            SQLCipherNativeLoader.ensureLoaded()
            val keyHex = vaultKey.joinToString("") { "%02x".format(it) }
            val rawKeyPragma = "x'$keyHex'"
            val rawKeyBytes = rawKeyPragma.toByteArray(Charsets.UTF_8)
            val legacyPassphrase = byteArrayOf(0x01) + keyHex.toByteArray(Charsets.UTF_8)

            migrateLegacyKeyFileIfNeeded(context, legacyPassphrase, rawKeyBytes)

            val factory = object : SupportSQLiteOpenHelper.Factory {
                override fun create(configuration: SupportSQLiteOpenHelper.Configuration):
                    SupportSQLiteOpenHelper {
                    val sqlcipherFactory = SupportOpenHelperFactory(rawKeyBytes)
                    return sqlcipherFactory.create(configuration)
                }
            }
            return Room.databaseBuilder(context, RescueAuthDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }

        /**
         * One-time conversion of a database keyed via the legacy passphrase
         * bytes (`0x01 + hex` — PBKDF2 path) to the raw-key syntax. No-op for
         * a fresh install (no file) or a database already keyed with the raw
         * key (probe succeeds). Runs OUTSIDE Room so a wrong probe can never
         * surface as a corrupt-vault error in the session layer.
         *
         * WAL handling: Room runs in WAL journal mode, so a legacy file may
         * carry un-checkpointed pages in `rescueauth_v2.db-wal` encrypted
         * with the OLD key. Rekeying the main file while those frames exist
         * would strand them (the -wal file stays keyed with the old key —
         * explicitly unsupported per Zetetic's rekey guidance). The migration
         * therefore checkpoints the WAL and switches to DELETE journal mode
         * BEFORE `PRAGMA rekey`; Room re-enables WAL on its own connections
         * afterwards.
         */
        private fun migrateLegacyKeyFileIfNeeded(
            context: Context,
            legacyPassphrase: ByteArray,
            rawKeyBytes: ByteArray,
        ) {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists() || dbFile.length() == 0L) return // fresh install
            if (probeKeyOpens(dbFile, rawKeyBytes)) {
                Log.i(TAG, "SQLCipher raw-key probe OK — file already raw-keyed, skipping migration")
                return
            }

            Log.i(TAG, "SQLCipher key migration: raw-key probe failed, converting legacy passphrase file")

            val db = try {
                SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    legacyPassphrase,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                    null,
                    null,
                )
            } catch (e: Exception) {
                Log.e(TAG, "SQLCipher key migration: legacy-key open failed", e)
                throw e
            }
            try {
                // Reading any page verifies the key; a mismatch throws
                // "file is not a database" before rekey can run.
                db.query("SELECT count(*) FROM sqlite_master").use { it.moveToFirst() }

                // Flush every page out of the WAL into the main file, then
                // leave WAL mode entirely so rekey owns a single encrypted
                // file. Both pragmas RETURN A RESULT ROW, so they must go
                // through query() — sqlcipher-android's execSQL() routes to
                // executeUpdateDelete and rejects row-returning statements
                // ("Queries can be performed using SQLiteDatabase query or
                // rawQuery methods only").
                db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
                db.query("PRAGMA journal_mode = DELETE").use { it.moveToFirst() }

                // rekey returns no rows on current SQLCipher builds, but
                // query() is used for symmetry — it tolerates both shapes.
                db.query("PRAGMA rekey = \"${rawKeyBytes.decodeToString()}\"").use { it.moveToFirst() }
            } catch (e: Exception) {
                Log.e(TAG, "SQLCipher key migration: rekey phase failed", e)
                throw e
            } finally {
                db.close()
            }

            // Invariant check: the file must now open with the raw key. A
            // failure here means rekey did not take effect — surface it (via
            // SessionManager) rather than silently handing Room a mismatched
            // file.
            check(probeKeyOpens(dbFile, rawKeyBytes)) {
                "SQLCipher key migration: post-rekey raw-key probe failed"
            }
            Log.i(TAG, "SQLCipher key migration: database converted to raw key")
        }

        /**
         * Opens [dbFile] with [keyBytes] and reads one page to verify the key
         * actually decrypts the file. Returns false when the key is wrong
         * (SQLCipher reports "file is not a database" on the first read), the
         * file is unreadable, or the native library fails — any of which
         * means the caller must fall back to the legacy path.
         */
        private fun probeKeyOpens(dbFile: java.io.File, keyBytes: ByteArray): Boolean = try {
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                keyBytes,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null,
                null,
            )
            try {
                db.query("SELECT count(*) FROM sqlite_master").use { it.moveToFirst() }
                true
            } finally {
                db.close()
            }
        } catch (e: Exception) {
            false
        }

        /**
         * v1 → v2 (Phase 3A):
         * - adds a `stableId` column to `auth_account`, `totp_credential`,
         *   `recovery_code_set`, `recovery_code` and `import_record`, each
         *   backfilled with the row's existing `id` (the Room primary key is
         *   the stable logical ID for records that predate Phase 3A);
         * - drops the obsolete `backup_record` table (automatic-backup model
         *   removed — manual Export Package only).
         */
        val MIGRATION_1_2: androidx.room.migration.Migration = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE auth_account ADD COLUMN stableId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE auth_account SET stableId = id WHERE stableId = ''")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_auth_account_stableId ON auth_account(stableId)")

                db.execSQL("ALTER TABLE totp_credential ADD COLUMN stableId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE totp_credential SET stableId = id WHERE stableId = ''")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_totp_credential_stableId ON totp_credential(stableId)")

                db.execSQL("ALTER TABLE recovery_code_set ADD COLUMN stableId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE recovery_code_set SET stableId = id WHERE stableId = ''")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_recovery_code_set_stableId ON recovery_code_set(stableId)")

                db.execSQL("ALTER TABLE recovery_code ADD COLUMN stableId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE recovery_code SET stableId = id WHERE stableId = ''")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_recovery_code_stableId ON recovery_code(stableId)")

                db.execSQL("ALTER TABLE import_record ADD COLUMN stableId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE import_record SET stableId = id WHERE stableId = ''")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_import_record_stableId ON import_record(stableId)")

                db.execSQL("DROP TABLE IF EXISTS backup_record")
            }
        }

        /**
         * v2 → v3 (Phase 3C): adds the Developer Vault persistence table.
         *
         * Non-destructive: all existing Authenticator / Recovery / ImportRecord
         * rows and their stableId lineage are untouched. Only the new
         * `developer_entry` table (and its indexes) is created. The Phase 2
         * SQLCipher / VaultKey security model is unchanged.
         */
        val MIGRATION_2_3: androidx.room.migration.Migration = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `developer_entry` (
                        `id` TEXT NOT NULL,
                        `stableId` TEXT NOT NULL,
                        `entryType` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `notes` TEXT,
                        `payloadJson` TEXT NOT NULL,
                        `createdAt` TEXT NOT NULL,
                        `updatedAt` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )""".trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_developer_entry_stableId` ON `developer_entry` (`stableId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_developer_entry_entryType` ON `developer_entry` (`entryType`)")
            }
        }

        /**
         * v3 → v4 (UI polish 2026-09): adds the `provider_meta` table.
         *
         * Display-only metadata keyed by the virtual Provider name
         * (serviceName). Non-destructive: no existing table or column is
         * touched, no row is rewritten. Providers without a row fall back to
         * the AUTO icon (brand auto-match / letter badge).
         */
        val MIGRATION_3_4: androidx.room.migration.Migration = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `provider_meta` (
                        `providerName` TEXT NOT NULL,
                        `iconKey` TEXT,
                        PRIMARY KEY(`providerName`)
                    )""".trimIndent(),
                )
            }
        }

        /** Closes all connections (used by lock()/process teardown). */
        fun closeDatabase(db: RescueAuthDatabase) {
            db.close()
        }

        /** Runs a pragma set on every connection open (16 KB compat checks). */
        fun onConfigurePragmas(db: SupportSQLiteDatabase) {
            // Enforce SQLCipher defaults explicitly; page size is independent
            // of OS 16 KB page size.
            db.execSQL("PRAGMA cipher_memory_security = ON")
            db.execSQL("PRAGMA secure_delete = ON")
        }
    }
}
