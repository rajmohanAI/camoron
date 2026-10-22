package com.camoron.domain.repository

import com.camoron.domain.model.CapturedImage
import com.camoron.domain.model.FaceScene
import com.camoron.domain.model.PhotoEdit

/** Photos for the in-app viewer and editor. */
interface PhotoLibrary {
    /** Content URIs of Camoron's photos, newest first. */
    suspend fun recentPhotos(limit: Int): List<String>

    /** Reads a photo (Camoron's or one another app shared for editing) with the time it was taken. */
    suspend fun read(uri: String): CapturedImage

    /** Saves an edited copy next to the original and returns its content URI. The original is untouched. */
    suspend fun saveEdited(image: CapturedImage): String
}

/** Renders an edit onto the full-size photo. */
interface PhotoEditor {
    /**
     * The finished JPEG, upright, with the original's camera details carried across. [scene] is
     * where the faces and hair are, for try-ons.
     */
    suspend fun render(photo: CapturedImage, edit: PhotoEdit, scene: FaceScene = FaceScene.EMPTY): CapturedImage
}
