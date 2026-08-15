package com.rescueauth.v2.ui.screens.startup

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R

object StartupLockTestTags {
    const val SCREEN = "startup_lock_screen"
    const val INTRO_SCREEN = "startup_intro_screen"
    const val INTRO_CONTINUE_BUTTON = "startup_intro_continue_button"
    const val AUTH_HOST = "startup_auth_host"
    const val EXIT_BUTTON = "startup_exit_button"
    const val SETTINGS_BUTTON = "startup_settings_button"
}

/**
 * One-time notice shown only on the very first install, explaining that the
 * user is enabling "unlock with phone" (Issue #50 UX rework).
 *
 * This is deliberately **not** a security onboarding page — it is a trivial
 * first-time "enable use your phone to unlock" screen. It does not explain
 * implementation details (vault, Keystore, master password, encryption, auth
 * token). Tapping **Enable** launches the existing system authentication
 * prompt (fingerprint / lock screen); on success the Vault is created and the
 * app opens. It is never shown again once acknowledged. Existing-vault
 * launches skip it entirely.
 */
@Composable
fun StartupIntroScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(StartupLockTestTags.INTRO_SCREEN),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.startup_intro_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.startup_intro_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )
        Button(
            onClick = onContinue,
            modifier = Modifier
                .padding(top = 24.dp)
                .testTag(StartupLockTestTags.INTRO_CONTINUE_BUTTON),
        ) {
            Text(text = stringResource(R.string.startup_intro_continue))
        }
    }
}

/**
 * Minimal neutral host shown while the system authentication prompt is being
 * requested / showing (Issue #50 §6).
 *
 * It is NOT interactive and never exposes sensitive content (TOTP, recovery
 * codes, secrets, developer data, main app content). It simply shows a neutral
 * loading / splash that acts as the backdrop for the system BiometricPrompt.
 * The user is not required to perform any additional action here.
 */
@Composable
fun StartupAuthHost(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(StartupLockTestTags.AUTH_HOST),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Blocking state shown when the device has **no** usable secure lock
 * (no PIN / pattern / password and no strong biometric enrolled).
 *
 * The Vault cannot be protected by Android Keystore user authentication, so
 * the app refuses to open it rather than silently lowering security. The user
 * can only go configure a secure device lock (via system settings), or exit.
 * After configuring a lock and returning, the app re-detects and can continue.
 */
@Composable
fun NoSecureDeviceScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    StartupBlockedScreen(
        title = stringResource(R.string.startup_no_secure_title),
        body = stringResource(R.string.startup_no_secure_body),
        actions = {
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_SECURITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier
                    .padding(top = 24.dp)
                    .testTag(StartupLockTestTags.SETTINGS_BUTTON),
            ) {
                Text(text = stringResource(R.string.startup_no_secure_action))
            }
            OutlinedButton(
                onClick = onExit,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag(StartupLockTestTags.EXIT_BUTTON),
            ) {
                Text(text = stringResource(R.string.startup_exit))
            }
        },
        modifier = modifier,
    )
}

/**
 * Generic unrecoverable-startup blocking screen (e.g. Keystore key
 * invalidated, DB corrupt). Shows a distinct error with only an Exit action.
 */
@Composable
fun StartupBlockedScreen(
    title: String,
    body: String,
    onExit: () -> Unit = {},
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(StartupLockTestTags.SCREEN),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )
        if (actions != null) {
            actions()
        } else {
            OutlinedButton(
                onClick = onExit,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .testTag(StartupLockTestTags.EXIT_BUTTON),
            ) {
                Text(text = stringResource(R.string.startup_exit))
            }
        }
    }
}
