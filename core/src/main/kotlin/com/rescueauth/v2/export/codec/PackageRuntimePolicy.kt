package com.rescueauth.v2.export.codec

/**
 * RUNTIME DECODE RESOURCE POLICY for the production/default Android decoder
 * (PACKAGE_FORMAT.md §Runtime decode resource policy / THREAT_MODEL.md).
 *
 * ## Why this is separate from the FORMAT HARD LIMIT
 *
 * - **FORMAT HARD LIMIT** (`PackageHeaderParser` / `PackageFormat`) says what
 *   the wire format can structurally express: memoryKiB up to
 *   `MAX_MEMORY_KIB` (256 MiB), iterations up to `MAX_ITERATIONS` (16). A
 *   package inside these limits is well-formed and *storable*.
 * - **RUNTIME DECODE RESOURCE POLICY** says what the mobile decoder will
 *   actually *execute*. A package may be structurally valid and inside the
 *   format limits yet still demand an Argon2id cost that is unsafe on a
 *   phone (ANR / OOM / battery burn).
 *
 * The accepted decode range must NOT be treated as a promise that the Android
 * decoder will run that cost. [checkDecodeBudget] is invoked by the codec
 * BEFORE Argon2id is ever executed, so over-budget packages are rejected with
 * [PackageCodecException.InvalidKdfParameters] on a pure parameter check.
 *
 * ## Budget
 *
 * Budget is measured in Argon2id memory-time cost units: `memoryKiB ×
 * iterations`. The current app-generated default (19 MiB / 2 iterations ≈
 * 38 912 units) is always accepted; the budget is set at 4× the default
 * (512 K units) with per-axis caps of 128 MiB memory and 8 iterations, so a
 * stronger-but-reasonable future export still decrypts while the worst-case
 * single Argon2id allocation stays ≈ 128 MiB on a mobile process.
 *
 * This policy is the production default. A future variant may offer a
 * user-confirmed "high-cost" decode path (e.g. importing an old very-strong
 * export), but the default decoder must never silently run above the budget.
 */
object PackageRuntimePolicy {

    /** Max memory working set the default decoder will allocate for one Argon2id run. */
    const val MAX_MEMORY_KIB = PackageFormat.RUNTIME_MAX_MEMORY_KIB

    /** Max iterations the default decoder will execute for one Argon2id run. */
    const val MAX_ITERATIONS = PackageFormat.RUNTIME_MAX_ITERATIONS

    /** Max memory×iterations cost units for one Argon2id run. */
    const val MAX_COST_UNITS = PackageFormat.RUNTIME_MAX_COST_UNITS

    /** Cost units of the app-generated default encode parameters. */
    const val DEFAULT_COST_UNITS = PackageFormat.RUNTIME_DEFAULT_COST_UNITS

    /**
     * Pure parameter check — NO allocation, NO KDF execution.
     *
     * @throws PackageCodecException.InvalidKdfParameters if the parameters are
     *   above the runtime decode budget.
     */
    fun checkDecodeBudget(memoryKiB: Int, iterations: Int, parallelism: Int) {
        if (memoryKiB > MAX_MEMORY_KIB) {
            throw PackageCodecException.InvalidKdfParameters(
                "kdf memoryKiB $memoryKiB exceeds the runtime decode budget " +
                    "(max $MAX_MEMORY_KIB KiB on this device)",
            )
        }
        if (iterations > MAX_ITERATIONS) {
            throw PackageCodecException.InvalidKdfParameters(
                "kdf iterations $iterations exceed the runtime decode budget (max $MAX_ITERATIONS)",
            )
        }
        val costUnits = memoryKiB.toLong() * iterations
        if (costUnits > MAX_COST_UNITS) {
            throw PackageCodecException.InvalidKdfParameters(
                "kdf cost $costUnits (memoryKiB×iterations) exceeds the runtime decode budget " +
                    "(max $MAX_COST_UNITS; default encode ≈ $DEFAULT_COST_UNITS)",
            )
        }
        // Parallelism is a CPU multiplier inside a single Argon2 run; cap it at
        // the format maximum here too so a pathological p value cannot multiply
        // the effective cost beyond the intent of the policy.
        if (parallelism > PackageFormat.MAX_PARALLELISM) {
            throw PackageCodecException.InvalidKdfParameters(
                "kdf parallelism $parallelism exceeds the runtime decode budget " +
                    "(max ${PackageFormat.MAX_PARALLELISM})",
            )
        }
    }
}
