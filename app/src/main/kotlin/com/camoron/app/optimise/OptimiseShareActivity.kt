package com.camoron.app.optimise

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.camoron.app.MainActivity
import com.camoron.app.R
import com.camoron.domain.repository.ConsentRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * "Optimise with Camoron" in Android's share menu. It takes a private copy of each shared photo
 * while it still may read them, queues them for [OptimiseWorker] and closes straight away; the
 * originals are never changed.
 */
@AndroidEntryPoint
class OptimiseShareActivity : ComponentActivity() {

    @Inject lateinit var consentRepository: ConsentRepository

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { queue() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        lifecycleScope.launch {
            if (!consentRepository.consent.first().hasAcceptedCurrentDisclaimer) {
                // The disclaimer comes first, as it does for the camera.
                Toast.makeText(this@OptimiseShareActivity, R.string.optimise_needs_setup, Toast.LENGTH_LONG).show()
                startActivity(Intent(this@OptimiseShareActivity, MainActivity::class.java))
                finish()
                return@launch
            }
            if (shouldAskForNotifications()) {
                prefs().edit().putBoolean(ASKED_NOTIFICATIONS, true).apply()
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                queue()
            }
        }
    }

    /** Asks once, so the finished photo can be announced; optimising works either way. */
    private fun shouldAskForNotifications(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs().getBoolean(ASKED_NOTIFICATIONS, false)

    private fun queue() {
        val uris = sharedPhotos(intent)
        lifecycleScope.launch {
            val queued = withContext(Dispatchers.IO) { uris.count { copyAndQueue(it) } }
            val message = if (queued == 0) {
                getString(R.string.optimise_nothing)
            } else {
                resources.getQuantityString(R.plurals.optimise_started, queued, queued)
            }
            Toast.makeText(this@OptimiseShareActivity, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun copyAndQueue(uri: Uri): Boolean = runCatching {
        val dir = File(cacheDir, "optimise").apply { mkdirs() }
        val copy = File(dir, UUID.randomUUID().toString())
        contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } } ?: return false
        val (name, taken) = describe(uri)
        OptimiseWorker.enqueue(applicationContext, copy, name, taken)
        true
    }.getOrDefault(false)

    /** The photo's file name and when it was taken, where the sharing app says. */
    private fun describe(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var taken: Long? = null
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN).takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { taken = cursor.getLong(it).takeIf { t -> t > 0 } }
                }
            }
        }
        return (name ?: "photo") to (taken ?: System.currentTimeMillis())
    }

    private fun sharedPhotos(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }.take(MAX_PHOTOS)

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS = "optimise"
        const val ASKED_NOTIFICATIONS = "asked_notifications"
        const val MAX_PHOTOS = 50
    }
}
