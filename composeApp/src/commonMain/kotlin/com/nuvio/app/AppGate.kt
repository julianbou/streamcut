package com.nuvio.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.DeviceSessionRegistration
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.ProfileSettingsSync
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.auth.AuthScreen
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.CollectionSyncService
import com.nuvio.app.features.downloads.DownloadsRepository
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.membership.MemberAccessRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktSettingsRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingEnrichmentCache
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.navigation.AppRoute
import androidx.compose.animation.core.MutableTransitionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Loads everything the app reads out of local storage before the first screen
 * is drawn. Named for the profile that used to scope that storage; the scope is
 * now a constant (StreamCut removed profiles), but the warm-up is still the step
 * between the gate and Main.
 */
internal suspend fun warmAppRepositories() {
    withContext(Dispatchers.Default) {
        AddonRepository.initialize()
        CollectionRepository.initialize()
        val enabledAddons = AddonRepository.uiState.value.addons.enabledAddons()
        ContinueWatchingPreferencesRepository.ensureLoaded()
        DownloadsRepository.ensureLoaded()
        EpisodeReleaseNotificationsRepository.ensureLoaded()
        HomeCatalogSettingsRepository.syncCatalogs(enabledAddons)
        LibraryRepository.ensureLoaded()
        P2pSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.ensureLoaded()
        TraktAuthRepository.ensureLoaded()
        TraktSettingsRepository.ensureLoaded()
        WatchedRepository.ensureLoaded()
        WatchProgressRepository.ensureLoaded()
        ContinueWatchingEnrichmentCache.warm(ProfileScopedKey.ScopeId)
        CollectionSyncService.startObserving()
        ProfileSettingsSync.startObserving()
    }
}

private enum class AppGateScreen {
    Loading,
    Auth,
    Main,
}

@Composable
internal fun AppGate(
    initialTab: AppScreenTab,
    initialRoute: AppRoute,
    useNativeNavigation: Boolean,
    useNativeTabBar: Boolean,
    useTabletFloatingTabBar: Boolean,
    ownsAppRuntime: Boolean,
    bypassAppGate: Boolean,
    renderMainContent: Boolean,
    onNavigate: ((AppRoute, launchSingleTop: Boolean) -> Unit)?,
    onGoBack: (() -> Unit)?,
    onReplace: ((AppRoute) -> Unit)?,
    onActivate: ((AppScreenTab) -> Unit)?,
    onAppReady: ((Boolean) -> Unit)?,
    onMainContentMountChanged: ((Boolean) -> Unit)?,
    onMainContentVisibleChanged: ((Boolean) -> Unit)?,
    onTabTitles: ((home: String, search: String, library: String, settings: String) -> Unit)?,
    appGateController: AppGateController?,
) {
    if (bypassAppGate) {
        MainAppContent(
            initialTab = initialTab,
            initialRoute = initialRoute,
            useNativeNavigation = useNativeNavigation,
            useNativeTabBar = useNativeTabBar,
            useTabletFloatingTabBar = useTabletFloatingTabBar,
            ownsAppRuntime = ownsAppRuntime,
            showLaunchOverlay = appGateController == null,
            onNavigate = onNavigate,
            onGoBack = onGoBack,
            onReplace = onReplace,
            onActivate = onActivate,
            onTabTitles = onTabTitles,
            appGateController = appGateController,
            onRootContentReady = appGateController?.let { controller ->
                controller::reportMainContentReady
            },
        )
        return
    }

    LaunchedEffect(Unit) {
        if (!ownsAppRuntime) return@LaunchedEffect
        AuthRepository.initialize()
    }

    LaunchedEffect(Unit) {
        if (!ownsAppRuntime) return@LaunchedEffect
        NetworkStatusRepository.ensureStarted()
        MemberAccessRepository.ensureStarted()
    }

    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val signInRequested by AuthRepository.signInRequested.collectAsStateWithLifecycle()

    LaunchedEffect(authState) {
        if (!ownsAppRuntime) return@LaunchedEffect
        DeviceSessionRegistration.registerIfAuthenticated(force = true)
    }

    var gateScreen by rememberSaveable { mutableStateOf(AppGateScreen.Loading.name) }
    var warmingUp by remember { mutableStateOf(false) }
    var mainContentStarted by rememberSaveable { mutableStateOf(false) }
    val externalMainContentReady = if (!renderMainContent && appGateController != null) {
        val ready by appGateController.mainContentReady.collectAsStateWithLifecycle()
        ready
    } else {
        false
    }

    LaunchedEffect(gateScreen, onAppReady) {
        if (gateScreen != AppGateScreen.Main.name) {
            onAppReady?.invoke(false)
        }
    }

    LaunchedEffect(gateScreen, renderMainContent, onMainContentMountChanged) {
        if (renderMainContent) return@LaunchedEffect
        when (gateScreen) {
            AppGateScreen.Main.name -> {
                mainContentStarted = true
                onMainContentMountChanged?.invoke(true)
            }
            else -> {
                mainContentStarted = false
                appGateController?.reportMainContentReady(false)
                onMainContentMountChanged?.invoke(false)
            }
        }
    }

    LaunchedEffect(
        renderMainContent,
        gateScreen,
        externalMainContentReady,
        onMainContentVisibleChanged,
    ) {
        if (!renderMainContent) {
            onMainContentVisibleChanged?.invoke(
                gateScreen == AppGateScreen.Main.name && externalMainContentReady,
            )
        }
    }

    /**
     * Warms local storage, then hands over to the app.
     *
     * [syncOnEnter] pulls the account's data first; it is skipped when the
     * gate is being passed on cached data alone, which is the offline case
     * below.
     */
    suspend fun enterMainGate(syncOnEnter: Boolean) {
        if (warmingUp) return
        warmingUp = true
        if (!renderMainContent) {
            appGateController?.beginContentReload()
        }
        try {
            // The gate opens even when warming or the first pull fails:
            // everything it loads has a local fallback, and refusing to
            // start because the network is down is the one outcome the
            // user cannot work around.
            runCatching {
                warmAppRepositories()
                if (syncOnEnter) {
                    withContext(Dispatchers.Default) {
                        SyncManager.pullAllForProfile(ProfileScopedKey.ScopeId)
                    }
                }
            }
            // ...but not when this effect was cancelled while it ran: the
            // auth state that sent us here is no longer the current one,
            // and runCatching swallows the cancellation that says so.
            currentCoroutineContext().ensureActive()
            gateScreen = AppGateScreen.Main.name
        } finally {
            // In a finally so a cancelled warm-up cannot leave the flag
            // stuck true, which would wedge the app on the loading screen
            // for the rest of the session.
            warmingUp = false
        }
    }

    // Keyed on the sign-out signal as well as the state: signing out while
    // already unauthenticated writes the state value that is already
    // current, so the state alone would never wake this effect and the gate
    // would stay on Main with the account's data wiped out from under it.
    LaunchedEffect(authState, signInRequested) {
        when (authState) {
            is AuthState.Loading -> {
                gateScreen = AppGateScreen.Loading.name
            }
            is AuthState.Unauthenticated -> {
                // A session that lapsed -- refresh failed, the machine is
                // offline, the token expired -- must still reach the library
                // on this machine rather than a sign-in screen it may have no
                // network to complete. A machine that has never had an account
                // has nothing to show, so it goes to Auth. So does an explicit
                // request for the sign-in screen: a sign-out, or the button in
                // account settings, which is a lapsed session's way back.
                if (AuthRepository.hasEverSignedIn && !signInRequested) {
                    if (gateScreen != AppGateScreen.Main.name) enterMainGate(syncOnEnter = false)
                } else {
                    gateScreen = AppGateScreen.Auth.name
                }
            }
            is AuthState.Authenticated -> {
                if (gateScreen == AppGateScreen.Loading.name || gateScreen == AppGateScreen.Auth.name) {
                    enterMainGate(syncOnEnter = true)
                }
            }
        }
    }

    val launchOverlayVisible =
        !renderMainContent &&
            gateScreen == AppGateScreen.Main.name &&
            !externalMainContentReady
    val launchOverlayState = remember {
        MutableTransitionState(launchOverlayVisible)
    }
    launchOverlayState.targetState = launchOverlayVisible

    LaunchedEffect(
        renderMainContent,
        gateScreen,
        externalMainContentReady,
        launchOverlayState.currentState,
        launchOverlayState.isIdle,
        onAppReady,
    ) {
        if (renderMainContent) return@LaunchedEffect
        val overlaysHidden = launchOverlayState.isIdle && !launchOverlayState.currentState
        onAppReady?.invoke(
            gateScreen == AppGateScreen.Main.name &&
                externalMainContentReady &&
                overlaysHidden,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = gateScreen,
            label = "app_gate",
            transitionSpec = {
                (fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.94f))
                    .togetherWith(fadeOut(tween(250)))
            },
        ) { currentGate ->
            when (currentGate) {
                AppGateScreen.Auth.name -> {
                    AuthScreen(modifier = Modifier.fillMaxSize())
                }
                AppGateScreen.Main.name -> {
                    if (renderMainContent) {
                        MainAppContent(
                            initialTab = initialTab,
                            initialRoute = initialRoute,
                            useNativeNavigation = useNativeNavigation,
                            useNativeTabBar = useNativeTabBar,
                            useTabletFloatingTabBar = useTabletFloatingTabBar,
                            ownsAppRuntime = ownsAppRuntime,
                            showLaunchOverlay = true,
                            onNavigate = onNavigate,
                            onGoBack = onGoBack,
                            onReplace = onReplace,
                            onActivate = onActivate,
                            onTabTitles = onTabTitles,
                            appGateController = appGateController,
                            onRootContentReady = { ready ->
                                onAppReady?.invoke(
                                    ready && gateScreen == AppGateScreen.Main.name,
                                )
                            },
                        )
                    }
                }
                // Loading, and any gate name saved by a build that still had
                // profiles: the state is a rememberSaveable string, so it can
                // outlive an upgrade. The auth effect corrects it; until then
                // this is the launch screen, not a blank window.
                else -> {
                    AppLaunchOverlay(modifier = Modifier.fillMaxSize())
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visibleState = launchOverlayState,
            enter = fadeIn(tween(400)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.fillMaxSize(),
        ) {
            AppLaunchOverlay(modifier = Modifier.fillMaxSize())
        }
    }
}
