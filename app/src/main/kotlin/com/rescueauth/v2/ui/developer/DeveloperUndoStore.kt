package com.rescueauth.v2.ui.developer

import com.rescueauth.v2.domain.DeletedDeveloperEntrySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * P8 §32 — shared, app-scoped in-memory holder for the pending ordinary
 * Developer delete Undo token.
 *
 * Exposes an observable monotonic [version] counter so active routes (including
 * `Search` via the app shell) can present the Undo snackbar immediately when a
 * detail route pops after deleting an entry.
 */
object DeveloperUndoStore {
    @Volatile
    var pending: DeletedDeveloperEntrySnapshot? = null

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    val pendingLabel: String? get() = pending?.safeLabel

    fun store(snapshot: DeletedDeveloperEntrySnapshot) {
        pending = snapshot
        _version.update { it + 1L }
    }

    fun consume(): DeletedDeveloperEntrySnapshot? {
        val p = pending
        pending = null
        if (p != null) _version.update { it + 1L }
        return p
    }

    fun clear() {
        val hadPending = pending != null
        pending = null
        if (hadPending) _version.update { it + 1L }
    }
}
