package com.example.data.repository

import com.example.config.AppConfig
import com.example.data.model.BorderStitchStats
import com.example.data.model.DecodedTileArtifact
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.LoadedTileBounds
import com.example.data.model.OsmNode
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.PoiItem
import com.example.data.model.RoadGraph
import com.example.data.model.TileNameLabelRecord
import com.example.data.remote.TileRoadGraphSource
import com.example.domain.GeoMath
import com.example.domain.SpatialGraphIndex
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single authoritative in-memory road-graph repository fed by tiles, Overpass cells, and live map fallback.
 * Maintains a rolling keep-window, LRU eviction cap, unified spatial hash index, and monotonic [version]
 * so MapMatcher, AheadGraphAnalyzer, Search, and Pipeline Diagnostics always read the exact same snapshot.
 */
class RoadGraphRepository(
  private val maxMemoryTiles: Int = AppConfig.MAX_MEMORY_TILES,
  private val maxOverpassCells: Int = AppConfig.MAX_MEMORY_OVERPASS_CELLS,
) {
  data class GraphSnapshot(
    val version: Long,
    val graph: RoadGraph,
    val index: SpatialGraphIndex,
    val sourceLabel: String,
    val publishedAtMs: Long,
    val memoryTileCount: Int,
    val overpassCellCount: Int,
    val memoryHits: Long,
    val memoryMisses: Long,
    val evictions: Int,
    val tileFetchedAtMs: Map<String, Long>,
    val emptyDecodedTileKeys: Set<String>,
    val pois: List<PoiItem> = emptyList(),
    val labelsByTile: Map<String, List<TileNameLabelRecord>> = emptyMap(),
    val overpassEdgeCount: Int = 0,
    val tileEdgeCount: Int = 0,
    val idCollisions: Int = 0,
    val danglingNodeRefs: Int = 0,
    val waysPerTileMin: Int = 0,
    val waysPerTileAvg: Double = 0.0,
    val waysPerTileMax: Int = 0,
    val mapFallbackUsed: Boolean = false,
    val mapFallbackWaysAdded: Int = 0,
    val hasPrimaryEdgeWithin150m: Boolean = false,
    val borderStitchStats: BorderStitchStats = BorderStitchStats(),
  ) {
    fun oldestAgeUnderPoint(point: GeoPoint): Long? {
      val matching =
        graph.loadedTiles.filter { b ->
          point.lat in b.south..b.north &&
            GeoMath.unwrapLonDelta(point.lon - b.west) >= -1e-6 &&
            GeoMath.unwrapLonDelta(b.east - point.lon) >= -1e-6
        }
      val ts =
        matching.mapNotNull { b -> tileFetchedAtMs["${b.z}_${b.x}_${b.y}"] }.minOrNull()
          ?: tileFetchedAtMs.values.minOrNull()
      return ts?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
    }

    fun isCenterTileDecodedEmpty(centerTileKey: String): Boolean =
      centerTileKey in emptyDecodedTileKeys

    fun allLabelsNear(point: GeoPoint): List<TileNameLabelRecord> {
      if (labelsByTile.isEmpty()) return emptyList()
      val res = ArrayList<TileNameLabelRecord>(64)
      for ((k, list) in labelsByTile) {
        if (list.isEmpty()) continue
        // Include any tile within rough ~0.04 deg (~4km) of point
        val sample = list.firstOrNull()?.points?.firstOrNull()
        if (sample == null || (abs(sample.lat - point.lat) <= 0.05 && abs(GeoMath.unwrapLonDelta(sample.lon - point.lon)) <= 0.06)) {
          res.addAll(list)
        } else if (k in emptyDecodedTileKeys) {
          continue
        }
      }
      return res
    }
  }

  private data class StoredTileEntry(
    val tileKey: String,
    val bounds: LoadedTileBounds,
    val graph: RoadGraph,
    val fetchedAtMs: Long,
    val isEmpty: Boolean,
    val labels: List<TileNameLabelRecord>,
    val pois: List<PoiItem>,
    var lastAccessMs: Long,
  )

  private data class StoredOverpassEntry(
    val cellKey: String,
    val center: GeoPoint,
    val graph: RoadGraph,
    val fetchedAtMs: Long,
    val pois: List<PoiItem>,
    var lastAccessMs: Long,
  )

  private val lock = Any()
  private val tileEntries = LinkedHashMap<String, StoredTileEntry>(36, 0.75f, true)
  private val overpassEntries = LinkedHashMap<String, StoredOverpassEntry>(48, 0.75f, true)
  private var mapFallbackGraph: RoadGraph? = null
  private var currentKeepTiles: Set<String> = emptySet()
  private var currentKeepCenter: GeoPoint? = null
  private var currentFallbackEvalCenter: GeoPoint? = null
  private var lastFallbackEvalPublishedCenter: GeoPoint? = null
  private var currentKeepHorizonMeters: Double = 3000.0
  private var versionCounter: Long = 0L
  private var memoryHits: Long = 0L
  private var memoryMisses: Long = 0L
  private var evictionCount: Int = 0

  private val emptyGraph =
    RoadGraph(
      nodes = emptyMap(),
      ways = emptyMap(),
      nodeToWays = emptyMap(),
      fetchedAtMs = 0L,
      fromCache = false,
      source = GraphDataSource.TILES,
      loadedTiles = emptyList(),
    )

  private val initialSnapshot =
    GraphSnapshot(
      version = 0L,
      graph = emptyGraph,
      index = SpatialGraphIndex.build(emptyGraph, 0L, "none"),
      sourceLabel = "none",
      publishedAtMs = 0L,
      memoryTileCount = 0,
      overpassCellCount = 0,
      memoryHits = 0L,
      memoryMisses = 0L,
      evictions = 0,
      tileFetchedAtMs = emptyMap(),
      emptyDecodedTileKeys = emptySet(),
    )

  private val _snapshot = MutableStateFlow(initialSnapshot)
  val snapshotFlow: StateFlow<GraphSnapshot> = _snapshot.asStateFlow()

  fun currentSnapshot(): GraphSnapshot = _snapshot.value

  fun hasTileInMemory(tileKey: String): Boolean =
    synchronized(lock) { tileEntries.containsKey(tileKey) }

  fun getMemoryTileKeys(): Set<String> =
    synchronized(lock) { tileEntries.keys.toHashSet() }

  fun recordMemoryCacheProbe(hit: Boolean) {
    synchronized(lock) {
      if (hit) memoryHits++ else memoryMisses++
    }
  }

  fun updateKeepWindow(
    center: GeoPoint,
    horizonMeters: Double,
    keepTileKeys: Set<String>,
  ): GraphSnapshot =
    synchronized(lock) {
      currentKeepCenter = center
      currentFallbackEvalCenter = center
      currentKeepHorizonMeters = horizonMeters
      currentKeepTiles = keepTileKeys
      val evictedAny = evictBeyondWindowAndCapsLocked()
      if (evictedAny) {
        rebuildAndPublishLocked()
      } else {
        _snapshot.value
      }
    }

  fun upsertTileGraph(
    tileKey: String,
    bounds: LoadedTileBounds,
    tileGraph: RoadGraph,
    fetchedAtMs: Long,
    isEmptyDecoded: Boolean,
    labels: List<TileNameLabelRecord> = emptyList(),
    pois: List<PoiItem> = emptyList(),
  ): GraphSnapshot =
    synchronized(lock) {
      val now = System.currentTimeMillis()
      tileEntries[tileKey] =
        StoredTileEntry(
          tileKey = tileKey,
          bounds = bounds,
          graph = tileGraph,
          fetchedAtMs = fetchedAtMs,
          isEmpty = isEmptyDecoded,
          labels = labels,
          pois = pois,
          lastAccessMs = now,
        )
      evictBeyondWindowAndCapsLocked()
      rebuildAndPublishLocked()
    }

  fun upsertDecodedArtifacts(
    artifacts: List<DecodedTileArtifact>,
  ): GraphSnapshot =
    synchronized(lock) {
      val now = System.currentTimeMillis()
      for (art in artifacts) {
        tileEntries[art.tileKey] =
          StoredTileEntry(
            tileKey = art.tileKey,
            bounds = art.bounds,
            graph = art.graph,
            fetchedAtMs = art.fetchedAtMs,
            isEmpty = art.isEmptyDecoded,
            labels = art.labels,
            pois = art.pois,
            lastAccessMs = now,
          )
      }
      evictBeyondWindowAndCapsLocked()
      rebuildAndPublishLocked()
    }

  fun isTileFreshInMemory(tileKey: String, staleAgeMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
    synchronized(lock) {
      val e = tileEntries[tileKey] ?: return@synchronized false
      (nowMs - e.fetchedAtMs) < staleAgeMs
    }

  fun upsertTileGraphsBatch(
    tiles: List<TileBatchItem>,
  ): GraphSnapshot =
    synchronized(lock) {
      val now = System.currentTimeMillis()
      for (t in tiles) {
        tileEntries[t.tileKey] =
          StoredTileEntry(
            tileKey = t.tileKey,
            bounds = t.bounds,
            graph = t.graph,
            fetchedAtMs = t.fetchedAtMs,
            isEmpty = t.isEmptyDecoded,
            labels = t.labels,
            pois = t.pois,
            lastAccessMs = now,
          )
      }
      evictBeyondWindowAndCapsLocked()
      rebuildAndPublishLocked()
    }

  data class TileBatchItem(
    val tileKey: String,
    val bounds: LoadedTileBounds,
    val graph: RoadGraph,
    val fetchedAtMs: Long,
    val isEmptyDecoded: Boolean,
    val labels: List<TileNameLabelRecord> = emptyList(),
    val pois: List<PoiItem> = emptyList(),
  )

  fun upsertOverpassCell(
    cellKey: String,
    cellCenter: GeoPoint,
    cellGraph: RoadGraph,
    fetchedAtMs: Long,
    pois: List<PoiItem> = emptyList(),
  ): GraphSnapshot =
    synchronized(lock) {
      overpassEntries[cellKey] =
        StoredOverpassEntry(
          cellKey = cellKey,
          center = cellCenter,
          graph = cellGraph,
          fetchedAtMs = fetchedAtMs,
          pois = pois,
          lastAccessMs = System.currentTimeMillis(),
        )
      evictOverpassBeyondCapsLocked()
      rebuildAndPublishLocked()
    }

  fun setMapFallbackGraph(
    graph: RoadGraph?,
    evalCenter: GeoPoint? = null,
  ): GraphSnapshot =
    synchronized(lock) {
      mapFallbackGraph = graph
      if (evalCenter != null) {
        currentFallbackEvalCenter = evalCenter
      }
      rebuildAndPublishLocked()
    }

  fun reevaluateFallbackForPosition(
    center: GeoPoint,
    minMoveMeters: Double = 50.0,
  ): GraphSnapshot? =
    synchronized(lock) {
      currentFallbackEvalCenter = center
      val fallback = mapFallbackGraph ?: return@synchronized null
      if (fallback.ways.isEmpty()) return@synchronized null
      val lastEval = lastFallbackEvalPublishedCenter
      val moved = lastEval?.let { GeoMath.haversineMeters(it, center) } ?: Double.MAX_VALUE
      if (moved < minMoveMeters) return@synchronized null
      val curSnap = _snapshot.value
      val primaryNear =
        hasPrimaryWayWithin150mLocked(center)
      if (!primaryNear || curSnap.mapFallbackUsed) {
        rebuildAndPublishLocked()
      } else {
        null
      }
    }

  fun clear(): GraphSnapshot =
    synchronized(lock) {
      tileEntries.clear()
      overpassEntries.clear()
      mapFallbackGraph = null
      currentKeepTiles = emptySet()
      currentKeepCenter = null
      currentFallbackEvalCenter = null
      lastFallbackEvalPublishedCenter = null
      evictionCount = 0
      memoryHits = 0L
      memoryMisses = 0L
      rebuildAndPublishLocked()
    }

  private fun evictBeyondWindowAndCapsLocked(): Boolean {
    var evicted = false
    val center = currentKeepCenter
    val maxDistM = max(currentKeepHorizonMeters + 1200.0, 2500.0)

    if (center != null && currentKeepTiles.isNotEmpty() && tileEntries.size > maxMemoryTiles / 2) {
      val toRemove = ArrayList<String>()
      for ((k, entry) in tileEntries) {
        if (k in currentKeepTiles) continue
        val midLat = (entry.bounds.south + entry.bounds.north) * 0.5
        val midLon = GeoMath.normalizeLon(entry.bounds.west + GeoMath.unwrapLonDelta(entry.bounds.east - entry.bounds.west) * 0.5)
        val dist = GeoMath.haversineMeters(center, GeoPoint(midLat, midLon))
        if (dist > maxDistM) {
          toRemove += k
        }
      }
      for (k in toRemove) {
        tileEntries.remove(k)
        evictionCount++
        evicted = true
      }
    }

    while (tileEntries.size > maxMemoryTiles) {
      val evictCandidate =
        tileEntries.keys.firstOrNull { it !in currentKeepTiles }
          ?: tileEntries.keys.firstOrNull()
          ?: break
      tileEntries.remove(evictCandidate)
      evictionCount++
      evicted = true
    }
    return evicted
  }

  private fun evictOverpassBeyondCapsLocked() {
    val center = currentKeepCenter
    val maxDistM = max(currentKeepHorizonMeters + 1200.0, 2500.0)
    if (center != null) {
      val iter = overpassEntries.entries.iterator()
      while (iter.hasNext()) {
        val e = iter.next()
        if (GeoMath.haversineMeters(center, e.value.center) > maxDistM) {
          iter.remove()
        }
      }
    }
    while (overpassEntries.size > maxOverpassCells) {
      val firstKey = overpassEntries.keys.firstOrNull() ?: break
      overpassEntries.remove(firstKey)
    }
  }

  private fun hasPrimaryWayWithin150mLocked(center: GeoPoint): Boolean {
    for (entry in tileEntries.values) {
      if (hasWayWithinRadius(entry.graph.ways.values, center, 150.0)) return true
    }
    for (cell in overpassEntries.values) {
      if (hasWayWithinRadius(cell.graph.ways.values, center, 150.0)) return true
    }
    return false
  }

  private fun hasWayWithinRadius(
    ways: Collection<OsmWay>,
    center: GeoPoint,
    radiusMeters: Double,
  ): Boolean {
    if (ways.isEmpty()) return false
    val marginLat = (radiusMeters + 50.0) / 111_320.0
    val cosLat = kotlin.math.cos(Math.toRadians(center.lat)).coerceAtLeast(0.15)
    val marginLon = (radiusMeters + 50.0) / (111_320.0 * cosLat)
    for (w in ways) {
      if (w.points.size < 2) continue
      var minLat = 90.0
      var maxLat = -90.0
      var minLon = 180.0
      var maxLon = -180.0
      for (p in w.points) {
        if (p.lat < minLat) minLat = p.lat
        if (p.lat > maxLat) maxLat = p.lat
        if (p.lon < minLon) minLon = p.lon
        if (p.lon > maxLon) maxLon = p.lon
      }
      if (center.lat < minLat - marginLat || center.lat > maxLat + marginLat) continue
      if (maxLon - minLon < 180.0) {
        val normLon = GeoMath.normalizeLon(center.lon)
        if (normLon < minLon - marginLon || normLon > maxLon + marginLon) continue
      }
      if (GeoMath.snapToPolyline(center, w.points).distanceMeters <= radiusMeters) {
        return true
      }
    }
    return false
  }

  private fun rebuildAndPublishLocked(): GraphSnapshot {
    val mergedNodes = HashMap<Long, OsmNode>(4096)
    val mergedWays = HashMap<Long, OsmWay>(2048)
    val wayOriginTileById = HashMap<Long, String>(2048)
    val nodeToWays = HashMap<Long, MutableList<Long>>(4096)
    val loadedTiles = ArrayList<LoadedTileBounds>(tileEntries.size)
    val tileTimestamps = HashMap<String, Long>(tileEntries.size)
    val emptyKeys = HashSet<String>()
    val labelsByTile = HashMap<String, List<TileNameLabelRecord>>(tileEntries.size)
    val allPois = LinkedHashMap<Long, PoiItem>(256)
    val tileWayCounts = ArrayList<Int>(tileEntries.size)
    var latestFetchedAt = 0L
    var allFromCache = true
    var tileEdgeCount = 0
    var overpassEdgeCount = 0
    var idCollisions = 0

    // 1. Tile baseline (all decoded z14 tiles)
    for ((k, entry) in tileEntries) {
      loadedTiles += entry.bounds
      tileTimestamps[k] = entry.fetchedAtMs
      if (entry.isEmpty) emptyKeys += k
      if (entry.labels.isNotEmpty()) labelsByTile[k] = entry.labels
      for (p in entry.pois) allPois[p.id] = p
      latestFetchedAt = max(latestFetchedAt, entry.fetchedAtMs)
      if (!entry.graph.fromCache) allFromCache = false
      tileWayCounts += entry.graph.ways.size

      mergedNodes.putAll(entry.graph.nodes)
      for ((wId, way) in entry.graph.ways) {
        val prevWay = mergedWays[wId]
        val prevTile = wayOriginTileById[wId]
        if (prevWay != null && (prevWay.source != way.source || (prevTile != null && prevTile != k))) {
          idCollisions++
        }
        mergedWays[wId] = way
        wayOriginTileById[wId] = k
        tileEdgeCount++
        for (nid in way.nodeIds.distinct()) {
          val list = nodeToWays.getOrPut(nid) { ArrayList(3) }
          if (wId !in list) list.add(wId)
        }
      }
    }

    val borderStitchStats =
      if (loadedTiles.isNotEmpty()) {
        TileRoadGraphSource.stitchCrossTileBordersAndComputeStats(
          nodes = mergedNodes,
          ways = mergedWays,
          nodeToWays = nodeToWays,
          loadedTiles = loadedTiles,
          wayTileKeyOverrides = wayOriginTileById,
        )
      } else {
        BorderStitchStats()
      }

    val waysPerTileMin = if (tileWayCounts.isEmpty()) 0 else tileWayCounts.min()
    val waysPerTileMax = if (tileWayCounts.isEmpty()) 0 else tileWayCounts.max()
    val waysPerTileAvg = if (tileWayCounts.isEmpty()) 0.0 else tileWayCounts.sum().toDouble() / tileWayCounts.size.toDouble()

    // 2. Overpass enrichment (Overpass enriches overlapping tile tags + adds missing ways without deleting)
    var hasOverpass = false
    if (overpassEntries.isNotEmpty()) {
      val baseIndex =
        if (mergedWays.isNotEmpty()) {
          val tempBase = RoadGraph(mergedNodes, mergedWays, nodeToWays, latestFetchedAt, allFromCache, GraphDataSource.TILES, loadedTiles)
          SpatialGraphIndex.build(tempBase, versionCounter, "tiles")
        } else {
          null
        }
      for ((_, cell) in overpassEntries) {
        for (p in cell.pois) allPois[p.id] = p
        if (cell.graph.ways.isEmpty() && cell.graph.nodes.isEmpty()) continue
        hasOverpass = true
        latestFetchedAt = max(latestFetchedAt, cell.fetchedAtMs)
        if (!cell.graph.fromCache) allFromCache = false

        // Keep Overpass node tags
        for ((nid, oNode) in cell.graph.nodes) {
          val existing = mergedNodes[nid]
          mergedNodes[nid] =
            if (existing != null && !oNode.tags.hasAnyTag()) existing else oNode
        }

        val toReplaceTileIds = HashSet<Long>()
        val enrichedOvWays = ArrayList<OsmWay>(cell.graph.ways.size)

        for ((_, oWay) in cell.graph.ways) {
          var mergedTags = oWay.tags
          if (baseIndex != null && oWay.points.size >= 2) {
            val oBearing = GeoMath.bearingDeg(oWay.points.first(), oWay.points.last())
            val midPt = oWay.points[oWay.points.size / 2]
            val nearbyBaseWays = baseIndex.queryWaysAround(midPt, 24.0)
            for (bWay in nearbyBaseWays) {
              if (bWay.points.size < 2 || bWay.id !in mergedWays) continue
              val bMid = bWay.points[bWay.points.size / 2]
              val d = GeoMath.snapToPolyline(bMid, oWay.points).distanceMeters
              if (d <= 16.0) {
                val bBearing = GeoMath.bearingDeg(bWay.points.first(), bWay.points.last())
                val delta = GeoMath.absAngleDiff(bBearing, oBearing)
                if (delta <= 28.0 || abs(delta - 180.0) <= 28.0) {
                  mergedTags = mergeTagsPreferOverpass(bWay.tags, mergedTags)
                  toReplaceTileIds += bWay.id
                }
              }
            }
          }
          enrichedOvWays += if (mergedTags != oWay.tags) oWay.copy(tags = mergedTags) else oWay
        }

        for (oldId in toReplaceTileIds) {
          val removed = mergedWays.remove(oldId) ?: continue
          wayOriginTileById.remove(oldId)
          for (nid in removed.nodeIds.distinct()) {
            nodeToWays[nid]?.remove(oldId)
          }
        }
        for (oWay in enrichedOvWays) {
          val prev = mergedWays[oWay.id]
          if (prev != null && prev.source != oWay.source) {
            idCollisions++
          }
          mergedWays[oWay.id] = oWay
          overpassEdgeCount++
          for (nid in oWay.nodeIds.distinct()) {
            val list = nodeToWays.getOrPut(nid) { ArrayList(3) }
            if (oWay.id !in list) list.add(oWay.id)
          }
        }
      }
    }

    // 3. Live-map fallback: whenever user position has NO tile/Overpass edge within 150 m,
    // merge fallback ways within 400 m of user without ever overriding existing tile/Overpass ways.
    val evalCenter = currentFallbackEvalCenter ?: currentKeepCenter
    val hasPrimaryEdgeWithin150m =
      if (evalCenter != null) hasWayWithinRadius(mergedWays.values, evalCenter, 150.0)
      else mergedWays.isNotEmpty()
    lastFallbackEvalPublishedCenter = evalCenter

    val fallback = mapFallbackGraph
    var mapFallbackWaysAdded = 0
    if (!hasPrimaryEdgeWithin150m && fallback != null && fallback.ways.isNotEmpty()) {
      for ((_, fWay) in fallback.ways) {
        if (fWay.points.size < 2) continue
        if (evalCenter != null && GeoMath.snapToPolyline(evalCenter, fWay.points).distanceMeters > 400.0) {
          continue
        }
        val existing = mergedWays[fWay.id]
        if (existing != null) {
          if (existing.source != fWay.source) {
            idCollisions++
          }
          continue
        }
        for (i in fWay.nodeIds.indices) {
          val nid = fWay.nodeIds[i]
          if (!mergedNodes.containsKey(nid)) {
            val pt = fWay.points.getOrNull(i)
            val node = fallback.nodes[nid] ?: pt?.let { OsmNode(id = nid, point = it) }
            if (node != null) mergedNodes[nid] = node
          }
        }
        mergedWays[fWay.id] = fWay
        mapFallbackWaysAdded++
        for (nid in fWay.nodeIds.distinct()) {
          val list = nodeToWays.getOrPut(nid) { ArrayList(3) }
          if (fWay.id !in list) list.add(fWay.id)
        }
      }
      if (mapFallbackWaysAdded > 0) {
        latestFetchedAt = max(latestFetchedAt, fallback.fetchedAtMs)
      }
    }
    val usedFallback = mapFallbackWaysAdded > 0

    var danglingNodeRefs = 0
    for (list in nodeToWays.values) {
      for (wId in list) {
        if (wId !in mergedWays) danglingNodeRefs++
      }
    }

    val hasTiles = tileEntries.values.any { it.graph.ways.isNotEmpty() }
    val source =
      when {
        hasTiles && hasOverpass -> GraphDataSource.MERGED
        hasOverpass -> GraphDataSource.OVERPASS
        hasTiles && usedFallback -> GraphDataSource.MERGED
        hasTiles -> GraphDataSource.TILES
        usedFallback -> GraphDataSource.MAP
        else -> GraphDataSource.TILES
      }
    val sourceLabel =
      when {
        hasTiles && hasOverpass && usedFallback -> "merged(tiles+ovp+map)"
        hasTiles && hasOverpass -> "merged(tiles+ovp)"
        hasOverpass && usedFallback -> "merged(ovp+map)"
        hasOverpass -> "overpass"
        hasTiles && usedFallback -> "merged(tiles+map)"
        hasTiles -> "tiles"
        usedFallback -> "map"
        else -> "none"
      }

    val combinedGraph =
      RoadGraph(
        nodes = mergedNodes,
        ways = mergedWays,
        nodeToWays = nodeToWays,
        fetchedAtMs = latestFetchedAt,
        fromCache = allFromCache,
        source = source,
        loadedTiles = loadedTiles,
        borderStitchStats = borderStitchStats,
      )
    versionCounter++
    val index = SpatialGraphIndex.build(combinedGraph, versionCounter, sourceLabel)
    val snap =
      GraphSnapshot(
        version = versionCounter,
        graph = combinedGraph,
        index = index,
        sourceLabel = sourceLabel,
        publishedAtMs = System.currentTimeMillis(),
        memoryTileCount = tileEntries.size,
        overpassCellCount = overpassEntries.size,
        memoryHits = memoryHits,
        memoryMisses = memoryMisses,
        evictions = evictionCount,
        tileFetchedAtMs = tileTimestamps,
        emptyDecodedTileKeys = emptyKeys,
        pois = allPois.values.toList(),
        labelsByTile = labelsByTile,
        overpassEdgeCount = overpassEdgeCount,
        tileEdgeCount = tileEdgeCount,
        idCollisions = idCollisions,
        danglingNodeRefs = danglingNodeRefs,
        waysPerTileMin = waysPerTileMin,
        waysPerTileAvg = waysPerTileAvg,
        waysPerTileMax = waysPerTileMax,
        mapFallbackUsed = usedFallback,
        mapFallbackWaysAdded = mapFallbackWaysAdded,
        hasPrimaryEdgeWithin150m = hasPrimaryEdgeWithin150m,
        borderStitchStats = borderStitchStats,
      )
    _snapshot.value = snap
    return snap
  }

  private fun mergeTagsPreferOverpass(tileTags: OsmTags, ovTags: OsmTags): OsmTags =
    OsmTags(
      name = ovTags.name ?: tileTags.name,
      nameAr = ovTags.nameAr ?: tileTags.nameAr,
      nameEn = ovTags.nameEn ?: tileTags.nameEn,
      nameLocalized = ovTags.nameLocalized ?: tileTags.nameLocalized,
      nameNonLatin = ovTags.nameNonLatin ?: tileTags.nameNonLatin,
      nameLatin = ovTags.nameLatin ?: tileTags.nameLatin,
      ref = ovTags.ref ?: tileTags.ref,
      destination = ovTags.destination ?: tileTags.destination,
      destinationRef = ovTags.destinationRef ?: tileTags.destinationRef,
      oneway = ovTags.oneway ?: tileTags.oneway,
      junction = ovTags.junction ?: tileTags.junction,
      highway = ovTags.highway ?: tileTags.highway,
      service = ovTags.service ?: tileTags.service,
      bridge = ovTags.bridge ?: tileTags.bridge,
      tunnel = ovTags.tunnel ?: tileTags.tunnel,
      layer = ovTags.layer ?: tileTags.layer,
      lanes = ovTags.lanes ?: tileTags.lanes,
      footway = ovTags.footway ?: tileTags.footway,
      crossing = ovTags.crossing ?: tileTags.crossing,
      toll = ovTags.toll ?: tileTags.toll,
      access = ovTags.access ?: tileTags.access,
      construction = ovTags.construction ?: tileTags.construction,
      barrier = ovTags.barrier ?: tileTags.barrier,
      noexit = ovTags.noexit ?: tileTags.noexit,
      military = ovTags.military ?: tileTags.military,
    )
}
