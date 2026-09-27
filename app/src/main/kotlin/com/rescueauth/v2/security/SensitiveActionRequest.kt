package com.rescueauth.v2.security

/**
 * An immutable, precise request for a sensitive operation.
 *
 * Binds the operation ([action]) to the original target ([target]) so that a
 * successful fresh re-auth authorizes **exactly** this request and nothing
 * else (Issue #20 P4 security-boundary CR §2).
 *
 * The [SensitiveActionGate] tracks the pending / authorized request by full
 * value equality (action + target), so an authorization can never be applied
 * to a different entry / field / operation selected later — even when the
 * current selection, navigation or a same-type second request changes while
 * the prompt is showing.
 */
data class SensitiveActionRequest(
    val action: SensitiveAction,
    val target: SensitiveActionTarget = SensitiveActionTarget.Global,
)

/**
 * The immutable target of a [SensitiveActionRequest].
 *
 * Captures enough identity to prevent a successful authorization from being
 * applied to a different target:
 *
 * - [Global] — operations with no finer-grained target (e.g. Full Vault
 *   Export).
 * - [DeveloperField] — a specific Developer entry field, identified by the
 *   entry's **stableId** plus a stable non-secret **field key** (e.g.
 *   `"apiSecret"`, `"privateKey"`, `"passphrase"`, `"field:<label>"`).
 * - [DeveloperEdit] — a full editor opening, bound to stableId plus a fresh
 *   in-memory attemptId so a previous opening cannot authorize a retry.
 *
 * The stableId is part of the target so a request for Entry A can never be
 * satisfied by an auth result while the user is looking at Entry B.
 */
sealed interface SensitiveActionTarget {
    /** No finer-grained target (e.g. a full-vault / section-scope export). */
    object Global : SensitiveActionTarget

    /**
     * A specific package export request (Issue #20 §7). Binds the authorized
     * re-auth to exactly one export scope + selection digest so that a scope A
     * authorization can never authorize a scope B pending request (test 38)
     * and a selected export's auth is bound to the original selection
     * (test 39). [selectionDigest] is non-null only for
     * [com.rescueauth.v2.exportimport.ExportScopeSpec.SelectedItems] exports.
     */
    data class ExportRequest(
        val scopeName: String,
        val selectionDigest: String? = null,
    ) : SensitiveActionTarget

    /** A specific Developer entry field (entry stableId + field key). */
    data class DeveloperField(
        val stableId: String,
        val fieldKey: String,
    ) : SensitiveActionTarget

    /** One editor-opening attempt, bound to an entry. Never persisted or passed in navigation. */
    data class DeveloperEdit(
        val stableId: String,
        val attemptId: String,
    ) : SensitiveActionTarget
}
