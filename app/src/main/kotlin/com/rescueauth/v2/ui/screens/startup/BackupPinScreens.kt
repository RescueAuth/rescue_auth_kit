package com.rescueauth.v2.ui.screens.startup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.exportimport.PinPolicy
import com.rescueauth.v2.security.BackupPinReauthDialogState
import com.rescueauth.v2.ui.components.DestructiveConfirmationDialog
import com.rescueauth.v2.ui.components.RescueAuthActionBar
import com.rescueauth.v2.ui.components.RescueAuthBackButton
import com.rescueauth.v2.ui.components.RescueAuthButton
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthExplainedCard
import com.rescueauth.v2.ui.components.RescueAuthFormPage
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthIconHeader
import com.rescueauth.v2.ui.components.RescueAuthOutlinedButton
import com.rescueauth.v2.ui.components.RescueAuthRowCard
import com.rescueauth.v2.ui.components.RescueAuthSheet
import com.rescueauth.v2.ui.components.RescueAuthTextField
import com.rescueauth.v2.ui.components.RescueAuthVisibilityToggle
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.ScreenTokens
import com.rescueauth.v2.ui.theme.Spacing
import kotlinx.coroutines.delay

object BackupPinTestTags {
    const val SETUP_SCREEN = "backup_pin_setup_screen"
    const val SETUP_PIN_INPUT = "backup_pin_setup_input"
    const val SETUP_CONFIRM_INPUT = "backup_pin_setup_confirm_input"
    const val UNLOCK_SCREEN = "backup_pin_unlock_screen"
    const val UNLOCK_PIN_INPUT = "backup_pin_unlock_input"
    const val UNLOCK_SUBMIT = "backup_pin_unlock_submit"
    const val UNLOCK_SWITCH_SYSTEM = "backup_pin_switch_system"
    const val UNLOCK_RESET_VAULT = "backup_pin_reset_vault"
    const val REAUTH_SHEET = "backup_pin_reauth_sheet"
    const val REAUTH_INPUT = "backup_pin_reauth_input"
    const val MANAGE_SHEET = "backup_pin_manage_sheet"
    const val MANAGE_PIN_INPUT = "backup_pin_manage_input"
    const val MANAGE_CONFIRM_INPUT = "backup_pin_manage_confirm_input"
    const val MANAGE_REMOVE_ROW = "backup_pin_manage_remove"
}

/**
 * First-run Backup PIN creation screen used on devices without a system screen lock.
 */
@Composable
fun StartupBackupPinSetupScreen(
    submitting: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onCreateWithPin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmPin by rememberSaveable { mutableStateOf("") }
    var showPin by rememberSaveable { mutableStateOf(false) }
    var showConfirm by rememberSaveable { mutableStateOf(false) }
    var attemptedSubmit by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = !submitting, onBack = onBack)

    val pinValidation = PinPolicy.validateNewPin(pin)
    val mismatch = confirmPin.isNotEmpty() && pin != confirmPin
    val inlineError = when {
        errorMessage != null -> errorMessage
        !attemptedSubmit && pin.isEmpty() -> null
        pinValidation is PinPolicy.ValidationResult.Invalid && (attemptedSubmit || pin.isNotEmpty()) ->
            pinIssueMessage(pinValidation.reason)
        mismatch -> stringResource(R.string.pin_mismatch_error)
        attemptedSubmit && confirmPin != pin -> stringResource(R.string.pin_mismatch_error)
        else -> null
    }
    val canSubmit = !submitting && pinValidation is PinPolicy.ValidationResult.Valid && pin == confirmPin

    RescueAuthFormPage(
        modifier = modifier.fillMaxSize().testTag(BackupPinTestTags.SETUP_SCREEN),
        title = stringResource(R.string.backup_pin_setup_title),
        subtitle = stringResource(R.string.backup_pin_setup_subtitle),
        navigationIcon = { RescueAuthBackButton(onBack) },
        bottomBar = {
            RescueAuthActionBar(
                secondaryLabel = stringResource(R.string.common_back),
                secondaryIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onSecondaryClick = onBack,
                secondaryEnabled = !submitting,
                primaryLabel = stringResource(if (submitting) R.string.common_working else R.string.backup_pin_setup_confirm_action),
                primaryIcon = Icons.Filled.Check,
                onPrimaryClick = {
                    attemptedSubmit = true
                    if (canSubmit) onCreateWithPin(pin)
                },
                primaryEnabled = !submitting && pin.isNotEmpty() && confirmPin.isNotEmpty(),
            )
        },
    ) {
        RescueAuthExplainedCard(
            identityKey = "backup_pin_setup",
            title = stringResource(R.string.backup_pin_setup_title),
            explanation = stringResource(R.string.backup_pin_setup_policy),
            icon = Icons.Outlined.Pin,
            testTagPrefix = "backup_pin_setup_help",
        ) {
            RescueAuthTextField(
                value = pin,
                onValueChange = { next -> if (next.all { it.isDigit() }) pin = next },
                enabled = !submitting,
                singleLine = true,
                label = { Text(stringResource(R.string.pin_label)) },
                visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Next,
                ),
                trailingIcon = {
                    RescueAuthVisibilityToggle(
                        revealed = showPin,
                        onClick = { showPin = !showPin },
                        modifier = Modifier.testTag("pin_visibility"),
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag(BackupPinTestTags.SETUP_PIN_INPUT),
            )
            RescueAuthTextField(
                value = confirmPin,
                onValueChange = { next -> if (next.all { it.isDigit() }) confirmPin = next },
                enabled = !submitting,
                singleLine = true,
                isError = inlineError != null,
                label = { Text(stringResource(R.string.pin_confirm_label)) },
                visualTransformation = if (showConfirm) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    attemptedSubmit = true
                    if (canSubmit) onCreateWithPin(pin)
                }),
                trailingIcon = {
                    RescueAuthVisibilityToggle(
                        revealed = showConfirm,
                        onClick = { showConfirm = !showConfirm },
                        modifier = Modifier.testTag("pin_confirm_visibility"),
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag(BackupPinTestTags.SETUP_CONFIRM_INPUT),
            )
            if (inlineError != null) {
                Text(
                    text = inlineError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Backup PIN unlock surface used both at startup and over a relocked app shell.
 */
@Composable
fun StartupBackupPinUnlockScreen(
    submitting: Boolean,
    wrongPin: Boolean,
    cooldownSeconds: Int,
    showFallbackNotice: Boolean,
    canSwitchToSystemAuth: Boolean,
    onSubmitPin: (String) -> Unit,
    onTickCooldown: () -> Unit,
    onBack: () -> Unit,
    onSwitchToSystemAuth: (() -> Unit)? = null,
    onResetVault: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var showPin by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    BackHandler(enabled = !submitting, onBack = onBack)

    LaunchedEffect(cooldownSeconds) {
        if (cooldownSeconds > 0) {
            delay(1_000L)
            onTickCooldown()
        }
    }
    LaunchedEffect(cooldownSeconds == 0) {
        if (cooldownSeconds == 0) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    val statusError = when {
        cooldownSeconds > 0 -> stringResource(R.string.backup_pin_cooldown_message, cooldownSeconds)
        wrongPin -> stringResource(R.string.backup_pin_wrong_error)
        else -> null
    }
    val canSubmit = !submitting && cooldownSeconds == 0 && pin.length >= PinPolicy.MIN_PIN_LENGTH

    Surface(
        modifier = modifier.fillMaxSize().testTag(BackupPinTestTags.UNLOCK_SCREEN),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                RescueAuthCard {
                    RescueAuthIconHeader(
                        icon = Icons.Filled.Lock,
                        title = stringResource(R.string.backup_pin_unlock_title),
                        description = if (showFallbackNotice) {
                            stringResource(R.string.backup_pin_unlock_fallback_notice)
                        } else {
                            stringResource(R.string.backup_pin_unlock_subtitle)
                        },
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(CardTokens.formFieldSpacing),
                    ) {
                        RescueAuthTextField(
                            value = pin,
                            onValueChange = { next -> if (next.all { it.isDigit() }) pin = next },
                            enabled = !submitting && cooldownSeconds == 0,
                            singleLine = true,
                            isError = statusError != null,
                            label = { Text(stringResource(R.string.pin_label)) },
                            visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                if (canSubmit) {
                                    val submitted = pin
                                    pin = ""
                                    onSubmitPin(submitted)
                                }
                            }),
                            trailingIcon = {
                                RescueAuthVisibilityToggle(
                                    revealed = showPin,
                                    onClick = { showPin = !showPin },
                                    modifier = Modifier.testTag("pin_visibility"),
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .testTag(BackupPinTestTags.UNLOCK_PIN_INPUT),
                        )
                        if (statusError != null) {
                            Text(
                                text = statusError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        RescueAuthButton(
                            onClick = {
                                val submitted = pin
                                pin = ""
                                onSubmitPin(submitted)
                            },
                            enabled = canSubmit,
                            modifier = Modifier.fillMaxWidth().testTag(BackupPinTestTags.UNLOCK_SUBMIT),
                        ) {
                            Text(stringResource(if (submitting) R.string.common_working else R.string.backup_pin_unlock_button))
                        }
                        if (canSwitchToSystemAuth && onSwitchToSystemAuth != null) {
                            RescueAuthOutlinedButton(
                                onClick = onSwitchToSystemAuth,
                                enabled = !submitting,
                                modifier = Modifier.fillMaxWidth().testTag(BackupPinTestTags.UNLOCK_SWITCH_SYSTEM),
                            ) {
                                Text(stringResource(R.string.startup_unlock_with_system))
                            }
                        }
                    }
                }
                if (onResetVault != null) {
                    TextButton(
                        onClick = { confirmReset = true },
                        enabled = !submitting,
                        modifier = Modifier.align(Alignment.CenterHorizontally).testTag(BackupPinTestTags.UNLOCK_RESET_VAULT),
                    ) {
                        Text(
                            text = stringResource(R.string.startup_reset_vault_action),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (confirmReset && onResetVault != null) {
        DestructiveConfirmationDialog(
            title = stringResource(R.string.startup_reset_vault_title),
            message = stringResource(R.string.startup_reset_vault_message),
            confirmLabel = stringResource(R.string.startup_reset_vault_confirm),
            onConfirm = {
                confirmReset = false
                onResetVault()
            },
            onDismiss = { confirmReset = false },
        )
    }
}

/**
 * Modal sheet shown when a sensitive action falls back to Backup PIN verification (ADR-0015 §4).
 */
@Composable
fun BackupPinReauthSheet(
    state: BackupPinReauthDialogState,
    onSubmitPin: (String) -> Unit,
    onTickCooldown: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var showPin by remember { mutableStateOf(false) }

    LaunchedEffect(state.cooldownSeconds) {
        if (state.cooldownSeconds > 0) {
            delay(1_000L)
            onTickCooldown()
        }
    }

    val errorText = when {
        state.cooldownSeconds > 0 -> stringResource(R.string.backup_pin_cooldown_message, state.cooldownSeconds)
        state.wrongPin -> stringResource(R.string.backup_pin_wrong_error)
        else -> null
    }
    val canVerify = !state.verifying && state.cooldownSeconds == 0 && pin.isNotEmpty()

    RescueAuthSheet(
        title = stringResource(R.string.backup_pin_reauth_title),
        subtitle = stringResource(R.string.backup_pin_reauth_subtitle),
        icon = Icons.Outlined.Pin,
        onDismiss = onDismiss,
        dismissEnabled = !state.verifying,
        modifier = Modifier.testTag(BackupPinTestTags.REAUTH_SHEET),
        footer = { dismiss ->
            RescueAuthActionBar(
                secondaryLabel = stringResource(R.string.common_cancel),
                secondaryIcon = Icons.Filled.Close,
                onSecondaryClick = dismiss,
                secondaryEnabled = !state.verifying,
                primaryLabel = stringResource(if (state.verifying) R.string.common_working else R.string.backup_pin_verify_action),
                primaryIcon = Icons.Filled.Check,
                onPrimaryClick = {
                    val submitted = pin
                    pin = ""
                    onSubmitPin(submitted)
                },
                primaryEnabled = canVerify,
            )
        },
    ) {
        RescueAuthCard {
            Column(verticalArrangement = Arrangement.spacedBy(CardTokens.formFieldSpacing)) {
                RescueAuthTextField(
                    value = pin,
                    onValueChange = { next -> if (next.all { it.isDigit() }) pin = next },
                    enabled = !state.verifying && state.cooldownSeconds == 0,
                    singleLine = true,
                    isError = errorText != null,
                    label = { Text(stringResource(R.string.pin_label)) },
                    visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        if (canVerify) {
                            val submitted = pin
                            pin = ""
                            onSubmitPin(submitted)
                        }
                    }),
                    trailingIcon = {
                        RescueAuthVisibilityToggle(
                            revealed = showPin,
                            onClick = { showPin = !showPin },
                            modifier = Modifier.testTag("pin_visibility"),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag(BackupPinTestTags.REAUTH_INPUT),
                )
                if (errorText != null) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Modal sheet in Settings → Security to add, rotate, or (when system unlock is active) remove the Backup PIN.
 */
@Composable
fun BackupPinManageSheet(
    hasExistingPin: Boolean,
    canRemovePin: Boolean,
    submitting: Boolean,
    onSavePin: (String, onDone: () -> Unit) -> Unit,
    onRemovePin: (onDone: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmPin by rememberSaveable { mutableStateOf("") }
    var showPin by rememberSaveable { mutableStateOf(false) }
    var showConfirm by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    val validation = PinPolicy.validateNewPin(pin)
    val errorText = when {
        !attempted && pin.isEmpty() -> null
        validation is PinPolicy.ValidationResult.Invalid && (attempted || pin.isNotEmpty()) ->
            pinIssueMessage(validation.reason)
        confirmPin.isNotEmpty() && confirmPin != pin -> stringResource(R.string.pin_mismatch_error)
        attempted && confirmPin != pin -> stringResource(R.string.pin_mismatch_error)
        else -> null
    }
    val valid = validation is PinPolicy.ValidationResult.Valid && pin == confirmPin

    RescueAuthSheet(
        title = stringResource(
            if (hasExistingPin) R.string.backup_pin_manage_change_title else R.string.backup_pin_manage_add_title,
        ),
        subtitle = stringResource(R.string.backup_pin_manage_subtitle),
        icon = Icons.Outlined.Pin,
        onDismiss = onDismiss,
        dismissEnabled = !submitting,
        modifier = Modifier.testTag(BackupPinTestTags.MANAGE_SHEET),
        footer = { dismiss ->
            RescueAuthActionBar(
                secondaryLabel = stringResource(R.string.common_cancel),
                secondaryIcon = Icons.Filled.Close,
                onSecondaryClick = dismiss,
                secondaryEnabled = !submitting,
                primaryLabel = stringResource(if (submitting) R.string.common_working else R.string.backup_pin_save_action),
                primaryIcon = Icons.Filled.Check,
                onPrimaryClick = {
                    attempted = true
                    if (valid) onSavePin(pin, dismiss)
                },
                primaryEnabled = !submitting && pin.isNotEmpty() && confirmPin.isNotEmpty(),
            )
        },
    ) {
        RescueAuthCard {
            Column(verticalArrangement = Arrangement.spacedBy(CardTokens.formFieldSpacing)) {
                RescueAuthTextField(
                    value = pin,
                    onValueChange = { next -> if (next.all { it.isDigit() }) pin = next },
                    enabled = !submitting,
                    singleLine = true,
                    label = { Text(stringResource(R.string.backup_pin_new_label)) },
                    visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Next,
                    ),
                    trailingIcon = {
                        RescueAuthVisibilityToggle(
                            revealed = showPin,
                            onClick = { showPin = !showPin },
                            modifier = Modifier.testTag("pin_visibility"),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag(BackupPinTestTags.MANAGE_PIN_INPUT),
                )
                RescueAuthTextField(
                    value = confirmPin,
                    onValueChange = { next -> if (next.all { it.isDigit() }) confirmPin = next },
                    enabled = !submitting,
                    singleLine = true,
                    isError = errorText != null,
                    label = { Text(stringResource(R.string.backup_pin_confirm_label)) },
                    visualTransformation = if (showConfirm) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    trailingIcon = {
                        RescueAuthVisibilityToggle(
                            revealed = showConfirm,
                            onClick = { showConfirm = !showConfirm },
                            modifier = Modifier.testTag("pin_confirm_visibility"),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag(BackupPinTestTags.MANAGE_CONFIRM_INPUT),
                )
                if (errorText != null) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (hasExistingPin && canRemovePin) {
            RescueAuthRowCard(
                onClick = if (!submitting) ({ confirmRemove = true }) else null,
                modifier = Modifier.testTag(BackupPinTestTags.MANAGE_REMOVE_ROW),
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
            ) {
                RescueAuthIconBadge(
                    icon = Icons.Outlined.Delete,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = stringResource(R.string.backup_pin_remove_action),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (confirmRemove) {
        DestructiveConfirmationDialog(
            title = stringResource(R.string.backup_pin_remove_confirm_title),
            message = stringResource(R.string.backup_pin_remove_confirm_message),
            confirmLabel = stringResource(R.string.backup_pin_remove_action),
            onConfirm = {
                confirmRemove = false
                onRemovePin(onDismiss)
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun pinIssueMessage(reason: PinPolicy.InvalidReason): String = when (reason) {
    PinPolicy.InvalidReason.EMPTY -> stringResource(R.string.pin_error_empty)
    PinPolicy.InvalidReason.NON_DIGIT -> stringResource(R.string.pin_policy_error_charset)
    PinPolicy.InvalidReason.TOO_SHORT -> stringResource(R.string.pin_policy_error_short, PinPolicy.MIN_PIN_LENGTH)
    PinPolicy.InvalidReason.TOO_LONG -> stringResource(R.string.pin_policy_error_long, PinPolicy.MAX_PIN_LENGTH)
}
