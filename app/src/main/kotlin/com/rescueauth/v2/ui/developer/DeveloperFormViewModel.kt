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
 * Developer entry editor state (Phase 4 P4/P6).
 *
 * A single editor handles all five Developer types (API Credential / SSH Key /
 * Generic Secret / Android Signing Key / Environment Variable Set) with
 * per-type fields. Form secrets (apiKey / apiSecret / privateKey / passphrase /
 * storePassword / keyPassword / env variable values / generic field values /
 * keystore import buffer) are held in the in-memory form only and are never
 * persisted except through the repository (which stores them inside the
 * encrypted SQLCipher Vault payload JSON).
 *
 * ## Android Signing Key keystore buffer (Phase 4 P6 §3/§16)
 *
 * The selected keystore bytes live only in [keystoreBytes] (in-memory). They
 * never enter navigation routes / SavedStateHandle / Bundle / DataStore /
 * logs / clipboard. On cancel / successful save / replace the buffer is
 * released (set to null). Process recreation does NOT restore it — the user
 * re-chooses the file.
 *
 * ## Validation (Issue #20 §20 / P6 §10)
 *
 * Minimum required fields per type; secrets are opaque values — no vendor
 * format guessing, no crypto parsing, no smart normalization. Errors never
 * echo a secret value.
 */
enum class DeveloperFormType {
    API_CREDENTIAL,
    SSH_KEY,
    GENERIC_SECRET,
    ANDROID_SIGNING_KEY,
    ENVIRONMENT_VARIABLE_SET,
}

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
    // Android Signing Key
    val projectName: String = "",
    val packageName: String = "",
    val keystoreFileName: String = "",
    val storePassword: String = "",
    val keyAlias: String = "",
    val keyPassword: String = "",
    /** Selected keystore raw bytes (in-memory only, released after save/cancel). */
    val keystoreBytes: ByteArray? = null,
    // Environment Variable Set
    val variables: List<Pair<String, String>> = listOf(Pair("", "")),
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val isEditing: Boolean get() = editingStableId != null

    fun trimmedTitle(): String = title.trim()
    fun trimmedService(): String = serviceName.trim()
    fun trimmedAccount(): String = accountName.trim()
    fun trimmedKeyName(): String = keyName.trim()
    fun trimmedProjectName(): String = projectName.trim()
    fun trimmedPackageName(): String = packageName.trim()
    fun trimmedKeyAlias(): String = keyAlias.trim()
    fun trimmedKeystoreFileName(): String = keystoreFileName.trim()

    fun parsedFields(): List<VaultKeyValue> =
        fields.map { (k, v) -> VaultKeyValue(k.trim(), v) }
            .filter { it.key.isNotEmpty() && it.value.isNotEmpty() }

    fun parsedVariables(): List<VaultKeyValue> =
        variables.map { (k, v) -> VaultKeyValue(k.trim(), v) }
            .filter { it.key.isNotEmpty() }
}

/** One-shot events for the Developer editor screen. */
sealed interface DeveloperFormEvent {
    data class Saved(val label: String) : DeveloperFormEvent
    data class Error(val message: String) : DeveloperFormEvent
}

/**
 * ViewModel for the Developer entry create/edit screen (Phase 4 P4/P6).
 *
 * - Create mints a new stableId; edit preserves the existing stableId
 *   (Issue #20 §18). Non-secret metadata edits need no fresh re-auth
 *   (Issue #20 §16); secret values are saved as part of the entry.
 * - For Android Signing Key, edit / replace-keystore keeps the SAME stableId
 *   (only the payload changes — Issue #20 P6 §4).
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
    fun onProjectNameChange(value: String) = _formState.update { it.copy(projectName = value, error = null) }
    fun onPackageNameChange(value: String) = _formState.update { it.copy(packageName = value, error = null) }
    fun onStorePasswordChange(value: String) = _formState.update { it.copy(storePassword = value, error = null) }
    fun onKeyAliasChange(value: String) = _formState.update { it.copy(keyAlias = value, error = null) }
    fun onKeyPasswordChange(value: String) = _formState.update { it.copy(keyPassword = value, error = null) }

    /**
     * Sets the selected keystore file metadata + bytes (Phase 4 P6 §3/§16).
     * Releasing the previous buffer before replacing is handled by the caller
     * (the route clears it via [clearKeystoreBuffer] when choosing another file).
     */
    fun onKeystoreSelected(fileName: String, bytes: ByteArray) {
        _formState.update {
            it.copy(
                keystoreFileName = fileName,
                keystoreBytes = bytes,
                error = null,
            )
        }
    }

    /** Releases the in-memory keystore buffer (choose another file / cancel). */
    fun clearKeystoreBuffer() {
        _formState.update { it.copy(keystoreBytes = null) }
    }

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

    // --- Environment Variable Set dynamic rows (Phase 4 P6 §9) ---

    fun onVariableNameChange(index: Int, value: String) = _formState.update { s ->
        val vars = s.variables.toMutableList()
        if (index in vars.indices) vars[index] = vars[index].copy(first = value)
        s.copy(variables = vars, error = null)
    }

    fun onVariableValueChange(index: Int, value: String) = _formState.update { s ->
        val vars = s.variables.toMutableList()
        if (index in vars.indices) vars[index] = vars[index].copy(second = value)
        s.copy(variables = vars, error = null)
    }

    fun addVariable() = _formState.update { s ->
        if (s.variables.size >= DeveloperRepository.MAX_FIELDS) s
        else s.copy(variables = s.variables + Pair("", ""))
    }

    fun removeVariable(index: Int) = _formState.update { s ->
        val vars = s.variables.toMutableList()
        if (vars.size > 1 && index in vars.indices) vars.removeAt(index)
        s.copy(variables = vars)
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
            is com.rescueauth.v2.export.VaultAndroidSigningKey -> _formState.value = DeveloperFormState(
                editingStableId = stableId,
                type = DeveloperFormType.ANDROID_SIGNING_KEY,
                title = entry.title,
                notes = entry.notes.orEmpty(),
                projectName = entry.projectName,
                packageName = entry.packageName,
                keystoreFileName = entry.keystoreFileName,
                storePassword = entry.storePassword,
                keyAlias = entry.keyAlias,
                keyPassword = entry.keyPassword,
                // Keystore bytes are NOT restored on edit — the existing stored
                // binary stays unless the user replaces it (Issue #20 P6 §4/§16).
                keystoreBytes = null,
            )
            is com.rescueauth.v2.export.VaultEnvironmentVariableSet -> _formState.value = DeveloperFormState(
                editingStableId = stableId,
                type = DeveloperFormType.ENVIRONMENT_VARIABLE_SET,
                title = entry.title,
                notes = entry.notes.orEmpty(),
                projectName = entry.projectName,
                variables = entry.variables.map { Pair(it.key, it.value) }
                    .ifEmpty { listOf(Pair("", "")) },
            )
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
                DeveloperFormType.ANDROID_SIGNING_KEY -> {
                    val keystore = form.keystoreBytes
                        ?: throw DeveloperRepository.ValidationException("keystore file is required")
                    if (form.isEditing) {
                        repo.editAndroidSigningKey(
                            stableId = form.editingStableId!!,
                            title = form.trimmedTitle(),
                            notes = form.notes,
                            projectName = form.trimmedProjectName(),
                            packageName = form.trimmedPackageName(),
                            keystoreFileName = form.trimmedKeystoreFileName(),
                            keystoreBytes = keystore,
                            storePassword = form.storePassword,
                            keyAlias = form.trimmedKeyAlias(),
                            keyPassword = form.keyPassword,
                        )
                    } else {
                        repo.createAndroidSigningKey(
                            title = form.trimmedTitle(),
                            notes = form.notes,
                            projectName = form.trimmedProjectName(),
                            packageName = form.trimmedPackageName(),
                            keystoreFileName = form.trimmedKeystoreFileName(),
                            keystoreBytes = keystore,
                            storePassword = form.storePassword,
                            keyAlias = form.trimmedKeyAlias(),
                            keyPassword = form.keyPassword,
                        )
                    }
                }
                DeveloperFormType.ENVIRONMENT_VARIABLE_SET -> {
                    val variables = form.parsedVariables()
                    if (form.isEditing) {
                        repo.editEnvironmentVariableSet(
                            stableId = form.editingStableId!!,
                            title = form.trimmedTitle(),
                            notes = form.notes,
                            projectName = form.trimmedProjectName(),
                            variables = variables,
                        )
                    } else {
                        repo.createEnvironmentVariableSet(
                            title = form.trimmedTitle(),
                            notes = form.notes,
                            projectName = form.trimmedProjectName(),
                            variables = variables,
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
            DeveloperFormType.ANDROID_SIGNING_KEY -> {
                when {
                    form.keystoreBytes == null -> "keystore_required"
                    form.trimmedKeystoreFileName().isEmpty() -> "keystore_required"
                    form.storePassword.isEmpty() -> "store_password_required"
                    form.trimmedKeyAlias().isEmpty() -> "key_alias_required"
                    form.keyPassword.isEmpty() -> "key_password_required"
                    else -> null
                }
            }
            DeveloperFormType.ENVIRONMENT_VARIABLE_SET -> {
                val variables = form.parsedVariables()
                when {
                    variables.isEmpty() -> "variable_required"
                    variables.any { it.key.isBlank() } -> "variable_name_required"
                    else -> null
                }
            }
        }
    }
}
