package com.camoron.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A Pro mode control row. When [enabled] is false the slider is greyed out and [disabledReason]
 * tells the user why, instead of hiding the control entirely.
 */
@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledReason: String? = null,
    steps: Int = 0,
    onAuto: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                if (enabled) valueText else disabledReason.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onAuto != null && enabled) {
                TextButton(onClick = onAuto) { Text("Auto") }
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            steps = steps,
        )
    }
}
