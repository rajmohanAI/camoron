package com.camoron.feature.camera

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.TextureView
import android.view.View
import androidx.annotation.RequiresApi
import com.camoron.core.common.DiagnosticLog
import com.camoron.domain.model.EnhanceParams
import com.camoron.domain.model.LookMatrix
import com.camoron.domain.model.ShotLook

/**
 * Draws the enhancement and the shot look live on the preview, on the GPU.
 *
 * On Android 13+ a small shader runs the exact tone curve and vibrance the photo gets, plus a light
 * version of the photo's detail step: edge-preserving noise smoothing, then gentle sharpening, both
 * scaled to the camera's ISO. So what you frame already looks like the optimised photo. Older
 * phones get the closest colour-matrix approximation (levels and saturation).
 */
internal class PreviewFilter(private val view: View) {

    private var look: ShotLook = ShotLook.NORMAL
    private var enhance: EnhanceParams? = null
    private var isoBucket: Int = -1
    // A shader that fails to compile on some GPU falls back to the colour matrix instead of crashing.
    private val shader: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        runCatching { RuntimeShader(SHADER) }
            .onFailure { DiagnosticLog.record("PreviewFilter", "Preview shader unavailable; using the simpler filter", it) }
            .getOrNull()
    } else {
        null
    }

    /** [iso] is the camera's current sensitivity, which sets how much noise smoothing the preview gets. */
    fun update(look: ShotLook, enhance: EnhanceParams?, iso: Int? = null) {
        // Coarse ISO steps, so small exposure changes don't rebuild the effect every frame.
        val bucket = ((iso ?: DEFAULT_ISO) + ISO_STEP / 2) / ISO_STEP
        if (look == this.look && enhance == this.enhance && bucket == isoBucket) return
        this.look = look
        this.enhance = enhance
        this.isoBucket = bucket
        val matrix = LookMatrix.forLook(look)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
            applyShader(matrix, enhance, bucket * ISO_STEP)
        } else {
            applyMatrix(matrix, enhance)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applyShader(look: FloatArray, enhance: EnhanceParams?, iso: Int) {
        if (enhance == null && LookMatrix.isIdentity(look)) {
            view.setRenderEffect(null)
            return
        }
        val shader = shader as RuntimeShader
        val p = enhance ?: IDENTITY
        shader.setFloatUniform("black", p.black)
        shader.setFloatUniform("white", p.white)
        shader.setFloatUniform("gammaValue", p.gamma)
        shader.setFloatUniform("contrast", p.contrast)
        shader.setFloatUniform("lift", p.shadowLift)
        shader.setFloatUniform("vibrance", p.vibrance)
        // Same shape as the photo's tuning: more smoothing and less sharpening as noise rises.
        val noise = (BASE_NOISE + iso * NOISE_PER_ISO).coerceAtMost(MAX_NOISE)
        shader.setFloatUniform("noise", if (enhance != null) noise else 0f)
        shader.setFloatUniform("sharpen", if (enhance != null) (MAX_SHARPEN - noise * SHARPEN_PER_NOISE).coerceIn(MIN_SHARPEN, MAX_SHARPEN) else 0f)
        shader.setFloatUniform("lookR", look[0], look[1], look[2], look[4] / 255f)
        shader.setFloatUniform("lookG", look[5], look[6], look[7], look[9] / 255f)
        shader.setFloatUniform("lookB", look[10], look[11], look[12], look[14] / 255f)
        view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
    }

    private fun applyMatrix(look: FloatArray, enhance: EnhanceParams?) {
        val combined = ColorMatrix()
        if (enhance != null) {
            val gain = 1f / (enhance.white - enhance.black)
            val offset = -enhance.black * gain * 255f
            combined.set(
                floatArrayOf(
                    gain, 0f, 0f, 0f, offset,
                    0f, gain, 0f, 0f, offset,
                    0f, 0f, gain, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
            combined.postConcat(ColorMatrix().apply { setSaturation(1f + enhance.vibrance * 0.6f) })
        }
        combined.postConcat(ColorMatrix(look))
        val identity = enhance == null && LookMatrix.isIdentity(look)
        // TextureView draws through its own hardware layer, whose paint takes the colour filter.
        (view as? TextureView)?.setLayerPaint(
            if (identity) null else Paint().apply { colorFilter = ColorMatrixColorFilter(combined) },
        )
    }

    private companion object {
        /** Parameters that leave the picture unchanged, for a look without enhancement. */
        val IDENTITY = EnhanceParams(black = 0f, white = 1f, gamma = 1f, contrast = 0f, shadowLift = 0f, vibrance = 0f)

        const val DEFAULT_ISO = 100
        const val ISO_STEP = 100
        const val BASE_NOISE = 0.006f
        const val NOISE_PER_ISO = 0.000012f
        const val MAX_NOISE = 0.045f
        const val MAX_SHARPEN = 0.45f
        const val MIN_SHARPEN = 0.1f
        const val SHARPEN_PER_NOISE = 9f

        /** Mirrors EnhanceMath.curve and EnhanceMath.vibrant, then the look's colour matrix. */
        const val SHADER = """
            uniform shader content;
            uniform float black;
            uniform float white;
            uniform float gammaValue;
            uniform float contrast;
            uniform float lift;
            uniform float vibrance;
            uniform float noise;
            uniform float sharpen;
            uniform float4 lookR;
            uniform float4 lookG;
            uniform float4 lookB;

            float lumaOf(float3 c) { return dot(c, float3(0.299, 0.587, 0.114)); }

            half4 main(float2 coord) {
                half4 color = content.eval(coord);
                float3 rgb = float3(color.rgb);
                if (noise > 0.0) {
                    // Edge-preserving smoothing over the 3x3 neighbourhood: neighbours that differ
                    // by more than the noise barely count, so edges stay crisp.
                    float centre = lumaOf(rgb);
                    float3 sum = float3(0.0);
                    float3 box = float3(0.0);
                    float weights = 0.0;
                    float inv = 1.0 / (2.0 * noise * noise * 9.0);
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            float3 n = float3(content.eval(coord + float2(float(dx), float(dy))).rgb);
                            float d = lumaOf(n) - centre;
                            float w = exp(-d * d * inv);
                            sum += n * w;
                            weights += w;
                            box += n;
                        }
                    }
                    float3 smooth = sum / weights;
                    // Gentle unsharp mask on the cleaned picture.
                    rgb = clamp(smooth + sharpen * (smooth - box / 9.0), 0.0, 1.0);
                }
                float3 x = clamp((rgb - black) / (white - black), 0.0, 1.0);
                x = pow(x, float3(gammaValue));
                float3 s = x * x * (3.0 - 2.0 * x);
                x = mix(x, s, contrast);
                x = x + lift * x * (1.0 - x) * (1.0 - x) * 4.0;
                float luma = dot(x, float3(0.2126, 0.7152, 0.0722));
                float saturation = max(x.r, max(x.g, x.b)) - min(x.r, min(x.g, x.b));
                x = clamp(luma + (x - luma) * (1.0 + vibrance * (1.0 - saturation)), 0.0, 1.0);
                float3 o = float3(
                    dot(lookR.rgb, x) + lookR.a,
                    dot(lookG.rgb, x) + lookG.a,
                    dot(lookB.rgb, x) + lookB.a
                );
                return half4(half3(clamp(o, 0.0, 1.0)), color.a);
            }
        """
    }
}
