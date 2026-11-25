package com.example.data.remote

import com.example.config.AppConfig
import com.example.data.interfaces.CorridorGraphUpdate
import com.example.data.interfaces.MapDataSource
import com.example.data.local.TileCacheDao
import com.example.data.local.TileCacheEntity
import com.example.data.model.BilingualName
import com.example.data.model.DecodedTileArtifact
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.LiveMapVectorFeature
import com.example.data.model.LoadedTileBounds
import com.example.data.model.MvtDecodeStageInfo
import com.example.data.model.NearbyTileLabelDiagnostic
import com.example.data.model.OsmNode
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.OverpassRequestLog
import com.example.data.model.PoiCategory
import com.example.data.model.PoiItem
import com.example.data.model.RoadGraph
import com.example.data.model.TileDecodeTiming
import com.example.data.model.TileFetchStageInfo
import com.example.data.model.TileNameLabelRecord
import com.example.data.model.TileUrlResolutionInfo
import com.example.data.model.TravelMode
import com.example.domain.GeoMath
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.tan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject

data class ZxyTile(
  val z: Int,
  val x: Int,
  val y: Int,
) {
  val key: String get() = "${z}_${x}_${y}"
}

private data class ResolvedVectorSource(
  val tileUrlTemplate: String,
  val maxZoom: Int,
  val hasTransportationLayer: Boolean,
  val diagnosticError: String?,
  val scheme: String = "xyz",
  val fetchedAtMs: Long = 0L,
  val howResolved: String = "style->tilejson",
)

private class NameLabelIndex private constructor(
  private val bucketToLabelIndices: Map<Long, IntArray>,
  private val labels: List<TileNameLabelRecord>,
  private val densePointsByLabel: List<List<Pair<GeoPoint, Double?>>>,
) {
  fun queryCandidateIndices(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): IntArray {
    if (labels.isEmpty()) return IntArray(0)
    val marginDeg = 0.00025
    val cy0 = floor((minLat - marginDeg).coerceIn(-89.9, 89.9) / GRID_DEG).toInt()
    val cy1 = floor((maxLat + marginDeg).coerceIn(-89.9, 89.9) / GRID_DEG).toInt()
    val cx0 = floor((GeoMath.normalizeLon(minLon) - marginDeg) / GRID_DEG).toInt()
    val cx1 = floor((GeoMath.normalizeLon(maxLon) + marginDeg) / GRID_DEG).toInt()
    if (cx1 - cx0 > 60 || cy1 - cy0 > 60) {
      return IntArray(labels.size) { it }
    }
    val seen = HashSet<Int>(16)
    val out = ArrayList<Int>(16)
    for (cy in cy0..cy1) {
      for (rawCx in cx0..cx1) {
        val cx = wrapLonCell(rawCx)
        val bucket = bucketToLabelIndices[packCell(cy, cx)] ?: continue
        for (idx in bucket) {
          if (seen.add(idx)) out += idx
        }
      }
    }
    return out.toIntArray()
  }

  fun labelAt(index: Int): TileNameLabelRecord = labels[index]

  fun denseSamplesAt(index: Int): List<Pair<GeoPoint, Double?>> = densePointsByLabel[index]

  companion object {
    private const val GRID_DEG = 0.001 // ~111 m
    private val LON_WRAP = (360.0 / GRID_DEG).toInt()

    fun build(labels: List<TileNameLabelRecord>): NameLabelIndex {
      val buckets = HashMap<Long, MutableList<Int>>(labels.size * 2)
      val denseList = ArrayList<List<Pair<GeoPoint, Double?>>>(labels.size)

      for ((lIdx, lbl) in labels.withIndex()) {
        val dense = densifyLabelWithBearings(lbl.points)
        denseList += dense
        val insertedCells = HashSet<Long>(dense.size + 2)
        for ((pt, _) in dense) {
          val cy = floor(pt.lat.coerceIn(-89.9, 89.9) / GRID_DEG).toInt()
          val cx = wrapLonCell(floor(GeoMath.normalizeLon(pt.lon) / GRID_DEG).toInt())
          val cellKey = packCell(cy, cx)
          if (insertedCells.add(cellKey)) {
            buckets.getOrPut(cellKey) { ArrayList(4) }.add(lIdx)
          }
        }
      }
      val packed = HashMap<Long, IntArray>(buckets.size)
      for ((k, v) in buckets) packed[k] = v.toIntArray()
      return NameLabelIndex(packed, labels, denseList)
    }

    private fun densifyLabelWithBearings(pts: List<GeoPoint>): List<Pair<GeoPoint, Double?>> {
      if (pts.isEmpty()) return emptyList()
      if (pts.size == 1) return listOf(pts[0] to null)
      val out = ArrayList<Pair<GeoPoint, Double?>>(pts.size * 2)
      for (i in 0 until pts.lastIndex) {
        val a = pts[i]
        val b = pts[i + 1]
        val brng = GeoMath.bearingDeg(a, b)
        val dist = GeoMath.haversineMeters(a, b)
        out += a to brng
        if (dist > 18.0) {
          val steps = (dist / 15.0).toInt().coerceIn(2, 16)
          for (s in 1 until steps) {
            val f = s.toDouble() / steps.toDouble()
            val lat = a.lat + (b.lat - a.lat) * f
            val lon = GeoMath.normalizeLon(a.lon + GeoMath.unwrapLonDelta(b.lon - a.lon) * f)
            out += GeoPoint(lat, lon) to brng
          }
        }
      }
      val lastBrng = GeoMath.bearingDeg(pts[pts.lastIndex - 1], pts.last())
      out += pts.last() to lastBrng
      return out
    }

    private fun wrapLonCell(rawX: Int): Int {
      val m = rawX % LON_WRAP
      return if (m < 0) m + LON_WRAP else m
    }

    private fun packCell(cy: Int, cx: Int): Long =
      (cy.toLong() shl 32) or (cx.toLong() and 0xFFFFFFFFL)
  }
}

/**
 * MapDataSource backed by z14 MVT vector tiles from the same keyless vector source as MAP_STYLE_LIGHT_URL.
 * Supports incremental per-tile streaming, bounded concurrency (3), binary disk caching of pre-decoded
 * stitched tiles (stitched_z14 .bin files, 30-day / 200 MB LRU), spatial hash label attachment, and POI extraction.
 */
class TileRoadGraphSource(
  private val tileCacheDao: TileCacheDao,
  private val styleCacheDir: File,
  private val client: OkHttpClient = defaultOkHttpClient(),
  private val styleUrl: String = AppConfig.MAP_STYLE_LIGHT_URL,
  private val clockMs: () -> Long = { System.currentTimeMillis() },
) : MapDataSource {

  private val binaryCodec = TileBinaryCodec(styleCacheDir, clockMs = clockMs)

  private val _requestLogs = MutableStateFlow<List<OverpassRequestLog>>(emptyList())
  override val requestLogs: StateFlow<List<OverpassRequestLog>> = _requestLogs.asStateFlow()

  private val _tileUrlInfo = MutableStateFlow(TileUrlResolutionInfo())
  val tileUrlInfo: StateFlow<TileUrlResolutionInfo> = _tileUrlInfo.asStateFlow()

  private val _tileFetchInfo = MutableStateFlow(TileFetchStageInfo())
  val tileFetchInfo: StateFlow<TileFetchStageInfo> = _tileFetchInfo.asStateFlow()

  private val _mvtDecodeInfo = MutableStateFlow(MvtDecodeStageInfo())
  val mvtDecodeInfo: StateFlow<MvtDecodeStageInfo> = _mvtDecodeInfo.asStateFlow()

  @Volatile private var cachedVectorSource: ResolvedVectorSource? = null

  fun diskStats(): TileBinaryCodec.DiskStats = binaryCodec.getDiskStats()

  fun invalidateTileJsonCache() {
    cachedVectorSource = null
    runCatching { File(styleCacheDir, "vector_tilejson.json").delete() }
    runCatching { File(styleCacheDir, "light_style.json").delete() }
  }

  suspend fun forceInvalidateTileJsonAndClearCache() {
    withContext(Dispatchers.IO) {
      invalidateTileJsonCache()
      tileCacheDao.clearAll()
      binaryCodec.clearAll()
    }
  }

  override fun fetchCorridorStream(
    center: GeoPoint,
    headingDeg: Double,
    mode: TravelMode,
    forceRefresh: Boolean,
  ): Flow<Result<CorridorGraphUpdate>> =
    fetchCorridorStreamWithWindow(
      center = center,
      headingDeg = headingDeg,
      mode = mode,
      horizonMeters = if (mode == TravelMode.DRIVING) AppConfig.DRIVING_DEFAULT_HORIZON_M else AppConfig.WALKING_LOOKAHEAD_M,
      alreadyInMemoryFreshKeys = emptySet(),
      trailingBreadcrumbs = emptyList(),
      forceRefresh = forceRefresh,
    )

  fun computeRequiredTiles(
    center: GeoPoint,
    headingDeg: Double,
    mode: TravelMode,
    horizonMeters: Double,
    trailingBreadcrumbs: List<GeoPoint> = emptyList(),
    zoom: Int = AppConfig.TILE_GRAPH_ZOOM,
  ): Pair<String, List<ZxyTile>> {
    val (cx, cy) = latLonToTile(center.lat, center.lon, zoom)
    val centerTileKey = "${zoom}_${cx}_${cy}"
    val aheadM =
      if (mode == TravelMode.DRIVING) horizonMeters.coerceIn(AppConfig.DRIVING_MIN_LOOKAHEAD_M, AppConfig.DRIVING_MAX_LOOKAHEAD_M)
      else AppConfig.WALKING_LOOKAHEAD_M
    val corridorWidthM = if (mode == TravelMode.DRIVING) 380.0 else 220.0
    val ordered = LinkedHashSet<ZxyTile>()
    val nInt = 1 shl zoom

    // 1. Center tile first
    ordered += ZxyTile(zoom, ((cx % nInt) + nInt) % nInt, cy.coerceIn(0, nInt - 1))

    // 2. Tiles along heading up to horizonMeters (400m steps -> center + ahead)
    val stepM = 450.0
    var d = stepM
    while (d <= aheadM + 200.0) {
      val aheadPt = GeoMath.offsetPoint(center, headingDeg, min(d, aheadM))
      val leftPt = GeoMath.offsetPoint(aheadPt, (headingDeg + 270.0) % 360.0, corridorWidthM)
      val rightPt = GeoMath.offsetPoint(aheadPt, (headingDeg + 90.0) % 360.0, corridorWidthM)
      for (pt in listOf(aheadPt, leftPt, rightPt)) {
        val (tx, ty) = latLonToTile(pt.lat, pt.lon, zoom)
        ordered += ZxyTile(zoom, ((tx % nInt) + nInt) % nInt, ty.coerceIn(0, nInt - 1))
      }
      d += stepM
    }

    // 3. 3x3 around user (side + immediate behind)
    for (dy in -1..1) {
      for (dx in -1..1) {
        val wx = (((cx + dx) % nInt) + nInt) % nInt
        val wy = (cy + dy).coerceIn(0, nInt - 1)
        ordered += ZxyTile(zoom, wx, wy)
      }
    }

    // 4. Trailing breadcrumbs (up to 2 km behind)
    for (bp in trailingBreadcrumbs) {
      val (tx, ty) = latLonToTile(bp.lat, bp.lon, zoom)
      ordered += ZxyTile(zoom, ((tx % nInt) + nInt) % nInt, ty.coerceIn(0, nInt - 1))
    }

    return centerTileKey to ordered.toList()
  }

  fun fetchCorridorStreamWithWindow(
    center: GeoPoint,
    headingDeg: Double,
    mode: TravelMode,
    horizonMeters: Double,
    alreadyInMemoryFreshKeys: Set<String> = emptySet(),
    trailingBreadcrumbs: List<GeoPoint> = emptyList(),
    forceRefresh: Boolean = false,
  ): Flow<Result<CorridorGraphUpdate>> =
    channelFlow {
      val startMs = clockMs()
      if (forceRefresh) {
        invalidateTileJsonCache()
      }

      val resolvedFromDiskOrMem = resolveVectorSource(forceNetwork = forceRefresh)
      var vecSrc =
        if (resolvedFromDiskOrMem.isSuccess) {
          resolvedFromDiskOrMem.getOrThrow()
        } else {
          // Even if style/TileJSON fetch failed, check if we still have cached Room/binary tiles!
          null
        }

      val zoom =
        if (vecSrc != null) minOf(AppConfig.TILE_GRAPH_ZOOM, vecSrc.maxZoom).coerceAtLeast(10)
        else AppConfig.TILE_GRAPH_ZOOM
      val (centerTileKey, tilesNeeded) =
        computeRequiredTiles(center, headingDeg, mode, horizonMeters, trailingBreadcrumbs, zoom)
      val tilesToWork =
        if (forceRefresh) tilesNeeded
        else tilesNeeded.filter { it.key !in alreadyInMemoryFreshKeys }

      if (tilesToWork.isEmpty() && alreadyInMemoryFreshKeys.isNotEmpty()) {
        // Everything required in window is already fresh in memory!
        _tileFetchInfo.value =
          TileFetchStageInfo(
            ok = true,
            centerTileKey = centerTileKey,
            requestedCount = tilesNeeded.size,
            okCount = tilesNeeded.size,
            cacheHitCount = tilesNeeded.size,
            netHitCount = 0,
            totalBytes = 0L,
            lastHttpStatus = "MEM",
            contentEncoding = "memory",
            durationMs = 1L,
            reason = null,
          )
        return@channelFlow
      }

      // Phase A: Fast read from pre-decoded binary cache (`stitched_z14/*.bin`) and Room PBF cache
      val binaryHits = ArrayList<DecodedTileArtifact>()
      val needRoomOrNet = ArrayList<ZxyTile>()
      val workKeys = tilesToWork.map { it.key }
      val cachedRowsByKey =
        if (forceRefresh) emptyMap()
        else tileCacheDao.getTiles(workKeys).associateBy { it.tileKey }

      val freshPbfRows = ArrayList<TileCacheEntity>()
      val stalePbfRows = ArrayList<TileCacheEntity>()
      val missingTiles = ArrayList<ZxyTile>()

      for (t in tilesToWork) {
        if (!forceRefresh) {
          val binArt = binaryCodec.readIfValid(t.key, mode, ignoreTtl = DebugNetworkSimulator.isOfflineBlocked())
          if (binArt != null) {
            binaryHits += binArt
            val age = clockMs() - binArt.fetchedAtMs
            if (age >= AppConfig.DATA_UPDATING_STALE_AGE_MS && t.key == centerTileKey && !DebugNetworkSimulator.isOfflineBlocked()) {
              missingTiles += t
            }
            continue
          }
        }
        val row = cachedRowsByKey[t.key]
        when {
          row == null || forceRefresh -> missingTiles += t
          (clockMs() - row.fetchedAtMs) >= AppConfig.CACHE_TTL_MS -> {
            stalePbfRows += row
            if (!DebugNetworkSimulator.isOfflineBlocked()) missingTiles += t
          }
          else -> freshPbfRows += row
        }
        needRoomOrNet += t
      }

      val totalGunzipMs = AtomicLong(0L)
      val totalDecodeMs = AtomicLong(0L)
      val cumulativeLayerCounts = LinkedHashMap<String, Int>()
      val cumulativeSamples = mutableListOf<String>()
      var maxExtentObserved = 4096
      val decodeLock = Mutex()
      val emittedCount = AtomicInteger(0)
      val totalBytesAtomic = AtomicLong(0L)

      suspend fun publishArtifact(artifact: DecodedTileArtifact, isFromNet: Boolean, isPartial: Boolean, log: OverpassRequestLog?) {
        decodeLock.withLock {
          emittedCount.incrementAndGet()
          for ((lk, lv) in artifact.layerCounts) {
            cumulativeLayerCounts[lk] = (cumulativeLayerCounts[lk] ?: 0) + lv
          }
          if (artifact.extent > maxExtentObserved) maxExtentObserved = artifact.extent
          if (cumulativeSamples.size < 3) {
            for (s in artifact.sampleCoords) {
              if (cumulativeSamples.size < 3) cumulativeSamples += s
            }
          }
          artifact.timing?.let { tm ->
            totalGunzipMs.addAndGet(tm.gunzipMs)
            totalDecodeMs.addAndGet(tm.decodeMs)
          }
          val layersSummary =
            if (cumulativeLayerCounts.isEmpty()) "none"
            else cumulativeLayerCounts.entries.joinToString(", ") { "${it.key}:${it.value}" }
          _mvtDecodeInfo.value =
            MvtDecodeStageInfo(
              ok = true,
              tileCount = emittedCount.get(),
              layersSummary = layersSummary,
              extent = maxExtentObserved,
              sampleCoords = if (cumulativeSamples.isEmpty()) "-" else cumulativeSamples.joinToString(" | "),
              durationMs = (clockMs() - startMs).coerceAtLeast(1L),
              errorReason = null,
              timingBreakdown = "gunzip=${totalGunzipMs.get()}ms dec=${totalDecodeMs.get()}ms bin=${binaryHits.size}",
            )
        }
        send(
          Result.success(
            CorridorGraphUpdate(
              graph = artifact.graph,
              isPartialOrStale = isPartial,
              isFromNetwork = isFromNet,
              requestLog = log,
              tileKey = artifact.tileKey,
              decodedArtifacts = listOf(artifact),
              pois = artifact.pois,
              emptyTileKeys = if (artifact.isEmptyDecoded) setOf(artifact.tileKey) else emptySet(),
            )
          )
        )
      }

      if (binaryHits.isNotEmpty()) {
        for ((idx, binArt) in binaryHits.withIndex()) {
          val isLastLocal = idx == binaryHits.lastIndex && freshPbfRows.isEmpty() && stalePbfRows.isEmpty()
          publishArtifact(
            artifact = binArt,
            isFromNet = false,
            isPartial = missingTiles.isNotEmpty() || !isLastLocal,
            log = null,
          )
        }
      }

      // Decode cached PBF rows concurrently (max 3) & emit incrementally as each finishes
      val pbfToDecode = freshPbfRows + stalePbfRows
      if (pbfToDecode.isNotEmpty()) {
        totalBytesAtomic.addAndGet(pbfToDecode.sumOf { it.rawBytes.size.toLong() })
        val decodeSem = Semaphore(AppConfig.MAX_PARALLEL_TILE_DECODES)
        var firstDecodeErr: String? = null
        coroutineScope {
          pbfToDecode.mapIndexed { idx, row ->
            async(Dispatchers.Default) {
              decodeSem.withPermit {
                val res = decodeEntityToArtifact(row, mode, fromCache = true, clockMs)
                if (res.isSuccess) {
                  val art = res.getOrThrow()
                  binaryCodec.write(art, mode)
                  publishArtifact(
                    artifact = art,
                    isFromNet = false,
                    isPartial = missingTiles.isNotEmpty() || idx < pbfToDecode.lastIndex,
                    log = null,
                  )
                } else if (firstDecodeErr == null) {
                  firstDecodeErr = res.exceptionOrNull()?.message
                }
              }
            }
          }.awaitAll()
        }
        if (emittedCount.get() == 0 && firstDecodeErr != null) {
          _mvtDecodeInfo.value = _mvtDecodeInfo.value.copy(ok = false, errorReason = firstDecodeErr)
        }
      }

      val localHitsCount = binaryHits.size + pbfToDecode.size
      if (localHitsCount > 0 && missingTiles.isEmpty()) {
        _tileFetchInfo.value =
          TileFetchStageInfo(
            ok = true,
            centerTileKey = centerTileKey,
            requestedCount = tilesNeeded.size,
            okCount = tilesNeeded.size,
            cacheHitCount = localHitsCount,
            netHitCount = 0,
            totalBytes = totalBytesAtomic.get(),
            lastHttpStatus = if (binaryHits.isNotEmpty() && pbfToDecode.isEmpty()) "BIN" else "CACHE",
            contentEncoding = "cache",
            durationMs = (clockMs() - startMs).coerceAtLeast(1L),
            reason = null,
          )
        appendLog(
          OverpassRequestLog(
            endpoint = "tile-cache",
            durationMs = (clockMs() - startMs).coerceAtLeast(1L),
            cellsFetched = 0,
            cellsCached = localHitsCount,
            succeeded = true,
            timestampMs = clockMs(),
            source = "tiles",
            method = "CACHE",
            protocol = "local",
            status = "CACHE",
          )
        )
        return@channelFlow
      }

      if (missingTiles.isEmpty()) return@channelFlow

      // Resolve TileJSON if we didn't have it yet
      if (vecSrc == null) {
        val reRes = resolveVectorSource(forceNetwork = true)
        if (reRes.isFailure) {
          val err = reRes.exceptionOrNull() ?: IOException("Style fetch failed")
          _tileUrlInfo.value =
            TileUrlResolutionInfo(
              ok = false,
              howResolved = "style->tilejson",
              template = "",
              scheme = "xyz",
              fetchedAtMs = clockMs(),
              reason = err.message ?: "Style fetch failed",
            )
          appendLog(
            OverpassRequestLog(
              endpoint = styleUrl,
              durationMs = (clockMs() - startMs).coerceAtLeast(1L),
              cellsFetched = 0,
              cellsCached = localHitsCount,
              failures = listOf(err.message ?: "Style fetch failed"),
              succeeded = false,
              timestampMs = clockMs(),
              source = "tiles",
              method = "GET",
              protocol = "http/1.1",
              status = "ERR",
            )
          )
          if (localHitsCount == 0 && alreadyInMemoryFreshKeys.isEmpty()) {
            send(Result.failure(err))
          }
          return@channelFlow
        }
        vecSrc = reRes.getOrThrow()
      }

      if (!vecSrc.hasTransportationLayer) {
        val msg = vecSrc.diagnosticError ?: "transportation layer missing in TileJSON"
        _tileUrlInfo.value =
          TileUrlResolutionInfo(
            ok = false,
            howResolved = vecSrc.howResolved,
            template = vecSrc.tileUrlTemplate,
            scheme = vecSrc.scheme,
            fetchedAtMs = vecSrc.fetchedAtMs,
            reason = msg,
          )
        if (localHitsCount == 0 && alreadyInMemoryFreshKeys.isEmpty()) {
          send(Result.failure(IOException(msg)))
        }
        return@channelFlow
      }

      // Phase B: Download & decode missing tiles with concurrency = 3, prioritizing user's center tile
      val fetchStart = clockMs()
      val netSem = Semaphore(AppConfig.MAX_PARALLEL_TILE_DECODES)
      val srcMutex = Mutex()
      val refreshed404 = AtomicInteger(0)
      val downloadedEntities = ArrayList<TileCacheEntity>()
      val failures = ArrayList<String>()
      var lastProtocol = "http/1.1"
      var lastStatus = "200"
      var lastEncoding = "identity"
      var lastUrl = vecSrc.tileUrlTemplate
      val netOkCount = AtomicInteger(0)

      suspend fun fetchAndEmitSingleTile(tile: ZxyTile, isCenter: Boolean) {
        netSem.withPermit {
          var retried404 = false
          while (true) {
            val activeSrc = srcMutex.withLock { vecSrc!! }
            val urlY = if (activeSrc.scheme.equals("tms", ignoreCase = true)) (1 shl tile.z) - 1 - tile.y else tile.y
            val tileUrl =
              activeSrc.tileUrlTemplate
                .replace("{z}", tile.z.toString())
                .replace("{x}", tile.x.toString())
                .replace("{y}", urlY.toString())
            var shouldRetry = false
            try {
              val req =
                Request.Builder()
                  .url(tileUrl)
                  .header("User-Agent", AppConfig.USER_AGENT)
                  .header("Accept", "*/*")
                  .header("X-Road-Tile-Request", "1")
                  .get()
                  .build()
              client.newCall(req).execute().use { resp ->
                srcMutex.withLock {
                  lastUrl = tileUrl
                  lastProtocol = resp.protocol.toString()
                  lastStatus = resp.code.toString()
                  lastEncoding = resp.header("Content-Encoding")?.takeIf { it.isNotBlank() } ?: "identity"
                }
                if (resp.isSuccessful) {
                  val bytes = resp.body?.bytes()
                  val nowTs = clockMs()
                  if (bytes != null && bytes.isNotEmpty()) {
                    totalBytesAtomic.addAndGet(bytes.size.toLong())
                    val entity =
                      TileCacheEntity(
                        tileKey = tile.key,
                        z = tile.z,
                        x = tile.x,
                        y = tile.y,
                        rawBytes = bytes,
                        fetchedAtMs = nowTs,
                      )
                    srcMutex.withLock { downloadedEntities += entity }
                    tileCacheDao.upsertTiles(listOf(entity))
                    val decRes = decodeEntityToArtifact(entity, mode, fromCache = false, clockMs)
                    if (decRes.isSuccess) {
                      val art = decRes.getOrThrow()
                      binaryCodec.write(art, mode)
                      val doneNow = netOkCount.incrementAndGet()
                      val isLastNet = doneNow >= missingTiles.size
                      publishArtifact(
                        artifact = art,
                        isFromNet = true,
                        isPartial = !isLastNet,
                        log = null,
                      )
                    } else {
                      srcMutex.withLock {
                        failures += "${tile.key}: ${decRes.exceptionOrNull()?.message ?: "decode error"}"
                      }
                    }
                  } else {
                    // HTTP 200 with empty body = genuinely empty map tile (ocean/desert)
                    val emptyBounds = computeTileBounds(tile.z, tile.x, tile.y)
                    val emptyArt =
                      DecodedTileArtifact(
                        tileKey = tile.key,
                        z = tile.z,
                        x = tile.x,
                        y = tile.y,
                        bounds = emptyBounds,
                        graph = RoadGraph(emptyMap(), emptyMap(), emptyMap(), nowTs, fromCache = false, GraphDataSource.TILES, listOf(emptyBounds)),
                        labels = emptyList(),
                        pois = emptyList(),
                        fetchedAtMs = nowTs,
                        isEmptyDecoded = true,
                      )
                    val doneNow = netOkCount.incrementAndGet()
                    publishArtifact(
                      artifact = emptyArt,
                      isFromNet = true,
                      isPartial = doneNow < missingTiles.size,
                      log = null,
                    )
                  }
                } else if ((resp.code == 404 || resp.code == 410) && !retried404) {
                  retried404 = true
                  if (refreshed404.compareAndSet(0, 1)) {
                    invalidateTileJsonCache()
                    val reResolved = resolveVectorSource(forceNetwork = true)
                    if (reResolved.isSuccess) {
                      srcMutex.withLock { vecSrc = reResolved.getOrThrow() }
                      shouldRetry = true
                    } else {
                      srcMutex.withLock { failures += "${tile.key}: HTTP ${resp.code} (TileJSON refresh failed)" }
                    }
                  } else {
                    shouldRetry = true
                  }
                } else {
                  srcMutex.withLock { failures += "${tile.key}: HTTP ${resp.code}" }
                }
              }
            } catch (ce: CancellationException) {
              throw ce
            } catch (t: Throwable) {
              srcMutex.withLock {
                lastUrl = tileUrl
                lastStatus = "ERR"
                failures += "${tile.key}: ${t.message ?: t::class.java.simpleName}"
              }
            }
            if (!shouldRetry) break
            if (isCenter) continue
          }
        }
      }

      // Center tile first if missing so user sees their own road in < 1s
      val centerTile = missingTiles.firstOrNull { it.key == centerTileKey }
      val restTiles = missingTiles.filterNot { it.key == centerTileKey }
      if (centerTile != null) {
        fetchAndEmitSingleTile(centerTile, isCenter = true)
      }
      if (restTiles.isNotEmpty()) {
        coroutineScope {
          restTiles.map { tile ->
            async(Dispatchers.IO) {
              fetchAndEmitSingleTile(tile, isCenter = false)
            }
          }.awaitAll()
        }
      }

      val fetchDur = (clockMs() - fetchStart).coerceAtLeast(1L)
      val okTotal = localHitsCount + netOkCount.get() + alreadyInMemoryFreshKeys.size
      _tileFetchInfo.value =
        TileFetchStageInfo(
          ok = okTotal > 0,
          centerTileKey = centerTileKey,
          requestedCount = tilesNeeded.size,
          okCount = okTotal,
          cacheHitCount = localHitsCount + alreadyInMemoryFreshKeys.size,
          netHitCount = netOkCount.get(),
          totalBytes = totalBytesAtomic.get(),
          lastHttpStatus = lastStatus,
          contentEncoding = lastEncoding,
          durationMs = fetchDur,
          reason = failures.firstOrNull(),
        )

      val log =
        OverpassRequestLog(
          endpoint = lastUrl,
          durationMs = fetchDur,
          cellsFetched = netOkCount.get(),
          cellsCached = localHitsCount + alreadyInMemoryFreshKeys.size,
          failures = failures,
          succeeded = netOkCount.get() > 0 || localHitsCount > 0,
          timestampMs = clockMs(),
          source = "tiles",
          method = "GET",
          protocol = lastProtocol,
          status = lastStatus,
          responseSizeKb = totalBytesAtomic.get() / 1024.0,
        )
      appendLog(log)

      if (netOkCount.get() == 0 && localHitsCount == 0 && alreadyInMemoryFreshKeys.isEmpty()) {
        send(Result.failure(IOException(failures.firstOrNull() ?: "Tile fetch failed")))
      }
    }.flowOn(Dispatchers.IO)

  override suspend fun fetchCorridorGraph(
    center: GeoPoint,
    headingDeg: Double,
    mode: TravelMode,
    radiusMeters: Double,
    forceRefresh: Boolean,
  ): Result<RoadGraph> =
    withContext(Dispatchers.IO) {
      val collected = ArrayList<RoadGraph>()
      var lastFailure: Throwable? = null
      fetchCorridorStream(center, headingDeg, mode, forceRefresh).collect { res ->
        res.fold(
          onSuccess = { collected += it.graph },
          onFailure = { lastFailure = it },
        )
      }
      if (collected.isEmpty()) {
        Result.failure(lastFailure ?: IOException("No tile graph produced"))
      } else {
        Result.success(mergeGraphsSimple(collected))
      }
    }

  override suspend fun getCachedCellCount(): Int =
    withContext(Dispatchers.IO) { tileCacheDao.countTiles() }

  suspend fun getOldestFetchedAtMs(): Long? =
    withContext(Dispatchers.IO) { tileCacheDao.getOldestFetchedAtMs() }

  override suspend fun clearCache() {
    withContext(Dispatchers.IO) {
      invalidateTileJsonCache()
      tileCacheDao.clearAll()
      binaryCodec.clearAll()
    }
  }

  private fun appendLog(log: OverpassRequestLog) {
    _requestLogs.value = (listOf(log) + _requestLogs.value).take(12)
  }

  private fun resolveVectorSource(forceNetwork: Boolean = false): Result<ResolvedVectorSource> {
    val cached = cachedVectorSource
    if (!forceNetwork && cached != null && (clockMs() - cached.fetchedAtMs) < AppConfig.TILEJSON_TTL_MS) {
      return Result.success(cached)
    }
    styleCacheDir.mkdirs()

    val styleFile = File(styleCacheDir, "light_style.json")
    val tileJsonFile = File(styleCacheDir, "vector_tilejson.json")

    val styleJsonRes =
      fetchTextWithDiskFallback(styleUrl, styleFile, AppConfig.TILEJSON_TTL_MS, forceNetwork)
    if (styleJsonRes.isFailure) {
      return Result.failure(styleJsonRes.exceptionOrNull() ?: IOException("Style load failed"))
    }

    return runCatching {
      val root = JSONObject(styleJsonRes.getOrThrow())
      val sources = root.optJSONObject("sources") ?: throw IOException("Style JSON missing sources")
      var vectorSrcObj: JSONObject? = null
      val keys = sources.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        val src = sources.optJSONObject(key) ?: continue
        if (src.optString("type").equals("vector", ignoreCase = true)) {
          vectorSrcObj = src
          break
        }
      }
      val srcObj = vectorSrcObj ?: throw IOException("No vector source in style JSON")

      val directTiles = srcObj.optJSONArray("tiles")
      val tileJsonUrl = srcObj.optString("url", "").takeIf { it.isNotBlank() }

      val tileJsonObj: JSONObject? =
        if (tileJsonUrl != null) {
          val tjRes =
            fetchTextWithDiskFallback(tileJsonUrl, tileJsonFile, AppConfig.TILEJSON_TTL_MS, forceNetwork)
          if (tjRes.isSuccess) JSONObject(tjRes.getOrThrow()) else null
        } else {
          null
        }

      val howResolved = if (tileJsonObj != null) "style->tilejson" else "style-inline-tiles"

      val tilesArr =
        tileJsonObj?.optJSONArray("tiles")
          ?: directTiles
          ?: throw IOException("Vector source missing tiles template")
      val tileTemplate =
        (0 until tilesArr.length())
          .map { tilesArr.optString(it) }
          .firstOrNull { it.isNotBlank() }
          ?: throw IOException("Empty tiles URL template")

      val scheme =
        tileJsonObj?.optString("scheme", "")?.takeIf { it.isNotBlank() }
          ?: srcObj.optString("scheme", "xyz").ifBlank { "xyz" }

      val maxZoom =
        tileJsonObj?.optInt("maxzoom", AppConfig.TILE_GRAPH_ZOOM)
          ?: srcObj.optInt("maxzoom", AppConfig.TILE_GRAPH_ZOOM)

      val vectorLayers =
        tileJsonObj?.optJSONArray("vector_layers")
          ?: srcObj.optJSONArray("vector_layers")
          ?: root.optJSONArray("vector_layers")

      val hasTransportation =
        if (vectorLayers != null) {
          (0 until vectorLayers.length()).any { idx ->
            vectorLayers.optJSONObject(idx)?.optString("id") == "transportation"
          }
        } else {
          val layersArr = root.optJSONArray("layers")
          layersArr != null &&
            (0 until layersArr.length()).any { idx ->
              layersArr.optJSONObject(idx)?.optString("source-layer") == "transportation"
            }
        }

      val now = clockMs()
      val resolved =
        ResolvedVectorSource(
          tileUrlTemplate = tileTemplate,
          maxZoom = maxZoom,
          hasTransportationLayer = hasTransportation,
          diagnosticError = if (hasTransportation) null else "vector_layers missing 'transportation'",
          scheme = scheme,
          fetchedAtMs = now,
          howResolved = howResolved,
        )
      _tileUrlInfo.value =
        TileUrlResolutionInfo(
          ok = hasTransportation,
          howResolved = howResolved,
          template = tileTemplate,
          scheme = scheme,
          fetchedAtMs = now,
          reason = resolved.diagnosticError,
        )
      if (hasTransportation) {
        cachedVectorSource = resolved
      }
      resolved
    }
  }

  private fun fetchTextWithDiskFallback(
    url: String,
    cacheFile: File,
    ttlMs: Long,
    forceNetwork: Boolean,
  ): Result<String> {
    val cached = runCatching { cacheFile.takeIf { it.exists() }?.readText() }.getOrNull()
    val ageMs = if (cacheFile.exists()) clockMs() - cacheFile.lastModified() else Long.MAX_VALUE
    if (!forceNetwork && !cached.isNullOrBlank() && ageMs < ttlMs) {
      return Result.success(cached)
    }
    if (DebugNetworkSimulator.isOfflineBlocked() || DebugNetworkSimulator.isTilesBlocked()) {
      if (!cached.isNullOrBlank()) return Result.success(cached)
    }

    val netRes =
      runCatching {
        val req =
          Request.Builder()
            .url(url)
            .header("User-Agent", AppConfig.USER_AGENT)
            .header("Accept", "*/*")
            .header("X-Road-Tile-Request", "1")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
          val body = resp.body?.string()
          if (body.isNullOrBlank()) throw IOException("Empty JSON body")
          runCatching { cacheFile.writeText(body) }
          body
        }
      }
    if (netRes.isSuccess) return netRes
    if (!forceNetwork && !cached.isNullOrBlank()) return Result.success(cached)
    return netRes
  }

  companion object {
    data class DecodeBuildOutput(
      val graph: RoadGraph,
      val decodeInfo: MvtDecodeStageInfo,
    )

    fun defaultOkHttpClient(): OkHttpClient =
      OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(AppConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(AppConfig.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .callTimeout(AppConfig.CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        .addInterceptor(DebugNetworkSimulator.interceptor)
        .build()

    fun tilesCoveringBbox(bbox: GeoBbox, zoom: Int): List<ZxyTile> {
      val nInt = 1 shl zoom
      val subBoxes = GeoMath.splitBboxAtAntimeridian(bbox.south, bbox.west, bbox.north, bbox.east)
      val res = LinkedHashSet<ZxyTile>()
      for (sub in subBoxes) {
        val (xMin, yMin) = latLonToTile(sub.north, sub.west, zoom)
        val (xMax, yMax) = latLonToTile(sub.south, sub.east, zoom)
        for (rawX in minOf(xMin, xMax)..maxOf(xMin, xMax)) {
          val wx = ((rawX % nInt) + nInt) % nInt
          for (y in minOf(yMin, yMax)..maxOf(yMin, yMax)) {
            res += ZxyTile(zoom, wx, y)
          }
        }
      }
      return res.toList()
    }

    fun tilesCoveringCenter3x3AndBbox(center: GeoPoint, bbox: GeoBbox, zoom: Int): List<ZxyTile> {
      val nInt = 1 shl zoom
      val (cx, cy) = latLonToTile(center.lat, center.lon, zoom)
      val ordered = LinkedHashSet<ZxyTile>()
      ordered += ZxyTile(zoom, ((cx % nInt) + nInt) % nInt, cy.coerceIn(0, nInt - 1))
      for (dy in -1..1) {
        for (dx in -1..1) {
          val wx = (((cx + dx) % nInt) + nInt) % nInt
          val wy = (cy + dy).coerceIn(0, nInt - 1)
          ordered += ZxyTile(zoom, wx, wy)
        }
      }
      for (t in tilesCoveringBbox(bbox, zoom)) {
        ordered += t
      }
      return ordered.toList()
    }

    fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
      val n = 2.0.pow(zoom)
      val maxIdx = n.toInt() - 1
      val clampedLat = GeoMath.clampMercatorLat(lat)
      val normLon = GeoMath.normalizeLon(lon)
      val latRad = Math.toRadians(clampedLat)
      val x = floor((normLon + 180.0) / 360.0 * n).toInt().coerceIn(0, maxIdx)
      val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, maxIdx)
      return x to y
    }

    fun computeTileBounds(z: Int, x: Int, y: Int, extent: Int = 4096): LoadedTileBounds {
      val sw = MvtDecoder.tilePxToGeoPoint(z, x, y, 0, extent, extent)
      val ne = MvtDecoder.tilePxToGeoPoint(z, x, y, extent, 0, extent)
      return LoadedTileBounds(
        z = z,
        x = x,
        y = y,
        south = minOf(sw.lat, ne.lat),
        west = minOf(sw.lon, ne.lon),
        north = maxOf(sw.lat, ne.lat),
        east = maxOf(sw.lon, ne.lon),
      )
    }

    fun decodeEntityToArtifact(
      entity: TileCacheEntity,
      mode: TravelMode,
      fromCache: Boolean,
      clockMs: () -> Long = { System.currentTimeMillis() },
    ): Result<DecodedTileArtifact> =
      runCatching {
        val tStart = System.nanoTime()
        val dec =
          MvtDecoder.decodeWithTiming(
            rawBytes = entity.rawBytes,
            z = entity.z,
            x = entity.x,
            y = entity.y,
            layerFilter = setOf("transportation", "transportation_name", "poi"),
          )
        val tile = dec.tile
        val ext = tile.layers.values.firstOrNull()?.extent ?: 4096
        val layerCounts = LinkedHashMap<String, Int>()
        val samples = mutableListOf<String>()
        for ((lName, layer) in tile.layers) {
          layerCounts[lName] = layer.features.size
          if (samples.size < 2 && lName == "transportation") {
            for (feat in layer.features) {
              val pt = feat.geometries.firstOrNull()?.firstOrNull()
              if (pt != null) {
                samples += String.format(Locale.US, "%.5f,%.5f", pt.lat, pt.lon)
                if (samples.size >= 2) break
              }
            }
          }
        }
        val labels = extractLabelsFromTiles(listOf(tile))
        val pois = extractPoisFromTile(tile)
        val graph =
          buildRoadGraphFromDecodedTiles(
            tiles = listOf(tile),
            mode = mode,
            fetchedAtMs = entity.fetchedAtMs,
            fromCache = fromCache,
            preExtractedLabels = labels,
          )
        val totalMs = ((System.nanoTime() - tStart) / 1_000_000L).coerceAtLeast(1L)
        val bounds = computeTileBounds(entity.z, entity.x, entity.y, ext)
        val isEmpty = graph.ways.isEmpty() && (tile.layers["transportation"]?.features?.isEmpty() != false)
        DecodedTileArtifact(
          tileKey = entity.tileKey,
          z = entity.z,
          x = entity.x,
          y = entity.y,
          bounds = bounds,
          graph = graph,
          labels = labels,
          pois = pois,
          fetchedAtMs = entity.fetchedAtMs,
          isEmptyDecoded = isEmpty,
          extent = ext,
          layerCounts = layerCounts,
          sampleCoords = samples,
          timing = TileDecodeTiming(entity.tileKey, dec.gunzipMs, dec.decodeMs, totalMs),
        )
      }

    fun buildRoadGraphFromEntities(
      entities: List<TileCacheEntity>,
      mode: TravelMode,
      fromCache: Boolean,
    ): RoadGraph = buildRoadGraphWithStats(entities, mode, fromCache).graph

    fun buildRoadGraphWithStats(
      entities: List<TileCacheEntity>,
      mode: TravelMode,
      fromCache: Boolean,
      clockMs: () -> Long = { System.currentTimeMillis() },
    ): DecodeBuildOutput {
      val t0 = clockMs()
      val decodedTiles = ArrayList<MvtDecoder.MvtTile>(entities.size)
      var firstError: String? = null
      var gunzipSum = 0L
      var decodeSum = 0L

      for (ent in entities) {
        runCatching {
          MvtDecoder.decodeWithTiming(
            rawBytes = ent.rawBytes,
            z = ent.z,
            x = ent.x,
            y = ent.y,
            layerFilter = setOf("transportation", "transportation_name"),
          )
        }.fold(
          onSuccess = { res ->
            decodedTiles += res.tile
            gunzipSum += res.gunzipMs
            decodeSum += res.decodeMs
          },
          onFailure = { err ->
            if (firstError == null) {
              firstError = "${ent.tileKey}: ${err.message ?: err::class.java.simpleName}"
            }
          },
        )
      }

      val newest = entities.maxOfOrNull { it.fetchedAtMs } ?: 0L
      val stitchStart = clockMs()
      val graph = buildRoadGraphFromDecodedTiles(decodedTiles, mode, newest, fromCache)
      val stitchDur = (clockMs() - stitchStart).coerceAtLeast(0L)

      val layerCounts = LinkedHashMap<String, Int>()
      var maxExtent = 0
      val samples = mutableListOf<String>()
      for (tile in decodedTiles) {
        for ((lName, layer) in tile.layers) {
          layerCounts[lName] = (layerCounts[lName] ?: 0) + layer.features.size
          if (layer.extent > maxExtent) maxExtent = layer.extent
          if (samples.size < 3 && lName == "transportation") {
            for (feat in layer.features) {
              val pt = feat.geometries.firstOrNull()?.firstOrNull()
              if (pt != null) {
                samples += String.format(Locale.US, "%.5f,%.5f", pt.lat, pt.lon)
                if (samples.size >= 3) break
              }
            }
          }
        }
      }
      val layersSummary =
        if (layerCounts.isEmpty()) "none"
        else layerCounts.entries.joinToString(", ") { "${it.key}:${it.value}" }
      val dur = (clockMs() - t0).coerceAtLeast(1L)
      val info =
        MvtDecodeStageInfo(
          ok = decodedTiles.isNotEmpty() && firstError == null,
          tileCount = decodedTiles.size,
          layersSummary = layersSummary,
          extent = maxExtent,
          sampleCoords = if (samples.isEmpty()) "-" else samples.joinToString(" | "),
          durationMs = dur,
          errorReason = firstError,
          timingBreakdown = "gunzip=${gunzipSum}ms dec=${decodeSum}ms stitch=${stitchDur}ms",
        )
      return DecodeBuildOutput(graph = graph, decodeInfo = info)
    }

    /**
     * Builds a stitched [RoadGraph] from live MapLibre `querySourceFeatures` (`transportation` &
     * `transportation_name`), tagging all produced ways as [GraphDataSource.MAP].
     */
    fun buildRoadGraphFromLiveFeatures(
      features: List<LiveMapVectorFeature>,
      @Suppress("UNUSED_PARAMETER") mode: TravelMode = TravelMode.DRIVING,
      fetchedAtMs: Long = System.currentTimeMillis(),
    ): RoadGraph {
      val labelFeats =
        features.filter { it.layerName == "transportation_name" }.map { f ->
          MvtDecoder.MvtFeature(
            id = 0L,
            geomType = MvtDecoder.GeomType.LINESTRING,
            tags = f.tags,
            geometries = f.geometries,
          )
        }
      val transFeats =
        features.filter { it.layerName == "transportation" }.map { f ->
          MvtDecoder.MvtFeature(
            id = 0L,
            geomType = MvtDecoder.GeomType.LINESTRING,
            tags = f.tags,
            geometries = f.geometries,
          )
        }
      val layers = HashMap<String, MvtDecoder.MvtLayer>()
      if (labelFeats.isNotEmpty()) {
        layers["transportation_name"] = MvtDecoder.MvtLayer("transportation_name", 4096, labelFeats)
      }
      if (transFeats.isNotEmpty()) {
        layers["transportation"] = MvtDecoder.MvtLayer("transportation", 4096, transFeats)
      }
      val syntheticTile = MvtDecoder.MvtTile(z = AppConfig.TILE_GRAPH_ZOOM, x = 0, y = 0, layers = layers)
      return buildRoadGraphFromDecodedTiles(
        tiles = listOf(syntheticTile),
        mode = TravelMode.DRIVING,
        fetchedAtMs = fetchedAtMs,
        fromCache = false,
        graphSource = GraphDataSource.MAP,
        populateLoadedBounds = false,
      )
    }

    fun extractLabelsFromTiles(tiles: List<MvtDecoder.MvtTile>): List<TileNameLabelRecord> {
      val labels = mutableListOf<TileNameLabelRecord>()
      for (tile in tiles) {
        val nameLayer = tile.layers["transportation_name"] ?: continue
        for (feat in nameLayer.features) {
          val t = feat.tags
          val tagMap = LinkedHashMap<String, String>()
          for ((k, v) in t) {
            val trimmed = v.trim()
            if (trimmed.isNotEmpty() && (OsmTags.isAllowedKey(k) || k == "name_en" || k == "name:latin" || k == "name:nonlatin")) {
              tagMap[k] = trimmed
            }
          }
          val hasAnyNameOrRef =
            tagMap.keys.any {
              it == "name" || it == "ref" || it.startsWith("name:") || it == "name_en"
            }
          if (!hasAnyNameOrRef) continue
          val lblClass = t["class"]?.lowercase(Locale.US)
          for (geom in feat.geometries) {
            if (geom.isNotEmpty()) {
              labels += TileNameLabelRecord(geom, lblClass, tagMap)
            }
          }
        }
      }
      return labels
    }

    fun extractPoisFromTile(tile: MvtDecoder.MvtTile): List<PoiItem> {
      val poiLayer = tile.layers["poi"] ?: return emptyList()
      val res = ArrayList<PoiItem>(poiLayer.features.size)
      var idx = 0L
      for (feat in poiLayer.features) {
        val pt = feat.geometries.firstOrNull()?.firstOrNull() ?: continue
        val t = feat.tags
        val tagMap = LinkedHashMap<String, String>()
        for ((k, v) in t) {
          val trimmed = v.trim()
          if (trimmed.isNotEmpty() && (OsmTags.isAllowedKey(k) || k == "name_en" || k == "class" || k == "subclass")) {
            tagMap[k] = trimmed
          }
        }
        val tags = OsmTags.fromMap(tagMap)
        val bName = BilingualName.fromOsmTags(tags)
        if (!bName.hasAny) continue
        val cls = t["class"]?.lowercase(Locale.US) ?: ""
        val sub = t["subclass"]?.lowercase(Locale.US) ?: ""
        val cat = mapPoiCategory(cls, sub)
        val rawId =
          if (feat.id != 0L) -((tile.x.toLong() shl 20) xor (tile.y.toLong() shl 8) xor feat.id.and(0xFFFFFL)) - 3_000_000_000L
          else -(((tile.x.toLong() * 31L + tile.y.toLong()) * 1000L + idx++) + 3_000_000_000L)
        res +=
          PoiItem(
            id = rawId,
            name = bName,
            category = cat,
            point = GeoPoint(roundTo1e6(pt.lat), GeoMath.normalizeLon(roundTo1e6(pt.lon))),
            osmTags = tagMap,
            source = GraphDataSource.TILES,
          )
      }
      return res
    }

    private fun mapPoiCategory(cls: String, sub: String): PoiCategory =
      when {
        cls == "fuel" || sub == "fuel" -> PoiCategory.FUEL
        cls == "parking" || sub == "parking" -> PoiCategory.PARKING
        cls == "hospital" || sub in setOf("hospital", "clinic", "pharmacy") -> PoiCategory.HOSPITAL
        cls == "police" || sub == "police" -> PoiCategory.POLICE
        cls in setOf("railway", "bus", "subway", "station") || sub in setOf("station", "halt", "tram_stop", "bus_station", "subway_entrance") ->
          PoiCategory.TRANSIT
        cls in setOf("restaurant", "fast_food") || sub in setOf("restaurant", "fast_food") -> PoiCategory.RESTAURANT
        cls == "cafe" || sub == "cafe" -> PoiCategory.CAFE
        cls == "shop" || sub in setOf("supermarket", "mall", "convenience", "shop") -> PoiCategory.SHOP
        cls in setOf("attraction", "monument", "museum", "place_of_worship") || sub in setOf("mosque", "church", "monument", "museum", "landmark") ->
          PoiCategory.LANDMARK
        else -> PoiCategory.OTHER
      }

    fun inspectLabelsNearWay(
      way: OsmWay,
      labels: List<TileNameLabelRecord>,
      radiusMeters: Double = 20.0,
    ): List<NearbyTileLabelDiagnostic> {
      if (labels.isEmpty() || way.points.size < 2) return emptyList()
      val tClass = way.tags.highway?.lowercase(Locale.US)
      val midWayIdx = (way.points.size / 2).coerceIn(0, way.points.lastIndex)
      val midWayPt = way.points[midWayIdx]
      val matches = ArrayList<NearbyTileLabelDiagnostic>()

      for (lbl in labels) {
        val sample = lbl.points.firstOrNull() ?: continue
        if (abs(sample.lat - midWayPt.lat) > 0.008 || abs(GeoMath.unwrapLonDelta(sample.lon - midWayPt.lon)) > 0.008) {
          continue
        }
        var bestDist = Double.MAX_VALUE
        var bestLblBearing: Double? = null
        var bestWaySnapSegIdx = 0
        for (i in lbl.points.indices) {
          val lp = lbl.points[i]
          val snap = GeoMath.snapToPolyline(lp, way.points)
          if (snap.distanceMeters < bestDist) {
            bestDist = snap.distanceMeters
            bestWaySnapSegIdx = snap.segmentIndex
            bestLblBearing =
              if (lbl.points.size >= 2) {
                val p0 = lbl.points[i.coerceAtMost(lbl.points.lastIndex - 1)]
                val p1 = lbl.points[(i + 1).coerceAtMost(lbl.points.lastIndex)]
                if (GeoMath.haversineMeters(p0, p1) > 0.5) GeoMath.bearingDeg(p0, p1) else null
              } else null
          }
        }
        if (bestDist <= radiusMeters) {
          val reason =
            when {
              !sameClassFamily(tClass, lbl.labelClass) -> "class"
              bestLblBearing != null && isPerpendicularConflict(way.points, bestWaySnapSegIdx, bestLblBearing) -> "perp"
              bestDist > AppConfig.TILE_NAME_ATTACH_MAX_METERS -> "dist>${AppConfig.TILE_NAME_ATTACH_MAX_METERS.toInt()}m"
              else -> null
            }
          matches +=
            NearbyTileLabelDiagnostic(
              name = lbl.tagMap["name"],
              nameAr = lbl.tagMap["name:ar"],
              nameEn = lbl.tagMap["name:en"] ?: lbl.tagMap["name_en"],
              ref = lbl.tagMap["ref"],
              labelClass = lbl.labelClass,
              distanceMeters = bestDist,
              rejectReason = reason,
            )
        }
      }
      return matches.sortedBy { it.distanceMeters }.take(5)
    }

    /**
     * Converts decoded MVT tiles (`transportation` + `transportation_name`) into a stitched [RoadGraph].
     * Uses a 0.001° spatial hash index for label attachment within 6.0 m with directional alignment filtering.
     */
    fun buildRoadGraphFromDecodedTiles(
      tiles: List<MvtDecoder.MvtTile>,
      @Suppress("UNUSED_PARAMETER") mode: TravelMode = TravelMode.DRIVING,
      fetchedAtMs: Long,
      fromCache: Boolean,
      graphSource: GraphDataSource = GraphDataSource.TILES,
      populateLoadedBounds: Boolean = true,
      preExtractedLabels: List<TileNameLabelRecord>? = null,
    ): RoadGraph {
      val labels = preExtractedLabels ?: extractLabelsFromTiles(tiles)
      val labelIndex = NameLabelIndex.build(labels)

      data class RawSegment(
        val wayIdSeed: Long,
        val points: List<GeoPoint>,
        val tClass: String?,
        val mappedHighway: String,
        val rawTagMap: Map<String, String>,
      )
      val segments = mutableListOf<RawSegment>()
      val namespace = if (graphSource == GraphDataSource.MAP) WAY_NS_MAP else WAY_NS_TILES
      val countersByTile = HashMap<Long, Long>()

      for (tile in tiles) {
        require(tile.z in 0..MAX_TILE_ZOOM) { "Tile zoom must be <= $MAX_TILE_ZOOM (got ${tile.z})" }
        val transLayer = tile.layers["transportation"] ?: continue
        val tileKeyPacked =
          (tile.z.toLong() shl 40) or
            ((tile.x.toLong() and 0xFFFFL) shl 20) or
            (tile.y.toLong() and 0xFFFFL)
        var tileCounter = countersByTile[tileKeyPacked] ?: 0L
        var overflowedTile = tileCounter >= MAX_WAY_COUNTER

        for (feat in transLayer.features) {
          if (overflowedTile) break
          if (feat.geomType != MvtDecoder.GeomType.LINESTRING) continue
          val props = feat.tags
          val tClass = props["class"]?.lowercase(Locale.US)
          val subclass = props["subclass"]?.lowercase(Locale.US)
          val ramp = props["ramp"] == "1" || props["ramp"] == "true"
          val service = props["service"]?.lowercase(Locale.US)
          val mappedHighway = mapTileClassToHighway(tClass, subclass, service, ramp) ?: continue

          val baseMap = LinkedHashMap<String, String>()
          baseMap["highway"] = mappedHighway
          service?.let { baseMap["service"] = it }
          when (props["oneway"]) {
            "1", "true", "yes" -> baseMap["oneway"] = "yes"
            "-1" -> baseMap["oneway"] = "-1"
          }
          val brunnel = props["brunnel"]?.lowercase(Locale.US)
          if (brunnel == "bridge") baseMap["bridge"] = "yes"
          if (brunnel == "tunnel") baseMap["tunnel"] = "yes"
          props["layer"]?.takeIf { it.isNotBlank() }?.let { baseMap["layer"] = it }

          for (poly in feat.geometries) {
            if (poly.size < 2) continue
            if (tileCounter >= MAX_WAY_COUNTER) {
              overflowedTile = true
              runCatching {
                android.util.Log.w(
                  "TileRoadGraphSource",
                  "Tile ${tile.z}/${tile.x}/${tile.y} (ns=$namespace) exceeded $MAX_WAY_COUNTER segments; skipping further segments",
                )
              }
              break
            }
            val sanitized =
              poly.map { pt ->
                GeoPoint(
                  lat = roundTo1e6(pt.lat),
                  lon = GeoMath.normalizeLon(roundTo1e6(pt.lon)),
                )
              }
            val wayId = encodeWayId(namespace, tile.z, tile.x, tile.y, tileCounter++)
            segments += RawSegment(wayId, sanitized, tClass, mappedHighway, baseMap)
          }
        }
        countersByTile[tileKeyPacked] = tileCounter
      }

      val nodes = HashMap<Long, OsmNode>(segments.size * 4)
      val ways = HashMap<Long, OsmWay>(segments.size)
      val nodeToWays = HashMap<Long, MutableList<Long>>(segments.size * 4)

      for (seg in segments) {
        val attached = findNearestLabelIndexed(seg.points, seg.mappedHighway, seg.tClass, labelIndex)
        val mergedTags =
          if (attached != null) {
            val combined = LinkedHashMap<String, String>(seg.rawTagMap.size + attached.tagMap.size)
            combined.putAll(attached.tagMap)
            combined.putAll(seg.rawTagMap)
            combined.remove("destination")
            combined.remove("destination:ref")
            OsmTags.fromMap(combined)
          } else {
            OsmTags.fromMap(seg.rawTagMap)
          }

        val ndIds = ArrayList<Long>(seg.points.size)
        val cleanPts = ArrayList<GeoPoint>(seg.points.size)
        for (pt in seg.points) {
          val nid = quantizedNodeId1e6(pt.lat, pt.lon)
          if (ndIds.lastOrNull() == nid) continue
          if (!nodes.containsKey(nid)) {
            nodes[nid] = OsmNode(id = nid, point = pt)
          }
          ndIds += nid
          cleanPts += pt
        }
        if (cleanPts.size < 2) continue

        val way =
          OsmWay(
            id = seg.wayIdSeed,
            nodeIds = ndIds,
            points = cleanPts,
            tags = mergedTags,
            source = graphSource,
          )
        ways[way.id] = way
        for (nid in ndIds.distinct()) {
          val list = nodeToWays.getOrPut(nid) { ArrayList(3) }
          if (way.id !in list) list.add(way.id)
        }
      }

      val loadedTiles =
        if (!populateLoadedBounds) {
          emptyList()
        } else {
          tiles.map { tile ->
            val ext = tile.layers.values.firstOrNull()?.extent ?: 4096
            computeTileBounds(tile.z, tile.x, tile.y, ext)
          }
        }

      return RoadGraph(
        nodes = nodes,
        ways = ways,
        nodeToWays = nodeToWays,
        fetchedAtMs = fetchedAtMs,
        fromCache = fromCache,
        source = graphSource,
        loadedTiles = loadedTiles,
      )
    }

    private fun findNearestLabelIndexed(
      wayPoints: List<GeoPoint>,
      mappedHighway: String,
      transportClass: String?,
      labelIndex: NameLabelIndex,
    ): TileNameLabelRecord? {
      if (wayPoints.size < 2) return null
      var wMinLat = 90.0
      var wMaxLat = -90.0
      var wMinLon = 180.0
      var wMaxLon = -180.0
      for (p in wayPoints) {
        if (p.lat < wMinLat) wMinLat = p.lat
        if (p.lat > wMaxLat) wMaxLat = p.lat
        if (p.lon < wMinLon) wMinLon = p.lon
        if (p.lon > wMaxLon) wMaxLon = p.lon
      }
      val candIndices = labelIndex.queryCandidateIndices(wMinLat, wMaxLat, wMinLon, wMaxLon)
      if (candIndices.isEmpty()) return null

      val maxDistM = AppConfig.TILE_NAME_ATTACH_MAX_METERS
      val bboxMarginLat = (maxDistM + 6.0) / 111_320.0
      val cosLat = cos(Math.toRadians((wMinLat + wMaxLat) * 0.5)).coerceAtLeast(0.15)
      val bboxMarginLon = (maxDistM + 6.0) / (111_320.0 * cosLat)

      var bestLabel: TileNameLabelRecord? = null
      var bestDist = maxDistM + 0.001

      for (idx in candIndices) {
        val lbl = labelIndex.labelAt(idx)
        if (!sameClassFamily(mappedHighway, lbl.labelClass) && !sameClassFamily(transportClass, lbl.labelClass)) {
          continue
        }
        val samples = labelIndex.denseSamplesAt(idx)
        for ((sp, lblBrng) in samples) {
          if (sp.lat < wMinLat - bboxMarginLat || sp.lat > wMaxLat + bboxMarginLat ||
            sp.lon < wMinLon - bboxMarginLon || sp.lon > wMaxLon + bboxMarginLon
          ) {
            continue
          }
          val snap = GeoMath.snapToPolyline(sp, wayPoints)
          if (snap.distanceMeters <= maxDistM && snap.distanceMeters < bestDist) {
            if (lblBrng != null && isPerpendicularConflict(wayPoints, snap.segmentIndex, lblBrng)) {
              continue
            }
            bestDist = snap.distanceMeters
            bestLabel = lbl
          }
        }
      }
      return bestLabel
    }

    private fun isPerpendicularConflict(
      wayPoints: List<GeoPoint>,
      segIdx: Int,
      labelBearingDeg: Double,
    ): Boolean {
      if (wayPoints.size < 2) return false
      val i = segIdx.coerceIn(0, wayPoints.lastIndex - 1)
      val a = wayPoints[i]
      val b = wayPoints[i + 1]
      if (GeoMath.haversineMeters(a, b) < 0.5) return false
      val wayBearing = GeoMath.bearingDeg(a, b)
      val diff = GeoMath.absAngleDiff(wayBearing, labelBearingDeg)
      return diff > 35.0 && diff < 145.0
    }

    private fun sameClassFamily(transportClass: String?, labelClass: String?): Boolean {
      if (transportClass == null || labelClass == null) return true
      val tc = transportClass.removeSuffix("_link")
      val lc = labelClass.removeSuffix("_link")
      if (tc == lc) return true
      return classFamily(tc) == classFamily(lc)
    }

    private fun classFamily(cls: String): String =
      when (cls) {
        "motorway", "trunk" -> "express"
        "primary", "secondary", "tertiary" -> "arterial"
        "minor", "residential", "unclassified", "living_street", "service", "track" -> "local"
        "path", "footway", "pedestrian", "steps", "cycleway" -> "nonmotorized"
        else -> cls
      }

    private fun mapTileClassToHighway(
      tClass: String?,
      subclass: String?,
      service: String?,
      ramp: Boolean,
    ): String? {
      val base = tClass ?: subclass ?: return null
      if (base == "ferry" || base == "rail" || base == "transit" || base == "aerialway") return null
      if (ramp) {
        return when (base.removeSuffix("_link")) {
          "motorway" -> "motorway_link"
          "trunk" -> "trunk_link"
          "primary" -> "primary_link"
          "secondary" -> "secondary_link"
          "tertiary" -> "tertiary_link"
          else -> "secondary_link"
        }
      }
      return when (base) {
        "minor" -> subclass ?: "residential"
        "path" -> subclass ?: "path"
        "service" -> "service"
        else -> base
      }
    }

    const val WAY_NS_TILES = 1
    const val WAY_NS_MAP = 2
    const val MAX_WAY_COUNTER = 1L shl 20
    const val MAX_TILE_ZOOM = 16

    fun encodeWayId(namespace: Int, z: Int, x: Int, y: Int, counter: Long): Long {
      require(namespace in 1..31) { "Namespace must be in 1..31 (got $namespace)" }
      require(z in 0..MAX_TILE_ZOOM) { "Zoom must be <= $MAX_TILE_ZOOM (got $z)" }
      require(counter in 0 until MAX_WAY_COUNTER) { "Way counter $counter exceeds < $MAX_WAY_COUNTER" }
      val magnitude =
        (namespace.toLong() shl 57) or
          (z.toLong() shl 52) or
          ((x.toLong() and 0xFFFFL) shl 36) or
          ((y.toLong() and 0xFFFFL) shl 20) or
          counter
      return -magnitude
    }

    internal data class SimpleMergeResult(
      val graph: RoadGraph,
      val idCollisions: Int,
      val danglingNodeRefs: Int,
    )

    internal fun mergeGraphsSimpleWithStats(graphs: List<RoadGraph>): SimpleMergeResult {
      if (graphs.size == 1) {
        val g = graphs[0]
        val cleanNodeToWays = HashMap<Long, MutableList<Long>>(g.nodeToWays.size)
        var dangling = 0
        for ((nid, wIds) in g.nodeToWays) {
          val distinctIds = ArrayList<Long>(wIds.size)
          for (wid in wIds) {
            if (wid !in g.ways) dangling++
            if (wid !in distinctIds) distinctIds += wid
          }
          cleanNodeToWays[nid] = distinctIds
        }
        return SimpleMergeResult(g.copy(nodeToWays = cleanNodeToWays), idCollisions = 0, danglingNodeRefs = dangling)
      }
      val nodes = HashMap<Long, OsmNode>()
      val ways = HashMap<Long, OsmWay>()
      val wayOriginGraphIdx = HashMap<Long, Int>()
      val nodeToWays = HashMap<Long, MutableList<Long>>()
      val bounds = ArrayList<LoadedTileBounds>()
      var latest = 0L
      var allCached = true
      var collisions = 0
      for ((gIdx, g) in graphs.withIndex()) {
        if (g.fetchedAtMs > latest) latest = g.fetchedAtMs
        if (!g.fromCache) allCached = false
        nodes.putAll(g.nodes)
        bounds.addAll(g.loadedTiles)
        for ((id, w) in g.ways) {
          val prev = ways[id]
          val prevIdx = wayOriginGraphIdx[id]
          if (prev != null && (prev.source != w.source || (w.source == GraphDataSource.TILES && prevIdx != gIdx))) {
            collisions++
          }
          ways[id] = w
          wayOriginGraphIdx[id] = gIdx
          for (nid in w.nodeIds.distinct()) {
            val list = nodeToWays.getOrPut(nid) { ArrayList(3) }
            if (id !in list) list.add(id)
          }
        }
      }
      var dangling = 0
      for (list in nodeToWays.values) {
        for (wid in list) {
          if (wid !in ways) dangling++
        }
      }
      return SimpleMergeResult(
        graph = RoadGraph(nodes, ways, nodeToWays, latest, allCached, GraphDataSource.TILES, bounds.distinct()),
        idCollisions = collisions,
        danglingNodeRefs = dangling,
      )
    }

    private fun mergeGraphsSimple(graphs: List<RoadGraph>): RoadGraph =
      mergeGraphsSimpleWithStats(graphs).graph

    fun roundTo1e6(v: Double): Double =
      (v * AppConfig.TILE_ENDPOINT_ROUND_SCALE).roundToLong() / AppConfig.TILE_ENDPOINT_ROUND_SCALE

    fun quantizedNodeId1e6(lat: Double, lon: Double): Long {
      val qLat = (lat * AppConfig.TILE_ENDPOINT_ROUND_SCALE).roundToLong()
      val qLon = (GeoMath.normalizeLon(lon) * AppConfig.TILE_ENDPOINT_ROUND_SCALE).roundToLong()
      val hash = (qLat * 1_000_003L) xor (qLon shl 24) xor (qLon ushr 4)
      return -(kotlin.math.abs(hash and 0x3FFFFFFFFFFFFFFFL) + 1_000_000_000L)
    }
  }
}
