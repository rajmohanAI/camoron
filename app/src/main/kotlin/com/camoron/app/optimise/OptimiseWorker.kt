package com.camoron.app.optimise

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.camoron.app.R
import com.camoron.engine.OptimiseOptions
import com.camoron.engine.PhotoOptimiser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Optimises one photo in the background with the photo engine and saves the result as a new
 * picture. Photos queue one after another, so several shared at once never compete for memory.
 */
class OptimiseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val file = inputData.getString(KEY_FILE)?.let(::File) ?: return@withContext Result.failure()
        // Already done by an earlier run that was stopped before it could report.
        if (!file.exists()) return@withContext Result.success()
        val name = inputData.getString(KEY_NAME) ?: file.name
        val taken = inputData.getLong(KEY_TAKEN, System.currentTimeMillis())
        try {
            val uri = optimise(file, name, taken)
            notifyDone(uri)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Out of memory included: report it, and finish as done so the photos queued after
            // this one still run (a failed step would cancel the rest of the queue).
            Log.w(TAG, "Could not optimise $name", e)
            notifyFailed(name)
            Result.success()
        } finally {
            if (!isStopped) file.delete()
        }
    }

    private fun optimise(file: File, name: String, taken: Long): Uri = PhotoFileSource.open(file).use { source ->
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val optimiser = PhotoOptimiser(OptimiseOptions(threads = threads))
        val (analysis, plan) = optimiser.prepare(source)
        val (width, height) = optimiser.outputSize(analysis, plan)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val report = optimiser.run(source, analysis, plan) { x, y, w, h, pixels ->
                synchronized(bitmap) { bitmap.setPixels(pixels, 0, w, x, y, w, h) }
            }
            report.notes.forEach { Log.i(TAG, it) }
            OptimisedPhotoSaver(applicationContext).save(bitmap, file, name, taken)
        } finally {
            bitmap.recycle()
        }
    }

    private fun notifyDone(uri: Uri) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(applicationContext, uri.hashCode(), view, PendingIntent.FLAG_IMMUTABLE)
        post(
            uri.hashCode(),
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_optimise)
                .setContentTitle(applicationContext.getString(R.string.optimise_done_title))
                .setContentText(applicationContext.getString(R.string.optimise_done_text))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun notifyFailed(name: String) {
        post(
            name.hashCode(),
            NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_optimise)
                .setContentTitle(applicationContext.getString(R.string.optimise_failed_title))
                .setContentText(name)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun post(id: Int, notification: android.app.Notification) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.optimise_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        runCatching { manager.notify(id, notification) }
    }

    companion object {
        private const val TAG = "CamoronOptimise"
        private const val CHANNEL = "optimise"
        private const val QUEUE = "optimise"
        private const val KEY_FILE = "file"
        private const val KEY_NAME = "name"
        private const val KEY_TAKEN = "taken"

        /** Queues [file], a private copy of the photo the worker deletes when it is done. */
        fun enqueue(context: Context, file: File, name: String, takenMillis: Long) {
            val input = Data.Builder()
                .putString(KEY_FILE, file.absolutePath)
                .putString(KEY_NAME, name)
                .putLong(KEY_TAKEN, takenMillis)
                .build()
            val request = OneTimeWorkRequestBuilder<OptimiseWorker>()
                .setInputData(input)
                .apply {
                    // Before Android 12 an expedited job runs as a foreground service, which needs
                    // its own notification; there it runs as ordinary background work instead.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
