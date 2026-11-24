package com.example.domain

import com.example.config.AppConfig
import com.example.data.model.BilingualName
import com.example.data.model.GeoPoint
import com.example.data.model.MatchDiagnostics
import com.example.data.model.MatchResult
import com.example.data.model.OffRoadCandidate
import com.example.data.model.OffRoadDirectionGroup
import com.example.data.model.OffRoadSide
import com.example.data.model.OsmWay
import com.example.data.model.RoadCandidate
import com.example.data.model.RoadFeature
import com.example.data.model.RoadGraph
import com.example.data.model.TravelMode
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

object MapMatcher {
  private val WALKING_ONLY_HIGHWAYS = setOf("footway", "path", "pedestrian", "steps", "cycleway")
  private val DRIVING_PREFERRED_HIGHWAYS =
    setOf(
      "motorway",
      "motorway_link",
      "trunk",
      "trunk_link",
      "primary",
      "primary_link",
      "secondary",
      "secondary_link",
      "tertiary",
      "tertiary_link",
      "unclassified",
      "residential",
      "living_street",
      "service",
      "road",
    )

  data class OffRoadResolvedAnalysis(
    val selectedCandidate: OffRoadCandidate,
    val needsDirectionChoice: Boolean,
    val directionGroups: List<OffRoadDirectionGroup>,
    val activeFeatures: List<RoadFeature>,
    val activeForwardAlongWay: Boolean?,
  )

  fun computeOnRoadMatchRadius(accuracyMeters: Float?): Double {
    val acc = (accuracyMeters ?: 0f).toDouble().coerceAtLeast(0.0)
    return max(AppConfig.MATCH_BASE_RADIUS_M, 2.0 * acc).coerceAtMost(AppConfig.MATCH_MAX_RADIUS_M)
  }

  fun match(
    graph: RoadGraph,
    position: GeoPoint,
    headingDeg: Double,
    mode: TravelMode,
    previousWayId: Long?,
    accuracyMeters: Float? = null,
    speedMps: Double = 0.0,
    spatialIndex: SpatialGraphIndex? = null,
    graphVersion: Long = 0L,
    sourceLabel: String = graph.source.name.lowercase(),
  ): MatchResult {
    val startNs = System.nanoTime()
    val baseRadius = computeOnRoadMatchRadius(accuracyMeters)
    val indexEdgeCount = spatialIndex?.edgeCount ?: graph.ways.size
    if (graph.ways.isEmpty()) {
      return MatchResult(
        primary = null,
        secondary = null,
        isUncertain = false,
        matchRadiusMeters = baseRadius,
        offRoadSearchRadiusMeters = baseRadius,
        offRoadCandidates = emptyList(),
        diagnostics =
          MatchDiagnostics(
            graphVersionRead = graphVersion,
            sourceRead = sourceLabel,
            indexEdgeCount = indexEdgeCount,
            matchDurationMs = (System.nanoTime() - startNs) / 1_000_000L,
          ),
      )
    }

    val queryRadius = AppConfig.MATCH_MAX_RADIUS_M + 35.0
    val candidateWays = spatialIndex?.queryWaysAround(position, queryRadius) ?: graph.ways.values
    val projectionResult = projectRelevantWays(candidateWays, position, headingDeg, mode)
    val wayProjections = projectionResult.projections
    val onRoadScored = mutableListOf<RoadCandidate>()
    var droppedByRadius = 0

    for (proj in wayProjections) {
      if (proj.bestDist > baseRadius) {
        droppedByRadius++
        continue
      }
      onRoadScored += scoreCandidate(proj, baseRadius, mode, previousWayId)
    }

    val nearestDist = projectionResult.nearestAnyDist
    val nearestWay = projectionResult.nearestAnyWay

    if (onRoadScored.isNotEmpty()) {
      val sorted = onRoadScored.sortedByDescending { it.confidence }
      val primary = sorted.first()

      val secondary =
        sorted
          .drop(1)
          .firstOrNull { cand ->
            cand.way.id != primary.way.id &&
              cand.bearingDiffDeg < 45.0 &&
              (primary.confidence - cand.confidence) <= AppConfig.UNCERTAIN_CONFIDENCE_DELTA &&
              cand.confidence >= 0.35
          }

      return MatchResult(
        primary = primary,
        secondary = secondary,
        isUncertain = secondary != null,
        matchRadiusMeters = baseRadius,
        offRoadSearchRadiusMeters = baseRadius,
        offRoadCandidates = emptyList(),
        diagnostics =
          MatchDiagnostics(
            graphVersionRead = graphVersion,
            sourceRead = sourceLabel,
            indexEdgeCount = indexEdgeCount,
            nearestEdgeDistanceMeters = nearestDist,
            nearestEdgeWayId = nearestWay?.id,
            nearestEdgeClass = nearestWay?.tags?.highway,
            candidatesExamined = candidateWays.size,
            droppedByRadius = droppedByRadius,
            droppedByClass = projectionResult.droppedByClass,
            droppedByAccess = 0,
            droppedByHeading = 0,
            droppedByOneway = 0,
            droppedByMissingName = 0,
            droppedByStatus = 0,
            matchDurationMs = (System.nanoTime() - startNs) / 1_000_000L,
          ),
      )
    }

    // OFF-ROAD: expand search radius in steps up to 150m (Driving) or 95m (Walking)
    val stepMax =
      if (mode == TravelMode.DRIVING) AppConfig.MATCH_MAX_RADIUS_M
      else AppConfig.OFFROAD_WALKING_MAX_RADIUS_M
    val steps =
      if (mode == TravelMode.DRIVING) listOf(45.0, 75.0, 110.0, AppConfig.MATCH_MAX_RADIUS_M)
      else listOf(35.0, 60.0, AppConfig.OFFROAD_WALKING_MAX_RADIUS_M)
        .filter { it >= baseRadius || it == stepMax }
        .ifEmpty { listOf(stepMax) }

    var searchRadiusUsed = baseRadius
    var bestWithinStep: List<WayProjection> = emptyList()

    for (stepR in steps) {
      val r = max(baseRadius, stepR).coerceAtMost(AppConfig.MATCH_MAX_RADIUS_M)
      searchRadiusUsed = r
      val found = wayProjections.filter { it.bestDist <= r }
      bestWithinStep = found
      val distinctSides = found.map { it.side }.distinct().size
      if (found.size >= 3 && distinctSides >= 3) break
    }

    val offRoadCandidates = pickDiverseOffRoadCandidates(bestWithinStep, position, headingDeg, mode)

    return MatchResult(
      primary = null,
      secondary = null,
      isUncertain = false,
      matchRadiusMeters = baseRadius,
      offRoadSearchRadiusMeters = searchRadiusUsed,
      offRoadCandidates = offRoadCandidates,
      diagnostics =
        MatchDiagnostics(
          graphVersionRead = graphVersion,
          sourceRead = sourceLabel,
          indexEdgeCount = indexEdgeCount,
          nearestEdgeDistanceMeters = nearestDist,
          nearestEdgeWayId = nearestWay?.id,
          nearestEdgeClass = nearestWay?.tags?.highway,
          candidatesExamined = candidateWays.size,
          droppedByRadius = droppedByRadius,
          droppedByClass = projectionResult.droppedByClass,
          droppedByAccess = 0,
          droppedByHeading = 0,
          droppedByOneway = 0,
          droppedByMissingName = 0,
          droppedByStatus = 0,
          matchDurationMs = (System.nanoTime() - startNs) / 1_000_000L,
        ),
    )
  }

  /**
   * Resolves road-ahead analysis when user taps an OFF-ROAD candidate (R1..R3).
   * Picks direction closest to heading, or exposes both directions when heading is unknown
   * or roughly perpendicular (60-120 deg difference) on a bidirectional road.
   */
  fun resolveOffRoadSelection(
    graph: RoadGraph,
    candidate: OffRoadCandidate,
    headingDeg: Double,
    hasReliableHeading: Boolean,
    mode: TravelMode,
    speedMps: Double,
    explicitForwardAlongWay: Boolean?,
  ): OffRoadResolvedAnalysis {
    val way = candidate.candidate.way
    val effOneway = way.tags.effectiveOneway()
    val onlyForward = mode == TravelMode.DRIVING && effOneway == "yes"
    val onlyReverse = mode == TravelMode.DRIVING && effOneway == "-1"

    val diffFwd = GeoMath.absAngleDiff(headingDeg, candidate.forwardBearingDeg)
    val diffRev = GeoMath.absAngleDiff(headingDeg, candidate.reverseBearingDeg)
    val minDiff = minOf(diffFwd, diffRev)

    val isPerpendicularOrUnknown =
      !onlyForward && !onlyReverse && (!hasReliableHeading || minDiff in 60.0..120.0)

    if (explicitForwardAlongWay != null) {
      val aligned =
        candidate.candidate.copy(
          forwardAlongWay = explicitForwardAlongWay,
          bearingDiffDeg = if (explicitForwardAlongWay) diffFwd else diffRev,
        )
      val feats = AheadGraphAnalyzer.analyzeAhead(graph, aligned, mode, speedMps)
      val groups = buildBothDirectionGroups(graph, candidate, mode, speedMps, onlyForward, onlyReverse)
      return OffRoadResolvedAnalysis(
        selectedCandidate = candidate.copy(candidate = aligned),
        needsDirectionChoice = isPerpendicularOrUnknown,
        directionGroups = groups,
        activeFeatures = feats,
        activeForwardAlongWay = explicitForwardAlongWay,
      )
    }

    if (isPerpendicularOrUnknown) {
      val groups = buildBothDirectionGroups(graph, candidate, mode, speedMps, onlyForward, onlyReverse)
      return OffRoadResolvedAnalysis(
        selectedCandidate = candidate,
        needsDirectionChoice = true,
        directionGroups = groups,
        activeFeatures = emptyList(),
        activeForwardAlongWay = null,
      )
    }

    val autoForward =
      when {
        onlyForward -> true
        onlyReverse -> false
        else -> diffFwd <= diffRev
      }
    val aligned =
      candidate.candidate.copy(
        forwardAlongWay = autoForward,
        bearingDiffDeg = if (autoForward) diffFwd else diffRev,
      )
    val feats = AheadGraphAnalyzer.analyzeAhead(graph, aligned, mode, speedMps)
    return OffRoadResolvedAnalysis(
      selectedCandidate = candidate.copy(candidate = aligned),
      needsDirectionChoice = false,
      directionGroups = emptyList(),
      activeFeatures = feats,
      activeForwardAlongWay = autoForward,
    )
  }

  private fun buildBothDirectionGroups(
    graph: RoadGraph,
    candidate: OffRoadCandidate,
    mode: TravelMode,
    speedMps: Double,
    onlyForward: Boolean,
    onlyReverse: Boolean,
  ): List<OffRoadDirectionGroup> {
    val result = mutableListOf<OffRoadDirectionGroup>()
    if (!onlyReverse) {
      val candFwd = candidate.candidate.copy(forwardAlongWay = true)
      val featsFwd = AheadGraphAnalyzer.analyzeAhead(graph, candFwd, mode, speedMps)
      result +=
        OffRoadDirectionGroup(
          forwardAlongWay = true,
          travelBearingDeg = candidate.forwardBearingDeg,
          compassCardinal = cardinalLabel(candidate.forwardBearingDeg),
          destinationHint = BilingualName.destinationFromOsmTags(candidate.candidate.way.tags),
          features = featsFwd,
        )
    }
    if (!onlyForward) {
      val candRev = candidate.candidate.copy(forwardAlongWay = false)
      val featsRev = AheadGraphAnalyzer.analyzeAhead(graph, candRev, mode, speedMps)
      result +=
        OffRoadDirectionGroup(
          forwardAlongWay = false,
          travelBearingDeg = candidate.reverseBearingDeg,
          compassCardinal = cardinalLabel(candidate.reverseBearingDeg),
          destinationHint = BilingualName.destinationFromOsmTags(candidate.candidate.way.tags),
          features = featsRev,
        )
    }
    return result
  }

  private fun cardinalLabel(bearingDeg: Double): String {
    val b = ((bearingDeg % 360.0) + 360.0) % 360.0
    return when {
      b < 22.5 || b >= 337.5 -> "N"
      b < 67.5 -> "NE"
      b < 112.5 -> "E"
      b < 157.5 -> "SE"
      b < 202.5 -> "S"
      b < 247.5 -> "SW"
      b < 292.5 -> "W"
      else -> "NW"
    }
  }

  private data class WayProjection(
    val way: OsmWay,
    val bestDist: Double,
    val bestSnap: GeoPoint,
    val bestSegIdx: Int,
    val forwardBearing: Double,
    val reverseBearing: Double,
    val bearingFromUser: Double,
    val bearingDeltaFromUserHeading: Double,
    val side: OffRoadSide,
    val forwardAlongWay: Boolean,
    val bearingDiff: Double,
    val onewayPenalty: Double,
  )

  private data class ProjectionBatchResult(
    val projections: List<WayProjection>,
    val droppedByClass: Int,
    val nearestAnyDist: Double?,
    val nearestAnyWay: OsmWay?,
  )

  private fun projectRelevantWays(
    ways: Collection<OsmWay>,
    position: GeoPoint,
    headingDeg: Double,
    mode: TravelMode,
  ): ProjectionBatchResult {
    val list = mutableListOf<WayProjection>()
    var droppedByClass = 0
    var nearestAnyDist: Double? = null
    var nearestAnyWay: OsmWay? = null

    for (way in ways) {
      if (way.points.size < 2) continue
      val hw = way.tags.highway
      if (hw == null) {
        droppedByClass++
        continue
      }
      if (mode == TravelMode.DRIVING && hw in WALKING_ONLY_HIGHWAYS) {
        droppedByClass++
        continue
      }

      var bestDist = Double.MAX_VALUE
      var bestSnap = way.points.first()
      var bestSegIdx = 0
      var bestSegBearing = 0.0

      for (i in 0 until way.points.lastIndex) {
        val proj = GeoMath.projectPointOnSegment(position, way.points[i], way.points[i + 1])
        if (proj.distanceMeters < bestDist) {
          bestDist = proj.distanceMeters
          bestSnap = proj.snappedPoint
          bestSegIdx = i
          bestSegBearing = proj.segmentBearingDeg
        }
      }

      if (nearestAnyDist == null || bestDist < nearestAnyDist) {
        nearestAnyDist = bestDist
        nearestAnyWay = way
      }

      val forwardDiff = GeoMath.absAngleDiff(headingDeg, bestSegBearing)
      val reverseBearing = (bestSegBearing + 180.0) % 360.0
      val reverseDiff = GeoMath.absAngleDiff(headingDeg, reverseBearing)

      val effOneway = way.tags.effectiveOneway()
      val onlyForward = mode == TravelMode.DRIVING && effOneway == "yes"
      val onlyReverse = mode == TravelMode.DRIVING && effOneway == "-1"

      val (forwardAlongWay, bearingDiff) =
        when {
          onlyForward -> true to forwardDiff
          onlyReverse -> false to reverseDiff
          forwardDiff <= reverseDiff -> true to forwardDiff
          else -> false to reverseDiff
        }

      val onewayPenalty =
        if ((onlyForward || onlyReverse) && bearingDiff > 105.0) 0.45 else 1.0

      val brngFromUser =
        if (bestDist < 0.5) headingDeg else GeoMath.bearingDeg(position, bestSnap)
      val relDelta = GeoMath.signedAngleDelta(headingDeg, brngFromUser)
      val side =
        when {
          abs(relDelta) <= 45.0 -> OffRoadSide.AHEAD
          abs(relDelta) >= 135.0 -> OffRoadSide.BEHIND
          relDelta > 0.0 -> OffRoadSide.RIGHT
          else -> OffRoadSide.LEFT
        }

      list +=
        WayProjection(
          way = way,
          bestDist = bestDist,
          bestSnap = bestSnap,
          bestSegIdx = bestSegIdx,
          forwardBearing = bestSegBearing,
          reverseBearing = reverseBearing,
          bearingFromUser = brngFromUser,
          bearingDeltaFromUserHeading = relDelta,
          side = side,
          forwardAlongWay = forwardAlongWay,
          bearingDiff = bearingDiff,
          onewayPenalty = onewayPenalty,
        )
    }
    return ProjectionBatchResult(
      projections = list,
      droppedByClass = droppedByClass,
      nearestAnyDist = nearestAnyDist,
      nearestAnyWay = nearestAnyWay,
    )
  }

  private fun scoreCandidate(
    proj: WayProjection,
    radiusMeters: Double,
    mode: TravelMode,
    previousWayId: Long?,
  ): RoadCandidate {
    val hw = proj.way.tags.highway ?: ""
    val distScore = exp(-proj.bestDist / (radiusMeters * 0.45))
    val angleScore = exp(-proj.bearingDiff / 52.0)
    val modePriority =
      when (mode) {
        TravelMode.DRIVING -> if (hw in DRIVING_PREFERRED_HIGHWAYS) 1.0 else 0.7
        TravelMode.WALKING -> if (hw in WALKING_ONLY_HIGHWAYS || hw == "living_street") 1.05 else 0.9
      }
    val continuityBonus = if (proj.way.id == previousWayId) 1.08 else 1.0
    val confidence =
      (distScore * 0.58 + angleScore * 0.42) * proj.onewayPenalty * modePriority * continuityBonus

    return RoadCandidate(
      way = proj.way,
      name = BilingualName.fromOsmTags(proj.way.tags),
      confidence = confidence.coerceIn(0.0, 1.0),
      lateralOffsetMeters = proj.bestDist,
      bearingDiffDeg = proj.bearingDiff,
      snappedPoint = proj.bestSnap,
      segmentIndex = proj.bestSegIdx,
      forwardAlongWay = proj.forwardAlongWay,
    )
  }

  /**
   * Picks up to 3 candidate roads prioritizing DIFFERENT ways/sides around the user (Ahead, Left, Right, Behind).
   */
  private fun pickDiverseOffRoadCandidates(
    projections: List<WayProjection>,
    position: GeoPoint,
    headingDeg: Double,
    mode: TravelMode,
  ): List<OffRoadCandidate> {
    if (projections.isEmpty()) return emptyList()
    val sortedByDist = projections.sortedBy { it.bestDist }
    val picked = mutableListOf<WayProjection>()
    val usedWayIds = mutableSetOf<Long>()
    val usedSides = mutableSetOf<OffRoadSide>()
    val usedRoadSideKeys = mutableSetOf<String>()

    fun roadNameKey(way: OsmWay): String {
      val b = BilingualName.fromOsmTags(way.tags)
      return (b.english ?: b.arabic ?: b.ref ?: "way_${way.id}").lowercase()
    }

    // Pass 1: closest distinct road from each distinct relative side (Ahead/Left/Right/Behind)
    for (p in sortedByDist) {
      if (picked.size >= 3) break
      val nameKey = "${roadNameKey(p.way)}_${p.side.name}"
      if (p.side !in usedSides && p.way.id !in usedWayIds && nameKey !in usedRoadSideKeys) {
        picked += p
        usedSides += p.side
        usedWayIds += p.way.id
        usedRoadSideKeys += nameKey
      }
    }

    // Pass 2: fill remaining slots (up to 3) with distinct named roads / distinct way IDs
    for (p in sortedByDist) {
      if (picked.size >= 3) break
      val nameKey = "${roadNameKey(p.way)}_${p.side.name}"
      if (p.way.id !in usedWayIds && nameKey !in usedRoadSideKeys) {
        picked += p
        usedWayIds += p.way.id
        usedRoadSideKeys += nameKey
      }
    }

    // Pass 3: any remaining distinct way IDs
    for (p in sortedByDist) {
      if (picked.size >= 3) break
      if (p.way.id !in usedWayIds) {
        picked += p
        usedWayIds += p.way.id
      }
    }

    return picked.mapIndexed { idx, proj ->
      val cand = scoreCandidate(proj, radiusMeters = AppConfig.MATCH_MAX_RADIUS_M, mode = mode, previousWayId = null)
      val stubWorld = buildRoadStubAroundSnap(proj.bestSnap, proj.forwardBearing)
      val stubLocal = stubWorld.map { GeoMath.projectToHeadingFrame(position, headingDeg, it) }
      OffRoadCandidate(
        badgeId = "R${idx + 1}",
        candidate = cand,
        side = proj.side,
        distanceMeters = proj.bestDist,
        closestPoint = proj.bestSnap,
        bearingFromUserDeg = proj.bearingFromUser,
        bearingDeltaDeg = proj.bearingDeltaFromUserHeading,
        stubWorldPoints = stubWorld,
        stubLocalPoints = stubLocal,
        forwardBearingDeg = proj.forwardBearing,
        reverseBearingDeg = proj.reverseBearing,
      )
    }
  }

  private fun buildRoadStubAroundSnap(snap: GeoPoint, bearingDeg: Double): List<GeoPoint> {
    val pBack = GeoMath.offsetPoint(snap, (bearingDeg + 180.0) % 360.0, 14.0)
    val pAhead = GeoMath.offsetPoint(snap, bearingDeg, 14.0)
    return listOf(pBack, snap, pAhead)
  }
}
