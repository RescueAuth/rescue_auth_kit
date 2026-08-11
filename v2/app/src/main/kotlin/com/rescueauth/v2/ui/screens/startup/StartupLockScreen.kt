package com.rescueauth.v2.ui.screens.startup

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import com.rescueauth.v2.R

object StartupLockTestTags {
    const val SCREEN = "startup_lock_screen"
    const val UNLOCK_BUTTON = "startup_unlock_button"
    const val EXIT_BUTTON = "startup_exit_button"
}

/**
 * Simple locked state shown before the Vault is unlocked (Issue #50).
 *
 * The app never renders the Vault shell while the session is LOCKED. This
 * screen offers exactly two actions:
 *  - **Unlock** — launches the system auth prompt (fingerprint / face / PIN /
 *    pattern / password).
 *  - **Exit** — closes the app.
 *
 * It intentionally does NOT provide any way to bypass authentication or lower
 * the Keystore security level.
 */
@Composable
fun StartupLockScreen(
    onUnlock: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(StartupLockTestTags.SCREEN),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Brand header (Issue #64): app logo + localized brand name. The logo is
        // purely decorative (contentDescription = null) so TalkBack reads the
        // brand name exactly once, not twice.
        StartupBrandHeader()

        Spacer(Modifier.height(24.dp))

        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.startup_locked_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.startup_locked_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )
        Button(
            onClick = onUnlock,
            modifier = Modifier
                .padding(top = 24.dp)
                .testTag(StartupLockTestTags.UNLOCK_BUTTON),
        ) {
            Text(text = stringResource(R.string.startup_unlock))
        }
        OutlinedButton(
            onClick = onExit,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag(StartupLockTestTags.EXIT_BUTTON),
        ) {
            Text(text = stringResource(R.string.startup_exit))
        }
    }
}

/**
 * Brand header for the startup visual: the app logo with the localized brand
 * name beneath it. Uses [MaterialTheme.colorScheme] colors so it adapts to
 * Light and Dark modes, and never hard-codes a single-theme color.
 */
@Composable
private fun StartupBrandHeader() {
    Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = Modifier.size(88.dp),
    )
    Text(
        text = stringResource(R.string.startup_brand_name),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * Blocking state shown when the device has **no** usable secure lock
 * (no PIN / pattern / password and no strong biometric enrolled).
 *
 * The Vault cannot be protected by Android Keystore user authentication, so
 * the app refuses to open it rather than silently lowering security. The user
 * can only go configure a secure device lock, or exit.
 */
@Composable
fun NoSecureDeviceScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StartupBlockedScreen(
        title = stringResource(R.string.startup_no_secure_title),
        body = stringResource(R.string.startup_no_secure_body),
        onExit = onExit,
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
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
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
