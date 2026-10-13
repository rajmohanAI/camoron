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
import com.camoron.domain.model.EnhanceParams
import com.camoron.domain.model.LookMatrix
import com.camoron.domain.model.ShotLook

/**
 * Draws the enhancement and the shot look live on the preview, on the GPU.
 *
 * On Android 13+ a small shader runs the exact tone curve and vibrance the photo gets. Older phones
 * get the closest colour-matrix approximation (levels and saturation), which a matrix can express.
 */
internal class PreviewFilter(private val view: View) {

    private var look: ShotLook = ShotLook.NORMAL
    private var enhance: EnhanceParams? = null
    private val shader: Any? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RuntimeShader(SHADER) else null

    fun update(look: ShotLook, enhance: EnhanceParams?) {
        if (look == this.look && enhance == this.enhance) return
        this.look = look
        this.enhance = enhance
        val matrix = LookMatrix.forLook(look)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applyShader(matrix, enhance)
        } else {
            applyMatrix(matrix, enhance)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applyShader(look: FloatArray, enhance: EnhanceParams?) {
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

        /** Mirrors EnhanceMath.curve and EnhanceMath.vibrant, then the look's colour matrix. */
        const val SHADER = """
            uniform shader content;
            uniform float black;
            uniform float white;
            uniform float gammaValue;
            uniform float contrast;
            uniform float lift;
            uniform float vibrance;
            uniform float4 lookR;
            uniform float4 lookG;
            uniform float4 lookB;

            half4 main(float2 coord) {
                half4 color = content.eval(coord);
                float3 x = clamp((float3(color.rgb) - black) / (white - black), 0.0, 1.0);
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
