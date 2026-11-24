package com.example.domain

import com.example.data.model.GeoPoint
import com.example.data.model.LocalPoint
import com.example.data.model.UnitPreference
import com.example.data.model.UnitSystem
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
  private const val EARTH_RADIUS_M = 6_371_000.0
  const val MERCATOR_MAX_LAT = 85.05112878
  private val IMPERIAL_COUNTRY_CODES = setOf("US", "GB", "MM", "LR")

  data class BoundingBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
  )

  fun normalizeLon(lon: Double): Double {
    if (lon == 180.0) return 180.0
    var wrapped = (lon + 180.0) % 360.0
    if (wrapped < 0.0) wrapped += 360.0
    return wrapped - 180.0
  }

  /**
   * Shortest signed longitude delta in [-180.0, 180.0], handling +/-180 antimeridian crossing.
   */
  fun unwrapLonDelta(dLon: Double): Double {
    var d = dLon % 360.0
    if (d > 180.0) d -= 360.0
    if (d < -180.0) d += 360.0
    return d
  }

  fun clampMercatorLat(lat: Double): Double = lat.coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT)

  /**
   * Splits a bbox crossing +/-180 longitude into 2 valid non-wrapping bboxes in [-180, 180].
   */
  fun splitBboxAtAntimeridian(
    south: Double,
    west: Double,
    north: Double,
    east: Double,
  ): List<BoundingBox> {
    val s = clampMercatorLat(minOf(south, north))
    val n = clampMercatorLat(maxOf(south, north))
    val wNorm = if (west < -180.0 || west > 180.0) normalizeLon(west) else west
    val eNorm = if (east < -180.0 || east > 180.0) normalizeLon(east) else east
    return if (wNorm > eNorm) {
      listOf(
        BoundingBox(south = s, west = wNorm, north = n, east = 180.0),
        BoundingBox(south = s, west = -180.0, north = n, east = eNorm),
      )
    } else {
      listOf(BoundingBox(south = s, west = wNorm, north = n, east = eNorm))
    }
  }

  fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(unwrapLonDelta(b.lon - a.lon))
    val h =
      sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2.0 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
  }

  fun bearingDeg(from: GeoPoint, to: GeoPoint): Double {
    val lat1 = Math.toRadians(from.lat)
    val lat2 = Math.toRadians(to.lat)
    val dLon = Math.toRadians(unwrapLonDelta(to.lon - from.lon))
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val deg = Math.toDegrees(atan2(y, x))
    return (deg + 360.0) % 360.0
  }

  /**
   * Returns signed delta in [-180, +180]: positive = clockwise/right, negative = counter-clockwise/left.
   */
  fun signedAngleDelta(fromBearingDeg: Double, toBearingDeg: Double): Double {
    var diff = (toBearingDeg - fromBearingDeg) % 360.0
    if (diff > 180.0) diff -= 360.0
    if (diff <= -180.0) diff += 360.0
    return diff
  }

  fun absAngleDiff(a: Double, b: Double): Double = abs(signedAngleDelta(a, b))

  fun offsetPoint(origin: GeoPoint, bearingDeg: Double, distanceMeters: Double): GeoPoint {
    val angularDist = distanceMeters / EARTH_RADIUS_M
    val brng = Math.toRadians(bearingDeg)
    val lat1 = Math.toRadians(origin.lat)
    val lon1 = Math.toRadians(origin.lon)
    val lat2 =
      asin(sin(lat1) * cos(angularDist) + cos(lat1) * sin(angularDist) * cos(brng))
    val lon2 =
      lon1 +
        atan2(
          sin(brng) * sin(angularDist) * cos(lat1),
          cos(angularDist) - sin(lat1) * sin(lat2),
        )
    return GeoPoint(
      lat = Math.toDegrees(lat2).coerceIn(-90.0, 90.0),
      lon = normalizeLon(Math.toDegrees(lon2)),
    )
  }

  /**
   * Projects [target] into local meters relative to [origin] oriented so [headingDeg] points +Y (ahead),
   * and +X points right.
   */
  fun projectToHeadingFrame(
    origin: GeoPoint,
    headingDeg: Double,
    target: GeoPoint,
  ): LocalPoint {
    val dist = haversineMeters(origin, target)
    if (dist < 0.01) return LocalPoint(0.0, 0.0)
    val brng = bearingDeg(origin, target)
    val relRad = Math.toRadians(signedAngleDelta(headingDeg, brng))
    return LocalPoint(
      xMeters = dist * sin(relRad),
      yMeters = dist * cos(relRad),
    )
  }

  data class SegmentProjection(
    val distanceMeters: Double,
    val snappedPoint: GeoPoint,
    val t: Double,
    val segmentBearingDeg: Double,
  )

  fun projectPointOnSegment(p: GeoPoint, a: GeoPoint, b: GeoPoint): SegmentProjection {
    val refLatRad = Math.toRadians((a.lat + b.lat) * 0.5)
    val metersPerLat = 111_132.92
    val metersPerLon = 111_412.84 * cos(refLatRad).coerceAtLeast(1e-6)

    val ax = 0.0
    val ay = 0.0
    val dLonAb = unwrapLonDelta(b.lon - a.lon)
    val dLonAp = unwrapLonDelta(p.lon - a.lon)
    val bx = dLonAb * metersPerLon
    val by = (b.lat - a.lat) * metersPerLat
    val px = dLonAp * metersPerLon
    val py = (p.lat - a.lat) * metersPerLat

    val abLenSq = bx * bx + by * by
    val t =
      if (abLenSq < 1e-6) {
        0.0
      } else {
        ((px - ax) * bx + (py - ay) * by) / abLenSq
      }.coerceIn(0.0, 1.0)

    val projX = bx * t
    val projY = by * t
    val dist = hypot(px - projX, py - projY)
    val snapped =
      GeoPoint(
        lat = a.lat + (b.lat - a.lat) * t,
        lon = normalizeLon(a.lon + dLonAb * t),
      )
    val segBearing = if (abLenSq < 1e-6) 0.0 else bearingDeg(a, b)
    return SegmentProjection(
      distanceMeters = dist,
      snappedPoint = snapped,
      t = t,
      segmentBearingDeg = segBearing,
    )
  }

  /**
   * Computes turning angle (deg) and approximate curve radius (m) at vertex [p2] between [p1] and [p3].
   */
  fun turningAngleAndRadius(p1: GeoPoint, p2: GeoPoint, p3: GeoPoint): Pair<Double, Double> {
    val d12 = haversineMeters(p1, p2)
    val d23 = haversineMeters(p2, p3)
    val d13 = haversineMeters(p1, p3)
    if (d12 < 3.0 || d23 < 3.0) return 0.0 to Double.MAX_VALUE

    val b12 = bearingDeg(p1, p2)
    val b23 = bearingDeg(p2, p3)
    val turnDeg = abs(signedAngleDelta(b12, b23))
    if (turnDeg < 2.0) return turnDeg to Double.MAX_VALUE

    // Circumcircle radius R = (a*b*c) / (4*Area)
    val s = (d12 + d23 + d13) * 0.5
    val areaSq = (s * (s - d12) * (s - d23) * (s - d13)).coerceAtLeast(0.0)
    val area = sqrt(areaSq)
    val radius =
      if (area < 1e-3) {
        Double.MAX_VALUE
      } else {
        (d12 * d23 * d13) / (4.0 * area)
      }
    return turnDeg to radius
  }

  fun polylineLengthMeters(points: List<GeoPoint>): Double {
    if (points.size < 2) return 0.0
    var sum = 0.0
    for (i in 0 until points.lastIndex) {
      sum += haversineMeters(points[i], points[i + 1])
    }
    return sum
  }

  data class PolylineSnap(
    val distanceMeters: Double,
    val snappedPoint: GeoPoint,
    val segmentIndex: Int,
    val segmentBearingDeg: Double,
  )

  fun snapToPolyline(p: GeoPoint, points: List<GeoPoint>): PolylineSnap {
    if (points.isEmpty()) {
      return PolylineSnap(Double.MAX_VALUE, p, 0, 0.0)
    }
    if (points.size == 1) {
      return PolylineSnap(haversineMeters(p, points[0]), points[0], 0, 0.0)
    }
    var bestDist = Double.MAX_VALUE
    var bestPt = points[0]
    var bestIdx = 0
    var bestBearing = 0.0
    for (i in 0 until points.lastIndex) {
      val proj = projectPointOnSegment(p, points[i], points[i + 1])
      if (proj.distanceMeters < bestDist) {
        bestDist = proj.distanceMeters
        bestPt = proj.snappedPoint
        bestIdx = i
        bestBearing = proj.segmentBearingDeg
      }
    }
    return PolylineSnap(bestDist, bestPt, bestIdx, bestBearing)
  }

  fun resolveEffectiveUnitSystem(
    pref: UnitPreference,
    locale: Locale = Locale.getDefault(),
  ): UnitSystem =
    when (pref) {
      UnitPreference.METRIC -> UnitSystem.METRIC
      UnitPreference.IMPERIAL -> UnitSystem.IMPERIAL
      UnitPreference.AUTO -> {
        val country = locale.country.uppercase(Locale.US)
        if (country in IMPERIAL_COUNTRY_CODES) UnitSystem.IMPERIAL else UnitSystem.METRIC
      }
    }

  fun detectLocaleScript(locale: Locale = Locale.getDefault()): String {
    val script = locale.script
    if (!script.isNullOrBlank()) return script
    return when (locale.language.lowercase(Locale.US)) {
      "ar", "fa", "ur", "ps" -> "Arab"
      "he", "iw" -> "Hebr"
      "ja" -> "Jpan"
      "ko" -> "Kore"
      "zh" -> "Hans"
      "ru", "uk", "bg", "sr", "be" -> "Cyrl"
      "th" -> "Thai"
      "hi", "mr", "ne" -> "Deva"
      "el" -> "Grek"
      else -> "Latn"
    }
  }

  fun isRtlScript(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    for (ch in text) {
      val dir = Character.getDirectionality(ch)
      if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
        dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
        dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING ||
        dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE
      ) {
        return true
      }
    }
    return false
  }

  fun formatDistance(meters: Double, metersFormat: String, kmFormat: String): String {
    val clamped = meters.coerceAtLeast(0.0)
    val rounded = ((clamped / 5.0).roundToInt() * 5).coerceAtLeast(if (clamped < 1.0) 0 else 5)
    return if (rounded < 1000) {
      String.format(metersFormat, rounded)
    } else {
      String.format(kmFormat, clamped / 1000.0)
    }
  }

  fun formatDistance(
    meters: Double,
    unitSystem: UnitSystem,
    metersFormat: String,
    kmFormat: String,
    feetFormat: String,
    milesFormat: String,
  ): String {
    val clamped = meters.coerceAtLeast(0.0)
    if (unitSystem == UnitSystem.METRIC) {
      return formatDistance(clamped, metersFormat, kmFormat)
    }
    val feet = clamped * 3.280839895
    return if (feet < 528.0) {
      val step = if (feet >= 50.0) 10 else 5
      val rounded = ((feet / step).roundToInt() * step).coerceAtLeast(if (feet < 1.0) 0 else step)
      String.format(feetFormat, rounded)
    } else {
      String.format(milesFormat, clamped / 1609.344)
    }
  }

  fun formatSpeedValueAndUnit(kmh: Double, unitSystem: UnitSystem): Pair<Int, String> =
    if (unitSystem == UnitSystem.IMPERIAL) {
      (kmh * 0.62137119).roundToInt() to "mph"
    } else {
      kmh.roundToInt() to "km/h"
    }
}
