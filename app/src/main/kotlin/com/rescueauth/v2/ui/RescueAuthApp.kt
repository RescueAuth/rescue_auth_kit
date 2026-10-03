package com.rescueauth.v2.ui

import com.rescueauth.v2.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.EnterExitState
import com.rescueauth.v2.ui.navigation.SearchFieldMotion
import com.rescueauth.v2.ui.navigation.searchFieldMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import com.rescueauth.v2.ui.navigation.motionDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rescueauth.v2.ui.components.UndoSnackbarHost
import com.rescueauth.v2.ui.authenticator.AuthenticatorRoute
import com.rescueauth.v2.ui.authenticator.AuthenticatorViewModel
import com.rescueauth.v2.ui.authenticator.AuthenticatorAddState
import com.rescueauth.v2.ui.components.RescueAuthFloatingAddOverlay
import com.rescueauth.v2.ui.components.rememberFloatingAddPosition
import com.rescueauth.v2.ui.screens.developer.DeveloperAddSheet
import com.rescueauth.v2.ui.theme.AddActionTokens
import com.rescueauth.v2.ui.authenticator.RecoveryCodesRoute
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.ui.developer.DeveloperDetailRoute
import com.rescueauth.v2.ui.developer.DeveloperFormRoute
import com.rescueauth.v2.ui.developer.DeveloperFormType
import com.rescueauth.v2.ui.developer.DeveloperRoute
import com.rescueauth.v2.ui.developer.DeveloperListViewModel
import com.rescueauth.v2.ui.model.DeveloperEntryType
import com.rescueauth.v2.ui.navigation.RescueAuthRoutes
import com.rescueauth.v2.ui.navigation.RescueAuthNavigationMotion
import com.rescueauth.v2.ui.navigation.vaultDestination
import com.rescueauth.v2.ui.navigation.TopLevelDestinations
import com.rescueauth.v2.ui.navigation.HomePages
import com.rescueauth.v2.ui.navigation.rememberHomePageMotionState
import com.rescueauth.v2.ui.screens.about.AboutRoute
import com.rescueauth.v2.ui.screens.about.AboutTestTags
import com.rescueauth.v2.ui.screens.developer.DeveloperScreen
import com.rescueauth.v2.ui.screens.exportimport.ExportImportMode
import com.rescueauth.v2.ui.screens.exportimport.ExportImportRoute
import com.rescueauth.v2.ui.screens.settings.SettingsScreen
import com.rescueauth.v2.ui.screens.settings.SettingsTransferScreen
import com.rescueauth.v2.ui.theme.ThemeMode
import com.rescueauth.v2.ui.screens.legacyimport.LegacyImportRoute
import com.rescueauth.v2.ui.search.SearchRoute
import android.net.Uri

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
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RescueAuthApp(
    modifier: Modifier = Modifier,
    versionName: String? = null,
    startRoute: String? = null,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: ((ThemeMode) -> Unit)? = null,
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val layoutDirection = LocalLayoutDirection.current
    val motion = remember(layoutDirection) { RescueAuthNavigationMotion(layoutDirection) }
    var retainedRootBottomPadding by remember { mutableStateOf(0.dp) }
    val homePageState = rememberHomePageMotionState(
        initialPage = TopLevelDestinations.all.indexOfFirst { it.route == startRoute }.coerceAtLeast(0),
        pageCount = TopLevelDestinations.all.size,
    )
    val navigationScope = rememberCoroutineScope()
    var homeScrollJob by remember { mutableStateOf<Job?>(null) }
    val selectHomePage: (Int) -> Unit = { page ->
        if (page != homePageState.targetPage) {
            homeScrollJob?.cancel()
            // Capture the visible offsets in this input event, before the next frame can run.
            homeScrollJob = navigationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                homePageState.animateTo(page)
            }
        }
    }
    val navigateFromWorkflow: (String) -> Unit = { route ->
        val rootPage = TopLevelDestinations.all.indexOfFirst { it.route == route }
        if (rootPage >= 0) {
            homeScrollJob?.cancel()
            navController.popBackStack(RescueAuthRoutes.HOME, inclusive = false)
            homeScrollJob = navigationScope.launch { homePageState.snapTo(rootPage) }
        } else {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // Optional deep-link on first app launch (e.g. a v1 migration entry tapped
    // from the Intro screen lands here straight inside the legacy importer).
    LaunchedEffect(startRoute) {
        val rootPage = TopLevelDestinations.all.indexOfFirst { it.route == startRoute }
        if (rootPage >= 0) homePageState.snapTo(rootPage)
        else if (startRoute != null) {
            // Scaffold subcomposition can attach NavHost after this effect starts.
            navController.currentBackStackEntryFlow.first()
            navController.navigate(startRoute) { launchSingleTop = true }
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // Keep the primary navigation visible only at the three product roots.
    // Detail, search and transfer flows get the full viewport and their own
    // back affordance; leaving the tab bar visible there makes secondary pages
    // feel like another root and competes with the task at hand.
    val showBottomBar = currentDestination?.route == RescueAuthRoutes.HOME
    var composedHomes by remember { mutableIntStateOf(0) }
    var homeNavigationSettled by remember { mutableStateOf(false) }
    val authenticatorAddState = remember { AuthenticatorAddState() }
    val addPosition = rememberFloatingAddPosition()
    // Capture the destination when the button is tapped; a later page change cannot retarget an open sheet.
    var addSheetPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(showBottomBar, homePageState.targetPage) { addSheetPage = null }
    BackHandler(enabled = showBottomBar && homePageState.targetPage != 0) { selectHomePage(0) }

    // The Authenticator ViewModel is held at the app-shell level (not inside
    // the per-tab route) so entering details and returning to a home page does
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
    val developerViewModel = remember {
        DeveloperListViewModel(
            developerRepositoryProvider = { VaultAccess.developerRepository() },
            sessionState = sessionStateFlow,
            scope = authenticatorScope,
        )
    }
    val migrationUi by authenticatorViewModel.migrationState.collectAsState()

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
        bottomBar = if (showBottomBar || composedHomes > 0) {
            {
                com.rescueauth.v2.ui.components.RescueAuthNavigationBar(
                    isSelected = { destination ->
                        TopLevelDestinations.all[homePageState.targetPage] == destination
                    },
                    pagePosition = { homePageState.dockPosition },
                    // The root Dock is uncovered by the departing opaque detail, never above its footer.
                    modifier = Modifier.zIndex(-1f),
                    enabled = showBottomBar && homeNavigationSettled,
                    onNavigate = { destination -> selectHomePage(TopLevelDestinations.all.indexOf(destination)) },
                )
            }
        } else {
            {}
        },
    ) { padding ->
        // Keep the host full-height. Root pages own their dock clearance, including while
        // they leave/re-enter; toggling the dock must not resize either page mid-transition.
        val rootBottomPadding = if (showBottomBar) padding.calculateBottomPadding() else retainedRootBottomPadding
        SideEffect {
            if (showBottomBar) retainedRootBottomPadding = rootBottomPadding
        }
        SharedTransitionLayout(Modifier.fillMaxSize()) {
        val searchSharedScope = this
        NavHost(
            navController = navController,
            startDestination = RescueAuthRoutes.HOME,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { motion.enter(isPop = false, sharedSearch = SearchFieldMotion.matches(initialState.destination.route, targetState.destination.route)) },
            exitTransition = { motion.exit(isPop = false, sharedSearch = SearchFieldMotion.matches(initialState.destination.route, targetState.destination.route)) },
            popEnterTransition = { motion.enter(isPop = true, sharedSearch = SearchFieldMotion.matches(initialState.destination.route, targetState.destination.route)) },
            popExitTransition = { motion.exit(isPop = true, sharedSearch = SearchFieldMotion.matches(initialState.destination.route, targetState.destination.route)) },
        ) {
            motionDestination(motion, RescueAuthRoutes.HOME) {
                val visibility = this
                DisposableEffect(Unit) {
                    composedHomes++
                    onDispose { composedHomes--; homeNavigationSettled = false }
                }
                val settled = transition.currentState == EnterExitState.Visible &&
                    transition.targetState == EnterExitState.Visible && !transition.isRunning
                SideEffect { homeNavigationSettled = settled }
                val searchFieldModifier = with(searchSharedScope) { searchFieldMotion(visibility) }
                HomePages(
                    state = homePageState,
                    modifier = Modifier.padding(bottom = rootBottomPadding).fillMaxSize().testTag("home_pager"),
                ) { page ->
                    when (page) {
                        0 -> {
                            AuthenticatorRoute(
                                viewModel = authenticatorViewModel,
                                isActive = showBottomBar && homePageState.settledPage == 0,
                                addState = authenticatorAddState,
                                showAddAction = false,
                                contentBottomPadding = AddActionTokens.contentClearance,
                                onOpenAccount = { accountId ->
                                    navController.navigate("authenticator/account-detail/$accountId")
                                },
                                onOpenProvider = { providerName ->
                                    navController.navigate("authenticator/provider/${Uri.encode(providerName)}")
                                },
                                onOpenSearch = {
                                    navController.navigate(RescueAuthRoutes.SEARCH) { launchSingleTop = true }
                                },
                                searchFieldModifier = searchFieldModifier,
                                modifier = Modifier.testTag(RescueAuthTestTags.SCREEN_AUTHENTICATOR),
                            )
                        }
                        1 -> {
                            DeveloperRoute(
                                viewModel = developerViewModel,
                                isActive = showBottomBar && homePageState.settledPage == 1,
                                showAddAction = false,
                                contentBottomPadding = AddActionTokens.contentClearance,
                                onOpenEntry = { entry ->
                                    navController.navigate(RescueAuthRoutes.developerEntry(entry.stableId))
                                },
                                onAddTypeSelected = { type ->
                                    navController.navigate(
                                        RescueAuthRoutes.developerAdd(type.name),
                                    )
                                },
                                onOpenCategory = { type ->
                                    navController.navigate("developer/category/${type.name}")
                                },
                                modifier = Modifier.testTag(RescueAuthTestTags.SCREEN_DEVELOPER),
                            )
                        }
                        2 -> {
                            SettingsScreen(
                                versionName = versionName,
                                themeMode = themeMode,
                                onThemeModeChange = onThemeModeChange,
                                onExportClick = { navController.navigate(RescueAuthRoutes.EXPORT) },
                                onImportClick = { navController.navigate(RescueAuthRoutes.IMPORT) },
                                onLegacyImportClick = { navController.navigate(RescueAuthRoutes.LEGACY_IMPORT) },
                                onAboutClick = { navController.navigate(RescueAuthRoutes.ABOUT) },
                                onTransferClick = { navController.navigate(RescueAuthRoutes.SETTINGS_TRANSFER) },
                                modifier = Modifier.testTag(RescueAuthTestTags.SCREEN_SETTINGS),
                            )
                        }
                    }
                }
                // Fixed across the three homes, but covered together with its root on detail navigation.
                RescueAuthFloatingAddOverlay(
                    visible = homePageState.targetPage != 2 && !migrationUi.scannerVisible,
                    enabled = showBottomBar && settled && !homePageState.isScrollInProgress && addSheetPage == null,
                    position = addPosition,
                    onClick = {
                        if (homePageState.settledPage == 0) authenticatorAddState.credentialSheetVisible = true
                        else addSheetPage = homePageState.settledPage
                    },
                    modifier = Modifier.padding(bottom = rootBottomPadding),
                )
            }
            // Older saved back stacks may still contain these root destinations. Keep their
            // IDs resolvable, then fold them into the matching page of the retained home entry.
            listOf(RescueAuthRoutes.DEVELOPER, RescueAuthRoutes.SETTINGS).forEach { route ->
                motionDestination(motion, route) {
                    LaunchedEffect(route) { navigateFromWorkflow(route) }
                }
            }
            motionDestination(motion, RescueAuthRoutes.SEARCH) {
                val visibility = this
                val searchFieldModifier = with(searchSharedScope) { searchFieldMotion(visibility) }
                val settled = transition.currentState == EnterExitState.Visible &&
                    transition.targetState == EnterExitState.Visible && !searchSharedScope.isTransitionActive
                SearchRoute(
                    searchFieldModifier = searchFieldModifier,
                    autoFocus = settled,
                    onBack = { navController.popBackStack() },
                    onResultClick = { result -> navController.navigate(result.vaultDestination()) },
                    modifier = Modifier.testTag("screen_search"),
                )
            }
            motionDestination(motion,
                route = RescueAuthRoutes.AUTHENTICATOR_PROVIDER,
                arguments = listOf(
                    androidx.navigation.navArgument("providerName") {
                        type = androidx.navigation.NavType.StringType
                    },
                ),
            ) { entry ->
                val providerName = entry.arguments?.getString("providerName").orEmpty()
                AuthenticatorRoute(
                    viewModel = authenticatorViewModel,
                    providerName = providerName,
                    isActive = backStackEntry?.id == entry.id,
                    floatingAddPosition = addPosition,
                    onAddRecovery = { id -> navController.navigate("authenticator/account/$id/recovery/add") },
                    onBack = { navController.popBackStack() },
                    onOpenAccount = { accountId ->
                        navController.navigate("authenticator/account-detail/$accountId")
                    },
                    onOpenRecovery = { accountId ->
                        navController.navigate("authenticator/account/$accountId/recovery")
                    },
                )
            }
            motionDestination(motion,
                route = RescueAuthRoutes.AUTHENTICATOR_ACCOUNT_DETAIL,
                arguments = listOf(
                    androidx.navigation.navArgument(RescueAuthRoutes.ARG_ACCOUNT_ID) {
                        type = androidx.navigation.NavType.StringType
                    },
                ),
            ) { entry ->
                val accountId = entry.arguments?.getString(RescueAuthRoutes.ARG_ACCOUNT_ID).orEmpty()
                AuthenticatorRoute(
                    viewModel = authenticatorViewModel,
                    accountId = accountId,
                    isActive = backStackEntry?.id == entry.id,
                    floatingAddPosition = addPosition,
                    onBack = { navController.popBackStack() },
                    onOpenRecovery = { id -> navController.navigate("authenticator/account/$id/recovery") },
                    onAddRecovery = { id -> navController.navigate("authenticator/account/$id/recovery/add") },
                )
            }
            listOf(
                RescueAuthRoutes.AUTHENTICATOR_ACCOUNT_RECOVERY,
                RescueAuthRoutes.AUTHENTICATOR_ACCOUNT_RECOVERY_ADD,
            ).forEach { recoveryRoute ->
                motionDestination(motion,
                    route = recoveryRoute,
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
                        onNavigate = navigateFromWorkflow,
                        openCreateOnStart = recoveryRoute == RescueAuthRoutes.AUTHENTICATOR_ACCOUNT_RECOVERY_ADD,
                        floatingAddPosition = addPosition,
                    )
                }
            }
            motionDestination(motion,
                route = "developer/category/{categoryType}",
                arguments = listOf(
                    androidx.navigation.navArgument("categoryType") {
                        type = androidx.navigation.NavType.StringType
                    },
                ),
            ) { entry ->
                val type = entry.arguments?.getString("categoryType")
                    ?.let { runCatching { DeveloperEntryType.valueOf(it) }.getOrNull() }
                DeveloperRoute(
                    viewModel = developerViewModel,
                    categoryType = type,
                    isActive = backStackEntry?.id == entry.id,
                    floatingAddPosition = addPosition,
                    onBack = { navController.popBackStack() },
                    onOpenEntry = { item ->
                        navController.navigate(RescueAuthRoutes.developerEntry(item.stableId))
                    },
                    onAddTypeSelected = { selected ->
                        navController.navigate(RescueAuthRoutes.developerAdd(selected.name))
                    },
                )
            }
            motionDestination(motion,
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
                    onNavigate = navigateFromWorkflow,
                )
            }
            motionDestination(motion,
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
                // Older create links encoded an empty edit id; they must not enter the guarded edit loader.
                val editId = entry.arguments?.getString(RescueAuthRoutes.ARG_EDIT_STABLE_ID)?.takeIf { it.isNotBlank() }
                val type = entry.arguments?.getString(RescueAuthRoutes.ARG_FORM_TYPE)
                    ?.let { runCatching { DeveloperFormType.valueOf(it) }.getOrNull() }
                    ?: DeveloperFormType.API_CREDENTIAL
                DeveloperFormRoute(
                    editingStableId = editId,
                    initialType = type,
                    onBack = { navController.popBackStack() },
                    onSaved = { navController.popBackStack() },
                    onNavigate = navigateFromWorkflow,
                )
            }
            motionDestination(motion, RescueAuthRoutes.SETTINGS_TRANSFER) {
                SettingsTransferScreen(
                    onBack = { navController.popBackStack() },
                    onExportClick = { navController.navigate(RescueAuthRoutes.EXPORT) },
                    onImportClick = { navController.navigate(RescueAuthRoutes.IMPORT) },
                    onLegacyImportClick = { navController.navigate(RescueAuthRoutes.LEGACY_IMPORT) },
                )
            }
            motionDestination(motion, RescueAuthRoutes.ABOUT) {
                AboutRoute(
                    versionName = versionName ?: "",
                    versionCode = BuildConfig.VERSION_CODE.toLong(),
                    encodedPublicKey = BuildConfig.UPDATE_PUBLIC_KEY,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.testTag(AboutTestTags.SCREEN),
                    onNavigate = navigateFromWorkflow,
                )
            }
            motionDestination(motion, RescueAuthRoutes.EXPORT) {
                ExportImportRoute(
                    mode = ExportImportMode.EXPORT,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.testTag("screen_export"),
                    onNavigate = navigateFromWorkflow,
                )
            }
            motionDestination(motion, RescueAuthRoutes.IMPORT) {
                ExportImportRoute(
                    mode = ExportImportMode.IMPORT,
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.testTag("screen_import"),
                    onNavigate = navigateFromWorkflow,
                )
            }
            motionDestination(motion, RescueAuthRoutes.LEGACY_IMPORT) {
                LegacyImportRoute(
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.testTag("screen_legacy_import"),
                    onNavigate = navigateFromWorkflow,
                )
            }
        }

        }
    }

    when (addSheetPage) {
        1 -> DeveloperAddSheet(
            onDismiss = { addSheetPage = null },
            onSelectType = { type -> navController.navigate(RescueAuthRoutes.developerAdd(type.name)) },
        )
    }
}
