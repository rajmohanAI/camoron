package com.camoron.engine

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How far to take a photo. */
data class OptimiseOptions(
    /** Long side to enlarge towards: 3840 is 4K. Photos already this big keep their size. */
    val targetLongSide: Int = 3840,
    /**
     * The most a photo is enlarged. Enlarging adds pixels, not detail, and past 2× a faithful
     * (non-generative) enlargement only looks softer, so it stops there by default.
     */
    val maxScale: Float = 2f,
    /** Cores to use. */
    val threads: Int = 4,
)

/**
 * What the engine decided to do, step by step, after measuring the photo and checking each step
 * against the original. A strength of 0 means the step was not needed or was rejected.
 */
data class Plan(
    val deblock: Float,
    val lumaDenoise: Float,
    val chromaDenoise: Float,
    /** Share of the removed grain put back, so surfaces keep a photographic texture. */
    val keepGrain: Float,
    val redScale: Float,
    val blueScale: Float,
    val sharpen: Float,
    val scale: Float,
    val castGains: FloatArray?,
    val curve: FloatArray?,
)

/** The outcome: the plan carried out, the output size and, in plain words, why. */
data class OptimiseReport(
    val analysis: Analysis,
    val plan: Plan,
    val outputWidth: Int,
    val outputHeight: Int,
    val notes: List<String>,
)

/**
 * Camoron's photo optimisation engine: photographic restoration, never regeneration.
 *
 * Every photo goes through the same order of work. First the photo is measured (noise, JPEG
 * blocking, colour fringing, colour cast, exposure). Then each correction is decided from those
 * measurements only, so a step the photo doesn't need is skipped. The structural steps (block
 * smoothing, noise reduction, fringing, sharpening) are tried on samples of the photo and
 * compared with the original. A step that makes texture waxy, adds halos or shifts colour is
 * weakened and tried again, or dropped. Then the whole photo is processed in blocks (so memory
 * stays small) and enlarged with a faithful Lanczos filter if it is smaller than the target.
 * Colour and exposure come last, and only where the measurements showed a clear problem.
 *
 * Nothing is generated: every output pixel comes from the photo's own pixels, so faces, text,
 * fabric and backgrounds stay exactly what the camera saw.
 */
class PhotoOptimiser(private val options: OptimiseOptions = OptimiseOptions()) {

    /** Measures [source] and decides the plan, checked against samples of the original. */
    fun prepare(source: PixelSource): Pair<Analysis, Plan> {
        val analysis = Analyser.analyse(source)
        val plan = Checks.refine(source, analysis, initialPlan(analysis))
        return analysis to plan
    }

    /** The size [prepare]'s plan produces. */
    fun outputSize(analysis: Analysis, plan: Plan): Pair<Int, Int> =
        (analysis.width * plan.scale).roundToInt() to (analysis.height * plan.scale).roundToInt()

    /** Optimises [source] into [sink], which must take the size [outputSize] gives. */
    fun run(source: PixelSource, analysis: Analysis, plan: Plan, sink: PixelSink): OptimiseReport {
        val (outW, outH) = outputSize(analysis, plan)
        val tiles = mutableListOf<IntArray>()
        for (y in 0 until outH step OUTPUT_TILE) {
            for (x in 0 until outW step OUTPUT_TILE) {
                tiles += intArrayOf(x, y, min(OUTPUT_TILE, outW - x), min(OUTPUT_TILE, outH - y))
            }
        }
        val pool = Executors.newFixedThreadPool(options.threads.coerceIn(1, 8))
        try {
            pool.invokeAll(tiles.map { (x, y, w, h) -> Callable { sink.write(x, y, w, h, renderTile(source, analysis, plan, x, y, w, h)) } })
                .forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        return OptimiseReport(analysis, plan, outW, outH, describe(analysis, plan, outW, outH))
    }

    /** Both steps in one, for callers that don't need to size their output first. */
    fun optimise(source: PixelSource, sinkFor: (width: Int, height: Int) -> PixelSink): OptimiseReport {
        val (analysis, plan) = prepare(source)
        val (w, h) = outputSize(analysis, plan)
        return run(source, analysis, plan, sinkFor(w, h))
    }

    internal fun initialPlan(a: Analysis): Plan {
        val noise255 = a.noise * 255f
        val chroma255 = a.chromaNoise * 255f
        // Already clean areas are left untouched; the strength grows with the measured noise.
        var luma = ((noise255 - 0.8f) / 1.5f).coerceIn(0f, 1f)
        val chroma = ((chroma255 - 0.5f) / 1.0f).coerceIn(0f, 1f)
        val deblock = if (a.blockiness > BLOCKY) ((a.blockiness - BLOCKY) / 0.3f).coerceIn(0.3f, 1f) else 0f
        // Heavy JPEG compression also leaves blotches inside the blocks ("mosquito noise") that
        // the noise measurement can't see, because compression removed the fine grain it measures.
        if (deblock > 0f) luma = max(luma, 0.4f + 0.6f * deblock)
        val longSide = max(a.width, a.height)
        val wanted = options.targetLongSide.toFloat() / longSide
        val scale = if (wanted > 1.05f) min(wanted, options.maxScale) else 1f
        // Mild by default, a little more before an enlargement, and less on grainy photos.
        val sharpen = ((if (scale > 1f) 0.6f else 0.45f) * (1f - noise255 / 10f).coerceIn(0.3f, 1f))
        return Plan(
            deblock = deblock,
            lumaDenoise = luma,
            chromaDenoise = chroma,
            keepGrain = KEEP_GRAIN,
            redScale = a.redScale,
            blueScale = a.blueScale,
            sharpen = sharpen,
            scale = scale,
            castGains = a.cast,
            curve = Exposure.curve(a),
        )
    }

    private fun renderTile(source: PixelSource, a: Analysis, plan: Plan, ox: Int, oy: Int, ow: Int, oh: Int): IntArray {
        // The source pixels this output block needs, plus a margin for the filters.
        val support = if (plan.scale > 1f) 4 else 0
        val sx0 = floor(ox / plan.scale).toInt() - support
        val sy0 = floor(oy / plan.scale).toInt() - support
        val sx1 = ceil((ox + ow) / plan.scale).toInt() + support
        val sy1 = ceil((oy + oh) / plan.scale).toInt() + support
        val margin = margin(a, plan)
        val rx0 = max(0, sx0 - margin)
        val ry0 = max(0, sy0 - margin)
        val rx1 = min(a.width, sx1 + margin)
        val ry1 = min(a.height, sy1 + margin)
        val region = Planes.fromArgb(rx0, ry0, rx1 - rx0, ry1 - ry0, source.read(rx0, ry0, rx1 - rx0, ry1 - ry0))
        restore(region, a, plan)
        val out = if (plan.scale > 1f) {
            Restoration.upscale(region, plan.scale, ox, oy, ow, oh)
        } else {
            region.crop(ox, oy, ow, oh)
        }
        Restoration.tone(out, plan.castGains, plan.curve)
        return out.toArgb()
    }

    internal companion object {
        const val OUTPUT_TILE = 384
        const val KEEP_GRAIN = 0.3f
        /** Block edges this much stronger than the rest: clearly visible JPEG blocking, not just a fine JPEG. */
        const val BLOCKY = 1.2f

        /** The structural steps, in the brief's order: clean, then optics, then sharpen. */
        fun restore(region: Planes, a: Analysis, plan: Plan) {
            Restoration.deblock(region, plan.deblock, a.noise)
            Restoration.denoise(region, plan.lumaDenoise, plan.chromaDenoise, cleaningLevel(a, plan), a.chromaNoise, plan.keepGrain)
            Restoration.correctFringing(region, plan.redScale, plan.blueScale, a.width / 2f, a.height / 2f)
            Restoration.sharpen(region, plan.sharpen, a.noise * (1f - plan.lumaDenoise * (1f - plan.keepGrain)))
        }

        /** The noise level the brightness clean-up works to: at least JPEG's blotches on a blocky photo. */
        fun cleaningLevel(a: Analysis, plan: Plan): Float =
            if (plan.deblock > 0f) max(a.noise, (1.5f + 2.5f * plan.deblock) / 255f) else a.noise

        /** Pixels each block reads beyond what it keeps: enough for every filter and the fringe shift. */
        fun margin(a: Analysis, plan: Plan): Int {
            val halfDiagonal = hypot(a.width / 2f, a.height / 2f)
            val shift = max(abs(plan.redScale - 1f), abs(plan.blueScale - 1f)) * halfDiagonal
            return 12 + ceil(shift).toInt()
        }

        fun describe(a: Analysis, plan: Plan, outW: Int, outH: Int): List<String> = buildList {
            add("Measured noise %.1f, colour noise %.1f (out of 255), JPEG blocking %.2f".format(a.noise * 255, a.chromaNoise * 255, a.blockiness))
            add(if (plan.deblock > 0) "Smoothed JPEG block edges (strength %.2f)".format(plan.deblock) else "No JPEG blocking to fix")
            add(
                if (plan.lumaDenoise > 0 || plan.chromaDenoise > 0) {
                    "Reduced noise (brightness %.2f, colour %.2f), keeping %d%% of the grain".format(plan.lumaDenoise, plan.chromaDenoise, (plan.keepGrain * 100).roundToInt())
                } else {
                    "Clean already: no noise reduction"
                },
            )
            add(
                if (plan.redScale != 1f || plan.blueScale != 1f) {
                    "Realigned colour fringes (red %.4f, blue %.4f)".format(plan.redScale, plan.blueScale)
                } else {
                    "No colour fringing found"
                },
            )
            add(if (plan.sharpen > 0) "Restrained sharpening (%.2f), no halos".format(plan.sharpen) else "Sharpening left off")
            add(if (plan.scale > 1f) "Enlarged %.2f× to %d×%d (Lanczos, no generated detail)".format(plan.scale, outW, outH) else "Kept at %d×%d".format(outW, outH))
            add(plan.castGains?.let { g -> "Eased a colour cast a third of the way (gains %.3f / %.3f / %.3f)".format(g[0], g[1], g[2]) } ?: "Colours left as taken")
            add(if (plan.curve != null) "Corrected an obvious exposure problem" else "Exposure left as taken")
        }
    }
}

/**
 * The before-and-after checks. The structural steps run on samples of the photo and are compared
 * with the original: if texture goes waxy, edges get halos, colours shift or the picture stops
 * matching closely, the step at fault is halved and tried again, and dropped after a few tries.
 */
internal object Checks {

    private const val ROUNDS = 4

    /** Texture must keep at least this much of its fine detail, and gain no more than this. */
    private const val MIN_TEXTURE = 0.75f
    private const val MAX_TEXTURE = 1.45f
    private const val MAX_HALO_SHARE = 0.005f
    private const val MAX_COLOUR_SHIFT = 2.5f / 255f
    private const val MIN_SIMILARITY = 0.93f

    data class Verdict(val texture: Float, val halos: Float, val colourShift: Float, val similarity: Float)

    fun refine(source: PixelSource, a: Analysis, start: Plan): Plan {
        var plan = start
        val samples = Analyser.sampleTiles(a.width, a.height, 160, columns = 3, rows = 2)
        repeat(ROUNDS) { round ->
            val v = measure(source, a, plan, samples)
            var next = plan
            if (v.texture < MIN_TEXTURE) next = next.copy(lumaDenoise = next.lumaDenoise * 0.5f)
            if (v.texture > MAX_TEXTURE || v.halos > MAX_HALO_SHARE) next = next.copy(sharpen = next.sharpen * 0.5f)
            if (v.colourShift > MAX_COLOUR_SHIFT) {
                next = next.copy(chromaDenoise = next.chromaDenoise * 0.5f, redScale = 1f, blueScale = 1f)
            }
            if (v.similarity < MIN_SIMILARITY) {
                next = next.copy(deblock = next.deblock * 0.5f, lumaDenoise = next.lumaDenoise * 0.6f, sharpen = next.sharpen * 0.6f)
            }
            if (next == plan) return plan
            plan = next
            if (round == ROUNDS - 1) {
                // Still failing after halving: leave the photo's structure as it was.
                return plan.copy(deblock = 0f, lumaDenoise = 0f, chromaDenoise = 0f, sharpen = 0f, redScale = 1f, blueScale = 1f)
            }
        }
        return plan
    }

    fun measure(source: PixelSource, a: Analysis, plan: Plan, samples: List<IntArray>): Verdict {
        val margin = PhotoOptimiser.margin(a, plan)
        var textureOrig = 0.0
        var textureNew = 0.0
        var halos = 0
        var pixels = 0
        var shift = 0.0
        var blocks = 0
        var similarity = 0.0
        for ((x, y, w, h) in samples) {
            val rx0 = max(0, x - margin)
            val ry0 = max(0, y - margin)
            val rx1 = min(a.width, x + w + margin)
            val ry1 = min(a.height, y + h + margin)
            val original = Planes.fromArgb(rx0, ry0, rx1 - rx0, ry1 - ry0, source.read(rx0, ry0, rx1 - rx0, ry1 - ry0))
            val result = original.copy()
            PhotoOptimiser.restore(result, a, plan)
            val o = original.crop(x, y, w, h)
            val r = result.crop(x, y, w, h)
            pixels += w * h
            // Fine detail where the original has texture beyond its noise.
            for (yy in 2 until h - 2) {
                for (xx in 2 until w - 2) {
                    val i = yy * w + xx
                    if (localSpread(o.luma, w, xx, yy) < 3f * a.noise + 2f / 255f) continue
                    val ho = o.luma[i] - mean3(o.luma, w, xx, yy)
                    val hr = r.luma[i] - mean3(r.luma, w, xx, yy)
                    textureOrig += ho * ho
                    textureNew += hr * hr
                }
            }
            // Halos: a result brighter or darker than anything near it in the original.
            for (yy in 1 until h - 1) {
                for (xx in 1 until w - 1) {
                    var lo = 1f
                    var hi = 0f
                    for (dy in -1..1) for (dx in -1..1) {
                        val v = o.luma[(yy + dy) * w + xx + dx]
                        lo = min(lo, v)
                        hi = max(hi, v)
                    }
                    val v = r.luma[yy * w + xx]
                    if (v > hi + 4f / 255f || v < lo - 4f / 255f) halos++
                }
            }
            // Colour of 8×8 areas: the structural steps must not move it.
            for (by in 0 until h - 7 step 8) {
                for (bx in 0 until w - 7 step 8) {
                    var db = 0f
                    var dr = 0f
                    for (yy in by until by + 8) for (xx in bx until bx + 8) {
                        val i = yy * w + xx
                        db += r.cb[i] - o.cb[i]
                        dr += r.cr[i] - o.cr[i]
                    }
                    shift += sqrt(((db / 64) * (db / 64) + (dr / 64) * (dr / 64)).toDouble())
                    blocks++
                }
            }
            similarity += ssim(o.luma, r.luma, w, h)
        }
        val texture = if (textureOrig > 0) sqrt(textureNew / textureOrig).toFloat() else 1f
        return Verdict(
            texture = texture,
            halos = halos.toFloat() / max(1, pixels),
            colourShift = (shift / max(1, blocks)).toFloat(),
            similarity = (similarity / samples.size).toFloat(),
        )
    }

    private fun mean3(v: FloatArray, w: Int, x: Int, y: Int): Float {
        var s = 0f
        for (dy in -1..1) for (dx in -1..1) s += v[(y + dy) * w + x + dx]
        return s / 9f
    }

    private fun localSpread(v: FloatArray, w: Int, x: Int, y: Int): Float {
        var lo = 1f
        var hi = 0f
        for (dy in -2..2) for (dx in -2..2) {
            val s = v[(y + dy) * w + x + dx]
            lo = min(lo, s)
            hi = max(hi, s)
        }
        return hi - lo
    }

    /** Structural similarity over 8×8 windows, 1 for identical pictures. */
    fun ssim(a: FloatArray, b: FloatArray, w: Int, h: Int): Double {
        val c1 = (0.01 * 0.01)
        val c2 = (0.03 * 0.03)
        var total = 0.0
        var n = 0
        for (by in 0 until h - 7 step 4) {
            for (bx in 0 until w - 7 step 4) {
                var ma = 0.0
                var mb = 0.0
                for (y in by until by + 8) for (x in bx until bx + 8) {
                    ma += a[y * w + x]
                    mb += b[y * w + x]
                }
                ma /= 64
                mb /= 64
                var va = 0.0
                var vb = 0.0
                var cov = 0.0
                for (y in by until by + 8) for (x in bx until bx + 8) {
                    val da = a[y * w + x] - ma
                    val db = b[y * w + x] - mb
                    va += da * da
                    vb += db * db
                    cov += da * db
                }
                va /= 63
                vb /= 63
                cov /= 63
                total += ((2 * ma * mb + c1) * (2 * cov + c2)) / ((ma * ma + mb * mb + c1) * (va + vb + c2))
                n++
            }
        }
        return if (n == 0) 1.0 else total / n
    }
}
