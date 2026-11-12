package com.camoron.feature.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.camoron.domain.model.GeoStamp
import kotlinx.coroutines.delay

/** Geo Tag's card on the preview: what will be printed on the photo, kept up to date. */
@Composable
internal fun GeoTagCard(state: CameraUiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(CLOCK_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    val allowed = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    val point = state.geoPoint
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.LocationOn,
            contentDescription = null,
            tint = Color(0xFFE53935),
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFF1F3F4))
                .padding(6.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when {
                !allowed -> Text(stringResource(R.string.geotag_no_permission), color = Color.White, fontSize = 13.sp)
                point == null -> Text(stringResource(R.string.geotag_finding), color = Color.White, fontSize = 13.sp)
                else -> {
                    val lines = GeoStamp(point, state.geoPlace, now).lines()
                    val hasTitle = lines.size > 2
                    lines.forEachIndexed { index, line ->
                        val title = index == 0 && hasTitle
                        Text(
                            line,
                            color = Color.White,
                            fontSize = if (title) 15.sp else 12.sp,
                            fontWeight = if (title) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

private const val CLOCK_TICK_MS = 15_000L
