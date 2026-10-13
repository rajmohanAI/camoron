package com.camoron.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.DeviceProfile
import com.camoron.domain.model.LensFacing
import com.camoron.domain.model.StillAspect
import java.util.Locale

/** Shows what the capability scan found, so it is clear why a feature is or isn't offered. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraInfoScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.camera_info)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val profile = state.profile
            if (profile == null) {
                Text(stringResource(R.string.camera_info_loading))
            } else {
                Text(describeDevice(profile), style = MaterialTheme.typography.bodyMedium)
                profile.cameras.forEach { camera ->
                    Card {
                        Text(
                            describeCamera(camera),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Technical summary for support, deliberately left in English: the values are Camera2 terms that
 * do not translate meaningfully.
 */
internal fun describeDevice(profile: DeviceProfile): String = buildString {
    appendLine("Cameras: ${profile.cameras.size}")
    appendLine("Front and back at once: ${yesNo(profile.supportsConcurrentFrontBack)}")
    append("Shutter sound required by law: ${yesNo(profile.mustPlayShutterSound)}")
}

internal fun describeCamera(c: CameraCapabilities): String = buildString {
    val facing = when (c.lensFacing) {
        LensFacing.BACK -> "Back"
        LensFacing.FRONT -> "Front"
        LensFacing.EXTERNAL -> "External"
    }
    appendLine("$facing camera (id ${c.cameraId})")
    appendLine("Resolution: ${String.format(Locale.US, "%.1f", c.sensorSize.megapixels)} MP")
    appendLine("Hardware level: ${c.hardwareLevel}")
    c.equivalentFocalLengthMm?.let { appendLine("Focal length: ${it.toInt()} mm equivalent") }
    if (c.physicalCameraCount > 1) appendLine("Lenses behind this camera: ${c.physicalCameraCount}")
    c.zoomRange.let {
        val kind = if (c.zoomRatioRange != null) "zoom ratio" else "digital crop"
        appendLine("Zoom: ${String.format(Locale.US, "%.1f×–%.1f×", it.start, it.endInclusive)} ($kind)")
    }
    if (c.minFocusDistanceDiopters > 0f) {
        appendLine("Closest focus: ${(100f / c.minFocusDistanceDiopters).toInt()} cm")
    } else {
        appendLine("Focus: fixed")
    }
    appendLine("Macro: ${yesNo(c.supportsMacro)}")
    appendLine("Flash: ${yesNo(c.hasFlash)}")
    appendLine(
        "Photo shapes: " + c.stillAspects.joinToString {
            when (it) {
                StillAspect.FOUR_THREE -> "4:3"
                StillAspect.SIXTEEN_NINE -> "16:9"
            }
        },
    )
    appendLine("Manual ISO and shutter: ${yesNo(c.supportsManualSensor)}")
    appendLine("RAW: ${yesNo(c.supportsRaw)}")
    appendLine("Hardware noise reduction: ${yesNo(c.supportsHqNoiseReduction)}")
    appendLine("Hardware distortion correction: ${yesNo(c.supportsDistortionCorrection)}")
    if (c.videoFrameRates.isNotEmpty()) appendLine("Frame rates: ${c.videoFrameRates.sorted().joinToString()} fps")
    appendLine("Slow motion: ${yesNo(c.supportsHighSpeedVideo)}")
    appendLine("Face detection: ${yesNo(c.supportsFaceDetection)}")
    append("Maker extensions: ${c.extensions.joinToString().ifEmpty { "none" }}")
}

private fun yesNo(value: Boolean) = if (value) "yes" else "no"
