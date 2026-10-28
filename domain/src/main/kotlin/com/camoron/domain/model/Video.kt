package com.camoron.domain.model

import kotlin.math.roundToLong

/** The kinds of video, each with its own frame rate and sound. */
enum class VideoKind(
    /** Frame rate of the saved file. */
    val outputFps: Int,
    val recordsSound: Boolean,
) {
    STANDARD(outputFps = 30, recordsSound = true),

    /** 24 frames a second, the rate films are shot at, for the motion blur and cadence of cinema. */
    CINEMATIC(outputFps = 24, recordsSound = true),

    /** One frame every so often, played back at 30 frames a second; silent. */
    TIMELAPSE(outputFps = 30, recordsSound = false),

    /** Filmed at a high frame rate (120 frames a second where the camera can) and played back at 30, so slower; silent. */
    SLOW_MOTION(outputFps = 30, recordsSound = false),
}

/** Recording resolution, as the sensor sees it (landscape). Saved videos are always 16:9. */
enum class VideoResolution(val width: Int, val height: Int) {
    FHD(1920, 1080),
    UHD(3840, 2160);

    val size: PixelSize get() = PixelSize(width, height)
}

/** How much faster than real time a timelapse plays: 15× captures a frame every half second. */
enum class TimelapseSpeed(val factor: Int) { X5(5), X15(15), X30(30), X60(60) }

/** Everything that decides how a video session is set up. */
data class VideoSpec(
    val kind: VideoKind,
    val resolution: VideoResolution = VideoResolution.FHD,
    val timelapseSpeed: TimelapseSpeed = TimelapseSpeed.X15,
)

object VideoMath {

    private const val BITS_PER_PIXEL = 0.32
    private const val MAX_BITRATE = 50_000_000
    private const val NANOS_PER_SECOND = 1_000_000_000L

    /** Encoder bitrate: generous enough that fine detail and the enhancement survive compression. */
    fun bitrate(size: PixelSize, fps: Int): Int =
        (size.width.toLong() * size.height * fps * BITS_PER_PIXEL).toLong().coerceAtMost(MAX_BITRATE.toLong()).toInt()

    /**
     * The camera frame-rate range for [kind], from the ranges the camera offers. A fixed rate gives
     * the smoothest motion; timelapse prefers the widest range so dim scenes get longer exposures.
     * Returns null when nothing suitable is offered and the camera's default should be kept.
     */
    fun chooseFpsRange(ranges: List<IntRange>, kind: VideoKind): IntRange? {
        fun best(fps: Int) = ranges.firstOrNull { it.first == fps && it.last == fps }
            ?: ranges.filter { it.last == fps }.maxByOrNull { it.first }
        return when (kind) {
            VideoKind.STANDARD -> best(30)
            VideoKind.CINEMATIC -> best(24) ?: best(30)
            VideoKind.TIMELAPSE -> ranges.filter { it.last == 30 }.minByOrNull { it.first } ?: best(30)
            // Without a high-speed session, the fastest steady rate the camera offers.
            VideoKind.SLOW_MOTION -> ranges.filter { it.first == it.last }.maxByOrNull { it.last } ?: ranges.maxByOrNull { it.last }
        }
    }

    /**
     * The high-speed recording for slow motion, from the [options] (size and frame-rate range) the
     * camera's high-speed mode offers: a steady rate of at least 120 frames a second, at the largest
     * 16:9 size up to 1080p, nearest 120 so each frame still gets light. Null when none fits.
     */
    fun chooseHighSpeed(options: List<Pair<PixelSize, IntRange>>): Pair<PixelSize, IntRange>? =
        options.filter { (size, range) ->
            range.first == range.last && range.last >= SLOW_MOTION_FPS &&
                size.width <= VideoResolution.FHD.width && kotlin.math.abs(size.width * 9 - size.height * 16) <= size.height
        }.sortedWith(
            compareByDescending<Pair<PixelSize, IntRange>> { it.first.width.toLong() * it.first.height }
                .thenBy { it.second.last - SLOW_MOTION_FPS },
        ).firstOrNull()

    /** How many times slower than real time a slow-motion video filmed at [cameraFps] plays. */
    fun slowdown(cameraFps: Int): Int = (cameraFps / VideoKind.SLOW_MOTION.outputFps).coerceAtLeast(1)

    private const val SLOW_MOTION_FPS = 120

    /**
     * Rotation for the saved video. Frames are encoded landscape with the upright picture turned a
     * quarter turn when [contentTurned] (a sideways-mounted sensor, as on phones), and the phone's
     * own rotation while recording is added on top, the same way photos are oriented.
     */
    fun orientationHint(deviceRotationDegrees: Int, isFrontFacing: Boolean, contentTurned: Boolean): Int {
        val rounded = ((deviceRotationDegrees + 45) / 90 * 90) % 360
        val device = if (isFrontFacing) (360 - rounded) % 360 else rounded
        return ((if (contentTurned) 90 else 0) + device) % 360
    }

    /** Size of the preview buffer for a recording at [size]: upright, and at most 1080p to keep it light. */
    fun previewSize(size: PixelSize, contentTurned: Boolean): PixelSize {
        val scale = minOf(1.0, 1920.0 / maxOf(size.width, size.height))
        val width = (size.width * scale).toInt() / 2 * 2
        val height = (size.height * scale).toInt() / 2 * 2
        return if (contentTurned) PixelSize(height, width) else PixelSize(width, height)
    }

    /** "0:07", "12:30", "1:02:03": the recording time as camera apps show it. */
    fun formatDuration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    /** Frame pacing for [spec] when the camera runs at [cameraFpsRange] (null: the camera's default). */
    fun pacerFor(spec: VideoSpec, cameraFpsRange: IntRange?): FramePacer = when (spec.kind) {
        VideoKind.TIMELAPSE -> FramePacer.timelapse(
            captureIntervalNs = spec.timelapseSpeed.factor * NANOS_PER_SECOND / VideoKind.TIMELAPSE.outputFps,
            outputFps = VideoKind.TIMELAPSE.outputFps,
        )
        // Without a native 24 fps mode, keep every frame that lands on the 24 fps grid.
        VideoKind.CINEMATIC -> if (cameraFpsRange?.last == 24) FramePacer.everyFrame() else FramePacer.decimate(24)
        VideoKind.STANDARD -> FramePacer.everyFrame()
        // Every frame, spaced for playback at 30 frames a second, so the film plays slower.
        VideoKind.SLOW_MOTION -> FramePacer.slowMotion(VideoKind.SLOW_MOTION.outputFps)
    }
}

/**
 * Decides which camera frames go into the video and the timestamp each one gets, from the frames'
 * own timestamps in nanoseconds. Not thread-safe; used from the one thread that draws the frames.
 */
class FramePacer private constructor(
    /** Minimum time between kept frames, or 0 to keep every frame. */
    private val intervalNs: Long,
    /** For timelapse: the spacing of frames in the saved file; 0 keeps the camera's own timing. */
    private val outputFrameNs: Long,
) {
    private var nextDue = Long.MIN_VALUE
    private var firstPts = 0L
    private var kept = 0L

    /** Presentation time for the frame taken at [timestampNs], or null to leave it out. */
    fun next(timestampNs: Long): Long? {
        if (intervalNs > 0) {
            if (nextDue != Long.MIN_VALUE && timestampNs < nextDue - JITTER_NS) return null
            // Stay on the grid, but don't try to catch up after a stall.
            nextDue = if (nextDue == Long.MIN_VALUE || timestampNs - nextDue >= intervalNs) {
                timestampNs + intervalNs
            } else {
                nextDue + intervalNs
            }
        }
        if (outputFrameNs == 0L) return timestampNs
        if (kept == 0L) firstPts = timestampNs
        return firstPts + kept++ * outputFrameNs
    }

    companion object {
        /** A little slack so a frame arriving a hair early still counts. */
        private const val JITTER_NS = 2_000_000L

        fun everyFrame() = FramePacer(intervalNs = 0, outputFrameNs = 0)

        fun decimate(fps: Int) = FramePacer(intervalNs = 1_000_000_000L / fps, outputFrameNs = 0)

        fun slowMotion(outputFps: Int) = FramePacer(intervalNs = 0, outputFrameNs = (1_000_000_000.0 / outputFps).roundToLong())

        fun timelapse(captureIntervalNs: Long, outputFps: Int) = FramePacer(
            intervalNs = captureIntervalNs,
            outputFrameNs = (1_000_000_000.0 / outputFps).roundToLong(),
        )
    }
}
