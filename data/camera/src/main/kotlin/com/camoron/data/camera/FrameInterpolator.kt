package com.camoron.data.camera

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.camoron.domain.model.EnhanceParams
import com.camoron.domain.model.PixelSize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Slow motion where the camera only films at 30 frames a second: makes the frame halfway between
 * each pair of camera frames, so the film can play twice as slowly and still move smoothly.
 *
 * It follows how things moved between the two frames rather than cross-fading them (which would
 * show every moving edge twice). Motion is found coarse to fine on a brightness pyramid: a wide
 * search at a sixteenth of the size, tidied with a median so stray matches don't tear the picture,
 * then refined at an eighth, a quarter and a half to under a pixel. Each pixel of the middle frame is taken
 * from both neighbours, half the motion back and half forward, and the two are averaged.
 *
 * Everything runs on the GPU, in the video renderer's GL context and thread (OpenGL ES 2.0).
 */
internal class FrameInterpolator(private val size: PixelSize) {

    /** One camera frame: its colour at full size and its brightness at a half, quarter, eighth and sixteenth. */
    private inner class Frame {
        val full = Target(size.width, size.height, linear = true)
        val half = Target(level(2).width, level(2).height, linear = true)
        val quarter = Target(level(4).width, level(4).height, linear = true)
        val eighth = Target(level(8).width, level(8).height, linear = true)
        val sixteenth = Target(level(16).width, level(16).height, linear = true)
        fun release() = listOf(full, half, quarter, eighth, sixteenth).forEach { it.release() }
    }

    /** A texture that can be drawn into. */
    private class Target(val width: Int, val height: Int, linear: Boolean) {
        val texture: Int
        val framebuffer: Int

        init {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            texture = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            val filter = if (linear) GLES20.GL_LINEAR else GLES20.GL_NEAREST
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glGenFramebuffers(1, ids, 0)
            framebuffer = ids[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0)
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            check(status == GLES20.GL_FRAMEBUFFER_COMPLETE) { "Interpolation buffer incomplete: 0x${Integer.toHexString(status)}" }
        }

        fun release() {
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }

    private val copy = Program(CAMERA_VERTEX_SHADER, COPY_SHADER)
    private val reduce = Program(PASS_VERTEX_SHADER, REDUCE_SHADER)
    private val search = Program(PASS_VERTEX_SHADER, SEARCH_SHADER)
    private val median = Program(PASS_VERTEX_SHADER, MEDIAN_SHADER)
    private val refine = Program(PASS_VERTEX_SHADER, REFINE_SHADER)
    private val blend = Program(CAMERA_VERTEX_SHADER, BLEND_SHADER)

    private var previous = Frame()
    private var current = Frame()
    private var hasPrevious = false
    private var hasCurrent = false
    private val coarse = Target(level(16).width, level(16).height, linear = false)
    private val coarseTidy = Target(level(16).width, level(16).height, linear = false)
    private val eighthMotion = Target(level(8).width, level(8).height, linear = false)
    private val eighthTidy = Target(level(8).width, level(8).height, linear = false)
    private val middle = Target(level(4).width, level(4).height, linear = false)
    private val middleTidy = Target(level(4).width, level(4).height, linear = false)
    private val fine = Target(level(2).width, level(2).height, linear = false)
    private val fineTidy = Target(level(2).width, level(2).height, linear = false)

    private val quad = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uv = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private val identity = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    private fun level(factor: Int) = PixelSize(((size.width + factor - 1) / factor).coerceAtLeast(1), ((size.height + factor - 1) / factor).coerceAtLeast(1))

    /** Whether a middle frame can be made: two camera frames have been taken in. */
    val ready: Boolean get() = hasPrevious && hasCurrent

    /** Takes in the camera frame on [cameraTexture] (external, with its [texMatrix]); the last one becomes the previous. */
    fun add(cameraTexture: Int, texMatrix: FloatArray) {
        if (hasCurrent) {
            val swap = previous
            previous = current
            current = swap
            hasPrevious = true
        }
        val frame = current
        into(frame.full) {
            copy.use()
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
            GLES20.glUniform1i(copy.uniform("uTexture"), 1)
            GLES20.glUniformMatrix4fv(copy.uniform("uTexMatrix"), 1, false, texMatrix, 0)
            GLES20.glUniformMatrix4fv(copy.uniform("uPosMatrix"), 1, false, identity, 0)
            copy.drawQuad()
        }
        reduce(frame.full, frame.half, fromColour = true)
        reduce(frame.half, frame.quarter, fromColour = false)
        reduce(frame.quarter, frame.eighth, fromColour = false)
        reduce(frame.eighth, frame.sixteenth, fromColour = false)
        hasCurrent = true
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /**
     * Draws the frame halfway between the previous and the current one into the bound window
     * surface, sized [viewport] and placed by [position], graded like the camera frames.
     */
    fun drawMiddle(viewport: PixelSize, position: FloatArray, enhance: EnhanceParams, look: FloatArray) {
        check(ready) { "Two frames are needed" }
        // Coarse search, tidied, then refined twice.
        into(coarse) {
            search.use()
            bind(search, "uPrev", 1, previous.sixteenth)
            bind(search, "uCur", 2, current.sixteenth)
            GLES20.glUniform2f(search.uniform("uTexel"), 1f / coarse.width, 1f / coarse.height)
            GLES20.glUniform1f(search.uniform("uScale"), 16f)
            search.drawQuad()
        }
        tidy(coarse, coarseTidy)
        global = sharedMotion(coarseTidy)
        refine(coarseTidy, previous.eighth, current.eighth, eighthMotion, 8f, REFINE_WIDE)
        tidy(eighthMotion, eighthTidy)
        // Again from the finer field, which knows the shared motion to a pixel or two.
        global = sharedMotion(eighthTidy)
        refine(eighthTidy, previous.quarter, current.quarter, middle, 4f, REFINE_WIDE)
        tidy(middle, middleTidy)
        refine(middleTidy, previous.half, current.half, fine, 2f, REFINE_NARROW, useGlobal = false)
        tidy(fine, fineTidy)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewport.width, viewport.height)
        blend.use()
        bind(blend, "uPrev", 1, previous.full)
        bind(blend, "uCur", 2, current.full)
        bind(blend, "uMotion", 3, fineTidy)
        GLES20.glUniform2f(blend.uniform("uTexel"), 1f / size.width, 1f / size.height)
        GLES20.glUniform2f(blend.uniform("uMotionTexel"), 1f / fineTidy.width, 1f / fineTidy.height)
        GLES20.glUniform2f(blend.uniform("uGlobal"), global[0], global[1])
        GLES20.glUniformMatrix4fv(blend.uniform("uTexMatrix"), 1, false, identity, 0)
        GLES20.glUniformMatrix4fv(blend.uniform("uPosMatrix"), 1, false, position, 0)
        GLES20.glUniform1f(blend.uniform("uBlack"), enhance.black)
        GLES20.glUniform1f(blend.uniform("uWhite"), enhance.white)
        GLES20.glUniform1f(blend.uniform("uGamma"), enhance.gamma)
        GLES20.glUniform1f(blend.uniform("uContrast"), enhance.contrast)
        GLES20.glUniform1f(blend.uniform("uLift"), enhance.shadowLift)
        GLES20.glUniform1f(blend.uniform("uVibrance"), enhance.vibrance)
        GLES20.glUniform4f(blend.uniform("uLookR"), look[0], look[1], look[2], look[4] / 255f)
        GLES20.glUniform4f(blend.uniform("uLookG"), look[5], look[6], look[7], look[9] / 255f)
        GLES20.glUniform4f(blend.uniform("uLookB"), look[10], look[11], look[12], look[14] / 255f)
        blend.drawQuad()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /** Forgets the frames taken in, so the next recording doesn't start from the last one's picture. */
    fun reset() {
        hasPrevious = false
        hasCurrent = false
    }

    fun release() {
        previous.release()
        current.release()
        listOf(coarse, coarseTidy, eighthMotion, eighthTidy, middle, middleTidy, fine, fineTidy).forEach { it.release() }
        listOf(copy, reduce, search, median, refine, blend).forEach { it.release() }
    }

    private fun reduce(source: Target, target: Target, fromColour: Boolean) = into(target) {
        reduce.use()
        bind(reduce, "uSource", 1, source)
        GLES20.glUniform1f(reduce.uniform("uFromColour"), if (fromColour) 1f else 0f)
        reduce.drawQuad()
    }

    private var global = floatArrayOf(0f, 0f)
    private val readback: ByteBuffer = ByteBuffer.allocateDirect(level(8).width * level(8).height * 4).order(ByteOrder.nativeOrder())

    /** The coarse motion most of the picture shares, read back from [motion] (small, so quick). */
    private fun sharedMotion(motion: Target): FloatArray {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motion.framebuffer)
        readback.clear()
        GLES20.glReadPixels(0, 0, motion.width, motion.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val bytes = ByteArray(motion.width * motion.height * 4)
        readback.rewind()
        readback.get(bytes)
        return MotionVotes.shared(bytes)
    }

    /** A median over each pixel's neighbours, so lone wrong matches give way to the motion around them. */
    private fun tidy(source: Target, target: Target) = into(target) {
        median.use()
        bind(median, "uMotion", 1, source)
        GLES20.glUniform2f(median.uniform("uTexel"), 1f / source.width, 1f / source.height)
        median.drawQuad()
    }

    private fun refine(coarser: Target, prev: Target, cur: Target, target: Target, scale: Float, reach: Float, useGlobal: Boolean = true) = into(target) {
        refine.use()
        bind(refine, "uCoarse", 1, coarser)
        bind(refine, "uPrev", 2, prev)
        bind(refine, "uCur", 3, cur)
        GLES20.glUniform2f(refine.uniform("uTexel"), 1f / target.width, 1f / target.height)
        GLES20.glUniform2f(refine.uniform("uCoarseTexel"), 1f / coarser.width, 1f / coarser.height)
        GLES20.glUniform2f(refine.uniform("uGlobal"), global[0], global[1])
        GLES20.glUniform1f(refine.uniform("uUseGlobal"), if (useGlobal) 1f else 0f)
        GLES20.glUniform1f(refine.uniform("uScale"), scale)
        GLES20.glUniform1f(refine.uniform("uReach"), reach)
        refine.drawQuad()
    }

    private inline fun into(target: Target, block: () -> Unit) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, target.framebuffer)
        GLES20.glViewport(0, 0, target.width, target.height)
        block()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun bind(program: Program, name: String, unit: Int, target: Target) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target.texture)
        GLES20.glUniform1i(program.uniform(name), unit)
    }

    private inner class Program(vertexSource: String, fragmentSource: String) {
        val id: Int = link(vertexSource, fragmentSource)
        private val uniforms = HashMap<String, Int>()
        private val aPos = GLES20.glGetAttribLocation(id, "aPos")
        private val aUv = GLES20.glGetAttribLocation(id, "aUv")

        fun uniform(name: String): Int = uniforms.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }

        fun use() = GLES20.glUseProgram(id)

        fun drawQuad() {
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPos)
            GLES20.glDisableVertexAttribArray(aUv)
        }

        fun release() = GLES20.glDeleteProgram(id)
    }

    private fun link(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(status[0] == GLES20.GL_TRUE) { "Interpolation shader link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Interpolation shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    internal companion object {
        /** How far each refinement looks around the coarser level's motion, in its own pixels. */
        const val REFINE_WIDE = 1f
        const val REFINE_NARROW = 0.5f

        fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }

        // The shaders below are also run by a WebGL test page during development, so they keep to
        // GLSL ES 1.00 and share nothing but the uniforms they declare.

        const val CAMERA_VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec4 aUv;
            uniform mat4 uTexMatrix;
            uniform mat4 uPosMatrix;
            varying vec2 vUv;
            void main() {
                gl_Position = uPosMatrix * aPos;
                vUv = (uTexMatrix * aUv).xy;
            }
        """

        const val PASS_VERTEX_SHADER = """
            attribute vec4 aPos;
            attribute vec4 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = aPos;
                vUv = aUv.xy;
            }
        """

        /** The camera frame as it is, before grading, so motion is matched on the real picture. */
        const val COPY_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = vec4(texture2D(uTexture, vUv).rgb, 1.0);
            }
        """

        /**
         * Half the size: the target pixel's centre falls where four source pixels meet, so one
         * filtered read is their average. From the colour frame it also turns colour to brightness.
         */
        const val REDUCE_SHADER = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uSource;
            uniform float uFromColour;
            void main() {
                vec3 c = texture2D(uSource, vUv).rgb;
                float y = mix(c.r, dot(c, vec3(0.299, 0.587, 0.114)), uFromColour);
                gl_FragColor = vec4(y, y, y, 1.0);
            }
        """

        /**
         * Motion is kept as half the movement from the previous frame to the current one, in
         * full-size pixels, in two bytes per axis (up to 64 pixels either way, to a hundredth).
         */
        private const val MOTION_CODE = """
            vec4 encodeMotion(vec2 d) {
                vec2 v = clamp((d + 64.0) / 128.0, 0.0, 1.0) * 255.0;
                vec2 hi = floor(v);
                vec2 lo = clamp(floor((v - hi) * 255.0 + 0.5), 0.0, 255.0);
                return vec4(hi.x, lo.x, hi.y, lo.y) / 255.0;
            }
            vec2 decodeMotion(vec4 c) {
                vec4 b = floor(c * 255.0 + 0.5);
                vec2 v = (vec2(b.x, b.z) + vec2(b.y, b.w) / 255.0) / 255.0;
                return v * 128.0 - 64.0;
            }
        """

        /**
         * How badly the previous frame at [p] - [d] and the current one at [p] + [d] disagree over
         * a square patch PATCH pixels from the centre each way (positions and [d] in texture
         * units, [t] one pixel), on average.
         */
        private const val PATCH_COST = """
            float patchCost(sampler2D prev, sampler2D cur, vec2 p, vec2 d, vec2 t) {
                float sum = 0.0;
                // Its own loop names: some shader compilers mix them up with the caller's once inlined.
                for (int pj = -PATCH; pj <= PATCH; pj++) {
                    for (int pi = -PATCH; pi <= PATCH; pi++) {
                        vec2 o = vec2(float(pi), float(pj)) * t;
                        sum += abs(texture2D(prev, p - d + o).r - texture2D(cur, p + d + o).r);
                    }
                }
                return sum / float((2 * PATCH + 1) * (2 * PATCH + 1));
            }
        """

        /** Tries every whole-pixel motion up to 4 pixels each way at a sixteenth of the size. */
        const val SEARCH_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uPrev;
            uniform sampler2D uCur;
            uniform vec2 uTexel;
            uniform float uScale;
            // A wide patch: this small, a 3×3 one matches too many places.
            #define PATCH 2
            $MOTION_CODE
            $PATCH_COST
            void main() {
                vec2 best = vec2(0.0);
                // Standing still is preferred unless a move matches clearly better, so flat areas
                // and noise don't pick up motion they don't have.
                float bestCost = patchCost(uPrev, uCur, vUv, vec2(0.0), uTexel) - 0.003;
                for (int j = -4; j <= 4; j++) {
                    for (int i = -4; i <= 4; i++) {
                        vec2 d = vec2(float(i), float(j));
                        float cost = patchCost(uPrev, uCur, vUv, d * uTexel, uTexel) + 0.0005 * length(d);
                        if (cost < bestCost) {
                            bestCost = cost;
                            best = d;
                        }
                    }
                }
                gl_FragColor = encodeMotion(best * uScale);
            }
        """

        /** The 3×3 neighbour whose motion is closest to all the others', which drops lone mismatches. */
        const val MEDIAN_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uMotion;
            uniform vec2 uTexel;
            $MOTION_CODE
            void main() {
                vec2 m[9];
                for (int j = 0; j < 3; j++) {
                    for (int i = 0; i < 3; i++) {
                        m[j * 3 + i] = decodeMotion(texture2D(uMotion, vUv + vec2(float(i - 1), float(j - 1)) * uTexel));
                    }
                }
                vec2 best = m[4];
                float bestSpread = 1e9;
                for (int a = 0; a < 9; a++) {
                    float spread = 0.0;
                    for (int b = 0; b < 9; b++) spread += length(m[a] - m[b]);
                    if (spread < bestSpread) {
                        bestSpread = spread;
                        best = m[a];
                    }
                }
                gl_FragColor = encodeMotion(best);
            }
        """

        /**
         * Refines the coarser level's motion: first picks the best of it and its eight neighbours'
         * (so a part the coarse search got wrong takes its neighbours' motion when that fits
         * better), then looks around that in half-pixel steps, up to [uReach] pixels each way.
         */
        const val REFINE_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uCoarse;
            uniform sampler2D uPrev;
            uniform sampler2D uCur;
            uniform vec2 uTexel;
            uniform vec2 uCoarseTexel;
            uniform float uScale;
            uniform float uReach;
            // The motion most of the picture shares (usually the phone's own movement), in full-size pixels.
            uniform vec2 uGlobal;
            // 0 once the levels are finer than the shared motion is known to.
            uniform float uUseGlobal;
            #define PATCH 1
            $MOTION_CODE
            $PATCH_COST
            void main() {
                vec2 own = decodeMotion(texture2D(uCoarse, vUv)) / uScale;
                vec2 start = own;
                float startCost = patchCost(uPrev, uCur, vUv, own * uTexel, uTexel);
                // Where the picture has little detail to go on, moving with everything else is the
                // safest guess, so the shared motion wins ties.
                vec2 shared = uGlobal / uScale;
                float sharedCost = patchCost(uPrev, uCur, vUv, shared * uTexel, uTexel) - 0.002;
                if (uUseGlobal > 0.5 && sharedCost < startCost) {
                    startCost = sharedCost;
                    start = shared;
                }
                for (int nj = -1; nj <= 1; nj++) {
                    for (int ni = -1; ni <= 1; ni++) {
                        if (ni == 0 && nj == 0) continue;
                        vec2 d = decodeMotion(texture2D(uCoarse, vUv + vec2(float(ni), float(nj)) * uCoarseTexel)) / uScale;
                        // A neighbour's motion has to fit clearly better to replace this one's.
                        float cost = patchCost(uPrev, uCur, vUv, d * uTexel, uTexel) + 0.002;
                        if (cost < startCost) {
                            startCost = cost;
                            start = d;
                        }
                    }
                }
                vec2 best = start;
                float bestCost = startCost;
                for (int j = -2; j <= 2; j++) {
                    for (int i = -2; i <= 2; i++) {
                        vec2 off = vec2(float(i), float(j)) * 0.5;
                        if ((i == 0 && j == 0) || max(abs(off.x), abs(off.y)) > uReach) continue;
                        vec2 d = start + off;
                        float cost = patchCost(uPrev, uCur, vUv, d * uTexel, uTexel) + 0.0003 * length(off);
                        if (cost < bestCost) {
                            bestCost = cost;
                            best = d;
                        }
                    }
                }
                gl_FragColor = encodeMotion(best * uScale);
            }
        """

        /**
         * The middle frame: each pixel from both neighbours along its motion, then graded like the
         * video. Near the edges of moving things a pixel's own motion can belong to its neighbour,
         * so the motions a little way around it and the shared motion are tried too, each weighted
         * by how well the two frames agree along it.
         */
        const val BLEND_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform sampler2D uPrev;
            uniform sampler2D uCur;
            uniform sampler2D uMotion;
            uniform vec2 uTexel;
            uniform vec2 uMotionTexel;
            uniform vec2 uGlobal;
            uniform float uBlack;
            uniform float uWhite;
            uniform float uGamma;
            uniform float uContrast;
            uniform float uLift;
            uniform float uVibrance;
            uniform vec4 uLookR;
            uniform vec4 uLookG;
            uniform vec4 uLookB;
            $MOTION_CODE
            vec3 sum;
            float weights;
            void tryMotion(vec2 motion) {
                vec2 d = motion * uTexel;
                vec3 a = texture2D(uPrev, vUv - d).rgb;
                vec3 b = texture2D(uCur, vUv + d).rgb;
                // Agreement over a few pixels, not one, so the choice doesn't flicker on noise.
                vec2 o = 2.0 * uTexel;
                vec3 a1 = texture2D(uPrev, vUv - d + o).rgb;
                vec3 b1 = texture2D(uCur, vUv + d + o).rgb;
                vec3 a2 = texture2D(uPrev, vUv - d - o).rgb;
                vec3 b2 = texture2D(uCur, vUv + d - o).rgb;
                vec3 e3 = abs(a - b) + abs(a1 - b1) + abs(a2 - b2);
                float e = (e3.r + e3.g + e3.b) / 9.0;
                float w = exp(-e * 60.0) + 1e-6;
                sum += w * 0.5 * (a + b);
                weights += w;
            }
            vec2 motionAt(vec2 offset) {
                return decodeMotion(texture2D(uMotion, vUv + offset * uMotionTexel));
            }
            void main() {
                sum = vec3(0.0);
                weights = 0.0;
                tryMotion(motionAt(vec2(0.0)));
                tryMotion(uGlobal);
                tryMotion(motionAt(vec2(3.0, 0.0)));
                tryMotion(motionAt(vec2(-3.0, 0.0)));
                tryMotion(motionAt(vec2(0.0, 3.0)));
                tryMotion(motionAt(vec2(0.0, -3.0)));
                vec3 c = sum / weights;
                vec3 x = clamp((c - uBlack) / (uWhite - uBlack), 0.0, 1.0);
                x = pow(x, vec3(uGamma));
                vec3 s = x * x * (3.0 - 2.0 * x);
                x = mix(x, s, uContrast);
                x = x + uLift * x * (1.0 - x) * (1.0 - x) * 4.0;
                float luma = dot(x, vec3(0.2126, 0.7152, 0.0722));
                float saturation = max(x.r, max(x.g, x.b)) - min(x.r, min(x.g, x.b));
                x = clamp(luma + (x - luma) * (1.0 + uVibrance * (1.0 - saturation)), 0.0, 1.0);
                vec3 o = vec3(
                    dot(uLookR.rgb, x) + uLookR.a,
                    dot(uLookG.rgb, x) + uLookG.a,
                    dot(uLookB.rgb, x) + uLookB.a
                );
                gl_FragColor = vec4(clamp(o, 0.0, 1.0), 1.0);
            }
        """
    }
}
