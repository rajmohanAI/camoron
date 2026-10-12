package com.camoron.data.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class SubjectTrackerTest {

    private val width = 160
    private val height = 120

    /** Mid-grey background with mild noise and a textured 16x16 subject at (sx, sy). */
    private fun frame(sx: Int?, sy: Int?, seed: Int, brightness: Int = 0): LumaFrame {
        val random = Random(seed)
        val pixels = ByteArray(width * height) { (120 + random.nextInt(-3, 4) + brightness).toByte() }
        if (sx != null && sy != null) {
            for (y in sy - 8 until sy + 8) for (x in sx - 8 until sx + 8) {
                val checker = if (((x - sx + 8) / 4 + (y - sy + 8) / 4) % 2 == 0) 230 else 20
                pixels[y * width + x] = (checker + brightness).coerceIn(0, 255).toByte()
            }
        }
        return LumaFrame(pixels, width, height)
    }

    @Test
    fun `follows a moving subject`() {
        val tracker = SubjectTracker()
        assertThat(tracker.start(frame(60, 60, seed = 1), 60f / width, 60f / height)).isTrue()

        var position: Pair<Float, Float>? = null
        for (step in 1..10) position = tracker.update(frame(60 + step * 4, 60 + step * 2, seed = step + 1))

        assertThat(position!!.first).isWithin(0.02f).of(100f / width)
        assertThat(position.second).isWithin(0.02f).of(80f / height)
        assertThat(tracker.isTracking).isTrue()
    }

    @Test
    fun `survives a change in overall brightness`() {
        val tracker = SubjectTracker()
        tracker.start(frame(80, 60, seed = 1), 0.5f, 0.5f)
        val position = tracker.update(frame(82, 60, seed = 2, brightness = 25))
        assertThat(position).isNotNull()
        assertThat(position!!.first).isWithin(0.02f).of(82f / width)
    }

    @Test
    fun `reports the subject lost when it leaves`() {
        val tracker = SubjectTracker()
        tracker.start(frame(80, 60, seed = 1), 0.5f, 0.5f)
        assertThat(tracker.update(frame(null, null, seed = 2))).isNull()
        assertThat(tracker.isTracking).isFalse()
    }

    @Test
    fun `refuses to track a featureless patch`() {
        val tracker = SubjectTracker()
        assertThat(tracker.start(frame(null, null, seed = 1), 0.5f, 0.5f)).isFalse()
        assertThat(tracker.isTracking).isFalse()
    }
}
