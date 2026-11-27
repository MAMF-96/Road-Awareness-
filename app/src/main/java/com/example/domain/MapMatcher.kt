package com.example.domain

import com.example.config.AppConfig
import com.example.data.model.BilingualName
import com.example.data.model.CandidateFactorDebug
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

/**
 * Prevents flapping between unrelated nearby roads when their match scores are close.
 * Connected continuation ways along the active route switch immediately; unrelated ways must
 * beat the active way's raw score by >= 30% for 3 consecutive fixes.
 */
class WayMatchHysteresis {
  private var activeWayId: Long? = null
  private var pendingWayId: Long? = null
  private var pendingCount: Int = 0

  fun reset() {
    activeWayId = null
    pendingWayId = null
    pendingCount = 0
  }

  data class SelectionResult<T>(
    val chosen: T,
    val note: String? = null,
  )

  fun <T> select(
    best: T,
    allScored: List<T>,
    wayIdOf: (T) -> Long,
    rawScoreOf: (T) -> Double,
    sharesNodeWithActive: (T) -> Boolean,
  ): SelectionResult<T> {
    val bestId = wayIdOf(best)
    val prevId = activeWayId
    if (prevId == null) {
      activeWayId = bestId
      pendingWayId = null
      pendingCount = 0
      return SelectionResult(best, null)
    }
    if (bestId == prevId) {
      pendingWayId = null
      pendingCount = 0
      return SelectionResult(best, null)
    }

    val activeCandidate = allScored.firstOrNull { wayIdOf(it) == prevId }
    if (activeCandidate == null) {
      // Previous road is no longer within match radius -> switch immediately.
      activeWayId = bestId
      pendingWayId = null
      pendingCount = 0
      return SelectionResult(best, "switched: prev left radius")
    }

    if (sharesNodeWithActive(best)) {
      // Natural continuation onto connected next segment along corridor.
      activeWayId = bestId
      pendingWayId = null
      pendingCount = 0
      return SelectionResult(best, "switched: connected continuation")
    }

    val activeScore = rawScoreOf(activeCandidate).coerceAtLeast(1e-6)
    val bestScore = rawScoreOf(best)
    val marginRatio = (bestScore - activeScore) / activeScore
    if (marginRatio >= 0.30) {
      if (pendingWayId == bestId) {
        pendingCount++
      } else {
        pendingWayId = bestId
        pendingCount = 1
      }
      if (pendingCount >= 3) {
        activeWayId = bestId
        pendingWayId = null
        pendingCount = 0
        return SelectionResult(best, "switched after 3 fixes (+${(marginRatio * 100).toInt()}%)")
      }
      return SelectionResult(activeCandidate, "hold way=$prevId (cand=$bestId ${pendingCount}/3)")
    } else {
      pendingWayId = null
      pendingCount = 0
      return SelectionResult(activeCandidate, "hold way=$prevId (+${(marginRatio * 100).toInt()}% < 30%)")
    }
  }
}

object MapMatcher {
  private val WALKING_ONLY_HIGHWAYS = setOf("footway", "path", "pedestrian", "steps", "cycleway")
  private val NON_DRIVING_HIGHWAYS =
    setOf("footway", "path", "pedestrian", "steps", "cycleway", "corridor", "raceway", "bus_guideway")
  private val NON_WALKING_HIGHWAYS = setOf("motorway", "motorway_link", "raceway", "bus_guideway")
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

  private data class ScoredEntry(
    val candidate: RoadCandidate,
    val rawScore: Double,
    val classPrior: Double,
    val sharesNodeWithPrev: Boolean,
    val debug: CandidateFactorDebug,
  )

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
    hysteresis: WayMatchHysteresis? = null,
  ): MatchResult {
    val startNs = System.nanoTime()
    val baseRadius = computeOnRoadMatchRadius(accuracyMeters)
    val indexEdgeCount = spatialIndex?.edgeCount ?: graph.ways.size
    if (graph.ways.isEmpty()) {
      hysteresis?.reset()
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

    val prevNodeSet = previousWayId?.let { graph.ways[it]?.nodeIds?.toHashSet() }
    val queryRadius = AppConfig.MATCH_MAX_RADIUS_M + 35.0
    val candidateWays = spatialIndex?.queryWaysAround(position, queryRadius) ?: graph.ways.values
    val projectionResult = projectRelevantWays(candidateWays, position, headingDeg, mode)
    val wayProjections = projectionResult.projections
    val onRoadScored = mutableListOf<ScoredEntry>()
    var droppedByRadius = 0

    for (proj in wayProjections) {
      if (proj.bestDist > baseRadius) {
        droppedByRadius++
        continue
      }
      onRoadScored +=
        scoreCandidateDetailed(
          graph = graph,
          proj = proj,
          radiusMeters = baseRadius,
          mode = mode,
          previousWayId = previousWayId,
          prevNodeSet = prevNodeSet,
        )
    }

    val nearestDist = projectionResult.nearestAnyDist
    val nearestWay = projectionResult.nearestAnyWay

    if (onRoadScored.isNotEmpty()) {
      val sorted = sortWithLateralClassTieBreak(onRoadScored)
      val bestRaw = sorted.first()
      val selection =
        if (hysteresis != null) {
          hysteresis.select(
            best = bestRaw,
            allScored = sorted,
            wayIdOf = { it.candidate.way.id },
            rawScoreOf = { it.rawScore },
            sharesNodeWithActive = { it.sharesNodeWithPrev },
          )
        } else {
          WayMatchHysteresis.SelectionResult(bestRaw, null)
        }
      val primaryEntry = selection.chosen
      val primary = primaryEntry.candidate

      val secondary =
        sorted
          .firstOrNull { entry ->
            val cand = entry.candidate
            cand.way.id != primary.way.id &&
              cand.bearingDiffDeg < 45.0 &&
              abs(primary.confidence - cand.confidence) <= AppConfig.UNCERTAIN_CONFIDENCE_DELTA &&
              cand.confidence >= 0.35
          }
          ?.candidate

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
            topCandidates = sorted.take(3).map { it.debug },
            hysteresisNote = selection.note,
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
      val hw = way.tags.highway?.lowercase()?.trim()
      if (hw == null) {
        droppedByClass++
        continue
      }
      if (mode == TravelMode.DRIVING && hw in NON_DRIVING_HIGHWAYS) {
        droppedByClass++
        continue
      }
      if (mode == TravelMode.WALKING && hw in NON_WALKING_HIGHWAYS) {
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

  internal fun classPrior(highway: String?, mode: TravelMode): Double {
    val hw = highway?.lowercase()?.trim().orEmpty()
    return when (mode) {
      TravelMode.DRIVING ->
        when {
          hw in setOf("motorway", "trunk", "primary") -> 1.0
          hw == "secondary" -> 0.96
          hw == "tertiary" -> 0.92
          hw.endsWith("_link") -> 0.90
          hw in setOf("unclassified", "residential") -> 0.85
          hw == "living_street" -> 0.78
          hw == "service" -> 0.65
          hw == "track" -> 0.55
          else -> 0.82
        }
      TravelMode.WALKING ->
        when {
          hw in setOf("footway", "path", "pedestrian", "steps") -> 1.0
          hw in setOf("living_street", "cycleway") -> 0.96
          hw == "residential" -> 0.92
          hw == "unclassified" -> 0.88
          hw in setOf("service", "track") -> 0.82
          hw == "tertiary" -> 0.78
          hw == "secondary" -> 0.75
          hw in setOf("primary", "trunk") -> 0.65
          hw.endsWith("_link") -> 0.50
          else -> 0.80
        }
    }
  }

  private fun polylineLengthMeters(way: OsmWay): Double {
    var total = 0.0
    for (i in 0 until way.points.lastIndex) {
      total += GeoMath.haversineMeters(way.points[i], way.points[i + 1])
    }
    return total
  }

  /**
   * Returns true if [way] belongs to an isolated road component shorter than 60 m
   * OR forms a dead-end spur shorter than 60 m ending in a degree-1 tip.
   */
  internal fun isShortDeadEndSpur(graph: RoadGraph, way: OsmWay): Boolean {
    if (way.nodeIds.isEmpty()) {
      return polylineLengthMeters(way) < 60.0
    }
    val selfLen = polylineLengthMeters(way)
    if (selfLen >= 60.0) return false

    // 1) Small isolated connected component check (< 60 m total)
    val visited = HashSet<Long>(8)
    val queue = ArrayDeque<OsmWay>(8)
    visited += way.id
    queue += way
    var componentLen = 0.0
    var reachedJunction = false

    while (queue.isNotEmpty() && visited.size <= 16 && componentLen < 60.0) {
      val cur = queue.removeFirst()
      componentLen += polylineLengthMeters(cur)
      if (componentLen >= 60.0) break
      val endpoints = listOfNotNull(cur.nodeIds.firstOrNull(), cur.nodeIds.lastOrNull())
      for (nid in endpoints) {
        val adj = graph.nodeToWays[nid].orEmpty().distinct()
        if (adj.size >= 3) reachedJunction = true
        for (otherId in adj) {
          if (visited.add(otherId)) {
            val other = graph.ways[otherId] ?: continue
            queue += other
          }
        }
      }
    }
    if (queue.isEmpty() && componentLen < 60.0 && visited.size <= 16) {
      return true
    }

    // 2) Connected at one end, dead-end at the other end: trace from dead-end tip until a junction (>= 3 ways)
    if (!reachedJunction) return false
    val startNid = way.nodeIds.firstOrNull()
    val endNid = way.nodeIds.lastOrNull()
    val startAdjCount = startNid?.let { graph.nodeToWays[it].orEmpty().distinct().size } ?: 0
    val endAdjCount = endNid?.let { graph.nodeToWays[it].orEmpty().distinct().size } ?: 0
    val isDeadEndWay = startAdjCount <= 1 || endAdjCount <= 1
    if (!isDeadEndWay) return false

    // Walk back from dead-end tip(s) summing single-chain spur until junction or >= 60 m
    var spurLen = selfLen
    var prevId = way.id
    var nextAnchorNid = if (startAdjCount <= 1) endNid else startNid
    var hops = 0
    while (nextAnchorNid != null && spurLen < 60.0 && hops < 8) {
      val adjIds = graph.nodeToWays[nextAnchorNid].orEmpty().distinct().filter { it != prevId }
      if (adjIds.size != 1) {
        // Reached a real junction (>= 2 continuing branches) or another dead end
        return spurLen < 60.0
      }
      val nextWay = graph.ways[adjIds.first()] ?: break
      spurLen += polylineLengthMeters(nextWay)
      prevId = nextWay.id
      nextAnchorNid =
        when (nextAnchorNid) {
          nextWay.nodeIds.firstOrNull() -> nextWay.nodeIds.lastOrNull()
          nextWay.nodeIds.lastOrNull() -> nextWay.nodeIds.firstOrNull()
          else -> null
        }
      hops++
    }
    return spurLen < 60.0
  }

  private fun sortWithLateralClassTieBreak(entries: List<ScoredEntry>): List<ScoredEntry> {
    if (entries.size <= 1) return entries
    return entries.sortedWith { a, b ->
      val aCand = a.candidate
      val bCand = b.candidate
      if (aCand.lateralOffsetMeters <= 20.0 && bCand.lateralOffsetMeters <= 20.0 && abs(a.classPrior - b.classPrior) > 0.01) {
        val higher = if (a.classPrior > b.classPrior) a else b
        val lower = if (a.classPrior > b.classPrior) b else a
        val lowerMatchesHigherDoesNot =
          lower.candidate.bearingDiffDeg <= 35.0 && higher.candidate.bearingDiffDeg > 60.0
        if (!lowerMatchesHigherDoesNot) {
          return@sortedWith if (a === higher) -1 else 1
        }
      }
      b.rawScore.compareTo(a.rawScore)
    }
  }

  private fun scoreCandidateDetailed(
    graph: RoadGraph,
    proj: WayProjection,
    radiusMeters: Double,
    mode: TravelMode,
    previousWayId: Long?,
    prevNodeSet: Set<Long>?,
  ): ScoredEntry {
    val hw = proj.way.tags.highway ?: ""
    val distScore = exp(-proj.bestDist / (radiusMeters * 0.45))
    val angleScore = exp(-proj.bearingDiff / 52.0)
    val prior = classPrior(hw, mode)
    val spurFactor =
      if (proj.way.id != previousWayId && isShortDeadEndSpur(graph, proj.way)) 0.75 else 1.0
    val sharesNode =
      prevNodeSet != null &&
        proj.way.id != previousWayId &&
        proj.way.nodeIds.any { it in prevNodeSet }
    val continuityBonus = if (proj.way.id == previousWayId || sharesNode) 1.15 else 1.0
    val rawScore =
      (distScore * 0.58 + angleScore * 0.42) *
        proj.onewayPenalty *
        prior *
        spurFactor *
        continuityBonus

    val name = BilingualName.fromOsmTags(proj.way.tags)
    val displayName = name.english ?: name.arabic ?: name.ref ?: "unnamed"
    val candidate =
      RoadCandidate(
        way = proj.way,
        name = name,
        confidence = rawScore.coerceIn(0.0, 1.0),
        lateralOffsetMeters = proj.bestDist,
        bearingDiffDeg = proj.bearingDiff,
        snappedPoint = proj.bestSnap,
        segmentIndex = proj.bestSegIdx,
        forwardAlongWay = proj.forwardAlongWay,
      )
    return ScoredEntry(
      candidate = candidate,
      rawScore = rawScore,
      classPrior = prior,
      sharesNodeWithPrev = sharesNode,
      debug =
        CandidateFactorDebug(
          wayId = proj.way.id,
          highway = hw.ifBlank { "road" },
          name = displayName,
          distanceMeters = proj.bestDist,
          distScore = distScore,
          angleScore = angleScore,
          classPrior = prior,
          spurFactor = spurFactor,
          continuityFactor = continuityBonus,
          onewayFactor = proj.onewayPenalty,
          finalScore = rawScore,
        ),
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
    val prior = classPrior(hw, mode)
    val continuityBonus = if (proj.way.id == previousWayId) 1.15 else 1.0
    val confidence =
      (distScore * 0.58 + angleScore * 0.42) * proj.onewayPenalty * prior * continuityBonus

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
