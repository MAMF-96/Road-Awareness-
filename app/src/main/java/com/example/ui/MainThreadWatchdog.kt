package com.example.ui

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class WatchdogStats(
  val blockedCount: Int = 0,
  val maxBlockMs: Long = 0L,
  val topOffenders: List<String> = emptyList(),
  val repoLockMaxWaitMs: Double = 0.0,
  val repoLockMaxHoldMs: Double = 0.0,
)

/**
 * Lightweight main-thread watchdog that detects main-thread stalls > 200 ms and tracks
 * top offending stack frames plus repository lock wait/hold peaks without touching the UI thread.
 */
object MainThreadWatchdog {
  private const val POLL_INTERVAL_MS = 50L
  private const val BLOCK_THRESHOLD_MS = 200L

  private val started = AtomicBoolean(false)
  private val tickAck = AtomicLong(0L)
  private val blockedCount = AtomicInteger(0)
  private val maxBlockMs = AtomicLong(0L)
  private val offenderCounts = ConcurrentHashMap<String, AtomicInteger>()

  private val repoLockMaxWaitNs = AtomicLong(0L)
  private val repoLockMaxHoldNs = AtomicLong(0L)

  fun start() {
    if (!started.compareAndSet(false, true)) return
    val mainHandler = Handler(Looper.getMainLooper())
    val mainThread = Looper.getMainLooper().thread
    val thread =
      Thread(
        {
          var seq = 1L
          while (!Thread.currentThread().isInterrupted) {
            val currentSeq = seq++
            val postedAtNs = System.nanoTime()
            mainHandler.post { tickAck.set(currentSeq) }
            try {
              Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
              break
            }
            if (tickAck.get() < currentSeq) {
              var stalledRecorded = false
              val stallStartNs = postedAtNs
              while (tickAck.get() < currentSeq && !Thread.currentThread().isInterrupted) {
                val elapsedMs = (System.nanoTime() - stallStartNs) / 1_000_000L
                if (elapsedMs >= BLOCK_THRESHOLD_MS && !stalledRecorded) {
                  stalledRecorded = true
                  blockedCount.incrementAndGet()
                  val trace =
                    runCatching { mainThread.stackTrace }
                      .getOrDefault(emptyArray())
                  val sig = formatOffenderSignature(trace)
                  offenderCounts.computeIfAbsent(sig) { AtomicInteger(0) }.incrementAndGet()
                }
                if (elapsedMs > maxBlockMs.get() && elapsedMs >= BLOCK_THRESHOLD_MS) {
                  updateMax(maxBlockMs, elapsedMs)
                }
                try {
                  Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                  return@Thread
                }
              }
              val finalMs = (System.nanoTime() - stallStartNs) / 1_000_000L
              if (finalMs >= BLOCK_THRESHOLD_MS) {
                updateMax(maxBlockMs, finalMs)
              }
            }
          }
        },
        "MainThreadWatchdog",
      )
    thread.isDaemon = true
    thread.start()
  }

  fun recordRepoLockTiming(waitNs: Long, holdNs: Long) {
    if (waitNs > 0L) updateMax(repoLockMaxWaitNs, waitNs)
    if (holdNs > 0L) updateMax(repoLockMaxHoldNs, holdNs)
  }

  fun snapshot(): WatchdogStats {
    val top =
      offenderCounts.entries
        .sortedByDescending { it.value.get() }
        .take(5)
        .map { (sig, cnt) -> "${cnt.get()}x $sig" }
    return WatchdogStats(
      blockedCount = blockedCount.get(),
      maxBlockMs = maxBlockMs.get(),
      topOffenders = top,
      repoLockMaxWaitMs = repoLockMaxWaitNs.get() / 1_000_000.0,
      repoLockMaxHoldMs = repoLockMaxHoldNs.get() / 1_000_000.0,
    )
  }

  internal fun resetForTest() {
    blockedCount.set(0)
    maxBlockMs.set(0L)
    offenderCounts.clear()
    repoLockMaxWaitNs.set(0L)
    repoLockMaxHoldNs.set(0L)
  }

  private fun updateMax(target: AtomicLong, candidate: Long) {
    while (true) {
      val prev = target.get()
      if (candidate <= prev || target.compareAndSet(prev, candidate)) return
    }
  }

  private fun formatOffenderSignature(frames: Array<StackTraceElement>): String {
    if (frames.isEmpty()) return "unknown_stack"
    val appFrames =
      frames.filter { it.className.startsWith("com.example") }
        .take(3)
    val chosen =
      if (appFrames.isNotEmpty()) appFrames
      else frames.take(3)
    return chosen.joinToString(" <- ") { f ->
      "${f.className.substringAfterLast('.')}.${f.methodName}:${f.lineNumber}"
    }
  }
}
