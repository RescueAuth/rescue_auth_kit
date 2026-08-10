package com.rescueauth.v2.ui

import com.rescueauth.v2.BuildConfig
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
import com.rescueauth.v2.ui.authenticator.AuthenticatorRoute
import com.rescueauth.v2.ui.authenticator.RecoveryCodesRoute
import com.rescueauth.v2.ui.developer.DeveloperDetailRoute
import com.rescueauth.v2.ui.developer.DeveloperFormRoute
import com.rescueauth.v2.ui.developer.DeveloperFormType
import com.rescueauth.v2.ui.developer.DeveloperRoute
import com.rescueauth.v2.ui.navigation.RescueAuthRoutes
import com.rescueauth.v2.ui.navigation.TopLevelDestinations
import com.rescueauth.v2.ui.screens.about.AboutRoute
import com.rescueauth.v2.ui.screens.about.AboutTestTags
import com.rescueauth.v2.ui.screens.developer.DeveloperScreen
import com.rescueauth.v2.ui.screens.exportimport.ExportImportMode
import com.rescueauth.v2.ui.screens.exportimport.ExportImportRoute
import com.rescueauth.v2.ui.screens.settings.SettingsScreen
import com.rescueauth.v2.ui.screens.legacyimport.LegacyImportRoute

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
                AuthenticatorRoute(
                    onOpenAccount = { accountId ->
                        navController.navigate("authenticator/account/$accountId")
                    },
                    modifier = Modifier.testTag(RescueAuthTestTags.SCREEN_AUTHENTICATOR),
                )
            }
            composable(
                route = RescueAuthRoutes.AUTHENTICATOR_ACCOUNT,
                arguments = listOf(
                    androidx.navigation.navArgument(RescueAuthRoutes.ARG_ACCOUNT_ID) {
                        type = androidx.navigation.NavType.StringType
                    },
                ),
            ) { entry ->
                val accountId = entry.arguments?.getString(RescueAuthRoutes.ARG_ACCOUNT_ID).orEmpty()
                RecoveryCodesRoute(
                    accountId = accountId,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(RescueAuthRoutes.DEVELOPER) {
                DeveloperRoute(
                    onOpenEntry = { entry ->
                        navController.navigate(RescueAuthRoutes.developerEntry(entry.stableId))
                    },
                    onAddTypeSelected = { type ->
                        navController.navigate(
                            RescueAuthRoutes.developerAdd(type.name),
                        )
                    },
                    modifier = Modifier.testTag(RescueAuthTestTags.SCREEN_DEVELOPER),
                )
            }
            composable(
                route = RescueAuthRoutes.DEVELOPER_ENTRY,
                arguments = listOf(
                    androidx.navigation.navArgument(RescueAuthRoutes.ARG_ENTRY_ID) {
                        type = androidx.navigation.NavType.StringType
                    },
                ),
            ) { entry ->
                val entryId = entry.arguments?.getString(RescueAuthRoutes.ARG_ENTRY_ID).orEmpty()
                DeveloperDetailRoute(
                    stableId = entryId,
                    onBack = { navController.popBackStack() },
                    onEdit = { stableId ->
                        navController.navigate(RescueAuthRoutes.developerEdit(stableId))
                    },
                )
            }
            composable(
                route = RescueAuthRoutes.DEVELOPER_FORM,
                arguments = listOf(
                    androidx.navigation.navArgument(RescueAuthRoutes.ARG_EDIT_STABLE_ID) {
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    androidx.navigation.navArgument(RescueAuthRoutes.ARG_FORM_TYPE) {
                        type = androidx.navigation.NavType.StringType
                        defaultValue = "API_CREDENTIAL"
                    },
                ),
            ) { entry ->
                val editId = entry.arguments?.getString(RescueAuthRoutes.ARG_EDIT_STABLE_ID)
                val type = entry.arguments?.getString(RescueAuthRoutes.ARG_FORM_TYPE)
                    ?.let { runCatching { DeveloperFormType.valueOf(it) }.getOrNull() }
                    ?: DeveloperFormType.API_CREDENTIAL
                DeveloperFormRoute(
                    editingStableId = editId,
                    initialType = type,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                )
            }
            composable(RescueAuthRoutes.SETTINGS) {
                SettingsScreen(
                    versionName = versionName,
                    onExportClick = { navController.navigate(RescueAuthRoutes.EXPORT) },
                    onImportClick = { navController.navigate(RescueAuthRoutes.IMPORT) },
                    onLegacyImportClick = { navController.navigate(RescueAuthRoutes.LEGACY_IMPORT) },
                    onAboutClick = { navController.navigate(RescueAuthRoutes.ABOUT) },
                    modifier = Modifier.testTag(RescueAuthTestTags.SCREEN_SETTINGS),
                )
            }
            composable(RescueAuthRoutes.ABOUT) {
                AboutRoute(
                    versionName = versionName ?: "",
                    versionCode = BuildConfig.VERSION_CODE.toLong(),
                    encodedPublicKey = BuildConfig.UPDATE_PUBLIC_KEY,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.testTag(AboutTestTags.SCREEN),
                )
            }
            composable(RescueAuthRoutes.EXPORT) {
                ExportImportRoute(
                    mode = ExportImportMode.EXPORT,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.testTag("screen_export"),
                )
            }
            composable(RescueAuthRoutes.IMPORT) {
                ExportImportRoute(
                    mode = ExportImportMode.IMPORT,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.testTag("screen_import"),
                )
            }
            composable(RescueAuthRoutes.LEGACY_IMPORT) {
                LegacyImportRoute(
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.testTag("screen_legacy_import"),
                )
            }
        }
    }
}
