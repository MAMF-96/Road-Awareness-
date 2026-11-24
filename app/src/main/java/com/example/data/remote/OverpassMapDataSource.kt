package com.example.data.remote

import android.util.Log
import com.example.config.AppConfig
import com.example.data.interfaces.CorridorGraphUpdate
import com.example.data.interfaces.MapDataSource
import com.example.data.local.CorridorCacheDao
import com.example.data.local.CorridorCacheEntity
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.LoaderStats60s
import com.example.data.model.OsmNode
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.OverpassEndpointStats
import com.example.data.model.OverpassEndpointTestResult
import com.example.data.model.OverpassRequestLog
import com.example.data.model.RoadGraph
import com.example.data.model.TravelMode
import com.example.domain.GeoMath
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.cos
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

data class GridCellId(
  val mode: TravelMode,
  val latIdx: Int,
  val lonIdx: Int,
) {
  val key: String get() = "${mode.name}_${latIdx}_${lonIdx}"
  val south: Double get() = latIdx * AppConfig.CACHE_CELL_SIZE_DEG
  val north: Double get() = (latIdx + 1) * AppConfig.CACHE_CELL_SIZE_DEG
  val west: Double get() = GeoMath.normalizeLon(lonIdx * AppConfig.CACHE_CELL_SIZE_DEG)
  val east: Double get() = GeoMath.normalizeLon((lonIdx + 1) * AppConfig.CACHE_CELL_SIZE_DEG)
  val centerLat: Double get() = (south + north) * 0.5
  val centerLon: Double get() = GeoMath.normalizeLon((lonIdx + 0.5) * AppConfig.CACHE_CELL_SIZE_DEG)

  fun toBbox(): GeoBbox {
    val rawWest = lonIdx * AppConfig.CACHE_CELL_SIZE_DEG
    val rawEast = (lonIdx + 1) * AppConfig.CACHE_CELL_SIZE_DEG
    val nWest = GeoMath.normalizeLon(rawWest)
    var nEast = GeoMath.normalizeLon(rawEast)
    if (nEast < nWest && rawEast > 180.0 && rawWest <= 180.0) {
      nEast = 180.0
    }
    return GeoBbox(
      south = GeoMath.clampMercatorLat(south),
      west = nWest,
      north = GeoMath.clampMercatorLat(north),
      east = nEast,
    )
  }
}

data class GeoBbox(
  val south: Double,
  val west: Double,
  val north: Double,
  val east: Double,
)

class OverpassMapDataSource(
  private val cacheDao: CorridorCacheDao,
  private val client: OkHttpClient = defaultOkHttpClient(),
  private val endpoints: List<String> = AppConfig.OVERPASS_ENDPOINTS,
  private val clockMs: () -> Long = { System.currentTimeMillis() },
  private val hedgeDelayMs: Long = AppConfig.OVERPASS_HEDGE_DELAY_MS,
) : MapDataSource {

  private val cellFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val inFlightSemaphore = Semaphore(AppConfig.OVERPASS_MAX_IN_FLIGHT)
  private val endpointCooldownUntilMs = ConcurrentHashMap<String, Long>()
  private val endpointFailureStreak = ConcurrentHashMap<String, Int>()
  private val endpointScore = ConcurrentHashMap<String, Double>()
  private val endpointStatsMap = ConcurrentHashMap<String, OverpassEndpointStats>()
  private val activeCellTasks = ConcurrentHashMap<String, Deferred<CellFetchOutcome>>()
  @Volatile private var activeCorridorKeySet: Set<String> = emptySet()

  private val currentAttemptStartMs = AtomicLong(0L)
  private val started60sTimestamps = ConcurrentLinkedDeque<Long>()
  private val completed60sTimestamps = ConcurrentLinkedDeque<Long>()
  private val cancelled60sTimestamps = ConcurrentLinkedDeque<Long>()
  private val lastCellSizeMeters = AtomicInteger(400)

  private val activeHttpCalls = AtomicInteger(0)
  val maxObservedConcurrentHttpCalls = AtomicInteger(0)

  private val _requestLogs = MutableStateFlow<List<OverpassRequestLog>>(emptyList())
  override val requestLogs: StateFlow<List<OverpassRequestLog>> = _requestLogs.asStateFlow()

  data class CellFetchOutcome(
    val cellKey: String,
    val succeeded: Boolean,
    val cachedEntity: CorridorCacheEntity?,
    val log: OverpassRequestLog?,
    val failures: List<String>,
    val error: Throwable?,
    val cellMeters: Int,
  )

  fun currentActiveRequestAgeMs(): Long? {
    val start = currentAttemptStartMs.get()
    if (start <= 0L || activeHttpCalls.get() <= 0) return null
    return (clockMs() - start).coerceAtLeast(0L)
  }

  fun endpointStats(): List<OverpassEndpointStats> =
    endpoints.map { ep ->
      val host = shortHost(ep)
      endpointStatsMap[host] ?: OverpassEndpointStats(
        host = host,
        lastStatus = "IDLE",
        durationMs = 0L,
        cellMeters = lastCellSizeMeters.get(),
        edgesAdded = 0,
      )
    }

  fun loaderStats60s(): LoaderStats60s {
    val cutoff = clockMs() - 60_000L
    pruneOlderThan(started60sTimestamps, cutoff)
    pruneOlderThan(completed60sTimestamps, cutoff)
    pruneOlderThan(cancelled60sTimestamps, cutoff)
    return LoaderStats60s(
      started60s = started60sTimestamps.size,
      completed60s = completed60sTimestamps.size,
      cancelled60s = cancelled60sTimestamps.size,
    )
  }

  fun cancelAllActiveRequests() {
    for ((_, task) in activeCellTasks.entries.toList()) {
      if (task.isActive) {
        task.cancel(CancellationException("Cleared cache / stopped"))
        cancelled60sTimestamps.addLast(clockMs())
      }
    }
    activeCellTasks.clear()
    activeCorridorKeySet = emptySet()
  }

  private fun pruneOlderThan(deque: ConcurrentLinkedDeque<Long>, cutoffMs: Long) {
    while (true) {
      val first = deque.peekFirst() ?: break
      if (first < cutoffMs) deque.pollFirst() else break
    }
  }

  override fun fetchCorridorStream(
    center: GeoPoint,
    headingDeg: Double,
    mode: TravelMode,
    forceRefresh: Boolean,
  ): Flow<Result<CorridorGraphUpdate>> =
    flow {
      val now = clockMs()
      val corridorBbox = computeCorridorBbox(center, headingDeg, mode)
      val unorderedCells = cellsIntersectingBbox(corridorBbox, mode)
      val corridorCells = orderCellsByPriority(unorderedCells, center, headingDeg)
      val corridorKeys = corridorCells.map { it.key }
      val nextCorridorSet = corridorKeys.toHashSet()
      activeCorridorKeySet = nextCorridorSet

      // Non-abortive corridor updates: cancel ONLY per-cell tasks whose cell no longer intersects corridor
      for ((taskKey, task) in activeCellTasks.entries.toList()) {
        val baseKey = taskKey.removePrefix("P2_")
        if (forceRefresh || baseKey !in nextCorridorSet) {
          if (task.isActive) {
            task.cancel(CancellationException("Cell left active corridor"))
            cancelled60sTimestamps.addLast(clockMs())
          }
          activeCellTasks.remove(taskKey)
        }
      }

      val cachedRows = cacheDao.getCells(corridorKeys)
      val cachedByKey = cachedRows.associateBy { it.cellKey }

      val freshCached = mutableListOf<CorridorCacheEntity>()
      val staleCached = mutableListOf<CorridorCacheEntity>()
      val missingCells = mutableListOf<GridCellId>()

      for (cell in corridorCells) {
        val row = cachedByKey[cell.key]
        if (row == null || forceRefresh) {
          missingCells += cell
        } else if ((now - row.fetchedAtMs) >= AppConfig.CACHE_TTL_MS) {
          staleCached += row
        } else {
          freshCached += row
        }
      }

      val usableCached = freshCached + staleCached
      val cachedGraphs =
        usableCached.map { parseOverpassJson(it.rawOverpassJson, it.fetchedAtMs, fromCache = true) }

      if (usableCached.isNotEmpty()) {
        val immediateMerged = mergeGraphs(cachedGraphs, fromCache = true)
        val partialOrStale = missingCells.isNotEmpty() || staleCached.isNotEmpty()
        if (!partialOrStale) {
          appendLog(
            OverpassRequestLog(
              endpoint = "room-cache",
              durationMs = (clockMs() - now).coerceAtLeast(1L),
              cellsFetched = 0,
              cellsCached = usableCached.size,
              cellsPrefetched = 0,
              succeeded = true,
              timestampMs = now,
              source = "overpass",
              method = "CACHE",
              protocol = "local",
              status = "CACHE",
              cooldownSummary = formatCooldownSummary(),
            )
          )
        }
        emit(
          Result.success(
            CorridorGraphUpdate(
              graph = immediateMerged,
              isPartialOrStale = partialOrStale,
              isFromNetwork = false,
              requestLog = null,
            )
          )
        )
      }

      val cellsToFetch =
        when {
          missingCells.isNotEmpty() -> missingCells
          staleCached.isNotEmpty() -> staleCached.mapNotNull { keyToCell(it.cellKey, mode) }
          else -> emptyList()
        }

      if (cellsToFetch.isNotEmpty()) {
        val cycleFailures = mutableListOf<String>()
        var anySucceeded = false
        var lastError: Throwable? = null
        var lastWinLog: OverpassRequestLog? = null
        var succeededCellsCount = 0

        // Pass 1: Strictly ONE cell per HTTP request, priority: user cell -> ahead cells -> others
        for ((idx, cell) in cellsToFetch.withIndex()) {
          val outcome =
            fetchOrJoinSingleCell(
              cell = cell,
              anchor = GeoPoint(cell.centerLat, cell.centerLon),
              mode = mode,
              pass = 1,
              cellsCached = usableCached.size,
              priorFailures = cycleFailures.toList(),
            )
          for (f in outcome.failures) {
            if (f !in cycleFailures) cycleFailures += f
          }
          if (outcome.succeeded) {
            anySucceeded = true
            succeededCellsCount++
            if (outcome.log != null) {
              lastWinLog =
                outcome.log.copy(
                  cellsFetched = succeededCellsCount,
                  failures = cycleFailures.distinct(),
                )
            }

            val allCellsNow = cacheDao.getCells(corridorKeys)
            val allGraphs =
              allCellsNow.map { parseOverpassJson(it.rawOverpassJson, it.fetchedAtMs, fromCache = false) }
            val mergedNow = mergeGraphs(allGraphs, fromCache = false)
            val isLast = idx == cellsToFetch.lastIndex
            if (lastWinLog != null) {
              replaceLatestSuccessfulLogIfSameEndpoint(lastWinLog)
            }
            emit(
              Result.success(
                CorridorGraphUpdate(
                  graph = mergedNow,
                  isPartialOrStale = !isLast,
                  isFromNetwork = true,
                  requestLog = lastWinLog,
                )
              )
            )
          } else {
            lastError = outcome.error ?: lastError
          }
        }

        // Driving mode Pass 2: fetch service roads as a second pass for each fetched cell
        if (anySucceeded && mode == TravelMode.DRIVING) {
          var serviceAddedAny = false
          for (cell in cellsToFetch) {
            if (cell.key !in activeCorridorKeySet) break
            val added = fetchAndMergeDrivingServicePass2(cell)
            if (added) serviceAddedAny = true
          }
          if (serviceAddedAny) {
            val refreshedCells = cacheDao.getCells(corridorKeys)
            val refreshedGraphs =
              refreshedCells.map { parseOverpassJson(it.rawOverpassJson, it.fetchedAtMs, fromCache = false) }
            val mergedWithService = mergeGraphs(refreshedGraphs, fromCache = false)
            emit(
              Result.success(
                CorridorGraphUpdate(
                  graph = mergedWithService,
                  isPartialOrStale = false,
                  isFromNetwork = true,
                  requestLog = lastWinLog,
                )
              )
            )
          }
        }

        if (anySucceeded) {
          val prefetchedCount = prefetchAheadCellsIfNeeded(center, headingDeg, mode, corridorKeys)
          if (prefetchedCount > 0 && lastWinLog != null) {
            replaceLatestSuccessfulLogIfSameEndpoint(lastWinLog.copy(cellsPrefetched = prefetchedCount))
          }
        } else if (usableCached.isEmpty()) {
          val nearest = cacheDao.getNearestCell(mode.name, center.lat, center.lon)
          val nearDist =
            nearest?.let { GeoMath.haversineMeters(center, GeoPoint(it.centerLat, it.centerLon)) }
              ?: Double.MAX_VALUE
          if (nearest != null && nearDist < 4_000.0) {
            emit(
              Result.success(
                CorridorGraphUpdate(
                  graph = parseOverpassJson(nearest.rawOverpassJson, nearest.fetchedAtMs, fromCache = true),
                  isPartialOrStale = true,
                  isFromNetwork = false,
                  requestLog = lastWinLog,
                )
              )
            )
          } else {
            emit(Result.failure(lastError ?: IOException("Overpass query failed")))
          }
        }
      } else if (usableCached.isNotEmpty()) {
        val prefetchedCount = prefetchAheadCellsIfNeeded(center, headingDeg, mode, corridorKeys)
        if (prefetchedCount > 0) {
          appendLog(
            OverpassRequestLog(
              endpoint = rankEndpoints().firstOrNull() ?: AppConfig.OVERPASS_PRIMARY_URL,
              durationMs = 0L,
              cellsFetched = 0,
              cellsCached = usableCached.size,
              cellsPrefetched = prefetchedCount,
              succeeded = true,
              timestampMs = clockMs(),
              source = "overpass",
              method = "POST",
              protocol = "http/1.1",
              status = "200",
              cooldownSummary = formatCooldownSummary(),
            )
          )
        }
      }
    }.flowOn(Dispatchers.IO)

  private fun replaceLatestSuccessfulLogIfSameEndpoint(updated: OverpassRequestLog) {
    val cur = _requestLogs.value
    if (cur.isNotEmpty() && cur.first().succeeded && cur.first().source == "overpass") {
      _requestLogs.value = listOf(updated) + cur.drop(1)
    }
  }

  private suspend fun fetchOrJoinSingleCell(
    cell: GridCellId,
    anchor: GeoPoint,
    mode: TravelMode,
    pass: Int,
    cellsCached: Int,
    priorFailures: List<String>,
  ): CellFetchOutcome {
    val taskKey = if (pass == 1) cell.key else "P2_${cell.key}"
    val existing = activeCellTasks[taskKey]
    if (existing != null && existing.isActive) {
      return existing.await()
    }
    val deferred =
      cellFetchScope.async {
        started60sTimestamps.addLast(clockMs())
        try {
          val netOutcome =
            executeOverpassHedged(
              initialBbox = cell.toBbox(),
              center = anchor,
              mode = mode,
              cellsCached = cellsCached,
              cellsRequested = 1,
              servicePassOnly = (pass == 2),
              priorFailures = priorFailures,
            )
          if (netOutcome.rawJson != null) {
            val fetchedAt = clockMs()
            val entities = partitionJsonIntoCells(netOutcome.rawJson, listOf(cell), fetchedAt)
            if (pass == 1 && entities.isNotEmpty()) {
              cacheDao.upsertCells(entities)
            }
            val parsedGraph = parseOverpassJson(netOutcome.rawJson, fetchedAt, fromCache = false)
            val edgeCount = parsedGraph.ways.size
            val host = shortHost(netOutcome.endpoint)
            endpointStatsMap[host] =
              OverpassEndpointStats(
                host = host,
                lastStatus = netOutcome.winningLog?.status ?: "200",
                durationMs = netOutcome.durationMs,
                cellMeters = netOutcome.cellMeters,
                edgesAdded = edgeCount,
              )
            completed60sTimestamps.addLast(clockMs())
            CellFetchOutcome(
              cellKey = cell.key,
              succeeded = true,
              cachedEntity = entities.firstOrNull(),
              log = netOutcome.winningLog,
              failures = netOutcome.failures,
              error = null,
              cellMeters = netOutcome.cellMeters,
            )
          } else {
            CellFetchOutcome(
              cellKey = cell.key,
              succeeded = false,
              cachedEntity = null,
              log = netOutcome.winningLog,
              failures = netOutcome.failures,
              error = netOutcome.error,
              cellMeters = netOutcome.cellMeters,
            )
          }
        } finally {
          activeCellTasks.remove(taskKey)
        }
      }
    activeCellTasks[taskKey] = deferred
    return deferred.await()
  }

  private suspend fun fetchAndMergeDrivingServicePass2(cell: GridCellId): Boolean {
    return runCatching {
      val existingRow = cacheDao.getCell(cell.key) ?: return@runCatching false
      val res =
        executeOverpassHedged(
          initialBbox = cell.toBbox(),
          center = GeoPoint(cell.centerLat, cell.centerLon),
          mode = TravelMode.DRIVING,
          cellsCached = 1,
          cellsRequested = 1,
          servicePassOnly = true,
          logOnResult = false,
        )
      val pass2Json = res.rawJson ?: return@runCatching false
      val pass2Obj = JSONObject(pass2Json)
      val newElements = pass2Obj.optJSONArray("elements") ?: return@runCatching false
      var serviceCount = 0
      val seen = HashSet<String>()
      val baseObj = JSONObject(existingRow.rawOverpassJson)
      val mergedArr = baseObj.optJSONArray("elements") ?: JSONArray()
      for (i in 0 until mergedArr.length()) {
        val el = mergedArr.optJSONObject(i) ?: continue
        seen += "${el.optString("type")}_${el.optLong("id", -1L)}"
      }
      for (i in 0 until newElements.length()) {
        val el = newElements.optJSONObject(i) ?: continue
        val tags = el.optJSONObject("tags") ?: continue
        if (tags.optString("highway") != "service") continue
        val sig = "${el.optString("type")}_${el.optLong("id", -1L)}"
        if (seen.add(sig)) {
          mergedArr.put(el)
          serviceCount++
        }
      }
      if (serviceCount == 0) return@runCatching false
      val mergedJson = JSONObject().put("elements", mergedArr).toString()
      cacheDao.upsertCell(existingRow.copy(rawOverpassJson = mergedJson))
      true
    }.getOrDefault(false)
  }

  override suspend fun fetchCorridorGraph(
    center: GeoPoint,
    headingDeg: Double,
    mode: TravelMode,
    radiusMeters: Double,
    forceRefresh: Boolean,
  ): Result<RoadGraph> =
    withContext(Dispatchers.IO) {
      var latest: Result<RoadGraph>? = null
      fetchCorridorStream(center, headingDeg, mode, forceRefresh).collect { updateRes ->
        latest = updateRes.map { it.graph }
      }
      latest ?: Result.failure(IOException("No road graph produced"))
    }

  override suspend fun getCachedCellCount(): Int =
    withContext(Dispatchers.IO) { cacheDao.countCells() }

  suspend fun getOldestFetchedAtMs(): Long? =
    withContext(Dispatchers.IO) { cacheDao.getOldestFetchedAtMs() }

  override suspend fun clearCache() {
    withContext(Dispatchers.IO) {
      cancelAllActiveRequests()
      cacheDao.clearAll()
    }
  }

  /**
   * Sends both a minimal test query around [center] and a real 300 m road query to every configured endpoint
   * to diagnose connectivity, HTTP status codes, and latency per mirror.
   */
  suspend fun testAllEndpoints(center: GeoPoint, mode: TravelMode): List<OverpassEndpointTestResult> =
    withContext(Dispatchers.IO) {
      val tinyBbox = bboxAroundPoint(center, 50.0)
      val tinyStr = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", tinyBbox.south, tinyBbox.west, tinyBbox.north, tinyBbox.east)
      val tinyQuery = "[out:json][timeout:${AppConfig.OVERPASS_TEST_QUERY_TIMEOUT_SEC}];node($tinyStr);out 1;"
      val testBbox = bboxAroundPoint(center, 200.0)
      val roadQuery = buildBboxQuery(testBbox, mode, AppConfig.OVERPASS_QUERY_TIMEOUT_SEC)

      endpoints.map { ep ->
        val tinyRes =
          try {
            executeSingleHttpAttempt(
              url = ep,
              query = tinyQuery,
              hedged = false,
              cellsCached = 0,
              cellsFetchedOnSuccess = 0,
              respectCooldownUpdate = false,
              logOnResult = true,
            )
          } catch (ce: CancellationException) {
            throw ce
          } catch (t: Throwable) {
            AttemptOutcome(ep, null, 1L, t.message ?: "ERR", false, false, null, t, null)
          }
        val roadRes =
          try {
            executeSingleHttpAttempt(
              url = ep,
              query = roadQuery,
              hedged = false,
              cellsCached = 0,
              cellsFetchedOnSuccess = 0,
              respectCooldownUpdate = false,
              logOnResult = true,
            )
          } catch (ce: CancellationException) {
            throw ce
          } catch (t: Throwable) {
            AttemptOutcome(ep, null, 1L, t.message ?: "ERR", false, false, null, t, null)
          }

        OverpassEndpointTestResult(
          endpoint = ep,
          tinyStatus = tinyRes.status,
          tinyDurationMs = tinyRes.durationMs,
          roadStatus = roadRes.status,
          roadDurationMs = roadRes.durationMs,
        )
      }
    }

  /**
   * Lightweight mirror ping using minimal 50m node query only (no road query) for fast latency & TLS timing check.
   */
  suspend fun pingAllEndpoints(center: GeoPoint): List<OverpassEndpointTestResult> =
    withContext(Dispatchers.IO) {
      val tinyBbox = bboxAroundPoint(center, 50.0)
      val tinyStr = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", tinyBbox.south, tinyBbox.west, tinyBbox.north, tinyBbox.east)
      val tinyQuery = "[out:json][timeout:${AppConfig.OVERPASS_TEST_QUERY_TIMEOUT_SEC}];node($tinyStr);out 1;"
      endpoints.map { ep ->
        val tinyRes =
          try {
            executeSingleHttpAttempt(
              url = ep,
              query = tinyQuery,
              hedged = false,
              cellsCached = 0,
              cellsFetchedOnSuccess = 0,
              respectCooldownUpdate = false,
              logOnResult = true,
            )
          } catch (ce: CancellationException) {
            throw ce
          } catch (t: Throwable) {
            AttemptOutcome(ep, null, 1L, t.message ?: "ERR", false, false, null, t, null)
          }
        OverpassEndpointTestResult(
          endpoint = ep,
          tinyStatus = tinyRes.status,
          tinyDurationMs = tinyRes.durationMs,
          roadStatus = "-",
          roadDurationMs = 0L,
        )
      }
    }

  private suspend fun prefetchAheadCellsIfNeeded(
    center: GeoPoint,
    headingDeg: Double,
    mode: TravelMode,
    alreadyCoveredKeys: List<String>,
  ): Int {
    val aheadStartM =
      if (mode == TravelMode.DRIVING) AppConfig.OVERPASS_DRIVING_AHEAD_M else AppConfig.OVERPASS_WALKING_AHEAD_M
    val aheadEndM = if (mode == TravelMode.DRIVING) aheadStartM + 500.0 else aheadStartM + 260.0
    val pStart = GeoMath.offsetPoint(center, headingDeg, aheadStartM)
    val pEnd = GeoMath.offsetPoint(center, headingDeg, aheadEndM)
    val sideM = if (mode == TravelMode.DRIVING) 240.0 else 160.0
    val pts =
      listOf(
        GeoMath.offsetPoint(pStart, (headingDeg + 90.0) % 360.0, sideM),
        GeoMath.offsetPoint(pStart, (headingDeg + 270.0) % 360.0, sideM),
        GeoMath.offsetPoint(pEnd, (headingDeg + 90.0) % 360.0, sideM),
        GeoMath.offsetPoint(pEnd, (headingDeg + 270.0) % 360.0, sideM),
      )
    val aheadBbox = bboxFromPoints(pts)
    val coveredSet = alreadyCoveredKeys.toHashSet()
    val candidateCells =
      orderCellsByPriority(cellsIntersectingBbox(aheadBbox, mode).filter { it.key !in coveredSet }, pStart, headingDeg)
    if (candidateCells.isEmpty()) return 0

    val existing = cacheDao.getCells(candidateCells.map { it.key }).associateBy { it.cellKey }
    val now = clockMs()
    val missing =
      candidateCells.filter { cell ->
        val row = existing[cell.key]
        row == null || (now - row.fetchedAtMs) >= AppConfig.CACHE_TTL_MS
      }
    if (missing.isEmpty()) return 0

    // One cell per request for ahead prefetch too
    var prefetched = 0
    for (cell in missing.take(2)) {
      val outcome =
        fetchOrJoinSingleCell(
          cell = cell,
          anchor = GeoPoint(cell.centerLat, cell.centerLon),
          mode = mode,
          pass = 1,
          cellsCached = 0,
          priorFailures = emptyList(),
        )
      if (outcome.succeeded) prefetched++
    }
    return prefetched
  }

  private data class AttemptOutcome(
    val endpoint: String,
    val rawJson: String?,
    val durationMs: Long,
    val status: String,
    val shouldHalveBbox: Boolean,
    val rateLimited429: Boolean,
    val retryAfterMs: Long?,
    val error: Throwable?,
    val log: OverpassRequestLog?,
  )

  private data class NetworkOutcome(
    val endpoint: String,
    val rawJson: String?,
    val durationMs: Long,
    val failures: List<String>,
    val error: Throwable?,
    val winningLog: OverpassRequestLog?,
    val cellMeters: Int,
  )

  private suspend fun executeOverpassHedged(
    initialBbox: GeoBbox,
    center: GeoPoint,
    mode: TravelMode,
    cellsCached: Int,
    cellsRequested: Int,
    servicePassOnly: Boolean = false,
    priorFailures: List<String> = emptyList(),
    logOnResult: Boolean = true,
  ): NetworkOutcome = coroutineScope {
    val startMs = clockMs()
    val ordered = rankEndpoints()
    val failures = priorFailures.toMutableList()
    var activeBbox = initialBbox
    var halvedOnce = false
    var cellMeters = 400
    lastCellSizeMeters.set(400)
    var lastError: Throwable? = null
    var lastEndpoint = ordered.firstOrNull() ?: AppConfig.OVERPASS_PRIMARY_URL
    var lastFailLog: OverpassRequestLog? = null

    if (ordered.isEmpty()) {
      return@coroutineScope NetworkOutcome(
        endpoint = lastEndpoint,
        rawJson = null,
        durationMs = 1L,
        failures = listOf("No endpoints configured"),
        error = IOException("No Overpass endpoints configured"),
        winningLog = null,
        cellMeters = cellMeters,
      )
    }

    val epA = ordered[0]
    val epB = ordered.getOrNull(1)
    lastEndpoint = epA

    val queryA = buildBboxQuery(activeBbox, mode, AppConfig.OVERPASS_QUERY_TIMEOUT_SEC, servicePassOnly)
    val jobA =
      async {
        executeSingleHttpAttempt(
          url = epA,
          query = queryA,
          hedged = false,
          cellsCached = cellsCached,
          cellsFetchedOnSuccess = cellsRequested,
          logOnResult = logOnResult,
          cellMeters = cellMeters,
        )
      }

    val earlyA =
      if (epB != null && epB != epA) {
        withTimeoutOrNull(hedgeDelayMs) { jobA.await() }
      } else {
        jobA.await()
      }

    if (earlyA != null) {
      if (earlyA.rawJson != null) {
        return@coroutineScope NetworkOutcome(
          endpoint = earlyA.endpoint,
          rawJson = earlyA.rawJson,
          durationMs = (clockMs() - startMs).coerceAtLeast(1L),
          failures = failures,
          error = null,
          winningLog = attachPriorFailuresToLatestLog(earlyA.log, failures),
          cellMeters = cellMeters,
        )
      }
      val fEntry = "${shortHost(earlyA.endpoint)}: ${earlyA.status}"
      if (fEntry !in failures) failures += fEntry
      lastError = earlyA.error
      lastFailLog = earlyA.log
      if (earlyA.shouldHalveBbox && !halvedOnce) {
        activeBbox = halveBboxMin200m(activeBbox, center)
        halvedOnce = true
        cellMeters = 200
        lastCellSizeMeters.set(200)
      }
    } else if (epB != null) {
      // Hedge after hedgeDelayMs (default 2s) on epB; max 2 in flight via semaphore
      val queryB = buildBboxQuery(activeBbox, mode, AppConfig.OVERPASS_QUERY_TIMEOUT_SEC, servicePassOnly)
      val jobB =
        async {
          executeSingleHttpAttempt(
            url = epB,
            query = queryB,
            hedged = true,
            cellsCached = cellsCached,
            cellsFetchedOnSuccess = cellsRequested,
            logOnResult = logOnResult,
            cellMeters = cellMeters,
          )
        }

      val (firstOutcome, secondJob) =
        select {
          jobA.onAwait { res -> res to jobB }
          jobB.onAwait { res -> res to jobA }
        }

      if (firstOutcome.rawJson != null) {
        secondJob.cancel(CancellationException("Hedge lost race"))
        return@coroutineScope NetworkOutcome(
          endpoint = firstOutcome.endpoint,
          rawJson = firstOutcome.rawJson,
          durationMs = (clockMs() - startMs).coerceAtLeast(1L),
          failures = failures,
          error = null,
          winningLog = attachPriorFailuresToLatestLog(firstOutcome.log, failures),
          cellMeters = cellMeters,
        )
      }

      val f1 = "${shortHost(firstOutcome.endpoint)}: ${firstOutcome.status}"
      if (f1 !in failures) failures += f1
      lastError = firstOutcome.error
      lastFailLog = firstOutcome.log

      val secondOutcome = secondJob.await()
      if (secondOutcome.rawJson != null) {
        return@coroutineScope NetworkOutcome(
          endpoint = secondOutcome.endpoint,
          rawJson = secondOutcome.rawJson,
          durationMs = (clockMs() - startMs).coerceAtLeast(1L),
          failures = failures,
          error = null,
          winningLog = attachPriorFailuresToLatestLog(secondOutcome.log, failures),
          cellMeters = cellMeters,
        )
      }

      val f2 = "${shortHost(secondOutcome.endpoint)}: ${secondOutcome.status}"
      if (f2 !in failures) failures += f2
      lastError = secondOutcome.error
      lastFailLog = secondOutcome.log
      if ((firstOutcome.shouldHalveBbox || secondOutcome.shouldHalveBbox) && !halvedOnce) {
        activeBbox = halveBboxMin200m(activeBbox, center)
        halvedOnce = true
        cellMeters = 200
        lastCellSizeMeters.set(200)
      }
    }

    // Try remaining endpoints sequentially if first/hedged endpoints failed
    val startRemainingIdx = if (earlyA == null && epB != null) 2 else 1
    for (idx in startRemainingIdx until ordered.size) {
      val url = ordered[idx]
      lastEndpoint = url
      val q = buildBboxQuery(activeBbox, mode, AppConfig.OVERPASS_QUERY_TIMEOUT_SEC, servicePassOnly)
      val attempt =
        executeSingleHttpAttempt(
          url = url,
          query = q,
          hedged = false,
          cellsCached = cellsCached,
          cellsFetchedOnSuccess = cellsRequested,
          logOnResult = logOnResult,
          cellMeters = cellMeters,
        )
      if (attempt.rawJson != null) {
        return@coroutineScope NetworkOutcome(
          endpoint = attempt.endpoint,
          rawJson = attempt.rawJson,
          durationMs = (clockMs() - startMs).coerceAtLeast(1L),
          failures = failures,
          error = null,
          winningLog = attachPriorFailuresToLatestLog(attempt.log, failures),
          cellMeters = cellMeters,
        )
      }
      val fEntry = "${shortHost(attempt.endpoint)}: ${attempt.status}"
      if (fEntry !in failures) failures += fEntry
      lastError = attempt.error
      lastFailLog = attempt.log
      if (attempt.shouldHalveBbox && !halvedOnce) {
        activeBbox = halveBboxMin200m(activeBbox, center)
        halvedOnce = true
        cellMeters = 200
        lastCellSizeMeters.set(200)
      }
    }

    NetworkOutcome(
      endpoint = lastEndpoint,
      rawJson = null,
      durationMs = (clockMs() - startMs).coerceAtLeast(1L),
      failures = failures,
      error = lastError ?: IOException("Overpass mirrors exhausted"),
      winningLog = lastFailLog,
      cellMeters = cellMeters,
    )
  }

  private fun attachPriorFailuresToLatestLog(
    winLog: OverpassRequestLog?,
    failures: List<String>,
  ): OverpassRequestLog? {
    if (winLog == null || failures.isEmpty()) return winLog
    val updated = winLog.copy(failures = failures.distinct())
    val cur = _requestLogs.value
    if (cur.isNotEmpty() && cur.first() == winLog) {
      _requestLogs.value = listOf(updated) + cur.drop(1)
    }
    return updated
  }

  private suspend fun executeSingleHttpAttempt(
    url: String,
    query: String,
    hedged: Boolean,
    cellsCached: Int,
    cellsFetchedOnSuccess: Int,
    respectCooldownUpdate: Boolean = true,
    logOnResult: Boolean = true,
    cellMeters: Int = 400,
  ): AttemptOutcome {
    inFlightSemaphore.acquire()
    val startMs = clockMs()
    currentAttemptStartMs.set(startMs)
    val concurrent = activeHttpCalls.incrementAndGet()
    maxObservedConcurrentHttpCalls.updateAndGet { prev -> maxOf(prev, concurrent) }

    val encodedData = URLEncoder.encode(query, "UTF-8")
    val payload = "data=$encodedData"
    val requestHeaders =
      linkedMapOf(
        "User-Agent" to AppConfig.USER_AGENT,
        "Accept" to "*/*",
        "Content-Type" to "application/x-www-form-urlencoded",
      )

    val timing = TimingCapture()
    val request =
      Request.Builder()
        .url(url)
        .header("User-Agent", AppConfig.USER_AGENT)
        .header("Accept", "*/*")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .tag(TimingCapture::class.java, timing)
        .post(payload.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        .build()

    val host = shortHost(url)
    try {
      client.newCall(request).awaitExecute().use { response ->
        val dur = (clockMs() - startMs).coerceAtLeast(1L)
        val proto = response.protocol.toString()
        val bodyStr = response.body?.string()
        val sizeKb = (bodyStr?.toByteArray(Charsets.UTF_8)?.size ?: 0) / 1024.0
        val respHeadersSummary =
          listOfNotNull(
            response.header("Server")?.takeIf { it.isNotBlank() }?.let { "srv=${it.take(24)}" },
            response.header("Content-Type")?.takeIf { it.isNotBlank() }?.let { "ct=${it.substringBefore(';').take(24)}" },
            response.header("Retry-After")?.takeIf { it.isNotBlank() }?.let { "ra=$it" },
          ).joinToString(" ").takeIf { it.isNotBlank() }

        if (response.isSuccessful && !bodyStr.isNullOrBlank()) {
          if (respectCooldownUpdate) {
            recordEndpointSuccess(url)
          }
          val log =
            OverpassRequestLog(
              endpoint = url,
              durationMs = dur,
              cellsFetched = cellsFetchedOnSuccess,
              cellsCached = cellsCached,
              succeeded = true,
              timestampMs = clockMs(),
              source = "overpass",
              method = "POST",
              protocol = proto,
              requestHeaders = requestHeaders,
              queryText = query,
              encodedLength = payload.length,
              status = response.code.toString(),
              responseSizeKb = sizeKb,
              hedged = hedged,
              cancelled = false,
              cooldownSummary = formatCooldownSummary(),
              dnsMs = timing.dnsMs,
              connectMs = timing.connectMs,
              tlsMs = timing.tlsMs,
              firstByteMs = timing.firstByteMs,
              responseHeadersSummary = respHeadersSummary,
            )
          if (logOnResult) appendLog(log)
          return AttemptOutcome(
            endpoint = url,
            rawJson = bodyStr,
            durationMs = dur,
            status = response.code.toString(),
            shouldHalveBbox = false,
            rateLimited429 = false,
            retryAfterMs = null,
            error = null,
            log = log,
          )
        }

        val code = response.code
        val statusLabel = if (response.isSuccessful) "EMPTY" else "HTTP $code"
        val snippet = bodyStr?.take(300)
        var retryAfterMs: Long? = null
        var shouldHalve = false

        endpointStatsMap[host] =
          OverpassEndpointStats(
            host = host,
            lastStatus = statusLabel,
            durationMs = dur,
            cellMeters = cellMeters,
            edgesAdded = 0,
          )

        if (respectCooldownUpdate) {
          when {
            code == 406 -> {
              val backoff = maxOf(AppConfig.OVERPASS_REJECT_406_COOLDOWN_MS, recordEndpointPenalty(url))
              endpointCooldownUntilMs[url] = clockMs() + backoff
              Log.w(
                TAG,
                "Overpass 406 from $host; headers=$requestHeaders body=${snippet.orEmpty()}",
              )
            }
            code == 429 -> {
              val raHeader = response.header("Retry-After")?.trim()?.toLongOrNull()
              retryAfterMs = (raHeader?.times(1000L) ?: AppConfig.OVERPASS_DEFAULT_429_WAIT_MS).coerceAtLeast(500L)
              val backoff = recordEndpointPenalty(url)
              val cooldown = maxOf(retryAfterMs, backoff)
              endpointCooldownUntilMs[url] = clockMs() + cooldown
            }
            code == 504 -> {
              endpointCooldownUntilMs[url] = clockMs() + recordEndpointPenalty(url)
              shouldHalve = true
            }
            code in 500..599 -> {
              endpointCooldownUntilMs[url] = clockMs() + recordEndpointPenalty(url)
              shouldHalve = true
            }
          }
        }

        val log =
          OverpassRequestLog(
            endpoint = url,
            durationMs = dur,
            cellsFetched = 0,
            cellsCached = cellsCached,
            failures = listOf("$host: $statusLabel"),
            succeeded = false,
            timestampMs = clockMs(),
            source = "overpass",
            method = "POST",
            protocol = proto,
            requestHeaders = requestHeaders,
            queryText = query,
            encodedLength = payload.length,
            status = code.toString(),
            responseSizeKb = sizeKb,
            responseSnippet = if (code == 406 || !snippet.isNullOrBlank()) snippet else null,
            hedged = hedged,
            cancelled = false,
            cooldownSummary = formatCooldownSummary(),
            dnsMs = timing.dnsMs,
            connectMs = timing.connectMs,
            tlsMs = timing.tlsMs,
            firstByteMs = timing.firstByteMs,
            responseHeadersSummary = respHeadersSummary,
          )
        if (logOnResult) appendLog(log)
        return AttemptOutcome(
          endpoint = url,
          rawJson = null,
          durationMs = dur,
          status = statusLabel,
          shouldHalveBbox = shouldHalve,
          rateLimited429 = code == 429,
          retryAfterMs = retryAfterMs,
          error = IOException("Overpass $statusLabel"),
          log = log,
        )
      }
    } catch (ce: CancellationException) {
      val dur = (clockMs() - startMs).coerceAtLeast(1L)
      if (logOnResult) {
        appendLog(
          OverpassRequestLog(
            endpoint = url,
            durationMs = dur,
            cellsFetched = 0,
            cellsCached = cellsCached,
            failures = listOf("$host: Cancelled"),
            succeeded = false,
            timestampMs = clockMs(),
            source = "overpass",
            method = "POST",
            protocol = "http/1.1",
            requestHeaders = requestHeaders,
            queryText = query,
            encodedLength = payload.length,
            status = "CANCELLED",
            hedged = hedged,
            cancelled = true,
            cooldownSummary = formatCooldownSummary(),
            dnsMs = timing.dnsMs,
            connectMs = timing.connectMs,
            tlsMs = timing.tlsMs,
            firstByteMs = timing.firstByteMs,
          )
        )
      }
      throw ce
    } catch (t: Throwable) {
      val dur = (clockMs() - startMs).coerceAtLeast(1L)
      val isTimeout = t is SocketTimeoutException || (t.message?.contains("timeout", ignoreCase = true) == true)
      if (respectCooldownUpdate) {
        endpointCooldownUntilMs[url] = clockMs() + recordEndpointPenalty(url)
      }
      val statusStr = if (isTimeout) "TIMEOUT" else (t::class.java.simpleName)
      endpointStatsMap[host] =
        OverpassEndpointStats(
          host = host,
          lastStatus = statusStr,
          durationMs = dur,
          cellMeters = cellMeters,
          edgesAdded = 0,
        )
      val log =
        OverpassRequestLog(
          endpoint = url,
          durationMs = dur,
          cellsFetched = 0,
          cellsCached = cellsCached,
          failures = listOf("$host: ${t.message ?: statusStr}"),
          succeeded = false,
          timestampMs = clockMs(),
          source = "overpass",
          method = "POST",
          protocol = "http/1.1",
          requestHeaders = requestHeaders,
          queryText = query,
          encodedLength = payload.length,
          status = statusStr,
          responseSnippet = t.message?.take(300),
          hedged = hedged,
          cancelled = false,
          cooldownSummary = formatCooldownSummary(),
          dnsMs = timing.dnsMs,
          connectMs = timing.connectMs,
          tlsMs = timing.tlsMs,
          firstByteMs = timing.firstByteMs,
        )
      if (logOnResult) appendLog(log)
      Log.w(TAG, "Overpass attempt failed on $host: ${t.message}")
      return AttemptOutcome(
        endpoint = url,
        rawJson = null,
        durationMs = dur,
        status = statusStr,
        shouldHalveBbox = isTimeout,
        rateLimited429 = false,
        retryAfterMs = null,
        error = t,
        log = log,
      )
    } finally {
      if (activeHttpCalls.decrementAndGet() <= 0) {
        currentAttemptStartMs.compareAndSet(startMs, 0L)
      }
      inFlightSemaphore.release()
    }
  }

  private suspend fun Call.awaitExecute(): Response =
    suspendCancellableCoroutine { cont ->
      cont.invokeOnCancellation { cancel() }
      enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
          }

          override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) {
              cont.resume(response)
            } else {
              response.close()
            }
          }
        }
      )
    }

  private fun rankEndpoints(): List<String> {
    if (endpoints.isEmpty()) return emptyList()
    val now = clockMs()
    val (ready, cooled) = endpoints.partition { (endpointCooldownUntilMs[it] ?: 0L) <= now }
    val sortedReady =
      ready.sortedWith(
        compareByDescending<String> { endpointScore[it] ?: 0.0 }
          .thenBy { endpoints.indexOf(it) }
      )
    val sortedCooled =
      cooled.sortedBy { endpointCooldownUntilMs[it] ?: 0L }
    return sortedReady + sortedCooled
  }

  private fun recordEndpointSuccess(url: String) {
    endpointCooldownUntilMs.remove(url)
    endpointFailureStreak.remove(url)
    endpointScore[url] = ((endpointScore[url] ?: 0.0) + 1.0).coerceAtMost(10.0)
  }

  private fun recordEndpointPenalty(url: String): Long {
    val streak = (endpointFailureStreak[url] ?: 0) + 1
    endpointFailureStreak[url] = streak
    endpointScore[url] = ((endpointScore[url] ?: 0.0) - 1.5).coerceAtLeast(-5.0)
    val shift = (streak - 1).coerceIn(0, 4)
    return minOf(AppConfig.OVERPASS_ENDPOINT_COOLDOWN_MS * (1L shl shift), AppConfig.OVERPASS_MAX_BACKOFF_MS)
  }

  private fun formatCooldownSummary(): String {
    val now = clockMs()
    val active =
      endpointCooldownUntilMs.entries
        .mapNotNull { (url, until) ->
          val remSec = (until - now) / 1000L
          if (remSec > 0L) "${shortHost(url)}=${remSec}s" else null
        }
    return if (active.isEmpty()) "" else active.joinToString(",")
  }

  private fun shortHost(url: String): String =
    runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

  private fun appendLog(log: OverpassRequestLog) {
    _requestLogs.value = (listOf(log) + _requestLogs.value).take(24)
  }

  private fun bboxAroundPoint(center: GeoPoint, halfMeters: Double): GeoBbox {
    val dLat = halfMeters / 111_320.0
    val cosLat = cos(Math.toRadians(center.lat)).coerceAtLeast(0.2)
    val dLon = halfMeters / (111_320.0 * cosLat)
    return GeoBbox(
      south = GeoMath.clampMercatorLat(center.lat - dLat),
      west = GeoMath.normalizeLon(center.lon - dLon),
      north = GeoMath.clampMercatorLat(center.lat + dLat),
      east = GeoMath.normalizeLon(center.lon + dLon),
    )
  }

  private fun computeCorridorBbox(origin: GeoPoint, headingDeg: Double, mode: TravelMode): GeoBbox {
    val radiusM =
      if (mode == TravelMode.DRIVING) AppConfig.OVERPASS_DRIVING_RADIUS_M
      else AppConfig.OVERPASS_WALKING_RADIUS_M
    val aheadM =
      if (mode == TravelMode.DRIVING) AppConfig.OVERPASS_DRIVING_AHEAD_M
      else AppConfig.OVERPASS_WALKING_AHEAD_M

    val backPoint = GeoMath.offsetPoint(origin, (headingDeg + 180.0) % 360.0, radiusM)
    val aheadPoint = GeoMath.offsetPoint(origin, headingDeg, aheadM)
    val aheadTip = GeoMath.offsetPoint(aheadPoint, headingDeg, radiusM * 0.5)

    val pts =
      listOf(
        origin,
        backPoint,
        aheadPoint,
        aheadTip,
        GeoMath.offsetPoint(origin, (headingDeg + 90.0) % 360.0, radiusM),
        GeoMath.offsetPoint(origin, (headingDeg + 270.0) % 360.0, radiusM),
        GeoMath.offsetPoint(aheadPoint, (headingDeg + 90.0) % 360.0, radiusM),
        GeoMath.offsetPoint(aheadPoint, (headingDeg + 270.0) % 360.0, radiusM),
      )

    return bboxFromPoints(pts)
  }

  private fun bboxFromPoints(pts: List<GeoPoint>): GeoBbox {
    val minLat = GeoMath.clampMercatorLat(pts.minOf { it.lat })
    val maxLat = GeoMath.clampMercatorLat(pts.maxOf { it.lat })
    val originLon = pts.first().lon
    val unwrappedOffsets = pts.map { GeoMath.unwrapLonDelta(it.lon - originLon) }
    val minLon = GeoMath.normalizeLon(originLon + unwrappedOffsets.min())
    val maxLon = GeoMath.normalizeLon(originLon + unwrappedOffsets.max())
    return GeoBbox(south = minLat, west = minLon, north = maxLat, east = maxLon)
  }

  private fun cellsIntersectingBbox(bbox: GeoBbox, mode: TravelMode): List<GridCellId> {
    val step = AppConfig.CACHE_CELL_SIZE_DEG
    val lonCellsCount = kotlin.math.round(360.0 / step).toInt()
    val halfCells = lonCellsCount / 2
    val subBoxes = GeoMath.splitBboxAtAntimeridian(bbox.south, bbox.west, bbox.north, bbox.east)
    val seen = LinkedHashSet<GridCellId>()
    for (sub in subBoxes) {
      val minLatIdx = floor(sub.south / step).toInt()
      val maxLatIdx = floor(sub.north / step).toInt()
      val minLonIdx = floor(sub.west / step).toInt()
      val maxLonIdx = floor(sub.east / step).toInt()
      for (latIdx in minLatIdx..maxLatIdx) {
        for (rawLonIdx in minLonIdx..maxLonIdx) {
          var wrappedIdx = ((rawLonIdx + halfCells) % lonCellsCount + lonCellsCount) % lonCellsCount - halfCells
          if (wrappedIdx == halfCells) wrappedIdx = -halfCells
          seen += GridCellId(mode, latIdx, wrappedIdx)
        }
      }
    }
    return seen.toList()
  }

  private fun orderCellsByPriority(
    cells: List<GridCellId>,
    userCenter: GeoPoint,
    headingDeg: Double,
  ): List<GridCellId> {
    if (cells.size <= 1) return cells
    val userCellKey = buildCellKey(userCenter, cells.first().mode)
    val aheadCenter = GeoMath.offsetPoint(userCenter, headingDeg, 350.0)
    val aheadCellKey = buildCellKey(aheadCenter, cells.first().mode)
    return cells.sortedWith(
      compareBy<GridCellId> {
        when (it.key) {
          userCellKey -> 0
          aheadCellKey -> 1
          else -> 2
        }
      }.thenBy { cell ->
        GeoMath.haversineMeters(userCenter, GeoPoint(cell.centerLat, cell.centerLon))
      }
    )
  }

  private fun keyToCell(key: String, fallbackMode: TravelMode): GridCellId? {
    val parts = key.split("_")
    if (parts.size != 3) return null
    val mode = runCatching { TravelMode.valueOf(parts[0]) }.getOrDefault(fallbackMode)
    val latIdx = parts[1].toIntOrNull() ?: return null
    val lonIdx = parts[2].toIntOrNull() ?: return null
    return GridCellId(mode, latIdx, lonIdx)
  }

  private fun partitionJsonIntoCells(
    rawJson: String,
    cells: List<GridCellId>,
    fetchedAtMs: Long,
  ): List<CorridorCacheEntity> {
    val root = runCatching { JSONObject(rawJson) }.getOrNull()
    val elements = root?.optJSONArray("elements") ?: JSONArray()

    val nodeCoords = HashMap<Long, Pair<Double, Double>>()
    val nodeElementsById = HashMap<Long, JSONObject>()
    val taggedNodeElements = mutableListOf<Triple<Double, Double, JSONObject>>()
    val wayElements = mutableListOf<Triple<GeoBbox, JSONObject, Boolean>>()

    for (i in 0 until elements.length()) {
      val el = elements.optJSONObject(i) ?: continue
      when (el.optString("type")) {
        "node" -> {
          val id = el.optLong("id", -1L)
          val lat = el.optDouble("lat", Double.NaN)
          val rawLon = el.optDouble("lon", Double.NaN)
          if (!lat.isNaN() && !rawLon.isNaN()) {
            val lon = GeoMath.normalizeLon(rawLon)
            if (id >= 0) {
              nodeCoords[id] = lat to lon
              nodeElementsById[id] = el
            }
            if (el.optJSONObject("tags") != null) {
              taggedNodeElements += Triple(lat, lon, el)
            }
          }
        }
        "way" -> {
          val geom = el.optJSONArray("geometry")
          if (geom != null && geom.length() >= 2) {
            var s = 90.0
            var n = -90.0
            var w = 180.0
            var e = -180.0
            for (g in 0 until geom.length()) {
              val pt = geom.optJSONObject(g) ?: continue
              val lat = pt.optDouble("lat", Double.NaN)
              val rawLon = pt.optDouble("lon", Double.NaN)
              if (!lat.isNaN() && !rawLon.isNaN()) {
                val lon = GeoMath.normalizeLon(rawLon)
                if (lat < s) s = lat
                if (lat > n) n = lat
                if (lon < w) w = lon
                if (lon > e) e = lon
              }
            }
            if (s <= n && w <= e) {
              wayElements += Triple(GeoBbox(s, w, n, e), el, false)
            }
          } else {
            val nds = el.optJSONArray("nodes")
            if (nds != null && nds.length() >= 2) {
              var s = 90.0
              var n = -90.0
              var w = 180.0
              var e = -180.0
              var foundAny = false
              for (j in 0 until nds.length()) {
                val c = nodeCoords[nds.optLong(j)] ?: continue
                foundAny = true
                if (c.first < s) s = c.first
                if (c.first > n) n = c.first
                if (c.second < w) w = c.second
                if (c.second > e) e = c.second
              }
              if (foundAny) {
                wayElements += Triple(GeoBbox(s, w, n, e), el, true)
              }
            }
          }
        }
      }
    }

    val pad = 0.0003
    return cells.map { cell ->
      val cellElements = JSONArray()
      val cellBox = cell.toBbox()
      val cs = cellBox.south - pad
      val cn = cellBox.north + pad
      val cw = cellBox.west - pad
      val ce = cellBox.east + pad

      val addedNodeIds = HashSet<Long>()
      for ((lat, lon, nodeEl) in taggedNodeElements) {
        if (lat in cs..cn && lon in cw..ce) {
          val id = nodeEl.optLong("id", -1L)
          if (id >= 0) addedNodeIds += id
          cellElements.put(nodeEl)
        }
      }

      val neededNodeIds = HashSet<Long>()
      for ((wb, wayEl, needsNodes) in wayElements) {
        val intersects =
          wb.north >= cs && wb.south <= cn &&
            (wb.east >= cw && wb.west <= ce || cells.size == 1)
        if (intersects) {
          cellElements.put(wayEl)
          if (needsNodes) {
            val nds = wayEl.optJSONArray("nodes")
            if (nds != null) {
              for (j in 0 until nds.length()) {
                val nid = nds.optLong(j, -1L)
                if (nid >= 0 && nid !in addedNodeIds) neededNodeIds += nid
              }
            }
          }
        }
      }

      for (nid in neededNodeIds) {
        nodeElementsById[nid]?.let { cellElements.put(it) }
      }

      val cellObj = JSONObject().put("elements", cellElements)
      CorridorCacheEntity(
        cellKey = cell.key,
        mode = cell.mode.name,
        centerLat = cell.centerLat,
        centerLon = cell.centerLon,
        rawOverpassJson = cellObj.toString(),
        fetchedAtMs = fetchedAtMs,
      )
    }
  }

  private class TimingCapture {
    @Volatile var callStartMs: Long = 0L
    @Volatile var dnsStartMs: Long = 0L
    @Volatile var dnsMs: Long? = null
    @Volatile var connectStartMs: Long = 0L
    @Volatile var connectMs: Long? = null
    @Volatile var tlsStartMs: Long = 0L
    @Volatile var tlsMs: Long? = null
    @Volatile var firstByteMs: Long? = null
  }

  companion object {
    private const val TAG = "OverpassData"

    fun defaultOkHttpClient(): OkHttpClient =
      OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(AppConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(AppConfig.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .callTimeout(AppConfig.CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        .eventListenerFactory { call ->
          val capture = call.request().tag(TimingCapture::class.java)
          object : EventListener() {
            override fun callStart(call: Call) {
              capture?.callStartMs = System.nanoTime() / 1_000_000L
            }

            override fun dnsStart(call: Call, domainName: String) {
              capture?.dnsStartMs = System.nanoTime() / 1_000_000L
            }

            override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
              capture?.let { c ->
                if (c.dnsStartMs > 0L) c.dnsMs = ((System.nanoTime() / 1_000_000L) - c.dnsStartMs).coerceAtLeast(0L)
              }
            }

            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
              capture?.connectStartMs = System.nanoTime() / 1_000_000L
            }

            override fun secureConnectStart(call: Call) {
              capture?.tlsStartMs = System.nanoTime() / 1_000_000L
            }

            override fun secureConnectEnd(call: Call, handshake: Handshake?) {
              capture?.let { c ->
                if (c.tlsStartMs > 0L) c.tlsMs = ((System.nanoTime() / 1_000_000L) - c.tlsStartMs).coerceAtLeast(0L)
              }
            }

            override fun connectEnd(
              call: Call,
              inetSocketAddress: InetSocketAddress,
              proxy: Proxy,
              protocol: Protocol?,
            ) {
              capture?.let { c ->
                if (c.connectStartMs > 0L) {
                  c.connectMs = ((System.nanoTime() / 1_000_000L) - c.connectStartMs).coerceAtLeast(0L)
                }
              }
            }

            override fun responseHeadersStart(call: Call) {
              capture?.let { c ->
                if (c.callStartMs > 0L && c.firstByteMs == null) {
                  c.firstByteMs = ((System.nanoTime() / 1_000_000L) - c.callStartMs).coerceAtLeast(0L)
                }
              }
            }
          }
        }
        .addInterceptor(DebugNetworkSimulator.interceptor)
        .build()

    fun buildCellKey(center: GeoPoint, mode: TravelMode): String {
      val step = AppConfig.CACHE_CELL_SIZE_DEG
      val lonCellsCount = kotlin.math.round(360.0 / step).toInt()
      val halfCells = lonCellsCount / 2
      val latIdx = floor(GeoMath.clampMercatorLat(center.lat) / step).toInt()
      val rawLonIdx = floor(GeoMath.normalizeLon(center.lon) / step).toInt()
      var wrappedIdx = ((rawLonIdx + halfCells) % lonCellsCount + lonCellsCount) % lonCellsCount - halfCells
      if (wrappedIdx == halfCells) wrappedIdx = -halfCells
      return "${mode.name}_${latIdx}_${wrappedIdx}"
    }

    fun halveBboxMin200m(bbox: GeoBbox, anchor: GeoPoint): GeoBbox {
      val centerLat = (bbox.south + bbox.north) * 0.5
      val centerLon = GeoMath.normalizeLon(bbox.west + GeoMath.unwrapLonDelta(bbox.east - bbox.west) * 0.5)
      val curHalfLat = (bbox.north - bbox.south) * 0.5
      val curHalfLon = kotlin.math.abs(GeoMath.unwrapLonDelta(bbox.east - bbox.west)) * 0.5

      val minHalfLat = AppConfig.OVERPASS_MIN_BBOX_HALF_METERS / 111_320.0
      val cosLat = cos(Math.toRadians(anchor.lat)).coerceAtLeast(0.2)
      val minHalfLon = AppConfig.OVERPASS_MIN_BBOX_HALF_METERS / (111_320.0 * cosLat)

      val nextHalfLat = (curHalfLat * 0.5).coerceAtLeast(minHalfLat)
      val nextHalfLon = (curHalfLon * 0.5).coerceAtLeast(minHalfLon)
      return GeoBbox(
        south = GeoMath.clampMercatorLat(centerLat - nextHalfLat),
        west = GeoMath.normalizeLon(centerLon - nextHalfLon),
        north = GeoMath.clampMercatorLat(centerLat + nextHalfLat),
        east = GeoMath.normalizeLon(centerLon + nextHalfLon),
      )
    }

    fun buildBboxQuery(
      bbox: GeoBbox,
      mode: TravelMode,
      timeoutSec: Int = AppConfig.OVERPASS_QUERY_TIMEOUT_SEC,
      servicePassOnly: Boolean = false,
    ): String {
      val boxes = GeoMath.splitBboxAtAntimeridian(bbox.south, bbox.west, bbox.north, bbox.east)
      val wayClause =
        if (servicePassOnly) {
          """way["highway"="service"]"""
        } else {
          val excludeRegex =
            if (mode == TravelMode.DRIVING) {
              "^(proposed|construction|abandoned|razed|platform|raceway|bus_guideway|footway|steps|path|corridor|pedestrian|cycleway|bridleway|service)$"
            } else {
              "^(proposed|construction|abandoned|razed|platform|raceway|bus_guideway)$"
            }
          """way["highway"]["highway"!~"$excludeRegex"]"""
        }

      val body =
        if (boxes.size == 1) {
          val b = boxes[0]
          val bboxStr = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", b.south, b.west, b.north, b.east)
          """$wayClause($bboxStr);"""
        } else {
          val parts =
            boxes.joinToString(" ") { b ->
              val bboxStr = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f", b.south, b.west, b.north, b.east)
              """$wayClause($bboxStr);"""
            }
          """($parts);"""
        }
      return """[out:json][timeout:$timeoutSec]; $body out geom qt;"""
    }

    /**
     * Parses Overpass JSON (`out geom qt;` with `nodes` + `geometry`, as well as `out body qt;`).
     */
    fun parseOverpassJson(jsonStr: String, fetchedAtMs: Long, fromCache: Boolean): RoadGraph {
      val root = JSONObject(jsonStr)
      val elements =
        root.optJSONArray("elements")
          ?: return RoadGraph(emptyMap(), emptyMap(), emptyMap(), fetchedAtMs, fromCache, GraphDataSource.OVERPASS)

      val nodes = HashMap<Long, OsmNode>(elements.length())
      val coordToTaggedNode = HashMap<Long, OsmNode>()
      val rawWays = mutableListOf<Triple<Long, List<Long>, OsmTags>>()

      for (i in 0 until elements.length()) {
        val el = elements.optJSONObject(i) ?: continue
        if (el.optString("type") != "node") continue
        val id = el.optLong("id", -1L)
        if (id < 0) continue
        val lat = el.optDouble("lat", Double.NaN)
        val rawLon = el.optDouble("lon", Double.NaN)
        if (lat.isNaN() || rawLon.isNaN()) continue
        val lon = GeoMath.normalizeLon(rawLon)

        val filteredTags = extractAllowedTags(el.optJSONObject("tags"))
        val osmNode = OsmNode(id = id, point = GeoPoint(lat, lon), tags = filteredTags)
        nodes[id] = osmNode
        if (filteredTags.hasAnyTag()) {
          coordToTaggedNode[quantizedCoordKey(lat, lon)] = osmNode
        }
      }

      for (i in 0 until elements.length()) {
        val el = elements.optJSONObject(i) ?: continue
        if (el.optString("type") != "way") continue
        val id = el.optLong("id", -1L)
        if (id < 0) continue
        val filteredTags = extractAllowedTags(el.optJSONObject("tags"))
        val geomArray = el.optJSONArray("geometry")
        val ndArray = el.optJSONArray("nodes")

        if (geomArray != null && geomArray.length() >= 2) {
          val ndIds = ArrayList<Long>(geomArray.length())
          for (j in 0 until geomArray.length()) {
            val pt = geomArray.optJSONObject(j) ?: continue
            val lat = pt.optDouble("lat", Double.NaN)
            val rawLon = pt.optDouble("lon", Double.NaN)
            if (lat.isNaN() || rawLon.isNaN()) continue
            val lon = GeoMath.normalizeLon(rawLon)

            val explicitNodeId =
              if (ndArray != null && ndArray.length() == geomArray.length()) ndArray.optLong(j, -1L) else -1L
            val coordKey = quantizedCoordKey(lat, lon)
            val taggedAtCoord = coordToTaggedNode[coordKey]
            val nodeId =
              when {
                explicitNodeId >= 0L -> explicitNodeId
                taggedAtCoord != null -> taggedAtCoord.id
                else -> -((coordKey and 0x3FFFFFFFFFFFFFFFL) + 1L)
              }
            if (!nodes.containsKey(nodeId)) {
              nodes[nodeId] =
                OsmNode(
                  id = nodeId,
                  point = GeoPoint(lat, lon),
                  tags = taggedAtCoord?.tags ?: OsmTags(),
                )
            }
            ndIds.add(nodeId)
          }
          if (ndIds.size >= 2) {
            rawWays.add(Triple(id, ndIds, filteredTags))
          }
        } else if (ndArray != null) {
          val ndIds = ArrayList<Long>(ndArray.length())
          for (j in 0 until ndArray.length()) {
            ndIds.add(ndArray.optLong(j))
          }
          if (ndIds.size >= 2) {
            rawWays.add(Triple(id, ndIds, filteredTags))
          }
        }
      }

      val ways = HashMap<Long, OsmWay>(rawWays.size)
      val nodeToWays = HashMap<Long, MutableList<Long>>()

      for ((wayId, ndIds, tags) in rawWays) {
        val pts = ArrayList<GeoPoint>(ndIds.size)
        val validIds = ArrayList<Long>(ndIds.size)
        for (nid in ndIds) {
          val node = nodes[nid] ?: continue
          validIds.add(nid)
          pts.add(node.point)
        }
        if (pts.size >= 2) {
          ways[wayId] =
            OsmWay(
              id = wayId,
              nodeIds = validIds,
              points = pts,
              tags = tags,
              source = GraphDataSource.OVERPASS,
            )
          for (nid in validIds.distinct()) {
            nodeToWays.getOrPut(nid) { mutableListOf() }.add(wayId)
          }
        }
      }

      return RoadGraph(
        nodes = nodes,
        ways = ways,
        nodeToWays = nodeToWays,
        fetchedAtMs = fetchedAtMs,
        fromCache = fromCache,
        source = GraphDataSource.OVERPASS,
      )
    }

    fun mergeGraphs(graphs: List<RoadGraph>, fromCache: Boolean): RoadGraph {
      if (graphs.isEmpty()) {
        return RoadGraph(emptyMap(), emptyMap(), emptyMap(), fetchedAtMs = 0L, fromCache = fromCache, source = GraphDataSource.OVERPASS)
      }
      if (graphs.size == 1) {
        return graphs[0].copy(fromCache = fromCache)
      }
      val nodes = HashMap<Long, OsmNode>()
      val ways = HashMap<Long, OsmWay>()
      val nodeToWays = HashMap<Long, MutableList<Long>>()
      var newestFetch = 0L

      for (g in graphs) {
        if (g.fetchedAtMs > newestFetch) newestFetch = g.fetchedAtMs
        nodes.putAll(g.nodes)
        for ((wayId, way) in g.ways) {
          if (!ways.containsKey(wayId)) {
            ways[wayId] = way
            for (nid in way.nodeIds.distinct()) {
              nodeToWays.getOrPut(nid) { mutableListOf() }.add(wayId)
            }
          }
        }
      }
      return RoadGraph(
        nodes = nodes,
        ways = ways,
        nodeToWays = nodeToWays,
        fetchedAtMs = newestFetch,
        fromCache = fromCache,
        source = GraphDataSource.OVERPASS,
      )
    }

    private fun quantizedCoordKey(lat: Double, lon: Double): Long {
      val latE7 = kotlin.math.round(lat * 1e7).toLong()
      val lonE7 = kotlin.math.round(GeoMath.normalizeLon(lon) * 1e7).toLong()
      return (latE7 * 1_000_003L) xor (lonE7 shl 28) xor (lonE7 ushr 3)
    }

    private fun extractAllowedTags(tagsObj: JSONObject?): OsmTags {
      if (tagsObj == null) return OsmTags()
      val map = HashMap<String, String>()
      val keys = tagsObj.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        if (OsmTags.isAllowedKey(key)) {
          val value = tagsObj.optString(key, "")
          if (value.isNotBlank()) {
            map[key] = value
          }
        }
      }
      return OsmTags.fromMap(map)
    }
  }
}
