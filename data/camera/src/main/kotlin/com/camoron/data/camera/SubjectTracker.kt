package com.camoron.data.camera

import kotlin.math.abs

/** A greyscale frame: one byte of luma per pixel, rows packed with no padding. */
internal class LumaFrame(val pixels: ByteArray, val width: Int, val height: Int) {
    fun at(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}

/**
 * Follows a tapped subject across low-resolution preview frames by template matching.
 *
 * The patch around the tap is compared, brightness-normalised, against nearby positions in each new
 * frame (coarse search, then a fine pass), and the template slowly adapts so gradual changes in
 * pose or light do not lose the subject. Works in normalised coordinates of the frame, which match
 * the sensor field of view that focus regions are expressed in.
 *
 * Not thread-safe; the camera thread owns it.
 */
internal class SubjectTracker(
    private val patchSize: Int = 24,
    private val searchRadius: Int = 20,
) {
    private var template: IntArray? = null
    private var centerX = 0
    private var centerY = 0

    val isTracking: Boolean get() = template != null

    /**
     * Starts tracking the subject at normalised ([nx], [ny]). Returns false when the patch has too
     * little texture to follow (a blank wall, the sky), in which case nothing is tracked.
     */
    fun start(frame: LumaFrame, nx: Float, ny: Float): Boolean {
        stop()
        if (frame.width < patchSize || frame.height < patchSize) return false
        centerX = clampX(frame, (nx * frame.width).toInt())
        centerY = clampY(frame, (ny * frame.height).toInt())
        val patch = extract(frame, centerX, centerY)
        if (meanAbsolute(patch) < MIN_TEXTURE) return false
        template = patch
        return true
    }

    /** Finds the subject in [frame]. Returns its normalised centre, or null once it is lost. */
    fun update(frame: LumaFrame): Pair<Float, Float>? {
        val current = template ?: return null
        var bestX = centerX
        var bestY = centerY
        var bestCost = Int.MAX_VALUE

        fun consider(x: Int, y: Int) {
            val cx = clampX(frame, x)
            val cy = clampY(frame, y)
            val cost = cost(frame, cx, cy, current, bestCost)
            if (cost < bestCost) {
                bestCost = cost
                bestX = cx
                bestY = cy
            }
        }

        var dy = -searchRadius
        while (dy <= searchRadius) {
            var dx = -searchRadius
            while (dx <= searchRadius) {
                consider(centerX + dx, centerY + dy)
                dx += COARSE_STEP
            }
            dy += COARSE_STEP
        }
        val coarseX = bestX
        val coarseY = bestY
        for (fy in -COARSE_STEP + 1 until COARSE_STEP) {
            for (fx in -COARSE_STEP + 1 until COARSE_STEP) consider(coarseX + fx, coarseY + fy)
        }

        val meanCost = bestCost.toFloat() / (patchSize * patchSize)
        if (meanCost > LOST_THRESHOLD) {
            stop()
            return null
        }
        centerX = bestX
        centerY = bestY
        adapt(current, extract(frame, centerX, centerY))
        return centerX.toFloat() / frame.width to centerY.toFloat() / frame.height
    }

    fun stop() {
        template = null
    }

    /** Zero-mean patch, so a change in overall brightness does not look like a different subject. */
    private fun extract(frame: LumaFrame, cx: Int, cy: Int): IntArray {
        val half = patchSize / 2
        val patch = IntArray(patchSize * patchSize)
        var sum = 0
        var i = 0
        for (y in cy - half until cy - half + patchSize) {
            for (x in cx - half until cx - half + patchSize) {
                val v = frame.at(x, y)
                patch[i++] = v
                sum += v
            }
        }
        val mean = sum / patch.size
        for (j in patch.indices) patch[j] -= mean
        return patch
    }

    /** Sum of absolute differences against the template, stopping early once it can't win. */
    private fun cost(frame: LumaFrame, cx: Int, cy: Int, template: IntArray, limit: Int): Int {
        val half = patchSize / 2
        var sum = 0
        for (y in cy - half until cy - half + patchSize) {
            for (x in cx - half until cx - half + patchSize) sum += frame.at(x, y)
        }
        val mean = sum / (patchSize * patchSize)
        var cost = 0
        var i = 0
        for (y in cy - half until cy - half + patchSize) {
            for (x in cx - half until cx - half + patchSize) {
                cost += abs(frame.at(x, y) - mean - template[i++])
            }
            if (cost >= limit) return cost
        }
        return cost
    }

    private fun adapt(template: IntArray, latest: IntArray) {
        for (i in template.indices) template[i] = (template[i] * (ADAPT_DIVISOR - 1) + latest[i]) / ADAPT_DIVISOR
    }

    private fun meanAbsolute(patch: IntArray): Float = patch.sumOf { abs(it) }.toFloat() / patch.size

    private fun clampX(frame: LumaFrame, x: Int) = x.coerceIn(patchSize / 2, frame.width - patchSize + patchSize / 2)
    private fun clampY(frame: LumaFrame, y: Int) = y.coerceIn(patchSize / 2, frame.height - patchSize + patchSize / 2)

    private companion object {
        const val COARSE_STEP = 2
        /** Mean per-pixel difference (0..255) beyond which the subject is considered lost. */
        const val LOST_THRESHOLD = 22f
        /** Mean deviation from the patch average below which there is nothing to lock on to. */
        const val MIN_TEXTURE = 6f
        /** The template moves a quarter of the way towards the latest match each frame. */
        const val ADAPT_DIVISOR = 4
    }
}
