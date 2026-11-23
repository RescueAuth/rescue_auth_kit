package com.rescueauth.v2.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rescueauth.v2.BuildConfig
import com.rescueauth.v2.R
import com.rescueauth.v2.domain.UndoRestoreOutcome
import com.rescueauth.v2.repository.VaultAccess
import com.rescueauth.v2.session.SecureSessionStateMachine
import com.rescueauth.v2.session.VaultSecurityStatus
import com.rescueauth.v2.ui.authenticator.AuthenticatorAddState
import com.rescueauth.v2.ui.authenticator.AuthenticatorRoute
import com.rescueauth.v2.ui.authenticator.AuthenticatorViewModel
import com.rescueauth.v2.ui.authenticator.RecoveryCodesRoute
import com.rescueauth.v2.ui.components.LocalRescueAuthSnackbarHostState
import com.rescueauth.v2.ui.components.RescueAuthFloatingAddOverlay
import com.rescueauth.v2.ui.components.RescueAuthNavigationBar
import com.rescueauth.v2.ui.components.UndoResult
import com.rescueauth.v2.ui.components.UndoSnackbarContract
import com.rescueauth.v2.ui.components.UndoSnackbarHost
import com.rescueauth.v2.ui.components.rememberFloatingAddPosition
import com.rescueauth.v2.ui.components.showInstantSnackbar
import com.rescueauth.v2.ui.developer.DeveloperDetailRoute
import com.rescueauth.v2.ui.developer.DeveloperFormRoute
import com.rescueauth.v2.ui.developer.DeveloperFormType
import com.rescueauth.v2.ui.developer.DeveloperListViewModel
import com.rescueauth.v2.ui.developer.DeveloperRoute
import com.rescueauth.v2.ui.developer.DeveloperUndoStore
import com.rescueauth.v2.ui.model.DeveloperEntryType
import com.rescueauth.v2.ui.navigation.HomePages
import com.rescueauth.v2.ui.navigation.RescueAuthNavigationMotion
import com.rescueauth.v2.ui.navigation.RescueAuthRoutes
import com.rescueauth.v2.ui.navigation.SearchFieldMotion
import com.rescueauth.v2.ui.navigation.TopLevelDestinations
import com.rescueauth.v2.ui.navigation.motionDestination
import com.rescueauth.v2.ui.navigation.rememberHomePageMotionState
import com.rescueauth.v2.ui.navigation.searchFieldMotion
import com.rescueauth.v2.ui.navigation.vaultDestination
import com.rescueauth.v2.ui.screens.about.AboutRoute
import com.rescueauth.v2.ui.screens.about.AboutTestTags
import com.rescueauth.v2.ui.screens.developer.DeveloperAddSheet
import com.rescueauth.v2.ui.screens.exportimport.ExportImportMode
import com.rescueauth.v2.ui.screens.exportimport.ExportImportRoute
import com.rescueauth.v2.ui.screens.legacyimport.LegacyImportRoute
import com.rescueauth.v2.ui.screens.settings.SettingsScreen
import com.rescueauth.v2.ui.screens.settings.SettingsTransferScreen
import com.rescueauth.v2.ui.search.SearchRoute
import com.rescueauth.v2.ui.theme.AddActionTokens
import com.rescueauth.v2.ui.theme.Spacing
import com.rescueauth.v2.ui.theme.ThemeColor
import com.rescueauth.v2.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
 * in a [Scaffold] with a bottom navigation dock, a shared [SnackbarHostState]
 * (via [LocalRescueAuthSnackbarHostState]) and a Navigation-Compose [NavHost].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RescueAuthApp(
    modifier: Modifier = Modifier,
    versionName: String? = null,
    startRoute: String? = null,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: ((ThemeMode) -> Unit)? = null,
    themeColor: ThemeColor = ThemeColor.DEFAULT,
    onThemeColorChange: ((ThemeColor) -> Unit)? = null,
    securityStatus: VaultSecurityStatus? = null,
    hasDeviceLock: Boolean = true,
    onSaveBackupPin: (suspend (String) -> Boolean)? = null,
    onRemoveBackupPin: (suspend () -> Boolean)? = null,
    onEnableSystemUnlock: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
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
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            homeScrollJob?.cancel()
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

    LaunchedEffect(startRoute) {
        val rootPage = TopLevelDestinations.all.indexOfFirst { it.route == startRoute }
        if (rootPage >= 0) homePageState.snapTo(rootPage)
        else if (startRoute != null) {
            navController.currentBackStackEntryFlow.first()
            navController.navigate(startRoute) { launchSingleTop = true }
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = currentDestination?.route == RescueAuthRoutes.HOME
    var composedHomes by remember { mutableIntStateOf(0) }
    var homeNavigationSettled by remember { mutableStateOf(false) }
    val authenticatorAddState = remember { AuthenticatorAddState() }
    val addPosition = rememberFloatingAddPosition()
    var addSheetPage by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(showBottomBar, homePageState.targetPage) { addSheetPage = null }
    BackHandler(enabled = showBottomBar && homePageState.targetPage != 0) { selectHomePage(0) }

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
    val undoVersion by DeveloperUndoStore.version.collectAsState()

    // Present Developer ordinary-delete Undo even when popped back to Search (where DeveloperRoute is inactive).
    LaunchedEffect(undoVersion, currentDestination?.route) {
        if (currentDestination?.route == RescueAuthRoutes.SEARCH && DeveloperUndoStore.pending != null) {
            val result = UndoSnackbarContract.showUndoSnackbar(
                snackbarHostState = snackbarHostState,
                message = context.getString(R.string.developer_entry_deleted_message),
                actionLabel = context.getString(R.string.undo_snackbar_action),
            )
            if (result == UndoResult.UNDO) {
                val snapshot = DeveloperUndoStore.consume() ?: return@LaunchedEffect
                val repo = VaultAccess.developerRepository() ?: return@LaunchedEffect
                when (runCatching { repo.restoreFromSnapshot(snapshot) }.getOrNull()) {
                    is UndoRestoreOutcome.Restored ->
                        snackbarHostState.showInstantSnackbar(context.getString(R.string.developer_entry_restored_message))
                    is UndoRestoreOutcome.Blocked ->
                        snackbarHostState.showInstantSnackbar(context.getString(R.string.developer_restore_blocked))
                    null -> Unit
                }
            } else {
                DeveloperUndoStore.clear()
            }
        }
    }

    val floatingAddVisibleOnShell =
        (showBottomBar && homePageState.targetPage != 2 && !migrationUi.scannerVisible) ||
            currentDestination?.route == RescueAuthRoutes.AUTHENTICATOR_PROVIDER ||
            currentDestination?.route == RescueAuthRoutes.AUTHENTICATOR_ACCOUNT_DETAIL ||
            currentDestination?.route == RescueAuthRoutes.AUTHENTICATOR_ACCOUNT_RECOVERY ||
            currentDestination?.route == RescueAuthRoutes.AUTHENTICATOR_ACCOUNT_RECOVERY_ADD ||
            currentDestination?.route == "developer/category/{categoryType}"

    CompositionLocalProvider(LocalRescueAuthSnackbarHostState provides snackbarHostState) {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                UndoSnackbarHost(
                    snackbarHostState = snackbarHostState,
                    modifier = if (floatingAddVisibleOnShell) {
                        Modifier.padding(bottom = AddActionTokens.size + Spacing.md)
                    } else {
                        Modifier
                    },
                )
            },
            bottomBar = if (showBottomBar || composedHomes > 0) {
                {
                    RescueAuthNavigationBar(
                        isSelected = { destination ->
                            TopLevelDestinations.all[homePageState.targetPage] == destination
                        },
                        pagePosition = { homePageState.dockPosition },
                        modifier = Modifier.zIndex(-1f),
                        enabled = showBottomBar && homeNavigationSettled,
                        onNavigate = { destination -> selectHomePage(TopLevelDestinations.all.indexOf(destination)) },
                    )
                }
            } else {
                {}
            },
        ) { padding ->
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
                                        themeColor = themeColor,
                                        onThemeColorChange = onThemeColorChange,
                                        securityStatus = securityStatus,
                                        hasDeviceLock = hasDeviceLock,
                                        onSaveBackupPin = onSaveBackupPin,
                                        onRemoveBackupPin = onRemoveBackupPin,
                                        onEnableSystemUnlock = onEnableSystemUnlock,
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
                    motionDestination(
                        motion,
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
                    motionDestination(
                        motion,
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
                            onOpenAccount = { mergedDestId ->
                                navController.navigate("authenticator/account-detail/$mergedDestId") {
                                    popUpTo(RescueAuthRoutes.AUTHENTICATOR_ACCOUNT_DETAIL) { inclusive = true }
                                }
                            },
                            onOpenRecovery = { id -> navController.navigate("authenticator/account/$id/recovery") },
                            onAddRecovery = { id -> navController.navigate("authenticator/account/$id/recovery/add") },
                        )
                    }
                    listOf(
                        RescueAuthRoutes.AUTHENTICATOR_ACCOUNT_RECOVERY,
                        RescueAuthRoutes.AUTHENTICATOR_ACCOUNT_RECOVERY_ADD,
                    ).forEach { recoveryRoute ->
                        motionDestination(
                            motion,
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
                    motionDestination(
                        motion,
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
                    motionDestination(
                        motion,
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
                    motionDestination(
                        motion,
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
    }

    when (addSheetPage) {
        1 -> DeveloperAddSheet(
            onDismiss = { addSheetPage = null },
            onSelectType = { type -> navController.navigate(RescueAuthRoutes.developerAdd(type.name)) },
        )
    }
}
