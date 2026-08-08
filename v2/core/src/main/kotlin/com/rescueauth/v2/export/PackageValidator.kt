package com.rescueauth.v2.export

import java.util.Base64

/**
 * Validates a [VaultSnapshot] / [VaultPackagePayload] for internal
 * consistency BEFORE any merge planning happens (Phase 3A §Validation).
 *
 * Policy (mirrors the legacy import contract):
 * - a malformed / internally inconsistent source is REJECTED as a whole;
 *   it must never produce a partial merge plan;
 * - unknown/illegal TOTP parameters are never silently repaired;
 * - invalid base32 secrets are rejected;
 * - recovery-code parent/child identity must be consistent;
 * - Developer Entry sensitive payloads must be well-formed (base64 keystore,
 *   non-blank secrets where required, size upper bound on binary assets).
 *
 * ## Scope contract (ROADMAP §8.4 / §17)
 *
 * A [VaultSnapshot] may carry a partial vault (selective export). The declared
 * [SnapshotScope] must be consistent with the actual content:
 *
 * - [SnapshotScope.AUTHENTICATOR_ONLY] → no developer entries;
 * - [SnapshotScope.DEVELOPER_ONLY] → no accounts;
 * - [SnapshotScope.FULL_VAULT] → both sections may be present (a vault may
 *   legitimately have an empty Authenticator or Developer section);
 * - [SnapshotScope.SELECTED_ITEMS] → any combination of items.
 *
 * Validation is a pure function: same input → same result, no I/O.
 */
object PackageValidator {

    private val SUPPORTED_ALGORITHMS = TotpParameters.SUPPORTED_ALGORITHMS
    private val SUPPORTED_DIGITS = TotpParameters.SUPPORTED_DIGITS
    private val SUPPORTED_STATUSES = setOf("UNUSED", "USED")

    /**
     * Upper bound for a binary keystore asset (base64 text length).
     * 8 MiB of raw keystore ≈ 11.2 MiB of base64 — comfortably above any real
     * Android keystore while still guarding against pathological payloads
     * (ROADMAP §8.2: "base64 或等价编码 + 大小上限").
     *
     * This is the **per-asset** cap only. The **whole-snapshot** capacity is
     * enforced separately via [PackageCapacity] so that a validator-accepted
     * payload is always encodable within the 16 MiB package limit
     * (PACKAGE_FORMAT.md §Capacity).
     */
    const val MAX_KEYSTORE_BASE64_LENGTH = 12 * 1024 * 1024

    /**
     * Whole-snapshot serialized-payload budget shared with the codec
     * ([PackageCapacity.MAX_SERIALIZED_PAYLOAD_SIZE]). A payload whose
     * estimated serialized size exceeds this budget is rejected here so the
     * "validator accepts but Full Export can never encode" state is impossible.
     */
    const val MAX_SERIALIZED_PAYLOAD_BUDGET = PackageCapacity.MAX_SERIALIZED_PAYLOAD_SIZE

    class ValidationException(message: String) : Exception(message)

    fun validate(snapshot: VaultSnapshot) {
        validateSnapshot(snapshot)
    }

    fun validate(payload: VaultPackagePayload) {
        if (payload.logicalSchemaVersion != VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION) {
            throw ValidationException(
                "Unsupported logical schema version ${payload.logicalSchemaVersion} " +
                    "(supported: ${VaultPackagePayload.CURRENT_LOGICAL_SCHEMA_VERSION})",
            )
        }
        if (payload.packageId.isBlank()) throw ValidationException("packageId must not be blank")
        if (payload.createdAt.isBlank()) throw ValidationException("createdAt must not be blank")
        validateSnapshot(payload.snapshot)
        validateCapacityBudget(payload)
    }

    /**
     * Enforces the whole-payload capacity contract: the estimated serialized
     * size must fit the codec's package budget. Without this, a logical-valid
     * FULL_VAULT could exceed the 16 MiB package limit and be impossible to
     * export (PACKAGE_FORMAT.md §Capacity).
     */
    private fun validateCapacityBudget(payload: VaultPackagePayload) {
        val estimated = PackageCapacity.estimateSerializedSize(payload)
        if (estimated > MAX_SERIALIZED_PAYLOAD_BUDGET) {
            throw ValidationException(
                "payload exceeds the package capacity budget: estimated serialized size " +
                    "$estimated bytes > max $MAX_SERIALIZED_PAYLOAD_BUDGET bytes " +
                    "(16 MiB package cap minus header/tag; reduce Developer Entry assets or account volume)",
            )
        }
    }

    private fun validateSnapshot(snapshot: VaultSnapshot) {
        // Scope consistency: a partial snapshot must not carry the sections it
        // claims to exclude (selective package contract).
        when (snapshot.scope) {
            SnapshotScope.AUTHENTICATOR_ONLY -> {
                if (snapshot.developerEntries.isNotEmpty()) {
                    throw ValidationException(
                        "scope=AUTHENTICATOR_ONLY but snapshot carries ${snapshot.developerEntries.size} developer entries",
                    )
                }
            }
            SnapshotScope.DEVELOPER_ONLY -> {
                if (snapshot.accounts.isNotEmpty()) {
                    throw ValidationException(
                        "scope=DEVELOPER_ONLY but snapshot carries ${snapshot.accounts.size} accounts",
                    )
                }
            }
            SnapshotScope.FULL_VAULT, SnapshotScope.SELECTED_ITEMS -> Unit // any combination is valid
        }

        val accountIds = HashSet<String>()
        for (account in snapshot.accounts) {
            if (account.stableId.isBlank()) {
                throw ValidationException("account stableId must not be blank")
            }
            if (!accountIds.add(account.stableId)) {
                throw ValidationException("duplicate account stableId '${account.stableId}'")
            }
            validateAccount(account)
        }

        val developerIds = HashSet<String>()
        for (entry in snapshot.developerEntries) {
            if (entry.stableId.isBlank()) {
                throw ValidationException("developer entry stableId must not be blank")
            }
            if (!developerIds.add(entry.stableId)) {
                throw ValidationException("duplicate developer entry stableId '${entry.stableId}'")
            }
            validateDeveloperEntry(entry)
        }
    }

    private fun validateAccount(account: VaultAccount) {
        // TOTP credentials: unique stableId within this account.
        val totpIds = HashSet<String>()
        for (t in account.totpCredentials) {
            if (t.stableId.isBlank()) throw ValidationException("totp stableId must not be blank")
            if (!totpIds.add(t.stableId)) {
                throw ValidationException("duplicate totp stableId '${t.stableId}' in account ${account.stableId}")
            }
            validateTotp(t)
        }

        // Recovery code sets + their codes (parent/child identity).
        val setIds = HashSet<String>()
        for (set in account.recoveryCodeSets) {
            if (set.stableId.isBlank()) throw ValidationException("recovery set stableId must not be blank")
            if (!setIds.add(set.stableId)) {
                throw ValidationException("duplicate recovery set stableId '${set.stableId}'")
            }
            val codeIds = HashSet<String>()
            for (code in set.codes) {
                if (code.stableId.isBlank()) throw ValidationException("recovery code stableId must not be blank")
                if (!codeIds.add(code.stableId)) {
                    throw ValidationException("duplicate recovery code stableId '${code.stableId}'")
                }
                if (code.status !in SUPPORTED_STATUSES) {
                    throw ValidationException("invalid recovery code status '${code.status}'")
                }
            }
        }
    }

    private fun validateTotp(t: VaultTotpCredential) {
        val algo = t.algorithm.trim().uppercase()
        when {
            algo.isEmpty() -> throw ValidationException("missing TOTP algorithm for ${t.stableId}")
            algo !in SUPPORTED_ALGORITHMS -> throw ValidationException("unknown TOTP algorithm '$algo' for ${t.stableId}")
            t.digits !in SUPPORTED_DIGITS -> throw ValidationException("invalid TOTP digits '${t.digits}' for ${t.stableId}")
            t.periodSeconds !in TotpParameters.MIN_PERIOD_SECONDS..TotpParameters.MAX_PERIOD_SECONDS ->
                throw ValidationException("invalid TOTP period '${t.periodSeconds}' for ${t.stableId}")
        }
        if (!TotpParameters.isValidBase32(t.secretBase32)) {
            throw ValidationException("invalid base32 secret for ${t.stableId}")
        }
    }

    private fun validateDeveloperEntry(entry: VaultDeveloperEntry) {
        when (entry) {
            is VaultAndroidSigningKey -> {
                requireBase64Keystore(entry)
                if (entry.keyAlias.isBlank()) {
                    throw ValidationException("android signing key ${entry.stableId}: keyAlias must not be blank")
                }
            }
            is VaultApiCredential -> {
                if (entry.apiKey.isBlank()) {
                    throw ValidationException("api credential ${entry.stableId}: apiKey must not be blank")
                }
                if (entry.apiSecret.isBlank()) {
                    throw ValidationException("api credential ${entry.stableId}: apiSecret must not be blank")
                }
            }
            is VaultSshKey -> {
                if (entry.privateKey.isBlank()) {
                    throw ValidationException("ssh key ${entry.stableId}: privateKey must not be blank")
                }
            }
            is VaultEnvironmentVariableSet -> {
                for (kv in entry.variables) {
                    if (kv.key.isBlank()) {
                        throw ValidationException("environment variable set ${entry.stableId}: variable key must not be blank")
                    }
                }
            }
            is VaultGenericSecret -> Unit // arbitrary label=value fields; no structural requirement
        }
    }

    /**
     * Enforces the binary-asset contract (ROADMAP §8.2): keystore contents
     * must be valid base64 and bounded in size so a schema limit never
     * silently truncates or drops the binary asset.
     */
    private fun requireBase64Keystore(entry: VaultAndroidSigningKey) {
        val raw = entry.keystoreBase64
        if (raw.isBlank()) {
            throw ValidationException("android signing key ${entry.stableId}: keystore contents must not be blank")
        }
        if (raw.length > MAX_KEYSTORE_BASE64_LENGTH) {
            throw ValidationException(
                "android signing key ${entry.stableId}: keystore base64 exceeds size limit " +
                    "(max $MAX_KEYSTORE_BASE64_LENGTH chars)",
            )
        }
        try {
            Base64.getDecoder().decode(raw)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("android signing key ${entry.stableId}: keystore contents are not valid base64")
        }
    }
}
