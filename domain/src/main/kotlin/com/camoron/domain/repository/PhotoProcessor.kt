package com.camoron.domain.repository

import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.PhotoAdjustments
import com.camoron.domain.model.RawBurst

/** Post-capture stage that makes the saved JPEG match what the user framed. */
interface PhotoProcessor {
    suspend fun apply(image: CapturedImage, adjustments: PhotoAdjustments): CapturedImage

    /**
     * Night mode: aligns a burst of same-exposure frames and averages them, which lowers noise by
     * roughly the square root of the frame count. Parts that moved between frames are taken from
     * the first frame only, so moving subjects don't ghost. Returns the first frame unchanged if the
     * merge can't run (for example, not enough memory).
     *
     * With [cropAspect] (as in [PhotoAdjustments.cropAspect]) only the part the photo will keep is
     * merged, which saves time, and the result is already that shape.
     */
    suspend fun mergeNight(frames: List<CapturedImage>, cropAspect: Float? = null): CapturedImage

    /**
     * Detail mode: aligns and merges a RAW [burst] tile by tile, then develops it (lens shading,
     * white balance, demosaicing, colour and tone) into a JPEG. Returns the camera's own JPEG of
     * the burst if developing fails.
     */
    suspend fun developRaw(burst: RawBurst): CapturedImage = burst.preview

    /**
     * Panorama: stitches [frames] taken as the phone turned, each at its [yaws] (radians, increasing
     * to the right), with a lens of [focalLength35mm] (35 mm equivalent; null for a typical phone
     * lens). Returns null when they can't be stitched.
     */
    suspend fun stitchPanorama(frames: List<CapturedImage>, yaws: List<Float>, focalLength35mm: Float?): CapturedImage?

    /**
     * Portrait's live look: [pixels] ([width]×[height] ARGB, upright, as on screen) come back as an
     * overlay of the blurred background with the subject left transparent, blurred by [strength]
     * (as for the photo: 1 is about f/1.8). Null when there is nothing to keep sharp.
     */
    suspend fun portraitPreview(pixels: IntArray, width: Int, height: Int, strength: Float): IntArray? = null
}
