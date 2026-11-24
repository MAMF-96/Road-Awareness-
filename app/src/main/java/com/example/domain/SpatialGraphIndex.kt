package com.example.domain

import com.example.data.model.GeoPoint
import com.example.data.model.OsmWay
import com.example.data.model.RoadGraph
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Fast spatial hash grid over a [RoadGraph]'s ways & nodes.
 * Uses 0.002° (~222m latitude) grid cells with antimeridian lon wrapping and cos(latitude) radius scaling.
 */
class SpatialGraphIndex private constructor(
  val graph: RoadGraph,
  val graphVersion: Long,
  val sourceLabel: String,
  private val cellToWayIds: Map<Long, LongArray>,
  private val wayBboxes: Map<Long, WayBbox>,
  private val cellToNodeIds: Map<Long, LongArray>,
) {
  val edgeCount: Int get() = graph.ways.size
  val nodeCount: Int get() = graph.nodes.size

  data class WayBbox(
    val minLat: Double,
    val maxLat: Double,
    val refLon: Double,
    val minDx: Double,
    val maxDx: Double,
  )

  data class NearestWayHit(
    val way: OsmWay,
    val distanceMeters: Double,
    val snappedPoint: GeoPoint,
    val segmentIndex: Int,
  )

  data class LocalStats(
    val nodes500: Int,
    val edges500: Int,
    val nodes1500: Int,
    val edges1500: Int,
    val nearest: NearestWayHit?,
  )

  fun queryWaysAround(center: GeoPoint, radiusMeters: Double): List<OsmWay> {
    if (graph.ways.isEmpty()) return emptyList()
    val r = radiusMeters.coerceAtLeast(1.0)
    val dLat = r / METERS_PER_DEG_LAT
    val cosLat = cos(Math.toRadians(center.lat)).coerceAtLeast(0.15)
    val dLon = (r / (METERS_PER_DEG_LAT * cosLat)).coerceAtMost(45.0)

    val minCellLat = latToCell(center.lat - dLat)
    val maxCellLat = latToCell(center.lat + dLat)
    val minCellLon = floor((GeoMath.normalizeLon(center.lon) - dLon) / GRID_DEG).toInt()
    val maxCellLon = floor((GeoMath.normalizeLon(center.lon) + dLon) / GRID_DEG).toInt()

    val seenIds = HashSet<Long>(32)
    val result = ArrayList<OsmWay>(32)

    for (cy in minCellLat..maxCellLat) {
      for (rawCx in minCellLon..maxCellLon) {
        val cx = wrapLonCell(rawCx)
        val bucket = cellToWayIds[packCell(cy, cx)] ?: continue
        for (wayId in bucket) {
          if (!seenIds.add(wayId)) continue
          val bbox = wayBboxes[wayId]
          if (bbox != null) {
            if (center.lat + dLat < bbox.minLat || center.lat - dLat > bbox.maxLat) continue
            val dxCenter = GeoMath.unwrapLonDelta(center.lon - bbox.refLon)
            if (dxCenter + dLon < bbox.minDx || dxCenter - dLon > bbox.maxDx) continue
          }
          val w = graph.ways[wayId] ?: continue
          result += w
        }
      }
    }
    return result
  }

  fun queryNearestWay(center: GeoPoint, maxSearchMeters: Double = 1500.0): NearestWayHit? {
    if (graph.ways.isEmpty()) return null
    val steps = doubleArrayOf(60.0, 180.0, 500.0, maxSearchMeters.coerceAtLeast(500.0))
    for (r in steps) {
      val cands = queryWaysAround(center, r)
      if (cands.isEmpty()) continue
      var best: NearestWayHit? = null
      for (way in cands) {
        if (way.points.size < 2) continue
        val snap = GeoMath.snapToPolyline(center, way.points)
        if (best == null || snap.distanceMeters < best.distanceMeters) {
          best =
            NearestWayHit(
              way = way,
              distanceMeters = snap.distanceMeters,
              snappedPoint = snap.snappedPoint,
              segmentIndex = snap.segmentIndex,
            )
        }
      }
      if (best != null && best.distanceMeters <= r) {
        return best
      }
    }
    return null
  }

  fun computeLocalStats(center: GeoPoint): LocalStats {
    if (graph.ways.isEmpty() && graph.nodes.isEmpty()) {
      return LocalStats(0, 0, 0, 0, null)
    }
    val dLat = 1500.0 / METERS_PER_DEG_LAT
    val cosLat = cos(Math.toRadians(center.lat)).coerceAtLeast(0.15)
    val dLon = (1500.0 / (METERS_PER_DEG_LAT * cosLat)).coerceAtMost(45.0)
    val minCellLat = latToCell(center.lat - dLat)
    val maxCellLat = latToCell(center.lat + dLat)
    val minCellLon = floor((GeoMath.normalizeLon(center.lon) - dLon) / GRID_DEG).toInt()
    val maxCellLon = floor((GeoMath.normalizeLon(center.lon) + dLon) / GRID_DEG).toInt()

    var nodes500 = 0
    var nodes1500 = 0
    val seenNodes = HashSet<Long>(256)
    for (cy in minCellLat..maxCellLat) {
      for (rawCx in minCellLon..maxCellLon) {
        val bucket = cellToNodeIds[packCell(cy, wrapLonCell(rawCx))] ?: continue
        for (nid in bucket) {
          if (!seenNodes.add(nid)) continue
          val node = graph.nodes[nid] ?: continue
          val d = GeoMath.haversineMeters(center, node.point)
          if (d <= 500.0) nodes500++
          if (d <= 1500.0) nodes1500++
        }
      }
    }

    var edges500 = 0
    var edges1500 = 0
    var nearest: NearestWayHit? = null
    val ways1500 = queryWaysAround(center, 1500.0)
    for (way in ways1500) {
      if (way.points.size < 2) continue
      val snap = GeoMath.snapToPolyline(center, way.points)
      if (snap.distanceMeters <= 500.0) edges500++
      if (snap.distanceMeters <= 1500.0) edges1500++
      if (nearest == null || snap.distanceMeters < nearest.distanceMeters) {
        nearest =
          NearestWayHit(
            way = way,
            distanceMeters = snap.distanceMeters,
            snappedPoint = snap.snappedPoint,
            segmentIndex = snap.segmentIndex,
          )
      }
    }
    return LocalStats(
      nodes500 = nodes500,
      edges500 = edges500,
      nodes1500 = nodes1500,
      edges1500 = edges1500,
      nearest = nearest,
    )
  }

  companion object {
    private const val GRID_DEG = 0.002 // ~222 m in latitude
    private const val METERS_PER_DEG_LAT = 111_320.0
    private val LON_CELLS_FULL_WRAP = (360.0 / GRID_DEG).toInt() // 180,000

    fun build(
      graph: RoadGraph,
      graphVersion: Long = 0L,
      sourceLabel: String = graph.source.name.lowercase(),
    ): SpatialGraphIndex {
      val wayBuckets = HashMap<Long, MutableList<Long>>(graph.ways.size * 2)
      val waySeenInCell = HashMap<Long, HashSet<Long>>(graph.ways.size)
      val bboxes = HashMap<Long, WayBbox>(graph.ways.size)

      for (way in graph.ways.values) {
        val pts = way.points
        if (pts.size < 2) continue
        val refLon = pts[0].lon
        var wMinLat = 90.0
        var wMaxLat = -90.0
        var wMinDx = Double.MAX_VALUE
        var wMaxDx = -Double.MAX_VALUE

        for (i in 0 until pts.size) {
          val p = pts[i]
          if (p.lat < wMinLat) wMinLat = p.lat
          if (p.lat > wMaxLat) wMaxLat = p.lat
          val dx = GeoMath.unwrapLonDelta(p.lon - refLon)
          if (dx < wMinDx) wMinDx = dx
          if (dx > wMaxDx) wMaxDx = dx
        }
        bboxes[way.id] = WayBbox(wMinLat, wMaxLat, refLon, wMinDx, wMaxDx)

        // Index each segment into grid cells so long roads stay tight in buckets
        val insertedCells = HashSet<Long>(pts.size * 2)
        for (i in 0 until pts.lastIndex) {
          val a = pts[i]
          val b = pts[i + 1]
          val sMinLat = min(a.lat, b.lat)
          val sMaxLat = max(a.lat, b.lat)
          val cy0 = latToCell(sMinLat)
          val cy1 = latToCell(sMaxLat)

          val lonA = GeoMath.normalizeLon(a.lon)
          val dxAB = GeoMath.unwrapLonDelta(b.lon - a.lon)
          val minL = min(lonA, lonA + dxAB)
          val maxL = max(lonA, lonA + dxAB)
          val cx0 = floor(minL / GRID_DEG).toInt()
          val cx1 = floor(maxL / GRID_DEG).toInt()
          // Clamp pathological segment span
          if (cx1 - cx0 > 400 || cy1 - cy0 > 400) continue

          for (cy in cy0..cy1) {
            for (rawCx in cx0..cx1) {
              val key = packCell(cy, wrapLonCell(rawCx))
              if (insertedCells.add(key)) {
                wayBuckets.getOrPut(key) { ArrayList(8) }.add(way.id)
              }
            }
          }
        }
      }

      val nodeBuckets = HashMap<Long, MutableList<Long>>(graph.nodes.size)
      for (node in graph.nodes.values) {
        val cy = latToCell(node.point.lat)
        val cx = wrapLonCell(floor(GeoMath.normalizeLon(node.point.lon) / GRID_DEG).toInt())
        val key = packCell(cy, cx)
        nodeBuckets.getOrPut(key) { ArrayList(12) }.add(node.id)
      }

      val packedWayBuckets = HashMap<Long, LongArray>(wayBuckets.size)
      for ((k, list) in wayBuckets) {
        packedWayBuckets[k] = list.toLongArray()
      }
      val packedNodeBuckets = HashMap<Long, LongArray>(nodeBuckets.size)
      for ((k, list) in nodeBuckets) {
        packedNodeBuckets[k] = list.toLongArray()
      }

      return SpatialGraphIndex(
        graph = graph,
        graphVersion = graphVersion,
        sourceLabel = sourceLabel,
        cellToWayIds = packedWayBuckets,
        wayBboxes = bboxes,
        cellToNodeIds = packedNodeBuckets,
      )
    }

    private fun latToCell(lat: Double): Int =
      floor(lat.coerceIn(-89.9, 89.9) / GRID_DEG).toInt()

    private fun wrapLonCell(rawCellX: Int): Int {
      val m = rawCellX % LON_CELLS_FULL_WRAP
      return if (m < 0) m + LON_CELLS_FULL_WRAP else m
    }

    private fun packCell(cy: Int, cx: Int): Long =
      (cy.toLong() shl 32) or (cx.toLong() and 0xFFFFFFFFL)
  }
}
