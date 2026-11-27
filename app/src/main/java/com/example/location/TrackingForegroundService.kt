package com.example.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.TravelMode
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object TrackingServicePolicy {
  const val IDLE_AUTO_STOP_MS = 600_000L // 10 minutes

  fun shouldRunInBackground(
    hasLocationPermission: Boolean,
    isMockMode: Boolean,
    backgroundTrackingWhileDriving: Boolean,
    travelMode: TravelMode,
  ): Boolean {
    if (!backgroundTrackingWhileDriving) return false
    if (travelMode != TravelMode.DRIVING) return false
    if (!hasLocationPermission && !isMockMode) return false
    return true
  }

  fun shouldAutoStopIdle(
    nowMs: Long,
    idleSinceMs: Long?,
    isDriving: Boolean,
    isFollowingUser: Boolean,
    timeoutMs: Long = IDLE_AUTO_STOP_MS,
  ): Boolean {
    if (isDriving && isFollowingUser) return false
    if (idleSinceMs == null || idleSinceMs <= 0L) return false
    return (nowMs - idleSinceMs) >= timeoutMs
  }
}

class TrackingForegroundService : Service() {
  private val handler = Handler(Looper.getMainLooper())
  private var isDriving = true
  private var isFollowingUser = true
  private var idleSinceMs: Long? = null

  private val idleWatchdogRunnable =
    object : Runnable {
      override fun run() {
        val nowMs = System.currentTimeMillis()
        if (
          TrackingServicePolicy.shouldAutoStopIdle(
            nowMs = nowMs,
            idleSinceMs = idleSinceMs,
            isDriving = isDriving,
            isFollowingUser = isFollowingUser,
          )
        ) {
          stopForegroundAndSelf()
          return
        }
        handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
      }
    }

  override fun onCreate() {
    super.onCreate()
    ensureChannel()
    isRunningAtomic.set(true)
    handler.postDelayed(idleWatchdogRunnable, WATCHDOG_INTERVAL_MS)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP_TRACKING -> {
        _stopRequests.tryEmit(Unit)
        stopForegroundAndSelf()
        return START_NOT_STICKY
      }
      else -> {
        isDriving = intent?.getBooleanExtra(EXTRA_IS_DRIVING, true) ?: true
        isFollowingUser = intent?.getBooleanExtra(EXTRA_IS_FOLLOWING, true) ?: true
        if (isDriving && isFollowingUser) {
          idleSinceMs = null
        } else if (idleSinceMs == null) {
          idleSinceMs = System.currentTimeMillis()
        }
        val notif = buildNotification()
        runCatching {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
              NOTIFICATION_ID,
              notif,
              ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
          } else {
            startForeground(NOTIFICATION_ID, notif)
          }
        }.onFailure {
          stopSelf()
        }
        return START_STICKY
      }
    }
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    stopForegroundAndSelf()
    super.onTaskRemoved(rootIntent)
  }

  override fun onDestroy() {
    handler.removeCallbacks(idleWatchdogRunnable)
    isRunningAtomic.set(false)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun stopForegroundAndSelf() {
    handler.removeCallbacks(idleWatchdogRunnable)
    isRunningAtomic.set(false)
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
      }
    }
    stopSelf()
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = getSystemService(NotificationManager::class.java) ?: return
    if (nm.getNotificationChannel(CHANNEL_ID) != null) return
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        getString(R.string.tracking_notif_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = getString(R.string.tracking_notif_channel_desc)
        setShowBadge(false)
        enableVibration(false)
        setSound(null, null)
      }
    nm.createNotificationChannel(channel)
  }

  private fun buildNotification(): Notification {
    val openIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val openPending =
      PendingIntent.getActivity(
        this,
        REQ_OPEN,
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val stopIntent =
      Intent(this, TrackingForegroundService::class.java).apply {
        action = ACTION_STOP_TRACKING
      }
    val stopPending =
      PendingIntent.getService(
        this,
        REQ_STOP,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(getString(R.string.tracking_notif_title))
      .setContentText(getString(R.string.tracking_notif_body_driving))
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setContentIntent(openPending)
      .addAction(0, getString(R.string.tracking_notif_stop), stopPending)
      .build()
  }

  companion object {
    const val CHANNEL_ID = "tracking_notif_channel"
    const val NOTIFICATION_ID = 4102
    const val ACTION_START_OR_UPDATE = "com.example.action.START_OR_UPDATE_TRACKING"
    const val ACTION_STOP_TRACKING = "com.example.action.STOP_TRACKING"
    private const val EXTRA_IS_DRIVING = "extra_is_driving"
    private const val EXTRA_IS_FOLLOWING = "extra_is_following"
    private const val REQ_OPEN = 101
    private const val REQ_STOP = 102
    private const val WATCHDOG_INTERVAL_MS = 30_000L

    private val isRunningAtomic = AtomicBoolean(false)
    val isRunning: Boolean
      get() = isRunningAtomic.get()

    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val stopRequests: SharedFlow<Unit> = _stopRequests.asSharedFlow()

    fun sync(
      context: Context,
      shouldRun: Boolean,
      isDriving: Boolean,
      isFollowingUser: Boolean,
    ) {
      val appCtx = context.applicationContext
      if (shouldRun) {
        val intent =
          Intent(appCtx, TrackingForegroundService::class.java).apply {
            action = ACTION_START_OR_UPDATE
            putExtra(EXTRA_IS_DRIVING, isDriving)
            putExtra(EXTRA_IS_FOLLOWING, isFollowingUser)
          }
        runCatching { ContextCompat.startForegroundService(appCtx, intent) }
      } else if (isRunningAtomic.get()) {
        runCatching {
          appCtx.stopService(Intent(appCtx, TrackingForegroundService::class.java))
        }
      }
    }
  }
}
