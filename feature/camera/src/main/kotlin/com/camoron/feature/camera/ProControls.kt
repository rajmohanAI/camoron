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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/** The Pro dials there are; one at a time is open, so the preview stays clear. */
private enum class ProDial { ISO, SHUTTER, EV, WB }

/**
 * Pro mode dials. Every control is driven by [capabilities]: anything the hardware cannot honour
 * is shown disabled with a reason rather than silently ignored.
 *
 * Each dial is a chip showing its current value; tapping one opens just that slider, and tapping
 * it again (or the hide button) closes it, so the controls never cover more than a strip of the
 * preview.
 */
@Composable
internal fun ProControls(
    capabilities: CameraCapabilities,
    settings: ManualSettings,
    liveExposure: LiveExposure?,
    rawEnabled: Boolean,
    /** Read lazily, so a new histogram only redraws the histogram, not the whole screen. */
    histogram: () -> Histogram?,
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
) {
    val unsupported = stringResource(R.string.unsupported_on_camera)
    val auto = stringResource(R.string.control_auto)
    var open by rememberSaveable { mutableStateOf<ProDial?>(null) }

    val isoRange = capabilities.isoRange
    val manualIso = settings.iso
    val isoShown = manualIso ?: liveExposure?.iso
    val isoText = when {
        manualIso != null -> manualIso.toString()
        isoShown != null -> "$auto · $isoShown"
        else -> auto
    }
    val shutterRange = capabilities.exposureTimeRangeNs
    val manualShutter = settings.exposureTimeNs
    val shutterShown = manualShutter ?: liveExposure?.exposureTimeNs
    val shutterText = when {
        manualShutter != null -> ExposureScale.formatShutter(manualShutter)
        shutterShown != null -> "$auto · ${ExposureScale.formatShutter(shutterShown)}"
        else -> auto
    }
    val evRange = capabilities.exposureCompensationRange
    val evSupported = capabilities.supports(ManualControl.EXPOSURE_COMPENSATION)
    val evText = ExposureScale.formatEv(settings.exposureCompensation, capabilities.exposureCompensationStep)
    val wbText = when (val wb = settings.whiteBalance) {
        WhiteBalance.Auto -> stringResource(R.string.wb_auto)
        is WhiteBalance.Preset -> stringResource(wb.preset.label())
        is WhiteBalance.Kelvin -> "${wb.temperature} K"
    }

    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.weight(1f).height(32.dp)) {
                when {
                    rawEnabled && capabilities.supportsRaw -> Text(
                        stringResource(R.string.histogram_off_with_raw),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    else -> HistogramView(histogram, Modifier.fillMaxSize())
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
            if (open != null) {
                IconButton(onClick = { open = null }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.cd_hide_dials))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                ProDial.ISO to "${stringResource(R.string.control_iso)} $isoText",
                ProDial.SHUTTER to "${stringResource(R.string.control_shutter)} $shutterText",
                ProDial.EV to "${stringResource(R.string.control_ev)} $evText",
                ProDial.WB to "${stringResource(R.string.control_wb)} $wbText",
            ).forEach { (dial, text) ->
                FilterChip(
                    selected = open == dial,
                    onClick = { open = if (open == dial) null else dial },
                    label = { Text(text, maxLines = 1) },
                )
            }
        }

        when (open) {
            ProDial.ISO -> LabeledSlider(
                label = stringResource(R.string.control_iso),
                valueText = isoText,
                value = if (isoRange != null && isoShown != null) ExposureScale.toSlider(isoShown, isoRange) else 0f,
                onValueChange = { f -> isoRange?.let { viewModel.setIso(ExposureScale.fromSlider(f, it)) } },
                enabled = capabilities.supports(ManualControl.ISO),
                disabledReason = unsupported,
                onAuto = { viewModel.setIso(null) }.takeIf { settings.iso != null },
            )
            ProDial.SHUTTER -> LabeledSlider(
                label = stringResource(R.string.control_shutter),
                valueText = shutterText,
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
            ProDial.EV -> LabeledSlider(
                label = stringResource(R.string.control_ev),
                valueText = evText,
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
            ProDial.WB -> WhiteBalanceControls(capabilities, settings.whiteBalance, viewModel::setWhiteBalance, unsupported)
            null -> Unit
        }
    }
}

/**
 * Portrait's exposure: one slim slider of exposure compensation, so a face against a bright or
 * dark background can be made brighter or darker while auto exposure keeps doing the rest.
 */
@Composable
internal fun ExposureControl(
    capabilities: CameraCapabilities,
    steps: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.cd_exposure)
    val range = capabilities.exposureCompensationRange ?: return
    if (!capabilities.supports(ManualControl.EXPOSURE_COMPENSATION) || range.last <= range.first) return
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Exposure, contentDescription = null, modifier = Modifier.size(20.dp))
        Slider(
            value = (steps - range.first).toFloat() / (range.last - range.first),
            onValueChange = { f -> onChange(range.first + (f * (range.last - range.first)).roundToInt()) },
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            modifier = Modifier.weight(1f).semantics { contentDescription = description },
        )
        Text(
            ExposureScale.formatEv(steps, capabilities.exposureCompensationStep),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(44.dp),
        )
        TextButton(onClick = { onChange(0) }, enabled = steps != 0) { Text(stringResource(R.string.exposure_reset)) }
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
private fun HistogramView(histogramOf: () -> Histogram?, modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    // Read while drawing, so each new histogram only redraws this canvas.
    Canvas(modifier) {
        val histogram = histogramOf() ?: return@Canvas
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
