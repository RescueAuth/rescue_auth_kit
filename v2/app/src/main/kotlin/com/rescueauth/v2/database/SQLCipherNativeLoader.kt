package com.rescueauth.v2.database

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Idempotent loader for the SQLCipher native core (`libsqlcipher.so`).
 *
 * ## Why this exists
 *
 * Zetetic `sqlcipher-android` 4.17.0 ships `libsqlcipher.so` inside the APK
 * (`lib/<abi>/libsqlcipher.so`) but its Java side does **not** call
 * `System.loadLibrary` automatically: bytecode inspection of
 * `net.zetetic.database.sqlcipher.SQLiteConnection.<clinit>` shows the static
 * block only initializes `$assertionsDisabled`, `EMPTY_STRING_ARRAY` and
 * `EMPTY_BYTE_ARRAY` and never touches the native library. As a result the
 * first `SQLiteConnection.nativeOpen(...)` on a fresh process fails with
 * `UnsatisfiedLinkError` unless something loads the library first.
 *
 * [ensureLoaded] performs the load exactly once per process (guarded by an
 * [AtomicBoolean]; Android's `System.loadLibrary` is itself idempotent for a
 * given library name). It is invoked from the single SQLCipher database entry
 * point ([RescueAuthDatabase.build]) so every database open — including the
 * real instrumented tests, which deliberately do NOT call `loadLibrary`
 * themselves — goes through the production initialization path.
 */
object SQLCipherNativeLoader {

    private val loaded = AtomicBoolean(false)

    /**
     * Loads `libsqlcipher.so`; a no-op after the first successful call.
     *
     * If loading fails the flag is reset so a later database entry point can
     * retry instead of permanently masking the problem.
     */
    @JvmStatic
    fun ensureLoaded() {
        if (loaded.compareAndSet(false, true)) {
            try {
                System.loadLibrary("sqlcipher")
            } catch (t: Throwable) {
                loaded.set(false)
                throw t
            }
        }
    }
}
