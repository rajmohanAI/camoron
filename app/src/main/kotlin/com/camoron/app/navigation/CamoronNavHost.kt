package com.camoron.app.navigation

import android.app.Activity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.camoron.app.R
import com.camoron.core.designsystem.Spotlight
import com.camoron.core.designsystem.SpotlightNavBar
import com.camoron.core.designsystem.SpotlightNavHandle
import com.camoron.core.designsystem.SpotlightNavItem
import com.camoron.feature.camera.CameraScreen
import com.camoron.feature.editor.AiToolsScreen
import com.camoron.feature.editor.EditHomeScreen
import com.camoron.feature.editor.EditorScreen
import com.camoron.feature.editor.EditorTab
import com.camoron.feature.editor.GalleryScreen
import com.camoron.feature.editor.ViewerScreen
import com.camoron.feature.onboarding.OnboardingScreen
import com.camoron.feature.settings.CameraInfoScreen
import com.camoron.feature.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
object OnboardingRoute

@Serializable
object CameraRoute

@Serializable
object SettingsRoute

@Serializable
object CameraInfoRoute

@Serializable
data class ViewerRoute(val uri: String)

/** [tab] names an [EditorTab] to start on, when a tool was chosen first (from AI tools). */
@Serializable
data class EditorRoute(val uri: String, val tab: String? = null)

@Serializable
object GalleryRoute

@Serializable
object EditHomeRoute

@Serializable
object AiToolsRoute

/**
 * [editUri] is set when another app (a gallery, say) asked Camoron to edit one of its photos and
 * the disclaimer has been accepted; the editor then opens straight away.
 */
@Composable
fun CamoronNavHost(startOnCamera: Boolean, editUri: String? = null) {
    val navController = rememberNavController()
    val activity = LocalContext.current as? Activity
    // The start destination is decided once; later consent changes navigate explicitly.
    val start: Any = remember {
        when {
            editUri != null -> EditorRoute(editUri)
            startOnCamera -> CameraRoute
            else -> OnboardingRoute
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTab = TopLevelDestination.of(backStackEntry?.destination)
    // The tab screens keep their last lines clear of the floating bar.
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val underBar = TAB_BAR_SPACE + navInset

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = start,
            // Quick, short moves between screens, like the phone's own apps; the library default is a slow 0.7 s fade.
            enterTransition = { enter(AnimatedContentTransitionScope.SlideDirection.Start) },
            exitTransition = { fadeOut(tween(SCREEN_MS)) },
            popEnterTransition = { fadeIn(tween(SCREEN_MS)) },
            popExitTransition = { exit(AnimatedContentTransitionScope.SlideDirection.End) },
        ) {
            composable<OnboardingRoute> {
                OnboardingScreen(
                    onFinished = {
                        navController.navigate(CameraRoute) {
                            popUpTo<OnboardingRoute> { inclusive = true }
                        }
                    },
                )
            }
            composable<CameraRoute> {
                CameraScreen(
                    onOpenSettings = { navController.navigateToTab(TopLevelDestination.PROFILE) },
                    onOpenPhoto = { navController.navigate(ViewerRoute(it)) },
                )
            }
            composable<ViewerRoute> { entry ->
                ViewerScreen(
                    startUri = entry.toRoute<ViewerRoute>().uri,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(EditorRoute(it)) },
                )
            }
            composable<GalleryRoute> {
                GalleryScreen(onOpenPhoto = { navController.navigate(ViewerRoute(it)) }, bottomPadding = underBar)
            }
            composable<EditHomeRoute> {
                EditHomeScreen(onEdit = { navController.navigate(EditorRoute(it)) }, bottomPadding = underBar)
            }
            composable<AiToolsRoute> {
                AiToolsScreen(
                    onOpenTool = { uri, tab -> navController.navigate(EditorRoute(uri, tab.name)) },
                    bottomPadding = underBar,
                )
            }
            composable<EditorRoute> { entry ->
                val route = entry.toRoute<EditorRoute>()
                EditorScreen(
                    uri = route.uri,
                    startTab = route.tab?.let { name -> EditorTab.entries.firstOrNull { it.name == name } },
                    // Opened for another app, closing the editor goes back to that app.
                    onClose = { if (!navController.popBackStack()) activity?.finish() },
                    // The new copy opens in the viewer, replacing the editor and the viewer it was opened from.
                    onSaved = { saved ->
                        val fromViewer = navController.previousBackStackEntry?.destination?.hasRoute<ViewerRoute>() == true
                        navController.navigate(ViewerRoute(saved)) {
                            if (fromViewer) popUpTo<ViewerRoute> { inclusive = true } else popUpTo<EditorRoute> { inclusive = true }
                        }
                    },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenCameraInfo = { navController.navigate(CameraInfoRoute) },
                    // Settings pads for the system bars itself; it needs only the bar's own height.
                    bottomPadding = TAB_BAR_SPACE,
                )
            }
            composable<CameraInfoRoute> {
                CameraInfoScreen(onBack = { navController.popBackStack() })
            }
        }
        CamoronTabBar(
            current = currentTab,
            onSelect = navController::navigateToTab,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Switches tabs the usual way: back to the camera at the root, keeping each tab's place (scroll,
 * open screen) for when it is picked again.
 */
private fun NavController.navigateToTab(tab: TopLevelDestination) {
    navigate(tab.route) {
        popUpTo<CameraRoute> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * The spotlight bar along the bottom. It is shown on the tab screens (Gallery, Edit, AI tools,
 * Profile) and hidden inside them (viewer, editor). On the camera it folds away to a small glowing
 * handle so the shutter stays the main control; tapping the handle brings the bar up over the
 * camera until a tab is picked or the camera is tapped.
 */
@Composable
private fun CamoronTabBar(
    current: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    var openOverCamera by remember { mutableStateOf(false) }
    LaunchedEffect(current) { openOverCamera = false }
    // The bar keeps showing the last tab while it slides away.
    var shown by remember { mutableStateOf(TopLevelDestination.CAMERA) }
    if (current != null) shown = current
    val showBar = current != null && (current != TopLevelDestination.CAMERA || openOverCamera)
    val showHandle = current == TopLevelDestination.CAMERA && !openOverCamera
    val items = TopLevelDestination.entries.map { SpotlightNavItem(it.icon, stringResource(it.label)) }

    Box(modifier) {
        AnimatedVisibility(openOverCamera, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { openOverCamera = false },
            )
        }
        AnimatedVisibility(
            visible = showBar,
            enter = fadeIn(tween(BAR_MS)) + slideInVertically(tween(BAR_MS)) { it / 2 },
            exit = fadeOut(tween(BAR_MS)) + slideOutVertically(tween(BAR_MS)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SpotlightNavBar(
                items = items,
                selectedIndex = shown.ordinal,
                onSelect = { index ->
                    val tab = TopLevelDestination.entries[index]
                    if (tab == current) openOverCamera = false else onSelect(tab)
                },
                modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = BAR_MARGIN),
            )
        }
        AnimatedVisibility(
            visible = showHandle,
            enter = fadeIn(tween(BAR_MS)),
            exit = fadeOut(tween(BAR_MS)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            SpotlightNavHandle(
                onClick = { openOverCamera = true },
                label = stringResource(R.string.tab_bar_show),
                modifier = Modifier.navigationBarsPadding().padding(bottom = 4.dp),
            )
        }
    }
}

private const val BAR_MS = 240
private val BAR_MARGIN = 12.dp

/** How much of a tab screen's bottom the floating bar covers, above the system navigation bar. */
private val TAB_BAR_SPACE = Spotlight.BarHeight + BAR_MARGIN * 2

private const val SCREEN_MS = 220

/** A new screen fades in while sliding a short way in from the side. */
private fun AnimatedContentTransitionScope<*>.enter(towards: AnimatedContentTransitionScope.SlideDirection): EnterTransition =
    fadeIn(tween(SCREEN_MS)) + slideIntoContainer(towards, tween(SCREEN_MS, easing = FastOutSlowInEasing)) { it / 10 }

/** Going back, the screen fades out while sliding a short way back out. */
private fun AnimatedContentTransitionScope<*>.exit(towards: AnimatedContentTransitionScope.SlideDirection): ExitTransition =
    fadeOut(tween(SCREEN_MS)) + slideOutOfContainer(towards, tween(SCREEN_MS, easing = FastOutSlowInEasing)) { it / 10 }
