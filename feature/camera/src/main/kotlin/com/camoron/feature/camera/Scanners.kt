package com.camoron.feature.camera

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.camoron.core.common.DiagnosticLog
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult

/**
 * QR and barcode scanning through Google's own scanner, which Play services runs: it needs no
 * camera permission or model in the app. Returns a function that opens it; [onResult] gets the
 * text of the code read.
 */
@Composable
internal fun rememberQrScanner(onResult: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val result by rememberUpdatedState(onResult)
    val failed = stringResource(R.string.scanner_unavailable)
    return {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code -> code.rawValue?.takeIf { it.isNotBlank() }?.let(result) }
            .addOnFailureListener {
                DiagnosticLog.record(TAG, "The QR scanner didn't open", it)
                Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
            }
    }
}

/**
 * Document scanning through Google's own scanner: it finds the page edges, straightens and cleans
 * up each page. Returns a function that opens it; [onPages] gets each scanned page as a JPEG URI.
 */
@Composable
internal fun rememberDocumentScanner(onPages: (List<Uri>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val pages by rememberUpdatedState(onPages)
    val failed = stringResource(R.string.scanner_unavailable)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { outcome ->
        if (outcome.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val scanned = GmsDocumentScanningResult.fromActivityResultIntent(outcome.data)
        val uris = scanned?.pages?.map { it.imageUri }.orEmpty()
        if (uris.isNotEmpty()) pages(uris)
    }
    return {
        val activity = context.findActivity()
        if (activity == null) {
            Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
        } else {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
                .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
                .addOnFailureListener {
                    DiagnosticLog.record(TAG, "The document scanner didn't open", it)
                    Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
                }
        }
    }
}

/** What a scanned code says, with ways to use it: open a link, copy or share the text. */
@Composable
internal fun QrResultDialog(text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val link = text.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qr_result_title)) },
        text = { Text(text) },
        confirmButton = {
            if (link != null) {
                TextButton(onClick = {
                    openLink(context, link)
                    onDismiss()
                }) { Text(stringResource(R.string.qr_open)) }
            } else {
                TextButton(onClick = {
                    share(context, text)
                    onDismiss()
                }) { Text(stringResource(R.string.qr_share)) }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                copy(context, text)
                onDismiss()
            }) { Text(stringResource(R.string.qr_copy)) }
        },
    )
}

private fun openLink(context: Context, link: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { DiagnosticLog.record(TAG, "No app opened the scanned link", it) }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("QR", text))
    Toast.makeText(context, context.getString(R.string.qr_copied), Toast.LENGTH_SHORT).show()
}

private const val TAG = "Scanners"
