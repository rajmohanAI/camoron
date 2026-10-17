package com.camoron.feature.camera

/**
 * Where the preview sits on the screen, in dp: its top edge, width and height. Switching photo
 * shape animates between two of these, so the picture glides to its new framing instead of jumping.
 */
internal data class PreviewFrame(val top: Float, val width: Float, val height: Float) {
    companion object {
        /**
         * The frame for a portrait photo shape of [ratio] (height over width) on a [screenWidth] x
         * [screenHeight] screen, between [topInset] and [bottomInset]; a null ratio is the whole
         * screen. It is the full width and as tall as the shape needs, narrower only where the
         * screen is too short, so it is always exactly the saved photo's shape.
         */
        fun of(ratio: Float?, screenWidth: Float, screenHeight: Float, topInset: Float, bottomInset: Float): PreviewFrame {
            if (ratio == null) return PreviewFrame(0f, screenWidth, screenHeight)
            val room = (screenHeight - topInset - bottomInset).coerceAtLeast(0f)
            val width = minOf(screenWidth, room / ratio)
            return PreviewFrame(topInset, width, width * ratio)
        }
    }
}
