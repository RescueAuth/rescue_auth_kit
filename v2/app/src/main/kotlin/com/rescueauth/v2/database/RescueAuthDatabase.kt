package com.rescueauth.v2.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * v2 encrypted database (schema version 1).
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
    ],
    version = 2,
    exportSchema = true,
)
abstract class RescueAuthDatabase : RoomDatabase() {

    abstract fun authAccountDao(): AuthAccountDao
    abstract fun totpCredentialDao(): TotpCredentialDao
    abstract fun recoveryCodeSetDao(): RecoveryCodeSetDao
    abstract fun recoveryCodeDao(): RecoveryCodeDao
    abstract fun importRecordDao(): ImportRecordDao

    companion object {
        const val DB_NAME = "rescueauth_v2.db"
        const val SCHEMA_VERSION = 2

        /**
         * Builds the encrypted database. [vaultKey] must be the unwrapped
         * 32-byte VaultKey (hex string for SQLCipher). The helper factory is
         * created per-open so a new key (or re-unwrap) takes effect.
         */
        fun build(context: Context, vaultKey: ByteArray): RescueAuthDatabase {
            // SQLCipher 4.17.0 does not load its native core automatically
            // (see SQLCipherNativeLoader). Every database entry point must
            // guarantee libsqlcipher.so is present before opening a
            // connection; the loader is idempotent per process.
            SQLCipherNativeLoader.ensureLoaded()
            val keyHex = vaultKey.joinToString("") { "%02x".format(it) }
            val factory = object : SupportSQLiteOpenHelper.Factory {
                override fun create(configuration: SupportSQLiteOpenHelper.Configuration):
                    SupportSQLiteOpenHelper {
                    val sqlcipherFactory = SupportOpenHelperFactory(
                        byteArrayOf(0x01) + keyHex.toByteArray(Charsets.UTF_8)
                    )
                    return sqlcipherFactory.create(configuration)
                }
            }
            return Room.databaseBuilder(context, RescueAuthDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
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
