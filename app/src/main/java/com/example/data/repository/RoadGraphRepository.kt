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
import com.example.data.model.PlanarizeStats
import com.example.data.model.PoiItem
import com.example.data.model.RoadGraph
import com.example.data.model.TileNameLabelRecord
import com.example.data.remote.TileRoadGraphSource
import com.example.domain.GeoMath
import com.example.domain.SpatialGraphIndex
import com.example.domain.TileEndpointPlanarizer
import com.example.ui.MainThreadWatchdog
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Copy-on-write authoritative road-graph repository.
 *
 * Locking discipline:
 * - [currentSnapshot] is lock-free via [StateFlow].
 * - [synchronized] blocks ONLY copy/swap immutable entry references (< microseconds).
 * - Heavy graph merging, endpoint planarization, cross-tile border stitching, Overpass enrichment,
 *   and [SpatialGraphIndex.build] always execute OUTSIDE [lock].
 * - Publishing checks `entriesVersion == captured.entriesVersion` and loops if mutated concurrently.
 * - Background coalesced rebuilds run at most one at a time with a 150 ms debounce and dirty flag.
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
    val planarizeStats: PlanarizeStats = PlanarizeStats(),
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
    val entryRevision: Long,
  )

  private data class StoredOverpassEntry(
    val cellKey: String,
    val center: GeoPoint,
    val graph: RoadGraph,
    val fetchedAtMs: Long,
    val pois: List<PoiItem>,
  )

  private data class CapturedRepoState(
    val entriesVersion: Long,
    val tiles: Map<String, StoredTileEntry>,
    val overpassCells: Map<String, StoredOverpassEntry>,
    val mapFallback: RoadGraph?,
    val evalCenter: GeoPoint?,
    val memoryHits: Long,
    val memoryMisses: Long,
    val evictionCount: Int,
  )

  private data class IncrementalTileCache(
    val tileRevisions: Map<String, Long> = emptyMap(),
    val tileNodeToDistinct: Map<String, List<Pair<Long, Long>>> = emptyMap(),
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
  private var entriesVersion: Long = 0L
  private var entryRevisionCounter: Long = 0L
  private var snapshotVersionCounter: Long = 0L
  private var memoryHits: Long = 0L
  private var memoryMisses: Long = 0L
  private var evictionCount: Int = 0

  // Incremental per-tile metadata cache accessed under rebuildMutex/single builder
  private val rebuildLock = Any()
  private var incrementalTileCache = IncrementalTileCache()

  // Coalescing / debounce state
  private val rebuildPendingDirty = AtomicBoolean(false)
  private val rebuildRunning = AtomicBoolean(false)
  private var debouncedRebuildJob: Job? = null

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

  private inline fun <T> withTimingLock(block: () -> T): T {
    val startWait = System.nanoTime()
    return synchronized(lock) {
      val acquired = System.nanoTime()
      try {
        block()
      } finally {
        val endHold = System.nanoTime()
        MainThreadWatchdog.recordRepoLockTiming(acquired - startWait, endHold - acquired)
      }
    }
  }

  fun hasTileInMemory(tileKey: String): Boolean =
    withTimingLock { tileEntries.containsKey(tileKey) }

  fun getMemoryTileKeys(): Set<String> =
    withTimingLock { tileEntries.keys.toHashSet() }

  fun recordMemoryCacheProbe(hit: Boolean) {
    withTimingLock {
      if (hit) memoryHits++ else memoryMisses++
    }
  }

  fun updateKeepWindow(
    center: GeoPoint,
    horizonMeters: Double,
    keepTileKeys: Set<String>,
  ): GraphSnapshot {
    val evictedAny =
      withTimingLock {
        currentKeepCenter = center
        currentFallbackEvalCenter = center
        currentKeepHorizonMeters = horizonMeters
        currentKeepTiles = keepTileKeys
        val evicted = evictBeyondWindowAndCapsLocked()
        if (evicted) entriesVersion++
        evicted
      }
    return if (evictedAny) {
      rebuildAndPublishCoW()
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
  ): GraphSnapshot {
    withTimingLock {
      val rev = ++entryRevisionCounter
      tileEntries[tileKey] =
        StoredTileEntry(
          tileKey = tileKey,
          bounds = bounds,
          graph = tileGraph,
          fetchedAtMs = fetchedAtMs,
          isEmpty = isEmptyDecoded,
          labels = labels,
          pois = pois,
          entryRevision = rev,
        )
      evictBeyondWindowAndCapsLocked()
      entriesVersion++
    }
    return rebuildAndPublishCoW()
  }

  fun upsertDecodedArtifacts(
    artifacts: List<DecodedTileArtifact>,
    deferRebuildScope: CoroutineScope? = null,
  ): GraphSnapshot {
    withTimingLock {
      for (art in artifacts) {
        val rev = ++entryRevisionCounter
        tileEntries[art.tileKey] =
          StoredTileEntry(
            tileKey = art.tileKey,
            bounds = art.bounds,
            graph = art.graph,
            fetchedAtMs = art.fetchedAtMs,
            isEmpty = art.isEmptyDecoded,
            labels = art.labels,
            pois = art.pois,
            entryRevision = rev,
          )
      }
      evictBeyondWindowAndCapsLocked()
      entriesVersion++
    }
    if (deferRebuildScope != null) {
      scheduleCoalescedRebuild(deferRebuildScope)
      return _snapshot.value
    }
    return rebuildAndPublishCoW()
  }

  fun isTileFreshInMemory(tileKey: String, staleAgeMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
    withTimingLock {
      val e = tileEntries[tileKey] ?: return@withTimingLock false
      (nowMs - e.fetchedAtMs) < staleAgeMs
    }

  fun upsertTileGraphsBatch(
    tiles: List<TileBatchItem>,
  ): GraphSnapshot {
    withTimingLock {
      for (t in tiles) {
        val rev = ++entryRevisionCounter
        tileEntries[t.tileKey] =
          StoredTileEntry(
            tileKey = t.tileKey,
            bounds = t.bounds,
            graph = t.graph,
            fetchedAtMs = t.fetchedAtMs,
            isEmpty = t.isEmptyDecoded,
            labels = t.labels,
            pois = t.pois,
            entryRevision = rev,
          )
      }
      evictBeyondWindowAndCapsLocked()
      entriesVersion++
    }
    return rebuildAndPublishCoW()
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
  ): GraphSnapshot {
    withTimingLock {
      overpassEntries[cellKey] =
        StoredOverpassEntry(
          cellKey = cellKey,
          center = cellCenter,
          graph = cellGraph,
          fetchedAtMs = fetchedAtMs,
          pois = pois,
        )
      evictOverpassBeyondCapsLocked()
      entriesVersion++
    }
    return rebuildAndPublishCoW()
  }

  fun setMapFallbackGraph(
    graph: RoadGraph?,
    evalCenter: GeoPoint? = null,
  ): GraphSnapshot {
    withTimingLock {
      mapFallbackGraph = graph
      if (evalCenter != null) {
        currentFallbackEvalCenter = evalCenter
      }
      entriesVersion++
    }
    return rebuildAndPublishCoW()
  }

  fun reevaluateFallbackForPosition(
    center: GeoPoint,
    minMoveMeters: Double = 50.0,
  ): GraphSnapshot? {
    val shouldRebuild =
      withTimingLock {
        currentFallbackEvalCenter = center
        val fallback = mapFallbackGraph ?: return@withTimingLock false
        if (fallback.ways.isEmpty()) return@withTimingLock false
        val lastEval = lastFallbackEvalPublishedCenter
        val moved = lastEval?.let { GeoMath.haversineMeters(it, center) } ?: Double.MAX_VALUE
        moved >= minMoveMeters
      }
    if (!shouldRebuild) return null

    // Evaluate outside lock against current immutable snapshot
    val curSnap = _snapshot.value
    val primaryNear =
      curSnap.index.queryWaysAround(center, 150.0).any { w ->
        w.source != GraphDataSource.MAP &&
          w.points.size >= 2 &&
          GeoMath.snapToPolyline(center, w.points).distanceMeters <= 150.0
      }
    return if (!primaryNear || curSnap.mapFallbackUsed) {
      rebuildAndPublishCoW()
    } else {
      null
    }
  }

  fun clear(): GraphSnapshot {
    withTimingLock {
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
      entriesVersion++
    }
    synchronized(rebuildLock) {
      incrementalTileCache = IncrementalTileCache()
    }
    return rebuildAndPublishCoW()
  }

  /**
   * Coalesces multiple rapid streaming updates into a single background rebuild debounced by 150 ms.
   * At most one rebuild runs at a time; extra updates mark dirty and schedule another pass.
   */
  fun scheduleCoalescedRebuild(
    scope: CoroutineScope,
    debounceMs: Long = 150L,
    onPublished: ((GraphSnapshot) -> Unit)? = null,
  ) {
    rebuildPendingDirty.set(true)
    if (rebuildRunning.get()) return
    debouncedRebuildJob?.cancel()
    debouncedRebuildJob =
      scope.launch {
        delay(debounceMs)
        drainCoalescedRebuilds(onPublished)
      }
  }

  private fun drainCoalescedRebuilds(onPublished: ((GraphSnapshot) -> Unit)?) {
    if (!rebuildRunning.compareAndSet(false, true)) return
    try {
      while (rebuildPendingDirty.getAndSet(false)) {
        val snap = rebuildAndPublishCoW()
        onPublished?.invoke(snap)
      }
    } finally {
      rebuildRunning.set(false)
      if (rebuildPendingDirty.get()) {
        if (rebuildRunning.compareAndSet(false, true)) {
          try {
            while (rebuildPendingDirty.getAndSet(false)) {
              val snap = rebuildAndPublishCoW()
              onPublished?.invoke(snap)
            }
          } finally {
            rebuildRunning.set(false)
          }
        }
      }
    }
  }

  private fun captureStateUnderBriefLock(): CapturedRepoState =
    withTimingLock {
      CapturedRepoState(
        entriesVersion = entriesVersion,
        tiles = LinkedHashMap(tileEntries),
        overpassCells = LinkedHashMap(overpassEntries),
        mapFallback = mapFallbackGraph,
        evalCenter = currentFallbackEvalCenter ?: currentKeepCenter,
        memoryHits = memoryHits,
        memoryMisses = memoryMisses,
        evictionCount = evictionCount,
      )
    }

  /**
   * Copy-on-write rebuild: reads immutable entry snapshots under a microsecond lock,
   * constructs the merged/planarized graph and SpatialGraphIndex completely OUTSIDE [lock],
   * then publishes under a microsecond lock only if [entriesVersion] has not changed.
   */
  fun rebuildAndPublishCoW(): GraphSnapshot {
    var attempts = 0
    while (attempts++ < 6) {
      val captured = captureStateUnderBriefLock()
      val built = synchronized(rebuildLock) { buildSnapshotOutsideLock(captured) }
      val published =
        withTimingLock {
          if (entriesVersion == captured.entriesVersion) {
            snapshotVersionCounter++
            val finalSnap = built.copy(version = snapshotVersionCounter)
            lastFallbackEvalPublishedCenter = captured.evalCenter
            _snapshot.value = finalSnap
            finalSnap
          } else {
            null
          }
        }
      if (published != null) return published
    }
    // Final unconditional attempt after retries
    val captured = captureStateUnderBriefLock()
    val built = synchronized(rebuildLock) { buildSnapshotOutsideLock(captured) }
    return withTimingLock {
      snapshotVersionCounter++
      val finalSnap = built.copy(version = snapshotVersionCounter)
      lastFallbackEvalPublishedCenter = captured.evalCenter
      _snapshot.value = finalSnap
      finalSnap
    }
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

  private fun buildSnapshotOutsideLock(captured: CapturedRepoState): GraphSnapshot {
    val tileEntriesMap = captured.tiles
    val overpassMap = captured.overpassCells

    // Incremental per-tile distinct node-way pair extraction (only compute for newly added/changed tiles)
    val prevCache = synchronized(rebuildLock) { incrementalTileCache }
    val nextRevs = HashMap<String, Long>(tileEntriesMap.size)
    val nextPairs = HashMap<String, List<Pair<Long, Long>>>(tileEntriesMap.size)

    for ((k, entry) in tileEntriesMap) {
      nextRevs[k] = entry.entryRevision
      val cachedPairs =
        if (prevCache.tileRevisions[k] == entry.entryRevision) prevCache.tileNodeToDistinct[k]
        else null
      if (cachedPairs != null) {
        nextPairs[k] = cachedPairs
      } else {
        val pairs = ArrayList<Pair<Long, Long>>(entry.graph.ways.size * 3)
        for ((wId, way) in entry.graph.ways) {
          val seenNodes = HashSet<Long>(way.nodeIds.size * 2)
          for (nid in way.nodeIds) {
            if (seenNodes.add(nid)) {
              pairs += nid to wId
            }
          }
        }
        nextPairs[k] = pairs
      }
    }
    synchronized(rebuildLock) {
      incrementalTileCache = IncrementalTileCache(nextRevs, nextPairs)
    }

    val mergedNodes = HashMap<Long, OsmNode>(4096)
    val mergedWays = HashMap<Long, OsmWay>(2048)
    val wayOriginTileById = HashMap<Long, String>(2048)
    val nodeToWays = HashMap<Long, MutableList<Long>>(4096)
    val loadedTiles = ArrayList<LoadedTileBounds>(tileEntriesMap.size)
    val tileTimestamps = HashMap<String, Long>(tileEntriesMap.size)
    val emptyKeys = HashSet<String>()
    val labelsByTile = HashMap<String, List<TileNameLabelRecord>>(tileEntriesMap.size)
    val allPois = LinkedHashMap<Long, PoiItem>(256)
    val tileWayCounts = ArrayList<Int>(tileEntriesMap.size)
    var latestFetchedAt = 0L
    var allFromCache = true
    var tileEdgeCount = 0
    var overpassEdgeCount = 0
    var idCollisions = 0

    // 1. Tile baseline (all decoded z14 tiles)
    for ((k, entry) in tileEntriesMap) {
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
      }
      val pairs = nextPairs[k].orEmpty()
      for ((nid, wId) in pairs) {
        val list = nodeToWays.getOrPut(nid) { ArrayList(3) }
        if (wId !in list) list.add(wId)
      }
    }

    // Run T-junction & ramp endpoint planarization when multiple tiles merge (and on un-planarized graphs)
    val planarizeStats =
      if (mergedWays.size >= 2) {
        TileEndpointPlanarizer.planarize(
          nodes = mergedNodes,
          ways = mergedWays,
          nodeToWays = nodeToWays,
          loadedTiles = loadedTiles,
          wayTileKeyOverrides = wayOriginTileById,
        )
      } else {
        PlanarizeStats()
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

    // 2. Overpass enrichment
    var hasOverpass = false
    if (overpassMap.isNotEmpty()) {
      val baseIndex =
        if (mergedWays.isNotEmpty()) {
          val tempBase = RoadGraph(mergedNodes, mergedWays, nodeToWays, latestFetchedAt, allFromCache, GraphDataSource.TILES, loadedTiles)
          SpatialGraphIndex.build(tempBase, captured.entriesVersion, "tiles")
        } else {
          null
        }
      for ((_, cell) in overpassMap) {
        for (p in cell.pois) allPois[p.id] = p
        if (cell.graph.ways.isEmpty() && cell.graph.nodes.isEmpty()) continue
        hasOverpass = true
        latestFetchedAt = max(latestFetchedAt, cell.fetchedAtMs)
        if (!cell.graph.fromCache) allFromCache = false

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

    // 3. Live-map fallback
    val evalCenter = captured.evalCenter
    val hasPrimaryEdgeWithin150m =
      if (evalCenter != null) hasWayWithinRadius(mergedWays.values, evalCenter, 150.0)
      else mergedWays.isNotEmpty()

    val fallback = captured.mapFallback
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

    val hasTiles = tileEntriesMap.values.any { it.graph.ways.isNotEmpty() }
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

    // Aggregate planarize stats from individual tiles + cross-tile planarize pass
    val aggVertexMerges = tileEntriesMap.values.sumOf { it.graph.planarizeStats.vertexMerges } + planarizeStats.vertexMerges
    val aggSplits = tileEntriesMap.values.sumOf { it.graph.planarizeStats.segmentSplits } + planarizeStats.segmentSplits
    val aggMismatch = tileEntriesMap.values.sumOf { it.graph.planarizeStats.rejectedLayerMismatches } + planarizeStats.rejectedLayerMismatches
    val combinedPlanarizeStats =
      PlanarizeStats(
        vertexMerges = aggVertexMerges,
        segmentSplits = aggSplits,
        rejectedLayerMismatches = aggMismatch,
      )

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
        planarizeStats = combinedPlanarizeStats,
        hasBeenPlanarized = mergedWays.isNotEmpty(),
      )
    val buildSeq = captured.entriesVersion + 1L
    val index = SpatialGraphIndex.build(combinedGraph, buildSeq, sourceLabel)
    return GraphSnapshot(
      version = buildSeq,
      graph = combinedGraph,
      index = index,
      sourceLabel = sourceLabel,
      publishedAtMs = System.currentTimeMillis(),
      memoryTileCount = tileEntriesMap.size,
      overpassCellCount = overpassMap.size,
      memoryHits = captured.memoryHits,
      memoryMisses = captured.memoryMisses,
      evictions = captured.evictionCount,
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
      planarizeStats = combinedPlanarizeStats,
    )
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
