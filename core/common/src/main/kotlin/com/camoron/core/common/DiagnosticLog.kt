package com.camoron.core.common

import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * A detailed log of what the camera did recently: problems, plus timings of the steps that make up
 * opening the camera, taking and saving a photo, and enhancing it. Kept in memory only (never
 * written to disk or sent anywhere), so a tester can read it on the Camera info screen and choose
 * to share it.
 */
object DiagnosticLog {
    private const val MAX_ENTRIES = 300
    private val entries = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** A problem: something failed or fell back. */
    @Synchronized
    fun record(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
        val detail = error?.let { " (${it.javaClass.simpleName}: ${it.message}${it.origin()})" }.orEmpty()
        add("! $tag: $message$detail")
    }

    /** A normal event worth seeing when tracking down slowness: a step and how long it took. */
    @Synchronized
    fun info(tag: String, message: String) {
        Log.i(tag, message)
        add("  $tag: $message")
    }

    /** Newest first. */
    @Synchronized
    fun entries(): List<String> = entries.toList()

    /** Problems only, newest first. */
    @Synchronized
    fun problems(): List<String> = entries.filter { it.startsWith("! ", startIndex = it.indexOf(' ') + 1) }

    /** The whole log as text to share, oldest first, headed by the phone and Android version. */
    @Synchronized
    fun report(): String = buildString {
        appendLine("Camoron log")
        appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine()
        entries.descendingIterator().forEach { appendLine(it) }
    }

    private fun add(line: String) {
        entries.addFirst("${time.format(Date())} $line")
        while (entries.size > MAX_ENTRIES) entries.removeLast()
    }

    /** Where in our own code the error came from, so a message-less exception is still traceable. */
    private fun Throwable.origin(): String {
        val frame = stackTrace.firstOrNull { it.className.startsWith("com.camoron") } ?: return ""
        return " at ${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
    }
}

/** Runs [block] and logs how long it took as "[step] took N ms". */
inline fun <T> DiagnosticLog.timed(tag: String, step: String, block: () -> T): T {
    val start = android.os.SystemClock.elapsedRealtime()
    try {
        return block()
    } finally {
        info(tag, "$step took ${android.os.SystemClock.elapsedRealtime() - start} ms")
    }
}
