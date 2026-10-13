package com.camoron.domain.repository

import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.FrameBurst
import com.camoron.domain.model.PhotoAdjustments

/** Post-capture stage that makes the saved JPEG match what the user framed. */
interface PhotoProcessor {
    suspend fun apply(image: CapturedImage, adjustments: PhotoAdjustments): CapturedImage

    /**
     * Night mode: aligns a burst of same-exposure frames and averages them, which lowers noise by
     * roughly the square root of the frame count. Parts that moved between frames are taken from
     * the first frame only, so moving subjects don't ghost. Returns the first frame unchanged if the
     * merge can't run (for example, not enough memory).
     */
    suspend fun mergeNight(frames: List<CapturedImage>): CapturedImage

    /**
     * Turns a burst into the saved JPEG: aligns and merges the frames (less noise, more real detail,
     * moving parts taken from one frame so they don't ghost), then crops, enhances and colours it
     * like [apply] does.
     */
    suspend fun render(burst: FrameBurst, adjustments: PhotoAdjustments): CapturedImage =
        error("This processor can't render frame bursts")
}
