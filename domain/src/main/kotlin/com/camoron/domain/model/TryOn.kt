package com.camoron.domain.model

/** Spectacle frame shapes. */
enum class GlassesStyle { NONE, ROUND, RECTANGLE, AVIATOR, CAT_EYE, BOLD, RIMLESS }

/** Frame colours, as opaque ARGB. */
enum class FrameColour(val argb: Int) {
    BLACK(0xFF151515.toInt()),
    TORTOISE(0xFF6B3E1F.toInt()),
    GOLD(0xFFC9A54C.toInt()),
    SILVER(0xFFB9BEC4.toInt()),
    RED(0xFFB3202A.toInt()),
    CLEAR(0xFFE8E4DC.toInt()),
}

/** Lens tints, from clear glasses to sunglasses. */
enum class LensTint { CLEAR, GREY, BROWN, GRADIENT, BLUE }

/** Face masks over the nose and mouth, and eye masks. */
enum class MaskStyle(val coversEyes: Boolean = false) {
    NONE,
    SURGICAL,
    N95,
    CLOTH,
    MASQUERADE(coversEyes = true),
    HERO(coversEyes = true),
}

/** Hair colours to try, as opaque ARGB; [NONE] keeps the real colour. */
enum class HairColour(val argb: Int) {
    NONE(0),
    BLACK(0xFF141210.toInt()),
    DARK_BROWN(0xFF3B2416.toInt()),
    CHESTNUT(0xFF6E3B1E.toInt()),
    AUBURN(0xFF8E3A1C.toInt()),
    BLONDE(0xFFD9B57A.toInt()),
    PLATINUM(0xFFE9E2D3.toInt()),
    RED(0xFFB0201C.toInt()),
    PINK(0xFFE3779E.toInt()),
    PURPLE(0xFF6E3AA8.toInt()),
    BLUE(0xFF2F5FC4.toInt()),
    SILVER(0xFFB8BCC2.toInt()),
}

/** Beards and moustaches, from a shadow of stubble to a full beard. */
enum class FacialHair { NONE, STUBBLE, MOUSTACHE, GOATEE, CIRCLE, SHORT_BEARD, FULL_BEARD }

/** Facial hair colours, as opaque ARGB. */
enum class BeardColour(val argb: Int) {
    BLACK(0xFF17130F.toInt()),
    DARK_BROWN(0xFF34210F.toInt()),
    BROWN(0xFF5C3A1E.toInt()),
    GINGER(0xFF9A4A1E.toInt()),
    BLONDE(0xFFB89662.toInt()),
    GREY(0xFF9A9894.toInt()),
}

/** What the person is trying on. Everything off means the photo is unchanged. */
data class TryOn(
    val glasses: GlassesStyle = GlassesStyle.NONE,
    val frame: FrameColour = FrameColour.BLACK,
    val lens: LensTint = LensTint.CLEAR,
    val mask: MaskStyle = MaskStyle.NONE,
    val hair: HairColour = HairColour.NONE,
    val hairStrength: Float = 0.8f,
    val facialHair: FacialHair = FacialHair.NONE,
    val beard: BeardColour = BeardColour.DARK_BROWN,
) {
    val isNone: Boolean
        get() = !needsFaces && (hair == HairColour.NONE || hairStrength <= 0f)

    /** Whether anything needs the face points (as opposed to only the hair outline). */
    val needsFaces: Boolean get() = glasses != GlassesStyle.NONE || mask != MaskStyle.NONE || facialHair != FacialHair.NONE
}

/**
 * One face's 478 points from the on-device face tracker, as x, y pairs in fractions (0..1) of the
 * upright photo's width and height.
 */
class FaceShape(val points: FloatArray) {
    val count: Int get() = points.size / 2
    fun x(i: Int) = points[2 * i]
    fun y(i: Int) = points[2 * i + 1]
}

/** How likely each pixel is hair, 0..255, at [width] x [height] over the upright photo. */
class HairMask(val width: Int, val height: Int, val alpha: ByteArray) {
    /** The hair likelihood (0..1) at a point given in fractions of the photo, smoothly interpolated. */
    fun at(nx: Float, ny: Float): Float {
        val fx = (nx * width - 0.5f).coerceIn(0f, width - 1f)
        val fy = (ny * height - 0.5f).coerceIn(0f, height - 1f)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val dx = fx - x0
        val dy = fy - y0
        val a = value(x0, y0) + (value(x1, y0) - value(x0, y0)) * dx
        val b = value(x0, y1) + (value(x1, y1) - value(x0, y1)) * dx
        return (a + (b - a) * dy) / 255f
    }

    private fun value(x: Int, y: Int) = alpha[y * width + x].toInt() and 0xFF
}

/** What the trackers found in a photo, for the try-ons to be drawn on. */
class FaceScene(val faces: List<FaceShape>, val hair: HairMask?) {
    companion object {
        val EMPTY = FaceScene(emptyList(), null)
    }
}

/**
 * New hair colour that keeps the hair's own light and shade: each pixel takes the target colour,
 * brightened or darkened by how light that strand is, so highlights and texture survive and dark
 * hair can still go blonde.
 */
object HairTint {
    /** [argb] recoloured towards [target] by [amount] (0..1, the hair likelihood times the strength). */
    fun apply(argb: Int, target: Int, amount: Float): Int {
        if (amount <= 0f) return argb
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
        // Dark hair is lifted towards the target; light strands go a little past it for highlights.
        val gain = SHADE_BASE + SHADE_SLOPE * luma
        val a = amount.coerceIn(0f, 1f)
        fun mix(own: Float, shift: Int): Int {
            val t = ((target shr shift) and 0xFF) / 255f * gain
            return ((own + (t.coerceIn(0f, 1f) - own) * a) * 255f + 0.5f).toInt().coerceIn(0, 255)
        }
        return (argb and 0xFF000000.toInt()) or (mix(r, 16) shl 16) or (mix(g, 8) shl 8) or mix(b, 0)
    }

    /**
     * How strongly a pixel is recoloured for a hair likelihood [m] (0..1): soft-edged, but the
     * uncertain fringe around the hair (forehead, background) is left alone.
     */
    fun weight(m: Float): Float {
        val t = ((m - EDGE_LOW) / (EDGE_HIGH - EDGE_LOW)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val EDGE_LOW = 0.35f
    private const val EDGE_HIGH = 0.85f
    private const val SHADE_BASE = 0.45f
    private const val SHADE_SLOPE = 1.25f
}
