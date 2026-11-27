package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import kotlin.math.ceil

/**
 * Hysteretic frame-timing monitor:
 * - Drops into auto low quality after 30 consecutive slow frames (> 32 ms)
 *   OR when the 5-second rolling p95 frame time exceeds 40 ms.
 * - Recovers to high quality only after 60 seconds of consistently good frames (<= 32 ms).
 */
class FrameQualityStateMachine {
  private val windowSamples = ArrayDeque<FrameSample>(MAX_WINDOW_SAMPLES)
  private var consecutiveSlowCount = 0
  private var lowQualitySinceGoodMs: Long? = null
  var isLowQuality: Boolean = false
    private set

  private data class FrameSample(
    val timestampMs: Long,
    val deltaMs: Double,
  )

  fun reset() {
    windowSamples.clear()
    consecutiveSlowCount = 0
    lowQualitySinceGoodMs = null
    isLowQuality = false
  }

  fun onFrame(nowMs: Long, frameDeltaMs: Double): Boolean {
    if (frameDeltaMs <= 0.0 || frameDeltaMs > 1_000.0) {
      // Ignore pause/resume single-frame spikes
      return isLowQuality
    }

    if (frameDeltaMs > CONSECUTIVE_SLOW_THRESHOLD_MS) {
      consecutiveSlowCount++
    } else {
      consecutiveSlowCount = 0
    }

    windowSamples.addLast(FrameSample(nowMs, frameDeltaMs))
    while (windowSamples.isNotEmpty() && (nowMs - windowSamples.first().timestampMs) > WINDOW_DURATION_MS) {
      windowSamples.removeFirst()
    }
    while (windowSamples.size > MAX_WINDOW_SAMPLES) {
      windowSamples.removeFirst()
    }

    if (!isLowQuality) {
      val hitConsecutive = consecutiveSlowCount >= CONSECUTIVE_SLOW_FRAMES
      val hitP95 =
        if (windowSamples.size >= MIN_SAMPLES_FOR_P95) {
          computeP95() > P95_SLOW_THRESHOLD_MS
        } else {
          false
        }
      if (hitConsecutive || hitP95) {
        isLowQuality = true
        lowQualitySinceGoodMs = null
      }
    } else {
      if (frameDeltaMs <= CONSECUTIVE_SLOW_THRESHOLD_MS) {
        if (lowQualitySinceGoodMs == null) {
          lowQualitySinceGoodMs = nowMs
        } else if ((nowMs - lowQualitySinceGoodMs!!) >= RECOVERY_HOLD_MS) {
          isLowQuality = false
          consecutiveSlowCount = 0
          lowQualitySinceGoodMs = null
        }
      } else {
        lowQualitySinceGoodMs = null
      }
    }
    return isLowQuality
  }

  private fun computeP95(): Double {
    val sorted = windowSamples.map { it.deltaMs }.sorted()
    val idx = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
    return sorted[idx]
  }

  companion object {
    const val CONSECUTIVE_SLOW_THRESHOLD_MS = 32.0
    const val CONSECUTIVE_SLOW_FRAMES = 30
    const val P95_SLOW_THRESHOLD_MS = 40.0
    const val WINDOW_DURATION_MS = 5_000L
    const val MIN_SAMPLES_FOR_P95 = 30
    const val RECOVERY_HOLD_MS = 60_000L
    private const val MAX_WINDOW_SAMPLES = 400
  }
}

@Composable
fun FrameQualityMonitorEffect(
  enabled: Boolean,
  onLowQualityChanged: (Boolean) -> Unit,
) {
  val latestCallback by rememberUpdatedState(onLowQualityChanged)
  val monitor = remember { FrameQualityStateMachine() }

  LaunchedEffect(enabled) {
    if (!enabled) {
      monitor.reset()
      latestCallback(false)
      return@LaunchedEffect
    }
    var prevNanos = 0L
    var lastNotified = monitor.isLowQuality
    while (true) {
      withFrameNanos { frameNanos ->
        if (prevNanos > 0L) {
          val deltaMs = (frameNanos - prevNanos) / 1_000_000.0
          val nowMs = System.currentTimeMillis()
          val low = monitor.onFrame(nowMs, deltaMs)
          if (low != lastNotified) {
            lastNotified = low
            latestCallback(low)
          }
        }
        prevNanos = frameNanos
      }
    }
  }
}
