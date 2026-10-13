package com.camoron.core.common

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * The last few camera problems, kept in memory only (never written to disk or sent anywhere), so a
 * tester can read them on the Camera info screen and share them by hand.
 */
object DiagnosticLog {
    private const val MAX_ENTRIES = 30
    private val entries = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun record(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
        val detail = error?.let { " (${it.javaClass.simpleName}: ${it.message})" }.orEmpty()
        entries.addFirst("${time.format(Date())} $tag: $message$detail")
        while (entries.size > MAX_ENTRIES) entries.removeLast()
    }

    /** Newest first. */
    @Synchronized
    fun entries(): List<String> = entries.toList()
}
