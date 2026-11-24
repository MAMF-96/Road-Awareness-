package com.example.domain

import com.example.config.AppConfig
import com.example.data.model.AheadAnalysisResult
import com.example.data.model.AheadTimelineEntry
import com.example.data.model.BilingualName
import com.example.data.model.BranchStatus
import com.example.data.model.DividedRoadInfo
import com.example.data.model.FeatureCensusEntry
import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.LandmarkAheadItem
import com.example.data.model.LocalPoint
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.PoiCategory
import com.example.data.model.PoiItem
import com.example.data.model.RelativeDirection
import com.example.data.model.RoadBranch
import com.example.data.model.RoadCandidate
import com.example.data.model.RoadFeature
import com.example.data.model.RoadGraph
import com.example.data.model.TravelMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

object AheadGraphAnalyzer {
  private val WALKING_PATH_HIGHWAYS = setOf("footway", "path", "pedestrian", "steps")
  private val NON_DRIVING_HIGHWAYS = setOf("footway", "path", "pedestrian", "steps", "cycleway")
  private val IGNORED_DRIVING_SERVICES = setOf("driveway", "parking_aisle", "drive-through")
  private val LINK_HIGHWAYS =
    setOf("motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link")
  private val BARRIER_BLOCKING_TYPES = setOf("gate", "lift_gate", "bollard")
  private val CHECKPOINT_BARRIER_TYPES = setOf("border_control", "checkpoint")

  private const val DRIVING_COMPOSITE_CORRIDOR_M = 800.0
  private const val WALKING_COMPOSITE_CORRIDOR_M = 250.0
  private const val MAX_COMPOSITE_CACHE_ENTRIES = 64

  private data class CompositeCacheKey(
    val cellGroup: String,
    val fetchedAtMs: Long,
    val wayCount: Int,
    val nodeCount: Int,
    val source: GraphDataSource,
    val matchedWayId: Long,
    val segmentIndex: Int,
    val forwardAlongWay: Boolean,
    val mode: TravelMode,
    val lookaheadBucket: Int,
    val loadedTileCount: Int,
    val loadedTilesHash: Int,
    val taggedNodesHash: Int,
    val waysSignatureHash: Int,
    val showMinorJunctions: Boolean,
    val landmarksEnabled: Boolean,
    val poisHash: Int,
  )

  private val compositeCacheLock = Any()
  private val compositeAnalysisCache =
    object : LinkedHashMap<CompositeCacheKey, AheadAnalysisResult>(MAX_COMPOSITE_CACHE_ENTRIES, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CompositeCacheKey, AheadAnalysisResult>?): Boolean =
        size > MAX_COMPOSITE_CACHE_ENTRIES
    }

  fun clearCompositeCache() {
    synchronized(compositeCacheLock) {
      compositeAnalysisCache.clear()
    }
  }

  private fun computeTaggedNodesHash(graph: RoadGraph): Int {
    var h = graph.nodes.size
    for (node in graph.nodes.values) {
      if (node.tags.hasAnyTag()) {
        h = 31 * h + node.id.hashCode()
        h = 31 * h + node.tags.hashCode()
      }
    }
    return h
  }

  private fun computeWaysSignatureHash(graph: RoadGraph): Int {
    var h = graph.ways.size
    for (way in graph.ways.values) {
      h = 31 * h + way.id.hashCode()
      h = 31 * h + way.tags.hashCode()
      h = 31 * h + way.nodeIds.size
    }
    return h
  }

  private fun buildCellGroupKey(graph: RoadGraph, origin: GeoPoint): String {
    val cellDeg = AppConfig.CACHE_CELL_SIZE_DEG
    val originLat = floor(origin.lat / cellDeg).toInt()
    val originLon = floor(origin.lon / cellDeg).toInt()
    val cells = sortedSetOf("${originLat}_${originLon}")
    for (way in graph.ways.values) {
      val pt = way.points.firstOrNull() ?: continue
      val cLat = floor(pt.lat / cellDeg).toInt()
      val cLon = floor(pt.lon / cellDeg).toInt()
      if (abs(cLat - originLat) <= 2 && abs(cLon - originLon) <= 2) {
        cells += "${cLat}_${cLon}"
      }
    }
    return cells.joinToString(",")
  }

  fun computeLookaheadMeters(
    mode: TravelMode,
    speedMps: Double,
    configuredHorizonMeters: Double = AppConfig.DRIVING_DEFAULT_HORIZON_M,
  ): Double {
    return if (mode == TravelMode.WALKING) {
      AppConfig.WALKING_LOOKAHEAD_M
    } else {
      val minM = AppConfig.DRIVING_MIN_LOOKAHEAD_M
      val maxM = configuredHorizonMeters.coerceIn(minM, AppConfig.DRIVING_MAX_LOOKAHEAD_M)
      val ratio = ((speedMps - 2.0) / 20.0).coerceIn(0.0, 1.0)
      minM + ratio * (maxM - minM)
    }
  }

  fun effectiveLayer(tags: OsmTags): Int {
    tags.layer?.trim()?.toIntOrNull()?.let { return it }
    if (tags.bridge != null && tags.bridge != "no") return 1
    if (tags.tunnel != null && tags.tunnel != "no") return -1
    return 0
  }

  private data class TracedSegment(
    val p1: GeoPoint,
    val p2: GeoPoint,
    val nodeId1: Long?,
    val nodeId2: Long?,
    val cumDistAtP1: Double,
    val segLenMeters: Double,
    val bearingDeg: Double,
    val way: OsmWay,
    val idx1: Int,
    val forward: Boolean,
  )

  private data class ClassificationOutcome(
    val type: FeatureType,
    val ruleId: String,
    val confidence: Double,
    val roundaboutCycleId: Int? = null,
    val forcedBlockedBranch: Boolean = false,
  )

  fun analyzeAhead(
    graph: RoadGraph,
    matched: RoadCandidate,
    mode: TravelMode,
    speedMps: Double,
  ): List<RoadFeature> = analyzeAheadDetailed(graph, matched, mode, speedMps).features

  fun analyzeAheadDetailed(
    graph: RoadGraph,
    matched: RoadCandidate,
    mode: TravelMode,
    speedMps: Double,
    configuredHorizonMeters: Double = AppConfig.DRIVING_DEFAULT_HORIZON_M,
    showMinorJunctions: Boolean = true,
    pois: List<PoiItem> = emptyList(),
    landmarksEnabled: Boolean = true,
  ): AheadAnalysisResult {
    val maxLookahead = computeLookaheadMeters(mode, speedMps, configuredHorizonMeters)
    val compositeCorridorMeters =
      if (mode == TravelMode.DRIVING) DRIVING_COMPOSITE_CORRIDOR_M else WALKING_COMPOSITE_CORRIDOR_M
    val cacheKey =
      CompositeCacheKey(
        cellGroup = buildCellGroupKey(graph, matched.snappedPoint),
        fetchedAtMs = graph.fetchedAtMs,
        wayCount = graph.ways.size,
        nodeCount = graph.nodes.size,
        source = graph.source,
        matchedWayId = matched.way.id,
        segmentIndex = matched.segmentIndex,
        forwardAlongWay = matched.forwardAlongWay,
        mode = mode,
        lookaheadBucket = (maxLookahead / 50.0).toInt(),
        loadedTileCount = graph.loadedTiles.size,
        loadedTilesHash = graph.loadedTiles.hashCode(),
        taggedNodesHash = computeTaggedNodesHash(graph),
        waysSignatureHash = computeWaysSignatureHash(graph),
        showMinorJunctions = showMinorJunctions,
        landmarksEnabled = landmarksEnabled,
        poisHash = pois.size * 31 + pois.firstOrNull()?.id.hashCode(),
      )
    synchronized(compositeCacheLock) {
      compositeAnalysisCache[cacheKey]?.let { return it }
    }

    val traceLimit = maxOf(maxLookahead, compositeCorridorMeters)
    val features = mutableListOf<RoadFeature>()
    val visitedWayIds = mutableSetOf<Long>()
    val visitedJunctionNodes = mutableSetOf<Long>()
    val visitedPointNodes = mutableSetOf<Long>()
    val visitedRoundaboutCycles = mutableSetOf<Int>()
    val tracedSegments = mutableListOf<TracedSegment>()

    val roundaboutInfo = detectGeometricCycles(graph)

    var currentWay = matched.way
    var forward = matched.forwardAlongWay
    var startNodeIndex = if (forward) matched.segmentIndex + 1 else matched.segmentIndex
    var prevPoint = matched.snappedPoint
    var prevNodeId: Long? = null
    var prevIdx = matched.segmentIndex
    var accumulatedDist = 0.0
    var stepGuard = 0

    // If the user is already matched onto a highway=construction or access=no way, surface ROAD_WORKS immediately
    if (isRoadWorksWay(currentWay)) {
      val aheadPts = sliceWorldPath(currentWay, startNodeIndex.coerceIn(0, currentWay.points.lastIndex), forward, takeBack = false, maxMeters = 60.0)
      val bearing =
        if (aheadPts.size >= 2) GeoMath.bearingDeg(aheadPts[0], aheadPts[1])
        else GeoMath.bearingDeg(currentWay.points[matched.segmentIndex], currentWay.points[(matched.segmentIndex + 1).coerceAtMost(currentWay.points.lastIndex)])
      val blockedBranch =
        RoadBranch(
          badgeId = "1A",
          wayId = currentWay.id,
          name = BilingualName.fromOsmTags(currentWay.tags),
          leadsTo = BilingualName.destinationFromOsmTags(currentWay.tags),
          distanceMeters = 0.0,
          status = BranchStatus.BLOCKED,
          relativeDirection = RelativeDirection.AHEAD,
          bearingDeltaDeg = 0.0,
          worldPoints = aheadPts,
          localPoints = aheadPts.map { GeoMath.projectToHeadingFrame(matched.snappedPoint, bearing, it) },
          osmTags = currentWay.tags,
        )
      features +=
        RoadFeature(
          index = 1,
          type = FeatureType.ROAD_WORKS,
          distanceMeters = 0.0,
          anchor = matched.snappedPoint,
          anchorNodeId = currentWay.nodeIds.getOrNull(matched.segmentIndex),
          incomingLocalPoints = emptyList(),
          incomingWorldPoints = emptyList(),
          outgoingMainLocalPoints = blockedBranch.localPoints,
          outgoingMainWorldPoints = aheadPts,
          branches = listOf(blockedBranch),
          osmTags = currentWay.tags,
          source = currentWay.source,
          ruleId = if (currentWay.tags.highway?.lowercase() == "construction") "osm_highway_construction" else "osm_access_no",
          confidence = 0.97,
        )
    }

    while (accumulatedDist < traceLimit && stepGuard++ < 260) {
      visitedWayIds += currentWay.id
      val indices =
        if (forward) {
          (startNodeIndex until currentWay.points.size).toList()
        } else {
          (startNodeIndex downTo 0).toList()
        }

      if (indices.isEmpty()) break

      var lastTraversedNodeId: Long? = null
      var lastIncomingBearing =
        if (indices.isNotEmpty()) {
          val firstPt = currentWay.points[indices.first()]
          if (GeoMath.haversineMeters(prevPoint, firstPt) > 1.0) {
            GeoMath.bearingDeg(prevPoint, firstPt)
          } else {
            val pIdx = if (forward) (indices.first() - 1).coerceAtLeast(0) else (indices.first() + 1).coerceAtMost(currentWay.points.lastIndex)
            GeoMath.bearingDeg(currentWay.points[pIdx], currentWay.points[indices.first()])
          }
        } else {
          0.0
        }

      for (posInList in indices.indices) {
        val idx = indices[posInList]
        val pt = currentWay.points[idx]
        val segStartDist = accumulatedDist
        val segDist = GeoMath.haversineMeters(prevPoint, pt)
        accumulatedDist += segDist
        if (segDist > 1.0) {
          lastIncomingBearing = GeoMath.bearingDeg(prevPoint, pt)
        }
        val nodeId = currentWay.nodeIds.getOrNull(idx)
        if (segDist > 0.5) {
          tracedSegments +=
            TracedSegment(
              p1 = prevPoint,
              p2 = pt,
              nodeId1 = prevNodeId,
              nodeId2 = nodeId,
              cumDistAtP1 = segStartDist,
              segLenMeters = segDist,
              bearingDeg = lastIncomingBearing,
              way = currentWay,
              idx1 = prevIdx,
              forward = forward,
            )
        }
        if (accumulatedDist > traceLimit) break

        lastTraversedNodeId = nodeId

        // 1) Node-level Overpass features (Crosswalk in Walking; Toll/Checkpoint/Barrier/Mini-Roundabout in Overpass)
        if (nodeId != null && nodeId !in visitedPointNodes) {
          val node = graph.nodes[nodeId]
          if (node != null) {
            if (mode == TravelMode.WALKING && (node.tags.highway == "crossing" || node.tags.crossing != null)) {
              visitedPointNodes += nodeId
              val incoming = sliceWorldPath(currentWay, idx, forward, takeBack = true, maxMeters = 45.0)
              val outgoing = sliceWorldPath(currentWay, idx, forward, takeBack = false, maxMeters = 45.0)
              features +=
                buildPointFeature(
                  index = features.size + 1,
                  type = FeatureType.CROSSWALK,
                  distance = accumulatedDist,
                  anchor = pt,
                  nodeId = nodeId,
                  headingDeg = lastIncomingBearing,
                  incomingWorld = incoming,
                  outgoingWorld = outgoing,
                  tags = node.tags.takeIf { it.hasAnyTag() } ?: currentWay.tags,
                  isCrosswalk = true,
                  source = currentWay.source,
                  ruleId = "osm_crossing",
                  confidence = 0.96,
                )
            } else if (currentWay.source != GraphDataSource.TILES && nodeId > 0L) {
              val nodeFeature = classifyOverpassNodeTag(node.tags)
              if (nodeFeature != null) {
                visitedPointNodes += nodeId
                val incoming = sliceWorldPath(currentWay, idx, forward, takeBack = true, maxMeters = 50.0)
                val outgoing = sliceWorldPath(currentWay, idx, forward, takeBack = false, maxMeters = 50.0)
                features +=
                  buildPointFeature(
                    index = features.size + 1,
                    type = nodeFeature.type,
                    distance = accumulatedDist,
                    anchor = pt,
                    nodeId = nodeId,
                    headingDeg = lastIncomingBearing,
                    incomingWorld = incoming,
                    outgoingWorld = outgoing,
                    tags = node.tags,
                    source = GraphDataSource.OVERPASS,
                    ruleId = nodeFeature.ruleId,
                    confidence = nodeFeature.confidence,
                  )
              }
            }
          }
        }

        // 2) Sharp curve at intermediate vertices (excluding junction nodes with >1 relevant ways)
        val connectedWaysAtNode =
          if (nodeId != null) {
            (graph.nodeToWays[nodeId] ?: emptyList())
              .mapNotNull { graph.ways[it] }
              .filter { isWayCountedAtNode(it, currentWay.id, mode) }
          } else {
            emptyList()
          }

        if (connectedWaysAtNode.size <= 1 && posInList > 0 && posInList < indices.lastIndex) {
          val pPrev = currentWay.points[indices[posInList - 1]]
          val pCurr = pt
          val pNext = currentWay.points[indices[posInList + 1]]
          val (turnDeg, radiusM) = GeoMath.turningAngleAndRadius(pPrev, pCurr, pNext)
          if (turnDeg >= AppConfig.SHARP_CURVE_MIN_ANGLE_DEG && radiusM <= AppConfig.SHARP_CURVE_MAX_RADIUS_M) {
            val nearExistingCurve =
              features.any { it.type == FeatureType.SHARP_CURVE && abs(it.distanceMeters - accumulatedDist) < 40.0 }
            if (!nearExistingCurve) {
              val incoming = sliceWorldPath(currentWay, idx, forward, takeBack = true, maxMeters = 55.0)
              val outgoing = sliceWorldPath(currentWay, idx, forward, takeBack = false, maxMeters = 55.0)
              features +=
                RoadFeature(
                  index = features.size + 1,
                  type = FeatureType.SHARP_CURVE,
                  distanceMeters = accumulatedDist,
                  anchor = pCurr,
                  anchorNodeId = nodeId,
                  incomingLocalPoints = incoming.map { GeoMath.projectToHeadingFrame(pCurr, lastIncomingBearing, it) },
                  incomingWorldPoints = incoming,
                  outgoingMainLocalPoints = outgoing.map { GeoMath.projectToHeadingFrame(pCurr, lastIncomingBearing, it) },
                  outgoingMainWorldPoints = outgoing,
                  branches = emptyList(),
                  osmTags = currentWay.tags,
                  curveAngleDeg = turnDeg,
                  curveRadiusMeters = radiusM,
                  source = currentWay.source,
                  ruleId = "geom_sharp_curve",
                  confidence = 0.90,
                )
            }
          }
        }

        // 3) Junction / Branching / Endpoint detection
        if (nodeId != null && nodeId !in visitedJunctionNodes) {
          val isEndpoint = idx == if (forward) currentWay.points.lastIndex else 0
          val hasOtherWays = connectedWaysAtNode.any { it.id != currentWay.id }
          val currentWayHasAheadSegment = !isEndpoint

          if (hasOtherWays || (isEndpoint && connectedWaysAtNode.size <= 1)) {
            val outgoingEdges =
              extractOutgoingEdges(
                graph = graph,
                junctionNodeId = nodeId,
                incomingWay = currentWay,
                incomingForward = forward,
                incomingBearing = lastIncomingBearing,
                includeCurrentAhead = currentWayHasAheadSegment,
                currentIdx = idx,
                mode = mode,
              )

            if (outgoingEdges.isNotEmpty() || isEndpoint) {
              val incomingWorld = sliceWorldPath(currentWay, idx, forward, takeBack = true, maxMeters = 60.0)
              val feature =
                classifyJunctionOrEndpoint(
                  graph = graph,
                  roundaboutInfo = roundaboutInfo,
                  visitedRoundaboutCycles = visitedRoundaboutCycles,
                  featureIndex = features.size + 1,
                  distance = accumulatedDist,
                  junctionPoint = pt,
                  junctionNodeId = nodeId,
                  incomingWay = currentWay,
                  incomingBearing = lastIncomingBearing,
                  incomingWorld = incomingWorld,
                  edges = outgoingEdges,
                  includeCurrentAhead = currentWayHasAheadSegment,
                  mode = mode,
                )
              if (feature != null) {
                visitedJunctionNodes += nodeId
                features += feature
              }
            }
          }
        }

        prevPoint = pt
        prevNodeId = nodeId
        prevIdx = idx
      }

      if (accumulatedDist >= traceLimit || lastTraversedNodeId == null) break

      // Continue along straightest open continuation way from lastTraversedNodeId
      val nextHop =
        findContinuationWay(
          graph = graph,
          fromNodeId = lastTraversedNodeId,
          currentWay = currentWay,
          incomingBearing = lastIncomingBearing,
          visitedWayIds = visitedWayIds,
          mode = mode,
        ) ?: break

      currentWay = nextHop.way
      forward = nextHop.forward
      startNodeIndex = if (forward) nextHop.nodeIndex + 1 else nextHop.nodeIndex - 1
      prevNodeId = lastTraversedNodeId
      prevIdx = nextHop.nodeIndex

      // Stop advancing past a ROAD_WORKS way after it has been emitted at the transition
      if (isRoadWorksWay(currentWay)) break

      // Detect entering a bridge / tunnel / footbridge on way transition
      val wayIsBridge = currentWay.tags.bridge != null && currentWay.tags.bridge != "no"
      val wayIsTunnel = currentWay.tags.tunnel != null && currentWay.tags.tunnel != "no"
      val wayIsFootbridge = wayIsBridge && mode == TravelMode.WALKING && currentWay.tags.highway in WALKING_PATH_HIGHWAYS
      if ((wayIsBridge || wayIsTunnel) && accumulatedDist > 8.0) {
        val anchorPt = currentWay.points[nextHop.nodeIndex]
        val alreadyAtNode =
          features.any {
            it.anchorNodeId == lastTraversedNodeId &&
              (it.type == FeatureType.BRIDGE || it.type == FeatureType.TUNNEL || it.type == FeatureType.FOOTBRIDGE)
          }
        if (!alreadyAtNode) {
          val incoming = listOf(prevPoint, anchorPt)
          val outgoing = sliceWorldPath(currentWay, nextHop.nodeIndex, forward, takeBack = false, maxMeters = 60.0)
          val (fType, rId) =
            when {
              wayIsFootbridge -> FeatureType.FOOTBRIDGE to "osm_footbridge"
              wayIsBridge -> FeatureType.BRIDGE to "osm_bridge"
              else -> FeatureType.TUNNEL to "osm_tunnel"
            }
          features +=
            buildPointFeature(
              index = features.size + 1,
              type = fType,
              distance = accumulatedDist,
              anchor = anchorPt,
              nodeId = lastTraversedNodeId,
              headingDeg = lastIncomingBearing,
              incomingWorld = incoming,
              outgoingWorld = outgoing,
              tags = currentWay.tags,
              isBridge = wayIsBridge && !wayIsFootbridge,
              isTunnel = wayIsTunnel,
              isFootbridge = wayIsFootbridge,
              source = currentWay.source,
              ruleId = rId,
              confidence = if (currentWay.source == GraphDataSource.TILES) 0.88 else 0.96,
            )
        }
      }
    }

    // 4) Grade-separated Overpass / Underpass crossings (ways crossing user's path WITHOUT a shared node)
    detectGradeSeparatedCrossings(
      graph = graph,
      tracedSegments = tracedSegments,
      maxLookahead = traceLimit,
      existingFeatures = features,
    )

    // 5) Composite features strictly on the corridor ahead (Driving 800m, Walking 250m)
    val dividedRoadInfo = detectDividedRoadPartner(graph, matched, tracedSegments, mode)
    val groupedFeatures =
      applyCompositeGrouping(
        graph = graph,
        matched = matched,
        tracedSegments = tracedSegments.filter { it.cumDistAtP1 <= compositeCorridorMeters },
        rawFeatures = features,
        dividedRoad = dividedRoadInfo,
        compositeCorridorMeters = compositeCorridorMeters,
        maxStandaloneLookahead = maxLookahead,
        mode = mode,
      )

    val sortedGrouped = groupedFeatures.sortedBy { it.distanceMeters }
    var hiddenMinorCount = 0
    val filteredFeatures =
      if (showMinorJunctions) {
        sortedGrouped
      } else {
        sortedGrouped.filter { feat ->
          val minor = isMinorJunctionOnArterial(matched, feat)
          if (minor) hiddenMinorCount++
          !minor
        }
      }

    // Re-index top 12 features cleanly 1, 2, 3... and re-letter branch IDs 1A, 1B, 1C...
    val indexed =
      filteredFeatures
        .take(12)
        .mapIndexed { idx, feat ->
          val num = idx + 1
          val relettered =
            feat.branches.mapIndexed { bIdx, branch ->
              val letter = ('A' + bIdx)
              branch.copy(badgeId = "$num$letter")
            }
          feat.copy(index = num, branches = relettered)
        }

    val corridorWorldPts = ArrayList<GeoPoint>(tracedSegments.size + 1)
    corridorWorldPts += matched.snappedPoint
    for (seg in tracedSegments) {
      if (seg.cumDistAtP1 > maxLookahead) break
      if (GeoMath.haversineMeters(corridorWorldPts.last(), seg.p2) > 0.8) {
        corridorWorldPts += seg.p2
      }
    }
    val loadedCorridorMeters =
      tracedSegments.lastOrNull()?.let { (it.cumDistAtP1 + it.segLenMeters).coerceAtMost(maxLookahead) } ?: 0.0

    val landmarksAhead =
      if (landmarksEnabled && pois.isNotEmpty() && tracedSegments.isNotEmpty()) {
        extractLandmarksAhead(pois, tracedSegments, maxLookahead, mode)
      } else {
        emptyList()
      }

    val featureTimeline =
      filteredFeatures.mapIndexed { idx, rawFeat ->
        val mapped = if (idx < indexed.size) indexed[idx] else rawFeat
        val branchTitle =
          mapped.branches
            .firstOrNull { it.name.hasAny }?.name
            ?: BilingualName.fromOsmTags(mapped.osmTags)
        val branchNames =
          mapped.branches.mapNotNull { br -> br.name.primaryText ?: br.name.ref }.distinct()
        val summary =
          when {
            branchNames.isNotEmpty() -> branchNames.take(2).joinToString(" · ")
            mapped.branches.isNotEmpty() -> "${mapped.branches.size}"
            else -> null
          }
        val status =
          when {
            mapped.branches.any { it.status == BranchStatus.OPEN } -> BranchStatus.OPEN
            mapped.branches.any { it.status == BranchStatus.UNVERIFIED } -> BranchStatus.UNVERIFIED
            mapped.branches.isNotEmpty() && mapped.branches.all { it.status == BranchStatus.BLOCKED } -> BranchStatus.BLOCKED
            else -> null
          }
        AheadTimelineEntry(
          featureIndex = if (idx < 12) idx + 1 else null,
          type = mapped.effectiveDisplayType(),
          distanceMeters = mapped.distanceMeters,
          title = branchTitle,
          branchSummary = summary,
          status = status,
          anchor = mapped.anchor,
          isLandmark = false,
          primaryWayId = mapped.branches.firstOrNull()?.wayId,
          branchWayIds = mapped.branches.map { it.wayId },
        )
      }

    val landmarkTimeline =
      landmarksAhead.map { lm ->
        AheadTimelineEntry(
          featureIndex = null,
          type = FeatureType.SERVICE_ROAD,
          distanceMeters = lm.distanceMeters,
          title = lm.name,
          branchSummary = lm.category.name.lowercase().replaceFirstChar { it.uppercase() },
          status = null,
          anchor = lm.point,
          isLandmark = true,
        )
      }

    val fullTimeline = (featureTimeline + landmarkTimeline).sortedBy { it.distanceMeters }

    val result =
      AheadAnalysisResult(
        features = indexed,
        dividedRoad = dividedRoadInfo,
        timelineEntries = fullTimeline,
        corridorWorldPoints = corridorWorldPts,
        loadedCorridorMeters = loadedCorridorMeters,
        hiddenMinorCount = hiddenMinorCount,
        landmarksAhead = landmarksAhead,
      )
    synchronized(compositeCacheLock) {
      compositeAnalysisCache[cacheKey] = result
    }
    return result
  }

  private fun isMinorJunctionOnArterial(matched: RoadCandidate, feature: RoadFeature): Boolean {
    if (highwayRank(matched.way.tags.highway) < 4) return false
    if (feature.type !in setOf(FeatureType.T_JUNCTION, FeatureType.Y_JUNCTION, FeatureType.SERVICE_ROAD)) return false
    val sideBranches =
      feature.branches.filter { br ->
        br.relativeDirection != RelativeDirection.AHEAD && abs(br.bearingDeltaDeg) > 18.0
      }
    if (sideBranches.isEmpty()) return false
    return sideBranches.all { br ->
      val hw = br.osmTags.highway?.lowercase()
      val r = highwayRank(hw)
      val hasIdentity =
        BilingualName.fromOsmTags(br.osmTags).hasAny ||
          !br.osmTags.ref.isNullOrBlank() ||
          !br.osmTags.destination.isNullOrBlank()
      r in 1..2 && !hasIdentity
    }
  }

  private fun extractLandmarksAhead(
    pois: List<PoiItem>,
    tracedSegments: List<TracedSegment>,
    maxLookaheadMeters: Double,
    mode: TravelMode,
  ): List<LandmarkAheadItem> {
    val maxLateralM = if (mode == TravelMode.DRIVING) 65.0 else 32.0
    val notableCats =
      setOf(
        PoiCategory.FUEL,
        PoiCategory.HOSPITAL,
        PoiCategory.POLICE,
        PoiCategory.TRANSIT,
        PoiCategory.LANDMARK,
      )
    val candidates = mutableListOf<LandmarkAheadItem>()
    for (poi in pois) {
      if (!poi.name.hasAny) continue
      if (poi.category !in notableCats && poi.category != PoiCategory.SHOP) continue
      var bestDistAlong = Double.MAX_VALUE
      var bestLateral = Double.MAX_VALUE
      var bestBearing = 0.0
      for (seg in tracedSegments) {
        if (seg.cumDistAtP1 > maxLookaheadMeters) break
        val snap = GeoMath.projectPointOnSegment(poi.point, seg.p1, seg.p2)
        if (snap.distanceMeters <= maxLateralM && snap.distanceMeters < bestLateral) {
          val along = seg.cumDistAtP1 + seg.segLenMeters * snap.t
          if (along in 5.0..maxLookaheadMeters) {
            bestLateral = snap.distanceMeters
            bestDistAlong = along
            bestBearing = seg.bearingDeg
          }
        }
      }
      if (bestLateral <= maxLateralM) {
        val segClosest = tracedSegments.minByOrNull { abs(it.cumDistAtP1 - bestDistAlong) }
        val delta =
          if (segClosest != null) {
            val brngToPoi = GeoMath.bearingDeg(segClosest.p1, poi.point)
            GeoMath.signedAngleDelta(bestBearing, brngToPoi)
          } else {
            0.0
          }
        val side =
          when {
            delta < -12.0 -> RelativeDirection.LEFT
            delta > 12.0 -> RelativeDirection.RIGHT
            else -> RelativeDirection.AHEAD
          }
        candidates +=
          LandmarkAheadItem(
            id = poi.id,
            name = poi.name,
            category = poi.category,
            point = poi.point,
            distanceMeters = bestDistAlong,
            side = side,
          )
      }
    }
    val deduped = mutableListOf<LandmarkAheadItem>()
    for (item in candidates.sortedBy { it.distanceMeters }) {
      val dup =
        deduped.any { prev ->
          abs(prev.distanceMeters - item.distanceMeters) < 40.0 &&
            (prev.name.primaryText?.equals(item.name.primaryText, ignoreCase = true) == true)
        }
      if (!dup) deduped += item
      if (deduped.size >= 24) break
    }
    return deduped
  }

  private data class CensusHit(
    val type: FeatureType,
    val distFromCenter: Double,
    val anchor: GeoPoint,
    val jumpPoint: GeoPoint,
    val jumpHeadingDeg: Double,
    val dedupKey: String,
  )

  /**
   * Computes counts per [FeatureType] within [radiusMeters] (default 1.5 km) of [center],
   * and for each type provides a mock jump position 150 m before the nearest instance, facing it.
   */
  fun computeFeatureCensus(
    graph: RoadGraph,
    center: GeoPoint,
    mode: TravelMode,
    radiusMeters: Double = 1500.0,
  ): List<FeatureCensusEntry> {
    if (graph.ways.isEmpty()) return emptyList()

    val hits = mutableListOf<CensusHit>()
    val seenKeys = HashSet<String>()
    val roundaboutInfo = detectGeometricCycles(graph)

    fun addHit(hit: CensusHit) {
      if (hit.distFromCenter > radiusMeters) return
      if (seenKeys.add(hit.dedupKey)) {
        hits += hit
      }
    }

    // Helper: walk backwards along road graph from (way, nodeIdx, forward) up to 150m
    fun computeJumpBefore(way: OsmWay, nodeIdx: Int, forwardToFeature: Boolean, anchor: GeoPoint): Pair<GeoPoint, Double> {
      var curWay = way
      var curIdx = nodeIdx
      var walkBackFwd = !forwardToFeature // direction AWAY from feature
      var prevPt = anchor
      var remaining = 150.0
      val visited = mutableSetOf(curWay.id)
      var hops = 0
      var lastPtTowardFeature = anchor

      while (remaining > 0.5 && hops++ < 10) {
        val step = if (walkBackFwd) 1 else -1
        var i = curIdx + step
        while (i in curWay.points.indices) {
          val nextPt = curWay.points[i]
          val seg = GeoMath.haversineMeters(prevPt, nextPt)
          if (seg >= remaining && seg > 0.1) {
            val frac = remaining / seg
            val jp =
              GeoPoint(
                lat = prevPt.lat + (nextPt.lat - prevPt.lat) * frac,
                lon = prevPt.lon + (nextPt.lon - prevPt.lon) * frac,
              )
            val hdg = GeoMath.bearingDeg(jp, prevPt)
            return jp to hdg
          }
          remaining -= seg
          lastPtTowardFeature = prevPt
          prevPt = nextPt
          curIdx = i
          i += step
        }

        val endNodeId = curWay.nodeIds.getOrNull(curIdx) ?: break
        val incomingBearingAway =
          if (GeoMath.haversineMeters(lastPtTowardFeature, prevPt) > 0.5) {
            GeoMath.bearingDeg(lastPtTowardFeature, prevPt)
          } else {
            0.0
          }
        // Pick straightest connected way continuing backwards
        var bestNext: Triple<OsmWay, Int, Boolean>? = null
        var bestDiff = 100.0
        for (wId in graph.nodeToWays[endNodeId].orEmpty()) {
          if (wId in visited) continue
          val cand = graph.ways[wId] ?: continue
          if (!isRelevantForMode(cand, mode)) continue
          val idxOnCand = cand.nodeIds.indexOf(endNodeId)
          if (idxOnCand < 0) continue
          if (idxOnCand + 1 < cand.points.size) {
            val d = GeoMath.absAngleDiff(incomingBearingAway, GeoMath.bearingDeg(cand.points[idxOnCand], cand.points[idxOnCand + 1]))
            if (d < bestDiff) {
              bestDiff = d
              bestNext = Triple(cand, idxOnCand, true)
            }
          }
          if (idxOnCand - 1 >= 0) {
            val d = GeoMath.absAngleDiff(incomingBearingAway, GeoMath.bearingDeg(cand.points[idxOnCand], cand.points[idxOnCand - 1]))
            if (d < bestDiff) {
              bestDiff = d
              bestNext = Triple(cand, idxOnCand, false)
            }
          }
        }
        if (bestNext == null) break
        visited += bestNext.first.id
        curWay = bestNext.first
        curIdx = bestNext.second
        walkBackFwd = bestNext.third
      }

      val hdg =
        if (GeoMath.haversineMeters(prevPt, lastPtTowardFeature) > 0.5) {
          GeoMath.bearingDeg(prevPt, lastPtTowardFeature)
        } else if (GeoMath.haversineMeters(prevPt, anchor) > 0.5) {
          GeoMath.bearingDeg(prevPt, anchor)
        } else {
          0.0
        }
      return prevPt to hdg
    }

    // 1) Scan ways & nodes within 1.5 km
    for (way in graph.ways.values) {
      if (way.points.size < 2) continue
      val relevant = isRelevantForMode(way, mode)
      if (!relevant && !isRoadWorksWay(way)) continue
      val midPt = way.points[way.points.size / 2]
      val approxWayDist = GeoMath.haversineMeters(center, midPt)
      if (approxWayDist > radiusMeters + 600.0) continue

      // Road works
      if (isRoadWorksWay(way)) {
        val anchor = way.points.first()
        val d = GeoMath.haversineMeters(center, anchor)
        if (d <= radiusMeters) {
          val (jp, hdg) = computeJumpBefore(way, 0, forwardToFeature = true, anchor = anchor)
          addHit(CensusHit(FeatureType.ROAD_WORKS, d, anchor, jp, hdg, "RW_${way.id}"))
        }
      }

      // Bridge / Tunnel / Footbridge way-level census
      val wayIsBridge = way.tags.bridge != null && way.tags.bridge != "no"
      val wayIsTunnel = way.tags.tunnel != null && way.tags.tunnel != "no"
      if (wayIsBridge || wayIsTunnel) {
        val anchor = way.points.first()
        val d = GeoMath.haversineMeters(center, anchor)
        if (d <= radiusMeters) {
          val fType =
            when {
              wayIsBridge && mode == TravelMode.WALKING && way.tags.highway in WALKING_PATH_HIGHWAYS -> FeatureType.FOOTBRIDGE
              wayIsBridge -> FeatureType.BRIDGE
              else -> FeatureType.TUNNEL
            }
          val (jp, hdg) = computeJumpBefore(way, 0, forwardToFeature = true, anchor = anchor)
          addHit(CensusHit(fType, d, anchor, jp, hdg, "${fType.name}_${way.id}"))
        }
      }

      for (idx in way.points.indices) {
        val pt = way.points[idx]
        val d = GeoMath.haversineMeters(center, pt)
        if (d > radiusMeters) continue
        val nid = way.nodeIds.getOrNull(idx)

        // Determine approaching direction along this way
        val (approachFwd, approachBearing) =
          if (idx > 0) {
            true to GeoMath.bearingDeg(way.points[idx - 1], pt)
          } else {
            false to GeoMath.bearingDeg(way.points[1], pt)
          }

        // Node-level Overpass tags
        if (nid != null) {
          val node = graph.nodes[nid]
          if (node != null) {
            if (mode == TravelMode.WALKING && (node.tags.highway == "crossing" || node.tags.crossing != null)) {
              val (jp, hdg) = computeJumpBefore(way, idx, approachFwd, pt)
              addHit(CensusHit(FeatureType.CROSSWALK, d, pt, jp, hdg, "CW_$nid"))
            }
            if (way.source != GraphDataSource.TILES && nid > 0L) {
              val nodeFeat = classifyOverpassNodeTag(node.tags)
              if (nodeFeat != null) {
                val (jp, hdg) = computeJumpBefore(way, idx, approachFwd, pt)
                addHit(CensusHit(nodeFeat.type, d, pt, jp, hdg, "${nodeFeat.type.name}_$nid"))
              }
            }
          }
        }

        // Sharp curve
        val connected =
          if (nid != null) {
            graph.nodeToWays[nid].orEmpty().mapNotNull { graph.ways[it] }.filter { isWayCountedAtNode(it, way.id, mode) }
          } else {
            emptyList()
          }
        if (connected.size <= 1 && idx in 1 until way.points.lastIndex) {
          val (turnDeg, rad) = GeoMath.turningAngleAndRadius(way.points[idx - 1], pt, way.points[idx + 1])
          if (turnDeg >= AppConfig.SHARP_CURVE_MIN_ANGLE_DEG && rad <= AppConfig.SHARP_CURVE_MAX_RADIUS_M) {
            val (jp, hdg) = computeJumpBefore(way, idx, true, pt)
            addHit(CensusHit(FeatureType.SHARP_CURVE, d, pt, jp, hdg, "SC_${way.id}_${idx / 2}"))
          }
        }

        // Junctions & Endpoints
        if (nid != null) {
          val isEndpoint = idx == 0 || idx == way.points.lastIndex
          val hasOther = connected.any { it.id != way.id }
          if (hasOther || (isEndpoint && connected.size <= 1)) {
            val includeAhead = !isEndpoint
            val edges =
              extractOutgoingEdges(
                graph = graph,
                junctionNodeId = nid,
                incomingWay = way,
                incomingForward = approachFwd,
                incomingBearing = approachBearing,
                includeCurrentAhead = includeAhead,
                currentIdx = idx,
                mode = mode,
              )
            if (edges.isNotEmpty() || isEndpoint) {
              val visitedCyclesLocal = mutableSetOf<Int>()
              val feat =
                classifyJunctionOrEndpoint(
                  graph = graph,
                  roundaboutInfo = roundaboutInfo,
                  visitedRoundaboutCycles = visitedCyclesLocal,
                  featureIndex = 1,
                  distance = d,
                  junctionPoint = pt,
                  junctionNodeId = nid,
                  incomingWay = way,
                  incomingBearing = approachBearing,
                  incomingWorld = sliceWorldPath(way, idx, approachFwd, takeBack = true, maxMeters = 50.0),
                  edges = edges,
                  includeCurrentAhead = includeAhead,
                  mode = mode,
                )
              if (feat != null) {
                val dedup =
                  if (feat.type == FeatureType.ROUNDABOUT && roundaboutInfo.roundaboutNodeToCycle.containsKey(nid)) {
                    "RB_CYCLE_${roundaboutInfo.roundaboutNodeToCycle[nid]}"
                  } else {
                    "${feat.type.name}_$nid"
                  }
                val (jp, hdg) = computeJumpBefore(way, idx, approachFwd, pt)
                addHit(CensusHit(feat.type, d, pt, jp, hdg, dedup))
              }
            }
          }
        }
      }
    }

    // 2) Grade-separated crossings within 1.5 km
    val waysList = graph.ways.values.filter { it.points.size >= 2 && it.tags.highway != null }
    for (i in waysList.indices) {
      val w1 = waysList[i]
      if (!isRelevantForMode(w1, mode)) continue
      if (GeoMath.haversineMeters(center, w1.points[w1.points.size / 2]) > radiusMeters + 400.0) continue
      val w1Nodes = w1.nodeIds.toHashSet()
      val l1 = effectiveLayer(w1.tags)

      for (j in i + 1 until waysList.size) {
        val w2 = waysList[j]
        val l2 = effectiveLayer(w2.tags)
        if (l1 == l2) continue
        val shared = w1Nodes.intersect(w2.nodeIds.toHashSet())
        if (!bboxesOverlap(w1.points, w2.points)) continue

        for (s1 in 0 until w1.points.lastIndex) {
          val a1 = w1.points[s1]
          val a2 = w1.points[s1 + 1]
          for (s2 in 0 until w2.points.lastIndex) {
            val b1 = w2.points[s2]
            val b2 = w2.points[s2 + 1]
            val inter = segmentIntersection(a1, a2, b1, b2) ?: continue
            val (t, _, crossPt) = inter
            val nearShared =
              shared.any { sn ->
                val sPt = graph.nodes[sn]?.point ?: return@any false
                GeoMath.haversineMeters(crossPt, sPt) < 3.0
              }
            if (nearShared) continue
            val dist = GeoMath.haversineMeters(center, crossPt)
            if (dist > radiusMeters) continue
            val fType = if (l2 > l1) FeatureType.OVERPASS_ABOVE else FeatureType.UNDERPASS_BELOW
            val (jp, hdg) = computeJumpBefore(w1, s1 + 1, forwardToFeature = true, anchor = crossPt)
            // Adjust distance roughly to crossPt from s1
            val segBearing = GeoMath.bearingDeg(a1, a2)
            val crossJp =
              if (GeoMath.haversineMeters(jp, crossPt) < 20.0) GeoMath.offsetPoint(crossPt, (segBearing + 180.0) % 360.0, 150.0) to segBearing
              else jp to hdg
            addHit(CensusHit(fType, dist, crossPt, crossJp.first, crossJp.second, "${fType.name}_${w1.id}_${w2.id}"))
            break
          }
        }
      }
    }

    // 3) Composite features census within 1.5 km (Staggered, Divided Road, Median Opening, Frontage Road, Interchanges)
    appendCompositeCensusHits(
      graph = graph,
      center = center,
      mode = mode,
      radiusMeters = radiusMeters,
      hits = hits,
      addHit = { addHit(it) },
      computeJumpBefore = { way, idx, fwd, anchor -> computeJumpBefore(way, idx, fwd, anchor) },
    )

    return hits
      .groupBy { it.type }
      .map { (type, list) ->
        val nearest = list.minBy { it.distFromCenter }
        FeatureCensusEntry(
          type = type,
          count = list.size,
          nearestDistanceMeters = nearest.distFromCenter,
          jumpPoint = nearest.jumpPoint,
          jumpHeadingDeg = nearest.jumpHeadingDeg,
        )
      }
      .sortedBy { it.nearestDistanceMeters }
  }

  private data class RawEdge(
    val way: OsmWay,
    val worldPoints: List<GeoPoint>,
    val bearingDeg: Double,
    val bearingDelta: Double,
    val status: BranchStatus,
    val isContinuationOfCurrentWay: Boolean,
    val touchesAtStart: Boolean,
    val touchesAtEnd: Boolean,
  )

  private data class NextHop(
    val way: OsmWay,
    val nodeIndex: Int,
    val forward: Boolean,
  )

  private fun isRoadWorksWay(way: OsmWay): Boolean {
    val hw = way.tags.highway?.lowercase()
    val acc = way.tags.access?.lowercase()
    return hw == "construction" || acc == "no"
  }

  private fun isRelevantForMode(way: OsmWay, mode: TravelMode): Boolean {
    val hw = way.tags.highway?.lowercase() ?: return false
    return if (mode == TravelMode.DRIVING) {
      hw !in NON_DRIVING_HIGHWAYS
    } else {
      true
    }
  }

  private fun isWayCountedAtNode(way: OsmWay, currentWayId: Long, mode: TravelMode): Boolean {
    if (!isRelevantForMode(way, mode)) return false
    if (mode == TravelMode.DRIVING && way.id != currentWayId) {
      val hw = way.tags.highway?.lowercase()
      val svc = way.tags.service?.lowercase()
      if (hw == "service" && svc in IGNORED_DRIVING_SERVICES) {
        return false
      }
    }
    return true
  }

  private fun classifyOverpassNodeTag(tags: OsmTags): ClassificationOutcome? {
    val b = tags.barrier?.lowercase()
    val hw = tags.highway?.lowercase()
    val mil = tags.military?.lowercase()
    return when {
      b == "toll_booth" -> ClassificationOutcome(FeatureType.TOLL_PLAZA, "osm_toll_booth", 0.98)
      hw == "toll_gantry" -> ClassificationOutcome(FeatureType.TOLL_PLAZA, "osm_toll_gantry", 0.98)
      mil == "checkpoint" -> ClassificationOutcome(FeatureType.CHECKPOINT, "osm_military_checkpoint", 0.98)
      b in CHECKPOINT_BARRIER_TYPES -> ClassificationOutcome(FeatureType.CHECKPOINT, "osm_barrier_$b", 0.97)
      b in BARRIER_BLOCKING_TYPES -> ClassificationOutcome(FeatureType.BARRIER, "osm_barrier_$b", 0.96)
      hw == "mini_roundabout" -> ClassificationOutcome(FeatureType.MINI_ROUNDABOUT, "osm_mini_roundabout", 0.98)
      else -> null
    }
  }

  private fun extractOutgoingEdges(
    graph: RoadGraph,
    junctionNodeId: Long,
    incomingWay: OsmWay,
    incomingForward: Boolean,
    incomingBearing: Double,
    includeCurrentAhead: Boolean,
    currentIdx: Int,
    mode: TravelMode,
  ): List<RawEdge> {
    val result = mutableListOf<RawEdge>()

    // Current way ahead segment (if junction is mid-way)
    if (includeCurrentAhead) {
      val aheadPts = sliceWorldPath(incomingWay, currentIdx, incomingForward, takeBack = false, maxMeters = 65.0)
      if (aheadPts.size >= 2) {
        val brng = GeoMath.bearingDeg(aheadPts[0], aheadPts[1])
        result +=
          RawEdge(
            way = incomingWay,
            worldPoints = aheadPts,
            bearingDeg = brng,
            bearingDelta = GeoMath.signedAngleDelta(incomingBearing, brng),
            status = evaluateBranchStatus(incomingWay, outgoingForward = incomingForward, mode = mode),
            isContinuationOfCurrentWay = true,
            touchesAtStart = currentIdx == 0,
            touchesAtEnd = currentIdx == incomingWay.points.lastIndex,
          )
      }
    }

    val waysAtNode = graph.nodeToWays[junctionNodeId].orEmpty().mapNotNull { graph.ways[it] }
    for (way in waysAtNode) {
      if (way.id == incomingWay.id) continue
      if (!isWayCountedAtNode(way, incomingWay.id, mode)) continue

      way.nodeIds.forEachIndexed { idx, nid ->
        if (nid != junctionNodeId) return@forEachIndexed
        val atStart = idx == 0
        val atEnd = idx == way.points.lastIndex
        if (idx + 1 < way.points.size) {
          val pts = sliceWorldPath(way, idx, forward = true, takeBack = false, maxMeters = 65.0)
          if (pts.size >= 2) {
            val brng = GeoMath.bearingDeg(pts[0], pts[1])
            result +=
              RawEdge(
                way = way,
                worldPoints = pts,
                bearingDeg = brng,
                bearingDelta = GeoMath.signedAngleDelta(incomingBearing, brng),
                status = evaluateBranchStatus(way, outgoingForward = true, mode = mode),
                isContinuationOfCurrentWay = false,
                touchesAtStart = atStart,
                touchesAtEnd = atEnd,
              )
          }
        }
        if (idx - 1 >= 0) {
          val pts = sliceWorldPath(way, idx, forward = false, takeBack = false, maxMeters = 65.0)
          if (pts.size >= 2) {
            val brng = GeoMath.bearingDeg(pts[0], pts[1])
            result +=
              RawEdge(
                way = way,
                worldPoints = pts,
                bearingDeg = brng,
                bearingDelta = GeoMath.signedAngleDelta(incomingBearing, brng),
                status = evaluateBranchStatus(way, outgoingForward = false, mode = mode),
                isContinuationOfCurrentWay = false,
                touchesAtStart = atStart,
                touchesAtEnd = atEnd,
              )
          }
        }
      }
    }

    // Remove near-duplicate directions on the exact same way
    return result.distinctBy { "${it.way.id}_${(it.bearingDeg / 15).toInt()}" }
  }

  fun evaluateBranchStatus(
    way: OsmWay,
    outgoingForward: Boolean,
    mode: TravelMode,
  ): BranchStatus {
    val hw = way.tags.highway?.lowercase()
    if (hw == "construction" || way.tags.access?.lowercase() == "no") {
      return BranchStatus.BLOCKED
    }
    if (hw == "proposed" || hw == "road") {
      return BranchStatus.UNVERIFIED
    }
    if (mode == TravelMode.WALKING && hw in WALKING_PATH_HIGHWAYS) {
      return BranchStatus.OPEN
    }
    when (way.tags.effectiveOneway()) {
      "yes" -> return if (outgoingForward) BranchStatus.OPEN else BranchStatus.BLOCKED
      "-1" -> return if (!outgoingForward) BranchStatus.OPEN else BranchStatus.BLOCKED
      "no" -> return BranchStatus.OPEN
    }
    // Non-motorway *_link without explicit oneway tag -> Unverified
    if (hw in LINK_HIGHWAYS) {
      return BranchStatus.UNVERIFIED
    }
    return BranchStatus.OPEN
  }

  private fun classifyJunctionOrEndpoint(
    graph: RoadGraph,
    roundaboutInfo: RoundaboutCycleInfo,
    visitedRoundaboutCycles: MutableSet<Int>,
    featureIndex: Int,
    distance: Double,
    junctionPoint: GeoPoint,
    junctionNodeId: Long,
    incomingWay: OsmWay,
    incomingBearing: Double,
    incomingWorld: List<GeoPoint>,
    edges: List<RawEdge>,
    includeCurrentAhead: Boolean,
    mode: TravelMode,
  ): RoadFeature? {
    val node = graph.nodes[junctionNodeId]
    val nodeTags = node?.tags

    if (edges.isEmpty()) {
      // 1. Turning circle / turning loop node tag -> CUL_DE_SAC
      if (nodeTags?.highway?.lowercase() in setOf("turning_circle", "turning_loop")) {
        return RoadFeature(
          index = featureIndex,
          type = FeatureType.CUL_DE_SAC,
          distanceMeters = distance,
          anchor = junctionPoint,
          anchorNodeId = junctionNodeId,
          incomingLocalPoints = incomingWorld.map { GeoMath.projectToHeadingFrame(junctionPoint, incomingBearing, it) },
          incomingWorldPoints = incomingWorld,
          outgoingMainLocalPoints = emptyList(),
          outgoingMainWorldPoints = emptyList(),
          branches = emptyList(),
          osmTags = nodeTags ?: incomingWay.tags,
          source = incomingWay.source,
          ruleId = "osm_turning_circle",
          confidence = 0.98,
        )
      }

      // 2. Geometric loop of radius < 25m -> CUL_DE_SAC
      if (junctionNodeId in roundaboutInfo.culDeSacLoopNodes || isTerminalLoopUnder25m(incomingWay.points)) {
        return RoadFeature(
          index = featureIndex,
          type = FeatureType.CUL_DE_SAC,
          distanceMeters = distance,
          anchor = junctionPoint,
          anchorNodeId = junctionNodeId,
          incomingLocalPoints = incomingWorld.map { GeoMath.projectToHeadingFrame(junctionPoint, incomingBearing, it) },
          incomingWorldPoints = incomingWorld,
          outgoingMainLocalPoints = emptyList(),
          outgoingMainWorldPoints = emptyList(),
          branches = emptyList(),
          osmTags = incomingWay.tags,
          source = incomingWay.source,
          ruleId = "geom_cul_de_sac_loop",
          confidence = 0.86,
        )
      }

      // 3. Explicit noexit=yes confirms DEAD_END (confidence 1.0)
      val hasNoExit =
        nodeTags?.noexit?.lowercase() == "yes" || incomingWay.tags.noexit?.lowercase() == "yes"
      if (hasNoExit) {
        return RoadFeature(
          index = featureIndex,
          type = FeatureType.DEAD_END,
          distanceMeters = distance,
          anchor = junctionPoint,
          anchorNodeId = junctionNodeId,
          incomingLocalPoints = incomingWorld.map { GeoMath.projectToHeadingFrame(junctionPoint, incomingBearing, it) },
          incomingWorldPoints = incomingWorld,
          outgoingMainLocalPoints = emptyList(),
          outgoingMainWorldPoints = emptyList(),
          branches = emptyList(),
          osmTags = nodeTags?.takeIf { it.hasAnyTag() } ?: incomingWay.tags,
          source = incomingWay.source,
          ruleId = "osm_noexit",
          confidence = 1.0,
        )
      }

      // 4. Never emit DEAD_END within 3m of a tile border unless neighbouring tile IS loaded
      if (incomingWay.source == GraphDataSource.TILES && isNearUnloadedTileBorder(junctionPoint, graph)) {
        return null
      }

      return RoadFeature(
        index = featureIndex,
        type = FeatureType.DEAD_END,
        distanceMeters = distance,
        anchor = junctionPoint,
        anchorNodeId = junctionNodeId,
        incomingLocalPoints = incomingWorld.map { GeoMath.projectToHeadingFrame(junctionPoint, incomingBearing, it) },
        incomingWorldPoints = incomingWorld,
        outgoingMainLocalPoints = emptyList(),
        outgoingMainWorldPoints = emptyList(),
        branches = emptyList(),
        osmTags = incomingWay.tags,
        source = incomingWay.source,
        ruleId = "geom_dead_end",
        confidence = 0.80,
      )
    }

    // Check if only a trivial continuation exists with no actual branches
    val nonMainEdges = edges.filterNot { it.isContinuationOfCurrentWay }
    if (nonMainEdges.isEmpty() && edges.size == 1) {
      return null
    }
    if (edges.size == 1 && abs(edges[0].bearingDelta) < 25.0) {
      val nextWay = edges[0].way
      if (isRoadWorksWay(nextWay)) {
        val nextBranch =
          RoadBranch(
            badgeId = "${featureIndex}A",
            wayId = nextWay.id,
            name = BilingualName.fromOsmTags(nextWay.tags),
            leadsTo = BilingualName.destinationFromOsmTags(nextWay.tags),
            distanceMeters = distance,
            status = BranchStatus.BLOCKED,
            relativeDirection = RelativeDirection.AHEAD,
            bearingDeltaDeg = edges[0].bearingDelta,
            worldPoints = edges[0].worldPoints,
            localPoints = edges[0].worldPoints.map { GeoMath.projectToHeadingFrame(junctionPoint, incomingBearing, it) },
            osmTags = nextWay.tags,
          )
        return RoadFeature(
          index = featureIndex,
          type = FeatureType.ROAD_WORKS,
          distanceMeters = distance,
          anchor = junctionPoint,
          anchorNodeId = junctionNodeId,
          incomingLocalPoints = incomingWorld.map { GeoMath.projectToHeadingFrame(junctionPoint, incomingBearing, it) },
          incomingWorldPoints = incomingWorld,
          outgoingMainLocalPoints = nextBranch.localPoints,
          outgoingMainWorldPoints = edges[0].worldPoints,
          branches = listOf(nextBranch),
          osmTags = nextWay.tags,
          source = nextWay.source,
          ruleId = if (nextWay.tags.highway?.lowercase() == "construction") "osm_highway_construction" else "osm_access_no",
          confidence = 0.97,
        )
      }
      if (nextWay.tags.highway == incomingWay.tags.highway) {
        // Pure OSM way split (bridge/tunnel/speed limit change) -> let way-transition emit BRIDGE/TUNNEL cleanly
        return null
      }
    }

    val incomingIsLink = incomingWay.tags.highway?.lowercase() in LINK_HIGHWAYS
    val hasOsmRoundabout =
      edges.any { it.way.tags.junction?.lowercase() in setOf("roundabout", "circular") } ||
        incomingWay.tags.junction?.lowercase() in setOf("roundabout", "circular")
    val geomCycleId = roundaboutInfo.roundaboutNodeToCycle[junctionNodeId]
    val hasGeomRoundabout = geomCycleId != null
    val hasCulDeSacLoop = junctionNodeId in roundaboutInfo.culDeSacLoopNodes

    val linkEdges = edges.filter { !it.isContinuationOfCurrentWay && it.way.tags.highway?.lowercase() in LINK_HIGHWAYS }
    val nonLinkEdges = edges.filter { !it.isContinuationOfCurrentWay && it.way.tags.highway?.lowercase() !in LINK_HIGHWAYS }

    val hasService = edges.any { it.way.tags.highway?.lowercase() == "service" }
    val hasFootpath = mode == TravelMode.WALKING && edges.any { it.way.tags.highway?.lowercase() in WALKING_PATH_HIGHWAYS }
    val hasFootbridge =
      mode == TravelMode.WALKING &&
        edges.any {
          it.way.tags.highway?.lowercase() in WALKING_PATH_HIGHWAYS &&
            it.way.tags.bridge != null &&
            it.way.tags.bridge != "no"
        }
    val hasUTurn = edges.any { abs(it.bearingDelta) >= 145.0 }

    val straightEdge = edges.minByOrNull { abs(it.bearingDelta) }
    val hasStraight = straightEdge != null && abs(straightEdge.bearingDelta) <= 22.0

    val outcome =
      classifyFeatureOutcome(
        hasOsmRoundabout = hasOsmRoundabout,
        geomCycleId = geomCycleId,
        visitedRoundaboutCycles = visitedRoundaboutCycles,
        hasCulDeSacLoop = hasCulDeSacLoop,
        nodeTags = nodeTags,
        hasFootbridge = hasFootbridge,
        hasFootpath = hasFootpath,
        incomingIsLink = incomingIsLink,
        includeCurrentAhead = includeCurrentAhead,
        linkEdges = linkEdges,
        nonLinkEdges = nonLinkEdges,
        edges = edges,
        straightEdge = straightEdge,
        hasStraight = hasStraight,
        hasService = hasService,
        hasUTurn = hasUTurn,
      ) ?: return null

    if (outcome.roundaboutCycleId != null) {
      visitedRoundaboutCycles += outcome.roundaboutCycleId
    }

    val sortedEdges = edges.sortedBy { it.bearingDelta }

    val branches =
      sortedEdges.mapIndexed { idx, edge ->
        val relDir =
          when {
            edge.bearingDelta < -18.0 -> RelativeDirection.LEFT
            edge.bearingDelta > 18.0 -> RelativeDirection.RIGHT
            else -> RelativeDirection.AHEAD
          }
        RoadBranch(
          badgeId = "${featureIndex}${'A' + idx}",
          wayId = edge.way.id,
          name = BilingualName.fromOsmTags(edge.way.tags),
          leadsTo = BilingualName.destinationFromOsmTags(edge.way.tags),
          distanceMeters = distance,
          status = edge.status,
          relativeDirection = relDir,
          bearingDeltaDeg = edge.bearingDelta,
          worldPoints = edge.worldPoints,
          localPoints = edge.worldPoints.map { GeoMath.projectToHeadingFrame(junctionPoint, incomingBearing, it) },
          osmTags = edge.way.tags,
        )
      }

    val mainOut = if (hasStraight) straightEdge.worldPoints else emptyList()

    return RoadFeature(
      index = featureIndex,
      type = outcome.type,
      distanceMeters = distance,
      anchor = junctionPoint,
      anchorNodeId = junctionNodeId,
      incomingLocalPoints = incomingWorld.map { GeoMath.projectToHeadingFrame(junctionPoint, incomingBearing, it) },
      incomingWorldPoints = incomingWorld,
      outgoingMainLocalPoints = mainOut.map { GeoMath.projectToHeadingFrame(junctionPoint, incomingBearing, it) },
      outgoingMainWorldPoints = mainOut,
      branches = branches,
      osmTags = incomingWay.tags,
      isFootbridge = hasFootbridge,
      source =
        if (edges.any { it.way.source == GraphDataSource.OVERPASS } || incomingWay.source == GraphDataSource.OVERPASS) {
          GraphDataSource.OVERPASS
        } else {
          incomingWay.source
        },
      ruleId = outcome.ruleId,
      confidence = outcome.confidence,
    )
  }

  private fun classifyFeatureOutcome(
    hasOsmRoundabout: Boolean,
    geomCycleId: Int?,
    visitedRoundaboutCycles: Set<Int>,
    hasCulDeSacLoop: Boolean,
    nodeTags: OsmTags?,
    hasFootbridge: Boolean,
    hasFootpath: Boolean,
    incomingIsLink: Boolean,
    includeCurrentAhead: Boolean,
    linkEdges: List<RawEdge>,
    nonLinkEdges: List<RawEdge>,
    edges: List<RawEdge>,
    straightEdge: RawEdge?,
    hasStraight: Boolean,
    hasService: Boolean,
    hasUTurn: Boolean,
  ): ClassificationOutcome? {
    // Roundabouts
    if (hasOsmRoundabout) {
      if (geomCycleId != null && geomCycleId in visitedRoundaboutCycles) return null
      return ClassificationOutcome(FeatureType.ROUNDABOUT, "osm_junction_roundabout", 1.0, geomCycleId)
    }
    if (geomCycleId != null) {
      if (geomCycleId in visitedRoundaboutCycles) return null
      return ClassificationOutcome(FeatureType.ROUNDABOUT, "geom_tile_roundabout", 0.85, geomCycleId)
    }
    if (nodeTags?.highway?.lowercase() == "mini_roundabout") {
      return ClassificationOutcome(FeatureType.MINI_ROUNDABOUT, "osm_mini_roundabout", 0.98)
    }

    // Cul-de-sac terminal loop
    if (hasCulDeSacLoop && edges.size <= 2) {
      return ClassificationOutcome(FeatureType.CUL_DE_SAC, "geom_cul_de_sac_loop", 0.86)
    }

    // Footbridge / Footpath
    if (hasFootbridge) {
      return ClassificationOutcome(FeatureType.FOOTBRIDGE, "osm_footbridge", 0.95)
    }
    if (hasFootpath && edges.none { it.way.tags.highway !in WALKING_PATH_HIGHWAYS && !it.isContinuationOfCurrentWay }) {
      return ClassificationOutcome(FeatureType.FOOTPATH, "osm_footpath", 0.94)
    }

    // Ramps:
    // 1. User is on a link and it ends at a non-link road ahead => MERGE
    if (incomingIsLink && !includeCurrentAhead && nonLinkEdges.isNotEmpty()) {
      return ClassificationOutcome(FeatureType.MERGE, "ramp_link_merge_into_main", 0.93)
    }

    // 2. Link edge at a junction on the user's path:
    if (linkEdges.isNotEmpty()) {
      val knownLink =
        linkEdges.firstOrNull {
          val eff = it.way.tags.effectiveOneway()
          eff == "yes" || eff == "-1"
        }
      if (knownLink != null) {
        return if (knownLink.status == BranchStatus.OPEN) {
          ClassificationOutcome(FeatureType.OFF_RAMP, "ramp_off", 0.94)
        } else {
          ClassificationOutcome(FeatureType.ON_RAMP, "ramp_on", 0.94)
        }
      }
      // If link direction is unknown -> do NOT default to OFF_RAMP; keep Unverified branch status & classify by geometry
    }

    val confScale = if (linkEdges.isNotEmpty()) 0.72 else 1.0
    val suffix = if (linkEdges.isNotEmpty()) "_unverified_link" else ""

    return when {
      edges.size >= 4 -> ClassificationOutcome(FeatureType.MULTI_WAY, "junc_multi_way$suffix", 0.92 * confScale)
      edges.size == 3 -> ClassificationOutcome(FeatureType.FOUR_WAY, "junc_four_way$suffix", 0.95 * confScale)
      edges.size == 2 && !hasStraight -> {
        val deltas = edges.map { abs(it.bearingDelta) }
        when {
          deltas.all { it in 22.0..70.0 } ->
            ClassificationOutcome(FeatureType.FORK, "geom_fork$suffix", 0.88 * confScale)
          deltas.any { it > 70.0 } ->
            ClassificationOutcome(FeatureType.T_JUNCTION, "geom_t_junction$suffix", 0.90 * confScale)
          else ->
            ClassificationOutcome(FeatureType.Y_JUNCTION, "geom_y_junction$suffix", 0.84 * confScale)
        }
      }
      edges.size == 2 && hasStraight -> {
        val side = edges.firstOrNull { it != straightEdge }
        when {
          side != null && abs(side.bearingDelta) in 18.0..58.0 -> {
            if (side.status == BranchStatus.BLOCKED) {
              ClassificationOutcome(FeatureType.MERGE, "geom_merge$suffix", 0.82 * confScale)
            } else {
              ClassificationOutcome(FeatureType.FORK, "geom_fork$suffix", 0.85 * confScale)
            }
          }
          hasService -> ClassificationOutcome(FeatureType.SERVICE_ROAD, "osm_service_road$suffix", 0.90 * confScale)
          hasUTurn -> ClassificationOutcome(FeatureType.U_TURN, "geom_u_turn$suffix", 0.88 * confScale)
          else -> ClassificationOutcome(FeatureType.T_JUNCTION, "geom_t_junction$suffix", 0.92 * confScale)
        }
      }
      hasUTurn -> ClassificationOutcome(FeatureType.U_TURN, "geom_u_turn$suffix", 0.88 * confScale)
      hasService -> ClassificationOutcome(FeatureType.SERVICE_ROAD, "osm_service_road$suffix", 0.90 * confScale)
      else -> ClassificationOutcome(FeatureType.Y_JUNCTION, "geom_y_fallback$suffix", 0.70 * confScale)
    }
  }

  private fun detectGradeSeparatedCrossings(
    graph: RoadGraph,
    tracedSegments: List<TracedSegment>,
    maxLookahead: Double,
    existingFeatures: MutableList<RoadFeature>,
  ) {
    if (tracedSegments.isEmpty()) return

    val candidateCrossingWays =
      graph.ways.values.filter { w ->
        val hw = w.tags.highway?.lowercase() ?: return@filter false
        hw !in setOf("proposed", "abandoned", "razed") && w.points.size >= 2
      }

    for (seg in tracedSegments) {
      val userLayer = effectiveLayer(seg.way.tags)
      val segNodes = seg.way.nodeIds.toHashSet()
      val sMinLat = minOf(seg.p1.lat, seg.p2.lat) - 0.0005
      val sMaxLat = maxOf(seg.p1.lat, seg.p2.lat) + 0.0005
      val sMinLon = minOf(seg.p1.lon, seg.p2.lon) - 0.0005
      val sMaxLon = maxOf(seg.p1.lon, seg.p2.lon) + 0.0005

      for (other in candidateCrossingWays) {
        if (other.id == seg.way.id) continue
        val otherLayer = effectiveLayer(other.tags)
        if (otherLayer == userLayer) continue

        // Fast bbox check
        var oMinLat = 90.0
        var oMaxLat = -90.0
        var oMinLon = 180.0
        var oMaxLon = -180.0
        for (p in other.points) {
          if (p.lat < oMinLat) oMinLat = p.lat
          if (p.lat > oMaxLat) oMaxLat = p.lat
          if (p.lon < oMinLon) oMinLon = p.lon
          if (p.lon > oMaxLon) oMaxLon = p.lon
        }
        if (oMaxLat < sMinLat || oMinLat > sMaxLat || oMaxLon < sMinLon || oMinLon > sMaxLon) continue

        val sharedNodes = segNodes.intersect(other.nodeIds.toHashSet())

        for (k in 0 until other.points.lastIndex) {
          val b1 = other.points[k]
          val b2 = other.points[k + 1]
          val inter = segmentIntersection(seg.p1, seg.p2, b1, b2) ?: continue
          val (t, _, crossPt) = inter

          // Must NOT cross at a shared node
          val crossesAtSharedNode =
            sharedNodes.any { sn ->
              val sp = graph.nodes[sn]?.point ?: return@any false
              GeoMath.haversineMeters(crossPt, sp) < 3.0
            }
          if (crossesAtSharedNode) continue

          val dist = seg.cumDistAtP1 + t * seg.segLenMeters
          if (dist < 2.0 || dist > maxLookahead) continue

          val isAbove = otherLayer > userLayer
          val fType = if (isAbove) FeatureType.OVERPASS_ABOVE else FeatureType.UNDERPASS_BELOW

          // Deduplicate parallel dual-carriageway decks within 22m
          val dup = existingFeatures.any { it.type == fType && abs(it.distanceMeters - dist) < 22.0 }
          if (dup) continue

          val incomingWorld = sliceWorldPath(seg.way, seg.idx1, seg.forward, takeBack = true, maxMeters = 45.0) + crossPt
          val outgoingWorld = listOf(crossPt) + sliceWorldPath(seg.way, seg.idx1, seg.forward, takeBack = false, maxMeters = 45.0)

          val otherForwardEnd = GeoMath.offsetPoint(crossPt, GeoMath.bearingDeg(b1, b2), 35.0)
          val otherBackEnd = GeoMath.offsetPoint(crossPt, GeoMath.bearingDeg(b2, b1), 35.0)
          val crossLocal =
            listOf(otherBackEnd, crossPt, otherForwardEnd).map {
              GeoMath.projectToHeadingFrame(crossPt, seg.bearingDeg, it)
            }

          existingFeatures +=
            RoadFeature(
              index = existingFeatures.size + 1,
              type = fType,
              distanceMeters = dist,
              anchor = crossPt,
              anchorNodeId = other.id,
              incomingLocalPoints = incomingWorld.map { GeoMath.projectToHeadingFrame(crossPt, seg.bearingDeg, it) },
              incomingWorldPoints = incomingWorld,
              outgoingMainLocalPoints = outgoingWorld.map { GeoMath.projectToHeadingFrame(crossPt, seg.bearingDeg, it) },
              outgoingMainWorldPoints = outgoingWorld,
              branches = emptyList(),
              osmTags = other.tags,
              isBridge = isAbove,
              isTunnel = !isAbove && other.tags.tunnel != null && other.tags.tunnel != "no",
              source = if (other.source == GraphDataSource.OVERPASS) GraphDataSource.OVERPASS else seg.way.source,
              ruleId = if (isAbove) "grade_overpass_above" else "grade_underpass_below",
              confidence = 0.92,
              crossingLocalPoints = crossLocal,
            )
          break
        }
      }
    }
  }

  private fun isNearUnloadedTileBorder(pt: GeoPoint, graph: RoadGraph): Boolean {
    val loaded = graph.loadedTiles
    if (loaded.isEmpty()) return false
    val loadedKeys = loaded.mapTo(HashSet()) { "${it.z}_${it.x}_${it.y}" }
    val thresholdM = 3.2

    for (tile in loaded) {
      // Check if pt is inside or within ~5m of this tile
      val latPad = 0.00005
      val lonPad = 0.00005
      if (pt.lat < tile.south - latPad || pt.lat > tile.north + latPad ||
        pt.lon < tile.west - lonPad || pt.lon > tile.east + lonPad
      ) {
        continue
      }

      val dWest = GeoMath.haversineMeters(pt, GeoPoint(pt.lat, tile.west))
      val dEast = GeoMath.haversineMeters(pt, GeoPoint(pt.lat, tile.east))
      val dNorth = GeoMath.haversineMeters(pt, GeoPoint(tile.north, pt.lon))
      val dSouth = GeoMath.haversineMeters(pt, GeoPoint(tile.south, pt.lon))

      if (dWest <= thresholdM && "${tile.z}_${tile.x - 1}_${tile.y}" !in loadedKeys) return true
      if (dEast <= thresholdM && "${tile.z}_${tile.x + 1}_${tile.y}" !in loadedKeys) return true
      if (dNorth <= thresholdM && "${tile.z}_${tile.x}_${tile.y - 1}" !in loadedKeys) return true
      if (dSouth <= thresholdM && "${tile.z}_${tile.x}_${tile.y + 1}" !in loadedKeys) return true
    }
    return false
  }

  private fun isTerminalLoopUnder25m(points: List<GeoPoint>): Boolean {
    if (points.size < 5) return false
    val endPt = points.last()
    val startPt = points.first()
    // Check tail loop at end or start
    for (tail in listOf(points.takeLast(16), points.take(16))) {
      if (tail.size < 5) continue
      val tip = tail.last()
      val anchorIdx = (0 until tail.size - 3).firstOrNull { GeoMath.haversineMeters(tip, tail[it]) < 14.0 }
      if (anchorIdx != null) {
        val loopPts = tail.subList(anchorIdx, tail.size)
        val cLat = loopPts.map { it.lat }.average()
        val cLon = loopPts.map { it.lon }.average()
        val centroid = GeoPoint(cLat, cLon)
        val meanR = loopPts.map { GeoMath.haversineMeters(centroid, it) }.average()
        if (meanR in 3.0..25.0) return true
      }
    }
    if (GeoMath.haversineMeters(startPt, endPt) < 12.0 && points.size >= 5) {
      val centroid = GeoPoint(points.map { it.lat }.average(), points.map { it.lon }.average())
      val meanR = points.map { GeoMath.haversineMeters(centroid, it) }.average()
      if (meanR in 3.0..25.0) return true
    }
    return false
  }

  private data class TopoEdge(
    val id: Int,
    val u: Long,
    val v: Long,
    val wayId: Long,
    val points: List<GeoPoint>,
    val lengthMeters: Double,
  )

  private data class RoundaboutCycleInfo(
    val roundaboutNodeToCycle: Map<Long, Int>,
    val culDeSacLoopNodes: Set<Long>,
  )

  /**
   * Detects geometric roundabouts (3-12 edges, total length 25-400m, all turning same direction,
   * mean radius 8-60m, 3+ attached external roads) and cul-de-sac loops (radius < 25m, 1 stem road).
   */
  private fun detectGeometricCycles(graph: RoadGraph): RoundaboutCycleInfo {
    val topoEdges = mutableListOf<TopoEdge>()
    val adj = HashMap<Long, MutableList<TopoEdge>>()
    var edgeIdSeq = 0

    for (way in graph.ways.values) {
      val hw = way.tags.highway?.lowercase() ?: continue
      if (hw in NON_DRIVING_HIGHWAYS) continue
      if (way.nodeIds.size < 2 || way.points.size != way.nodeIds.size) continue

      var segStart = 0
      for (i in 1 until way.nodeIds.size) {
        val nid = way.nodeIds[i]
        val isSplit = i == way.nodeIds.lastIndex || (graph.nodeToWays[nid]?.size ?: 0) >= 2
        if (isSplit) {
          val u = way.nodeIds[segStart]
          val v = way.nodeIds[i]
          val subPts = way.points.subList(segStart, i + 1)
          val len = GeoMath.polylineLengthMeters(subPts)
          if (len > 0.5 && u != v) {
            val edge = TopoEdge(edgeIdSeq++, u, v, way.id, subPts, len)
            topoEdges += edge
            adj.getOrPut(u) { mutableListOf() }.add(edge)
            adj.getOrPut(v) { mutableListOf() }.add(edge)
          }
          segStart = i
        }
      }
    }

    if (topoEdges.isEmpty()) return RoundaboutCycleInfo(emptyMap(), emptySet())

    val roundaboutNodeToCycle = HashMap<Long, Int>()
    val culDeSacLoopNodes = HashSet<Long>()
    val seenCycleSignatures = HashSet<String>()
    var cycleSeq = 1

    val sortedNodes = adj.keys.sorted()
    for (startNode in sortedNodes) {
      val startNeighbors = adj[startNode] ?: continue
      if (startNeighbors.size < 2) continue

      val pathNodes = ArrayList<Long>(13)
      val pathEdges = ArrayList<TopoEdge>(12)
      val pathEdgeIds = HashSet<Int>(16)
      val visitedNodes = HashSet<Long>(16)

      pathNodes += startNode
      visitedNodes += startNode

      fun dfs(curr: Long, accumLen: Double) {
        if (pathEdges.size >= 12) return
        val edges = adj[curr] ?: return
        for (edge in edges) {
          if (edge.id in pathEdgeIds) continue
          val next = if (edge.u == curr) edge.v else edge.u
          val nextLen = accumLen + edge.lengthMeters
          if (nextLen > 400.0) continue

          if (next == startNode && pathEdges.size in 2..11) {
            val totalEdges = pathEdges + edge
            if (totalEdges.size in 3..12 && nextLen in 25.0..400.0) {
              val sig = totalEdges.map { it.id }.sorted().joinToString(",")
              if (seenCycleSignatures.add(sig)) {
                val cycleNodes = pathNodes.toList()
                val ringPts = buildCycleRingPoints(cycleNodes, totalEdges)
                val geomOk = evaluateCycleGeometry(ringPts)
                if (geomOk != null) {
                  val cycleEdgeSet = totalEdges.mapTo(HashSet()) { it.id }
                  val externalEdges = HashSet<Int>()
                  for (cn in cycleNodes) {
                    for (inc in adj[cn].orEmpty()) {
                      if (inc.id !in cycleEdgeSet) externalEdges += inc.id
                    }
                  }
                  if (geomOk.meanRadius in 8.0..60.0 && externalEdges.size >= 3) {
                    val cid = cycleSeq++
                    for (cn in cycleNodes) {
                      roundaboutNodeToCycle[cn] = cid
                    }
                  } else if (geomOk.meanRadius < 25.0 && externalEdges.size <= 1) {
                    culDeSacLoopNodes.addAll(cycleNodes)
                  }
                }
              }
            }
          } else if (next > startNode && next !in visitedNodes && pathEdges.size < 11) {
            visitedNodes += next
            pathNodes += next
            pathEdges += edge
            pathEdgeIds += edge.id
            dfs(next, nextLen)
            pathEdgeIds -= edge.id
            pathEdges.removeAt(pathEdges.lastIndex)
            pathNodes.removeAt(pathNodes.lastIndex)
            visitedNodes -= next
          }
        }
      }

      dfs(startNode, 0.0)
    }

    return RoundaboutCycleInfo(roundaboutNodeToCycle, culDeSacLoopNodes)
  }

  private fun buildCycleRingPoints(nodes: List<Long>, edges: List<TopoEdge>): List<GeoPoint> {
    val ring = ArrayList<GeoPoint>(edges.size * 4)
    for (i in edges.indices) {
      val e = edges[i]
      val fromNode = nodes[i]
      val pts = if (e.u == fromNode) e.points else e.points.reversed()
      for (p in pts) {
        if (ring.isEmpty() || GeoMath.haversineMeters(ring.last(), p) > 0.5) {
          ring += p
        }
      }
    }
    if (ring.size >= 2 && GeoMath.haversineMeters(ring.first(), ring.last()) < 0.5) {
      ring.removeAt(ring.lastIndex)
    }
    return ring
  }

  private data class CycleGeomStats(val meanRadius: Double)

  private fun evaluateCycleGeometry(ringPts: List<GeoPoint>): CycleGeomStats? {
    if (ringPts.size < 3) return null
    val turns = ArrayList<Double>(ringPts.size)
    val n = ringPts.size
    for (i in 0 until n) {
      val pPrev = ringPts[(i - 1 + n) % n]
      val pCurr = ringPts[i]
      val pNext = ringPts[(i + 1) % n]
      val b1 = GeoMath.bearingDeg(pPrev, pCurr)
      val b2 = GeoMath.bearingDeg(pCurr, pNext)
      val delta = GeoMath.signedAngleDelta(b1, b2)
      if (abs(delta) >= 2.0) {
        turns += delta
      }
    }
    if (turns.size < 3) return null
    val allPositive = turns.all { it > -4.0 } && turns.any { it > 8.0 }
    val allNegative = turns.all { it < 4.0 } && turns.any { it < -8.0 }
    if (!allPositive && !allNegative) return null

    val cLat = ringPts.map { it.lat }.average()
    val cLon = ringPts.map { it.lon }.average()
    val centroid = GeoPoint(cLat, cLon)
    val radii = ringPts.map { GeoMath.haversineMeters(centroid, it) }
    val meanR = radii.average()
    val minR = radii.minOrNull() ?: 0.0
    if (meanR < 4.0 || minR < meanR * 0.42) return null

    return CycleGeomStats(meanR)
  }

  private fun bboxesOverlap(pts1: List<GeoPoint>, pts2: List<GeoPoint>): Boolean {
    var minLat1 = 90.0
    var maxLat1 = -90.0
    var minLon1 = 180.0
    var maxLon1 = -180.0
    for (p in pts1) {
      if (p.lat < minLat1) minLat1 = p.lat
      if (p.lat > maxLat1) maxLat1 = p.lat
      if (p.lon < minLon1) minLon1 = p.lon
      if (p.lon > maxLon1) maxLon1 = p.lon
    }
    var minLat2 = 90.0
    var maxLat2 = -90.0
    var minLon2 = 180.0
    var maxLon2 = -180.0
    for (p in pts2) {
      if (p.lat < minLat2) minLat2 = p.lat
      if (p.lat > maxLat2) maxLat2 = p.lat
      if (p.lon < minLon2) minLon2 = p.lon
      if (p.lon > maxLon2) maxLon2 = p.lon
    }
    val pad = 0.0002
    return maxLat1 + pad >= minLat2 && minLat1 - pad <= maxLat2 &&
      maxLon1 + pad >= minLon2 && minLon1 - pad <= maxLon2
  }

  /**
   * 2D segment-segment intersection in local meters relative to [a1].
   * Returns (t along a1->a2, u along b1->b2, intersection GeoPoint) or null.
   */
  private fun segmentIntersection(
    a1: GeoPoint,
    a2: GeoPoint,
    b1: GeoPoint,
    b2: GeoPoint,
  ): Triple<Double, Double, GeoPoint>? {
    val refLatRad = Math.toRadians((a1.lat + a2.lat) * 0.5)
    val mLat = 111_132.92
    val mLon = 111_412.84 * cos(refLatRad)

    val ax = 0.0
    val ay = 0.0
    val dx1 = (a2.lon - a1.lon) * mLon
    val dy1 = (a2.lat - a1.lat) * mLat
    val bx = (b1.lon - a1.lon) * mLon
    val by = (b1.lat - a1.lat) * mLat
    val dx2 = (b2.lon - b1.lon) * mLon
    val dy2 = (b2.lat - b1.lat) * mLat

    if (hypot(dx1, dy1) < 0.1 || hypot(dx2, dy2) < 0.1) return null

    val denom = dx1 * dy2 - dy1 * dx2
    if (abs(denom) < 1e-6) return null

    val qpx = bx - ax
    val qpy = by - ay
    val t = (qpx * dy2 - qpy * dx2) / denom
    val u = (qpx * dy1 - qpy * dx1) / denom
    if (t !in 0.0..1.0 || u !in 0.0..1.0) return null

    val interPt =
      GeoPoint(
        lat = a1.lat + (a2.lat - a1.lat) * t,
        lon = a1.lon + (a2.lon - a1.lon) * t,
      )
    return Triple(t, u, interPt)
  }

  private fun findContinuationWay(
    graph: RoadGraph,
    fromNodeId: Long,
    currentWay: OsmWay,
    incomingBearing: Double,
    visitedWayIds: Set<Long>,
    mode: TravelMode,
  ): NextHop? {
    val candidates = mutableListOf<Pair<NextHop, Double>>()
    val wayIds = graph.nodeToWays[fromNodeId].orEmpty()
    for (wId in wayIds) {
      if (wId == currentWay.id || wId in visitedWayIds) continue
      val way = graph.ways[wId] ?: continue
      if (!isRelevantForMode(way, mode) && !isRoadWorksWay(way)) continue
      val idx = way.nodeIds.indexOf(fromNodeId)
      if (idx < 0) continue

      if (idx + 1 < way.points.size) {
        val status = evaluateBranchStatus(way, outgoingForward = true, mode = mode)
        if (status != BranchStatus.BLOCKED || isRoadWorksWay(way)) {
          val brng = GeoMath.bearingDeg(way.points[idx], way.points[idx + 1])
          val angleDiff = GeoMath.absAngleDiff(incomingBearing, brng)
          if (angleDiff < 85.0) {
            candidates += NextHop(way, idx, true) to angleDiff
          }
        }
      }
      if (idx - 1 >= 0) {
        val status = evaluateBranchStatus(way, outgoingForward = false, mode = mode)
        if (status != BranchStatus.BLOCKED || isRoadWorksWay(way)) {
          val brng = GeoMath.bearingDeg(way.points[idx], way.points[idx - 1])
          val angleDiff = GeoMath.absAngleDiff(incomingBearing, brng)
          if (angleDiff < 85.0) {
            candidates += NextHop(way, idx, false) to angleDiff
          }
        }
      }
    }
    return candidates.minByOrNull { it.second }?.first
  }

  private fun sliceWorldPath(
    way: OsmWay,
    fromIdx: Int,
    forward: Boolean,
    takeBack: Boolean,
    maxMeters: Double,
  ): List<GeoPoint> {
    val step =
      when {
        forward && !takeBack -> 1
        forward && takeBack -> -1
        !forward && !takeBack -> -1
        else -> 1
      }
    val pts = mutableListOf<GeoPoint>()
    var i = fromIdx
    var dist = 0.0
    var prev: GeoPoint? = null
    while (i in way.points.indices) {
      val curr = way.points[i]
      if (prev != null) {
        dist += GeoMath.haversineMeters(prev, curr)
      }
      pts += curr
      if (dist >= maxMeters) break
      prev = curr
      i += step
    }
    return if (takeBack) pts.reversed() else pts
  }

  private fun buildPointFeature(
    index: Int,
    type: FeatureType,
    distance: Double,
    anchor: GeoPoint,
    nodeId: Long?,
    headingDeg: Double,
    incomingWorld: List<GeoPoint>,
    outgoingWorld: List<GeoPoint>,
    tags: OsmTags,
    isBridge: Boolean = false,
    isTunnel: Boolean = false,
    isFootbridge: Boolean = false,
    isCrosswalk: Boolean = false,
    source: GraphDataSource = GraphDataSource.OVERPASS,
    ruleId: String = "",
    confidence: Double = 1.0,
  ): RoadFeature {
    return RoadFeature(
      index = index,
      type = type,
      distanceMeters = distance,
      anchor = anchor,
      anchorNodeId = nodeId,
      incomingLocalPoints = incomingWorld.map { GeoMath.projectToHeadingFrame(anchor, headingDeg, it) },
      incomingWorldPoints = incomingWorld,
      outgoingMainLocalPoints = outgoingWorld.map { GeoMath.projectToHeadingFrame(anchor, headingDeg, it) },
      outgoingMainWorldPoints = outgoingWorld,
      branches = emptyList(),
      osmTags = tags,
      isBridge = isBridge,
      isTunnel = isTunnel,
      isFootbridge = isFootbridge,
      isCrosswalk = isCrosswalk,
      source = source,
      ruleId = ruleId,
      confidence = confidence,
    )
  }

  // =====================================================================
  // COMPOSITE ROAD FEATURE DETECTION (Corridor ahead: 800m Driving / 250m Walking)
  // =====================================================================

  private data class OverlapMeasurement(
    val lateralMeters: Double,
    val signedOffsetMeters: Double,
    val overlapMeters: Double,
    val undirectedBearingDiffDeg: Double,
    val oppositeDirectedDiffDeg: Double,
  )

  private data class CorridorPointWithDist(
    val point: GeoPoint,
    val nodeId: Long?,
    val distMeters: Double,
    val bearingDeg: Double,
    val way: OsmWay,
  )

  private fun buildCorridorTimeline(
    matched: RoadCandidate,
    tracedSegments: List<TracedSegment>,
  ): List<CorridorPointWithDist> {
    if (tracedSegments.isEmpty()) return emptyList()
    val list = ArrayList<CorridorPointWithDist>(tracedSegments.size + 1)
    val first = tracedSegments.first()
    list += CorridorPointWithDist(first.p1, first.nodeId1, first.cumDistAtP1, first.bearingDeg, first.way)
    for (seg in tracedSegments) {
      list += CorridorPointWithDist(seg.p2, seg.nodeId2, seg.cumDistAtP1 + seg.segLenMeters, seg.bearingDeg, seg.way)
    }
    return list
  }

  private fun highwayRank(highway: String?): Int =
    when (highway?.lowercase()?.trim()) {
      "motorway" -> 7
      "trunk" -> 6
      "primary" -> 5
      "secondary" -> 4
      "tertiary" -> 3
      "unclassified", "residential", "living_street", "road" -> 2
      "service" -> 1
      else -> 0
    }

  private fun normalizedRoadNames(tags: OsmTags): Set<String> =
    listOfNotNull(tags.name, tags.nameEn, tags.nameAr)
      .map { it.trim().lowercase() }
      .filter { it.isNotEmpty() }
      .toSet()

  private fun sameRoadIdentityForDivided(tagsA: OsmTags, tagsB: OsmTags): Boolean {
    val namesA = normalizedRoadNames(tagsA)
    val namesB = normalizedRoadNames(tagsB)
    val refA = tagsA.ref?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val refB = tagsB.ref?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val hasAnyIdA = namesA.isNotEmpty() || refA != null
    val hasAnyIdB = namesB.isNotEmpty() || refB != null
    if (!hasAnyIdA && !hasAnyIdB) {
      return tagsA.highway?.lowercase() == tagsB.highway?.lowercase()
    }
    if (refA != null && refB != null && refA == refB) return true
    if (namesA.isNotEmpty() && namesB.isNotEmpty() && namesA.intersect(namesB).isNotEmpty()) return true
    return false
  }

  private fun directedWayPoints(way: OsmWay): List<GeoPoint>? {
    val ow = way.tags.effectiveOneway() ?: return null
    return when (ow) {
      "yes" -> way.points
      "-1" -> way.points.reversed()
      else -> null
    }
  }

  private fun corridorBasePoints(matched: RoadCandidate, tracedSegments: List<TracedSegment>): List<GeoPoint> {
    val pts = ArrayList<GeoPoint>(matched.way.points.size + tracedSegments.size)
    // Include matched way points before/around user so overlap on a divided road is measured properly
    if (matched.forwardAlongWay) {
      for (p in matched.way.points) {
        if (pts.isEmpty() || GeoMath.haversineMeters(pts.last(), p) > 0.5) pts += p
      }
    } else {
      for (p in matched.way.points.reversed()) {
        if (pts.isEmpty() || GeoMath.haversineMeters(pts.last(), p) > 0.5) pts += p
      }
    }
    var leftMatchedWay = false
    for (seg in tracedSegments) {
      if (seg.way.id != matched.way.id) leftMatchedWay = true
      if (leftMatchedWay) {
        if (pts.isEmpty() || GeoMath.haversineMeters(pts.last(), seg.p2) > 0.5) {
          pts += seg.p2
        }
      }
    }
    return pts
  }

  private fun cumulativeArcPositions(points: List<GeoPoint>): DoubleArray {
    val arc = DoubleArray(points.size)
    for (i in 1 until points.size) {
      arc[i] = arc[i - 1] + GeoMath.haversineMeters(points[i - 1], points[i])
    }
    return arc
  }

  private fun measureParallelOverlap(
    refPoints: List<GeoPoint>,
    candPoints: List<GeoPoint>,
    maxLateralMeters: Double,
  ): OverlapMeasurement? {
    if (refPoints.size < 2 || candPoints.size < 2) return null
    val refArc = cumulativeArcPositions(refPoints)
    val totalRefLen = refArc.last()
    if (totalRefLen < 5.0) return null

    var minArc = Double.MAX_VALUE
    var maxArc = -Double.MAX_VALUE
    var lateralSum = 0.0
    var signedSum = 0.0
    var validCount = 0
    var undirDiffSum = 0.0
    var oppDiffSum = 0.0

    for (i in candPoints.indices) {
      val pt = candPoints[i]
      val snap = GeoMath.snapToPolyline(pt, refPoints)
      val segIdx = snap.segmentIndex.coerceIn(0, refPoints.lastIndex - 1)
      val segLen = GeoMath.haversineMeters(refPoints[segIdx], refPoints[segIdx + 1]).coerceAtLeast(0.01)
      val distFromA = GeoMath.haversineMeters(refPoints[segIdx], snap.snappedPoint)
      val arcPos = refArc[segIdx] + distFromA.coerceAtMost(segLen)

      val refB = snap.segmentBearingDeg
      val candB =
        if (i < candPoints.lastIndex) GeoMath.bearingDeg(candPoints[i], candPoints[i + 1])
        else GeoMath.bearingDeg(candPoints[i - 1], candPoints[i])

      val diffSame = GeoMath.absAngleDiff(refB, candB)
      val diffOpp = GeoMath.absAngleDiff(refB, (candB + 180.0) % 360.0)
      val undirDiff = min(diffSame, diffOpp)

      if (snap.distanceMeters <= maxLateralMeters * 1.4) {
        if (arcPos < minArc) minArc = arcPos
        if (arcPos > maxArc) maxArc = arcPos
        lateralSum += snap.distanceMeters
        val local = GeoMath.projectToHeadingFrame(snap.snappedPoint, refB, pt)
        signedSum += local.xMeters
        undirDiffSum += undirDiff
        oppDiffSum += diffOpp
        validCount++
      }
    }

    // Also project refPoints onto candPoints so a longer/2-vertex cand way covers intermediate ref points
    for (j in refPoints.indices) {
      val rp = refPoints[j]
      val snap = GeoMath.snapToPolyline(rp, candPoints)
      val refB =
        if (j < refPoints.lastIndex) GeoMath.bearingDeg(refPoints[j], refPoints[j + 1])
        else GeoMath.bearingDeg(refPoints[j - 1], refPoints[j])
      val candSegIdx = snap.segmentIndex.coerceIn(0, candPoints.lastIndex - 1)
      val candB = GeoMath.bearingDeg(candPoints[candSegIdx], candPoints[candSegIdx + 1])
      val diffSame = GeoMath.absAngleDiff(refB, candB)
      val diffOpp = GeoMath.absAngleDiff(refB, (candB + 180.0) % 360.0)
      val undirDiff = min(diffSame, diffOpp)

      if (snap.distanceMeters <= maxLateralMeters * 1.4) {
        val arcPos = refArc[j]
        if (arcPos < minArc) minArc = arcPos
        if (arcPos > maxArc) maxArc = arcPos
        lateralSum += snap.distanceMeters
        val local = GeoMath.projectToHeadingFrame(rp, refB, snap.snappedPoint)
        signedSum += local.xMeters
        undirDiffSum += undirDiff
        oppDiffSum += diffOpp
        validCount++
      }
    }

    if (validCount == 0) return null
    val overlap = (maxArc - minArc).coerceAtLeast(0.0)
    return OverlapMeasurement(
      lateralMeters = lateralSum / validCount,
      signedOffsetMeters = signedSum / validCount,
      overlapMeters = overlap,
      undirectedBearingDiffDeg = undirDiffSum / validCount,
      oppositeDirectedDiffDeg = oppDiffSum / validCount,
    )
  }

  private fun detectDividedRoadPartner(
    graph: RoadGraph,
    matched: RoadCandidate,
    tracedSegments: List<TracedSegment>,
    mode: TravelMode,
  ): DividedRoadInfo? {
    val userWay = matched.way
    if (userWay.tags.effectiveOneway() !in setOf("yes", "-1")) return null
    val refPts = corridorBasePoints(matched, tracedSegments)
    if (refPts.size < 2) return null

    // Group opposite-oneway ways sharing same identity to handle split ways cleanly
    val candidatePartners =
      graph.ways.values.filter { w ->
        w.id != userWay.id &&
          w.points.size >= 2 &&
          isRelevantForMode(w, mode) &&
          w.tags.highway?.lowercase() !in LINK_HIGHWAYS &&
          sameRoadIdentityForDivided(userWay.tags, w.tags) &&
          w.tags.effectiveOneway() in setOf("yes", "-1")
      }
    if (candidatePartners.isEmpty()) return null

    var bestInfo: DividedRoadInfo? = null
    for (partner in candidatePartners) {
      val directedPts = directedWayPoints(partner) ?: continue
      val meas = measureParallelOverlap(refPts, directedPts, maxLateralMeters = 60.0) ?: continue
      if (meas.lateralMeters !in 3.0..60.0) continue
      if (meas.oppositeDirectedDiffDeg >= 20.0) continue
      // Check individual overlap OR aggregated overlap across connected same-identity partner ways
      var effectiveOverlap = meas.overlapMeters
      if (effectiveOverlap <= 100.0) {
        val partnerChain = collectConnectedSameIdentityOneway(graph, partner, mode)
        if (partnerChain.size > 1) {
          val chainPts = partnerChain.flatMap { directedWayPoints(it) ?: emptyList() }
          val chainMeas = measureParallelOverlap(refPts, chainPts, maxLateralMeters = 60.0)
          if (chainMeas != null && chainMeas.lateralMeters in 3.0..60.0 && chainMeas.oppositeDirectedDiffDeg < 20.0) {
            effectiveOverlap = chainMeas.overlapMeters
          }
        }
      }
      if (effectiveOverlap <= 100.0) continue

      if (bestInfo == null || meas.lateralMeters < bestInfo.separationMeters) {
        bestInfo =
          DividedRoadInfo(
            isDivided = true,
            separationMeters = meas.lateralMeters,
            partnerSideOffsetMeters = meas.signedOffsetMeters,
            partnerWayId = partner.id,
            ruleId = "composite_divided_road",
            confidence = 0.90,
          )
      }
    }
    return bestInfo
  }

  private fun collectConnectedSameIdentityOneway(
    graph: RoadGraph,
    seed: OsmWay,
    mode: TravelMode,
  ): List<OsmWay> {
    val result = mutableListOf(seed)
    val visited = hashSetOf(seed.id)
    val queue = ArrayDeque<OsmWay>()
    queue.addLast(seed)
    while (queue.isNotEmpty() && result.size < 10) {
      val curr = queue.removeFirst()
      for (endNid in listOfNotNull(curr.nodeIds.firstOrNull(), curr.nodeIds.lastOrNull())) {
        for (wId in graph.nodeToWays[endNid].orEmpty()) {
          if (wId in visited) continue
          val next = graph.ways[wId] ?: continue
          if (!isRelevantForMode(next, mode)) continue
          if (next.tags.effectiveOneway() !in setOf("yes", "-1")) continue
          if (!sameRoadIdentityForDivided(seed.tags, next.tags)) continue
          visited += wId
          result += next
          queue.addLast(next)
        }
      }
    }
    return result
  }

  private fun applyCompositeGrouping(
    graph: RoadGraph,
    matched: RoadCandidate,
    tracedSegments: List<TracedSegment>,
    rawFeatures: List<RoadFeature>,
    dividedRoad: DividedRoadInfo?,
    compositeCorridorMeters: Double,
    maxStandaloneLookahead: Double,
    mode: TravelMode,
  ): List<RoadFeature> {
    val working = rawFeatures.toMutableList()
    val corridorTimeline = buildCorridorTimeline(matched, tracedSegments)
    val corridorNodeDist = HashMap<Long, CorridorPointWithDist>()
    for (cp in corridorTimeline) {
      val nid = cp.nodeId ?: continue
      if (cp.distMeters <= compositeCorridorMeters && !corridorNodeDist.containsKey(nid)) {
        corridorNodeDist[nid] = cp
      }
    }
    val corridorWayIds = tracedSegments.mapTo(HashSet()) { it.way.id }.apply { add(matched.way.id) }
    val refPts = corridorBasePoints(matched, tracedSegments)
    val replacedIndices = HashSet<Int>()

    // 1) MEDIAN_OPENING on divided roads ahead (connector <= 80m joining both carriageways on user's side)
    val addedComposites = mutableListOf<RoadFeature>()
    if (dividedRoad != null) {
      val partnerSeed = graph.ways[dividedRoad.partnerWayId]
      val partnerWays =
        if (partnerSeed != null) {
          collectConnectedSameIdentityOneway(graph, partnerSeed, mode)
        } else {
          emptyList()
        }
      val partnerNodeSet = partnerWays.flatMapTo(HashSet()) { it.nodeIds }
      val partnerWayIds = partnerWays.mapTo(HashSet()) { it.id }

      for (conn in graph.ways.values) {
        if (conn.id in corridorWayIds || conn.id in partnerWayIds) continue
        if (!isRelevantForMode(conn, mode)) continue
        if (conn.points.size < 2) continue
        val connLen = GeoMath.polylineLengthMeters(conn.points)
        if (connLen > 80.0) continue

        val sharedUserNids = conn.nodeIds.filter { it in corridorNodeDist }
        val sharedPartnerNids = conn.nodeIds.filter { it in partnerNodeSet }
        if (sharedUserNids.isEmpty() || sharedPartnerNids.isEmpty()) continue

        // Pick user corridor node ahead of user
        val userEntry =
          sharedUserNids
            .mapNotNull { corridorNodeDist[it] }
            .filter { it.distMeters in 1.0..compositeCorridorMeters }
            .minByOrNull { it.distMeters }
            ?: continue

        val uIdx = conn.nodeIds.indexOf(userEntry.nodeId!!)
        val pNid = sharedPartnerNids.firstOrNull { it != userEntry.nodeId } ?: continue
        val pIdx = conn.nodeIds.indexOf(pNid)
        if (uIdx < 0 || pIdx < 0 || uIdx == pIdx) continue
        val forwardToPartner = pIdx > uIdx
        val status = evaluateBranchStatus(conn, outgoingForward = forwardToPartner, mode = mode)
        if (status == BranchStatus.BLOCKED) continue

        val connWorld = sliceWorldPath(conn, uIdx, forwardToPartner, takeBack = false, maxMeters = 80.0)
        if (connWorld.size < 2) continue
        val brng = GeoMath.bearingDeg(connWorld[0], connWorld[1])
        val signedDelta = GeoMath.signedAngleDelta(userEntry.bearingDeg, brng)
        val relDir =
          when {
            signedDelta < -15.0 -> RelativeDirection.LEFT
            signedDelta > 15.0 -> RelativeDirection.RIGHT
            else -> RelativeDirection.AHEAD
          }

        val incomingWorld =
          sliceWorldPath(
            userEntry.way,
            userEntry.way.nodeIds.indexOf(userEntry.nodeId).coerceAtLeast(0),
            matched.forwardAlongWay,
            takeBack = true,
            maxMeters = 55.0,
          )
        val outgoingWorld =
          sliceWorldPath(
            userEntry.way,
            userEntry.way.nodeIds.indexOf(userEntry.nodeId).coerceAtLeast(0),
            matched.forwardAlongWay,
            takeBack = false,
            maxMeters = 55.0,
          )
        val branch =
          RoadBranch(
            badgeId = "1A",
            wayId = conn.id,
            name = BilingualName.fromOsmTags(conn.tags),
            leadsTo = BilingualName.destinationFromOsmTags(conn.tags),
            distanceMeters = userEntry.distMeters,
            status = status,
            relativeDirection = relDir,
            bearingDeltaDeg = signedDelta,
            worldPoints = connWorld,
            localPoints = connWorld.map { GeoMath.projectToHeadingFrame(userEntry.point, userEntry.bearingDeg, it) },
            osmTags = conn.tags,
          )

        addedComposites +=
          RoadFeature(
            index = 1,
            type = FeatureType.MEDIAN_OPENING,
            distanceMeters = userEntry.distMeters,
            anchor = userEntry.point,
            anchorNodeId = userEntry.nodeId,
            incomingLocalPoints = incomingWorld.map { GeoMath.projectToHeadingFrame(userEntry.point, userEntry.bearingDeg, it) },
            incomingWorldPoints = incomingWorld,
            outgoingMainLocalPoints = outgoingWorld.map { GeoMath.projectToHeadingFrame(userEntry.point, userEntry.bearingDeg, it) },
            outgoingMainWorldPoints = outgoingWorld,
            branches = listOf(branch),
            osmTags = conn.tags,
            source = conn.source,
            ruleId = "composite_median_opening",
            confidence = 0.90,
            dualCarriagewayOffsetMeters = dividedRoad.partnerSideOffsetMeters,
          )

        // Replace overlapping U_TURN / T_JUNCTION / SERVICE_ROAD at this node
        working.forEachIndexed { idx, feat ->
          if (feat.anchorNodeId == userEntry.nodeId &&
            feat.type in setOf(FeatureType.U_TURN, FeatureType.T_JUNCTION, FeatureType.SERVICE_ROAD)
          ) {
            replacedIndices += idx
          }
        }
      }
    }

    // 2) FRONTAGE_ROAD: parallel service or lower-class way (<= 40m, < 15deg, overlap > 80m) with connector ahead
    val userMaxRank = highwayRank(matched.way.tags.highway)
    val frontageCandidates =
      graph.ways.values.filter { w ->
        if (w.id in corridorWayIds || w.points.size < 2) return@filter false
        if (!isRelevantForMode(w, mode)) return@filter false
        val hw = w.tags.highway?.lowercase() ?: return@filter false
        if (hw in LINK_HIGHWAYS) return@filter false
        val isFrontageClass =
          if (hw == "service") {
            !(mode == TravelMode.DRIVING && w.tags.service?.lowercase() in IGNORED_DRIVING_SERVICES)
          } else {
            val r = highwayRank(hw)
            r in 1 until userMaxRank
          }
        isFrontageClass
      }

    val seenFrontageNodes = HashSet<Long>()
    for (par in frontageCandidates) {
      val meas = measureParallelOverlap(refPts, par.points, maxLateralMeters = 40.0) ?: continue
      if (meas.lateralMeters !in 2.0..40.0) continue
      if (meas.undirectedBearingDiffDeg >= 15.0) continue
      if (meas.overlapMeters <= 80.0) continue

      val parNodes = par.nodeIds.toHashSet()
      // Find connector ahead: either direct shared node or short connector way <= 80m linking corridor node to parNode
      var bestConnector: Triple<CorridorPointWithDist, OsmWay, List<GeoPoint>>? = null

      val directNode =
        par.nodeIds
          .mapNotNull { corridorNodeDist[it] }
          .filter { it.distMeters in 1.0..compositeCorridorMeters }
          .minByOrNull { it.distMeters }
      if (directNode != null) {
        val pIdx = par.nodeIds.indexOf(directNode.nodeId!!)
        val slice =
          if (pIdx + 1 < par.points.size) sliceWorldPath(par, pIdx, true, takeBack = false, maxMeters = 55.0)
          else sliceWorldPath(par, pIdx, false, takeBack = false, maxMeters = 55.0)
        bestConnector = Triple(directNode, par, slice)
      } else {
        for (conn in graph.ways.values) {
          if (conn.id == par.id || conn.id in corridorWayIds || conn.points.size < 2) continue
          if (!isRelevantForMode(conn, mode)) continue
          val connLen = GeoMath.polylineLengthMeters(conn.points)
          if (connLen > 80.0) continue
          val userEntry =
            conn.nodeIds
              .mapNotNull { corridorNodeDist[it] }
              .filter { it.distMeters in 1.0..compositeCorridorMeters }
              .minByOrNull { it.distMeters }
              ?: continue
          val touchesPar = conn.nodeIds.any { it in parNodes && it != userEntry.nodeId }
          if (!touchesPar) continue
          val uIdx = conn.nodeIds.indexOf(userEntry.nodeId!!)
          val pIdx = conn.nodeIds.indexOfFirst { it in parNodes && it != userEntry.nodeId }
          val fwd = pIdx > uIdx
          val slice = sliceWorldPath(conn, uIdx, fwd, takeBack = false, maxMeters = 60.0)
          if (bestConnector == null || userEntry.distMeters < bestConnector.first.distMeters) {
            bestConnector = Triple(userEntry, conn, slice)
          }
        }
      }

      if (bestConnector != null) {
        val (uEntry, connWay, connWorld) = bestConnector
        val uNid = uEntry.nodeId ?: continue
        if (!seenFrontageNodes.add(uNid)) continue
        if (addedComposites.any { it.type == FeatureType.MEDIAN_OPENING && it.anchorNodeId == uNid }) continue

        val side =
          if (meas.signedOffsetMeters < 0.0) RelativeDirection.LEFT else RelativeDirection.RIGHT
        val uIdx = uEntry.way.nodeIds.indexOf(uNid).coerceAtLeast(0)
        val incomingWorld = sliceWorldPath(uEntry.way, uIdx, matched.forwardAlongWay, takeBack = true, maxMeters = 55.0)
        val outgoingWorld = sliceWorldPath(uEntry.way, uIdx, matched.forwardAlongWay, takeBack = false, maxMeters = 55.0)

        val connFwd =
          if (connWorld.size >= 2) {
            val brng = GeoMath.bearingDeg(connWorld[0], connWorld[1])
            val signedDelta = GeoMath.signedAngleDelta(uEntry.bearingDeg, brng)
            val status =
              if (connWay.id == par.id) {
                val pIdx = par.nodeIds.indexOf(uNid)
                evaluateBranchStatus(par, pIdx + 1 < par.points.size, mode)
              } else {
                val cuIdx = connWay.nodeIds.indexOf(uNid)
                val cpIdx = connWay.nodeIds.indexOfFirst { it in parNodes && it != uNid }
                evaluateBranchStatus(connWay, cpIdx > cuIdx, mode)
              }
            RoadBranch(
              badgeId = "1A",
              wayId = par.id,
              name = BilingualName.fromOsmTags(par.tags), // Tagged only, never invented
              leadsTo = BilingualName.destinationFromOsmTags(par.tags),
              distanceMeters = uEntry.distMeters,
              status = status,
              relativeDirection = side,
              bearingDeltaDeg = signedDelta,
              worldPoints = connWorld,
              localPoints = connWorld.map { GeoMath.projectToHeadingFrame(uEntry.point, uEntry.bearingDeg, it) },
              osmTags = par.tags,
            )
          } else {
            null
          }

        addedComposites +=
          RoadFeature(
            index = 1,
            type = FeatureType.FRONTAGE_ROAD,
            distanceMeters = uEntry.distMeters,
            anchor = uEntry.point,
            anchorNodeId = uNid,
            incomingLocalPoints = incomingWorld.map { GeoMath.projectToHeadingFrame(uEntry.point, uEntry.bearingDeg, it) },
            incomingWorldPoints = incomingWorld,
            outgoingMainLocalPoints = outgoingWorld.map { GeoMath.projectToHeadingFrame(uEntry.point, uEntry.bearingDeg, it) },
            outgoingMainWorldPoints = outgoingWorld,
            branches = listOfNotNull(connFwd),
            osmTags = par.tags,
            source = par.source,
            ruleId = "composite_frontage_road",
            confidence = 0.88,
            dualCarriagewayOffsetMeters = meas.signedOffsetMeters,
            frontageSide = side,
          )

        working.forEachIndexed { idx, feat ->
          if (feat.anchorNodeId == uNid &&
            feat.type in setOf(FeatureType.SERVICE_ROAD, FeatureType.T_JUNCTION)
          ) {
            replacedIndices += idx
          }
        }
      }
    }

    // 3) STAGGERED_JUNCTION: two T-junctions within 80m (Driving) / 40m (Walking) on opposite sides, minor bearings <= 20deg collinear
    val maxStaggerMeters = if (mode == TravelMode.DRIVING) 80.0 else 40.0
    val tCandidates =
      working.mapIndexedNotNull { idx, feat ->
        if (idx in replacedIndices || feat.type != FeatureType.T_JUNCTION || feat.distanceMeters > compositeCorridorMeters) {
          null
        } else {
          idx to feat
        }
      }.sortedBy { it.second.distanceMeters }

    val usedStaggerIdx = HashSet<Int>()
    for (i in 0 until tCandidates.lastIndex) {
      val (idx1, f1) = tCandidates[i]
      if (idx1 in usedStaggerIdx) continue
      val b1 = findMinorSideBranch(f1) ?: continue

      for (j in i + 1 until tCandidates.size) {
        val (idx2, f2) = tCandidates[j]
        if (idx2 in usedStaggerIdx) continue
        val offset = f2.distanceMeters - f1.distanceMeters
        if (offset > maxStaggerMeters) break
        if (offset < 1.0) continue

        val b2 = findMinorSideBranch(f2) ?: continue
        val oppositeSides =
          (b1.relativeDirection == RelativeDirection.LEFT && b2.relativeDirection == RelativeDirection.RIGHT) ||
            (b1.relativeDirection == RelativeDirection.RIGHT && b2.relativeDirection == RelativeDirection.LEFT)
        if (!oppositeSides) continue

        if (b1.worldPoints.size < 2 || b2.worldPoints.size < 2) continue
        val bearing1 = GeoMath.bearingDeg(b1.worldPoints[0], b1.worldPoints[1])
        val bearing2 = GeoMath.bearingDeg(b2.worldPoints[0], b2.worldPoints[1])
        val collinearDiff = GeoMath.absAngleDiff(bearing1, (bearing2 + 180.0) % 360.0)
        if (collinearDiff > 20.0) continue

        // Merge into ONE STAGGERED_JUNCTION with 1A left, 1B right + offset distance
        usedStaggerIdx += idx1
        usedStaggerIdx += idx2
        replacedIndices += idx1
        replacedIndices += idx2

        val anchorHeading =
          if (f1.incomingWorldPoints.size >= 2) {
            GeoMath.bearingDeg(f1.incomingWorldPoints[f1.incomingWorldPoints.size - 2], f1.anchor)
          } else {
            GeoMath.bearingDeg(matched.snappedPoint, f1.anchor)
          }
        val combinedOutgoingWorld =
          if (f2.outgoingMainWorldPoints.isNotEmpty()) {
            listOf(f1.anchor, f2.anchor) + f2.outgoingMainWorldPoints
          } else {
            listOf(f1.anchor, f2.anchor, GeoMath.offsetPoint(f2.anchor, anchorHeading, 45.0))
          }
        val leftBranch = if (b1.relativeDirection == RelativeDirection.LEFT) b1 else b2
        val rightBranch = if (b1.relativeDirection == RelativeDirection.RIGHT) b1 else b2
        val reprojectedLeft =
          leftBranch.copy(
            badgeId = "1A",
            localPoints = leftBranch.worldPoints.map { GeoMath.projectToHeadingFrame(f1.anchor, anchorHeading, it) },
          )
        val reprojectedRight =
          rightBranch.copy(
            badgeId = "1B",
            localPoints = rightBranch.worldPoints.map { GeoMath.projectToHeadingFrame(f1.anchor, anchorHeading, it) },
          )

        addedComposites +=
          RoadFeature(
            index = 1,
            type = FeatureType.STAGGERED_JUNCTION,
            distanceMeters = f1.distanceMeters,
            anchor = f1.anchor,
            anchorNodeId = f1.anchorNodeId,
            incomingLocalPoints = f1.incomingWorldPoints.map { GeoMath.projectToHeadingFrame(f1.anchor, anchorHeading, it) },
            incomingWorldPoints = f1.incomingWorldPoints,
            outgoingMainLocalPoints = combinedOutgoingWorld.map { GeoMath.projectToHeadingFrame(f1.anchor, anchorHeading, it) },
            outgoingMainWorldPoints = combinedOutgoingWorld,
            branches = listOf(reprojectedLeft, reprojectedRight),
            osmTags = f1.osmTags,
            source = if (f1.source == GraphDataSource.OVERPASS || f2.source == GraphDataSource.OVERPASS) GraphDataSource.OVERPASS else f1.source,
            ruleId = "composite_staggered_junction",
            confidence = 0.90,
            staggerOffsetMeters = offset,
          )
        break
      }
    }

    // 4) INTERCHANGE grouping (Driving only, within 500m span in 800m corridor)
    if (mode == TravelMode.DRIVING) {
      val remainingAfterEarly =
        working.filterIndexed { idx, _ -> idx !in replacedIndices }
      val interchangeResults =
        groupInterchangesInCorridor(
          graph = graph,
          matched = matched,
          corridorTimeline = corridorTimeline,
          corridorWayIds = corridorWayIds,
          rawFeatures = remainingAfterEarly,
          compositeCorridorMeters = compositeCorridorMeters,
        )
      if (interchangeResults.isNotEmpty()) {
        val hiddenIds = interchangeResults.flatMapTo(HashSet()) { res -> res.second }
        val keptBase =
          remainingAfterEarly
            .filterNot { it.index in hiddenIds }
            .filter { it.distanceMeters <= maxStandaloneLookahead }
        return (keptBase + addedComposites.filter { it.distanceMeters <= compositeCorridorMeters } + interchangeResults.map { it.first })
      }
    }

    val finalBase =
      working
        .filterIndexed { idx, feat -> idx !in replacedIndices && feat.distanceMeters <= maxStandaloneLookahead }
    return finalBase + addedComposites.filter { it.distanceMeters <= compositeCorridorMeters }
  }

  private fun findMinorSideBranch(feature: RoadFeature): RoadBranch? {
    val sideBranches =
      feature.branches.filter { br ->
        (br.relativeDirection == RelativeDirection.LEFT || br.relativeDirection == RelativeDirection.RIGHT) &&
          abs(br.bearingDeltaDeg) > 35.0 &&
          br.osmTags.highway?.lowercase() !in LINK_HIGHWAYS
      }
    return if (sideBranches.size == 1) sideBranches.first() else null
  }

  private data class LogicalRamp(
    val primaryWay: OsmWay,
    val ways: List<OsmWay>,
    val points: List<GeoPoint>,
    val cumTurnDeg: Double,
    val radiusMeters: Double,
    val isLoop: Boolean,
    val isFlyover: Boolean,
    val corridorDistMeters: Double,
    val anchorOnCorridor: GeoPoint?,
    val entryBearingDeg: Double,
    val worldSliceFromCorridor: List<GeoPoint>,
    val branchStatus: BranchStatus,
  )

  private fun groupInterchangesInCorridor(
    graph: RoadGraph,
    matched: RoadCandidate,
    corridorTimeline: List<CorridorPointWithDist>,
    corridorWayIds: Set<Long>,
    rawFeatures: List<RoadFeature>,
    compositeCorridorMeters: Double,
  ): List<Pair<RoadFeature, Set<Int>>> {
    // Identify grade separations in the 800m corridor
    val gradeSepFeatures =
      rawFeatures.filter {
        it.distanceMeters <= compositeCorridorMeters &&
          it.type in setOf(FeatureType.BRIDGE, FeatureType.TUNNEL, FeatureType.OVERPASS_ABOVE, FeatureType.UNDERPASS_BELOW)
      }
    val gradeSepSegments =
      corridorTimeline.filter {
        it.distMeters <= compositeCorridorMeters &&
          (effectiveLayer(it.way.tags) != 0 ||
            (it.way.tags.bridge != null && it.way.tags.bridge != "no") ||
            (it.way.tags.tunnel != null && it.way.tags.tunnel != "no"))
      }

    if (gradeSepFeatures.isEmpty() && gradeSepSegments.isEmpty()) return emptyList()

    // Grade separation anchors sorted along corridor
    val gradeAnchors =
      buildList {
        gradeSepFeatures.forEach { add(it.distanceMeters to it.anchor) }
        if (gradeSepFeatures.isEmpty()) {
          gradeSepSegments.firstOrNull()?.let { add(it.distMeters to it.point) }
        }
      }.sortedBy { it.first }

    if (gradeAnchors.isEmpty()) return emptyList()

    // Cluster grade anchors within 350m
    val seedClusters = mutableListOf<MutableList<Pair<Double, GeoPoint>>>()
    for (ga in gradeAnchors) {
      val lastCluster = seedClusters.lastOrNull()
      if (lastCluster != null && ga.first - lastCluster.first().first <= 350.0) {
        lastCluster += ga
      } else {
        seedClusters += mutableListOf(ga)
      }
    }

    val linkWays = graph.ways.values.filter { it.tags.highway?.lowercase() in LINK_HIGHWAYS && it.points.size >= 2 }
    if (linkWays.size < 2) return emptyList()

    val results = mutableListOf<Pair<RoadFeature, Set<Int>>>()
    val consumedFeatureIndices = HashSet<Int>()
    val consumedLinkIds = HashSet<Long>()

    for (clusterSeeds in seedClusters) {
      val centerDist = clusterSeeds.map { it.first }.average()
      val centerPt = clusterSeeds[clusterSeeds.size / 2].second
      val minSpanAllowed = (centerDist - 250.0).coerceAtLeast(0.0)
      val maxSpanAllowed = (centerDist + 250.0).coerceAtMost(compositeCorridorMeters)

      // Gather link ways within this 500m corridor span (or within 300m of grade separation center)
      val clusterLinkWays =
        linkWays.filter { lw ->
          if (lw.id in consumedLinkIds) return@filter false
          val dToCenter = lw.points.minOf { GeoMath.haversineMeters(centerPt, it) }
          if (dToCenter > 320.0) return@filter false
          val touchedDist =
            lw.nodeIds.mapNotNull { nid ->
              corridorTimeline.firstOrNull { it.nodeId == nid }?.distMeters
            }.minOrNull()
          if (touchedDist != null) {
            touchedDist in minSpanAllowed..maxSpanAllowed
          } else {
            // Link starts/ends on the crossing road or chains into a corridor link
            dToCenter <= 260.0
          }
        }

      if (clusterLinkWays.size < 2) continue

      // Build logical ramps (chaining consecutive link ways sharing a link-only intermediate node)
      val logicalRamps =
        buildLogicalRamps(
          graph = graph,
          clusterLinks = clusterLinkWays,
          corridorTimeline = corridorTimeline,
          corridorWayIds = corridorWayIds,
          fallbackCenterPt = centerPt,
          fallbackDist = centerDist,
        )
      if (logicalRamps.size < 2) continue

      // Determine cluster corridor span
      val allDists =
        (clusterSeeds.map { it.first } + logicalRamps.map { it.corridorDistMeters })
          .filter { it <= compositeCorridorMeters }
      val clusterMinDist = (allDists.minOrNull() ?: centerDist).coerceAtLeast(0.0)
      val clusterMaxDist = (allDists.maxOrNull() ?: centerDist).coerceAtMost(compositeCorridorMeters)
      if (clusterMaxDist - clusterMinDist > 500.0) continue

      val crossingRoadWays =
        findInterchangeCrossingWays(
          graph = graph,
          gradeSepFeatures = gradeSepFeatures.filter { it.distanceMeters in (clusterMinDist - 40.0)..(clusterMaxDist + 40.0) },
          clusterLinks = clusterLinkWays,
          corridorWayIds = corridorWayIds,
          centerPt = centerPt,
        )

      val classified =
        classifyInterchangeCluster(
          graph = graph,
          ramps = logicalRamps,
          crossingRoadWays = crossingRoadWays,
          centerPt = centerPt,
        )

      val entryTimeline =
        corridorTimeline.minByOrNull { abs(it.distMeters - clusterMinDist) }
      val anchorPt = entryTimeline?.point ?: centerPt
      val anchorHeading =
        entryTimeline?.bearingDeg
          ?: GeoMath.bearingDeg(matched.snappedPoint, anchorPt)

      val hiddenRaw =
        rawFeatures.filter { feat ->
          feat.index !in consumedFeatureIndices &&
            feat.distanceMeters in (clusterMinDist - 20.0)..(clusterMaxDist + 20.0) &&
            feat.type in setOf(
              FeatureType.OFF_RAMP,
              FeatureType.ON_RAMP,
              FeatureType.MERGE,
              FeatureType.BRIDGE,
              FeatureType.TUNNEL,
              FeatureType.OVERPASS_ABOVE,
              FeatureType.UNDERPASS_BELOW,
            )
        }
      hiddenRaw.forEach { consumedFeatureIndices += it.index }
      clusterLinkWays.forEach { consumedLinkIds += it.id }

      val branches =
        logicalRamps.sortedBy { it.corridorDistMeters }.mapIndexed { bIdx, ramp ->
          val delta = GeoMath.signedAngleDelta(anchorHeading, ramp.entryBearingDeg)
          val relDir =
            when {
              delta < -18.0 -> RelativeDirection.LEFT
              delta > 18.0 -> RelativeDirection.RIGHT
              else -> RelativeDirection.AHEAD
            }
          RoadBranch(
            badgeId = "1${'A' + bIdx}",
            wayId = ramp.primaryWay.id,
            name = BilingualName.fromOsmTags(ramp.primaryWay.tags),
            leadsTo = BilingualName.destinationFromOsmTags(ramp.primaryWay.tags),
            distanceMeters = ramp.corridorDistMeters,
            status = ramp.branchStatus,
            relativeDirection = relDir,
            bearingDeltaDeg = delta,
            worldPoints = ramp.worldSliceFromCorridor,
            localPoints = ramp.worldSliceFromCorridor.map { GeoMath.projectToHeadingFrame(anchorPt, anchorHeading, it) },
            osmTags = ramp.primaryWay.tags,
          )
        }

      val incomingWorld =
        sliceWorldPath(
          entryTimeline?.way ?: matched.way,
          entryTimeline?.nodeId?.let { nid -> entryTimeline.way.nodeIds.indexOf(nid).coerceAtLeast(0) } ?: matched.segmentIndex,
          matched.forwardAlongWay,
          takeBack = true,
          maxMeters = 60.0,
        )
      val outgoingWorld =
        sliceWorldPath(
          entryTimeline?.way ?: matched.way,
          entryTimeline?.nodeId?.let { nid -> entryTimeline.way.nodeIds.indexOf(nid).coerceAtLeast(0) } ?: matched.segmentIndex,
          matched.forwardAlongWay,
          takeBack = false,
          maxMeters = 90.0,
        )

      val crossingLocal =
        crossingRoadWays.firstOrNull()?.points?.map { GeoMath.projectToHeadingFrame(anchorPt, anchorHeading, it) }
          ?: emptyList()

      val interchangeFeature =
        RoadFeature(
          index = 1,
          type = classified.type,
          distanceMeters = clusterMinDist,
          anchor = anchorPt,
          anchorNodeId = entryTimeline?.nodeId,
          incomingLocalPoints = incomingWorld.map { GeoMath.projectToHeadingFrame(anchorPt, anchorHeading, it) },
          incomingWorldPoints = incomingWorld,
          outgoingMainLocalPoints = outgoingWorld.map { GeoMath.projectToHeadingFrame(anchorPt, anchorHeading, it) },
          outgoingMainWorldPoints = outgoingWorld,
          branches = branches,
          osmTags = entryTimeline?.way?.tags ?: matched.way.tags,
          isBridge = hiddenRaw.any { it.isBridge || it.type == FeatureType.BRIDGE },
          isTunnel = hiddenRaw.any { it.isTunnel || it.type == FeatureType.TUNNEL },
          source =
            if (clusterLinkWays.any { it.source == GraphDataSource.OVERPASS }) GraphDataSource.OVERPASS
            else matched.way.source,
          ruleId = classified.ruleId,
          confidence = classified.confidence,
          crossingLocalPoints = crossingLocal,
          hiddenSubFeatures = hiddenRaw,
        )

      results += interchangeFeature to hiddenRaw.mapTo(HashSet()) { it.index }
    }

    return results
  }

  private fun buildLogicalRamps(
    graph: RoadGraph,
    clusterLinks: List<OsmWay>,
    corridorTimeline: List<CorridorPointWithDist>,
    corridorWayIds: Set<Long>,
    fallbackCenterPt: GeoPoint,
    fallbackDist: Double,
  ): List<LogicalRamp> {
    val clusterIds = clusterLinks.mapTo(HashSet()) { it.id }
    val usedIds = HashSet<Long>()
    val ramps = mutableListOf<LogicalRamp>()

    for (seed in clusterLinks) {
      if (seed.id in usedIds) continue
      val chain = mutableListOf(seed)
      usedIds += seed.id

      // Extend through link-only joints
      fun extend(fromWay: OsmWay, atStart: Boolean) {
        var curr = fromWay
        var nodeId = if (atStart) curr.nodeIds.firstOrNull() else curr.nodeIds.lastOrNull()
        var guard = 0
        while (nodeId != null && guard++ < 6) {
          val incident = graph.nodeToWays[nodeId].orEmpty().mapNotNull { graph.ways[it] }
          val hasNonLink = incident.any { it.tags.highway?.lowercase() !in LINK_HIGHWAYS }
          if (hasNonLink) break
          val nextLink = incident.firstOrNull { it.id in clusterIds && it.id !in usedIds } ?: break
          usedIds += nextLink.id
          chain += nextLink
          nodeId = if (nextLink.nodeIds.firstOrNull() == nodeId) nextLink.nodeIds.lastOrNull() else nextLink.nodeIds.firstOrNull()
        }
      }

      extend(seed, atStart = true)
      extend(seed, atStart = false)

      // Order merged polyline points
      val pts = mergeChainPoints(chain)
      val (cumTurn, radius) = computeCumulativeTurnAndRadius(pts)
      val isLoop = cumTurn >= 200.0 && radius < 120.0
      val hasGradeStruct =
        chain.any { w ->
          (w.tags.bridge != null && w.tags.bridge != "no") ||
            (w.tags.tunnel != null && w.tags.tunnel != "no") ||
            effectiveLayer(w.tags) != 0
        }
      val isFlyover = !isLoop && hasGradeStruct && radius > 120.0

      // Find corridor anchor & distance
      var bestTimeline: CorridorPointWithDist? = null
      var bestAnchorWay = seed
      var bestNodeOnSeed = seed.nodeIds.firstOrNull()
      for (w in chain) {
        for (nid in w.nodeIds) {
          val cp = corridorTimeline.firstOrNull { it.nodeId == nid }
          if (cp != null && (bestTimeline == null || cp.distMeters < bestTimeline.distMeters)) {
            bestTimeline = cp
            bestAnchorWay = w
            bestNodeOnSeed = nid
          }
        }
      }

      val dist = bestTimeline?.distMeters ?: fallbackDist
      val worldSlice: List<GeoPoint>
      val status: BranchStatus
      val entryBearing: Double
      if (bestTimeline != null && bestNodeOnSeed != null) {
        val idx = bestAnchorWay.nodeIds.indexOf(bestNodeOnSeed).coerceAtLeast(0)
        val canFwd = idx + 1 < bestAnchorWay.points.size
        worldSlice = sliceWorldPath(bestAnchorWay, idx, canFwd, takeBack = false, maxMeters = 95.0)
        status = evaluateBranchStatus(bestAnchorWay, canFwd, TravelMode.DRIVING)
        entryBearing =
          if (worldSlice.size >= 2) GeoMath.bearingDeg(worldSlice[0], worldSlice[1])
          else bestTimeline.bearingDeg
      } else {
        worldSlice = pts.take(8)
        status = evaluateBranchStatus(seed, true, TravelMode.DRIVING)
        entryBearing =
          if (pts.size >= 2) GeoMath.bearingDeg(pts[0], pts[1])
          else GeoMath.bearingDeg(fallbackCenterPt, pts.first())
      }

      ramps +=
        LogicalRamp(
          primaryWay = bestAnchorWay,
          ways = chain,
          points = pts,
          cumTurnDeg = cumTurn,
          radiusMeters = radius,
          isLoop = isLoop,
          isFlyover = isFlyover,
          corridorDistMeters = dist,
          anchorOnCorridor = bestTimeline?.point,
          entryBearingDeg = entryBearing,
          worldSliceFromCorridor = worldSlice,
          branchStatus = status,
        )
    }
    return ramps
  }

  private fun mergeChainPoints(chain: List<OsmWay>): List<GeoPoint> {
    if (chain.size == 1) return chain.first().points
    val remaining = chain.toMutableList()
    val pts = remaining.removeAt(0).points.toMutableList()
    var progress = true
    while (remaining.isNotEmpty() && progress) {
      progress = false
      val it = remaining.iterator()
      while (it.hasNext()) {
        val next = it.next().points
        if (next.size < 2) {
          it.remove()
          continue
        }
        when {
          GeoMath.haversineMeters(pts.last(), next.first()) < 3.0 -> {
            pts.addAll(next.drop(1))
            it.remove()
            progress = true
          }
          GeoMath.haversineMeters(pts.last(), next.last()) < 3.0 -> {
            pts.addAll(next.reversed().drop(1))
            it.remove()
            progress = true
          }
          GeoMath.haversineMeters(pts.first(), next.last()) < 3.0 -> {
            val head = next.dropLast(1)
            pts.addAll(0, head)
            it.remove()
            progress = true
          }
          GeoMath.haversineMeters(pts.first(), next.first()) < 3.0 -> {
            val head = next.reversed().dropLast(1)
            pts.addAll(0, head)
            it.remove()
            progress = true
          }
        }
      }
    }
    return pts
  }

  private fun computeCumulativeTurnAndRadius(points: List<GeoPoint>): Pair<Double, Double> {
    if (points.size < 3) return 0.0 to 1_000.0
    var cumTurn = 0.0
    var signedTurn = 0.0
    var prevB: Double? = null
    for (i in 0 until points.lastIndex) {
      if (GeoMath.haversineMeters(points[i], points[i + 1]) < 1.0) continue
      val b = GeoMath.bearingDeg(points[i], points[i + 1])
      if (prevB != null) {
        val d = GeoMath.signedAngleDelta(prevB, b)
        cumTurn += abs(d)
        signedTurn += d
      }
      prevB = b
    }

    val totalLen = GeoMath.polylineLengthMeters(points)
    val arcRadius =
      if (cumTurn >= 5.0) {
        totalLen / Math.toRadians(cumTurn)
      } else {
        1_000.0
      }

    val radius =
      if (cumTurn >= 160.0) {
        val cLat = points.map { it.lat }.average()
        val cLon = points.map { it.lon }.average()
        val centroid = GeoPoint(cLat, cLon)
        val centroidRadius = points.map { GeoMath.haversineMeters(centroid, it) }.average()
        min(centroidRadius, arcRadius)
      } else {
        arcRadius
      }
    return cumTurn to radius
  }

  private fun findInterchangeCrossingWays(
    graph: RoadGraph,
    gradeSepFeatures: List<RoadFeature>,
    clusterLinks: List<OsmWay>,
    corridorWayIds: Set<Long>,
    centerPt: GeoPoint,
  ): List<OsmWay> {
    val clusterNodes = clusterLinks.flatMapTo(HashSet()) { it.nodeIds }
    val crossing = linkedSetOf<OsmWay>()
    for (nid in clusterNodes) {
      for (wId in graph.nodeToWays[nid].orEmpty()) {
        if (wId in corridorWayIds) continue
        val w = graph.ways[wId] ?: continue
        val hw = w.tags.highway?.lowercase() ?: continue
        if (hw in LINK_HIGHWAYS || hw in NON_DRIVING_HIGHWAYS) continue
        crossing += w
      }
    }
    if (crossing.isEmpty()) {
      gradeSepFeatures.forEach { feat ->
        feat.anchorNodeId?.let { aid -> graph.ways[aid]?.let { if (it.id !in corridorWayIds) crossing += it } }
      }
    }
    if (crossing.isEmpty()) {
      for (w in graph.ways.values) {
        if (w.id in corridorWayIds) continue
        val hw = w.tags.highway?.lowercase() ?: continue
        if (hw in LINK_HIGHWAYS || hw in NON_DRIVING_HIGHWAYS) continue
        if (w.points.any { GeoMath.haversineMeters(centerPt, it) < 120.0 }) {
          crossing += w
        }
      }
    }
    return crossing.toList()
  }

  private fun classifyInterchangeCluster(
    graph: RoadGraph,
    ramps: List<LogicalRamp>,
    crossingRoadWays: List<OsmWay>,
    centerPt: GeoPoint,
  ): ClassificationOutcome {
    val loopCount = ramps.count { it.isLoop }
    val nonLoopRamps = ramps.filter { !it.isLoop }
    val flyoverCount = ramps.count { it.isFlyover }

    // 1. CLOVERLEAF: >= 3 loop ramps (0.8 with 3, 0.9 with 4)
    if (loopCount >= 3) {
      val conf = if (loopCount >= 4) 0.90 else 0.80
      return ClassificationOutcome(FeatureType.CLOVERLEAF, "interchange_cloverleaf", conf)
    }

    // 2. DIAMOND: 4 ramps, no loops, each turning < 60deg and meeting crossing road at-grade on both sides
    if (ramps.size == 4 && loopCount == 0 && ramps.all { it.cumTurnDeg < 60.0 }) {
      val crossingWay = crossingRoadWays.firstOrNull()
      if (crossingWay != null && crossingWay.points.size >= 2) {
        val crossBearing = GeoMath.bearingDeg(crossingWay.points.first(), crossingWay.points.last())
        val crossingNodes = crossingRoadWays.flatMapTo(HashSet()) { it.nodeIds }
        var negSide = false
        var posSide = false
        for (ramp in ramps) {
          val rampNodeIds = ramp.ways.flatMapTo(HashSet()) { it.nodeIds }
          val shared = rampNodeIds.intersect(crossingNodes)
          for (sn in shared) {
            val pt = graph.nodes[sn]?.point ?: continue
            // At-grade check: same effective layer as a crossing way sharing node
            val crossSharing =
              crossingRoadWays.firstOrNull { sn in it.nodeIds } ?: continue
            val atGrade = effectiveLayer(ramp.primaryWay.tags) == effectiveLayer(crossSharing.tags) ||
              (ramp.primaryWay.tags.layer == null && crossSharing.tags.layer == null)
            if (!atGrade) continue
            val local = GeoMath.projectToHeadingFrame(centerPt, crossBearing, pt)
            if (local.yMeters < -12.0) negSide = true
            if (local.yMeters > 12.0) posSide = true
          }
        }
        if (negSide && posSide) {
          return ClassificationOutcome(FeatureType.DIAMOND, "interchange_diamond", 0.85)
        }
      }
    }

    // 3. TRUMPET: exactly 1 loop ramp, 2-3 other ramps, crossing road ends at the interchange
    if (loopCount == 1 && nonLoopRamps.size in 2..3) {
      val crossingEndsAtInterchange =
        if (crossingRoadWays.isEmpty()) {
          false
        } else {
          // Check if crossing road has a terminal endpoint inside/at the interchange OR only lies on one side
          val allCrossNodes = crossingRoadWays.flatMap { it.nodeIds }.distinct()
          val crossEndsAtCluster =
            allCrossNodes.any { nid ->
              val pt = graph.nodes[nid]?.point ?: return@any false
              if (GeoMath.haversineMeters(centerPt, pt) > 350.0) return@any false
              val inc = graph.nodeToWays[nid].orEmpty().mapNotNull { graph.ways[it] }
              val nonLinkInc = inc.count { it.tags.highway?.lowercase() !in LINK_HIGHWAYS }
              val linkInc = inc.count { it.tags.highway?.lowercase() in LINK_HIGHWAYS }
              nonLinkInc == 1 && linkInc >= 1
            }
          val crossPts = crossingRoadWays.flatMap { it.points }
          val refCrossBearing =
            crossingRoadWays.firstOrNull()?.let { GeoMath.bearingDeg(it.points.first(), it.points.last()) } ?: 0.0
          val yVals = crossPts.map { GeoMath.projectToHeadingFrame(centerPt, refCrossBearing, it).yMeters }
          val spansBothSides = (yVals.minOrNull() ?: 0.0) < -30.0 && (yVals.maxOrNull() ?: 0.0) > 30.0
          crossEndsAtCluster || !spansBothSides
        }
      if (crossingEndsAtInterchange) {
        return ClassificationOutcome(FeatureType.TRUMPET, "interchange_trumpet", 0.80)
      }
    }

    // 4. DIRECTIONAL: >= 2 flyover links (bridge/tunnel) with radius > 120m and no loops (conf 0.7 -> shown as generic)
    if (flyoverCount >= 2 && loopCount == 0) {
      return ClassificationOutcome(FeatureType.DIRECTIONAL, "interchange_directional", 0.70)
    }

    // 5. Generic INTERCHANGE
    return ClassificationOutcome(FeatureType.INTERCHANGE, "interchange_generic", 0.75)
  }

  private fun appendCompositeCensusHits(
    graph: RoadGraph,
    center: GeoPoint,
    mode: TravelMode,
    radiusMeters: Double,
    hits: MutableList<CensusHit>,
    addHit: (CensusHit) -> Unit,
    computeJumpBefore: (OsmWay, Int, Boolean, GeoPoint) -> Pair<GeoPoint, Double>,
  ) {
    // 1) STAGGERED_JUNCTION census from T_JUNCTION pairs in raw hits
    val tHits = hits.filter { it.type == FeatureType.T_JUNCTION }
    val maxStaggerM = if (mode == TravelMode.DRIVING) 80.0 else 40.0
    if (tHits.size >= 2) {
      val roadNodes = HashMap<Long, MutableList<OsmWay>>()
      for (w in graph.ways.values) {
        if (!isRelevantForMode(w, mode)) continue
        for (nid in w.nodeIds) roadNodes.getOrPut(nid) { mutableListOf() }.add(w)
      }
      val paired = HashSet<Int>()
      for (i in 0 until tHits.lastIndex) {
        if (i in paired) continue
        val h1 = tHits[i]
        for (j in i + 1 until tHits.size) {
          if (j in paired) continue
          val h2 = tHits[j]
          val distApart = GeoMath.haversineMeters(h1.anchor, h2.anchor)
          if (distApart !in 1.0..maxStaggerM) continue
          val mainBearing = GeoMath.bearingDeg(h1.anchor, h2.anchor)
          // Find minor branches at h1 & h2
          val w1Minor =
            graph.ways.values.firstOrNull { w ->
              isRelevantForMode(w, mode) &&
                w.points.size >= 2 &&
                GeoMath.haversineMeters(w.points.first(), h1.anchor) < 2.0 &&
                GeoMath.absAngleDiff(mainBearing, GeoMath.bearingDeg(w.points[0], w.points[1])) in 45.0..135.0
            } ?: continue
          val w2Minor =
            graph.ways.values.firstOrNull { w ->
              w.id != w1Minor.id &&
                isRelevantForMode(w, mode) &&
                w.points.size >= 2 &&
                GeoMath.haversineMeters(w.points.first(), h2.anchor) < 2.0 &&
                GeoMath.absAngleDiff(mainBearing, GeoMath.bearingDeg(w.points[0], w.points[1])) in 45.0..135.0
            } ?: continue
          val b1 = GeoMath.bearingDeg(w1Minor.points[0], w1Minor.points[1])
          val b2 = GeoMath.bearingDeg(w2Minor.points[0], w2Minor.points[1])
          val s1 = GeoMath.signedAngleDelta(mainBearing, b1)
          val s2 = GeoMath.signedAngleDelta(mainBearing, b2)
          if (s1 * s2 >= 0.0) continue
          if (GeoMath.absAngleDiff(b1, (b2 + 180.0) % 360.0) > 20.0) continue

          paired += i
          paired += j
          val jp = GeoMath.offsetPoint(h1.anchor, (mainBearing + 180.0) % 360.0, 150.0)
          addHit(
            CensusHit(
              FeatureType.STAGGERED_JUNCTION,
              h1.distFromCenter,
              h1.anchor,
              jp,
              mainBearing,
              "STAGGER_${i}_$j",
            ),
          )
          break
        }
      }
    }

    // 2) DIVIDED_ROAD & MEDIAN_OPENING census
    val onewayWays =
      graph.ways.values.filter { w ->
        w.points.size >= 2 &&
          isRelevantForMode(w, mode) &&
          w.tags.highway?.lowercase() !in LINK_HIGHWAYS &&
          w.tags.effectiveOneway() in setOf("yes", "-1") &&
          GeoMath.haversineMeters(center, w.points[w.points.size / 2]) <= radiusMeters + 300.0
      }
    val pairedDividedWays = HashSet<Long>()
    val partnerWayIds = HashSet<Long>()
    for (i in onewayWays.indices) {
      val w1 = onewayWays[i]
      if (w1.id in pairedDividedWays) continue
      val p1 = directedWayPoints(w1) ?: continue
      for (j in i + 1 until onewayWays.size) {
        val w2 = onewayWays[j]
        if (!sameRoadIdentityForDivided(w1.tags, w2.tags)) continue
        val p2 = directedWayPoints(w2) ?: continue
        val meas = measureParallelOverlap(p1, p2, maxLateralMeters = 60.0) ?: continue
        if (meas.lateralMeters in 3.0..60.0 && meas.oppositeDirectedDiffDeg < 20.0 && meas.overlapMeters > 100.0) {
          pairedDividedWays += w1.id
          pairedDividedWays += w2.id
          partnerWayIds += w1.id
          partnerWayIds += w2.id
          val mid = p1[p1.size / 2]
          val d = GeoMath.haversineMeters(center, mid)
          val (jp, hdg) = computeJumpBefore(w1, 0, true, w1.points.first())
          addHit(CensusHit(FeatureType.DIVIDED_ROAD, d, mid, jp, hdg, "DIV_${w1.id}_${w2.id}"))
          break
        }
      }
    }

    if (partnerWayIds.isNotEmpty()) {
      val partnerNodes =
        partnerWayIds.flatMapTo(HashSet()) { id -> graph.ways[id]?.nodeIds.orEmpty() }
      for (conn in graph.ways.values) {
        if (conn.id in partnerWayIds || conn.points.size < 2 || !isRelevantForMode(conn, mode)) continue
        if (GeoMath.polylineLengthMeters(conn.points) > 80.0) continue
        val sharedCount = conn.nodeIds.count { it in partnerNodes }
        if (sharedCount >= 2) {
          val anchor = conn.points.first()
          val d = GeoMath.haversineMeters(center, anchor)
          if (d <= radiusMeters) {
            val n0 = conn.nodeIds.firstOrNull()
            val hostWay = n0?.let { nid -> graph.nodeToWays[nid].orEmpty().mapNotNull { graph.ways[it] }.firstOrNull { it.id in partnerWayIds } } ?: conn
            val (jp, hdg) = computeJumpBefore(hostWay, 0, true, anchor)
            addHit(CensusHit(FeatureType.MEDIAN_OPENING, d, anchor, jp, hdg, "MED_${conn.id}"))
          }
        }
      }
    }

    // 3) FRONTAGE_ROAD census
    val mainHighways =
      graph.ways.values.filter { w ->
        w.points.size >= 2 &&
          isRelevantForMode(w, mode) &&
          highwayRank(w.tags.highway) >= 3 &&
          GeoMath.haversineMeters(center, w.points[w.points.size / 2]) <= radiusMeters + 300.0
      }
    for (par in graph.ways.values) {
      if (par.points.size < 2 || !isRelevantForMode(par, mode)) continue
      val hw = par.tags.highway?.lowercase() ?: continue
      if (hw in LINK_HIGHWAYS) continue
      val rPar = highwayRank(hw)
      if (hw == "service" && mode == TravelMode.DRIVING && par.tags.service?.lowercase() in IGNORED_DRIVING_SERVICES) continue
      val parNodes = par.nodeIds.toHashSet()
      val host =
        mainHighways.firstOrNull { mh ->
          mh.id != par.id &&
            (hw == "service" || rPar in 1 until highwayRank(mh.tags.highway)) &&
            measureParallelOverlap(mh.points, par.points, 40.0)?.let { m ->
              m.lateralMeters in 2.0..40.0 && m.undirectedBearingDiffDeg < 15.0 && m.overlapMeters > 80.0
            } == true &&
            (parNodes.any { it in mh.nodeIds } ||
              graph.ways.values.any { c ->
                c.id != par.id && c.id != mh.id && c.points.size >= 2 &&
                  GeoMath.polylineLengthMeters(c.points) <= 80.0 &&
                  c.nodeIds.any { it in parNodes } && c.nodeIds.any { it in mh.nodeIds }
              })
        }
      if (host != null) {
        val anchor = par.points.first()
        val d = GeoMath.haversineMeters(center, anchor)
        if (d <= radiusMeters) {
          val (jp, hdg) = computeJumpBefore(host, 0, true, host.points.first())
          addHit(CensusHit(FeatureType.FRONTAGE_ROAD, d, anchor, jp, hdg, "FR_${par.id}"))
        }
      }
    }

    // 4) INTERCHANGE cluster census (Driving)
    if (mode == TravelMode.DRIVING) {
      val linkWays = graph.ways.values.filter { it.tags.highway?.lowercase() in LINK_HIGHWAYS && it.points.size >= 2 }
      val gradeWays =
        graph.ways.values.filter { w ->
          w.points.size >= 2 &&
            (effectiveLayer(w.tags) != 0 || (w.tags.bridge != null && w.tags.bridge != "no") || (w.tags.tunnel != null && w.tags.tunnel != "no"))
        }
      val seenClusterKeys = HashSet<Long>()
      for (gw in gradeWays) {
        if (gw.id in seenClusterKeys) continue
        val gwMid = gw.points[gw.points.size / 2]
        val d = GeoMath.haversineMeters(center, gwMid)
        if (d > radiusMeters) continue
        val nearbyLinks = linkWays.filter { lw -> lw.points.any { GeoMath.haversineMeters(gwMid, it) < 280.0 } }
        if (nearbyLinks.size < 2) continue
        seenClusterKeys += gw.id
        val ramps =
          buildLogicalRamps(
            graph = graph,
            clusterLinks = nearbyLinks,
            corridorTimeline = emptyList(),
            corridorWayIds = hashSetOf(gw.id),
            fallbackCenterPt = gwMid,
            fallbackDist = d,
          )
        if (ramps.size < 2) continue
        val crossing =
          findInterchangeCrossingWays(
            graph = graph,
            gradeSepFeatures = emptyList(),
            clusterLinks = nearbyLinks,
            corridorWayIds = hashSetOf(gw.id),
            centerPt = gwMid,
          )
        val outcome = classifyInterchangeCluster(graph, ramps, crossing, gwMid)
        val effectiveType =
          if (outcome.type != FeatureType.INTERCHANGE && outcome.confidence < 0.8) FeatureType.INTERCHANGE else outcome.type
        val (jp, hdg) = computeJumpBefore(gw, 0, true, gw.points.first())
        addHit(CensusHit(effectiveType, d, gwMid, jp, hdg, "IC_${gw.id}"))
      }
    }
  }
}
