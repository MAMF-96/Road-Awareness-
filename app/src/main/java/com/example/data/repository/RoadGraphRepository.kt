package com.example.data.repository

import com.example.config.AppConfig
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

  fun setMapFallbackGraph(graph: RoadGraph?): GraphSnapshot =
    synchronized(lock) {
      mapFallbackGraph = graph
      rebuildAndPublishLocked()
    }

  fun clear(): GraphSnapshot =
    synchronized(lock) {
      tileEntries.clear()
      overpassEntries.clear()
      mapFallbackGraph = null
      currentKeepTiles = emptySet()
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

  private fun rebuildAndPublishLocked(): GraphSnapshot {
    val startNs = System.nanoTime()
    val mergedNodes = HashMap<Long, OsmNode>(4096)
    val mergedWays = HashMap<Long, OsmWay>(2048)
    val nodeToWays = HashMap<Long, MutableList<Long>>(4096)
    val loadedTiles = ArrayList<LoadedTileBounds>(tileEntries.size)
    val tileTimestamps = HashMap<String, Long>(tileEntries.size)
    val emptyKeys = HashSet<String>()
    val labelsByTile = HashMap<String, List<TileNameLabelRecord>>(tileEntries.size)
    val allPois = LinkedHashMap<Long, PoiItem>(256)
    var latestFetchedAt = 0L
    var allFromCache = true
    var tileEdgeCount = 0
    var overpassEdgeCount = 0

    // 1. Tile baseline (all decoded z14 tiles)
    for ((k, entry) in tileEntries) {
      loadedTiles += entry.bounds
      tileTimestamps[k] = entry.fetchedAtMs
      if (entry.isEmpty) emptyKeys += k
      if (entry.labels.isNotEmpty()) labelsByTile[k] = entry.labels
      for (p in entry.pois) allPois[p.id] = p
      latestFetchedAt = max(latestFetchedAt, entry.fetchedAtMs)
      if (!entry.graph.fromCache) allFromCache = false

      mergedNodes.putAll(entry.graph.nodes)
      for ((wId, way) in entry.graph.ways) {
        mergedWays[wId] = way
        tileEdgeCount++
        for (nid in way.nodeIds.distinct()) {
          nodeToWays.getOrPut(nid) { ArrayList(3) }.add(wId)
        }
      }
    }

    // 2. Fallback live map ways if tiles empty for an area
    val fallback = mapFallbackGraph
    var usedFallback = false
    if (fallback != null && fallback.ways.isNotEmpty() && mergedWays.isEmpty()) {
      usedFallback = true
      latestFetchedAt = max(latestFetchedAt, fallback.fetchedAtMs)
      mergedNodes.putAll(fallback.nodes)
      for ((wId, way) in fallback.ways) {
        mergedWays[wId] = way
        for (nid in way.nodeIds.distinct()) {
          nodeToWays.getOrPut(nid) { ArrayList(3) }.add(wId)
        }
      }
    }

    // 3. Overpass enrichment (Overpass enriches overlapping tile tags + adds missing ways without deleting)
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
          for (nid in removed.nodeIds) {
            nodeToWays[nid]?.remove(oldId)
          }
        }
        for (oWay in enrichedOvWays) {
          mergedWays[oWay.id] = oWay
          overpassEdgeCount++
          for (nid in oWay.nodeIds.distinct()) {
            val list = nodeToWays.getOrPut(nid) { ArrayList(3) }
            if (oWay.id !in list) list.add(oWay.id)
          }
        }
      }
    }

    val hasTiles = tileEntries.values.any { it.graph.ways.isNotEmpty() }
    val source =
      when {
        hasTiles && hasOverpass -> GraphDataSource.MERGED
        hasOverpass -> GraphDataSource.OVERPASS
        hasTiles -> GraphDataSource.TILES
        usedFallback -> GraphDataSource.MAP
        else -> GraphDataSource.TILES
      }
    val sourceLabel =
      when {
        hasTiles && hasOverpass -> "merged(tiles+ovp)"
        hasOverpass -> "overpass"
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
