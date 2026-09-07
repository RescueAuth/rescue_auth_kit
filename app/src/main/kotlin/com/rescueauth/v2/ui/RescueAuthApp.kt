package com.rescueauth.v2.ui

import com.rescueauth.v2.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rescueauth.v2.ui.components.UndoSnackbarHost
import com.rescueauth.v2.ui.authenticator.AuthenticatorRoute
import com.rescueauth.v2.ui.authenticator.AuthenticatorViewModel
import com.rescueauth.v2.ui.authenticator.RecoveryCodesRoute
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.session.SecureSessionStateMachine
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
import com.rescueauth.v2.ui.search.SearchRoute

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
    startRoute: String? = null,
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    // Optional deep-link on first app launch (e.g. a v1 migration entry tapped
    // from the Intro screen lands here straight inside the legacy importer).
    LaunchedEffect(startRoute) {
        if (startRoute != null) {
            navController.navigate(startRoute) { launchSingleTop = true }
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // The Authenticator ViewModel is held at the app-shell level (not inside
    // the per-tab route) so switching bottom tabs — which disposes the route's
    // composition and would otherwise destroy a `remember { }` ViewModel — does
    // NOT tear down the Room collection job nor force a full reload + TOTP
    // recompute on every return to the Authenticator tab. The shell survives
    // tab switches and nested-navigation pushes, so the loaded card state is
    // retained and the screen renders immediately instead of showing a loading
    // spinner each time the user re-enters (Issue #70 "每次进入认证器都要加载一段时间").
    val authenticatorScope = rememberCoroutineScope()
    val sessionStateFlow: kotlinx.coroutines.flow.StateFlow<SecureSessionStateMachine.State> = remember {
        val sm = VaultAccess.sessionManager
        if (sm != null) sm.sessionStateFlow else SecureSessionStateMachine().state
    }
    val authenticatorViewModel = remember {
        AuthenticatorViewModel(
            repositoryProvider = { VaultAccess.authenticatorRepository() },
            recoveryRepositoryProvider = { VaultAccess.recoveryRepository() },
            managementRepositoryProvider = { VaultAccess.providerAccountRepository() },
            sessionState = sessionStateFlow,
            clock = AuthenticatorViewModel.Clock { System.currentTimeMillis() / 1000L },
            scope = authenticatorScope,
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        // The shell Scaffold must NOT re-apply the system-bar insets to the
        // NavHost: on targetSdk 35 (enforced edge-to-edge) each screen already
        // owns a Scaffold + TopAppBar that correctly insets for the status bar
        // / display cutout. Re-applying the status-bar height here on top of
        // that would push every page down by an extra status-bar tall empty
        // band above the header (the "excessive top gap" / double padding bug).
        // Bottom insets are still handled by the NavigationBar itself.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { UndoSnackbarHost(snackbarHostState) },
        bottomBar = {
            com.rescueauth.v2.ui.components.RescueAuthNavigationBar(
                isSelected = { destination ->
                    currentDestination?.hierarchy?.any { it.route == destination.route } == true
                },
                onNavigate = { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = RescueAuthRoutes.AUTHENTICATOR,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
        ) {
            composable(RescueAuthRoutes.AUTHENTICATOR) {
                AuthenticatorRoute(
                    viewModel = authenticatorViewModel,
                    onOpenAccount = { accountId ->
                        navController.navigate("authenticator/account/$accountId")
                    },
                    onOpenSearch = {
                        navController.navigate(RescueAuthRoutes.SEARCH)
                    },
                    modifier = Modifier.testTag(RescueAuthTestTags.SCREEN_AUTHENTICATOR),
                )
            }
            composable(RescueAuthRoutes.SEARCH) {
                SearchRoute(
                    onBack = { navController.popBackStack() },
                    onResultClick = { result ->
                        when (result) {
                            is com.rescueauth.v2.search.SearchResult.Provider -> {
                                navController.popBackStack()
                                // Provider = serviceName grouping; landing on the
                                // Authenticator home (its natural context).
                            }
                            is com.rescueauth.v2.search.SearchResult.Account -> {
                                navController.navigate("authenticator/account/${result.navigationId}")
                            }
                            is com.rescueauth.v2.search.SearchResult.Totp -> {
                                // Open the owning Account detail (P7 §12).
                                navController.navigate("authenticator/account/${result.accountId}")
                            }
                            is com.rescueauth.v2.search.SearchResult.RecoverySet -> {
                                navController.navigate("authenticator/account/${result.accountId}")
                            }
                            is com.rescueauth.v2.search.SearchResult.Developer -> {
                                navController.navigate(RescueAuthRoutes.developerEntry(result.navigationId))
                            }
                        }
                    },
                    modifier = Modifier.testTag("screen_search"),
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
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
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
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
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
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
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
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(RescueAuthRoutes.EXPORT) {
                ExportImportRoute(
                    mode = ExportImportMode.EXPORT,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.testTag("screen_export"),
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(RescueAuthRoutes.IMPORT) {
                ExportImportRoute(
                    mode = ExportImportMode.IMPORT,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.testTag("screen_import"),
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(RescueAuthRoutes.LEGACY_IMPORT) {
                LegacyImportRoute(
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.testTag("screen_legacy_import"),
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
    }
}
