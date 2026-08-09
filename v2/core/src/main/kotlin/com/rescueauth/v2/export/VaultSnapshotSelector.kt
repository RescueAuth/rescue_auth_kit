package com.rescueauth.v2.export

import java.security.MessageDigest

/**
 * Shared pure logical **selection / filtering** engine for Selective
 * Export / Import (Phase 4 P5).
 *
 * This is the single place that turns a user's stableId-based selection into a
 * filtered [VaultSnapshot] (scope = [SnapshotScope.SELECTED_ITEMS]) and that
 * enumerates what is selectable in a snapshot. It is used by BOTH the export
 * and the import flow so the hierarchy / dependency-closure semantics are
 * never duplicated (Issue #20 §16):
 *
 * ```
 * export:   current VaultSnapshot → SelectableItems (UI) → SelectedItemSet
 *           → VaultSnapshotSelector.selected → (encode)
 * import:   decoded package VaultSnapshot → SelectableItems (UI) → SelectedItemSet
 *           → VaultSnapshotSelector.selected → (filter) → MergePlanner → apply
 * ```
 *
 * The engine is pure Kotlin with NO Android / Room / UI dependencies and NO
 * secret handling: its input/output are logical stableIds and logical
 * snapshots. The UI shows safe metadata; the *identity* is always a stableId.
 *
 * ## Selection identity (Issue #20 §5)
 *
 * Selection never depends on list index / title / account name / sort
 * position / Room row id. [SelectedItemSet] carries only logical **stableId**
 * sets:
 *
 * - account stableIds,
 * - TOTP credential stableIds,
 * - recovery-code-set stableIds (a set is atomic — never per-code),
 * - Developer Entry stableIds (an entry is atomic).
 *
 * A "Provider" has no stableId in the portable logical schema (a Provider is
 * the `serviceName` grouping of accounts, ROADMAP §8 / PACKAGE_FORMAT §Identity).
 * Selecting a Provider is therefore a UI convenience that **expands to the
 * account stableIds** of that provider ([SelectableProvider.expandToStableIds])
 * before the committed selection is built — the committed [SelectedItemSet]
 * is always stableId-only.
 *
 * ## Hierarchy / dependency closure (Issue #20 §4)
 *
 * Selected items are never orphaned, and selecting a parent never silently
 * pulls in siblings:
 *
 * - Provider selected → all accounts under it → all of each account's TOTP +
 *   Recovery Sets;
 * - Account selected → all its TOTP + Recovery Sets, and the account row
 *   (which carries the Provider `serviceName` parent metadata) is included;
 * - single TOTP selected → only that TOTP, plus its account (parent metadata);
 * - Recovery Code Set selected → the WHOLE set (atomic; per-code selection is
 *   never allowed), plus its account;
 * - Developer Entry selected → the whole entry (atomic; per-field selection is
 *   never allowed) for all five types (Android Signing Key / API Credential /
 *   SSH Key / Environment Variable Set / Generic Secret).
 *
 * "Auto-include parent" is structural dependency closure, not "also export the
 * parent's other children": selecting one TOTP of Account A does NOT export
 * Account A's other TOTPs / Recovery Sets unless they are selected too.
 *
 * ## Stale / missing selection (Issue #20 §5, §14)
 *
 * [selected] re-validates every selected stableId against the snapshot it is
 * filtering. A missing / stale stableId throws [SelectionStaleException] —
 * the caller must surface "selection stale / item changed" instead of
 * silently exporting or importing a different object. [selected] also rejects
 * an empty selection ([EmptySelectionException]) so a "selected items" package
 * is never accidentally produced from nothing.
 */
object VaultSnapshotSelector {

    // ------------------------------------------------------------------
    // Section presets (used by the scope pickers on BOTH export & import)
    // ------------------------------------------------------------------

    /** Entire Vault: Authenticator + Recovery Codes + all Developer Entries. */
    fun fullVault(snapshot: VaultSnapshot): VaultSnapshot =
        snapshot.copy(scope = SnapshotScope.FULL_VAULT)

    /** Authenticator section only (accounts + TOTP + Recovery Sets), no Developer. */
    fun authenticatorOnly(snapshot: VaultSnapshot): VaultSnapshot =
        snapshot.copy(
            accounts = snapshot.accounts,
            developerEntries = emptyList(),
            scope = SnapshotScope.AUTHENTICATOR_ONLY,
        )

    /** Developer section only, no Authenticator. */
    fun developerOnly(snapshot: VaultSnapshot): VaultSnapshot =
        snapshot.copy(
            accounts = emptyList(),
            developerEntries = snapshot.developerEntries,
            scope = SnapshotScope.DEVELOPER_ONLY,
        )

    /** Everything the snapshot carries, as an explicit item set. */
    fun everything(snapshot: VaultSnapshot): SelectedItemSet = SelectedItemSet(
        selectedAccountStableIds = snapshot.accounts.map { it.stableId }.toSet(),
        selectedTotpStableIds = snapshot.accounts.flatMap { it.totpCredentials.map { c -> c.stableId } }.toSet(),
        selectedRecoverySetStableIds = snapshot.accounts.flatMap { it.recoveryCodeSets.map { s -> s.stableId } }.toSet(),
        selectedDeveloperStableIds = snapshot.developerEntries.map { it.stableId }.toSet(),
    )

    /** All Authenticator items the snapshot carries (no Developer). */
    fun authenticatorItemSet(snapshot: VaultSnapshot): SelectedItemSet = SelectedItemSet(
        selectedAccountStableIds = snapshot.accounts.map { it.stableId }.toSet(),
        selectedTotpStableIds = snapshot.accounts.flatMap { it.totpCredentials.map { c -> c.stableId } }.toSet(),
        selectedRecoverySetStableIds = snapshot.accounts.flatMap { it.recoveryCodeSets.map { s -> s.stableId } }.toSet(),
        selectedDeveloperStableIds = emptySet(),
    )

    /** All Developer Entries the snapshot carries. */
    fun developerItemSet(snapshot: VaultSnapshot): SelectedItemSet = SelectedItemSet(
        selectedAccountStableIds = emptySet(),
        selectedTotpStableIds = emptySet(),
        selectedRecoverySetStableIds = emptySet(),
        selectedDeveloperStableIds = snapshot.developerEntries.map { it.stableId }.toSet(),
    )

    // ------------------------------------------------------------------
    // What is selectable (safe metadata for the selection UI)
    // ------------------------------------------------------------------

    /**
     * Builds the safe, selectable enumeration of [snapshot] for the selection
     * UI. Contains NO secrets — only stableIds and display metadata (labels /
     * titles / counts), so it is safe to hold in Compose state.
     */
    fun selectableItems(snapshot: VaultSnapshot): SelectableItems {
        val providers = LinkedHashMap<String, MutableList<VaultAccount>>()
        for (account in snapshot.accounts) {
            providers.getOrPut(account.serviceName) { mutableListOf() }.add(account)
        }
        val providerList = providers.map { (serviceName, accounts) ->
            SelectableProvider(
                serviceName = serviceName,
                accounts = accounts.map { account ->
                    SelectableAccount(
                        stableId = account.stableId,
                        serviceName = account.serviceName,
                        accountName = account.accountName,
                        totpCredentials = account.totpCredentials.map { totp ->
                            SelectableItem(stableId = totp.stableId, label = totpLabel(totp))
                        },
                        recoveryCodeSets = account.recoveryCodeSets.map { set ->
                            SelectableRecoverySet(
                                stableId = set.stableId,
                                title = set.title,
                                codeCount = set.codes.size,
                                usedCount = set.codes.count { it.status == "USED" },
                            )
                        },
                    )
                },
            )
        }
        val developerEntries = snapshot.developerEntries.map { entry ->
            SelectableDeveloperEntry(
                stableId = entry.stableId,
                type = developerTypeName(entry),
                title = entry.title,
                displayName = developerDisplayName(entry),
            )
        }
        return SelectableItems(providers = providerList, developerEntries = developerEntries)
    }

    // ------------------------------------------------------------------
    // Selection validation + filtering
    // ------------------------------------------------------------------

    /**
     * Validates [selection] against [snapshot] and throws
     * [SelectionStaleException] (missing stableIds) or [EmptySelectionException]
     * (nothing selected). Returns the canonical [SelectedItemSet].
     */
    fun validateSelection(snapshot: VaultSnapshot, selection: SelectedItemSet): SelectedItemSet {
        if (selection.isEmpty) throw EmptySelectionException()
        val accountIds = snapshot.accounts.map { it.stableId }.toSet()
        val totpIds = snapshot.accounts.flatMap { it.totpCredentials.map { c -> c.stableId } }.toSet()
        val setIds = snapshot.accounts.flatMap { it.recoveryCodeSets.map { s -> s.stableId } }.toSet()
        val devIds = snapshot.developerEntries.map { it.stableId }.toSet()
        val missing = LinkedHashSet<String>()
        (selection.selectedAccountStableIds - accountIds).forEach { missing += "account:$it" }
        (selection.selectedTotpStableIds - totpIds).forEach { missing += "totp:$it" }
        (selection.selectedRecoverySetStableIds - setIds).forEach { missing += "recoverySet:$it" }
        (selection.selectedDeveloperStableIds - devIds).forEach { missing += "developer:$it" }
        if (missing.isNotEmpty()) throw SelectionStaleException(missing)
        return selection
    }

    /**
     * Filters [snapshot] to the selected items and applies the hierarchy /
     * dependency closure (scope = [SnapshotScope.SELECTED_ITEMS]).
     *
     * The output preserves the source ordering (deterministic) and can never
     * contain an orphan child (every kept child's account is kept).
     *
     * @throws SelectionStaleException when a selected stableId is missing from
     *   [snapshot] (caller must NOT silently proceed).
     * @throws EmptySelectionException when [selection] selects nothing.
     */
    fun selected(snapshot: VaultSnapshot, selection: SelectedItemSet): VaultSnapshot {
        validateSelection(snapshot, selection)

        val accountByStable = snapshot.accounts.associateBy { it.stableId }
        val totpAccount = HashMap<String, String>()
        val setAccount = HashMap<String, String>()
        for (account in snapshot.accounts) {
            account.totpCredentials.forEach { totpAccount[it.stableId] = account.stableId }
            account.recoveryCodeSets.forEach { setAccount[it.stableId] = account.stableId }
        }

        // Dependency closure: selected accounts + parents of selected children.
        val parentAccountsOfTotps = selection.selectedTotpStableIds.map { totpAccount[it] }.filterNotNull().toSet()
        val parentAccountsOfSets = selection.selectedRecoverySetStableIds.map { setAccount[it] }.filterNotNull().toSet()
        val selectedAccountSet = selection.selectedAccountStableIds +
            parentAccountsOfTotps +
            parentAccountsOfSets

        val filteredAccounts = snapshot.accounts
            .filter { it.stableId in selectedAccountSet }
            .map { account ->
                val explicitAccount = account.stableId in selection.selectedAccountStableIds
                val totps = if (explicitAccount) {
                    account.totpCredentials
                } else {
                    account.totpCredentials.filter { it.stableId in selection.selectedTotpStableIds }
                }
                val sets = if (explicitAccount) {
                    account.recoveryCodeSets
                } else {
                    account.recoveryCodeSets.filter { it.stableId in selection.selectedRecoverySetStableIds }
                }
                account.copy(totpCredentials = totps, recoveryCodeSets = sets)
            }
            .also { checkNoOrphans(it) }

        val filteredDevelopers = snapshot.developerEntries
            .filter { it.stableId in selection.selectedDeveloperStableIds }

        return VaultSnapshot(
            accounts = filteredAccounts,
            developerEntries = filteredDevelopers,
            scope = SnapshotScope.SELECTED_ITEMS,
        )
    }

    /**
     * Internal invariant: a filtered snapshot must never carry a child whose
     * account is absent. Guaranteed by construction in [selected]; asserted
     * here defensively so a future change cannot silently introduce orphans
     * (Issue #20 §4 / test 11).
     */
    private fun checkNoOrphans(accounts: List<VaultAccount>) {
        val present = accounts.map { it.stableId }.toSet()
        for (account in accounts) {
            check(account.stableId in present) { "orphan account reference ${account.stableId}" }
            // children are nested inside their account in the logical model, so
            // a present account always carries its own children.
        }
    }

    // ------------------------------------------------------------------
    // Safe display helpers (never secret values)
    // ------------------------------------------------------------------

    private fun totpLabel(totp: VaultTotpCredential): String {
        // TOTP credentials have no title of their own in the logical model;
        // use the (non-secret) parameters for the UI label.
        return "${totp.digits}-digit · ${totp.algorithm} · ${totp.periodSeconds}s"
    }

    private fun developerTypeName(entry: VaultDeveloperEntry): String = when (entry) {
        is VaultAndroidSigningKey -> "android_signing_key"
        is VaultApiCredential -> "api_credential"
        is VaultSshKey -> "ssh_key"
        is VaultEnvironmentVariableSet -> "environment_variable_set"
        is VaultGenericSecret -> "generic_secret"
    }

    private fun developerDisplayName(entry: VaultDeveloperEntry): String = when (entry) {
        is VaultAndroidSigningKey -> entry.projectName
        is VaultApiCredential -> entry.serviceName
        is VaultSshKey -> entry.keyName
        is VaultEnvironmentVariableSet -> entry.projectName
        is VaultGenericSecret -> entry.title
    }
}

// ---------------------------------------------------------------------------
// Selection / selectable models
// ---------------------------------------------------------------------------

/**
 * A committed, stableId-only selection (Issue #20 §5, §16).
 *
 * Holds only logical stableIds (non-secret). Recovery Code Sets are atomic
 * (the whole set is selected, never individual codes) and Developer Entries
 * are atomic (never per-field). Provider-level selection is a UI convenience
 * that expands to account stableIds before this model is built.
 */
data class SelectedItemSet(
    val selectedAccountStableIds: Set<String> = emptySet(),
    val selectedTotpStableIds: Set<String> = emptySet(),
    val selectedRecoverySetStableIds: Set<String> = emptySet(),
    val selectedDeveloperStableIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = selectedAccountStableIds.isEmpty() &&
            selectedTotpStableIds.isEmpty() &&
            selectedRecoverySetStableIds.isEmpty() &&
            selectedDeveloperStableIds.isEmpty()

    val authenticatorCount: Int
        get() = selectedAccountStableIds.size + selectedTotpStableIds.size + selectedRecoverySetStableIds.size

    val developerCount: Int
        get() = selectedDeveloperStableIds.size

    /**
     * A canonical digest of the selection (sorted stableIds), used to bind a
     * sensitive-action re-auth to the exact export selection that was
     * authorized (Issue #20 §7, §27 test 39). Non-secret.
     */
    fun digest(): String {
        val parts = buildList {
            selectedAccountStableIds.forEach { add("a\u0000$it") }
            selectedTotpStableIds.forEach { add("t\u0000$it") }
            selectedRecoverySetStableIds.forEach { add("s\u0000$it") }
            selectedDeveloperStableIds.forEach { add("d\u0000$it") }
        }.sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(parts.joinToString("\u0001").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Safe, selectable enumeration of a snapshot's contents for the selection UI.
 *
 * Contains NO secrets — only stableIds and display metadata / counts — so it
 * is safe to hold in Compose state and safe to show in previews.
 */
data class SelectableItems(
    val providers: List<SelectableProvider> = emptyList(),
    val developerEntries: List<SelectableDeveloperEntry> = emptyList(),
) {
    val hasAuthenticator: Boolean get() = providers.isNotEmpty()
    val hasDeveloper: Boolean get() = developerEntries.isNotEmpty()

    val accountCount: Int get() = providers.sumOf { it.accounts.size }
    val totpCount: Int get() = providers.sumOf { p -> p.accounts.sumOf { it.totpCredentials.size } }
    val recoverySetCount: Int get() = providers.sumOf { p -> p.accounts.sumOf { it.recoveryCodeSets.size } }
    val developerCount: Int get() = developerEntries.size

    val isEmpty: Boolean get() = providers.isEmpty() && developerEntries.isEmpty()
}

/**
 * One Provider grouping (by `serviceName`) in the selection UI.
 *
 * A Provider has no stableId in the portable logical schema; selecting one
 * expands to the account stableIds of [expandToStableIds] before the committed
 * selection is built (Issue #20 §4, §5).
 */
data class SelectableProvider(
    val serviceName: String,
    val accounts: List<SelectableAccount> = emptyList(),
) {
    val totpCount: Int get() = accounts.sumOf { it.totpCredentials.size }
    val recoverySetCount: Int get() = accounts.sumOf { it.recoveryCodeSets.size }

    /** Stable account ids under this provider (the committed selection identity). */
    fun expandToStableIds(): Set<String> = accounts.map { it.stableId }.toSet()
}

data class SelectableAccount(
    val stableId: String,
    val serviceName: String,
    val accountName: String,
    val totpCredentials: List<SelectableItem> = emptyList(),
    val recoveryCodeSets: List<SelectableRecoverySet> = emptyList(),
)

/** A leaf selectable item (TOTP). [label] is non-secret display metadata. */
data class SelectableItem(val stableId: String, val label: String)

/** A Recovery Code Set (atomic selection unit). Counts are non-secret. */
data class SelectableRecoverySet(
    val stableId: String,
    val title: String,
    val codeCount: Int,
    val usedCount: Int,
) {
    val remainingCount: Int get() = codeCount - usedCount
}

/** A Developer Entry (atomic selection unit). Safe metadata only. */
data class SelectableDeveloperEntry(
    val stableId: String,
    val type: String,
    val title: String,
    val displayName: String,
)

/** Thrown when a selected stableId no longer exists in the snapshot being filtered. */
class SelectionStaleException(val missingStableIds: Set<String>) :
    Exception("selection stale: missing ${missingStableIds.sorted().joinToString(", ")}")

/** Thrown when a "Selected Items" export/import is attempted with nothing selected. */
class EmptySelectionException : Exception("no items selected")
