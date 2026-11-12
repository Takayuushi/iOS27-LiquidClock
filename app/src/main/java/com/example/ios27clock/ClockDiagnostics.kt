package com.example.ios27clock

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Process
import android.provider.BaseColumns
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
 * Maintains one rolling diagnostics file:
 * Internal storage/IOS27Clock/crash-report.txt
 *
 * Recoverable incidents are appended to the same file. Older timestamped
 * crash-*.txt files from earlier app builds are folded into it on first use.
 */
object ClockDiagnostics {
    private const val TAG = "IOS27ClockDiagnostics"
    private const val REPORT_FILE_NAME = "crash-report.txt"
    private const val REPORT_FOLDER = "IOS27Clock/"
    private const val MAX_BREADCRUMBS = 100
    private const val MAX_DUPLICATE_INTERVAL_MS = 15_000L
    private const val MAX_REPORT_BYTES = 2_000_000L
    private const val LEGACY_DOCUMENTS_FOLDER = "Documents/IOS27Clock/"
    private val lock = Any()
    private val breadcrumbs = ArrayDeque<String>()
    private val lastReportAt = mutableMapOf<String, Long>()
    private val installed = AtomicBoolean(false)
    private val legacyReportsImported = AtomicBoolean(false)
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
                throwable = throwable,
                forceWrite = true
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
        // Create the single rolling report on first launch after this update,
        // then fold the old timestamped files into it immediately. The user
        // does not need to wait for another crash to consolidate 39 old files.
        writeReport(
            context = appContext,
            title = "Diagnostics initialized",
            thread = Thread.currentThread(),
            details = "Rolling crash report ready. Future incidents will be appended to this file."
        )
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
            throwable = throwable,
            incidentKey = tag
        )
    }

    fun reportMessage(title: String, details: String) {
        record("$title: $details")
        writeReport(
            context = appContext,
            title = title,
            thread = Thread.currentThread(),
            details = details,
            incidentKey = title
        )
    }

    private fun writeReport(
        context: Context?,
        title: String,
        thread: Thread,
        throwable: Throwable? = null,
        details: String? = null,
        incidentKey: String? = null,
        forceWrite: Boolean = false
    ) {
        if (context == null) {
            Log.e(TAG, "Cannot write diagnostics: application context is unavailable.", throwable)
            return
        }

        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (!forceWrite && incidentKey != null) {
                val previous = lastReportAt[incidentKey] ?: 0L
                if (now - previous < MAX_DUPLICATE_INTERVAL_MS) {
                    Log.w(TAG, "Repeated diagnostic '$incidentKey' recorded in breadcrumbs only.")
                    return
                }
                lastReportAt[incidentKey] = now
            }

            val report = buildString {
                appendLine("============================================================")
                appendLine("iOS 27 Liquid Clock incident")
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
                if (!details.isNullOrBlank()) {
                    appendLine()
                    appendLine("Details:")
                    appendLine(details)
                }
                if (throwable != null) {
                    appendLine()
                    appendLine("Exception:")
                    val writer = StringWriter()
                    throwable.printStackTrace(PrintWriter(writer))
                    appendLine(writer.toString())
                }
                appendLine()
                appendLine("Recent activity (newest last):")
                breadcrumbs.forEach { appendLine(it) }
                appendLine()
            }

            try {
                appendToPublicReport(context, report)
                if (legacyReportsImported.compareAndSet(false, true)) {
                    importLegacyReports(context)
                }
                Log.e(TAG, "Diagnostic appended to IOS27Clock/$REPORT_FILE_NAME", throwable)
                return
            } catch (storageException: Exception) {
                Log.e(TAG, "Public diagnostics write failed; using app-specific fallback.", storageException)
            } catch (storageError: LinkageError) {
                Log.e(TAG, "MediaStore diagnostics API failed; using app-specific fallback.", storageError)
            }

            try {
                val folder = context.getExternalFilesDir(null)
                    ?.let { File(it, "IOS27Clock") }
                    ?: File(context.filesDir, "IOS27Clock")
                if (!folder.exists() && !folder.mkdirs()) {
                    throw IllegalStateException("Could not create fallback diagnostics directory.")
                }
                val file = File(folder, REPORT_FILE_NAME)
                if (file.length() > MAX_REPORT_BYTES) {
                    file.writeText("Older diagnostics were trimmed at ${timestamp()}.\n")
                }
                file.appendText(report)
                Log.e(TAG, "Diagnostic appended to fallback report: ${file.absolutePath}", throwable)
            } catch (fallbackException: Exception) {
                Log.e(TAG, "Unable to save diagnostic report to either location.", fallbackException)
            }
        }
    }

    private fun appendToPublicReport(context: Context, report: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = REPORT_FOLDER
        var reportUri = findReportUri(context, collection, relativePath)

        if (reportUri == null) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, REPORT_FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            reportUri = resolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore could not create the diagnostics file.")
            resolver.openOutputStream(reportUri, "w")?.bufferedWriter()?.use {
                it.write(report)
                it.flush()
            } ?: throw IllegalStateException("MediaStore returned no output stream.")
            val completed = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(reportUri, completed, null, null)
            return
        }

        val existingSize = findReportSize(context, collection, relativePath)
        if (existingSize > MAX_REPORT_BYTES) {
            resolver.delete(reportUri, null, null)
            legacyReportsImported.set(false)
            appendToPublicReport(context, "Older diagnostics were trimmed at ${timestamp()}.\n$report")
            return
        }

        resolver.openOutputStream(reportUri, "wa")?.bufferedWriter()?.use {
            it.write(report)
            it.flush()
        } ?: throw IllegalStateException("MediaStore could not append to the diagnostics file.")
    }

    private fun findReportUri(
        context: Context,
        collection: android.net.Uri,
        relativePath: String
    ): android.net.Uri? {
        val cursor = context.contentResolver.query(
            collection,
            arrayOf(BaseColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(REPORT_FILE_NAME, relativePath),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        ) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            return ContentUris.withAppendedId(collection, it.getLong(0))
        }
    }

    private fun findReportSize(
        context: Context,
        collection: android.net.Uri,
        relativePath: String
    ): Long {
        val cursor = context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(REPORT_FILE_NAME, relativePath),
            null
        ) ?: return 0L
        cursor.use {
            return if (it.moveToFirst()) it.getLong(0) else 0L
        }
    }

    private fun importLegacyReports(context: Context) {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val targetPath = REPORT_FOLDER
        val legacyUris = linkedMapOf<String, Pair<android.net.Uri, String>>()

        fun collectMediaStore(relativePath: String, includeMaster: Boolean) {
            val selection = if (includeMaster) {
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            } else {
                "(${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} != ?) AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
            }
            val args = if (includeMaster) {
                arrayOf("crash-%.txt", relativePath)
            } else {
                arrayOf("crash-%.txt", REPORT_FILE_NAME, relativePath)
            }
            val cursor = resolver.query(
                collection,
                arrayOf(BaseColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_ADDED} ASC"
            ) ?: return

            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val name = it.getString(1) ?: "legacy-crash-report.txt"
                    val uri = ContentUris.withAppendedId(collection, id)
                    // Key on the URI so a file found by overlapping queries is imported once.
                    legacyUris[uri.toString()] = uri to name
                }
            }
        }

        // Earlier builds wrote individual crash-*.txt files at the storage root.
        // A previous revision also wrote a master file under Documents; migrate it.
        collectMediaStore(targetPath, includeMaster = false)
        collectMediaStore(LEGACY_DOCUMENTS_FOLDER, includeMaster = true)

        val target = findReportUri(context, collection, targetPath)
        if (target != null) {
            legacyUris.values.forEach { (uri, name) ->
                try {
                    val content = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: return@forEach
                    if (uri == target) return@forEach
                    resolver.openOutputStream(target, "wa")?.bufferedWriter()?.use {
                        it.write("\n\n---------------- Imported legacy report: $name ----------------\n")
                        it.write(content)
                        it.flush()
                    } ?: return@forEach
                    resolver.delete(uri, null, null)
                } catch (exception: Exception) {
                    Log.w(TAG, "Could not fold legacy diagnostics '$name' into the rolling report.", exception)
                }
            }
        }

        // Older fallbacks used app-specific external/internal IOS27Clock folders.
        // Import them as well, then remove the timestamped copies only after a
        // successful append to the public rolling report.
        val legacyFolders = listOfNotNull(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { File(it, "IOS27Clock") },
            context.getExternalFilesDir(null)?.let { File(it, "IOS27Clock") },
            File(context.filesDir, "IOS27Clock")
        ).distinctBy { it.absolutePath }

        legacyFolders.forEach { folder ->
            val files = folder.listFiles()?.filter { file ->
                file.isFile && file.name.endsWith(".txt", ignoreCase = true) &&
                    file.name.startsWith("crash-", ignoreCase = true)
            }.orEmpty()
            files.forEach { file ->
                try {
                    val content = file.readText()
                    if (target != null) {
                        resolver.openOutputStream(target, "wa")?.bufferedWriter()?.use {
                            it.write("\n\n---------------- Imported legacy report: ${file.name} ----------------\n")
                            it.write(content)
                            it.flush()
                        } ?: return@forEach
                    } else {
                        val fallback = File(folder, REPORT_FILE_NAME)
                        fallback.appendText("\n\n---------------- Imported legacy report: ${file.name} ----------------\n$content")
                    }
                    file.delete()
                } catch (exception: Exception) {
                    Log.w(TAG, "Could not fold legacy diagnostics '${file.name}' into the rolling report.", exception)
                }
            }
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
