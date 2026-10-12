package com.camoron.domain.repository

import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.PhotoAdjustments

/** Post-capture stage that makes the saved JPEG match what the user framed. */
interface PhotoProcessor {
    suspend fun apply(image: CapturedImage, adjustments: PhotoAdjustments): CapturedImage
}
