package com.rescueauth.v2.ui.authenticator

import com.rescueauth.v2.repository.AccountAdditionContent
import com.rescueauth.v2.repository.ProviderAccountRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.totp.OtpauthParser
import com.rescueauth.v2.totp.ParsedTotp
import com.rescueauth.v2.totp.TotpCore
import com.rescueauth.v2.ui.model.AccountUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AccountAddContentKind { NONE, TOTP, RECOVERY }
enum class AccountAddScope { VAULT, SERVICE, ACCOUNT }

/** Draft secrets are in memory only; switching tabs, dismissal and session lock discard them. */
data class AccountAddFormState(
    val visible: Boolean = false,
    val scope: AccountAddScope = AccountAddScope.SERVICE,
    val provider: String = "",
    val selectedAccountId: String? = null,
    val accountName: String = "",
    val kind: AccountAddContentKind = AccountAddContentKind.NONE,
    val totp: AddTotpFormState = AddTotpFormState(mode = AddMode.MANUAL),
    val recovery: RecoveryFormState = RecoveryFormState(),
    val submitting: Boolean = false,
    val error: String? = null,
    val providerEdited: Boolean = false,
    val accountEdited: Boolean = false,
    val expectsExistingProvider: Boolean = true,
)

class AccountAddViewModel(
    private val repositoryProvider: () -> ProviderAccountRepository?,
    private val sessionState: StateFlow<SecureSessionStateMachine.State>,
    scope: CoroutineScope,
    private val defaultRecoveryTitleProvider: () -> String = { "Recovery codes" },
) {
    private val _formState = MutableStateFlow(AccountAddFormState())
    val formState = _formState.asStateFlow()
    private var generation = 0
    private var accounts = emptyList<AccountUi>()

    init {
        scope.launch { sessionState.collect { if (it != SecureSessionStateMachine.State.UNLOCKED) {
            accounts = emptyList(); dismiss()
        } } }
    }

    /** Only safe target metadata is retained, never generated codes or recovery values. */
    fun setAccounts(value: List<AccountUi>) {
        accounts = value.map { it.copy(totpCredentials = emptyList(), recoverySets = emptyList()) }
        _formState.update { form ->
            if (form.visible && form.scope == AccountAddScope.VAULT && accounts.any { it.providerName == form.provider.trim() })
                form.copy(expectsExistingProvider = true) else form
        }
    }

    fun beginAtHome(kind: AccountAddContentKind = AccountAddContentKind.TOTP) {
        if (sessionState.value != SecureSessionStateMachine.State.UNLOCKED) return
        generation++
        _formState.value = AccountAddFormState(visible = true, scope = AccountAddScope.VAULT,
            kind = kind, expectsExistingProvider = false)
    }

    fun begin(provider: String, account: AccountUi? = null, kind: AccountAddContentKind? = null) {
        if (sessionState.value != SecureSessionStateMachine.State.UNLOCKED) return
        if (account != null && account.providerName != provider) return
        generation++
        _formState.value = AccountAddFormState(visible = true, provider = provider,
            scope = if (account == null) AccountAddScope.SERVICE else AccountAddScope.ACCOUNT,
            selectedAccountId = account?.id, accountName = account?.accountName.orEmpty(),
            providerEdited = true, accountEdited = account != null,
            kind = kind ?: if (account == null) AccountAddContentKind.NONE else AccountAddContentKind.TOTP)
    }

    fun dismiss() { generation++; _formState.value = AccountAddFormState() }

    fun selectAccount(account: AccountUi?) {
        val form = _formState.value
        if (!form.visible || form.submitting || (account != null && account.providerName != form.provider)) return
        _formState.value = form.copy(selectedAccountId = account?.id, accountName = account?.accountName.orEmpty(),
            kind = if (account != null && form.kind == AccountAddContentKind.NONE) AccountAddContentKind.TOTP else form.kind,
            accountEdited = true, error = null)
    }

    fun onProviderChange(value: String) = update { form ->
        if (form.scope != AccountAddScope.VAULT) form else form.copy(provider = value, providerEdited = true,
            selectedAccountId = null, accountName = if (form.selectedAccountId == null) form.accountName else "",
            expectsExistingProvider = (form.provider.trim() == value.trim() && form.expectsExistingProvider) || accounts.any { it.providerName == value.trim() })
    }
    fun onAccountNameChange(value: String) = update { it.copy(accountName = value, selectedAccountId = null, accountEdited = true) }
    fun selectKind(value: AccountAddContentKind) = update {
        if (value == AccountAddContentKind.NONE && it.selectedAccountId != null) it else it.copy(kind = value)
    }
    fun updateTotp(change: (AddTotpFormState) -> AddTotpFormState) = update { form ->
        val next = change(form.totp)
        val updated = form.copy(totp = next)
        if (next.mode == AddMode.PASTE && next.uri != form.totp.uri) {
            val parsed = try { OtpauthParser.parse(next.uri) } catch (_: Exception) { null }
            if (parsed != null) fillLabels(updated, parsed) else updated
        } else updated
    }
    fun onRecoveryTitleChange(value: String) = update { it.copy(recovery = it.recovery.copy(title = value)) }
    fun onRecoveryValuesChange(value: String) = update { it.copy(recovery = it.recovery.copy(valuesText = value)) }
    private fun update(change: (AccountAddFormState) -> AccountAddFormState) = _formState.update {
        if (!it.visible || it.submitting || sessionState.value != SecureSessionStateMachine.State.UNLOCKED) it else change(it).copy(error = null)
    }

    /** Single otpauth QR fills this draft; bulk migration remains in the separate import adapter. */
    fun onScanned(raw: String) {
        if (!_formState.value.visible || sessionState.value != SecureSessionStateMachine.State.UNLOCKED) return
        try {
            onParsedTotp(OtpauthParser.parse(raw))
        } catch (_: Exception) {
            _formState.update { if (it.visible && sessionState.value == SecureSessionStateMachine.State.UNLOCKED) it.copy(error = "invalid_uri") else it }
        }
    }

    fun onParsedTotp(parsed: ParsedTotp) = update { form ->
        fillLabels(form, parsed).copy(totp = form.totp.copy(mode = AddMode.MANUAL, secret = parsed.secretBase32,
            algorithm = parsed.algorithm, digits = parsed.digits, periodSeconds = parsed.periodSeconds))
    }

    private fun fillLabels(form: AccountAddFormState, parsed: ParsedTotp): AccountAddFormState {
        val root = form.scope == AccountAddScope.VAULT
        val provider = if (root && !form.providerEdited) parsed.issuer ?: "Unknown" else form.provider
        val name = if (root && !form.accountEdited) parsed.accountName ?: provider
            else form.accountName.ifBlank { if (!form.accountEdited) parsed.accountName.orEmpty() else "" }
        return form.copy(provider = provider, accountName = name,
            expectsExistingProvider = !root || (provider.trim() == form.provider.trim() && form.expectsExistingProvider) || accounts.any { it.providerName == provider.trim() })
    }

    suspend fun submit(): Boolean {
        val form = _formState.value
        if (!form.visible || form.submitting || sessionState.value != SecureSessionStateMachine.State.UNLOCKED) return false
        val repo = repositoryProvider() ?: return false
        if (!_formState.compareAndSet(form, form.copy(submitting = true, error = null))) return false
        val request = generation
        fun fail(code: String): Boolean {
            if (generation == request) _formState.update { it.copy(error = code) }
            return false
        }
        return try {
            if (form.provider.isBlank()) return fail("provider_required")
            if (form.accountName.isBlank()) return fail("account_required")
            val content = when (form.kind) {
                AccountAddContentKind.NONE -> AccountAdditionContent.Empty
                AccountAddContentKind.TOTP -> {
                    val input = if (form.totp.mode == AddMode.PASTE) {
                        val parsed = try { OtpauthParser.parse(form.totp.uri) } catch (_: Exception) { return fail("invalid_uri") }
                        AccountAdditionContent.Totp(parsed.secretBase32, parsed.algorithm, parsed.digits, parsed.periodSeconds)
                    } else AccountAdditionContent.Totp(TotpCore.normalizeSecret(form.totp.secret), form.totp.algorithm,
                        form.totp.digits, form.totp.periodSeconds)
                    if (input.secret.isEmpty() || !TotpCore.isValidBase32(input.secret)) return fail("invalid_secret")
                    input
                }
                AccountAddContentKind.RECOVERY -> {
                    val values = form.recovery.parsedValues()
                    if (values.isEmpty()) return fail("empty_codes")
                    if (values.toSet().size != values.size) return fail("duplicate_codes")
                    AccountAdditionContent.Recovery(values, form.recovery.title, defaultRecoveryTitleProvider())
                }
            }
            repo.saveAccountAddition(form.provider, form.accountName, form.selectedAccountId, content,
                allowNewProvider = form.scope == AccountAddScope.VAULT && !form.expectsExistingProvider,
                reuseMatchingAccount = form.scope == AccountAddScope.VAULT)
            if (generation != request || sessionState.value != SecureSessionStateMachine.State.UNLOCKED) false
            else { dismiss(); true }
        } catch (e: CancellationException) {
            throw e
        } catch (_: ProviderAccountRepository.ConflictException) {
            fail("duplicate_account")
        } catch (_: ProviderAccountRepository.NotFoundException) {
            fail("target_unavailable")
        } catch (_: Exception) {
            fail("save_failed")
        } finally {
            if (generation == request) _formState.update { it.copy(submitting = false) }
        }
    }
}
