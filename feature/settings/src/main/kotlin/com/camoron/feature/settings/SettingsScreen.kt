package com.camoron.feature.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCameraInfo: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    // Save location turns on only once the user has allowed location (precise or approximate).
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) viewModel.setGeotag(true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            val s = state.settings
            SwitchRow(R.string.selfie_mirror, R.string.selfie_mirror_desc, s.selfieMirror, viewModel::setSelfieMirror)
            SwitchRow(
                title = R.string.shutter_sound,
                description = if (state.shutterSoundLocked) R.string.shutter_sound_locked else R.string.shutter_sound_desc,
                checked = s.shutterSound || state.shutterSoundLocked,
                onChange = viewModel::setShutterSound,
                enabled = !state.shutterSoundLocked,
            )
            SwitchRow(
                R.string.af_tracking,
                R.string.af_tracking_desc,
                s.autoFocusTracking,
                viewModel::setAutoFocusTracking,
            )
            SwitchRow(R.string.phone_hdr, R.string.phone_hdr_desc, s.phoneHdr, viewModel::setPhoneHdr)
            SwitchRow(
                R.string.geotag,
                R.string.geotag_desc,
                s.geotag,
                onChange = { on ->
                    if (on) {
                        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    } else {
                        viewModel.setGeotag(false)
                    }
                },
            )

            SectionHeader(R.string.capture_assistant)
            SwitchRow(R.string.grid, R.string.grid_desc, s.grid, viewModel::setGrid)
            SwitchRow(R.string.leveller, R.string.leveller_desc, s.leveller, viewModel::setLeveller)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ActionRow(R.string.reset_settings, R.string.reset_settings_desc) { confirmReset = true }
            ActionRow(R.string.camera_info, R.string.camera_info_desc, onOpenCameraInfo)

            SectionHeader(R.string.about)
            Text(
                stringResource(R.string.about_disclaimer),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                stringResource(R.string.about_privacy),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.reset_confirm_title)) },
            text = { Text(stringResource(R.string.reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.reset()
                    confirmReset = false
                }) { Text(stringResource(R.string.action_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SwitchRow(
    title: Int,
    description: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        // The row handles clicks so the whole line is a touch target.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ActionRow(title: Int, description: Int, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
