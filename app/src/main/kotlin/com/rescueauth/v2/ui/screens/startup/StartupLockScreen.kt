package com.rescueauth.v2.ui.screens.startup

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.ui.text.font.FontWeight
import com.rescueauth.v2.ui.components.RescueAuthMark
import com.rescueauth.v2.ui.components.RescueAuthWordmark
import com.rescueauth.v2.ui.theme.CardTokens
import com.rescueauth.v2.ui.theme.ScreenTokens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import com.rescueauth.v2.ui.components.RescueAuthButton as Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.rescueauth.v2.ui.components.RescueAuthOutlinedButton as OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.components.RescueAuthCard
import com.rescueauth.v2.ui.components.RescueAuthIconHeader
import com.rescueauth.v2.ui.theme.Spacing

object StartupLockTestTags {
    const val SCREEN = "startup_lock_screen"
    const val INTRO_SCREEN = "startup_intro_screen"
    const val INTRO_CONTINUE_BUTTON = "startup_intro_continue_button"
    const val INTRO_IMPORT_V1_BUTTON = "startup_intro_import_v1_button"
    const val AUTH_HOST = "startup_auth_host"
    const val EXIT_BUTTON = "startup_exit_button"
    const val SETTINGS_BUTTON = "startup_settings_button"
}

@Composable
fun StartupIntroScreen(
    onContinue: () -> Unit,
    onImportV1: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag(StartupLockTestTags.INTRO_SCREEN),
        color = MaterialTheme.colorScheme.background,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            val minimumHeight = maxHeight
            Column(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .heightIn(min = minimumHeight)
                    .padding(horizontal = ScreenTokens.horizontalPadding, vertical = Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                RescueAuthCard(
                    modifier = Modifier.testTag(StartupSplashTestTags.BRANDING),
                    containerColor = MaterialTheme.colorScheme.background,
                    contentPadding = CardTokens.noPadding,
                ) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        RescueAuthMark(Modifier.size(CardTokens.welcomeLogoSize))
                        Spacer(Modifier.height(Spacing.sm))
                        RescueAuthWordmark(prominent = true)
                        Text(
                            stringResource(R.string.startup_intro_storage),
                            modifier = Modifier.padding(top = Spacing.sm),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(Spacing.xl))
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().testTag(StartupLockTestTags.INTRO_CONTINUE_BUTTON),
                    contentPadding = PaddingValues(horizontal = CardTokens.heroPadding, vertical = Spacing.md),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground,
                        contentColor = MaterialTheme.colorScheme.background),
                ) {
                    Icon(Icons.Outlined.Fingerprint, null, Modifier.size(24.dp))
                    Column(Modifier.weight(1f).padding(horizontal = Spacing.md)) {
                        Text(stringResource(R.string.studio_create_vault), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.compact_unlock_methods),
                            Modifier.padding(top = Spacing.xxs), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.78f))
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
                }
                if (onImportV1 != null) {
                    TextButton(
                        onClick = onImportV1,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)
                            .testTag(StartupLockTestTags.INTRO_IMPORT_V1_BUTTON),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    ) { Text(stringResource(R.string.startup_intro_import_v1), style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

@Composable
fun StartupAuthHost(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(StartupLockTestTags.AUTH_HOST),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StartupBranding(logoSize = 72.dp)
        }
    }
}

/** Neutral host shown while the authenticated Vault is opened in the background. */
@Composable
fun StartupOpeningHost(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StartupBranding(logoSize = 72.dp)
            Text(
                text = stringResource(R.string.startup_opening),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.lg),
            )
        }
    }
}

@Composable
fun NoSecureDeviceScreen(onExit: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    StartupBlockedScreen(
        title = stringResource(R.string.startup_no_secure_title),
        body = stringResource(R.string.startup_no_secure_body),
        onExit = onExit,
        modifier = modifier,
        actions = {
            Button(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_SECURITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(StartupLockTestTags.SETTINGS_BUTTON),
            ) {
                Text(stringResource(R.string.startup_no_secure_action))
            }
            OutlinedButton(
                onClick = onExit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs)
                    .testTag(StartupLockTestTags.EXIT_BUTTON),
            ) {
                Text(stringResource(R.string.startup_exit))
            }
        },
    )
}

@Composable
fun StartupBlockedScreen(
    title: String,
    body: String,
    onExit: () -> Unit = {},
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(StartupLockTestTags.SCREEN),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.Center,
        ) {
            RescueAuthCard {
                RescueAuthIconHeader(Icons.Filled.Lock, title, body, Modifier.testTag("startup_blocked_header"),
                    titleStyle = MaterialTheme.typography.titleLarge,
                    iconContainerColor = MaterialTheme.colorScheme.errorContainer,
                    iconContentColor = MaterialTheme.colorScheme.onErrorContainer)
                Column(Modifier.fillMaxWidth().padding(top = Spacing.lg)) {
                    if (actions != null) actions()
                    else OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth().testTag(StartupLockTestTags.EXIT_BUTTON)) {
                        Text(stringResource(R.string.startup_exit))
                    }
                }
            }
        }
    }
}
