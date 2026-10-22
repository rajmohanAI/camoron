package com.camoron.app.navigation

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.camoron.feature.camera.CameraScreen
import com.camoron.feature.editor.EditorScreen
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

@Serializable
data class EditorRoute(val uri: String)

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

    NavHost(navController = navController, startDestination = start) {
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
                onOpenSettings = { navController.navigate(SettingsRoute) },
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
        composable<EditorRoute> { entry ->
            EditorScreen(
                uri = entry.toRoute<EditorRoute>().uri,
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
            )
        }
        composable<CameraInfoRoute> {
            CameraInfoScreen(onBack = { navController.popBackStack() })
        }
    }
}
