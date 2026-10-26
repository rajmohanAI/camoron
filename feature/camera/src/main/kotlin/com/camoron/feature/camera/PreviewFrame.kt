package com.camoron.feature.camera

/**
 * Where the preview sits on the screen, in dp: its top edge, width and height. Switching mode
 * animates between two of these, so the picture glides to its new framing instead of jumping.
 */
internal data class PreviewFrame(val top: Float, val width: Float, val height: Float) {
    companion object {
        /**
         * The frame for a portrait photo shape of [ratio] (height over width) on a [screenWidth] x
         * [screenHeight] screen, like the phone's own camera: every shape fills the screen's width,
         * so taller shapes (9:16, full screen) show a bigger picture rather than a narrower one.
         * The frame starts below the top bar when it fits between [topInset] and [bottomInset];
         * taller ones run behind the controls, and full screen covers the whole screen.
         */
        fun photo(ratio: Float, screenWidth: Float, screenHeight: Float, topInset: Float, bottomInset: Float): PreviewFrame {
            val room = (screenHeight - topInset - bottomInset).coerceAtLeast(0f)
            val height = screenWidth * ratio
            return when {
                height <= room -> PreviewFrame(topInset, screenWidth, height)
                height <= screenHeight -> PreviewFrame(minOf(topInset, (screenHeight - height) / 2f), screenWidth, height)
                // Only on unusually short screens: as tall as the screen, keeping the shape.
                else -> PreviewFrame(0f, screenHeight / ratio, screenHeight)
            }
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
