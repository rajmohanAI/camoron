package com.camoron.data.camera

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import com.camoron.core.common.DiagnosticLog
import com.camoron.domain.model.EnhanceParams
import com.camoron.domain.model.FramePacer
import com.camoron.domain.model.LookMatrix
import com.camoron.domain.model.PixelSize
import com.camoron.domain.model.ShotLook
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Draws camera frames for video on the GPU: the camera streams into a texture, and every frame is
 * drawn through the same enhancement and look shader the photo preview uses, onto the preview and,
 * while recording, onto the video encoder. So what is on screen is what is in the file.
 *
 * Frames are drawn upright. For the encoder they are turned a quarter turn into a landscape frame
 * when [contentTurned] (a sideways-mounted sensor, as on phones), and the file is tagged to rotate
 * back on playback, so any encoder can take the full 16:9 size.
 *
 * All GL work happens on one dedicated thread; the public methods block until it is done.
 */
internal class VideoRenderer(
    private val previewSurface: Surface,
    private val previewSize: PixelSize,
    /** Size of the camera stream and of the encoded video (landscape). */
    private val videoSize: PixelSize,
    private val contentTurned: Boolean,
    /** The camera stamps frames with elapsedRealtime, which must be moved to the audio clock. */
    private val realtimeTimestamps: Boolean,
) {
    private val thread = HandlerThread("CamoronVideo").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var previewEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var pacer: FramePacer? = null
    private var clockOffsetNs = 0L

    private var program = 0
    private var texture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    private val texMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val turned = FloatArray(16).also { Matrix.setRotateM(it, 0, 90f, 0f, 0f, 1f) }
    private val quad = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uv = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    private var reportedError = false

    @Volatile
    private var filter = Filter(LookMatrix.forLook(ShotLook.NORMAL), null)

    private class Filter(val look: FloatArray, val enhance: EnhanceParams?)

    /** Sets up GL and returns the surface the camera should stream into. */
    fun start(): Surface = onGlThread {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No recordable EGL config"
        }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        previewEgl = windowSurface(previewSurface)
        makeCurrent(previewEgl)

        program = buildProgram()
        texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val input = SurfaceTexture(texture).apply {
            setDefaultBufferSize(videoSize.width, videoSize.height)
            setOnFrameAvailableListener({ drawFrame() }, handler)
        }
        surfaceTexture = input
        Surface(input).also { cameraSurface = it }
    }

    fun setFilter(look: FloatArray, enhance: EnhanceParams?) {
        filter = Filter(look, enhance)
    }

    /** Starts drawing frames into the encoder's input [surface], paced by [pacer]. */
    fun startEncoding(surface: Surface, pacer: FramePacer) = onGlThread {
        encoderEgl = windowSurface(surface)
        clockOffsetNs = if (realtimeTimestamps) SystemClock.elapsedRealtimeNanos() - System.nanoTime() else 0L
        this.pacer = pacer
    }

    /** Stops feeding the encoder; call before stopping it. */
    fun stopEncoding() = onGlThread {
        releaseEncoderSurface()
    }

    fun release() {
        runCatching {
            onGlThread {
                surfaceTexture?.setOnFrameAvailableListener(null)
                releaseEncoderSurface()
                if (previewEgl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previewEgl)
                previewEgl = EGL14.EGL_NO_SURFACE
                if (program != 0) GLES20.glDeleteProgram(program)
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                surfaceTexture?.release()
                cameraSurface?.release()
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                context = EGL14.EGL_NO_CONTEXT
                EGL14.eglReleaseThread()
            }
        }.onFailure { DiagnosticLog.record(TAG, "Couldn't release the video renderer", it) }
        thread.quitSafely()
    }

    /** GL thread, for every camera frame. */
    private fun drawFrame() {
        val input = surfaceTexture ?: return
        try {
            makeCurrent(previewEgl)
            input.updateTexImage()
            input.getTransformMatrix(texMatrix)
            draw(previewSize, identity)
            EGL14.eglSwapBuffers(display, previewEgl)

            if (encoderEgl == EGL14.EGL_NO_SURFACE) return
            val pts = pacer?.next(input.timestamp - clockOffsetNs) ?: return
            makeCurrent(encoderEgl)
            draw(videoSize, if (contentTurned) turned else identity)
            EGLExt.eglPresentationTimeANDROID(display, encoderEgl, pts)
            EGL14.eglSwapBuffers(display, encoderEgl)
        } catch (e: RuntimeException) {
            // Keep going: one bad frame shouldn't end the video. Report it once.
            if (!reportedError) DiagnosticLog.record(TAG, "Couldn't draw a video frame", e)
            reportedError = true
        }
    }

    private fun draw(size: PixelSize, position: FloatArray) {
        val f = filter
        val p = f.enhance ?: IDENTITY
        val look = f.look
        GLES20.glViewport(0, 0, size.width, size.height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(uniform("uTexture"), 0)
        GLES20.glUniformMatrix4fv(uniform("uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniformMatrix4fv(uniform("uPosMatrix"), 1, false, position, 0)
        GLES20.glUniform1f(uniform("uBlack"), p.black)
        GLES20.glUniform1f(uniform("uWhite"), p.white)
        GLES20.glUniform1f(uniform("uGamma"), p.gamma)
        GLES20.glUniform1f(uniform("uContrast"), p.contrast)
        GLES20.glUniform1f(uniform("uLift"), p.shadowLift)
        GLES20.glUniform1f(uniform("uVibrance"), p.vibrance)
        GLES20.glUniform4f(uniform("uLookR"), look[0], look[1], look[2], look[4] / 255f)
        GLES20.glUniform4f(uniform("uLookG"), look[5], look[6], look[7], look[9] / 255f)
        GLES20.glUniform4f(uniform("uLookB"), look[10], look[11], look[12], look[14] / 255f)

        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aUv = GLES20.glGetAttribLocation(program, "aUv")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    private val uniforms = HashMap<String, Int>()

    private fun uniform(name: String): Int = uniforms.getOrPut(name) { GLES20.glGetUniformLocation(program, name) }

    private fun releaseEncoderSurface() {
        pacer = null
        if (encoderEgl == EGL14.EGL_NO_SURFACE) return
        makeCurrent(previewEgl)
        EGL14.eglDestroySurface(display, encoderEgl)
        encoderEgl = EGL14.EGL_NO_SURFACE
    }

    /**
     * The camera may still be letting go of the preview surface from the photo session, so give
     * it a moment before giving up.
     */
    private fun windowSurface(surface: Surface): EGLSurface {
        repeat(SURFACE_ATTEMPTS) { attempt ->
            val egl = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (egl != null && egl != EGL14.EGL_NO_SURFACE) return egl
            if (attempt < SURFACE_ATTEMPTS - 1) SystemClock.sleep(SURFACE_RETRY_MS)
        }
        error("eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
    }

    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(status[0] == GLES20.GL_TRUE) { "Shader link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    /** Runs [block] on the GL thread and waits for it, rethrowing its failure. */
    private fun <T> onGlThread(block: () -> T): T {
        val task = FutureTask(block)
        check(handler.post(task)) { "Video renderer has stopped" }
        return try {
            task.get(GL_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private companion object {
        const val TAG = "Video"
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val SURFACE_ATTEMPTS = 10
        const val SURFACE_RETRY_MS = 50L
        const val GL_TIMEOUT_S = 5L

        /** Parameters that leave the picture unchanged, for when Enhance is off. */
        val IDENTITY = EnhanceParams(black = 0f, white = 1f, gamma = 1f, contrast = 0f, shadowLift = 0f, vibrance = 0f)

        fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }

        const val VERTEX_SHADER = """
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

        /** The photo preview shader (EnhanceMath.curve, EnhanceMath.vibrant, then the look), in GLSL. */
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vUv;
            uniform samplerExternalOES uTexture;
            uniform float uBlack;
            uniform float uWhite;
            uniform float uGamma;
            uniform float uContrast;
            uniform float uLift;
            uniform float uVibrance;
            uniform vec4 uLookR;
            uniform vec4 uLookG;
            uniform vec4 uLookB;
            void main() {
                vec3 x = clamp((texture2D(uTexture, vUv).rgb - uBlack) / (uWhite - uBlack), 0.0, 1.0);
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
