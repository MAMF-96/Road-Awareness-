package com.example.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashDiagnostics {
  private const val PREFS_NAME = "road_awareness_crash_diag"
  private const val KEY_LAST_CRASH = "last_uncaught_crash"

  @Volatile private var installed = false

  fun install(context: Context) {
    if (installed) return
    synchronized(this) {
      if (installed) return
      installed = true
      val appCtx = context.applicationContext
      val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
      Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
          val msg = formatUncaughtException(thread.name, throwable)
          appCtx
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_CRASH, msg)
            .commit()
        }
        prevHandler?.uncaughtException(thread, throwable)
      }
    }
  }

  fun formatUncaughtException(
    threadName: String,
    throwable: Throwable,
    timestampMs: Long = System.currentTimeMillis(),
  ): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val stackHead = throwable.stackTrace.take(3).joinToString(" -> ") { "${it.className.substringAfterLast('.')}:${it.lineNumber}" }
    return buildString {
      append(fmt.format(Date(timestampMs)))
      append(" | thread=")
      append(threadName)
      append(" | ")
      append(throwable::class.java.simpleName)
      throwable.message?.takeIf { it.isNotBlank() }?.let {
        append(": ")
        append(it.take(180))
      }
      if (stackHead.isNotBlank()) {
        append(" | ")
        append(stackHead)
      }
    }
  }

  fun readLastExitSummary(context: Context): String {
    val appCtx = context.applicationContext
    val savedCrash =
      runCatching {
        appCtx
          .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .getString(KEY_LAST_CRASH, null)
      }.getOrNull()

    val systemExit =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
          val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
          val reasons = am?.getHistoricalProcessExitReasons(appCtx.packageName, 0, 3).orEmpty()
          reasons.firstOrNull()?.let { info ->
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))
            val reasonName = exitReasonName(info.reason)
            val desc = info.description?.takeIf { it.isNotBlank() }?.take(140)
            "$ts | $reasonName (${info.status})" + (desc?.let { " | $it" } ?: "")
          }
        }.getOrNull()
      } else {
        null
      }

    return listOfNotNull(savedCrash?.let { "Crash: $it" }, systemExit?.let { "Process exit: $it" })
      .joinToString("\n")
  }

  private fun exitReasonName(reason: Int): String =
    when (reason) {
      ApplicationExitInfo.REASON_CRASH -> "CRASH"
      ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
      ApplicationExitInfo.REASON_ANR -> "ANR"
      ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
      ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
      ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
      ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
      ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
      ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
      ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
      ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
      ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
      else -> "REASON_$reason"
    }
}
