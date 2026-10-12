package com.camoron.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.camoron.feature.camera.CameraScreen
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

@Composable
fun CamoronNavHost(startOnCamera: Boolean) {
    val navController = rememberNavController()
    // The start destination is decided once; later consent changes navigate explicitly.
    val start: Any = remember { if (startOnCamera) CameraRoute else OnboardingRoute }

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
            CameraScreen(onOpenSettings = { navController.navigate(SettingsRoute) })
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
