package com.camoron.data.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.os.Build
import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.LensShading
import com.camoron.domain.model.RawBurst
import com.camoron.domain.model.RawColour
import java.nio.ByteOrder

/** Turns Camera2's RAW frames and metadata into a [RawBurst] for Detail mode. */
internal object RawDevelopment {

    /** Colour of each 2×2 filter position (top left, top right, bottom left, bottom right), or null for other sensors. */
    fun pattern(arrangement: Int?): List<RawColour>? {
        val r = RawColour.RED
        val g = RawColour.GREEN
        val b = RawColour.BLUE
        return when (arrangement) {
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> listOf(r, g, g, b)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> listOf(g, r, b, g)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> listOf(g, b, r, g)
            CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> listOf(b, g, g, r)
            else -> null
        }
    }

    /** The sensor values of a RAW_SENSOR image, one 16-bit value per pixel, without row padding. */
    fun copy(image: Image, width: Int, height: Int): ShortArray {
        val plane = image.planes[0]
        val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val rowShorts = plane.rowStride / 2
        val out = ShortArray(width * height)
        for (y in 0 until height) {
            buffer.position(y * rowShorts)
            buffer.get(out, y * width, width)
        }
        return out
    }

    fun burst(
        characteristics: CameraCharacteristics,
        result: CaptureResult,
        frames: List<ShortArray>,
        width: Int,
        height: Int,
        pattern: List<RawColour>,
        rotationDegrees: Int,
        preview: CapturedImage,
    ): RawBurst {
        val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val black = (if (dynamic) result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) else null)
            ?: characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)?.let { p ->
                // The pattern is indexed by column, then row.
                floatArrayOf(p.getOffsetForIndex(0, 0).toFloat(), p.getOffsetForIndex(1, 0).toFloat(), p.getOffsetForIndex(0, 1).toFloat(), p.getOffsetForIndex(1, 1).toFloat())
            }
            ?: FloatArray(4)
        val white = (if (dynamic) result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) else null)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)
            ?: 1023
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val whiteBalance = gains?.let { floatArrayOf(it.red, (it.greenEven + it.greenOdd) / 2, it.blue) } ?: floatArrayOf(1f, 1f, 1f)
        val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val matrix = transform?.let { t -> FloatArray(9) { t.getElement(it % 3, it / 3).toFloat() } }
            ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val noise = result.get(CaptureResult.SENSOR_NOISE_PROFILE)?.takeIf { it.size >= 4 }
        return RawBurst(
            frames = frames,
            width = width,
            height = height,
            pattern = pattern,
            blackLevel = black,
            whiteLevel = white,
            whiteBalance = whiteBalance,
            colourMatrix = matrix,
            noiseScale = noise?.let { n -> FloatArray(4) { n[it].first.toFloat() } },
            noiseOffset = noise?.let { n -> FloatArray(4) { n[it].second.toFloat() } },
            shading = shading(result),
            rotationDegrees = rotationDegrees,
            preview = preview,
        )
    }

    private fun shading(result: CaptureResult): LensShading? {
        val map = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP) ?: return null
        val columns = map.columnCount
        val rows = map.rowCount
        if (columns < 1 || rows < 1) return null
        val gains = FloatArray(columns * rows * 3)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val i = (row * columns + column) * 3
                gains[i] = map.getGainFactor(RggbChannelVector.RED, column, row)
                gains[i + 1] = (map.getGainFactor(RggbChannelVector.GREEN_EVEN, column, row) + map.getGainFactor(RggbChannelVector.GREEN_ODD, column, row)) / 2
                gains[i + 2] = map.getGainFactor(RggbChannelVector.BLUE, column, row)
            }
        }
        return LensShading(columns, rows, gains)
    }
}
