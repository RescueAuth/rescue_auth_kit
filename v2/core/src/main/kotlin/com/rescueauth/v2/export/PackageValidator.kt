package com.rescueauth.v2.export

import com.rescueauth.v2.legacy.TotpVerifier

/**
 * Validates a [VaultSnapshot] / [VaultPackagePayload] for internal
 * consistency BEFORE any merge planning happens (Phase 3A §Validation).
 *
 * Policy (mirrors the legacy import contract):
 * - a malformed / internally inconsistent source is REJECTED as a whole;
 *   it must never produce a partial merge plan;
 * - unknown/illegal TOTP parameters are never silently repaired;
 * - invalid base32 secrets are rejected;
 * - recovery-code parent/child identity must be consistent.
 *
 * Validation is a pure function: same input → same result, no I/O.
 */
object PackageValidator {

    /** Supported TOTP algorithms (same set as the legacy validator). */
    private val SUPPORTED_ALGORITHMS = setOf(
        TotpVerifier.ALGORITHM_SHA1,
        TotpVerifier.ALGORITHM_SHA256,
        TotpVerifier.ALGORITHM_SHA512,
    )

    private val SUPPORTED_DIGITS = setOf(6, 7, 8)
    private val SUPPORTED_STATUSES = setOf("UNUSED", "USED")

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
    }

    private fun validateSnapshot(snapshot: VaultSnapshot) {
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
            t.periodSeconds <= 0 -> throw ValidationException("invalid TOTP period '${t.periodSeconds}' for ${t.stableId}")
        }
        try {
            TotpVerifier.decodeBase32(t.secretBase32)
        } catch (e: TotpVerifier.TotpException) {
            throw ValidationException("invalid base32 secret for ${t.stableId}: ${e.message}")
        }
    }
}
