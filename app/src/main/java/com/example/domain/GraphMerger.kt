package com.example.domain

import com.example.data.model.GraphDataSource
import com.example.data.model.OsmNode
import com.example.data.model.OsmWay
import com.example.data.model.RoadGraph
import kotlin.math.abs

/**
 * Progressively merges fast z14 vector-tile road graphs with authoritative Overpass OSM graphs.
 * Overpass ways always win for overlapping physical roads (preserving destination, lanes, junction,
 * oneway, etc.), while non-overlapping tile ways fill gaps cleanly without duplicate parallel candidates.
 */
object GraphMerger {

  fun merge(tileGraph: RoadGraph?, overpassGraph: RoadGraph?): RoadGraph? {
    if (tileGraph == null && overpassGraph == null) return null
    if (tileGraph == null) return overpassGraph
    if (overpassGraph == null) return tileGraph

    val mergedNodes = HashMap<Long, OsmNode>(overpassGraph.nodes.size + tileGraph.nodes.size)
    val mergedWays = HashMap<Long, OsmWay>(overpassGraph.ways.size + tileGraph.ways.size)
    val nodeToWays = HashMap<Long, MutableList<Long>>(overpassGraph.nodes.size + tileGraph.nodes.size)

    // 1. Overpass ways always win
    mergedNodes.putAll(overpassGraph.nodes)
    for ((wId, way) in overpassGraph.ways) {
      mergedWays[wId] = way
      for (nid in way.nodeIds.distinct()) {
        nodeToWays.getOrPut(nid) { mutableListOf() }.add(wId)
      }
    }

    // 2. Keep tile ways ONLY where Overpass has no overlapping physical road
    val overpassList = overpassGraph.ways.values.toList()
    var keptTileWays = 0
    for ((wId, tileWay) in tileGraph.ways) {
      if (isCoveredByOverpass(tileWay, overpassList)) continue
      keptTileWays++
      for (i in tileWay.nodeIds.indices) {
        val nid = tileWay.nodeIds[i]
        val pt = tileWay.points.getOrNull(i) ?: continue
        if (!mergedNodes.containsKey(nid)) {
          mergedNodes[nid] = tileGraph.nodes[nid] ?: OsmNode(id = nid, point = pt)
        }
      }
      mergedWays[wId] = tileWay
      for (nid in tileWay.nodeIds.distinct()) {
        nodeToWays.getOrPut(nid) { mutableListOf() }.add(wId)
      }
    }

    val combinedSource =
      when {
        keptTileWays > 0 && overpassGraph.ways.isNotEmpty() -> GraphDataSource.MERGED
        overpassGraph.ways.isNotEmpty() -> GraphDataSource.OVERPASS
        else -> tileGraph.source
      }

    return RoadGraph(
      nodes = mergedNodes,
      ways = mergedWays,
      nodeToWays = nodeToWays,
      fetchedAtMs = maxOf(overpassGraph.fetchedAtMs, tileGraph.fetchedAtMs),
      fromCache = overpassGraph.fromCache && tileGraph.fromCache,
      source = combinedSource,
      loadedTiles = tileGraph.loadedTiles,
    )
  }

  private fun isCoveredByOverpass(tileWay: OsmWay, overpassWays: List<OsmWay>): Boolean {
    if (overpassWays.isEmpty() || tileWay.points.size < 2) return false
    val midPt = tileWay.points[tileWay.points.size / 2]
    val firstPt = tileWay.points.first()
    val lastPt = tileWay.points.last()
    val tileBearing = GeoMath.bearingDeg(firstPt, lastPt)

    for (ovWay in overpassWays) {
      if (ovWay.points.size < 2) continue
      // Fast lat/lon bounding rejection (~35m margin)
      var minLat = 90.0
      var maxLat = -90.0
      var minLon = 180.0
      var maxLon = -180.0
      for (p in ovWay.points) {
        if (p.lat < minLat) minLat = p.lat
        if (p.lat > maxLat) maxLat = p.lat
        if (p.lon < minLon) minLon = p.lon
        if (p.lon > maxLon) maxLon = p.lon
      }
      val pad = 0.00035
      if (midPt.lat < minLat - pad || midPt.lat > maxLat + pad ||
        midPt.lon < minLon - pad || midPt.lon > maxLon + pad
      ) {
        continue
      }

      val snapMid = GeoMath.snapToPolyline(midPt, ovWay.points)
      if (snapMid.distanceMeters <= 16.0) {
        val segIdx = snapMid.segmentIndex.coerceIn(0, ovWay.points.lastIndex - 1)
        val ovBearing = GeoMath.bearingDeg(ovWay.points[segIdx], ovWay.points[segIdx + 1])
        val delta = GeoMath.absAngleDiff(tileBearing, ovBearing)
        if (delta <= 28.0 || abs(delta - 180.0) <= 28.0) {
          return true
        }
      }
    }
    return false
  }
}
