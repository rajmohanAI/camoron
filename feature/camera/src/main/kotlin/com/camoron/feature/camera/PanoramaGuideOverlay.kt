package com.camoron.feature.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import com.camoron.domain.model.PanoramaPlan

/**
 * The panorama guide while sweeping: a level line across the middle (or, for a vertical sweep, a
 * line up the middle) with the current heading as a dot in the centre, and a ring for the next frame that slides towards it as the phone turns, with
 * dots for the frames after it. Along the bottom, a strip shows the frames taken so far.
 */
@Composable
internal fun PanoramaGuideOverlay(guide: PanoramaGuide, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        val vertical = guide.vertical
        val track = if (vertical) Modifier.fillMaxHeight().width(64.dp) else Modifier.fillMaxWidth().height(64.dp)
        Canvas(track.align(Alignment.Center)) {
            // Positions along the sweep: sideways left to right, or vertically with "up" at the top.
            val length = if (vertical) size.height else size.width
            val across = (if (vertical) size.width else size.height) / 2
            fun at(along: Float) = if (vertical) Offset(across, length - along) else Offset(along, across)
            val centre = length / 2
            val spacing = length * SPACING
            val ring = 15.dp.toPx()
            // Before a direction is chosen, both ways are shown; the side the phone leans to leads.
            val ways = if (guide.direction != 0f) listOf(guide.direction) else listOf(1f, -1f)
            val band = 28.dp.toPx()
            if (vertical) {
                drawRect(Color.Black.copy(alpha = 0.35f), topLeft = Offset(across - band / 2, 0f), size = Size(band, length))
            } else {
                drawRect(Color.Black.copy(alpha = 0.35f), topLeft = Offset(0f, across - band / 2), size = Size(length, band))
            }
            for (way in ways) {
                val lead = guide.direction != 0f || way == guide.leaning
                val moved = if (lead) guide.progress else 0f
                val target = centre + way * spacing * (1f - moved)
                drawLine(Color.White.copy(alpha = 0.8f), at(centre), at(if (way > 0) length else 0f), strokeWidth = 2.dp.toPx())
                for (k in 1 until DOTS) {
                    val p = target + way * spacing * k
                    if (p in 0f..length) drawCircle(Color.White, radius = 4.dp.toPx(), center = at(p))
                }
                drawCircle(Glass.Accent.copy(alpha = if (lead) 1f else 0.5f), radius = ring, center = at(target), style = Stroke(width = 6.dp.toPx()))
            }
            // Where the phone points now.
            drawCircle(Color.White, radius = 9.dp.toPx(), center = at(centre))
            drawCircle(Glass.Accent, radius = 5.dp.toPx(), center = at(centre))
        }

        // The panorama so far, growing the way the phone turns.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .fillMaxWidth()
                .height(56.dp)
                .background(Color.Black.copy(alpha = 0.45f))
                .border(1.dp, Color.White.copy(alpha = 0.8f)),
        ) {
            val leftward = guide.direction < 0f
            CompositionLocalProvider(LocalLayoutDirection provides if (leftward) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Row(
                    Modifier.fillMaxHeight().align(if (guide.thumbnails.isEmpty()) Alignment.Center else Alignment.CenterStart),
                    horizontalArrangement = Arrangement.spacedBy((-6).dp),
                ) {
                    for (image in guide.thumbnails.takeLast(PanoramaPlan.MAX_FRAMES)) {
                        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxHeight().padding(vertical = 2.dp).aspectRatio(THUMB_ASPECT))
                    }
                }
            }
        }
    }
}


private const val SPACING = 0.22f
private const val DOTS = 4
/** Frames are taken upright, so each is taller than wide in the strip. */
private const val THUMB_ASPECT = 0.62f
