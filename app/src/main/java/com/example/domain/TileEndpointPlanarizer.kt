package com.example.domain

import com.example.data.model.GeoPoint
import com.example.data.model.LoadedTileBounds
import com.example.data.model.OsmNode
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.PlanarizeStats
import com.example.data.model.RoadGraph
import com.example.data.remote.TileRoadGraphSource
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Spatial-hash endpoint planarizer that snaps T-junctions and ramp connections within 1.5 m
 * using a 3 m spatial hash grid while preserving layer / bridge / tunnel separation.
 *
 * Step (a): Vertex merge if an endpoint lies within 1.5 m of a vertex/endpoint of another way on the same effectiveLayer.
 * Step (b): Otherwise, if it lies within 1.5 m of the interior of a segment of another way on the same effectiveLayer,
 *           split that way at the projected point, insert the shared vertex, and merge the endpoints using
 *           injective encoded way IDs (< 2^20) where applicable.
 *
 * Does NOT snap interior crossings without an endpoint, nor cross effectiveLayer boundaries.
 */
object TileEndpointPlanarizer {
  private const val GRID_CELL_METERS = 3.0
  private const val SNAP_RADIUS_METERS = 1.5
  private const val SNAP_RADIUS_SQ = SNAP_RADIUS_METERS * SNAP_RADIUS_METERS

  private val EXCLUDED_CLASSES =
    setOf(
      "rail",
      "tram",
      "subway",
      "light_rail",
      "monorail",
      "narrow_gauge",
      "preserved",
      "ferry",
      "aerialway",
      "cable_car",
      "gondola",
      "chair_lift",
      "drag_lift",
      "funicular",
      "transit",
    )

  fun isExcludedTransit(tags: OsmTags): Boolean {
    val hw = tags.highway?.lowercase(Locale.US)?.trim()
    val svc = tags.service?.lowercase(Locale.US)?.trim()
    return (hw != null && hw in EXCLUDED_CLASSES) || (svc != null && svc in EXCLUDED_CLASSES)
  }

  fun effectiveLayer(tags: OsmTags): Int {
    tags.layer?.trim()?.toIntOrNull()?.let { return it }
    val bridge = tags.bridge?.trim()?.lowercase(Locale.US)
    if (bridge != null && bridge != "no" && bridge != "false" && bridge != "0") return 1
    val tunnel = tags.tunnel?.trim()?.lowercase(Locale.US)
    if (tunnel != null && tunnel != "no" && tunnel != "false" && tunnel != "0") return -1
    return 0
  }

  fun planarize(
    nodes: MutableMap<Long, OsmNode>,
    ways: MutableMap<Long, OsmWay>,
    nodeToWays: MutableMap<Long, MutableList<Long>>,
    loadedTiles: List<LoadedTileBounds> = emptyList(),
    wayTileKeyOverrides: MutableMap<Long, String>? = null,
  ): PlanarizeStats {
    if (ways.size < 2) {
      return PlanarizeStats()
    }

    // Compute local flat-earth projection centered on graph centroid
    var sumLat = 0.0
    var sumLon = 0.0
    var cnt = 0
    for (w in ways.values) {
      val pt = w.points.firstOrNull() ?: continue
      sumLat += pt.lat
      sumLon += pt.lon
      cnt++
      if (cnt >= 64) break
    }
    if (cnt == 0) return PlanarizeStats()
    val refLat = sumLat / cnt
    val refLon = GeoMath.normalizeLon(sumLon / cnt)
    val mPerDegLat = 111_320.0
    val mPerDegLon = 111_320.0 * cos(Math.toRadians(refLat)).coerceAtLeast(0.15)

    fun toLocalX(lon: Double): Double = GeoMath.unwrapLonDelta(lon - refLon) * mPerDegLon
    fun toLocalY(lat: Double): Double = (lat - refLat) * mPerDegLat
    fun toGeoPoint(mx: Double, my: Double): GeoPoint {
      val lat = ((refLat + my / mPerDegLat) * 1_000_000.0).let { kotlin.math.round(it) / 1_000_000.0 }
      val lon = GeoMath.normalizeLon(((refLon + mx / mPerDegLon) * 1_000_000.0).let { kotlin.math.round(it) / 1_000_000.0 })
      return GeoPoint(lat, lon)
    }

    fun packCell(cx: Int, cy: Int): Long = (cy.toLong() shl 32) or (cx.toLong() and 0xFFFFFFFFL)

    var vertexMerges = 0
    var segmentSplits = 0
    var rejectedLayerMismatches = 0
    val mismatchPairs = HashSet<Long>()

    // Track node layers observed from eligible ways so vertices carry layer mask/value
    val activeWayIds = ways.keys.sorted()

    // ---------------- PASS (a): Vertex merge within 1.5m ----------------
    run {
      data class VertexEntry(
        val wayId: Long,
        val nodeId: Long,
        val x: Double,
        val y: Double,
        val layer: Int,
        val isEndpoint: Boolean,
      )

      val initVertexCap = min(ways.size * 3, 16_384)
      val vertices = ArrayList<VertexEntry>(initVertexCap)
      val vGrid = HashMap<Long, MutableList<Int>>(initVertexCap)

      for (wid in activeWayIds) {
        val w = ways[wid] ?: continue
        if (w.points.size < 2 || w.nodeIds.size != w.points.size) continue
        if (isExcludedTransit(w.tags)) continue
        val layer = effectiveLayer(w.tags)
        val last = w.points.lastIndex
        for (i in w.points.indices) {
          val pt = w.points[i]
          val x = toLocalX(pt.lon)
          val y = toLocalY(pt.lat)
          val idx = vertices.size
          vertices += VertexEntry(wid, w.nodeIds[i], x, y, layer, i == 0 || i == last)
          val cx = floor(x / GRID_CELL_METERS).toInt()
          val cy = floor(y / GRID_CELL_METERS).toInt()
          vGrid.getOrPut(packCell(cx, cy)) { ArrayList(1) }.add(idx)
        }
      }

      val parent = HashMap<Long, Long>()
      fun findRoot(id: Long): Long {
        var cur = id
        while (true) {
          val p = parent[cur] ?: return cur
          if (p == cur) return cur
          cur = p
        }
      }
      fun unionNodes(a: Long, b: Long): Long {
        val ra = findRoot(a)
        val rb = findRoot(b)
        if (ra == rb) return ra
        val keep = min(ra, rb)
        val drop = max(ra, rb)
        parent[drop] = keep
        return keep
      }

      for (i in vertices.indices) {
        val v = vertices[i]
        if (!v.isEndpoint) continue
        val cx = floor(v.x / GRID_CELL_METERS).toInt()
        val cy = floor(v.y / GRID_CELL_METERS).toInt()
        var bestOtherNid = 0L
        var bestDistSq = SNAP_RADIUS_SQ + 1e-6

        for (dy in -1..1) {
          for (dx in -1..1) {
            val bucket = vGrid[packCell(cx + dx, cy + dy)] ?: continue
            for (j in bucket) {
              val o = vertices[j]
              if (o.wayId == v.wayId) continue
              if (findRoot(o.nodeId) == findRoot(v.nodeId)) continue
              val ddx = o.x - v.x
              val ddy = o.y - v.y
              val dSq = ddx * ddx + ddy * ddy
              if (dSq <= SNAP_RADIUS_SQ) {
                if (o.layer != v.layer) {
                  val pairKey = min(v.wayId, o.wayId) * 1000003L + max(v.wayId, o.wayId)
                  if (mismatchPairs.add(pairKey)) rejectedLayerMismatches++
                  continue
                }
                if (dSq < bestDistSq) {
                  bestDistSq = dSq
                  bestOtherNid = o.nodeId
                }
              }
            }
          }
        }

        if (bestOtherNid != 0L) {
          val ra = findRoot(v.nodeId)
          val rb = findRoot(bestOtherNid)
          if (ra != rb) {
            unionNodes(ra, rb)
            vertexMerges++
          }
        }
      }

      if (parent.isNotEmpty()) {
        val replacements = HashMap<Long, Long>(parent.size)
        for (k in parent.keys) {
          val root = findRoot(k)
          if (root != k) replacements[k] = root
        }
        applyNodeReplacements(replacements, nodes, ways, nodeToWays)
      }
    }

    // ---------------- PASS (b): Split interior segments within 1.5m of an endpoint ----------------
    run {
      data class SegEntry(
        val wayId: Long,
        val segIdx: Int,
        val nid1: Long,
        val nid2: Long,
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double,
        val layer: Int,
      )

      val wayIdsNow = ways.keys.sorted()

      // Collect endpoint neighborhoods first so segment rasterization only allocates cells near real endpoints
      var minEpX = Double.POSITIVE_INFINITY
      var maxEpX = Double.NEGATIVE_INFINITY
      var minEpY = Double.POSITIVE_INFINITY
      var maxEpY = Double.NEGATIVE_INFINITY
      var epCount = 0
      for (wid in wayIdsNow) {
        val w = ways[wid] ?: continue
        if (w.points.size < 2 || w.nodeIds.size != w.points.size) continue
        if (isExcludedTransit(w.tags)) continue
        epCount += 2
      }
      if (epCount == 0) return@run

      val coarseCellMeters = 250.0
      val epFineCells = LongHashSet(min(epCount, 32_768).coerceAtLeast(64))
      val epCoarseCells = LongHashSet(min(epCount, 2_048).coerceAtLeast(64))

      for (wid in wayIdsNow) {
        val w = ways[wid] ?: continue
        if (w.points.size < 2 || w.nodeIds.size != w.points.size) continue
        if (isExcludedTransit(w.tags)) continue
        val endpointIndices = intArrayOf(0, w.points.lastIndex)
        for (epIdx in endpointIndices) {
          val epPt = w.points[epIdx]
          val ex = toLocalX(epPt.lon)
          val ey = toLocalY(epPt.lat)
          if (!ex.isFinite() || !ey.isFinite()) continue
          if (ex < minEpX) minEpX = ex
          if (ex > maxEpX) maxEpX = ex
          if (ey < minEpY) minEpY = ey
          if (ey > maxEpY) maxEpY = ey
          val ecx = floor(ex / GRID_CELL_METERS).toInt()
          val ecy = floor(ey / GRID_CELL_METERS).toInt()
          epFineCells.add(packCell(ecx, ecy))
          val ccx = floor(ex / coarseCellMeters).toInt()
          val ccy = floor(ey / coarseCellMeters).toInt()
          epCoarseCells.add(packCell(ccx, ccy))
        }
      }
      if (!minEpX.isFinite() || !minEpY.isFinite()) return@run

      val clipMinX = minEpX - 6.0
      val clipMaxX = maxEpX + 6.0
      val clipMinY = minEpY - 6.0
      val clipMaxY = maxEpY + 6.0

      val segs = ArrayList<SegEntry>(min(wayIdsNow.size * 3, 16_384))
      val sGrid = HashMap<Long, MutableList<Int>>()
      val coarseGrid = HashMap<Long, MutableList<Int>>()
      val fallbackLongSegs = ArrayList<Int>()
      val clippedCoords = DoubleArray(4)

      fun hasNeighborInSet(set: LongHashSet, gx: Int, gy: Int): Boolean {
        for (dy in -1..1) {
          for (dx in -1..1) {
            if (set.contains(packCell(gx + dx, gy + dy))) return true
          }
        }
        return false
      }

      for (wid in wayIdsNow) {
        val w = ways[wid] ?: continue
        if (w.points.size < 2 || w.nodeIds.size != w.points.size) continue
        if (isExcludedTransit(w.tags)) continue
        val layer = effectiveLayer(w.tags)
        var x1 = toLocalX(w.points[0].lon)
        var y1 = toLocalY(w.points[0].lat)
        for (sIdx in 0 until w.points.lastIndex) {
          val x2 = toLocalX(w.points[sIdx + 1].lon)
          val y2 = toLocalY(w.points[sIdx + 1].lat)
          if (x1.isFinite() && y1.isFinite() && x2.isFinite() && y2.isFinite()) {
            if (clipSegmentToBox(x1, y1, x2, y2, clipMinX, clipMinY, clipMaxX, clipMaxY, clippedCoords)) {
              val cx1 = clippedCoords[0]
              val cy1 = clippedCoords[1]
              val cx2 = clippedCoords[2]
              val cy2 = clippedCoords[3]
              var segEntryIdx = -1
              fun ensureSegIdx(): Int {
                if (segEntryIdx < 0) {
                  segEntryIdx = segs.size
                  segs += SegEntry(wid, sIdx, w.nodeIds[sIdx], w.nodeIds[sIdx + 1], x1, y1, x2, y2, layer)
                }
                return segEntryIdx
              }

              val span = max(kotlin.math.abs(cx2 - cx1), kotlin.math.abs(cy2 - cy1))
              if (span <= 150.0) {
                visitSupercoverGridCells(cx1, cy1, cx2, cy2, GRID_CELL_METERS) { gx, gy ->
                  if (hasNeighborInSet(epFineCells, gx, gy)) {
                    val idx = ensureSegIdx()
                    sGrid.getOrPut(packCell(gx, gy)) { ArrayList(2) }.add(idx)
                  }
                }
              } else {
                val estCoarseSteps =
                  ceil(kotlin.math.abs(cx2 - cx1) / coarseCellMeters) +
                    ceil(kotlin.math.abs(cy2 - cy1) / coarseCellMeters)
                if (estCoarseSteps <= 4_000.0) {
                  visitSupercoverGridCells(cx1, cy1, cx2, cy2, coarseCellMeters) { gx, gy ->
                    if (hasNeighborInSet(epCoarseCells, gx, gy)) {
                      val idx = ensureSegIdx()
                      coarseGrid.getOrPut(packCell(gx, gy)) { ArrayList(2) }.add(idx)
                    }
                  }
                } else {
                  fallbackLongSegs.add(ensureSegIdx())
                }
              }
            }
          }
          x1 = x2
          y1 = y2
        }
      }

      if (segs.isEmpty()) return@run

      data class SplitReq(
        val segIdx: Int,
        val t: Double,
        val splitNodeId: Long,
        val splitPoint: GeoPoint,
      )
      val splitsByWay = HashMap<Long, MutableList<SplitReq>>()

      for (wid in wayIdsNow) {
        val w = ways[wid] ?: continue
        if (w.points.size < 2 || w.nodeIds.size != w.points.size) continue
        if (isExcludedTransit(w.tags)) continue
        val layer = effectiveLayer(w.tags)
        val endpointIndices = intArrayOf(0, w.points.lastIndex)
        for (epIdx in endpointIndices) {
          val epNid = w.nodeIds[epIdx]
          val epPt = w.points[epIdx]
          val ex = toLocalX(epPt.lon)
          val ey = toLocalY(epPt.lat)
          if (!ex.isFinite() || !ey.isFinite()) continue
          val cx = floor(ex / GRID_CELL_METERS).toInt()
          val cy = floor(ey / GRID_CELL_METERS).toInt()
          val ccx = floor(ex / coarseCellMeters).toInt()
          val ccy = floor(ey / coarseCellMeters).toInt()

          var bestSeg: SegEntry? = null
          var bestT = 0.0
          var bestProjX = 0.0
          var bestProjY = 0.0
          var bestDistSq = SNAP_RADIUS_SQ + 1e-6

          fun considerSeg(sIdx: Int) {
            val seg = segs[sIdx]
            if (seg.wayId == wid) return
            if (epNid == seg.nid1 || epNid == seg.nid2) return

            val vx = seg.x2 - seg.x1
            val vy = seg.y2 - seg.y1
            val lenSq = vx * vx + vy * vy
            if (lenSq < 0.04) return
            val rawT = ((ex - seg.x1) * vx + (ey - seg.y1) * vy) / lenSq
            val t = rawT.coerceIn(0.0, 1.0)
            val px = seg.x1 + t * vx
            val py = seg.y1 + t * vy
            val ddx = ex - px
            val ddy = ey - py
            val dSq = ddx * ddx + ddy * ddy
            if (dSq > SNAP_RADIUS_SQ) return

            // Must hit interior of segment (away from segment's own endpoints; endpoints handled in pass (a))
            val distEnd1Sq = (px - seg.x1) * (px - seg.x1) + (py - seg.y1) * (py - seg.y1)
            val distEnd2Sq = (px - seg.x2) * (px - seg.x2) + (py - seg.y2) * (py - seg.y2)
            if (distEnd1Sq < 0.25 || distEnd2Sq < 0.25) return

            val targetWay = ways[seg.wayId] ?: return
            if (epNid in targetWay.nodeIds) return

            if (seg.layer != layer) {
              val pairKey = min(wid, seg.wayId) * 1000003L + max(wid, seg.wayId)
              if (mismatchPairs.add(pairKey)) rejectedLayerMismatches++
              return
            }

            if (dSq < bestDistSq) {
              bestDistSq = dSq
              bestSeg = seg
              bestT = t
              bestProjX = px
              bestProjY = py
            }
          }

          for (dy in -1..1) {
            for (dx in -1..1) {
              val fineBucket = sGrid[packCell(cx + dx, cy + dy)]
              if (fineBucket != null) {
                for (k in fineBucket.indices) considerSeg(fineBucket[k])
              }
              val coarseBucket = coarseGrid[packCell(ccx + dx, ccy + dy)]
              if (coarseBucket != null) {
                for (k in coarseBucket.indices) considerSeg(coarseBucket[k])
              }
            }
          }
          for (k in fallbackLongSegs.indices) {
            considerSeg(fallbackLongSegs[k])
          }

          if (bestSeg != null) {
            val splitPt = nodes[epNid]?.point ?: toGeoPoint(bestProjX, bestProjY)
            // Keep endpoint node at shared position if within tolerance; update node pos slightly if needed?
            // Keeping existing epNid and epPt ensures epWay endpoint is already merged cleanly!
            val list = splitsByWay.getOrPut(bestSeg.wayId) { ArrayList(2) }
            if (list.none { it.segIdx == bestSeg.segIdx && kotlin.math.abs(it.t - bestT) < 1e-4 }) {
              list += SplitReq(bestSeg.segIdx, bestT, epNid, splitPt)
            } else {
              // Another split request at nearly identical point -> merge endpoint node with that split node
              val existingSplit = list.first { it.segIdx == bestSeg.segIdx && kotlin.math.abs(it.t - bestT) < 1e-4 }
              if (existingSplit.splitNodeId != epNid) {
                applyNodeReplacements(mapOf(epNid to existingSplit.splitNodeId), nodes, ways, nodeToWays)
                vertexMerges++
              }
            }
          }
        }
      }

      if (splitsByWay.isNotEmpty()) {
        // Track highest counter per (ns, z, x, y) for injective < 2^20 way IDs
        val usedWayIds = ways.keys.toHashSet()
        val maxCounterByPrefix = HashMap<Long, Long>()
        for (wid in usedWayIds) {
          if (wid < 0L) {
            val mag = -wid
            val prefix = mag ushr 20
            val ctr = mag and (TileRoadGraphSource.MAX_WAY_COUNTER - 1L)
            val prev = maxCounterByPrefix[prefix] ?: -1L
            if (ctr > prev) maxCounterByPrefix[prefix] = ctr
          }
        }
        var maxPositiveId = usedWayIds.filter { it > 0L }.maxOrNull() ?: 1_000_000L

        fun allocateSplitWayId(baseWay: OsmWay): Long {
          if (baseWay.id < 0L) {
            val mag = -baseWay.id
            val ns = ((mag ushr 57) and 0x1FL).toInt()
            val z = ((mag ushr 52) and 0x1FL).toInt()
            val x = ((mag ushr 36) and 0xFFFFL).toInt()
            val y = ((mag ushr 20) and 0xFFFFL).toInt()
            if (ns in 1..31 && z in 0..TileRoadGraphSource.MAX_TILE_ZOOM) {
              val prefix = mag ushr 20
              var ctr = (maxCounterByPrefix[prefix] ?: 0L) + 1L
              while (ctr < TileRoadGraphSource.MAX_WAY_COUNTER) {
                val candidate = TileRoadGraphSource.encodeWayId(ns, z, x, y, ctr)
                if (candidate !in usedWayIds) {
                  maxCounterByPrefix[prefix] = ctr
                  usedWayIds += candidate
                  return candidate
                }
                ctr++
              }
            }
          }
          // Non-tile way (or rare counter exhaustion): allocate positive synthetic way ID outside negative tile space
          var cand = maxPositiveId + 1L
          while (cand in usedWayIds) cand++
          maxPositiveId = cand
          usedWayIds += cand
          return cand
        }

        for ((targetWid, reqs) in splitsByWay) {
          val origWay = ways[targetWid] ?: continue
          if (origWay.nodeIds.size < 2 || origWay.points.size != origWay.nodeIds.size) continue

          val sortedReqs = reqs.sortedWith(compareBy<SplitReq> { it.segIdx }.thenBy { it.t })
          val pieces = ArrayList<Pair<List<Long>, List<GeoPoint>>>(sortedReqs.size + 1)

          var curIds = ArrayList<Long>()
          var curPts = ArrayList<GeoPoint>()
          curIds += origWay.nodeIds[0]
          curPts += origWay.points[0]

          var reqPtr = 0
          for (sIdx in 0 until origWay.points.lastIndex) {
            while (reqPtr < sortedReqs.size && sortedReqs[reqPtr].segIdx == sIdx) {
              val r = sortedReqs[reqPtr++]
              if (curIds.lastOrNull() != r.splitNodeId) {
                curIds += r.splitNodeId
                curPts += r.splitPoint
              }
              if (curIds.size >= 2) {
                pieces += curIds to curPts
              }
              curIds = ArrayList()
              curPts = ArrayList()
              curIds += r.splitNodeId
              curPts += r.splitPoint
            }
            val nextId = origWay.nodeIds[sIdx + 1]
            val nextPt = origWay.points[sIdx + 1]
            if (curIds.lastOrNull() != nextId) {
              curIds += nextId
              curPts += nextPt
            }
          }
          if (curIds.size >= 2) {
            pieces += curIds to curPts
          }
          if (pieces.size < 2) continue

          // Replace original way with pieces[0] and allocate new injective way IDs for pieces[1..]
          for (nid in origWay.nodeIds.distinct()) {
            nodeToWays[nid]?.remove(targetWid)
          }

          val originTile = wayTileKeyOverrides?.get(targetWid) ?: TileRoadGraphSource.decodeTileKeyFromWayId(targetWid)
          for ((pIdx, piece) in pieces.withIndex()) {
            val newId = if (pIdx == 0) targetWid else allocateSplitWayId(origWay)
            val newWay = origWay.copy(id = newId, nodeIds = piece.first, points = piece.second)
            ways[newId] = newWay
            if (originTile != null && wayTileKeyOverrides != null) {
              wayTileKeyOverrides[newId] = originTile
            }
            for (nid in piece.first.distinct()) {
              val list = nodeToWays.getOrPut(nid) { ArrayList(3) }
              if (newId !in list) list.add(newId)
              if (!nodes.containsKey(nid)) {
                val pIdxInPiece = piece.first.indexOf(nid)
                if (pIdxInPiece >= 0) nodes[nid] = OsmNode(id = nid, point = piece.second[pIdxInPiece])
              }
            }
          }
          segmentSplits += (pieces.size - 1)
        }
      }
    }

    return PlanarizeStats(
      vertexMerges = vertexMerges,
      segmentSplits = segmentSplits,
      rejectedLayerMismatches = rejectedLayerMismatches,
    )
  }

  /**
   * Counts degree-1 endpoints within [radiusMeters] (default 30 m) of [corridorWorldPoints]
   * that remain disconnected inside loaded tiles (away from unloaded tile borders), useful for Diagnostics.
   */
  fun measureUnsnappedNearCorridor(
    graph: RoadGraph,
    corridorWorldPoints: List<GeoPoint>,
    radiusMeters: Double = 30.0,
  ): Pair<Int, List<String>> {
    if (corridorWorldPoints.size < 2 || graph.ways.isEmpty()) return 0 to emptyList()
    var count = 0
    val samples = ArrayList<String>(6)
    val seenNodes = HashSet<Long>()
    for ((wid, w) in graph.ways) {
      if (w.nodeIds.size < 2 || w.points.size != w.nodeIds.size) continue
      if (isExcludedTransit(w.tags)) continue
      val endpoints = intArrayOf(0, w.nodeIds.lastIndex)
      for (epIdx in endpoints) {
        val nid = w.nodeIds[epIdx]
        if (!seenNodes.add(nid)) continue
        val degree = graph.nodeToWays[nid]?.size ?: 0
        if (degree != 1) continue
        val pt = w.points[epIdx]
        val d = GeoMath.snapToPolyline(pt, corridorWorldPoints).distanceMeters
        if (d <= radiusMeters) {
          count++
          if (samples.size < 5) {
            samples += String.format(
              Locale.US,
              "%s@%.1fm(%.5f,%.5f,L%d)",
              w.tags.highway ?: "way",
              d,
              pt.lat,
              pt.lon,
              effectiveLayer(w.tags),
            )
          }
        }
      }
    }
    return count to samples
  }

  private fun applyNodeReplacements(
    replacements: Map<Long, Long>,
    nodes: MutableMap<Long, OsmNode>,
    ways: MutableMap<Long, OsmWay>,
    nodeToWays: MutableMap<Long, MutableList<Long>>,
  ) {
    if (replacements.isEmpty()) return
    val touchedWays = HashSet<Long>()
    for (oldId in replacements.keys) {
      nodeToWays[oldId]?.let { touchedWays.addAll(it) }
    }
    for (wid in touchedWays) {
      val w = ways[wid] ?: continue
      var changed = false
      val newIds = ArrayList<Long>(w.nodeIds.size)
      val newPts = ArrayList<GeoPoint>(w.points.size)
      for (idx in w.nodeIds.indices) {
        val origId = w.nodeIds[idx]
        val finalId = replacements[origId] ?: origId
        if (finalId != origId) changed = true
        val pt =
          if (finalId != origId) (nodes[finalId]?.point ?: w.points[idx])
          else w.points[idx]
        if (newIds.lastOrNull() != finalId) {
          newIds += finalId
          newPts += pt
        }
      }
      if (changed && newPts.size >= 2) {
        ways[wid] = w.copy(nodeIds = newIds, points = newPts)
      }
    }
    for ((oldId, keepId) in replacements) {
      val oldWays = nodeToWays.remove(oldId)
      if (oldWays != null) {
        val keepList = nodeToWays.getOrPut(keepId) { ArrayList(4) }
        for (wid in oldWays) {
          if (wid in ways && wid !in keepList) keepList.add(wid)
        }
      }
      nodes.remove(oldId)
    }
  }

  private fun clipSegmentToBox(
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    minX: Double,
    minY: Double,
    maxX: Double,
    maxY: Double,
    out: DoubleArray,
  ): Boolean {
    val dx = x2 - x1
    val dy = y2 - y1
    var t0 = 0.0
    var t1 = 1.0

    fun clipEdge(p: Double, q: Double): Boolean {
      if (p == 0.0) return q >= 0.0
      val r = q / p
      if (p < 0.0) {
        if (r > t1) return false
        if (r > t0) t0 = r
      } else {
        if (r < t0) return false
        if (r < t1) t1 = r
      }
      return true
    }

    if (!clipEdge(-dx, x1 - minX)) return false
    if (!clipEdge(dx, maxX - x1)) return false
    if (!clipEdge(-dy, y1 - minY)) return false
    if (!clipEdge(dy, maxY - y1)) return false

    out[0] = x1 + t0 * dx
    out[1] = y1 + t0 * dy
    out[2] = x1 + t1 * dx
    out[3] = y1 + t1 * dy
    return true
  }

  private inline fun visitSupercoverGridCells(
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    cellSize: Double,
    visitor: (cx: Int, cy: Int) -> Unit,
  ) {
    val startCx = floor(x1 / cellSize).toInt()
    val startCy = floor(y1 / cellSize).toInt()
    val endCx = floor(x2 / cellSize).toInt()
    val endCy = floor(y2 / cellSize).toInt()

    var cx = startCx
    var cy = startCy
    visitor(cx, cy)
    if (cx == endCx && cy == endCy) return

    val dx = x2 - x1
    val dy = y2 - y1
    val stepX = if (endCx >= startCx) 1 else -1
    val stepY = if (endCy >= startCy) 1 else -1

    var tMaxX =
      if (kotlin.math.abs(dx) < 1e-12) {
        Double.POSITIVE_INFINITY
      } else {
        val nextBx = (cx + if (dx > 0.0) 1 else 0) * cellSize
        ((nextBx - x1) / dx).coerceAtLeast(0.0)
      }
    var tMaxY =
      if (kotlin.math.abs(dy) < 1e-12) {
        Double.POSITIVE_INFINITY
      } else {
        val nextBy = (cy + if (dy > 0.0) 1 else 0) * cellSize
        ((nextBy - y1) / dy).coerceAtLeast(0.0)
      }

    val tDeltaX = if (kotlin.math.abs(dx) < 1e-12) Double.POSITIVE_INFINITY else kotlin.math.abs(cellSize / dx)
    val tDeltaY = if (kotlin.math.abs(dy) < 1e-12) Double.POSITIVE_INFINITY else kotlin.math.abs(cellSize / dy)

    val maxSteps = kotlin.math.abs(endCx - startCx) + kotlin.math.abs(endCy - startCy) + 4
    var steps = 0
    while ((cx != endCx || cy != endCy) && steps++ < maxSteps) {
      val diff = tMaxX - tMaxY
      if (kotlin.math.abs(diff) <= 1e-9 && tMaxX.isFinite() && tMaxY.isFinite()) {
        val movedX = cx != endCx
        val movedY = cy != endCy
        if (movedX) {
          cx += stepX
          tMaxX += tDeltaX
          visitor(cx, cy)
        }
        if (movedY) {
          cy += stepY
          tMaxY += tDeltaY
          if (movedX) visitor(cx - stepX, cy)
          visitor(cx, cy)
        }
      } else if (tMaxX < tMaxY && cx != endCx || cy == endCy) {
        cx += stepX
        tMaxX += tDeltaX
        visitor(cx, cy)
      } else {
        cy += stepY
        tMaxY += tDeltaY
        visitor(cx, cy)
      }
    }
    if (cx != endCx || cy != endCy) {
      visitor(endCx, endCy)
    }
  }

  private class LongHashSet(expectedElements: Int) {
    private var table: LongArray
    private var mask: Int
    private var size = 0
    private var resizeThreshold: Int
    private var hasMinValue = false

    init {
      var cap = 64
      val needed = (expectedElements.coerceAtLeast(16) * 2)
      while (cap < needed) cap = cap shl 1
      table = LongArray(cap) { EMPTY }
      mask = cap - 1
      resizeThreshold = (cap * 2) / 3
    }

    fun add(key: Long) {
      if (key == EMPTY) {
        hasMinValue = true
        return
      }
      if (size >= resizeThreshold) rehash()
      var idx = mix64(key) and mask
      while (true) {
        val cur = table[idx]
        if (cur == EMPTY) {
          table[idx] = key
          size++
          return
        }
        if (cur == key) return
        idx = (idx + 1) and mask
      }
    }

    fun contains(key: Long): Boolean {
      if (key == EMPTY) return hasMinValue
      var idx = mix64(key) and mask
      while (true) {
        val cur = table[idx]
        if (cur == EMPTY) return false
        if (cur == key) return true
        idx = (idx + 1) and mask
      }
    }

    private fun rehash() {
      val old = table
      val newCap = old.size shl 1
      val next = LongArray(newCap) { EMPTY }
      val newMask = newCap - 1
      for (k in old) {
        if (k == EMPTY) continue
        var idx = mix64(k) and newMask
        while (next[idx] != EMPTY) {
          idx = (idx + 1) and newMask
        }
        next[idx] = k
      }
      table = next
      mask = newMask
      resizeThreshold = (newCap * 2) / 3
    }

    private fun mix64(z: Long): Int {
      var x = z
      x = (x xor (x ushr 33)) * -0xae502812aa7333L
      x = (x xor (x ushr 33)) * -0x3b314601e57a13adL
      x = x xor (x ushr 33)
      return x.toInt()
    }

    companion object {
      private const val EMPTY = Long.MIN_VALUE
    }
  }
}
