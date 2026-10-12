package com.camoron.domain.model

/**
 * One of the zoom buttons above the shutter. [label] is what the user sees (0.5, 1, 2, 3);
 * [cameraId] and [zoomRatio] say how to get there, which may mean switching to a separate
 * ultra-wide camera rather than zooming the main one.
 */
data class ZoomPreset(
    val label: Float,
    val cameraId: String,
    val zoomRatio: Float,
)

/**
 * Where the camera is focusing, in normalised preview coordinates (0..1, portrait, as displayed).
 * [tracking] is true while the focus point follows a moving subject.
 */
data class FocusTarget(val x: Float, val y: Float, val tracking: Boolean)
