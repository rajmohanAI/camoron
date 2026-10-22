package com.camoron.feature.camera

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/**
 * The "glass pro" look of the camera screen: frosted, translucent controls floating over the full
 * preview, white type and a single warm accent for whatever is on.
 */
internal object Glass {
    /** The one accent colour: active modes, zoom, toggles that are on. */
    val Accent = Color(0xFFFFC94A)
    val Recording = Color(0xFFFF453A)
    private val Tint = Color(0xFF0E0F13)
    private val Stroke = Color.White.copy(alpha = 0.16f)

    /** Frosted fill: darker at the bottom, with a faint highlight along the top edge like real glass. */
    val Fill = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.10f).compositeOver(Tint.copy(alpha = 0.42f)), Tint.copy(alpha = 0.52f)),
    )

    fun Modifier.glass(shape: Shape = CircleShape): Modifier =
        clip(shape).background(Fill, shape).border(1.dp, Stroke, shape)
}

/**
 * Rotation that keeps icons and labels upright when the phone is turned sideways, as phone camera
 * apps do while the layout itself stays portrait.
 */
internal val LocalIconRotation = compositionLocalOf { 0f }

/** Turns icons to match [deviceDegrees] (0..359, from the orientation sensor) along the shortest way. */
@Composable
internal fun ProvideIconRotation(deviceDegrees: Int, content: @Composable () -> Unit) {
    // A quarter turn only once the phone is clearly past 45 degrees, so icons don't flicker.
    val quarter = ((deviceDegrees + 45) / 90) % 4
    val angle = remember { Animatable(0f) }
    LaunchedEffect(quarter) {
        val target = -quarter * 90f
        val current = angle.value
        val delta = ((target - current) % 360f + 540f) % 360f - 180f
        angle.animateTo(current + delta, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow))
    }
    CompositionLocalProvider(LocalIconRotation provides angle.value, content = content)
}

@Composable
internal fun Modifier.upright(): Modifier = rotate(LocalIconRotation.current)

/** Light taps of the vibration motor for controls, like the clicks of a real camera dial. */
internal class Haptics(private val view: View) {
    /** A small tick: changing a mode, a zoom step or a setting. */
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A firmer press: the shutter. */
    fun confirm() {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }
}

@Composable
internal fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
