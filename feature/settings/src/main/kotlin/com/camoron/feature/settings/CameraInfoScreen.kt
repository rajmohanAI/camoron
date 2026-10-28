package com.camoron.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.camoron.core.common.DiagnosticLog
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
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.camera_info)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { shareLog(context, state.profile) }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share_log))
                    }
                },
            )
        },
    ) { padding ->
        // Selectable, so a tester can copy the details (and any recent problems) into a message.
        SelectionContainer(Modifier.padding(padding)) {
            Column(
                Modifier
                    .fillMaxSize()
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
                val log = DiagnosticLog.entries()
                if (log.isNotEmpty()) {
                    Card {
                        Text(
                            "Camera log (newest first; problems marked !)\n" + log.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Sends the detailed log and the camera details to any app the tester picks (mail, chat, notes). */
private fun shareLog(context: Context, profile: DeviceProfile?) {
    val text = buildString {
        append(DiagnosticLog.report())
        if (profile != null) {
            appendLine()
            appendLine(describeDevice(profile))
            profile.cameras.forEach { appendLine(); appendLine(describeCamera(it)) }
        }
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Camoron log")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.share_log_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
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
    val macroLens = c.macroLensZoom?.let { String.format(Locale.US, " (close-focusing lens at %.1f×)", it) }.orEmpty()
    appendLine("Macro: ${yesNo(c.offersMacro)}$macroLens")
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
