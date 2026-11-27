package com.example.ui

import com.example.data.model.ChaseCameraDistance
import com.example.data.model.GeoPoint
import com.example.data.model.TravelMode
import com.example.domain.GeoMath
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class ChaseCameraPose(
  val topRatio: Double,
  val zoom: Double,
  val tiltDeg: Double,
  val bearingDeg: Double,
  val pinMaxDistanceMeters: Double?,
  val headingConeMeters: Double,
  val shouldEmitCameraUpdate: Boolean,
)

class ChaseCameraSmoother {
  private var smoothedHeadingDeg: Double? = null
  private var smoothedZoom: Double? = null
  private var lastEmitMs: Long = 0L
  private var lastUpdatedAtMs: Long = 0L
  private var lastChaseEnabled: Boolean? = null

  fun reset() {
    smoothedHeadingDeg = null
    smoothedZoom = null
    lastEmitMs = 0L
    lastUpdatedAtMs = 0L
    lastChaseEnabled = null
  }

  fun update(
    nowMs: Long,
    chaseCameraEnabled: Boolean,
    distancePreset: ChaseCameraDistance,
    travelMode: TravelMode,
    isLandscape: Boolean,
    gpsHeadingDeg: Double,
    speedMps: Double,
    userPoint: GeoPoint,
    isMatchedToRoad: Boolean,
    corridorWorldPoints: List<GeoPoint>,
    isOverviewMode: Boolean = false,
    forceEmit: Boolean = false,
    zoomTrim: Double = 0.0,
    tiltOverrideDeg: Double? = null,
    vehiclePosFraction: Double? = null,
    curveLeanPercent: Int = 100,
  ): ChaseCameraPose {
    val cleanHeading = normalizeAngleDeg(gpsHeadingDeg)

    if (!chaseCameraEnabled) {
      val legacyTopRatio = if (isLandscape) 0.30 else 0.34
      val legacyZoom =
        if (travelMode == TravelMode.DRIVING) {
          when {
            speedMps > 20.0 -> 15.6
            speedMps > 10.0 -> 16.3
            else -> 16.8
          }
        } else {
          17.8
        }
      val legacyTilt = if (travelMode == TravelMode.DRIVING) 32.0 else 16.0
      val legacyCone = if (travelMode == TravelMode.DRIVING) 85.0 else 38.0

      smoothedHeadingDeg = cleanHeading
      smoothedZoom = legacyZoom
      lastUpdatedAtMs = nowMs
      lastEmitMs = nowMs
      lastChaseEnabled = false

      return ChaseCameraPose(
        topRatio = legacyTopRatio,
        zoom = legacyZoom,
        tiltDeg = legacyTilt,
        bearingDeg = cleanHeading,
        pinMaxDistanceMeters = null,
        headingConeMeters = legacyCone,
        shouldEmitCameraUpdate = true,
      )
    }

    val switchedMode = lastChaseEnabled != true
    val speedKmh = (speedMps * 3.6).coerceAtLeast(0.0)
    val baseZoom =
      if (travelMode == TravelMode.DRIVING) {
        val t = ((speedKmh - 30.0) / 70.0).coerceIn(0.0, 1.0)
        18.4 - t * 0.5
      } else {
        19.0
      }
    val targetZoom = (baseZoom + distancePreset.zoomOffset + zoomTrim).coerceIn(13.0, 21.0)

    val dtSec =
      if (switchedMode || lastUpdatedAtMs <= 0L) {
        0.0
      } else {
        ((nowMs - lastUpdatedAtMs).coerceIn(0L, 2_000L)) / 1000.0
      }

    val prevZoom = smoothedZoom
    val nextZoom =
      if (switchedMode || prevZoom == null || dtSec <= 0.0) {
        targetZoom
      } else {
        val alphaZoom = 1.0 - exp(-dtSec / 1.5)
        prevZoom + alphaZoom * (targetZoom - prevZoom)
      }

    val prevHeading = smoothedHeadingDeg
    val nextHeading =
      if (switchedMode || prevHeading == null || dtSec <= 0.0) {
        cleanHeading
      } else {
        val alphaHeading = 1.0 - exp(-dtSec / 0.4)
        val delta = shortestAngleDeltaDeg(prevHeading, cleanHeading)
        normalizeAngleDeg(prevHeading + alphaHeading * delta)
      }

    smoothedZoom = nextZoom
    smoothedHeadingDeg = nextHeading
    lastUpdatedAtMs = nowMs

    val aheadBearing =
      if (isMatchedToRoad && speedKmh >= 5.0 && corridorWorldPoints.size >= 2) {
        computeAheadTangentBearing(userPoint, corridorWorldPoints, lookaheadMeters = 40.0)
      } else {
        null
      }

    val leanScale = (curveLeanPercent.coerceIn(0, 100) / 100.0) * 0.5
    val finalBearing =
      if (aheadBearing != null && leanScale > 0.0) {
        val rawDelta = shortestAngleDeltaDeg(nextHeading, aheadBearing)
        val clampedDelta = rawDelta.coerceIn(-25.0, 25.0)
        normalizeAngleDeg(nextHeading + leanScale * clampedDelta)
      } else {
        nextHeading
      }

    val coneMeters =
      if (travelMode == TravelMode.DRIVING) {
        18.0 + (speedKmh / 100.0).coerceIn(0.0, 1.0) * 67.0
      } else {
        16.0 + (speedMps / 2.0).coerceIn(0.0, 1.0) * 22.0
      }

    val presetTilt =
      if (travelMode == TravelMode.DRIVING) {
        when (distancePreset) {
          ChaseCameraDistance.VERY_CLOSE -> 68.0
          ChaseCameraDistance.CLOSE -> 66.0
          ChaseCameraDistance.NORMAL -> 62.0
          ChaseCameraDistance.FAR -> 60.0
        }
      } else {
        55.0
      }
    val finalTilt = (tiltOverrideDeg ?: presetTilt).coerceIn(40.0, 75.0)

    val presetPosFraction =
      when (distancePreset) {
        ChaseCameraDistance.VERY_CLOSE -> 0.86
        ChaseCameraDistance.CLOSE -> 0.84
        ChaseCameraDistance.NORMAL -> 0.80
        ChaseCameraDistance.FAR -> 0.78
      }
    val finalPosFraction = (vehiclePosFraction ?: presetPosFraction).coerceIn(0.65, 0.90)
    val finalTopRatio = (2.0 * finalPosFraction - 1.0).coerceIn(0.30, 0.80)

    val presetPinMaxMeters =
      when (distancePreset) {
        ChaseCameraDistance.VERY_CLOSE, ChaseCameraDistance.CLOSE -> 300.0
        ChaseCameraDistance.NORMAL, ChaseCameraDistance.FAR -> 400.0
      }

    val elapsedSinceEmit = if (lastEmitMs <= 0L) Long.MAX_VALUE else nowMs - lastEmitMs
    val shouldEmit = forceEmit || switchedMode || elapsedSinceEmit >= MIN_EMIT_INTERVAL_MS
    if (shouldEmit) {
      lastEmitMs = nowMs
    }
    lastChaseEnabled = true

    return ChaseCameraPose(
      topRatio = finalTopRatio,
      zoom = nextZoom,
      tiltDeg = finalTilt,
      bearingDeg = finalBearing,
      pinMaxDistanceMeters = if (isOverviewMode) null else presetPinMaxMeters,
      headingConeMeters = coneMeters,
      shouldEmitCameraUpdate = shouldEmit,
    )
  }

  companion object {
    const val CHASE_TOP_RATIO = 0.68 // Close default: 84% down free area height
    const val CHASE_PIN_MAX_DIST_METERS = 300.0
    const val MIN_EMIT_INTERVAL_MS = 67L // <= 15 Hz camera updates

    fun normalizeAngleDeg(deg: Double): Double {
      val m = deg % 360.0
      return if (m < 0.0) m + 360.0 else m
    }

    fun shortestAngleDeltaDeg(fromDeg: Double, toDeg: Double): Double {
      var d = (toDeg - fromDeg) % 360.0
      if (d > 180.0) d -= 360.0
      if (d < -180.0) d += 360.0
      return d
    }

    fun computeAheadTangentBearing(
      userPoint: GeoPoint,
      corridor: List<GeoPoint>,
      lookaheadMeters: Double = 40.0,
    ): Double? {
      if (corridor.size < 2) return null
      val cosLat = cos(Math.toRadians(userPoint.lat)).coerceAtLeast(0.15)
      val mPerDegLat = 111_195.0
      val mPerDegLon = 111_195.0 * cosLat

      // Find closest point along corridor
      var bestSegIdx = 0
      var bestSegT = 0.0
      var bestDistSq = Double.MAX_VALUE
      val cumMeters = DoubleArray(corridor.size)
      cumMeters[0] = 0.0
      for (i in 0 until corridor.size - 1) {
        val a = corridor[i]
        val b = corridor[i + 1]
        val ax = (a.lon - userPoint.lon) * mPerDegLon
        val ay = (a.lat - userPoint.lat) * mPerDegLat
        val bx = (b.lon - userPoint.lon) * mPerDegLon
        val by = (b.lat - userPoint.lat) * mPerDegLat
        val dx = bx - ax
        val dy = by - ay
        val segLenSq = dx * dx + dy * dy
        val segLen = sqrt(segLenSq)
        cumMeters[i + 1] = cumMeters[i] + segLen
        val t = if (segLenSq > 1e-6) (-(ax * dx + ay * dy) / segLenSq).coerceIn(0.0, 1.0) else 0.0
        val px = ax + t * dx
        val py = ay + t * dy
        val dSq = px * px + py * py
        if (dSq < bestDistSq) {
          bestDistSq = dSq
          bestSegIdx = i
          bestSegT = t
        }
      }

      val bestSegLen = cumMeters[bestSegIdx + 1] - cumMeters[bestSegIdx]
      val userAlongMeters = cumMeters[bestSegIdx] + bestSegT * bestSegLen
      val targetAlongMeters = min(cumMeters.last(), userAlongMeters + lookaheadMeters)

      for (i in 0 until corridor.size - 1) {
        val sStart = cumMeters[i]
        val sEnd = cumMeters[i + 1]
        if (sEnd >= targetAlongMeters - 1e-6 || i == corridor.size - 2) {
          val a = corridor[i]
          val b = corridor[i + 1]
          if (GeoMath.haversineMeters(a, b) >= 0.5) {
            return GeoMath.bearingDeg(a, b)
          }
          // Degenerate tiny segment: scan nearby
          val prevPt = corridor[max(0, i - 1)]
          val nextPt = corridor[min(corridor.lastIndex, i + 2)]
          if (GeoMath.haversineMeters(prevPt, nextPt) >= 0.5) {
            return GeoMath.bearingDeg(prevPt, nextPt)
          }
        }
      }
      return null
    }

    fun maxPitchedStubMeters(
      latDeg: Double,
      zoom: Double,
      pitchDeg: Double,
      minDp: Double = 28.0,
      maxDp: Double = 90.0,
      metersPerDpOverride: Double? = null,
    ): Double? {
      if (pitchDeg < 30.0) return null
      val cosLat = cos(Math.toRadians(latDeg)).coerceAtLeast(0.15)
      val cosPitch = cos(Math.toRadians(pitchDeg.coerceIn(0.0, 75.0))).coerceAtLeast(0.20)
      val metersPerDp = metersPerDpOverride ?: (78_271.517 * cosLat / 2.0.pow(zoom) / cosPitch)
      return max(minDp * metersPerDp, maxDp * metersPerDp)
    }

    fun clampPolylineByMeters(
      points: List<GeoPoint>,
      maxMeters: Double?,
    ): List<GeoPoint> {
      if (maxMeters == null || points.size < 2 || maxMeters <= 0.0) return points
      val out = ArrayList<GeoPoint>(points.size)
      out += points[0]
      var acc = 0.0
      for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val segLen = GeoMath.haversineMeters(a, b)
        if (segLen <= 0.01) continue
        if (acc + segLen >= maxMeters) {
          val remain = maxMeters - acc
          val frac = (remain / segLen).coerceIn(0.0, 1.0)
          out += GeoPoint(a.lat + (b.lat - a.lat) * frac, a.lon + (b.lon - a.lon) * frac)
          break
        }
        acc += segLen
        out += b
      }
      return if (out.size >= 2) out else points
    }
  }
}
