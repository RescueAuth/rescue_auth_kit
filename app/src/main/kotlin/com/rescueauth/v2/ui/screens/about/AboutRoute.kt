package com.rescueauth.v2.ui.screens.about

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rescueauth.v2.update.HttpUpdateTransport
import com.rescueauth.v2.update.UpdateCheckViewModel
import com.rescueauth.v2.update.UpdateTrustProvider
import com.rescueauth.v2.update.UpdateVersionDecision
import com.rescueauth.v2.update.ExternalOpenHelper

/**
 * About route — wires the [UpdateCheckViewModel] (its coroutine scope is the
 * Compose scope, so leaving the screen cancels any in-flight check — Issue #20
 * §20), the external "Open Release Page" action, and the [AboutScreen].
 *
 * The public key comes from BuildConfig via [UpdateTrustProvider]; when not
 * provisioned the update check returns NOT_CONFIGURED and the Vault keeps
 * working (fail open for the app).
 */
@Composable
fun AboutRoute(
    versionName: String,
    versionCode: Long,
    encodedPublicKey: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel = remember {
        UpdateCheckViewModel(
            transport = HttpUpdateTransport(),
            trustConfig = UpdateTrustProvider.fromBuildConfig(encodedPublicKey),
            versionIdentity = {
                UpdateVersionDecision.VersionIdentity(
                    versionName = versionName,
                    versionCode = versionCode,
                )
            },
            scope = scope,
        )
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.cancel() }
    }

    val state by viewModel.state.collectAsState()

    AboutScreen(
        versionName = versionName,
        versionCode = versionCode,
        state = state,
        onCheckForUpdates = { viewModel.check() },
        onOpenReleasePage = {
            // Only offered for a verified update (AboutScreen only shows the
            // button in UpdateAvailable). External open re-validates HTTPS.
            ExternalOpenHelper.openReleasePage(context.applicationContext, viewModel.verifiedOpenUrl())
        },
        onBack = onBack,
        modifier = modifier,
        onNavigate = onNavigate,
    )
}
