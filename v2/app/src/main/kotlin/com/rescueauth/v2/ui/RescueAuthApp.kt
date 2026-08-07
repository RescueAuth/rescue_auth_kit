package com.rescueauth.v2.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rescueauth.v2.ui.components.UndoSnackbarHost
import com.rescueauth.v2.ui.navigation.RescueAuthRoutes
import com.rescueauth.v2.ui.navigation.TopLevelDestinations
import com.rescueauth.v2.ui.screens.authenticator.AuthenticatorScreen
import com.rescueauth.v2.ui.screens.developer.DeveloperScreen
import com.rescueauth.v2.ui.screens.settings.SettingsScreen

object RescueAuthTestTags {
    const val NAV_AUTHENTICATOR = "nav_authenticator"
    const val NAV_DEVELOPER = "nav_developer"
    const val NAV_SETTINGS = "nav_settings"
    const val SCREEN_AUTHENTICATOR = "screen_authenticator"
    const val SCREEN_DEVELOPER = "screen_developer"
    const val SCREEN_SETTINGS = "screen_settings"
}

/**
 * RescueAuth v2 app shell.
 *
 * Hosts the three top-level destinations (Authenticator / Developer / Settings)
 * in a [Scaffold] with a bottom [NavigationBar], a shared [SnackbarHostState]
 * (the Undo contract lives here) and a Navigation-Compose [NavHost].
 *
 * This shell is intentionally **presentation-only**: it receives screen-level
 * state and callbacks from the host so it can be unit-tested without a real
 * Vault backend, and it never reads Room entities.
 */
@Composable
fun RescueAuthApp(
    modifier: Modifier = Modifier,
    versionName: String? = null,
    onAuthenticatorAdd: (() -> Unit)? = null,
    onDeveloperAdd: (() -> Unit)? = null,
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { UndoSnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                TopLevelDestinations.all.forEach { destination ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = null,
                            )
                        },
                        label = {
                            Text(text = stringResource(destination.labelRes))
                        },
                        modifier = Modifier.testTag(destination.testTag),
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = RescueAuthRoutes.AUTHENTICATOR,
            modifier = Modifier.padding(padding),
        ) {
            composable(RescueAuthRoutes.AUTHENTICATOR) {
                AuthenticatorScreen(
                    onAddClick = onAuthenticatorAdd,
                    modifier = Modifier.testTag(RescueAuthTestTags.SCREEN_AUTHENTICATOR),
                )
            }
            composable(RescueAuthRoutes.DEVELOPER) {
                DeveloperScreen(
                    onAddClick = onDeveloperAdd,
                    modifier = Modifier.testTag(RescueAuthTestTags.SCREEN_DEVELOPER),
                )
            }
            composable(RescueAuthRoutes.SETTINGS) {
                SettingsScreen(
                    versionName = versionName,
                    modifier = Modifier.testTag(RescueAuthTestTags.SCREEN_SETTINGS),
                )
            }
        }
    }
}
