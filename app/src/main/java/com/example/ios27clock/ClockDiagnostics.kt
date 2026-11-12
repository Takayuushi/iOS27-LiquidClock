package com.example.ios27clock

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Writes fatal and recoverable rendering diagnostics to:
 * Internal storage/Documents/IOS27Clock/
 *
 * MediaStore is used so Android's scoped-storage rules do not require broad
 * storage permissions or a first-run folder picker.
 */
object ClockDiagnostics {
    private const val TAG = "IOS27ClockDiagnostics"
    private const val MAX_BREADCRUMBS = 100
    private val lock = Any()
    private val breadcrumbs = ArrayDeque<String>()
    private val installed = AtomicBoolean(false)
    @Volatile private var appContext: Context? = null
    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        if (!installed.compareAndSet(false, true)) return

        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record("FATAL: uncaught exception on ${thread.name}")
            writeReport(
                context = appContext,
                title = "Fatal application crash",
                thread = thread,
                throwable = throwable
            )

            val delegate = previousHandler
            if (delegate != null) {
                delegate.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
        record("Crash diagnostics installed")
    }

    fun record(message: String) {
        val stamp = timestamp()
        synchronized(lock) {
            breadcrumbs.addLast("$stamp [${Thread.currentThread().name}] $message")
            while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeFirst()
        }
        Log.d(TAG, message)
    }

    fun report(tag: String, throwable: Throwable) {
        record("$tag: ${throwable.javaClass.name}: ${throwable.message}")
        writeReport(
            context = appContext,
            title = "Recoverable diagnostic: $tag",
            thread = Thread.currentThread(),
            throwable = throwable
        )
    }

    fun reportMessage(title: String, details: String) {
        record("$title: $details")
        writeReport(
            context = appContext,
            title = title,
            thread = Thread.currentThread(),
            details = details
        )
    }

    private fun writeReport(
        context: Context?,
        title: String,
        thread: Thread,
        throwable: Throwable? = null,
        details: String? = null
    ) {
        if (context == null) {
            Log.e(TAG, "Cannot write diagnostics: application context is unavailable.", throwable)
            return
        }

        val report = buildString {
            appendLine("iOS 27 Liquid Clock diagnostics")
            appendLine("Type: $title")
            appendLine("Time: ${timestamp()}")
            appendLine("Thread: ${thread.name} (id=${thread.id})")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                appendLine("App version: ${info.versionName} (${info.longVersionCode})")
            } catch (_: Exception) {
                appendLine("App version: unavailable")
            }
            appendLine()
            if (!details.isNullOrBlank()) {
                appendLine("Details:")
                appendLine(details)
                appendLine()
            }
            if (throwable != null) {
                appendLine("Exception:")
                val writer = StringWriter()
                throwable.printStackTrace(PrintWriter(writer))
                appendLine(writer.toString())
            }
            appendLine()
            appendLine("Recent activity (newest last):")
            synchronized(lock) {
                breadcrumbs.forEach { appendLine(it) }
            }
        }

        val fileName = "crash-${fileTimestamp()}.txt"
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOCUMENTS}/IOS27Clock/"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use {
                    it.write(report)
                    it.flush()
                } ?: throw IllegalStateException("MediaStore returned no output stream.")
                val completed = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, completed, null, null)
                Log.e(TAG, "Diagnostic report saved to Documents/IOS27Clock/$fileName", throwable)
                return
            }
            throw IllegalStateException("MediaStore could not create the diagnostics file.")
        } catch (storageException: Exception) {
            Log.e(TAG, "Public diagnostics write failed; using app-specific fallback.", storageException)
        } catch (storageError: LinkageError) {
            Log.e(TAG, "MediaStore diagnostics API failed; using app-specific fallback.", storageError)
        }

        try {
            val folder = context.getExternalFilesDir(null)?.let { File(it, "IOS27Clock") }
                ?: File(context.filesDir, "IOS27Clock")
            if (!folder.exists() && !folder.mkdirs()) {
                throw IllegalStateException("Could not create fallback diagnostics directory.")
            }
            File(folder, fileName).writeText(report)
            Log.e(TAG, "Diagnostic report saved to fallback folder: ${folder.absolutePath}/$fileName", throwable)
        } catch (fallbackException: Exception) {
            Log.e(TAG, "Unable to save diagnostic report to either location.", fallbackException)
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun fileTimestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
}
