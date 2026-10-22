package com.camoron.feature.camera

/**
 * Where the preview sits on the screen, in dp: its top edge, width and height. Switching mode
 * animates between two of these, so the picture glides to its new framing instead of jumping.
 */
internal data class PreviewFrame(val top: Float, val width: Float, val height: Float) {
    companion object {
        /** The camera's full picture is 3:4 (height over width 4/3) when the phone is upright. */
        private const val SENSOR_RATIO = 4f / 3

        /**
         * The frame for a portrait photo shape of [ratio] (height over width) on a [screenWidth] x
         * [screenHeight] screen, between [topInset] and [bottomInset]. Every photo shape shows the
         * camera's picture at the same size and place, so changing shape never zooms or stretches
         * it: the full 3:4 picture fills the width, and taller shapes (9:16, full screen) are its
         * middle part at the same height, with dark sides. Narrower than the screen only where the
         * screen is too short for the 3:4 picture.
         */
        fun photo(ratio: Float, screenWidth: Float, screenHeight: Float, topInset: Float, bottomInset: Float): PreviewFrame {
            val room = (screenHeight - topInset - bottomInset).coerceAtLeast(0f)
            val pictureWidth = minOf(screenWidth, room / SENSOR_RATIO)
            val height = pictureWidth * SENSOR_RATIO
            return PreviewFrame(topInset, minOf(pictureWidth, height / ratio), height)
        }

        /**
         * The frame for video of [ratio] (height over width): the full width and as tall as the
         * shape needs, narrower only where the screen is too short, so it is exactly the recorded frame.
         */
        fun video(ratio: Float, screenWidth: Float, screenHeight: Float, topInset: Float, bottomInset: Float): PreviewFrame {
            val room = (screenHeight - topInset - bottomInset).coerceAtLeast(0f)
            val width = minOf(screenWidth, room / ratio)
            return PreviewFrame(topInset, width, width * ratio)
        }
    }
}
