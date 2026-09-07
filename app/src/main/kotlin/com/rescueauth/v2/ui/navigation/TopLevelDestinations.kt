package com.rescueauth.v2.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.ui.graphics.vector.ImageVector
import com.rescueauth.v2.R
import com.rescueauth.v2.ui.RescueAuthTestTags

/**
 * One top-level destination in the app shell.
 *
 * @param route the [RescueAuthRoutes] route
 * @param labelRes string resource for the navigation label
 * @param icon navigation-bar icon
 */
data class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val testTag: String,
)

/**
 * The three top-level destinations: Authenticator, Developer, Settings.
 * Developer Vault is a first-class product module (never hidden inside
 * Settings).
 */
object TopLevelDestinations {
    val AUTHENTICATOR = TopLevelDestination(
        route = RescueAuthRoutes.AUTHENTICATOR,
        labelRes = R.string.nav_authenticator,
        icon = Icons.Outlined.Shield,
        testTag = RescueAuthTestTags.NAV_AUTHENTICATOR,
    )
    val DEVELOPER = TopLevelDestination(
        route = RescueAuthRoutes.DEVELOPER,
        labelRes = R.string.nav_developer,
        icon = Icons.Outlined.Code,
        testTag = RescueAuthTestTags.NAV_DEVELOPER,
    )
    val SETTINGS = TopLevelDestination(
        route = RescueAuthRoutes.SETTINGS,
        labelRes = R.string.nav_settings,
        icon = Icons.Outlined.Settings,
        testTag = RescueAuthTestTags.NAV_SETTINGS,
    )

    val all = listOf(AUTHENTICATOR, DEVELOPER, SETTINGS)
}
