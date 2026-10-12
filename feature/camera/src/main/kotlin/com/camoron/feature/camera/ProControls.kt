package com.camoron.feature.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.camoron.core.designsystem.LabeledSlider
import com.camoron.domain.model.CameraCapabilities
import com.camoron.domain.model.Histogram
import com.camoron.domain.model.LiveExposure
import com.camoron.domain.model.ManualControl
import com.camoron.domain.model.ManualSettings
import com.camoron.domain.model.WhiteBalance
import com.camoron.domain.model.WhiteBalancePreset
import kotlin.math.roundToInt

/**
 * Pro mode dials. Every control is driven by [capabilities]: anything the hardware cannot honour
 * is shown disabled with a reason rather than silently ignored.
 */
@Composable
internal fun ProControls(
    capabilities: CameraCapabilities,
    settings: ManualSettings,
    liveExposure: LiveExposure?,
    rawEnabled: Boolean,
    histogram: Histogram?,
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
) {
    val unsupported = stringResource(R.string.unsupported_on_camera)
    val auto = stringResource(R.string.control_auto)

    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.weight(1f).height(48.dp)) {
                when {
                    rawEnabled && capabilities.supportsRaw -> Text(
                        stringResource(R.string.histogram_off_with_raw),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    histogram != null -> HistogramView(histogram, Modifier.fillMaxSize())
                }
            }
            FilterChip(
                selected = rawEnabled && capabilities.supportsRaw,
                onClick = { viewModel.setRawEnabled(!rawEnabled) },
                enabled = capabilities.supportsRaw,
                label = {
                    Text(stringResource(if (capabilities.supportsRaw) R.string.raw_label else R.string.raw_unsupported))
                },
            )
        }

        // ISO
        val isoRange = capabilities.isoRange
        val manualIso = settings.iso
        val isoShown = manualIso ?: liveExposure?.iso
        LabeledSlider(
            label = stringResource(R.string.control_iso),
            valueText = when {
                manualIso != null -> manualIso.toString()
                isoShown != null -> "$auto · $isoShown"
                else -> auto
            },
            value = if (isoRange != null && isoShown != null) ExposureScale.toSlider(isoShown, isoRange) else 0f,
            onValueChange = { f -> isoRange?.let { viewModel.setIso(ExposureScale.fromSlider(f, it)) } },
            enabled = capabilities.supports(ManualControl.ISO),
            disabledReason = unsupported,
            onAuto = { viewModel.setIso(null) }.takeIf { settings.iso != null },
        )

        // Shutter speed
        val shutterRange = capabilities.exposureTimeRangeNs
        val manualShutter = settings.exposureTimeNs
        val shutterShown = manualShutter ?: liveExposure?.exposureTimeNs
        LabeledSlider(
            label = stringResource(R.string.control_shutter),
            valueText = when {
                manualShutter != null -> ExposureScale.formatShutter(manualShutter)
                shutterShown != null -> "$auto · ${ExposureScale.formatShutter(shutterShown)}"
                else -> auto
            },
            value = if (shutterRange != null && shutterShown != null) {
                ExposureScale.toSlider(shutterShown, shutterRange)
            } else {
                0f
            },
            onValueChange = { f -> shutterRange?.let { viewModel.setShutter(ExposureScale.fromSlider(f, it)) } },
            enabled = capabilities.supports(ManualControl.SHUTTER_SPEED),
            disabledReason = unsupported,
            onAuto = { viewModel.setShutter(null) }.takeIf { settings.exposureTimeNs != null },
        )

        // Exposure compensation (only meaningful while auto-exposure runs)
        val evRange = capabilities.exposureCompensationRange
        val evSupported = capabilities.supports(ManualControl.EXPOSURE_COMPENSATION)
        LabeledSlider(
            label = stringResource(R.string.control_ev),
            valueText = ExposureScale.formatEv(settings.exposureCompensation, capabilities.exposureCompensationStep),
            value = if (evRange != null) {
                (settings.exposureCompensation - evRange.first).toFloat() / (evRange.last - evRange.first)
            } else {
                0.5f
            },
            onValueChange = { f ->
                evRange?.let { viewModel.setExposureCompensation(it.first + (f * (it.last - it.first)).roundToInt()) }
            },
            enabled = evSupported && !settings.isManualExposure,
            disabledReason = if (evSupported) stringResource(R.string.ev_off_in_manual) else unsupported,
            steps = evRange?.let { (it.last - it.first - 1).coerceAtLeast(0) } ?: 0,
        )

        WhiteBalanceControls(capabilities, settings.whiteBalance, viewModel::setWhiteBalance, unsupported)
    }
}

@Composable
private fun WhiteBalanceControls(
    capabilities: CameraCapabilities,
    current: WhiteBalance,
    onChange: (WhiteBalance) -> Unit,
    unsupported: String,
) {
    val presets = WhiteBalancePreset.entries.filter { it == WhiteBalancePreset.AUTO || it in capabilities.whiteBalancePresets }
    val supported = capabilities.supports(ManualControl.WHITE_BALANCE)

    Text(
        if (supported) stringResource(R.string.control_wb) else "${stringResource(R.string.control_wb)}: $unsupported",
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    if (!supported) return

    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            val selected = when (current) {
                WhiteBalance.Auto -> preset == WhiteBalancePreset.AUTO
                is WhiteBalance.Preset -> current.preset == preset
                is WhiteBalance.Kelvin -> false
            }
            FilterChip(
                selected = selected,
                onClick = { onChange(if (preset == WhiteBalancePreset.AUTO) WhiteBalance.Auto else WhiteBalance.Preset(preset)) },
                label = { Text(stringResource(preset.label())) },
            )
        }
        if (capabilities.supportsManualWhiteBalance) {
            FilterChip(
                selected = current is WhiteBalance.Kelvin,
                onClick = { onChange(WhiteBalance.Kelvin(DEFAULT_KELVIN)) },
                label = { Text(stringResource(R.string.wb_kelvin)) },
            )
        }
    }

    if (current is WhiteBalance.Kelvin) {
        LabeledSlider(
            label = stringResource(R.string.wb_kelvin),
            valueText = "${current.temperature} K",
            value = (current.temperature - WhiteBalance.Kelvin.MIN).toFloat() / (WhiteBalance.Kelvin.MAX - WhiteBalance.Kelvin.MIN),
            onValueChange = { f ->
                val kelvin = WhiteBalance.Kelvin.MIN + (f * (WhiteBalance.Kelvin.MAX - WhiteBalance.Kelvin.MIN)).roundToInt()
                onChange(WhiteBalance.Kelvin((kelvin / KELVIN_STEP) * KELVIN_STEP))
            },
        )
    }
}

/** Luminance histogram drawn as bars, scaled to the tallest bucket. */
@Composable
private fun HistogramView(histogram: Histogram, modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    Canvas(modifier) {
        val bins = histogram.bins
        if (histogram.max == 0 || bins.isEmpty()) return@Canvas
        val barWidth = size.width / bins.size
        bins.forEachIndexed { index, count ->
            val barHeight = size.height * count / histogram.max
            drawRect(
                color = barColor,
                topLeft = Offset(index * barWidth, size.height - barHeight),
                size = Size(barWidth, barHeight),
            )
        }
    }
}

private fun WhiteBalancePreset.label(): Int = when (this) {
    WhiteBalancePreset.AUTO -> R.string.wb_auto
    WhiteBalancePreset.DAYLIGHT -> R.string.wb_daylight
    WhiteBalancePreset.CLOUDY -> R.string.wb_cloudy
    WhiteBalancePreset.SHADE -> R.string.wb_shade
    WhiteBalancePreset.INCANDESCENT -> R.string.wb_incandescent
    WhiteBalancePreset.FLUORESCENT -> R.string.wb_fluorescent
}

private const val DEFAULT_KELVIN = 5500
private const val KELVIN_STEP = 50
