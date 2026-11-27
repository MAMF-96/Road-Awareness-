package com.example.location

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.example.BuildConfig
import com.example.config.AppConfig
import com.example.data.model.GeoPoint
import com.example.data.model.LocationSnapshot
import com.example.domain.GeoMath
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class LocationTracker(context: Context) {
  private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
  private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
  private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
  private val accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
  private val magnetSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

  private val _locationFlow = MutableStateFlow<LocationSnapshot?>(null)
  val locationFlow: StateFlow<LocationSnapshot?> = _locationFlow.asStateFlow()

  private val _isGpsSearching = MutableStateFlow(!BuildConfig.DEBUG)
  val isGpsSearching: StateFlow<Boolean> = _isGpsSearching.asStateFlow()

  private val _ignoredLiveJumps = MutableStateFlow<List<String>>(emptyList())
  val ignoredLiveJumps: StateFlow<List<String>> = _ignoredLiveJumps.asStateFlow()

  private val _liveSourceEvents = MutableSharedFlow<String>(extraBufferCapacity = 32)
  val liveSourceEvents: SharedFlow<String> = _liveSourceEvents.asSharedFlow()

  private var compassHeadingDeg: Double = AppConfig.DEFAULT_FALLBACK_HEADING_DEG
  private var hasCompassFix: Boolean = false
  private var smoothedSin: Double = 0.0
  private var smoothedCos: Double = 1.0
  private var lastEmittedCompassHeading: Double = Double.NaN
  private var lastCompassEmitMs: Long = 0L

  private var lastAccel = FloatArray(3)
  private var lastMagnet = FloatArray(3)
  private var hasAccel = false
  private var hasMagnet = false

  private var lastLivePoint: GeoPoint? = null
  private var lastLiveSpeed: Double = 0.0
  private var lastLiveAccuracy: Float? = null
  private var lastLiveBearing: Double? = null
  private var lastAcceptedLivePoint: GeoPoint? = null
  private var lastAcceptedLiveTimeMs: Long = 0L

  private val mockEnabledAtomic = AtomicBoolean(BuildConfig.DEBUG)
  private var mockPoint: GeoPoint? = null
  private var mockHeadingDeg: Double = AppConfig.DEFAULT_FALLBACK_HEADING_DEG
  private var mockSpeedKmh: Double = 30.0
  private var mockAccuracyMeters: Float = 3.0f
  private var mockJitterEnabled: Boolean = false

  private var isTrackingRequested = false
  @Volatile private var isLiveCallbacksRegistered = false
  private var locationThread: HandlerThread? = null
  private var locationHandler: Handler? = null

  private fun ensureLocationLooper(): Pair<Looper, Handler> {
    synchronized(this) {
      val existingThread = locationThread
      val existingHandler = locationHandler
      if (existingThread != null && existingThread.isAlive && existingHandler != null) {
        return existingThread.looper to existingHandler
      }
      val thread = HandlerThread("RoadAwarenessLocation").apply { start() }
      val handler = Handler(thread.looper)
      locationThread = thread
      locationHandler = handler
      return thread.looper to handler
    }
  }

  fun isUsingMainLooper(): Boolean {
    val looper = locationThread?.looper ?: return false
    return looper == Looper.getMainLooper()
  }

  private fun updateSmoothedCompass(rawAzimuthDeg: Double) {
    if (mockEnabledAtomic.get() || !isLiveCallbacksRegistered) return
    val normDeg = ((rawAzimuthDeg % 360.0) + 360.0) % 360.0
    val rad = Math.toRadians(normDeg)
    val alpha = 0.22
    if (!hasCompassFix) {
      smoothedSin = sin(rad)
      smoothedCos = cos(rad)
      hasCompassFix = true
    } else {
      smoothedSin = smoothedSin * (1.0 - alpha) + sin(rad) * alpha
      smoothedCos = smoothedCos * (1.0 - alpha) + cos(rad) * alpha
    }
    compassHeadingDeg = (Math.toDegrees(atan2(smoothedSin, smoothedCos)) + 360.0) % 360.0

    val now = System.currentTimeMillis()
    val movedEnough =
      lastEmittedCompassHeading.isNaN() ||
        GeoMath.absAngleDiff(lastEmittedCompassHeading, compassHeadingDeg) >= 2.0
    if (!mockEnabledAtomic.get() && lastLivePoint != null && lastLiveSpeed < AppConfig.STATIONARY_SPEED_THRESHOLD_MPS) {
      if (movedEnough && (now - lastCompassEmitMs) >= 120L) {
        lastEmittedCompassHeading = compassHeadingDeg
        lastCompassEmitMs = now
        emitLiveSnapshot()
      }
    }
  }

  private val sensorListener =
    object : SensorEventListener {
      private val rotMatrix = FloatArray(9)
      private val orientation = FloatArray(3)

      override fun onSensorChanged(event: SensorEvent) {
        if (mockEnabledAtomic.get() || !isLiveCallbacksRegistered) return
        when (event.sensor.type) {
          Sensor.TYPE_ROTATION_VECTOR -> {
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            updateSmoothedCompass(Math.toDegrees(orientation[0].toDouble()))
          }
          Sensor.TYPE_ACCELEROMETER -> {
            System.arraycopy(event.values, 0, lastAccel, 0, 3)
            hasAccel = true
            if (rotationSensor == null && hasMagnet) {
              if (SensorManager.getRotationMatrix(rotMatrix, null, lastAccel, lastMagnet)) {
                SensorManager.getOrientation(rotMatrix, orientation)
                updateSmoothedCompass(Math.toDegrees(orientation[0].toDouble()))
              }
            }
          }
          Sensor.TYPE_MAGNETIC_FIELD -> {
            System.arraycopy(event.values, 0, lastMagnet, 0, 3)
            hasMagnet = true
            if (rotationSensor == null && hasAccel) {
              if (SensorManager.getRotationMatrix(rotMatrix, null, lastAccel, lastMagnet)) {
                SensorManager.getOrientation(rotMatrix, orientation)
                updateSmoothedCompass(Math.toDegrees(orientation[0].toDouble()))
              }
            }
          }
        }
      }

      override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

  private val locationCallback =
    object : LocationCallback() {
      override fun onLocationResult(result: LocationResult) {
        if (mockEnabledAtomic.get() || !isLiveCallbacksRegistered) return
        val loc = result.lastLocation ?: return
        val newPt = GeoPoint(loc.latitude, loc.longitude)
        val nowMs = System.currentTimeMillis()
        val jumpCheck = checkLiveJump(lastAcceptedLivePoint, lastAcceptedLiveTimeMs, newPt, nowMs)
        if (jumpCheck.isRejected) {
          recordIgnoredJump(newPt, jumpCheck.distanceMeters, jumpCheck.deltaMs)
          return
        }
        lastAcceptedLivePoint = newPt
        lastAcceptedLiveTimeMs = nowMs
        lastLivePoint = newPt
        lastLiveSpeed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        lastLiveAccuracy = if (loc.hasAccuracy()) loc.accuracy else null
        lastLiveBearing =
          if (loc.hasBearing() && lastLiveSpeed >= AppConfig.STATIONARY_SPEED_THRESHOLD_MPS) {
            loc.bearing.toDouble()
          } else {
            null
          }
        _isGpsSearching.value = false
        _liveSourceEvents.tryEmit("LIVE_FUSED")
        emitLiveSnapshot()
      }
    }

  private fun recordIgnoredJump(newPt: GeoPoint, distMeters: Double, deltaMs: Long) {
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    val msg =
      String.format(
        Locale.US,
        "%s ignored +%.0fm in %dms (%.5f, %.5f)",
        timeStr,
        distMeters,
        deltaMs,
        newPt.lat,
        newPt.lon,
      )
    _ignoredLiveJumps.value = (listOf(msg) + _ignoredLiveJumps.value).take(20)
    _liveSourceEvents.tryEmit(
      String.format(Locale.US, "LIVE_JUMP_IGNORED(+%.0fm/%dms)", distMeters, deltaMs),
    )
  }

  @SuppressLint("MissingPermission")
  private fun registerLiveCallbacks() {
    if (isLiveCallbacksRegistered || mockEnabledAtomic.get()) return
    isLiveCallbacksRegistered = true
    _isGpsSearching.value = (lastLivePoint == null)

    val req =
      LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
        .setMinUpdateIntervalMillis(800L)
        .setMinUpdateDistanceMeters(2.0f)
        .build()

    val (bgLooper, bgHandler) = ensureLocationLooper()
    runCatching {
      fusedClient.lastLocation.addOnSuccessListener { loc ->
        if (mockEnabledAtomic.get() || !isLiveCallbacksRegistered) return@addOnSuccessListener
        if (loc != null && lastLivePoint == null) {
          val newPt = GeoPoint(loc.latitude, loc.longitude)
          val nowMs = System.currentTimeMillis()
          val jumpCheck = checkLiveJump(lastAcceptedLivePoint, lastAcceptedLiveTimeMs, newPt, nowMs)
          if (jumpCheck.isRejected) {
            recordIgnoredJump(newPt, jumpCheck.distanceMeters, jumpCheck.deltaMs)
            return@addOnSuccessListener
          }
          lastAcceptedLivePoint = newPt
          lastAcceptedLiveTimeMs = nowMs
          lastLivePoint = newPt
          lastLiveSpeed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
          lastLiveAccuracy = if (loc.hasAccuracy()) loc.accuracy else null
          lastLiveBearing =
            if (loc.hasBearing() && lastLiveSpeed >= AppConfig.STATIONARY_SPEED_THRESHOLD_MPS) {
              loc.bearing.toDouble()
            } else {
              null
            }
          _isGpsSearching.value = false
          _liveSourceEvents.tryEmit("LIVE_LAST_KNOWN")
          emitLiveSnapshot()
        }
      }
      fusedClient.requestLocationUpdates(req, locationCallback, bgLooper)
    }

    if (rotationSensor != null) {
      sensorManager?.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_UI, bgHandler)
    } else {
      accelSensor?.let { sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI, bgHandler) }
      magnetSensor?.let { sensorManager?.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI, bgHandler) }
    }
  }

  private fun unregisterLiveCallbacks() {
    isLiveCallbacksRegistered = false
    runCatching { fusedClient.removeLocationUpdates(locationCallback) }
    runCatching { sensorManager?.unregisterListener(sensorListener) }
  }

  fun shutdown() {
    stopTracking()
    synchronized(this) {
      runCatching { locationThread?.quitSafely() }
      locationThread = null
      locationHandler = null
    }
  }

  fun startTracking() {
    isTrackingRequested = true
    if (mockEnabledAtomic.get()) {
      unregisterLiveCallbacks()
      _isGpsSearching.value = false
      if (_locationFlow.value == null) {
        emitMockSnapshot()
      }
    } else {
      registerLiveCallbacks()
    }
  }

  fun stopTracking() {
    isTrackingRequested = false
    unregisterLiveCallbacks()
  }

  fun restoreLastLivePoint(point: GeoPoint?) {
    if (point == null || lastLivePoint != null) return
    lastLivePoint = point
    if (!mockEnabledAtomic.get()) {
      _isGpsSearching.value = false
      emitLiveSnapshot()
    }
  }

  fun restoreMockState(
    point: GeoPoint?,
    headingDeg: Double,
    speedKmh: Double,
    accuracyMeters: Float,
    jitterEnabled: Boolean,
    enabled: Boolean,
  ) {
    mockPoint = point
    mockHeadingDeg = ((headingDeg % 360.0) + 360.0) % 360.0
    mockSpeedKmh = speedKmh.coerceIn(0.0, 120.0)
    mockAccuracyMeters = accuracyMeters.coerceIn(3.0f, 50.0f)
    mockJitterEnabled = jitterEnabled
    setMockMode(enabled)
  }

  fun restoreMockPoint(point: GeoPoint) {
    mockPoint = point
    if (mockEnabledAtomic.get()) {
      emitMockSnapshot()
    }
  }

  /**
   * Switches between Debug Mock mode and Live GPS mode.
   * NEVER leaks/copies mock coordinates or any hardcoded city into Live GPS mode.
   */
  fun setMockMode(enabled: Boolean) {
    mockEnabledAtomic.set(enabled)
    if (enabled) {
      unregisterLiveCallbacks()
      _isGpsSearching.value = false
      if (mockPoint != null) {
        emitMockSnapshot()
      } else {
        _locationFlow.value = null
      }
    } else {
      lastAcceptedLivePoint = null
      lastAcceptedLiveTimeMs = 0L
      if (isTrackingRequested) {
        registerLiveCallbacks()
      }
      if (lastLivePoint != null) {
        _isGpsSearching.value = false
        emitLiveSnapshot()
      } else {
        _isGpsSearching.value = true
        _locationFlow.value = null
      }
    }
  }

  fun setMockSpeedKmh(speedKmh: Double) {
    mockSpeedKmh = speedKmh.coerceIn(0.0, 120.0)
    if (mockEnabledAtomic.get()) {
      emitMockSnapshot()
    }
  }

  fun setMockAccuracyMeters(accuracyMeters: Float) {
    mockAccuracyMeters = accuracyMeters.coerceIn(3.0f, 50.0f)
    if (mockEnabledAtomic.get()) {
      emitMockSnapshot()
    }
  }

  fun setMockJitterEnabled(enabled: Boolean) {
    mockJitterEnabled = enabled
    if (mockEnabledAtomic.get()) {
      emitMockSnapshot()
    }
  }

  fun refreshJitterSnapshot() {
    if (mockEnabledAtomic.get() && mockJitterEnabled) {
      emitMockSnapshot()
    }
  }

  fun getBaseMockPoint(): GeoPoint? = mockPoint

  fun getBaseMockHeadingDeg(): Double = mockHeadingDeg

  fun updateMockLocation(point: GeoPoint, headingDeg: Double = mockHeadingDeg) {
    mockPoint = point
    mockHeadingDeg = ((headingDeg % 360.0) + 360.0) % 360.0
    if (mockEnabledAtomic.get()) {
      emitMockSnapshot()
    }
  }

  fun updateMockHeading(headingDeg: Double, enforceMock: Boolean = false) {
    if (enforceMock && mockEnabledAtomic.compareAndSet(false, true)) {
      unregisterLiveCallbacks()
      _isGpsSearching.value = false
    }
    mockHeadingDeg = ((headingDeg % 360.0) + 360.0) % 360.0
    if (mockEnabledAtomic.get()) {
      emitMockSnapshot()
    }
  }

  private fun emitLiveSnapshot() {
    if (mockEnabledAtomic.get()) return
    val pt = lastLivePoint ?: return
    val useGpsCourse = lastLiveSpeed >= AppConfig.STATIONARY_SPEED_THRESHOLD_MPS && lastLiveBearing != null
    val heading = if (useGpsCourse) lastLiveBearing!! else compassHeadingDeg
    val reliableHeading = useGpsCourse || hasCompassFix
    _locationFlow.value =
      LocationSnapshot(
        point = pt,
        headingDeg = heading,
        speedMps = lastLiveSpeed,
        accuracyMeters = lastLiveAccuracy,
        isMock = false,
        timestampMs = System.currentTimeMillis(),
        hasReliableHeading = reliableHeading,
      )
  }

  private fun emitMockSnapshot() {
    val basePt = mockPoint ?: return
    val emittedPoint =
      if (mockJitterEnabled) {
        val radiusM = sqrt(Random.nextDouble()) * mockAccuracyMeters.toDouble()
        val bearingDeg = Random.nextDouble(0.0, 360.0)
        GeoMath.offsetPoint(basePt, bearingDeg, radiusM)
      } else {
        basePt
      }
    _locationFlow.value =
      LocationSnapshot(
        point = emittedPoint,
        headingDeg = mockHeadingDeg,
        speedMps = mockSpeedKmh / 3.6,
        accuracyMeters = mockAccuracyMeters,
        isMock = true,
        timestampMs = System.currentTimeMillis(),
        hasReliableHeading = true,
      )
  }

  data class JumpCheckResult(
    val isRejected: Boolean,
    val distanceMeters: Double,
    val deltaMs: Long,
  )

  companion object {
    internal const val MAX_LIVE_JUMP_METERS = 500.0
    internal const val LIVE_JUMP_WINDOW_MS = 2_000L

    fun checkLiveJump(
      prevPoint: GeoPoint?,
      prevTimeMs: Long,
      newPoint: GeoPoint,
      newTimeMs: Long,
    ): JumpCheckResult {
      if (prevPoint == null || prevTimeMs <= 0L) {
        return JumpCheckResult(isRejected = false, distanceMeters = 0.0, deltaMs = 0L)
      }
      val dtMs = (newTimeMs - prevTimeMs).coerceAtLeast(0L)
      val distM = GeoMath.haversineMeters(prevPoint, newPoint)
      val rejected = dtMs < LIVE_JUMP_WINDOW_MS && distM > MAX_LIVE_JUMP_METERS
      return JumpCheckResult(isRejected = rejected, distanceMeters = distM, deltaMs = dtMs)
    }
  }
}
