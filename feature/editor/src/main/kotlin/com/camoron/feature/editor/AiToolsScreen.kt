package com.camoron.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.camoron.core.designsystem.Spotlight

/**
 * The AI tools tab: Camoron's smart edits in one place. Each asks for a photo, then opens it in the
 * right tool.
 */
@Composable
fun AiToolsScreen(onOpenTool: (uri: String, tab: EditorTab) -> Unit, bottomPadding: Dp = 0.dp) {
    val context = LocalContext.current
    // Which tool the picked photo is for; null means Optimise, which runs outside the editor.
    var pending by rememberSaveable { mutableStateOf<EditorTab?>(null) }
    val pick = rememberPhotoPicker { uri ->
        val tab = pending
        if (tab == null) optimise(context, uri) else onOpenTool(uri, tab)
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.ai_tools), style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text(stringResource(R.string.ai_tools_hint), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f))
        }
        ToolCard(Icons.Outlined.AutoFixHigh, R.string.ai_eraser, R.string.ai_eraser_desc) {
            pending = EditorTab.ERASE
            pick()
        }
        ToolCard(Icons.Outlined.Face, R.string.ai_try_on, R.string.ai_try_on_desc) {
            pending = EditorTab.TRY_ON
            pick()
        }
        ToolCard(Icons.Outlined.AutoAwesome, R.string.ai_optimise, R.string.ai_optimise_desc) {
            pending = null
            pick()
        }
        Spacer(Modifier.height(bottomPadding))
    }
}

@Composable
private fun ToolCard(icon: ImageVector, title: Int, description: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Spotlight.Light.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Spotlight.Light)
        }
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(stringResource(description), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
        }
    }
}
