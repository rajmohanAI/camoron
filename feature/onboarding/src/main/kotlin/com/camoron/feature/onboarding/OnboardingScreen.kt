package com.camoron.feature.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.camoron.domain.model.DeviceSupport

/** Runtime permissions the camera flow needs on this OS version. */
fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
}.toTypedArray()

fun hasRequiredPermissions(context: android.content.Context): Boolean = requiredPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/**
 * Consent gate shown before the camera ever opens: the capability disclaimer must be accepted,
 * then camera permission granted. [onFinished] fires once both are satisfied.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var permissionDenied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) onFinished() else permissionDenied = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)

        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            !state.disclaimerAccepted -> DisclaimerStep(state.deviceSupport, onAccept = viewModel::acceptDisclaimer)
            else -> PermissionStep(
                denied = permissionDenied,
                onRequest = {
                    if (hasRequiredPermissions(context)) onFinished() else launcher.launch(requiredPermissions())
                },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
            )
        }
    }
}

@Composable
private fun DisclaimerStep(deviceSupport: DeviceSupport?, onAccept: () -> Unit) {
    var checked by rememberSaveable { mutableStateOf(false) }

    Text(stringResource(R.string.disclaimer_heading), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.disclaimer_body), style = MaterialTheme.typography.bodyLarge)
    Text(
        stringResource(R.string.disclaimer_privacy),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (deviceSupport is DeviceSupport.BelowMinimum) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.device_below_minimum_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.device_below_minimum_body), style = MaterialTheme.typography.bodyMedium)
                deviceSupport.reasons.forEach { reason ->
                    val text = when (reason) {
                        DeviceSupport.Reason.HARDWARE_LEVEL_TOO_LOW -> R.string.reason_hardware_level
                        DeviceSupport.Reason.RESOLUTION_TOO_LOW -> R.string.reason_resolution
                        DeviceSupport.Reason.NO_BACK_CAMERA -> R.string.reason_no_back_camera
                    }
                    Text("• " + stringResource(text), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { checked = it })
        Text(stringResource(R.string.disclaimer_accept))
    }
    Button(onClick = onAccept, enabled = checked, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_continue))
    }
}

@Composable
private fun PermissionStep(denied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Text(stringResource(R.string.permission_heading), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.permission_body), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(8.dp))
    Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_allow_camera))
    }
    if (denied) {
        Text(stringResource(R.string.permission_denied), color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_open_settings))
        }
    }
}
