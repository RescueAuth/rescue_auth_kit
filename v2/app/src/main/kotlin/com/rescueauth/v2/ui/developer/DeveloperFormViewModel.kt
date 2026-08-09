package com.rescueauth.v2.ui.developer

import com.rescueauth.v2.export.VaultKeyValue
import com.rescueauth.v2.repository.DeveloperRepository
import com.rescueauth.v2.session.SecureSessionStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Developer entry editor state (Phase 4 P4).
 *
 * A single editor handles the three P4 types (API Credential / SSH Key /
 * Generic Secret) with per-type fields. Form secrets (apiKey / apiSecret /
 * privateKey / passphrase / generic field values) are held in the in-memory
 * form only and are never persisted except through the repository (which
 * stores them inside the encrypted SQLCipher Vault payload JSON).
 *
 * ## Validation (Issue #20 §20)
 *
 * Minimum required fields per type; secrets are opaque values — no vendor
 * format guessing, no crypto parsing, no smart normalization. Errors never
 * echo a secret value.
 */
enum class DeveloperFormType { API_CREDENTIAL, SSH_KEY, GENERIC_SECRET }

data class DeveloperFormState(
    val editingStableId: String? = null,
    val type: DeveloperFormType = DeveloperFormType.API_CREDENTIAL,
    val title: String = "",
    val notes: String = "",
    // API Credential
    val serviceName: String = "",
    val accountName: String = "",
    val apiKey: String = "",
    val apiSecret: String = "",
    // SSH Key
    val keyName: String = "",
    val publicKey: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
    // Generic Secret
    val fields: List<Pair<String, String>> = listOf(Pair("", "")),
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val isEditing: Boolean get() = editingStableId != null

    fun trimmedTitle(): String = title.trim()
    fun trimmedService(): String = serviceName.trim()
    fun trimmedAccount(): String = accountName.trim()
    fun trimmedKeyName(): String = keyName.trim()

    fun parsedFields(): List<VaultKeyValue> =
        fields.map { (k, v) -> VaultKeyValue(k.trim(), v) }
            .filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
}

/** One-shot events for the Developer editor screen. */
sealed interface DeveloperFormEvent {
    data class Saved(val label: String) : DeveloperFormEvent
    data class Error(val message: String) : DeveloperFormEvent
}

/**
 * ViewModel for the Developer entry create/edit screen (Phase 4 P4).
 *
 * - Create mints a new stableId; edit preserves the existing stableId
 *   (Issue #20 §18). Non-secret metadata edits need no fresh re-auth
 *   (Issue #20 §16); secret values are saved as part of the entry.
 */
class DeveloperFormViewModel(
    private val developerRepositoryProvider: () -> DeveloperRepository?,
    sessionState: StateFlow<SecureSessionStateMachine.State>,
    private val scope: CoroutineScope,
) {

    private val _formState = MutableStateFlow(DeveloperFormState())
    val formState: StateFlow<DeveloperFormState> = _formState.asStateFlow()

    private val _events = MutableStateFlow<DeveloperFormEvent?>(null)
    val events: StateFlow<DeveloperFormEvent?> = _events.asStateFlow()

    fun onEventShown() {
        _events.value = null
    }

    // ------------------------------------------------------------------
    // Form controls
    // ------------------------------------------------------------------

    fun beginCreate(type: DeveloperFormType) {
        _formState.value = DeveloperFormState(type = type)
    }

    fun onTitleChange(value: String) = _formState.update { it.copy(title = value, error = null) }
    fun onNotesChange(value: String) = _formState.update { it.copy(notes = value, error = null) }
    fun onServiceNameChange(value: String) = _formState.update { it.copy(serviceName = value, error = null) }
    fun onAccountNameChange(value: String) = _formState.update { it.copy(accountName = value, error = null) }
    fun onApiKeyChange(value: String) = _formState.update { it.copy(apiKey = value, error = null) }
    fun onApiSecretChange(value: String) = _formState.update { it.copy(apiSecret = value, error = null) }
    fun onKeyNameChange(value: String) = _formState.update { it.copy(keyName = value, error = null) }
    fun onPublicKeyChange(value: String) = _formState.update { it.copy(publicKey = value, error = null) }
    fun onPrivateKeyChange(value: String) = _formState.update { it.copy(privateKey = value, error = null) }
    fun onPassphraseChange(value: String) = _formState.update { it.copy(passphrase = value, error = null) }

    fun onFieldLabelChange(index: Int, value: String) = _formState.update { s ->
        val fields = s.fields.toMutableList()
        if (index in fields.indices) fields[index] = fields[index].copy(first = value)
        s.copy(fields = fields, error = null)
    }

    fun onFieldValueChange(index: Int, value: String) = _formState.update { s ->
        val fields = s.fields.toMutableList()
        if (index in fields.indices) fields[index] = fields[index].copy(second = value)
        s.copy(fields = fields, error = null)
    }

    fun addField() = _formState.update { s ->
        if (s.fields.size >= DeveloperRepository.MAX_FIELDS) s
        else s.copy(fields = s.fields + Pair("", ""))
    }

    fun removeField(index: Int) = _formState.update { s ->
        val fields = s.fields.toMutableList()
        if (fields.size > 1 && index in fields.indices) fields.removeAt(index)
        s.copy(fields = fields)
    }

    fun dismiss() {
        _formState.value = DeveloperFormState()
    }

    /** Pre-fills the form from an existing entry for edit (preserves stableId). */
    suspend fun beginEdit(stableId: String) {
        val repo = developerRepositoryProvider() ?: return
        val entry = repo.getByStableId(stableId) ?: return
        when (entry) {
            is com.rescueauth.v2.export.VaultApiCredential -> _formState.value = DeveloperFormState(
                editingStableId = stableId,
                type = DeveloperFormType.API_CREDENTIAL,
                title = entry.title,
                notes = entry.notes.orEmpty(),
                serviceName = entry.serviceName,
                accountName = entry.accountName,
                apiKey = entry.apiKey,
                apiSecret = entry.apiSecret,
            )
            is com.rescueauth.v2.export.VaultSshKey -> _formState.value = DeveloperFormState(
                editingStableId = stableId,
                type = DeveloperFormType.SSH_KEY,
                title = entry.title,
                notes = entry.notes.orEmpty(),
                keyName = entry.keyName,
                publicKey = entry.publicKey,
                privateKey = entry.privateKey,
                passphrase = entry.passphrase,
            )
            is com.rescueauth.v2.export.VaultGenericSecret -> _formState.value = DeveloperFormState(
                editingStableId = stableId,
                type = DeveloperFormType.GENERIC_SECRET,
                title = entry.title,
                notes = entry.notes.orEmpty(),
                fields = entry.fields.map { Pair(it.key, it.value) }
                    .ifEmpty { listOf(Pair("", "")) },
            )
            else -> Unit // P6 types are not editable in P4
        }
    }

    // ------------------------------------------------------------------
    // Save (create / edit)
    // ------------------------------------------------------------------

    /** Validates and saves the form. Returns true on success. */
    suspend fun submit(): Boolean {
        val form = _formState.value
        if (form.submitting) return false
        val repo = developerRepositoryProvider() ?: return false

        // Basic required-field validation (no secret echoed in errors).
        val validationError = validate(form)
        if (validationError != null) {
            _formState.update { it.copy(error = validationError) }
            return false
        }

        _formState.update { it.copy(submitting = true, error = null) }
        return try {
            when (form.type) {
                DeveloperFormType.API_CREDENTIAL -> {
                    if (form.isEditing) {
                        repo.editApiCredential(
                            stableId = form.editingStableId!!,
                            title = form.trimmedTitle(),
                            notes = form.notes,
                            serviceName = form.trimmedService(),
                            accountName = form.trimmedAccount(),
                            apiKey = form.apiKey,
                            apiSecret = form.apiSecret,
                        )
                    } else {
                        repo.createApiCredential(
                            title = form.trimmedTitle(),
                            notes = form.notes,
                            serviceName = form.trimmedService(),
                            accountName = form.trimmedAccount(),
                            apiKey = form.apiKey,
                            apiSecret = form.apiSecret,
                        )
                    }
                }
                DeveloperFormType.SSH_KEY -> {
                    if (form.isEditing) {
                        repo.editSshKey(
                            stableId = form.editingStableId!!,
                            title = form.trimmedTitle(),
                            notes = form.notes,
                            keyName = form.trimmedKeyName(),
                            publicKey = form.publicKey,
                            privateKey = form.privateKey,
                            passphrase = form.passphrase,
                        )
                    } else {
                        repo.createSshKey(
                            title = form.trimmedTitle(),
                            notes = form.notes,
                            keyName = form.trimmedKeyName(),
                            publicKey = form.publicKey,
                            privateKey = form.privateKey,
                            passphrase = form.passphrase,
                        )
                    }
                }
                DeveloperFormType.GENERIC_SECRET -> {
                    val fields = form.parsedFields()
                    if (form.isEditing) {
                        repo.editGenericSecret(
                            stableId = form.editingStableId!!,
                            title = form.trimmedTitle(),
                            notes = form.notes,
                            fields = fields,
                        )
                    } else {
                        repo.createGenericSecret(
                            title = form.trimmedTitle(),
                            notes = form.notes,
                            fields = fields,
                        )
                    }
                }
            }
            _formState.value = DeveloperFormState()
            _events.value = DeveloperFormEvent.Saved(form.trimmedTitle().ifEmpty { "entry" })
            true
        } catch (e: DeveloperRepository.ValidationException) {
            _formState.update { it.copy(error = "invalid") }
            _events.value = DeveloperFormEvent.Error(e.message ?: "invalid")
            false
        } catch (e: DeveloperRepository.NotFoundException) {
            _formState.update { it.copy(error = "not_found") }
            _events.value = DeveloperFormEvent.Error("not_found")
            false
        } catch (e: Exception) {
            _formState.update { it.copy(error = "unexpected") }
            _events.value = DeveloperFormEvent.Error("unexpected")
            false
        } finally {
            _formState.update { it.copy(submitting = false) }
        }
    }

    private fun validate(form: DeveloperFormState): String? {
        if (form.trimmedTitle().isEmpty()) return "title_required"
        return when (form.type) {
            DeveloperFormType.API_CREDENTIAL -> {
                when {
                    form.trimmedService().isEmpty() -> "service_required"
                    form.trimmedAccount().isEmpty() -> "account_required"
                    form.apiKey.isEmpty() -> "api_key_required"
                    form.apiSecret.isEmpty() -> "api_secret_required"
                    else -> null
                }
            }
            DeveloperFormType.SSH_KEY -> {
                if (form.privateKey.isEmpty()) "private_key_required" else null
            }
            DeveloperFormType.GENERIC_SECRET -> {
                val fields = form.parsedFields()
                when {
                    fields.isEmpty() -> "field_required"
                    fields.any { it.key.isBlank() } -> "field_label_required"
                    else -> null
                }
            }
        }
    }
}
