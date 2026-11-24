package com.example.ui.components

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.example.BuildConfig

data class DebugTouchRing(
  val id: Long,
  val xPx: Float,
  val yPx: Float,
  val startedAtMs: Long,
)

/**
 * Observes Activity.dispatchTouchEvent without consuming or modifying touches,
 * drives the 5s Driving auto-fade wake signal, and provides debug-only touch ring
 * and Inspector HUD telemetry (last touch action, latency, handled control id).
 */
object TouchTelemetry {
  val wakeTick = mutableLongStateOf(0L)
  val debugLastTouchAction = mutableStateOf("none")
  val debugLastTouchLatencyMs = mutableLongStateOf(0L)
  val debugLastControlId = mutableStateOf("none")
  val touchRings = mutableStateListOf<DebugTouchRing>()

  @Volatile private var downTimestampMs: Long = 0L
  @Volatile private var nextRingId: Long = 1L

  fun onDispatchTouchEvent(ev: MotionEvent) {
    when (ev.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        val now = SystemClock.uptimeMillis()
        downTimestampMs = now
        wakeTick.longValue = wakeTick.longValue + 1L
        if (BuildConfig.DEBUG) {
          debugLastTouchAction.value = "DOWN"
          debugLastTouchLatencyMs.longValue = 0L
          debugLastControlId.value = "none"
          addRing(ev.x, ev.y, now)
        }
      }
      MotionEvent.ACTION_POINTER_DOWN -> {
        if (BuildConfig.DEBUG) {
          val now = SystemClock.uptimeMillis()
          debugLastTouchAction.value = "POINTER_DOWN"
          val idx = ev.actionIndex.coerceIn(0, ev.pointerCount - 1)
          addRing(ev.getX(idx), ev.getY(idx), now)
        }
      }
      MotionEvent.ACTION_MOVE -> {
        if (BuildConfig.DEBUG) {
          debugLastTouchAction.value = "MOVE"
        }
      }
      MotionEvent.ACTION_UP -> {
        if (BuildConfig.DEBUG) {
          debugLastTouchAction.value = "UP"
          if (downTimestampMs > 0L) {
            debugLastTouchLatencyMs.longValue =
              (SystemClock.uptimeMillis() - downTimestampMs).coerceAtLeast(0L)
          }
        }
      }
      MotionEvent.ACTION_CANCEL -> {
        if (BuildConfig.DEBUG) {
          debugLastTouchAction.value = "CANCEL"
        }
      }
    }
  }

  fun recordControlHandled(controlId: String) {
    wakeTick.longValue = wakeTick.longValue + 1L
    if (BuildConfig.DEBUG) {
      debugLastControlId.value = controlId
      if (downTimestampMs > 0L) {
        debugLastTouchLatencyMs.longValue =
          (SystemClock.uptimeMillis() - downTimestampMs).coerceAtLeast(0L)
      }
      if (debugLastTouchAction.value == "none") {
        debugLastTouchAction.value = "CLICK"
      }
    }
  }

  fun reset() {
    wakeTick.longValue = 0L
    debugLastTouchAction.value = "none"
    debugLastTouchLatencyMs.longValue = 0L
    debugLastControlId.value = "none"
    downTimestampMs = 0L
    touchRings.clear()
  }

  private fun addRing(x: Float, y: Float, startedAtMs: Long) {
    val ring = DebugTouchRing(id = nextRingId++, xPx = x, yPx = y, startedAtMs = startedAtMs)
    touchRings.add(ring)
    if (touchRings.size > 10) {
      touchRings.removeAt(0)
    }
  }
}

@Composable
fun rememberPressHapticInteractionSource(): MutableInteractionSource {
  val view = LocalView.current
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  LaunchedEffect(isPressed) {
    if (isPressed) {
      view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
  }
  return interactionSource
}

@Composable
fun Modifier.innerPressScale(interactionSource: MutableInteractionSource): Modifier {
  val isPressed by interactionSource.collectIsPressedAsState()
  val scale by animateFloatAsState(
    targetValue = if (isPressed) 0.96f else 1f,
    animationSpec = spring(dampingRatio = 0.85f, stiffness = 750f),
    label = "innerPressScale",
  )
  return this.graphicsLayer {
    scaleX = scale
    scaleY = scale
  }
}

@Composable
fun DebugTouchRingsOverlay(modifier: Modifier = Modifier) {
  if (!BuildConfig.DEBUG) return
  val rings = TouchTelemetry.touchRings
  val ringAgesMs = remember { androidx.compose.runtime.mutableStateMapOf<Long, Long>() }
  val frameTick = remember { mutableLongStateOf(0L) }

  LaunchedEffect(rings.size) {
    var prevFrameMs = -1L
    while (rings.isNotEmpty()) {
      withFrameMillis { frameMs ->
        val dt =
          if (prevFrameMs < 0L) 16L
          else (frameMs - prevFrameMs).coerceIn(1L, 64L)
        prevFrameMs = frameMs
        frameTick.longValue = frameTick.longValue + 1L
        val wallNow = SystemClock.uptimeMillis()
        val expiredIds = mutableListOf<Long>()
        rings.forEach { ring ->
          val nextAge = (ringAgesMs[ring.id] ?: 0L) + dt
          ringAgesMs[ring.id] = nextAge
          if (nextAge >= 420L || wallNow - ring.startedAtMs >= 420L) {
            expiredIds.add(ring.id)
          }
        }
        if (expiredIds.isNotEmpty()) {
          rings.removeAll { it.id in expiredIds }
          expiredIds.forEach { ringAgesMs.remove(it) }
        }
      }
    }
  }

  if (rings.isEmpty()) return

  Canvas(modifier = modifier.fillMaxSize()) {
    @Suppress("UNUSED_VARIABLE")
    val tick = frameTick.longValue
    val minR = 8.dp.toPx()
    val maxR = 36.dp.toPx()
    val strokePx = 2.dp.toPx()
    rings.forEach { ring ->
      val age = ringAgesMs[ring.id] ?: 0L
      val t = (age.toFloat() / 420f).coerceIn(0f, 1f)
      val radius = minR + (maxR - minR) * t
      val alpha = (1f - t).coerceIn(0f, 1f)
      drawCircle(
        color = Color(0xFF0A64D8).copy(alpha = alpha * 0.90f),
        radius = radius,
        center = Offset(ring.xPx, ring.yPx),
        style = Stroke(width = strokePx),
      )
      drawCircle(
        color = Color.White.copy(alpha = alpha * 0.70f),
        radius = (radius - strokePx).coerceAtLeast(1f),
        center = Offset(ring.xPx, ring.yPx),
        style = Stroke(width = strokePx * 0.5f),
      )
    }
  }
}
