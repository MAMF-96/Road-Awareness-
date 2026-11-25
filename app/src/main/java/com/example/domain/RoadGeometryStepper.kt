package com.example.domain

import com.example.data.model.GeoPoint
import com.example.data.model.OsmWay
import com.example.data.model.RoadCandidate
import com.example.data.model.RoadGraph
import com.example.data.model.TravelMode
import kotlin.math.abs

object RoadGeometryStepper {
  private val NON_DRIVING_HIGHWAYS =
    setOf("footway", "path", "pedestrian", "steps", "cycleway", "corridor", "raceway", "bus_guideway")
  private val NON_WALKING_HIGHWAYS = setOf("motorway", "motorway_link", "raceway", "bus_guideway")
  private val IGNORED_DRIVING_SERVICES = setOf("driveway", "parking_aisle", "drive-through")

  data class StepResult(
    val point: GeoPoint,
    val headingDeg: Double,
    val wayId: Long?,
    val hitDeadEnd: Boolean,
    val consumedPreferredBranch: Boolean,
    val movedMeters: Double,
  )

  private data class BranchOption(
    val way: OsmWay,
    val nodeIdxInWay: Int,
    val forward: Boolean,
    val nextPoint: GeoPoint,
    val outBearingDeg: Double,
    val turnDiffDeg: Double,
  )

  fun stepAlongRoad(
    graph: RoadGraph?,
    matched: RoadCandidate?,
    currentPoint: GeoPoint,
    currentHeadingDeg: Double,
    deltaMeters: Double,
    mode: TravelMode,
    preferredBranchWayId: Long? = null,
  ): StepResult {
    if (abs(deltaMeters) < 0.05) {
      return StepResult(
        point = currentPoint,
        headingDeg = currentHeadingDeg,
        wayId = matched?.way?.id,
        hitDeadEnd = false,
        consumedPreferredBranch = false,
        movedMeters = 0.0,
      )
    }

    val activeMatch =
      matched
        ?: graph?.let {
          MapMatcher.match(
            graph = it,
            position = currentPoint,
            headingDeg = currentHeadingDeg,
            mode = mode,
            previousWayId = null,
            accuracyMeters = 25f,
          ).primary
        }

    if (graph == null || activeMatch == null || activeMatch.way.points.size < 2) {
      val moveBearing =
        if (deltaMeters >= 0.0) currentHeadingDeg
        else (currentHeadingDeg + 180.0) % 360.0
      val stepped = GeoMath.offsetPoint(currentPoint, moveBearing, abs(deltaMeters))
      return StepResult(
        point = stepped,
        headingDeg = currentHeadingDeg,
        wayId = null,
        hitDeadEnd = false,
        consumedPreferredBranch = false,
        movedMeters = abs(deltaMeters),
      )
    }

    val goingForward = deltaMeters > 0.0
    var currentWay = activeMatch.way
    var travelDirForward = if (goingForward) activeMatch.forwardAlongWay else !activeMatch.forwardAlongWay
    val pts = currentWay.points
    val segIdx = activeMatch.segmentIndex.coerceIn(0, pts.lastIndex - 1)
    var cursorPt = activeMatch.snappedPoint
    var nextVertexIdx = if (travelDirForward) segIdx + 1 else segIdx
    var remaining = abs(deltaMeters)
    var totalMoved = 0.0
    var consumedPreferred = false
    var guard = 0

    // Seed tangent heading from current segment
    val initialSegBearing = GeoMath.bearingDeg(pts[segIdx], pts[segIdx + 1])
    var lastStepBearing =
      if (travelDirForward) initialSegBearing
      else (initialSegBearing + 180.0) % 360.0

    while (remaining > 0.01 && guard++ < 64) {
      val wayPts = currentWay.points
      if (nextVertexIdx in wayPts.indices) {
        val targetPt = wayPts[nextVertexIdx]
        val segLen = GeoMath.haversineMeters(cursorPt, targetPt)
        val segBearing =
          if (segLen > 0.2) GeoMath.bearingDeg(cursorPt, targetPt)
          else lastStepBearing

        if (segLen >= remaining && segLen > 0.01) {
          val t = (remaining / segLen).coerceIn(0.0, 1.0)
          val finalPt =
            GeoPoint(
              lat = cursorPt.lat + (targetPt.lat - cursorPt.lat) * t,
              lon = cursorPt.lon + (targetPt.lon - cursorPt.lon) * t,
            )
          totalMoved += remaining
          val finalHeading =
            if (goingForward) segBearing
            else (segBearing + 180.0) % 360.0
          return StepResult(
            point = finalPt,
            headingDeg = ((finalHeading % 360.0) + 360.0) % 360.0,
            wayId = currentWay.id,
            hitDeadEnd = false,
            consumedPreferredBranch = consumedPreferred,
            movedMeters = totalMoved,
          )
        }

        // Advance to this vertex
        remaining -= segLen
        totalMoved += segLen
        if (segLen > 0.2) lastStepBearing = segBearing
        cursorPt = targetPt

        // Check interior junction turn onto preferredBranchWayId when driving/walking forward
        val vertexNodeId = currentWay.nodeIds.getOrNull(nextVertexIdx)
        if (goingForward &&
          !consumedPreferred &&
          preferredBranchWayId != null &&
          preferredBranchWayId != currentWay.id &&
          vertexNodeId != null &&
          preferredBranchWayId in graph.nodeToWays[vertexNodeId].orEmpty()
        ) {
          val branchWay = graph.ways[preferredBranchWayId]
          val chosenBranch =
            branchWay?.let { pickBestDirectionOnWayAtNode(it, vertexNodeId, lastStepBearing, mode, goingForward) }
          if (chosenBranch != null) {
            currentWay = chosenBranch.way
            travelDirForward = chosenBranch.forward
            nextVertexIdx = if (travelDirForward) chosenBranch.nodeIdxInWay + 1 else chosenBranch.nodeIdxInWay - 1
            lastStepBearing = chosenBranch.outBearingDeg
            consumedPreferred = true
            continue
          }
        }

        nextVertexIdx += if (travelDirForward) 1 else -1
        continue
      }

      // Reached the end of currentWay
      val endIdx = if (travelDirForward) currentWay.nodeIds.lastIndex else 0
      val endNodeId = currentWay.nodeIds.getOrNull(endIdx)
      if (endNodeId == null) {
        val finalHeading = if (goingForward) lastStepBearing else (lastStepBearing + 180.0) % 360.0
        return StepResult(
          point = cursorPt,
          headingDeg = ((finalHeading % 360.0) + 360.0) % 360.0,
          wayId = currentWay.id,
          hitDeadEnd = true,
          consumedPreferredBranch = consumedPreferred,
          movedMeters = totalMoved,
        )
      }

      val options = collectContinuationOptions(graph, currentWay, endNodeId, lastStepBearing, mode, goingForward)
      val preferredOpt =
        if (goingForward && !consumedPreferred && preferredBranchWayId != null) {
          options.filter { it.way.id == preferredBranchWayId }.minByOrNull { it.turnDiffDeg }
        } else {
          null
        }
      val nextOpt = preferredOpt ?: options.firstOrNull()

      if (nextOpt == null) {
        val finalHeading = if (goingForward) lastStepBearing else (lastStepBearing + 180.0) % 360.0
        return StepResult(
          point = cursorPt,
          headingDeg = ((finalHeading % 360.0) + 360.0) % 360.0,
          wayId = currentWay.id,
          hitDeadEnd = true,
          consumedPreferredBranch = consumedPreferred,
          movedMeters = totalMoved,
        )
      }

      if (preferredOpt != null) {
        consumedPreferred = true
      }
      currentWay = nextOpt.way
      travelDirForward = nextOpt.forward
      nextVertexIdx = if (travelDirForward) nextOpt.nodeIdxInWay + 1 else nextOpt.nodeIdxInWay - 1
      lastStepBearing = nextOpt.outBearingDeg
    }

    val finalHeading = if (goingForward) lastStepBearing else (lastStepBearing + 180.0) % 360.0
    return StepResult(
      point = cursorPt,
      headingDeg = ((finalHeading % 360.0) + 360.0) % 360.0,
      wayId = currentWay.id,
      hitDeadEnd = false,
      consumedPreferredBranch = consumedPreferred,
      movedMeters = totalMoved,
    )
  }

  private fun isWayNavigableForMode(way: OsmWay, mode: TravelMode): Boolean {
    val hw = way.tags.highway?.lowercase()?.trim() ?: return false
    if (hw == "construction" || way.tags.access?.lowercase()?.trim() == "no") return false
    if (mode == TravelMode.DRIVING) {
      if (hw in NON_DRIVING_HIGHWAYS) return false
      if (hw == "service" && way.tags.service?.lowercase()?.trim() in IGNORED_DRIVING_SERVICES) return false
    } else {
      if (hw in NON_WALKING_HIGHWAYS) return false
    }
    return true
  }

  private fun isDirectionAllowed(way: OsmWay, forward: Boolean, mode: TravelMode, goingForward: Boolean): Boolean {
    if (!goingForward || mode != TravelMode.DRIVING) return true
    val effOneway = way.tags.effectiveOneway()
    if (effOneway == "yes" && !forward) return false
    if (effOneway == "-1" && forward) return false
    return true
  }

  private fun pickBestDirectionOnWayAtNode(
    way: OsmWay,
    nodeId: Long,
    incomingBearingDeg: Double,
    mode: TravelMode,
    goingForward: Boolean,
  ): BranchOption? {
    val idx = way.nodeIds.indexOf(nodeId)
    if (idx < 0 || way.points.size < 2) return null
    val candidates = mutableListOf<BranchOption>()
    if (idx < way.points.lastIndex && isDirectionAllowed(way, forward = true, mode = mode, goingForward = goingForward)) {
      val b = GeoMath.bearingDeg(way.points[idx], way.points[idx + 1])
      candidates += BranchOption(way, idx, true, way.points[idx + 1], b, GeoMath.absAngleDiff(incomingBearingDeg, b))
    }
    if (idx > 0 && isDirectionAllowed(way, forward = false, mode = mode, goingForward = goingForward)) {
      val b = GeoMath.bearingDeg(way.points[idx], way.points[idx - 1])
      candidates += BranchOption(way, idx, false, way.points[idx - 1], b, GeoMath.absAngleDiff(incomingBearingDeg, b))
    }
    return candidates.minByOrNull { it.turnDiffDeg }
  }

  private fun collectContinuationOptions(
    graph: RoadGraph,
    currentWay: OsmWay,
    nodeId: Long,
    incomingBearingDeg: Double,
    mode: TravelMode,
    goingForward: Boolean,
  ): List<BranchOption> {
    val wayIds = graph.nodeToWays[nodeId].orEmpty()
    val options = mutableListOf<BranchOption>()
    for (wId in wayIds) {
      if (wId == currentWay.id) continue
      val w = graph.ways[wId] ?: continue
      if (w.points.size < 2 || !isWayNavigableForMode(w, mode)) continue
      val idx = w.nodeIds.indexOf(nodeId)
      if (idx < 0) continue
      if (idx < w.points.lastIndex && isDirectionAllowed(w, forward = true, mode = mode, goingForward = goingForward)) {
        val outB = GeoMath.bearingDeg(w.points[idx], w.points[idx + 1])
        val diff = GeoMath.absAngleDiff(incomingBearingDeg, outB)
        if (diff < 145.0) {
          options += BranchOption(w, idx, true, w.points[idx + 1], outB, diff)
        }
      }
      if (idx > 0 && isDirectionAllowed(w, forward = false, mode = mode, goingForward = goingForward)) {
        val outB = GeoMath.bearingDeg(w.points[idx], w.points[idx - 1])
        val diff = GeoMath.absAngleDiff(incomingBearingDeg, outB)
        if (diff < 145.0) {
          options += BranchOption(w, idx, false, w.points[idx - 1], outB, diff)
        }
      }
    }
    // Check closed-loop self-wrap if no other way continues
    if (options.isEmpty() && currentWay.nodeIds.size >= 3 && currentWay.nodeIds.first() == currentWay.nodeIds.last()) {
      val isAtEnd = nodeId == currentWay.nodeIds.last()
      if (isAtEnd) {
        val idx = 0
        val outB = GeoMath.bearingDeg(currentWay.points[0], currentWay.points[1])
        val diff = GeoMath.absAngleDiff(incomingBearingDeg, outB)
        if (diff < 145.0 && isDirectionAllowed(currentWay, true, mode, goingForward)) {
          options += BranchOption(currentWay, idx, true, currentWay.points[1], outB, diff)
        }
      }
    }
    return options.sortedBy { it.turnDiffDeg }
  }

  fun interpolatePoint(from: GeoPoint, to: GeoPoint, fraction: Double): GeoPoint {
    val t = fraction.coerceIn(0.0, 1.0)
    return GeoPoint(
      lat = from.lat + (to.lat - from.lat) * t,
      lon = from.lon + (to.lon - from.lon) * t,
    )
  }

  fun interpolateHeadingDeg(fromDeg: Double, toDeg: Double, fraction: Double): Double {
    val t = fraction.coerceIn(0.0, 1.0)
    val delta = GeoMath.signedAngleDelta(fromDeg, toDeg)
    return ((fromDeg + delta * t) % 360.0 + 360.0) % 360.0
  }
}
