package ch.kalinka.babymonitor.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import ch.kalinka.babymonitor.BuildConfig
import ch.kalinka.babymonitor.net.ApiClient
import ch.kalinka.babymonitor.net.LogUpload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

/**
 * The app's log: written to logcat as before, and also uploaded to the server so it ends up in
 * Loki next to the server's own. Lines written before [start] only reach logcat.
 *
 * How much is uploaded is the server's call: every upload's reply names the lowest level it wants,
 * so turning on debugging needs a setting on the server and no new app.
 */
object AppLog {
    private const val Tag = "AppLog"
    private const val FirstUploadDelayMillis = 5_000L
    private const val UploadIntervalMillis = 30_000L
    private const val DebugUploadIntervalMillis = 10_000L
    private const val MaxLinesPerUpload = 500
    private const val MaxUploadsPerRound = 5
    private const val LastExitReported = "lastExitReported"
    private const val UploadLevel = "uploadLevel"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var outbox: LogOutbox? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    /** The lowest level uploaded. Kept across restarts, so a debug session covers the first call too. */
    @Volatile
    var uploadLevel: LogLevel = LogLevel.Info
        private set

    val isDebugging: Boolean
        get() = uploadLevel <= LogLevel.Debug

    fun v(tag: String, message: String) {
        Log.v(tag, message)
        record(LogLevel.Verbose, tag, message)
    }

    fun d(tag: String, message: String, error: Throwable? = null) {
        Log.d(tag, message, error)
        record(LogLevel.Debug, tag, message, error)
    }

    fun i(tag: String, message: String, error: Throwable? = null) {
        Log.i(tag, message, error)
        record(LogLevel.Info, tag, message, error)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, message, error)
        record(LogLevel.Warn, tag, message, error)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        record(LogLevel.Error, tag, message, error)
    }

    fun start(context: Context) {
        val prefs = context.getSharedPreferences("log", Context.MODE_PRIVATE)
        this.prefs = prefs
        uploadLevel = LogLevel.named(prefs.getString(UploadLevel, null)) ?: LogLevel.Info
        outbox = LogOutbox(File(context.filesDir, "logs"))
        catchCrashes()
        reportLastExit(context)
        uploadForever(ApiClient(context))
    }

    /**
     * Keeps a line for upload without writing it to logcat, for callers that already did. Info and
     * above are always kept and only filtered when uploaded: they are what carries the server's
     * answer back, and without them a phone set to warnings would never hear it change.
     */
    fun record(level: LogLevel, tag: String, message: String, error: Throwable? = null) {
        if (level < minOf(uploadLevel, LogLevel.Info)) {
            return
        }

        // Disk full or the like: losing a log line must never take the app down with it.
        val outbox = outbox ?: return
        runCatching {
            outbox.append(
                encodeLogEntry(LogEntry(Instant.now().toString(), level.wireName, tag, message, error?.stackTraceToString()))
            )
        }
    }

    /** Writes the crash down before the process dies; it is uploaded on the next start. */
    private fun catchCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Android's own handler, called next, already puts the crash in logcat.
            record(LogLevel.Fatal, "Crash", "Uncaught on thread ${thread.name}", error)
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                exitProcess(10)
            }
        }
    }

    /**
     * Says why the last run ended. The crash handler never sees a native crash, like WebRTC
     * aborting the process, but Android remembers it.
     */
    private fun reportLastExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }

        val activityManager = context.getSystemService(ActivityManager::class.java)
        val last = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return
        val prefs = prefs ?: return
        if (last.timestamp <= prefs.getLong(LastExitReported, 0)) {
            return
        }
        prefs.edit { putLong(LastExitReported, last.timestamp) }

        val message = "Last run ended at ${Instant.ofEpochMilli(last.timestamp)}: ${exitReason(last.reason)}" +
            last.description?.let { " ($it)" }.orEmpty()
        when (last.reason) {
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR -> e(Tag, message)
            else -> i(Tag, message)
        }
    }

    private fun exitReason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "not responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for memory"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exited"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by a signal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "stopped by the user"
        ApplicationExitInfo.REASON_USER_STOPPED -> "force-stopped by the user"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "a permission changed"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "a dependency died"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "too much resource use"
        ApplicationExitInfo.REASON_OTHER -> "killed by the system"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "the app was updated"
        else -> "reason $reason"
    }

    private fun uploadForever(api: ApiClient) {
        scope.launch {
            delay(FirstUploadDelayMillis)
            while (true) {
                upload(api)
                delay(if (isDebugging) DebugUploadIntervalMillis else UploadIntervalMillis)
            }
        }
    }

    // Several batches a round, so a debug session's flood of WebRTC lines does not fall behind.
    private suspend fun upload(api: ApiClient) {
        val outbox = outbox ?: return
        // Without a server there is nowhere to send them, and no reason to keep them.
        if (!api.isConfigured) {
            outbox.clear()
            return
        }

        repeat(MaxUploadsPerRound) {
            val batch = outbox.nextBatch().take(MaxLinesPerUpload)
            if (batch.isEmpty()) {
                return
            }

            when (val result = api.uploadLogs(logUploadBody(BuildConfig.VERSION_NAME, batch, uploadLevel))) {
                is LogUpload.Sent -> {
                    outbox.markSent(batch.size)
                    result.level?.let(::useLevel)
                }

                // A refused batch would be refused again, so it is dropped rather than retried forever.
                LogUpload.Refused -> outbox.markSent(batch.size)
                LogUpload.Failed -> return
            }
        }
    }

    private fun useLevel(level: LogLevel) {
        if (level == uploadLevel) {
            return
        }

        uploadLevel = level
        prefs?.edit { putString(UploadLevel, level.wireName) }
        i(Tag, "The server asks for ${level.wireName} and above from now on")
    }
}
