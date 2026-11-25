package com.camoron.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** One tab of the [SpotlightNavBar]: an icon, and the name screen readers say for it. */
data class SpotlightNavItem(
    val icon: ImageVector,
    val label: String,
)

/** The colours of the spotlight bar: dark frosted glass and a single mint-teal light. */
object Spotlight {
    val Light = Color(0xFF6EE7CF)
    private val Tint = Color(0xFF101315)
    val Fill = Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.10f).compositeOver(Tint.copy(alpha = 0.62f)), Tint.copy(alpha = 0.74f)),
    )
    val Stroke = Color.White.copy(alpha = 0.14f)
    val BarHeight = 64.dp
}

/**
 * A floating, frosted-glass tab bar with icons only. The chosen tab is lit from above: a teal bar
 * on the top edge throws a soft cone of light down onto its icon, and the light glides to whichever
 * tab is picked next.
 */
@Composable
fun SpotlightNavBar(
    items: List<SpotlightNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    // Where the light is, in tabs; a soft spring so it glides and settles rather than snaps.
    val position by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "spotlight position",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(Spotlight.BarHeight)
            .clip(shape)
            .background(Spotlight.Fill, shape)
            .drawBehind { drawSpotlight(position, items.size, moving = abs(position - selectedIndex)) }
            .border(1.dp, Spotlight.Stroke, shape),
    ) {
        Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { index, item ->
                SpotlightTab(
                    item = item,
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The light: a glowing bar along the top edge and a cone fading down from it over the tab. */
private fun DrawScope.drawSpotlight(position: Float, count: Int, moving: Float) {
    if (count == 0) return
    val slot = size.width / count
    val centre = slot * (position + 0.5f)
    val barWidth = 30.dp.toPx()
    val barHeight = 3.5.dp.toPx()
    // The beam dims a little while it travels, so it reads as one light moving, not two.
    val strength = 1f - 0.35f * moving.coerceIn(0f, 1f)

    // Soft halo where the light source sits.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Spotlight.Light.copy(alpha = 0.28f * strength), Color.Transparent),
            center = Offset(centre, 0f),
            radius = 40.dp.toPx(),
        ),
        radius = 40.dp.toPx(),
        center = Offset(centre, 0f),
    )
    // The cone, narrow at the bar and wider over the icon.
    val topHalf = barWidth / 2f
    val bottomHalf = slot * 0.46f
    val cone = Path().apply {
        moveTo(centre - topHalf, 0f)
        lineTo(centre + topHalf, 0f)
        lineTo(centre + bottomHalf, size.height)
        lineTo(centre - bottomHalf, size.height)
        close()
    }
    drawPath(
        cone,
        Brush.verticalGradient(
            0f to Spotlight.Light.copy(alpha = 0.34f * strength),
            0.65f to Spotlight.Light.copy(alpha = 0.08f * strength),
            1f to Color.Transparent,
            startY = 0f,
            endY = size.height,
        ),
    )
    // The bar itself, with a faint glow just under it.
    drawRoundRect(
        color = Spotlight.Light.copy(alpha = 0.35f * strength),
        topLeft = Offset(centre - barWidth / 2f - 3.dp.toPx(), 0f),
        size = Size(barWidth + 6.dp.toPx(), barHeight + 3.dp.toPx()),
        cornerRadius = CornerRadius(barHeight),
    )
    drawRoundRect(
        color = Spotlight.Light,
        topLeft = Offset(centre - barWidth / 2f, 0f),
        size = Size(barWidth, barHeight),
        cornerRadius = CornerRadius(barHeight / 2f),
    )
}

@Composable
private fun SpotlightTab(item: SpotlightNavItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tint by animateColorAsState(
        if (selected) Color.White else Color.White.copy(alpha = 0.62f),
        animationSpec = tween(220),
        label = "tab tint",
    )
    val scale by animateFloatAsState(if (selected) 1.08f else 1f, animationSpec = spring(stiffness = Spring.StiffnessMedium), label = "tab scale")
    Box(
        modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                // The light itself is the feedback; no grey ripple over the glass.
                indication = null,
            )
            .semantics { contentDescription = item.label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            item.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .padding(top = 4.dp)
                .size(26.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

/**
 * The bar folded away to a short glowing line, for screens where it must not compete with the main
 * control (the camera's shutter). Tapping it brings the full bar back.
 */
@Composable
fun SpotlightNavHandle(onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(72.dp)
            .height(20.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = label,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(40.dp)
                .height(5.dp)
                .drawBehind {
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            listOf(Spotlight.Light.copy(alpha = 0.45f), Color.Transparent),
                            center = center,
                            radius = size.width,
                        ),
                        topLeft = Offset(-12.dp.toPx(), -8.dp.toPx()),
                        size = Size(size.width + 24.dp.toPx(), size.height + 16.dp.toPx()),
                        cornerRadius = CornerRadius(12.dp.toPx()),
                    )
                }
                .clip(RoundedCornerShape(50))
                .background(Spotlight.Light.copy(alpha = 0.9f)),
        )
    }
}

@Preview(widthDp = 380, heightDp = 120, backgroundColor = 0xFF2A2724, showBackground = true)
@Composable
private fun SpotlightNavBarPreview() {
    CamoronTheme {
        Box(Modifier.padding(16.dp)) {
            SpotlightNavBar(
                items = listOf(
                    SpotlightNavItem(Icons.Outlined.PhotoCamera, "Camera"),
                    SpotlightNavItem(Icons.Outlined.PhotoLibrary, "Gallery"),
                    SpotlightNavItem(Icons.Outlined.Edit, "Edit"),
                    SpotlightNavItem(Icons.Outlined.AutoAwesome, "AI tools"),
                    SpotlightNavItem(Icons.Outlined.Person, "Profile"),
                ),
                selectedIndex = 1,
                onSelect = {},
            )
        }
    }
}
