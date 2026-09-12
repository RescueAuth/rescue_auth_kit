package com.rescueauth.v2.ui.screens.startup

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
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
import com.rescueauth.v2.ui.components.RescueAuthIconBadge
import com.rescueauth.v2.ui.components.RescueAuthRibbonBackdrop
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
        modifier = modifier
            .fillMaxSize()
            .testTag(StartupLockTestTags.INTRO_SCREEN),
        color = MaterialTheme.colorScheme.background,
    ) {
        // Box so the "Import from v1" entry can sit in the top-right corner,
        // independent of the vertically-centered intro card below.
        Box(modifier = Modifier.fillMaxSize()) {
            RescueAuthRibbonBackdrop(Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = Spacing.lg),
            ) {
            if (onImportV1 != null) {
                TextButton(
                    onClick = onImportV1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .testTag(StartupLockTestTags.INTRO_IMPORT_V1_BUTTON),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Spacing.xs),
                ) {
                    Text(
                        text = stringResource(R.string.startup_intro_import_v1),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(animationSpec = tween(420)) + slideInVertically(
                    animationSpec = tween(420),
                    initialOffsetY = { it / 12 },
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 72.dp, bottom = 32.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StartupBranding(logoSize = 56.dp)
                    Text(
                        text = stringResource(R.string.startup_intro_tagline),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
                RescueAuthCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xl),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RescueAuthIconBadge(
                            icon = Icons.Filled.Lock,
                            size = 42.dp,
                            iconSize = 21.dp,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.startup_intro_title),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = stringResource(R.string.startup_intro_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacing.xxs),
                            )
                        }
                    }
                    Button(
                        onClick = onContinue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.lg)
                            .testTag(StartupLockTestTags.INTRO_CONTINUE_BUTTON),
                    ) {
                        Text(text = stringResource(R.string.startup_intro_continue))
                    }
                }
                }
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
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RescueAuthIconBadge(
                icon = Icons.Filled.Lock,
                size = 64.dp,
                iconSize = 30.dp,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.md),
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.lg),
            ) {
                if (actions != null) {
                    actions()
                } else {
                    OutlinedButton(
                        onClick = onExit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(StartupLockTestTags.EXIT_BUTTON),
                    ) {
                        Text(stringResource(R.string.startup_exit))
                    }
                }
            }
        }
    }
}
