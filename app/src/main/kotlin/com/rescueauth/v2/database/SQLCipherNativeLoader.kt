package com.rescueauth.v2.database

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
 * [ensureLoaded] performs the load exactly once per process. It is invoked from
 * the single SQLCipher database entry point ([RescueAuthDatabase.build]) so
 * every database open — including the real instrumented tests, which
 * deliberately do NOT call `loadLibrary` themselves — goes through the
 * production initialization path.
 *
 * ## Concurrency contract (why there is no publish-before-load race)
 *
 * Two threads may call [ensureLoaded] concurrently on the first load. The
 * fast-path volatile read of [loaded] plus the `synchronized` re-check
 * (double-checked locking) guarantees:
 *
 * 1. The native library is loaded **at most once** — a single thread ever runs
 *    [doLoad] under [lock].
 * 2. `loaded` is only ever set to `true` **after** [doLoad] has fully returned
 *    (dlopen + `JNI_OnLoad` + native-method registration all complete). A
 *    thread that observes `loaded == true` therefore happens-after the whole
 *    load, so it can safely call SQLCipher native methods.
 * 3. A thread that arrives while a first load is still in progress either
 *    blocks on the monitor and re-checks the flag (it never returns early and
 *    never calls SQLCipher native code before the load finished), or — if it
 *    read `true` on the fast path — the load is already complete.
 * 4. If [doLoad] throws, `loaded` stays `false`, so a later database entry
 *    point can retry instead of permanently masking the problem.
 *
 * (The previous implementation used `AtomicBoolean.compareAndSet(false, true)`
 * *before* loading; that published "loaded" to other threads while the native
 * core was still being loaded — a real publish-before-load race.)
 */
object SQLCipherNativeLoader {

    private val lock = Any()

    @Volatile
    private var loaded = false

    /**
     * The operation that actually makes the native core available. Kept as an
     * internal seam so host-JVM tests can inject a slow, observable load and
     * deterministically verify the ordering guarantee without a real
     * `libsqlcipher.so` on `java.library.path`. Never reassigned in production.
     */
    internal var doLoad: () -> Unit = { System.loadLibrary("sqlcipher") }

    /**
     * Loads `libsqlcipher.so`; a no-op after the first successful call.
     *
     * `loaded` is published **only after** [doLoad] returns successfully, so no
     * other thread can ever observe the flag as `true` while the native core is
     * still being loaded.
     */
    @JvmStatic
    fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            doLoad()
            loaded = true
        }
    }

    /**
     * Test-only hook: returns the loader to its pristine state so unit tests are
     * order-independent (a successful injected load must not leak into tests
     * that expect the real, host-JVM-missing library to fail loudly).
     */
    internal fun resetForTest() {
        synchronized(lock) {
            loaded = false
            doLoad = { System.loadLibrary("sqlcipher") }
        }
    }
}
