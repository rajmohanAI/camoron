package com.camoron.app

import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.camoron.app.navigation.CamoronNavHost
import com.camoron.core.designsystem.CamoronTheme
import com.camoron.feature.onboarding.hasRequiredPermissions
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Camoron is always dark: light bar icons over transparent bars, never a white button bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            CamoronTheme {
                val consentAccepted by viewModel.disclaimerAccepted.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    // Wait for the stored consent before choosing a start screen, so the camera
                    // never opens ahead of the disclaimer.
                    consentAccepted?.let { accepted ->
                        CamoronNavHost(startOnCamera = accepted && hasRequiredPermissions(this@MainActivity))
                    }
                }
            }
        }
    }
}
