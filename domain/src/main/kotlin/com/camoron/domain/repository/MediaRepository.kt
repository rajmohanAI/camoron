package com.camoron.domain.repository

import com.camoron.domain.model.CapturedImage

interface MediaRepository {
    /** Saves the image to the shared gallery and returns its content URI as a string. */
    suspend fun save(image: CapturedImage): String
}
