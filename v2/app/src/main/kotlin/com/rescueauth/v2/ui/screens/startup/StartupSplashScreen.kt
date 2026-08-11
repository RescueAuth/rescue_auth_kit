package com.rescueauth.v2.ui.screens.startup

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rescueauth.v2.R

object StartupSplashTestTags {
    const val SCREEN = "startup_splash_screen"
}

/**
 * Minimal startup host (Issue #64) shown right after the system SplashScreen
 * and before the startup authentication flow.
 *
 * It displays only the app logo plus the localized brand name, giving the
 * user a recognizable brand visual at launch without adding any interaction
 * or startup latency. Per Issue #64 §6 this is an intentionally tiny "system
 * splash → brief Compose startup host" layer: there are no buttons, no lock
 * screen, and it never blocks the authentication flow that follows.
 *
 * The logo is purely decorative (`contentDescription = null`) and the brand
 * name carries the only semantic, so TalkBack reads the brand exactly once
 * instead of twice (Issue #64 §10).
 */
@Composable
fun StartupSplashScreen(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(StartupSplashTestTags.SCREEN),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
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
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
