package com.camoron.domain

import com.camoron.domain.model.CropMath
import com.camoron.domain.model.CropMath.Handle
import com.camoron.domain.model.CropRectF
import com.camoron.domain.model.EditCrop
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CropMathTest {

    private fun map(m: FloatArray, x: Float, y: Float) = (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])

    private fun assertPoint(actual: Pair<Float, Float>, x: Float, y: Float) {
        assertThat(actual.first).isWithin(0.01f).of(x)
        assertThat(actual.second).isWithin(0.01f).of(y)
    }

    @Test
    fun `no crop maps every pixel to itself`() {
        val m = CropMath.transform(400, 300, EditCrop())
        assertPoint(map(m, 10f, 20f), 10f, 20f)
        assertThat(CropMath.outputSize(400, 300, EditCrop())).isEqualTo(400 to 300)
    }

    @Test
    fun `a quarter turn puts the top left corner at the top right`() {
        val crop = EditCrop(quarterTurns = 1)
        val m = CropMath.transform(400, 300, crop)
        assertPoint(map(m, 0f, 0f), 300f, 0f)
        assertPoint(map(m, 400f, 300f), 0f, 400f)
        assertThat(CropMath.outputSize(400, 300, crop)).isEqualTo(300 to 400)
    }

    @Test
    fun `half and three quarter turns`() {
        assertPoint(map(CropMath.transform(400, 300, EditCrop(quarterTurns = 2)), 0f, 0f), 400f, 300f)
        assertPoint(map(CropMath.transform(400, 300, EditCrop(quarterTurns = 3)), 0f, 0f), 0f, 400f)
    }

    @Test
    fun `flip mirrors left to right`() {
        val m = CropMath.transform(400, 300, EditCrop(mirrored = true))
        assertPoint(map(m, 0f, 10f), 400f, 10f)
    }

    @Test
    fun `crop shifts the picture and sets the output size`() {
        val crop = EditCrop(left = 0.25f, top = 0.5f, right = 0.75f, bottom = 1f)
        val m = CropMath.transform(400, 300, crop)
        assertPoint(map(m, 100f, 150f), 0f, 0f)
        assertThat(CropMath.outputSize(400, 300, crop)).isEqualTo(200 to 150)
    }

    @Test
    fun `straighten turns about the centre`() {
        val m = CropMath.transform(400, 300, EditCrop(straighten = 90f))
        // The centre stays put; a point to the right of it moves below it (clockwise on screen).
        assertPoint(map(m, 200f, 150f), 200f, 150f)
        assertPoint(map(m, 300f, 150f), 200f, 250f)
    }

    @Test
    fun `the whole frame no longer fits once straightened, and fitting shrinks it to the picture`() {
        assertThat(CropMath.fits(CropRectF.FULL, 400f, 300f, 0f)).isTrue()
        assertThat(CropMath.fits(CropRectF.FULL, 400f, 300f, 5f)).isFalse()
        val fitted = CropMath.fitInside(CropRectF.FULL, 400f, 300f, 5f)
        assertThat(CropMath.fits(fitted, 400f, 300f, 5f)).isTrue()
        assertThat(fitted.width).isGreaterThan(0.8f)
        assertThat(fitted.width).isLessThan(1f)
        // Shrinking keeps the shape and the centre.
        assertThat(fitted.width).isWithin(0.001f).of(fitted.height)
        assertThat(fitted.centerX).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `a centred square in a landscape frame`() {
        val square = CropMath.centred(1f, 400f, 300f, 0f)
        assertThat(square.width * 400).isWithin(0.5f).of(300f)
        assertThat(square.height).isWithin(0.001f).of(1f)
        assertThat(square.centerX).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `turning clockwise four times comes back to the start`() {
        val start = EditCrop(left = 0.1f, top = 0.2f, right = 0.6f, bottom = 0.9f, straighten = 3f)
        var crop = start
        repeat(4) { crop = CropMath.turnClockwise(crop) }
        assertThat(crop.quarterTurns % 4).isEqualTo(0)
        assertThat(crop.left).isWithin(1e-5f).of(start.left)
        assertThat(crop.bottom).isWithin(1e-5f).of(start.bottom)
    }

    @Test
    fun `turning moves the crop with the picture`() {
        // A crop of the left half becomes the top half after a clockwise turn.
        val turned = CropMath.turnClockwise(EditCrop(right = 0.5f))
        assertThat(turned.rect).isEqualTo(CropRectF(0f, 0f, 1f, 0.5f))
    }

    @Test
    fun `turning a flipped photo still turns it clockwise on screen`() {
        // Pixel at the source's top-left: after flip it is top-right; a clockwise turn takes it to bottom-right.
        val crop = CropMath.turnClockwise(CropMath.flip(EditCrop()))
        val m = CropMath.transform(400, 300, crop)
        assertPoint(map(m, 0f, 0f), 300f, 400f)
    }

    @Test
    fun `flipping twice is a no-op`() {
        val start = EditCrop(left = 0.1f, right = 0.4f, straighten = 4f)
        val back = CropMath.flip(CropMath.flip(start))
        assertThat(back.mirrored).isFalse()
        assertThat(back.straighten).isEqualTo(4f)
        assertThat(back.left).isWithin(1e-5f).of(0.1f)
        assertThat(back.right).isWithin(1e-5f).of(0.4f)
    }

    @Test
    fun `dragging a corner resizes and stops at the frame edge`() {
        val rect = CropRectF(0.2f, 0.2f, 0.8f, 0.8f)
        val moved = CropMath.drag(rect, Handle.TOP_LEFT, -0.1f, -0.05f, 400f, 300f, 0f, null)
        assertThat(moved.left).isWithin(1e-4f).of(0.1f)
        assertThat(moved.top).isWithin(1e-4f).of(0.15f)
        val stopped = CropMath.drag(rect, Handle.LEFT, -0.5f, 0f, 400f, 300f, 0f, null)
        assertThat(stopped.left).isWithin(0.001f).of(0f)
    }

    @Test
    fun `dragging never makes the crop smaller than the minimum`() {
        val rect = CropRectF(0.2f, 0.2f, 0.8f, 0.8f)
        val moved = CropMath.drag(rect, Handle.RIGHT, -0.7f, 0f, 400f, 300f, 0f, null)
        assertThat(moved.width).isAtLeast(CropMath.MIN_SIZE - 0.002f)
    }

    @Test
    fun `a locked shape keeps its ratio while a corner is dragged`() {
        val square = CropMath.centred(1f, 400f, 300f, 0f)
        val smaller = CropMath.drag(square, Handle.BOTTOM_RIGHT, -0.1f, 0f, 400f, 300f, 0f, 1f)
        assertThat(smaller.width * 400).isWithin(0.5f).of(smaller.height * 300)
        assertThat(smaller.width).isLessThan(square.width)
    }

    @Test
    fun `moving slides along an edge it is pressed against`() {
        val rect = CropRectF(0f, 0.2f, 0.5f, 0.6f)
        val moved = CropMath.drag(rect, Handle.MOVE, -0.1f, 0.1f, 400f, 300f, 0f, null)
        assertThat(moved.left).isWithin(1e-4f).of(0f)
        assertThat(moved.top).isWithin(1e-4f).of(0.3f)
    }

    @Test
    fun `exif orientations stand the photo upright`() {
        // A sensor-landscape 400x300 photo of a portrait scene, tagged rotate 90.
        val m = CropMath.upright(6, 400, 300)
        assertPoint(map(m, 0f, 0f), 300f, 0f)
        assertPoint(map(m, 400f, 300f), 0f, 400f)
        assertPoint(map(CropMath.upright(8, 400, 300), 0f, 0f), 0f, 400f)
        assertPoint(map(CropMath.upright(1, 400, 300), 5f, 6f), 5f, 6f)
        assertThat(CropMath.uprightSwapsSides(6)).isTrue()
        assertThat(CropMath.uprightSwapsSides(3)).isFalse()
    }

    @Test
    fun `chained maps apply in order`() {
        val turn = CropMath.upright(6, 400, 300)
        val crop = CropMath.transform(300, 400, EditCrop(left = 0.5f))
        val both = CropMath.then(turn, crop)
        assertPoint(map(both, 0f, 0f), 150f, 0f)
    }
}
