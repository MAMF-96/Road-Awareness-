package com.example.domain

import com.example.data.model.AheadTimelineEntry
import com.example.data.model.BilingualName
import com.example.data.model.BranchStatus
import com.example.data.model.DividedRoadInfo
import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.OsmWay
import com.example.data.model.PassByRelation
import com.example.data.model.RelativeDirection
import com.example.data.model.RoadFeature
import com.example.data.model.RoadGraph
import com.example.data.model.TravelMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * PART C: Detects every named road (highway >= residential, or named service) within 60 m
 * laterally along the traced corridor at a crossing angle >= 30 deg, determines side,
 * closest-approach distance, lateral offset, and connection relation (Direct, Via frontage/ramp <= 400m,
 * Across the median, Grade-separated, Not connected).
 */
object PassByStreetDetector {

  data class CorridorSample(
    val p1: GeoPoint,
    val p2: GeoPoint,
    val distAtP1: Double,
    val segLen: Double,
    val bearingDeg: Double,
    val way: OsmWay,
    val nodeId1: Long?,
    val nodeId2: Long?,
  )

  private val LINK_AND_FRONTAGE =
    setOf(
      "motorway_link",
      "trunk_link",
      "primary_link",
      "secondary_link",
      "tertiary_link",
      "service",
      "living_street",
    )

  private fun isEligiblePassByHighway(way: OsmWay): Boolean {
    if (TileEndpointPlanarizer.isExcludedTransit(way.tags)) return false
    val name = BilingualName.fromOsmTags(way.tags)
    if (!name.hasAny) return false
    val hw = way.tags.highway?.lowercase(Locale.US)?.trim() ?: return false
    return when (hw) {
      "motorway",
      "trunk",
      "primary",
      "secondary",
      "tertiary",
      "unclassified",
      "residential",
      "living_street",
      "service", -> true
      else -> false
    }
  }

  fun detectPassByStreets(
    graph: RoadGraph,
    corridorSamples: List<CorridorSample>,
    corridorWayIds: Set<Long>,
    corridorNodeDists: Map<Long, Double>,
    existingFeatures: List<RoadFeature>,
    dividedRoad: DividedRoadInfo?,
    maxLookaheadMeters: Double,
    mode: TravelMode,
    deadlineNs: Long = Long.MAX_VALUE,
  ): List<AheadTimelineEntry> {
    if (corridorSamples.isEmpty() || graph.ways.isEmpty()) return emptyList()

    val existingBranchWayIds = HashSet<Long>(existingFeatures.size * 3)
    val existingBranchNamesAndDist = ArrayList<Pair<String, Double>>(existingFeatures.size * 2)
    for (feat in existingFeatures) {
      for (br in feat.branches) {
        existingBranchWayIds += br.wayId
        val n = (br.name.primaryText ?: br.name.ref)?.lowercase(Locale.US)?.trim()
        if (!n.isNullOrBlank()) {
          existingBranchNamesAndDist += n to feat.distanceMeters
        }
      }
    }

    // Build bounding box around corridor to prune far ways in O(1)
    var minLat = 90.0
    var maxLat = -90.0
    var minLon = 180.0
    var maxLon = -180.0
    for (cs in corridorSamples) {
      if (cs.distAtP1 > maxLookaheadMeters + 80.0) break
      for (pt in listOf(cs.p1, cs.p2)) {
        if (pt.lat < minLat) minLat = pt.lat
        if (pt.lat > maxLat) maxLat = pt.lat
        if (pt.lon < minLon) minLon = pt.lon
        if (pt.lon > maxLon) maxLon = pt.lon
      }
    }
    val latPad = 0.0012 // ~130 m
    val lonPad = 0.0015

    // Local projection around first corridor sample for fast segment distance/side checks
    val refLat = corridorSamples.first().p1.lat
    val refLon = corridorSamples.first().p1.lon
    val mPerDegLat = 111_320.0
    val mPerDegLon = 111_320.0 * cos(Math.toRadians(refLat)).coerceAtLeast(0.15)
    fun toX(lon: Double): Double = GeoMath.unwrapLonDelta(lon - refLon) * mPerDegLon
    fun toY(lat: Double): Double = (lat - refLat) * mPerDegLat

    data class BestMatch(
      val way: OsmWay,
      val name: BilingualName,
      val approachDistMeters: Double,
      val lateralMeters: Double,
      val side: RelativeDirection,
      val anchorOnCorridor: GeoPoint,
      val streetNearestPt: GeoPoint,
      val corridorSeg: CorridorSample,
      val crossesCorridor: Boolean,
      val relation: PassByRelation,
      val layerDelta: Int,
    )

    // Group matches by normalized street name + corridor proximity (~90m) so multi-segment named roads produce 1 clean entry
    val bestByNameBucket = LinkedHashMap<String, BestMatch>()

    var checkedCount = 0
    for ((_, way) in graph.ways) {
      if ((checkedCount++ and 127) == 0 && System.nanoTime() > deadlineNs) break
      if (way.id in corridorWayIds) continue
      if (way.points.size < 2) continue
      if (!isEligiblePassByHighway(way)) continue

      // Quick bbox reject
      var wMinLat = 90.0
      var wMaxLat = -90.0
      var wMinLon = 180.0
      var wMaxLon = -180.0
      for (p in way.points) {
        if (p.lat < wMinLat) wMinLat = p.lat
        if (p.lat > wMaxLat) wMaxLat = p.lat
        if (p.lon < wMinLon) wMinLon = p.lon
        if (p.lon > wMaxLon) wMaxLon = p.lon
      }
      if (wMaxLat < minLat - latPad || wMinLat > maxLat + latPad) continue
      if (wMaxLon - wMinLon < 180.0 && (wMaxLon < minLon - lonPad || wMinLon > maxLon + lonPad)) continue

      // Find closest approach to corridor with crossing angle >= 30 deg
      var bestApproachDist = Double.MAX_VALUE
      var bestLateral = Double.MAX_VALUE
      var bestSeg: CorridorSample? = null
      var bestCorridorPt: GeoPoint? = null
      var bestStreetPt: GeoPoint? = null
      var bestCross = false
      var bestSide = RelativeDirection.AHEAD

      for (cs in corridorSamples) {
        if (cs.distAtP1 > maxLookaheadMeters) break
        val cx1 = toX(cs.p1.lon)
        val cy1 = toY(cs.p1.lat)
        val cx2 = toX(cs.p2.lon)
        val cy2 = toY(cs.p2.lat)
        val cMinX = min(cx1, cx2) - 65.0
        val cMaxX = max(cx1, cx2) + 65.0
        val cMinY = min(cy1, cy2) - 65.0
        val cMaxY = max(cy1, cy2) + 65.0

        for (i in 0 until way.points.lastIndex) {
          val sp1 = way.points[i]
          val sp2 = way.points[i + 1]
          val sx1 = toX(sp1.lon)
          val sy1 = toY(sp1.lat)
          val sx2 = toX(sp2.lon)
          val sy2 = toY(sp2.lat)
          if (max(sx1, sx2) < cMinX || min(sx1, sx2) > cMaxX || max(sy1, sy2) < cMinY || min(sy1, sy2) > cMaxY) {
            continue
          }

          val streetBearing = GeoMath.bearingDeg(sp1, sp2)
          val angle = GeoMath.absAngleDiff(streetBearing, cs.bearingDeg)
          val effectiveCrossingAngle = min(angle, 180.0 - angle)
          if (effectiveCrossingAngle < 30.0) continue

          // Check segment-segment intersection or closest distance
          val intersects = segmentsIntersect2d(cx1, cy1, cx2, cy2, sx1, sy1, sx2, sy2)
          val lateral: Double
          val tCorridor: Double
          val tStreet: Double
          if (intersects != null) {
            lateral = 0.0
            tCorridor = intersects.first
            tStreet = intersects.second
          } else {
            val res = closestBetweenSegments(cx1, cy1, cx2, cy2, sx1, sy1, sx2, sy2)
            lateral = res.distMeters
            tCorridor = res.tA
            tStreet = res.tB
          }

          if (lateral <= 60.0) {
            val approachDist = cs.distAtP1 + tCorridor * cs.segLen
            if (approachDist in 0.0..maxLookaheadMeters) {
              val anchorPt =
                GeoPoint(
                  cs.p1.lat + tCorridor * (cs.p2.lat - cs.p1.lat),
                  GeoMath.normalizeLon(cs.p1.lon + tCorridor * GeoMath.unwrapLonDelta(cs.p2.lon - cs.p1.lon)),
                )
              val nearStreetPt =
                GeoPoint(
                  sp1.lat + tStreet * (sp2.lat - sp1.lat),
                  GeoMath.normalizeLon(sp1.lon + tStreet * GeoMath.unwrapLonDelta(sp2.lon - sp1.lon)),
                )
              // Choose side from vector pointing into the majority of the street relative to corridor tangent
              val repPt =
                if (GeoMath.haversineMeters(anchorPt, way.points.first()) > GeoMath.haversineMeters(anchorPt, way.points.last())) {
                  way.points.first()
                } else {
                  way.points.last()
                }
              val checkPt = if (intersects != null) repPt else nearStreetPt
              val relFrame = GeoMath.projectToHeadingFrame(anchorPt, cs.bearingDeg, checkPt)
              val side =
                when {
                  abs(relFrame.xMeters) < 3.0 -> RelativeDirection.AHEAD
                  relFrame.xMeters < 0.0 -> RelativeDirection.LEFT
                  else -> RelativeDirection.RIGHT
                }
              // Prefer smaller lateral distance, tie-break closer along corridor
              val better =
                bestSeg == null ||
                  (lateral < bestLateral - 2.0) ||
                  (abs(lateral - bestLateral) <= 2.0 && approachDist < bestApproachDist)
              if (better) {
                bestLateral = lateral
                bestApproachDist = approachDist
                bestSeg = cs
                bestCorridorPt = anchorPt
                bestStreetPt = nearStreetPt
                bestCross = intersects != null
                bestSide = side
              }
            }
          }
        }
      }

      val segFound = bestSeg ?: continue
      val anchorPt = bestCorridorPt ?: continue
      val nearPt = bestStreetPt ?: continue

      // Check existing feature deduplication
      val bName = BilingualName.fromOsmTags(way.tags)
      val normName = (bName.primaryText ?: bName.ref ?: "").lowercase(Locale.US).trim()
      if (way.id in existingBranchWayIds) continue
      if (normName.isNotEmpty() && existingBranchNamesAndDist.any { (en, ed) -> en == normName && abs(ed - bestApproachDist) <= 45.0 }) {
        continue
      }

      val wayLayer = TileEndpointPlanarizer.effectiveLayer(way.tags)
      val corridorLayer = TileEndpointPlanarizer.effectiveLayer(segFound.way.tags)
      val layerDelta = wayLayer - corridorLayer

      val relation =
        classifyPassByRelation(
          graph = graph,
          way = way,
          corridorNodeDists = corridorNodeDists,
          approachDist = bestApproachDist,
          lateralMeters = bestLateral,
          side = bestSide,
          crossesCorridor = bestCross,
          layerDelta = layerDelta,
          dividedRoad = dividedRoad,
          mode = mode,
        )

      val bucketKey = "${normName.ifEmpty { "w${way.id}" }}_${(bestApproachDist / 90.0).toInt()}"
      val cand =
        BestMatch(
          way = way,
          name = bName,
          approachDistMeters = bestApproachDist,
          lateralMeters = bestLateral,
          side = bestSide,
          anchorOnCorridor = anchorPt,
          streetNearestPt = nearPt,
          corridorSeg = segFound,
          crossesCorridor = bestCross,
          relation = relation,
          layerDelta = layerDelta,
        )
      val prev = bestByNameBucket[bucketKey]
      if (prev == null || cand.lateralMeters < prev.lateralMeters) {
        bestByNameBucket[bucketKey] = cand
      }
    }

    return bestByNameBucket.values
      .sortedBy { it.approachDistMeters }
      .map { m ->
        val summary = formatPassByRelationSummary(m.relation, m.layerDelta, m.lateralMeters, m.side)
        val status =
          when (m.relation) {
            PassByRelation.DIRECT, PassByRelation.VIA_FRONTAGE_OR_RAMP -> BranchStatus.OPEN
            PassByRelation.ACROSS_MEDIAN, PassByRelation.GRADE_SEPARATED, PassByRelation.NOT_CONNECTED -> BranchStatus.BLOCKED
          }
        val fType =
          when {
            m.relation == PassByRelation.GRADE_SEPARATED && m.layerDelta > 0 -> FeatureType.OVERPASS_ABOVE
            m.relation == PassByRelation.GRADE_SEPARATED && m.layerDelta < 0 -> FeatureType.UNDERPASS_BELOW
            m.relation == PassByRelation.VIA_FRONTAGE_OR_RAMP -> FeatureType.FRONTAGE_ROAD
            else -> FeatureType.T_JUNCTION
          }
        AheadTimelineEntry(
          featureIndex = null,
          type = fType,
          distanceMeters = m.approachDistMeters,
          title = m.name,
          branchSummary = summary,
          status = status,
          anchor = m.anchorOnCorridor,
          isLandmark = false,
          primaryWayId = m.way.id,
          branchWayIds = listOf(m.way.id),
          isPassByStreet = true,
          passBySide = m.side,
          passByRelation = m.relation,
          passByLateralMeters = m.lateralMeters,
        )
      }
  }

  private fun classifyPassByRelation(
    graph: RoadGraph,
    way: OsmWay,
    corridorNodeDists: Map<Long, Double>,
    approachDist: Double,
    lateralMeters: Double,
    side: RelativeDirection,
    crossesCorridor: Boolean,
    layerDelta: Int,
    dividedRoad: DividedRoadInfo?,
    mode: TravelMode,
  ): PassByRelation {
    // 1. Grade-separated crossing (different layer + crosses/under/over within 12m)
    if (layerDelta != 0 && (crossesCorridor || lateralMeters <= 12.0)) {
      return PassByRelation.GRADE_SEPARATED
    }

    // 2. Direct node connection with corridor on same layer
    val sharesCorridorNode =
      layerDelta == 0 && way.nodeIds.any { nid -> nid in corridorNodeDists }
    if (sharesCorridorNode) {
      return PassByRelation.DIRECT
    }

    // 3. Connected via frontage/service/link within <= 400 m along corridor
    if (hasFrontageOrRampConnection(graph, way, corridorNodeDists, approachDist, mode)) {
      return PassByRelation.VIA_FRONTAGE_OR_RAMP
    }

    // 4. Across the median (divided road: opposite carriageway is on that side OR partner way connects to this street)
    if (dividedRoad != null && dividedRoad.isDivided) {
      val partnerSide =
        if (dividedRoad.partnerSideOffsetMeters < 0.0) RelativeDirection.LEFT else RelativeDirection.RIGHT
      val partnerWay = graph.ways[dividedRoad.partnerWayId]
      val touchesPartner =
        partnerWay != null && way.nodeIds.any { nid -> nid in partnerWay.nodeIds }
      if (touchesPartner || side == partnerSide) {
        return PassByRelation.ACROSS_MEDIAN
      }
    }

    return PassByRelation.NOT_CONNECTED
  }

  private fun hasFrontageOrRampConnection(
    graph: RoadGraph,
    targetWay: OsmWay,
    corridorNodeDists: Map<Long, Double>,
    targetApproachDist: Double,
    mode: TravelMode,
  ): Boolean {
    val targetNodes = targetWay.nodeIds.toHashSet()
    if (targetNodes.isEmpty()) return false
    val seedNodes =
      corridorNodeDists.entries
        .filter { (_, d) -> abs(d - targetApproachDist) <= 400.0 }
        .map { it.key }
    if (seedNodes.isEmpty()) return false

    // BFS over link/service/frontage edges up to 400 m
    val visited = HashMap<Long, Double>(32)
    val queue = ArrayDeque<Pair<Long, Double>>()
    for (nid in seedNodes) {
      visited[nid] = 0.0
      queue.addLast(nid to 0.0)
    }

    var expansions = 0
    while (queue.isNotEmpty() && expansions++ < 160) {
      val (curNode, curDist) = queue.removeFirst()
      if (curDist > 400.0) continue
      val ways = graph.nodeToWays[curNode] ?: continue
      for (wid in ways) {
        val w = graph.ways[wid] ?: continue
        val hw = w.tags.highway?.lowercase(Locale.US)?.trim() ?: continue
        val isConnector =
          hw in LINK_AND_FRONTAGE ||
            w.tags.service != null ||
            (mode == TravelMode.WALKING && hw in setOf("footway", "path", "pedestrian", "residential"))
        if (!isConnector) continue
        if (w.nodeIds.any { it in targetNodes }) {
          return true
        }
        val approxLen = polylineLenMeters(w.points)
        val nextDist = curDist + approxLen
        if (nextDist > 400.0) continue
        for (nextNid in listOf(w.nodeIds.first(), w.nodeIds.last())) {
          val prev = visited[nextNid]
          if (prev == null || nextDist < prev) {
            visited[nextNid] = nextDist
            queue.addLast(nextNid to nextDist)
          }
        }
      }
    }
    return false
  }

  private fun formatPassByRelationSummary(
    relation: PassByRelation,
    layerDelta: Int,
    lateralMeters: Double,
    side: RelativeDirection,
  ): String {
    val sideWord =
      when (side) {
        RelativeDirection.LEFT -> "Left"
        RelativeDirection.RIGHT -> "Right"
        RelativeDirection.AHEAD -> "Ahead"
      }
    val offStr = "${lateralMeters.toInt()}m"
    return when (relation) {
      PassByRelation.DIRECT -> "$sideWord · $offStr · Direct"
      PassByRelation.VIA_FRONTAGE_OR_RAMP -> "$sideWord · $offStr · Via frontage/ramp"
      PassByRelation.ACROSS_MEDIAN -> "$sideWord · $offStr · Across the median"
      PassByRelation.GRADE_SEPARATED ->
        if (layerDelta > 0) "$sideWord · crosses over" else "$sideWord · crosses under"
      PassByRelation.NOT_CONNECTED -> "$sideWord · $offStr · Not connected"
    }
  }

  private fun polylineLenMeters(pts: List<GeoPoint>): Double {
    var sum = 0.0
    for (i in 0 until pts.lastIndex) {
      sum += GeoMath.haversineMeters(pts[i], pts[i + 1])
    }
    return sum
  }

  private fun segmentsIntersect2d(
    ax1: Double,
    ay1: Double,
    ax2: Double,
    ay2: Double,
    bx1: Double,
    by1: Double,
    bx2: Double,
    by2: Double,
  ): Pair<Double, Double>? {
    val dax = ax2 - ax1
    val day = ay2 - ay1
    val dbx = bx2 - bx1
    val dby = by2 - by1
    val denom = dax * dby - day * dbx
    if (abs(denom) < 1e-6) return null
    val dx = bx1 - ax1
    val dy = by1 - ay1
    val t = (dx * dby - dy * dbx) / denom
    val u = (dx * day - dy * dax) / denom
    return if (t in 0.0..1.0 && u in 0.0..1.0) t to u else null
  }

  private data class SegClosest(val distMeters: Double, val tA: Double, val tB: Double)

  private fun closestBetweenSegments(
    ax1: Double,
    ay1: Double,
    ax2: Double,
    ay2: Double,
    bx1: Double,
    by1: Double,
    bx2: Double,
    by2: Double,
  ): SegClosest {
    var bestDistSq = Double.MAX_VALUE
    var bestTa = 0.0
    var bestTb = 0.0

    fun checkPtToSeg(px: Double, py: Double, x1: Double, y1: Double, x2: Double, y2: Double): Pair<Double, Double> {
      val vx = x2 - x1
      val vy = y2 - y1
      val lSq = vx * vx + vy * vy
      val t = if (lSq < 1e-6) 0.0 else (((px - x1) * vx + (py - y1) * vy) / lSq).coerceIn(0.0, 1.0)
      val qx = x1 + t * vx
      val qy = y1 + t * vy
      val dSq = (px - qx) * (px - qx) + (py - qy) * (py - qy)
      return dSq to t
    }

    val (d1, tb1) = checkPtToSeg(ax1, ay1, bx1, by1, bx2, by2)
    if (d1 < bestDistSq) { bestDistSq = d1; bestTa = 0.0; bestTb = tb1 }

    val (d2, tb2) = checkPtToSeg(ax2, ay2, bx1, by1, bx2, by2)
    if (d2 < bestDistSq) { bestDistSq = d2; bestTa = 1.0; bestTb = tb2 }

    val (d3, ta3) = checkPtToSeg(bx1, by1, ax1, ay1, ax2, ay2)
    if (d3 < bestDistSq) { bestDistSq = d3; bestTa = ta3; bestTb = 0.0 }

    val (d4, ta4) = checkPtToSeg(bx2, by2, ax1, ay1, ax2, ay2)
    if (d4 < bestDistSq) { bestDistSq = d4; bestTa = ta4; bestTb = 1.0 }

    return SegClosest(kotlin.math.sqrt(bestDistSq), bestTa, bestTb)
  }
}
