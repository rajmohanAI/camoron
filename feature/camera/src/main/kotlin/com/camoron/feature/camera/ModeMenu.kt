package com.camoron.feature.camera

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MovieCreation
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The modes always on the mode row; everything else lives under More. */
internal val MAIN_MODES = listOf(CaptureMode.VIDEO, CaptureMode.PHOTO, CaptureMode.PORTRAIT, CaptureMode.PRO)

/** The modes under More, in the order the More sheet shows them. */
internal val MORE_MODES = listOf(
    CaptureMode.DETAIL,
    CaptureMode.GEOTAG,
    CaptureMode.PANORAMA,
    CaptureMode.SKY,
    CaptureMode.SLOW_MOTION,
    CaptureMode.TIMELAPSE,
    CaptureMode.CINEMATIC,
)

/**
 * The mode row's entries: the main modes, the More mode in use (if any) beside them so it stays
 * visible and selected, then More itself (null).
 */
internal fun modeRowEntries(selected: CaptureMode): List<CaptureMode?> =
    MAIN_MODES + listOfNotNull(selected.takeIf { it in MORE_MODES }) + listOf(null)

/** A tool in the More sheet that isn't a camera mode, such as a scanner. */
internal class MoreTool(val label: Int, val icon: ImageVector, val onClick: () -> Unit)

/** The More sheet: every extra mode, then the tools, each as an icon and a name, like the phone's own camera. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoreModesSheet(
    selected: CaptureMode,
    onSelect: (CaptureMode) -> Unit,
    onDismiss: () -> Unit,
    tools: List<MoreTool> = emptyList(),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xF2121212),
        contentColor = Color.White,
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(MORE_MODES) { mode ->
                MoreTile(modeIcon(mode), stringResource(modeLabel(mode)), mode == selected) { onSelect(mode) }
            }
            items(tools) { tool ->
                MoreTile(tool.icon, stringResource(tool.label), selected = false, onClick = tool.onClick)
            }
        }
    }
}

@Composable
private fun MoreTile(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Glass.Accent else Color.White, modifier = Modifier.size(30.dp))
        Text(label, color = if (selected) Glass.Accent else Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

private fun modeIcon(mode: CaptureMode): ImageVector = when (mode) {
    CaptureMode.DETAIL -> Icons.Filled.HighQuality
    CaptureMode.GEOTAG -> Icons.Filled.LocationOn
    CaptureMode.PANORAMA -> Icons.Filled.Panorama
    CaptureMode.SKY -> Icons.Filled.NightsStay
    CaptureMode.SLOW_MOTION -> Icons.Filled.SlowMotionVideo
    CaptureMode.TIMELAPSE -> Icons.Filled.Timelapse
    CaptureMode.CINEMATIC -> Icons.Filled.MovieCreation
    else -> Icons.Filled.HighQuality
}
