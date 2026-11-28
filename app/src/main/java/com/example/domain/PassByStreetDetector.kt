package com.example.domain

import com.example.data.model.AheadTimelineEntry
import com.example.data.model.BandRelationCounts
import com.example.data.model.BandRoadDiagnostic
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
 * Fix Pass 2 (Part 2): Scans the entire 80 m corridor band along the horizon for EVERY street
 * (>= residential, plus service within constraints and parallel slow/frontage stretches),
 * determines side, closest approach, connection relation (Direct / Via slow road / Across median /
 * Overpass / Not connected), merges duplicates, and records per-way band diagnostics.
 */
object PassByStreetDetector {

  const val BAND_LATERAL_METERS = 80.0
  private const val FAR_UNLABELED_SERVICE_LIMIT_M = 1_000.0

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

  data class PassByScanResult(
    val entries: List<AheadTimelineEntry>,
    val diagnostics: List<BandRoadDiagnostic>,
    val counts: BandRelationCounts,
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

  private val HIDDEN_SERVICE_SUBTYPES = setOf("driveway", "parking_aisle", "drive-through")

  private val STREET_OR_HIGHER_HIGHWAYS =
    setOf(
      "motorway",
      "trunk",
      "primary",
      "secondary",
      "tertiary",
      "unclassified",
      "residential",
      "living_street",
      "motorway_link",
      "trunk_link",
      "primary_link",
      "secondary_link",
      "tertiary_link",
      "road",
    )

  private fun highwayRank(hw: String?): Int =
    when (hw?.lowercase(Locale.US)?.trim()) {
      "motorway", "motorway_link" -> 7
      "trunk", "trunk_link" -> 6
      "primary", "primary_link" -> 5
      "secondary", "secondary_link" -> 4
      "tertiary", "tertiary_link" -> 3
      "unclassified", "residential", "living_street", "road" -> 2
      "service" -> 1
      else -> 0
    }

  private fun resolveStreetName(way: OsmWay): Pair<BilingualName, Boolean> {
    val raw = BilingualName.fromOsmTags(way.tags)
    return if (raw.hasAny) {
      raw to true
    } else {
      BilingualName(arabic = "شارع بلا اسم معروف", english = "Unnamed street", ref = null) to false
    }
  }

  private fun unnamedDisplayFallback(way: OsmWay): String =
    way.tags.name ?: way.tags.nameEn ?: way.tags.nameAr ?: way.tags.ref ?: "Unnamed (${way.tags.highway ?: "road"})"

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
    dividedPartnerWayIds: Set<Long> = emptySet(),
    showMinorJunctions: Boolean = false,
  ): List<AheadTimelineEntry> =
    detectPassByStreetsWithDiagnostics(
      graph = graph,
      corridorSamples = corridorSamples,
      corridorWayIds = corridorWayIds,
      corridorNodeDists = corridorNodeDists,
      existingFeatures = existingFeatures,
      dividedRoad = dividedRoad,
      maxLookaheadMeters = maxLookaheadMeters,
      mode = mode,
      deadlineNs = deadlineNs,
      dividedPartnerWayIds = dividedPartnerWayIds,
      showMinorJunctions = showMinorJunctions,
    ).entries

  fun detectPassByStreetsWithDiagnostics(
    graph: RoadGraph,
    corridorSamples: List<CorridorSample>,
    corridorWayIds: Set<Long>,
    corridorNodeDists: Map<Long, Double>,
    existingFeatures: List<RoadFeature>,
    dividedRoad: DividedRoadInfo?,
    maxLookaheadMeters: Double,
    mode: TravelMode,
    deadlineNs: Long = Long.MAX_VALUE,
    dividedPartnerWayIds: Set<Long> = emptySet(),
    showMinorJunctions: Boolean = false,
  ): PassByScanResult {
    if (corridorSamples.isEmpty() || graph.ways.isEmpty()) {
      return PassByScanResult(emptyList(), emptyList(), BandRelationCounts())
    }

    val existingBranchWayIds = HashSet<Long>(existingFeatures.size * 3)
    val existingBranchNamesAndDist = ArrayList<Pair<String, Double>>(existingFeatures.size * 2)
    val existingFrontageSidesAndDists = ArrayList<Pair<RelativeDirection?, Double>>()
    for (feat in existingFeatures) {
      if (feat.type == FeatureType.FRONTAGE_ROAD) {
        existingFrontageSidesAndDists += feat.frontageSide to feat.distanceMeters
      }
      for (br in feat.branches) {
        existingBranchWayIds += br.wayId
        val n = (br.name.primaryText ?: br.name.ref)?.lowercase(Locale.US)?.trim()
        if (!n.isNullOrBlank()) {
          existingBranchNamesAndDist += n to feat.distanceMeters
        }
      }
    }

    // Corridor bounding box
    var minLat = 90.0
    var maxLat = -90.0
    var minLon = 180.0
    var maxLon = -180.0
    for (cs in corridorSamples) {
      if (cs.distAtP1 > maxLookaheadMeters + 90.0) break
      for (pt in listOf(cs.p1, cs.p2)) {
        if (pt.lat < minLat) minLat = pt.lat
        if (pt.lat > maxLat) maxLat = pt.lat
        if (pt.lon < minLon) minLon = pt.lon
        if (pt.lon > maxLon) maxLon = pt.lon
      }
    }
    val latPad = 0.0014 // ~155 m
    val lonPad = 0.0018

    val refLat = corridorSamples.first().p1.lat
    val refLon = corridorSamples.first().p1.lon
    val mPerDegLat = 111_320.0
    val mPerDegLon = 111_320.0 * cos(Math.toRadians(refLat)).coerceAtLeast(0.15)
    fun toX(lon: Double): Double = GeoMath.unwrapLonDelta(lon - refLon) * mPerDegLon
    fun toY(lat: Double): Double = (lat - refLat) * mPerDegLat

    val effectivePartnerWayIds = HashSet<Long>(dividedPartnerWayIds)
    if (dividedRoad != null) {
      effectivePartnerWayIds += dividedRoad.partnerWayId
    }

    data class BandGeometryHit(
      val way: OsmWay,
      val name: BilingualName,
      val hasExplicitName: Boolean,
      val highway: String,
      val serviceTag: String?,
      val approachDistMeters: Double,
      val maxApproachDistMeters: Double,
      val minApproachDistMeters: Double,
      val lateralMeters: Double,
      val side: RelativeDirection,
      val anchorOnCorridor: GeoPoint,
      val streetNearestPt: GeoPoint,
      val corridorSeg: CorridorSample,
      val crossesCorridor: Boolean,
      val angleAtClosestDeg: Double,
      val maxAngleDeg: Double,
      val sharesCorridorNode: Boolean,
      val sharesPartnerNode: Boolean,
      val parallelSpanMeters: Double,
      val layerDelta: Int,
    )

    val allBandHits = ArrayList<BandGeometryHit>()
    val diagnostics = ArrayList<BandRoadDiagnostic>()

    val partnerNodesSet = HashSet<Long>()
    for (pid in effectivePartnerWayIds) {
      graph.ways[pid]?.nodeIds?.let { partnerNodesSet.addAll(it) }
    }

    var checkedCount = 0
    for ((_, way) in graph.ways) {
      if ((checkedCount++ and 127) == 0 && System.nanoTime() > deadlineNs) break
      if (way.id in corridorWayIds) continue
      if (way.points.size < 2) continue

      // Quick bbox reject against corridor
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

      // Measure geometry vs corridor inside 80m lateral band
      var bestApproachDist = Double.MAX_VALUE
      var bestLateral = Double.MAX_VALUE
      var bestSeg: CorridorSample? = null
      var bestCorridorPt: GeoPoint? = null
      var bestStreetPt: GeoPoint? = null
      var bestCross = false
      var bestSide = RelativeDirection.AHEAD
      var bestAngle = 0.0
      var maxAngle = 0.0
      var minParallelAlong = Double.MAX_VALUE
      var maxParallelAlong = -Double.MAX_VALUE
      var minApproachAlong = Double.MAX_VALUE
      var maxApproachAlong = -Double.MAX_VALUE

      for (cs in corridorSamples) {
        if (cs.distAtP1 > maxLookaheadMeters + 10.0) break
        val cx1 = toX(cs.p1.lon)
        val cy1 = toY(cs.p1.lat)
        val cx2 = toX(cs.p2.lon)
        val cy2 = toY(cs.p2.lat)
        val cMinX = min(cx1, cx2) - 85.0
        val cMaxX = max(cx1, cx2) + 85.0
        val cMinY = min(cy1, cy2) - 85.0
        val cMaxY = max(cy1, cy2) + 85.0

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

          if (lateral <= BAND_LATERAL_METERS) {
            val approachDist = cs.distAtP1 + tCorridor * cs.segLen
            if (approachDist in 0.0..maxLookaheadMeters) {
              if (effectiveCrossingAngle > maxAngle) maxAngle = effectiveCrossingAngle
              if (approachDist < minApproachAlong) minApproachAlong = approachDist
              if (approachDist > maxApproachAlong) maxApproachAlong = approachDist
              if (effectiveCrossingAngle < 25.0) {
                val segProjStart = cs.distAtP1
                val segProjEnd = (cs.distAtP1 + cs.segLen).coerceAtMost(maxLookaheadMeters)
                if (segProjStart < minParallelAlong) minParallelAlong = segProjStart
                if (segProjEnd > maxParallelAlong) maxParallelAlong = segProjEnd
              }
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
                bestAngle = effectiveCrossingAngle
              }
            }
          }
        }
      }

      val segFound = bestSeg ?: continue
      val anchorPt = bestCorridorPt ?: continue
      val nearPt = bestStreetPt ?: continue

      val hw = way.tags.highway?.lowercase(Locale.US)?.trim() ?: "unknown"
      val svc = way.tags.service?.lowercase(Locale.US)?.trim()
      val (bName, hasExplicitName) = resolveStreetName(way)
      val dispName = unnamedDisplayFallback(way)

      // Check exclusions and record every single <= 80m way in diagnostics
      if (way.id in effectivePartnerWayIds) {
        diagnostics +=
          BandRoadDiagnostic(
            wayId = way.id,
            name = dispName,
            highway = hw,
            angleDeg = bestAngle,
            lateralMeters = bestLateral,
            alongDistMeters = bestApproachDist,
            relation = null,
            included = false,
            reason = "Excluded: opposite carriageway (divided road)",
          )
        continue
      }

      if (TileEndpointPlanarizer.isExcludedTransit(way.tags)) {
        diagnostics +=
          BandRoadDiagnostic(
            wayId = way.id,
            name = dispName,
            highway = hw,
            angleDeg = bestAngle,
            lateralMeters = bestLateral,
            alongDistMeters = bestApproachDist,
            relation = null,
            included = false,
            reason = "Excluded: rail/transit/barrier",
          )
        continue
      }

      if (!AheadGraphAnalyzer.isRelevantForMode(way, mode)) {
        diagnostics +=
          BandRoadDiagnostic(
            wayId = way.id,
            name = dispName,
            highway = hw,
            angleDeg = bestAngle,
            lateralMeters = bestLateral,
            alongDistMeters = bestApproachDist,
            relation = null,
            included = false,
            reason = "Excluded: non-${mode.name.lowercase()} highway ($hw)",
          )
        continue
      }

      if (hw !in STREET_OR_HIGHER_HIGHWAYS && hw != "service") {
        diagnostics +=
          BandRoadDiagnostic(
            wayId = way.id,
            name = dispName,
            highway = hw,
            angleDeg = bestAngle,
            lateralMeters = bestLateral,
            alongDistMeters = bestApproachDist,
            relation = null,
            included = false,
            reason = "Excluded: below residential class ($hw)",
          )
        continue
      }

      if (!showMinorJunctions && hw == "service") {
        if (svc in HIDDEN_SERVICE_SUBTYPES) {
          diagnostics +=
            BandRoadDiagnostic(
              wayId = way.id,
              name = dispName,
              highway = hw,
              angleDeg = bestAngle,
              lateralMeters = bestLateral,
              alongDistMeters = bestApproachDist,
              relation = null,
              included = false,
              reason = "Excluded: minor service ($svc)",
            )
          continue
        }
        if (!hasExplicitName && bestApproachDist > FAR_UNLABELED_SERVICE_LIMIT_M) {
          diagnostics +=
            BandRoadDiagnostic(
              wayId = way.id,
              name = dispName,
              highway = hw,
              angleDeg = bestAngle,
              lateralMeters = bestLateral,
              alongDistMeters = bestApproachDist,
              relation = null,
              included = false,
              reason = "Excluded: unnamed service > 1 km",
            )
          continue
        }
      }

      val sharesCorridorNode = way.nodeIds.any { nid -> nid in corridorNodeDists }
      val sharesPartnerNode = way.nodeIds.any { nid -> nid in partnerNodesSet }
      val layerDelta = TileEndpointPlanarizer.effectiveLayer(way.tags) - TileEndpointPlanarizer.effectiveLayer(segFound.way.tags)
      val parallelSpan =
        if (maxParallelAlong > minParallelAlong) maxParallelAlong - minParallelAlong else 0.0

      allBandHits +=
        BandGeometryHit(
          way = way,
          name = bName,
          hasExplicitName = hasExplicitName,
          highway = hw,
          serviceTag = svc,
          approachDistMeters = bestApproachDist,
          maxApproachDistMeters = maxApproachAlong.coerceAtLeast(bestApproachDist),
          minApproachDistMeters = minApproachAlong.coerceAtMost(bestApproachDist),
          lateralMeters = bestLateral,
          side = bestSide,
          anchorOnCorridor = anchorPt,
          streetNearestPt = nearPt,
          corridorSeg = segFound,
          crossesCorridor = bestCross,
          angleAtClosestDeg = bestAngle,
          maxAngleDeg = maxAngle,
          sharesCorridorNode = sharesCorridorNode,
          sharesPartnerNode = sharesPartnerNode,
          parallelSpanMeters = parallelSpan,
          layerDelta = layerDelta,
        )
    }

    // Phase 1: Identify slow / frontage road ways running parallel alongside corridor (2..80m, angle < 25 deg)
    val slowRoadHits = ArrayList<BandGeometryHit>()
    val slowRoadWayIds = HashSet<Long>()
    val slowRoadNodes = HashSet<Long>()

    for (hit in allBandHits) {
      val segRank = highwayRank(hit.corridorSeg.way.tags.highway)
      val hitRank = highwayRank(hit.highway)
      val isFrontageClass =
        hit.highway == "service" ||
          (hitRank in 2 until segRank) ||
          (dividedRoad != null && hitRank <= segRank && hit.highway !in LINK_AND_FRONTAGE && hit.lateralMeters >= 8.0)
      val isParallel =
        hit.angleAtClosestDeg < 25.0 &&
          hit.lateralMeters in 2.0..BAND_LATERAL_METERS &&
          (hit.parallelSpanMeters >= 20.0 || polylineLenMeters(hit.way.points) >= 35.0)
      if (isFrontageClass && isParallel && !hit.crossesCorridor && hit.side != RelativeDirection.AHEAD) {
        slowRoadHits += hit
        slowRoadWayIds += hit.way.id
        slowRoadNodes.addAll(hit.way.nodeIds)
      }
    }

    // Group slow-road hits by (side, normName) into contiguous stretches so slow road appears ONCE per stretch
    data class SlowStretch(
      val representative: BandGeometryHit,
      val startDist: Double,
      val endDist: Double,
      val ways: List<OsmWay>,
    )
    val slowStretches = ArrayList<SlowStretch>()
    val slowBySideAndName =
      slowRoadHits.groupBy { hit ->
        val n = if (hit.hasExplicitName) (hit.name.primaryText ?: hit.name.ref ?: "").lowercase(Locale.US).trim() else ""
        "${hit.side.name}|$n"
      }
    for ((_, group) in slowBySideAndName) {
      val sorted = group.sortedBy { it.minApproachDistMeters }
      var curList = mutableListOf<BandGeometryHit>()
      var curEnd = -1.0
      for (h in sorted) {
        if (curList.isEmpty()) {
          curList.add(h)
          curEnd = h.maxApproachDistMeters
        } else if (h.minApproachDistMeters <= curEnd + 180.0) {
          curList.add(h)
          curEnd = max(curEnd, h.maxApproachDistMeters)
        } else {
          val rep = curList.first()
          slowStretches += SlowStretch(rep, curList.minOf { it.minApproachDistMeters }, curEnd, curList.map { it.way })
          curList = mutableListOf(h)
          curEnd = h.maxApproachDistMeters
        }
      }
      if (curList.isNotEmpty()) {
        val rep = curList.first()
        slowStretches += SlowStretch(rep, curList.minOf { it.minApproachDistMeters }, curEnd, curList.map { it.way })
      }
    }

    // Phase 2: Classify every candidate & build entries + diagnostics
    data class EmittedCandidate(
      val sortDist: Double,
      val entry: AheadTimelineEntry,
      val bucketKey: String,
      val lateralMeters: Double,
      val wayId: Long,
    )

    val emittedMap = LinkedHashMap<String, EmittedCandidate>()

    // Emit slow road stretches (once per stretch) unless an existing FRONTAGE_ROAD already covers it
    for (stretch in slowStretches) {
      val rep = stretch.representative
      val stretchNames = stretch.ways.firstNotNullOfOrNull { w ->
        val raw = BilingualName.fromOsmTags(w.tags)
        if (raw.hasAny) raw else null
      } ?: BilingualName(arabic = "طريق بطيء", english = "Slow road", ref = null)
      val stretchRelation =
        if (rep.sharesCorridorNode ||
          hasFrontageOrRampConnection(graph, rep.way, corridorNodeDists, stretch.startDist, mode, slowRoadWayIds)
        ) {
          PassByRelation.DIRECT
        } else if (dividedRoad != null &&
          ((dividedRoad.partnerSideOffsetMeters < 0.0 && rep.side == RelativeDirection.LEFT) ||
            (dividedRoad.partnerSideOffsetMeters > 0.0 && rep.side == RelativeDirection.RIGHT))
        ) {
          PassByRelation.ACROSS_MEDIAN
        } else {
          PassByRelation.NOT_CONNECTED
        }

      val alreadyAsFrontage =
        existingFrontageSidesAndDists.any { (fSide, fDist) ->
          fSide == rep.side && abs(fDist - stretch.startDist) <= 80.0
        } || stretch.ways.any { it.id in existingBranchWayIds }

      diagnostics +=
        BandRoadDiagnostic(
          wayId = rep.way.id,
          name = "${unnamedDisplayFallback(rep.way)} [slow stretch ${stretch.startDist.toInt()}-${stretch.endDist.toInt()}m]",
          highway = rep.highway,
          angleDeg = rep.angleAtClosestDeg,
          lateralMeters = rep.lateralMeters,
          alongDistMeters = stretch.startDist,
          relation = stretchRelation,
          included = !alreadyAsFrontage,
          reason = if (alreadyAsFrontage) "Merged with composite frontage feature" else "Included: slow/frontage road stretch (${rep.side.name})",
        )

      if (!alreadyAsFrontage) {
        val status =
          when (stretchRelation) {
            PassByRelation.DIRECT, PassByRelation.VIA_FRONTAGE_OR_RAMP -> BranchStatus.OPEN
            else -> BranchStatus.BLOCKED
          }
        val bucketKey = "slow_${rep.side.name}_${(stretch.startDist / 200.0).toInt()}"
        val entry =
          AheadTimelineEntry(
            featureIndex = null,
            type = FeatureType.FRONTAGE_ROAD,
            distanceMeters = stretch.startDist,
            title = stretchNames,
            branchSummary = formatPassByRelationSummary(stretchRelation, rep.layerDelta, rep.lateralMeters, rep.side),
            status = status,
            anchor = rep.anchorOnCorridor,
            isLandmark = false,
            primaryWayId = rep.way.id,
            branchWayIds = stretch.ways.map { it.id },
            isPassByStreet = true,
            passBySide = rep.side,
            passByRelation = stretchRelation,
            passByLateralMeters = rep.lateralMeters,
            isSlowRoadStretch = true,
            slowRoadEndDistMeters = stretch.endDist,
          )
        emittedMap[bucketKey] = EmittedCandidate(stretch.startDist, entry, bucketKey, rep.lateralMeters, rep.way.id)
      }
    }

    // Evaluate all remaining ways (cross streets, side streets ending on corridor or on slow road)
    for (hit in allBandHits) {
      if (hit.way.id in slowRoadWayIds) continue

      val touchesSlowRoad =
        hit.way.nodeIds.any { it in slowRoadNodes } ||
          sharesEndpointNearWay(hit.way, slowRoadHits.map { it.way }, maxSnapMeters = 6.0)

      val touchesCorridor = hit.sharesCorridorNode || hit.sharesPartnerNode
      val isCrossingOrConnected =
        hit.maxAngleDeg >= 12.0 || hit.crossesCorridor || touchesCorridor || touchesSlowRoad || hit.highway in LINK_AND_FRONTAGE

      if (!isCrossingOrConnected) {
        diagnostics +=
          BandRoadDiagnostic(
            wayId = hit.way.id,
            name = unnamedDisplayFallback(hit.way),
            highway = hit.highway,
            angleDeg = hit.angleAtClosestDeg,
            lateralMeters = hit.lateralMeters,
            alongDistMeters = hit.approachDistMeters,
            relation = null,
            included = false,
            reason = "Excluded: parallel unlinked segment (${hit.angleAtClosestDeg.toInt()} deg)",
          )
        continue
      }

      val normName =
        if (hit.hasExplicitName) (hit.name.primaryText ?: hit.name.ref ?: "").lowercase(Locale.US).trim()
        else ""

      val dupWithExistingBranch =
        hit.way.id in existingBranchWayIds ||
          (normName.isNotEmpty() && existingBranchNamesAndDist.any { (en, ed) -> en == normName && abs(ed - hit.approachDistMeters) <= 60.0 })

      val relation =
        classifyPassByRelation(
          graph = graph,
          way = hit.way,
          corridorNodeDists = corridorNodeDists,
          approachDist = hit.approachDistMeters,
          lateralMeters = hit.lateralMeters,
          side = hit.side,
          crossesCorridor = hit.crossesCorridor,
          layerDelta = hit.layerDelta,
          dividedRoad = dividedRoad,
          mode = mode,
          slowRoadWayIds = slowRoadWayIds,
          touchesSlowRoadDirect = touchesSlowRoad,
          partnerNodesSet = partnerNodesSet,
        )

      if (dupWithExistingBranch) {
        diagnostics +=
          BandRoadDiagnostic(
            wayId = hit.way.id,
            name = unnamedDisplayFallback(hit.way),
            highway = hit.highway,
            angleDeg = hit.angleAtClosestDeg,
            lateralMeters = hit.lateralMeters,
            alongDistMeters = hit.approachDistMeters,
            relation = relation,
            included = false,
            reason = "Merged with junction feature (deduplicated)",
          )
        continue
      }

      val bucketKey =
        if (normName.isNotEmpty()) {
          "${normName}_${hit.side.name}_${(hit.approachDistMeters / 90.0).toInt()}"
        } else {
          "unnamed_w${hit.way.id}_${hit.side.name}"
        }

      val prev = emittedMap[bucketKey]
      if (prev != null && prev.lateralMeters <= hit.lateralMeters) {
        diagnostics +=
          BandRoadDiagnostic(
            wayId = hit.way.id,
            name = unnamedDisplayFallback(hit.way),
            highway = hit.highway,
            angleDeg = hit.angleAtClosestDeg,
            lateralMeters = hit.lateralMeters,
            alongDistMeters = hit.approachDistMeters,
            relation = relation,
            included = false,
            reason = "Merged: duplicate segment of same street",
          )
        continue
      }

      val status =
        when (relation) {
          PassByRelation.DIRECT, PassByRelation.VIA_FRONTAGE_OR_RAMP -> BranchStatus.OPEN
          PassByRelation.ACROSS_MEDIAN, PassByRelation.GRADE_SEPARATED, PassByRelation.NOT_CONNECTED -> BranchStatus.BLOCKED
        }
      val fType =
        when {
          relation == PassByRelation.GRADE_SEPARATED && hit.layerDelta > 0 -> FeatureType.OVERPASS_ABOVE
          relation == PassByRelation.GRADE_SEPARATED && hit.layerDelta < 0 -> FeatureType.UNDERPASS_BELOW
          relation == PassByRelation.VIA_FRONTAGE_OR_RAMP -> FeatureType.T_JUNCTION
          else -> FeatureType.T_JUNCTION
        }

      val entry =
        AheadTimelineEntry(
          featureIndex = null,
          type = fType,
          distanceMeters = hit.approachDistMeters,
          title = hit.name,
          branchSummary = formatPassByRelationSummary(relation, hit.layerDelta, hit.lateralMeters, hit.side),
          status = status,
          anchor = hit.anchorOnCorridor,
          isLandmark = false,
          primaryWayId = hit.way.id,
          branchWayIds = listOf(hit.way.id),
          isPassByStreet = true,
          passBySide = hit.side,
          passByRelation = relation,
          passByLateralMeters = hit.lateralMeters,
        )
      emittedMap[bucketKey] = EmittedCandidate(hit.approachDistMeters, entry, bucketKey, hit.lateralMeters, hit.way.id)
      diagnostics +=
        BandRoadDiagnostic(
          wayId = hit.way.id,
          name = unnamedDisplayFallback(hit.way),
          highway = hit.highway,
          angleDeg = hit.angleAtClosestDeg,
          lateralMeters = hit.lateralMeters,
          alongDistMeters = hit.approachDistMeters,
          relation = relation,
          included = true,
          reason = "Included: ${relationLabel(relation)} (${hit.side.name})",
        )
    }

    val sortedEntries = emittedMap.values.sortedBy { it.sortDist }.map { it.entry }
    var directCount = 0
    var viaSlowCount = 0
    var medianCount = 0
    var overpassCount = 0
    var notConnCount = 0
    for (e in sortedEntries) {
      when (e.passByRelation) {
        PassByRelation.DIRECT -> directCount++
        PassByRelation.VIA_FRONTAGE_OR_RAMP -> viaSlowCount++
        PassByRelation.ACROSS_MEDIAN -> medianCount++
        PassByRelation.GRADE_SEPARATED -> overpassCount++
        PassByRelation.NOT_CONNECTED -> notConnCount++
        null -> {}
      }
    }

    return PassByScanResult(
      entries = sortedEntries,
      diagnostics = diagnostics.sortedBy { it.alongDistMeters },
      counts =
        BandRelationCounts(
          direct = directCount,
          viaSlowRoad = viaSlowCount,
          acrossMedian = medianCount,
          overpass = overpassCount,
          notConnected = notConnCount,
        ),
    )
  }

  private fun sharesEndpointNearWay(
    way: OsmWay,
    otherWays: List<OsmWay>,
    maxSnapMeters: Double,
  ): Boolean {
    if (way.points.isEmpty() || otherWays.isEmpty()) return false
    val first = way.points.first()
    val last = way.points.last()
    for (ow in otherWays) {
      if (ow.id == way.id || ow.points.size < 2) continue
      if (GeoMath.snapToPolyline(first, ow.points).distanceMeters <= maxSnapMeters ||
        GeoMath.snapToPolyline(last, ow.points).distanceMeters <= maxSnapMeters
      ) {
        return true
      }
    }
    return false
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
    slowRoadWayIds: Set<Long>,
    touchesSlowRoadDirect: Boolean,
    partnerNodesSet: Set<Long>,
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

    // 3. Connected directly to a slow/frontage road OR via BFS connector within corridor horizon
    if (touchesSlowRoadDirect ||
      hasFrontageOrRampConnection(graph, way, corridorNodeDists, approachDist, mode, slowRoadWayIds)
    ) {
      return PassByRelation.VIA_FRONTAGE_OR_RAMP
    }

    // 4. Across median (opposite carriageway is on that side OR partner way connects to this street)
    if (dividedRoad != null && dividedRoad.isDivided) {
      val partnerSide =
        if (dividedRoad.partnerSideOffsetMeters < 0.0) RelativeDirection.LEFT else RelativeDirection.RIGHT
      val touchesPartner = way.nodeIds.any { nid -> nid in partnerNodesSet }
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
    slowRoadWayIds: Set<Long>,
  ): Boolean {
    val targetNodes = targetWay.nodeIds.toHashSet()
    if (targetNodes.isEmpty()) return false
    val seedNodes =
      corridorNodeDists.entries
        .filter { (_, d) -> abs(d - targetApproachDist) <= 900.0 }
        .map { it.key }
    if (seedNodes.isEmpty()) return false

    val visited = HashMap<Long, Double>(48)
    val queue = ArrayDeque<Pair<Long, Double>>()
    for (nid in seedNodes) {
      visited[nid] = 0.0
      queue.addLast(nid to 0.0)
    }

    var expansions = 0
    while (queue.isNotEmpty() && expansions++ < 280) {
      val (curNode, curDist) = queue.removeFirst()
      if (curDist > 1200.0) continue
      val ways = graph.nodeToWays[curNode] ?: continue
      for (wid in ways) {
        val w = graph.ways[wid] ?: continue
        val hw = w.tags.highway?.lowercase(Locale.US)?.trim() ?: continue
        val isConnector =
          wid in slowRoadWayIds ||
            hw in LINK_AND_FRONTAGE ||
            hw in setOf("unclassified", "residential", "tertiary") ||
            w.tags.service != null ||
            (mode == TravelMode.WALKING && hw in setOf("footway", "path", "pedestrian"))
        if (!isConnector) continue
        if (w.nodeIds.any { it in targetNodes }) {
          return true
        }
        val approxLen = polylineLenMeters(w.points)
        val nextDist = curDist + approxLen
        if (nextDist > 1200.0) continue
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

  private fun relationLabel(relation: PassByRelation): String =
    when (relation) {
      PassByRelation.DIRECT -> "Direct"
      PassByRelation.VIA_FRONTAGE_OR_RAMP -> "Via slow road"
      PassByRelation.ACROSS_MEDIAN -> "Across median"
      PassByRelation.GRADE_SEPARATED -> "Overpass"
      PassByRelation.NOT_CONNECTED -> "Not connected"
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
      PassByRelation.VIA_FRONTAGE_OR_RAMP -> "$sideWord · $offStr · Via slow road"
      PassByRelation.ACROSS_MEDIAN -> "$sideWord · $offStr · Across median"
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
