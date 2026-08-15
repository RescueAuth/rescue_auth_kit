package com.rescueauth.v2.ui.developer

import com.rescueauth.v2.domain.DeletedDeveloperEntrySnapshot

/**
 * P8 §32 — shared, app-scoped in-memory holder for the pending ordinary
 * Developer delete Undo token.
 *
 * ## Why shared (not a detail-slice local field)
 *
 * An ordinary Developer delete happens on the Detail screen, which is popped
 * immediately after (`onBack()`). If the pending Undo token lived only in the
 * Detail ViewModel it would be disposed with the screen and the Snackbar Undo
 * would be lost. This holder survives that navigation so the Developer list
 * route (which stays in the back stack) can show the Undo Snackbar and restore
 * on action (P8 §32).
 *
 * ## Security (P8 §5, §13)
 *
 * - In-memory only: never persisted, never in SavedStateHandle/Bundle/
 *   DataStore/file/cache/logs/clipboard/navigation route.
 * - Cleared on session lock ([clear]) — the Undo token is NOT restored after
 *   unlock (P8 §6). The Developer list / detail ViewModels call [clear] when
 *   they observe a lock transition.
 * - Only ONE pending Undo is held at a time: a new delete replaces the previous
 *   token and releases its secret snapshot (P8 §7).
 */
object DeveloperUndoStore {
    /** The pending ordinary Developer entry snapshot, or null when none. */
    @Volatile
    var pending: DeletedDeveloperEntrySnapshot? = null

    /** Safe display label of the pending entry, or null when none. */
    val pendingLabel: String? get() = pending?.safeLabel

    /** Replaces the current token with [snapshot] (releases the old one). */
    fun store(snapshot: DeletedDeveloperEntrySnapshot) {
        pending = snapshot
    }

    /** Consumes (clears) the token and returns it for a single Undo attempt. */
    fun consume(): DeletedDeveloperEntrySnapshot? {
        val p = pending
        pending = null
        return p
    }

    /** Clears the pending token (session lock / new delete without Undo). */
    fun clear() {
        pending = null
    }
}
