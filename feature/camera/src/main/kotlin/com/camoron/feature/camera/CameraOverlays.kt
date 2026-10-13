package com.camoron.feature.camera

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import com.camoron.domain.model.FocusTarget
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Rule-of-thirds grid. */
@Composable
internal fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.5f)
        for (i in 1..2) {
            val x = size.width * i / 3
            val y = size.height * i / 3
            drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}

/**
 * A line that stays parallel to the horizon and turns yellow when the phone is straight (in
 * portrait or landscape). Hidden while the phone is lying flat, where "level" has no meaning.
 */
@Composable
internal fun LevellerOverlay(modifier: Modifier = Modifier) {
    val roll by rememberRollDegrees()
    val angle = roll ?: return
    val level = abs(LevelMath.deviation(angle)) < LEVEL_TOLERANCE_DEGREES
    Canvas(modifier) {
        val half = size.minDimension * 0.3f
        val centre = center
        rotate(angle, centre) {
            drawLine(
                color = if (level) LEVEL_COLOR else Color.White,
                start = Offset(centre.x - half, centre.y),
                end = Offset(centre.x + half, centre.y),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

/**
 * Square on the focus point. A plain tap shows it briefly; while tracking it follows the subject
 * in yellow until the subject is lost.
 */
@Composable
internal fun FocusIndicator(target: FocusTarget?, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        visible = target != null
        if (target != null && !target.tracking) {
            delay(TAP_FOCUS_VISIBLE_MS)
            visible = false
        }
    }
    val shown = target?.takeIf { visible } ?: return
    Canvas(modifier) {
        val side = 64.dp.toPx()
        drawRect(
            color = if (shown.tracking) LEVEL_COLOR else Color.White,
            topLeft = Offset(shown.x * size.width - side / 2, shown.y * size.height - side / 2),
            size = Size(side, side),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
internal fun CountdownOverlay(seconds: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(seconds.toString(), color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Light)
    }
}

/** Phone roll from gravity, in screen-rotation degrees; null while it lies flat. */
@Composable
private fun rememberRollDegrees(): State<Float?> {
    val context = LocalContext.current
    val roll = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(context) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) return@DisposableEffect onDispose { }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                roll.value = LevelMath.roll(event.values[0], event.values[1])
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager.unregisterListener(listener) }
    }
    return roll
}

internal object LevelMath {
    /**
     * Angle to rotate an on-screen line so it matches the horizon, from gravity along the
     * screen's x and y axes. Null when the phone is too flat for a meaningful reading.
     */
    fun roll(gravityX: Float, gravityY: Float): Float? {
        if (hypot(gravityX, gravityY) < FLAT_THRESHOLD) return null
        return Math.toDegrees(atan2(gravityX.toDouble(), gravityY.toDouble())).toFloat()
    }

    /** How far [roll] is from the nearest straight position (portrait, landscape or upside down). */
    fun deviation(roll: Float): Float = roll - (roll / 90f).roundToInt() * 90f

    private const val FLAT_THRESHOLD = 3f
}

private const val LEVEL_TOLERANCE_DEGREES = 1.5f
private const val TAP_FOCUS_VISIBLE_MS = 1_500L
private val LEVEL_COLOR = Color(0xFFFFD54F)
