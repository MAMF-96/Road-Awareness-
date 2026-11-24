package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.example.config.AppConfig
import com.example.data.local.CorridorCacheDao
import com.example.data.local.CorridorCacheEntity
import com.example.data.model.BilingualName
import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.LocationSnapshot
import com.example.data.model.MatchResult
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.RoadCandidate
import com.example.data.model.TileRoadHint
import com.example.data.model.TravelMode
import com.example.data.remote.GridCellId
import com.example.data.remote.OverpassMapDataSource
import com.example.domain.AheadGraphAnalyzer
import com.example.domain.GeoMath
import com.example.domain.MapMatcher
import com.example.ui.RoadHudUiState
import com.example.ui.screens.RoadAwarenessScreenContent
import com.example.ui.theme.RoadAwarenessTheme
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.floor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class StatusTextAndFastOverpassTest {

  @get:Rule val composeRule = createComposeRule()

  private class FakeCorridorCacheDao : CorridorCacheDao {
    private val map = ConcurrentHashMap<String, CorridorCacheEntity>()

    override suspend fun getCell(cellKey: String): CorridorCacheEntity? = map[cellKey]

    override suspend fun getCells(cellKeys: List<String>): List<CorridorCacheEntity> =
      cellKeys.mapNotNull { map[it] }

    override suspend fun getNearestCell(mode: String, lat: Double, lon: Double): CorridorCacheEntity? =
      map.values.filter { it.mode == mode }.minByOrNull {
        val dLat = it.centerLat - lat
        val dLon = it.centerLon - lon
        dLat * dLat + dLon * dLon
      }

    override suspend fun upsertCell(entity: CorridorCacheEntity) {
      map[entity.cellKey] = entity
    }

    override suspend fun upsertCells(entities: List<CorridorCacheEntity>) {
      entities.forEach { map[it.cellKey] = it }
    }

    override suspend fun countCells(): Int = map.size

    override suspend fun getOldestFetchedAtMs(): Long? = map.values.minOfOrNull { it.fetchedAtMs }

    override suspend fun clearAll() {
      map.clear()
    }
  }

  private fun setHudContent(uiState: RoadHudUiState) {
    composeRule.setContent {
      RoadAwarenessTheme {
        RoadAwarenessScreenContent(
          uiState = uiState,
          onModeSelected = {},
          onSelectFeature = {},
          onGrantLocationClick = {},
          onOpenSearch = {},
          onRecenter = {},
          onOpenSettings = {},
          onSearchQueryChange = {},
          onExecuteSearch = {},
          onSelectPlace = {},
          onSelectTheme = {},
          onToggleArabicTts = {},
          onToggleShowInspector = {},
          onInspectorDetentChange = {},
          onMockHeadingChange = {},
          onToggleMockMode = {},
          onDebugFlagsChange = { _, _, _ -> },
          onClearCache = {},
          mapContent = { mod, _, _, _, _, _ -> Box(modifier = mod) },
        )
      }
    }
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun statusText_loadingShowsSkeletonsAndNeverLeaksTechnicalErrors() {
    val loadingEmpty =
      RoadHudUiState(
        hasLocationPermission = true,
        isGpsSearching = false,
        location = LocationSnapshot(GeoPoint(30.0444, 31.2357), 0.0, 0.0, 4f, true, 1000L),
        isLoadingGraph = true,
        hasConfirmedGraphData = false,
        lastConfirmedRoadName = null,
        loadingStallReason = "Overpass request slow (>4s)",
        networkError = "HTTP 504 Gateway Timeout",
      )

    setHudContent(loadingEmpty)
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("header_road_skeleton").assertIsDisplayed()
    composeRule.onNodeWithTag("panel_title_skeleton").assertIsDisplayed()
    composeRule.onNodeWithTag("panel_rows_skeleton").assertIsDisplayed()
    composeRule.onNodeWithTag("schematic_canvas").assertIsDisplayed()

    // Technical reasons & status strings must NEVER appear in main UI
    composeRule.onNodeWithText("Overpass", substring = true).assertDoesNotExist()
    composeRule.onNodeWithText("HTTP 504", substring = true).assertDoesNotExist()
    composeRule.onNodeWithText("Loading", substring = true).assertDoesNotExist()
    composeRule.onNodeWithTag("offline_chip").assertDoesNotExist()
    composeRule.onNodeWithTag("slow_data_chip").assertDoesNotExist()
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun statusText_failurePreservesLastRoadNameAndShowsSingleLineNoticeThenStaleAfter10s() {
    val confirmedRoad = BilingualName("شارع النيل", "Nile St", "M12")
    val failureUnder10s =
      RoadHudUiState(
        hasLocationPermission = true,
        isGpsSearching = false,
        location = LocationSnapshot(GeoPoint(30.0444, 31.2357), 0.0, 0.0, 4f, true, 1000L),
        isLoadingGraph = false,
        hasConfirmedGraphData = false,
        isGraphErrorState = true,
        isDataStaleOver10s = false,
        lastConfirmedRoadName = confirmedRoad,
        loadingStallReason = "Overpass request failed: HTTP 429",
        networkError = "HTTP 429",
      )

    setHudContent(failureUnder10s)
    composeRule.waitForIdle()

    composeRule.onNodeWithText("شارع النيل", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText("Nile St", substring = true).assertIsDisplayed()
    composeRule.onAllNodesWithTag("panel_failure_notice").assertCountEquals(1)
    composeRule.onNodeWithText("Searching roads…").assertIsDisplayed()
    composeRule.onNodeWithText("HTTP 429", substring = true).assertDoesNotExist()
    composeRule.onNodeWithText("Overpass", substring = true).assertDoesNotExist()
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun statusText_over10sShowsRetryingAndDelayedChipsAppearWithoutReplacingRoadName() {
    val state =
      RoadHudUiState(
        hasLocationPermission = true,
        isGpsSearching = false,
        location = LocationSnapshot(GeoPoint(30.0444, 31.2357), 0.0, 0.0, 4f, true, 1000L),
        isLoadingGraph = false,
        hasConfirmedGraphData = false,
        isGraphErrorState = true,
        isDataStaleOver10s = true,
        showSlowDataChipAfter5s = true,
        tileFallbackRoad = TileRoadHint(BilingualName(null, "Corniche El-Nil", null), "primary"),
        roadNameSource = "tiles",
      )

    setHudContent(state)
    composeRule.waitForIdle()

    composeRule.onNodeWithText("Corniche El-Nil").assertIsDisplayed()
    composeRule.onNodeWithTag("slow_data_chip").assertIsDisplayed()
    composeRule.onNodeWithText("Road data unavailable, retrying").assertIsDisplayed()
  }

  @Test
  @Config(sdk = [36], qualifiers = "ar-w360dp-h800dp")
  fun statusText_arabicLocale_rendersLocalizedFailureLine() {
    val arState =
      RoadHudUiState(
        hasLocationPermission = true,
        isGpsSearching = false,
        location = LocationSnapshot(GeoPoint(30.0444, 31.2357), 0.0, 0.0, 4f, true, 1000L),
        isGraphErrorState = true,
        isDataStaleOver10s = true,
        showOfflineChipAfter5s = true,
        lastConfirmedRoadName = BilingualName("طريق النصر", "El Nasr Rd", null),
      )

    setHudContent(arState)
    composeRule.waitForIdle()

    composeRule.onNodeWithText("طريق النصر").assertIsDisplayed()
    composeRule.onNodeWithTag("offline_chip").assertIsDisplayed()
    composeRule.onNodeWithText("بيانات الطرق غير متوفرة، جارٍ إعادة المحاولة").assertIsDisplayed()
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun statusText_failedStageShowsSingleStageLineAndRetryButton() {
    var retried = false
    val state =
      RoadHudUiState(
        hasLocationPermission = true,
        isGpsSearching = false,
        location = LocationSnapshot(GeoPoint(35.6812, 139.7671), 0.0, 0.0, 4f, true, 1000L),
        isGraphErrorState = true,
        isDataStaleOver10s = true,
        roadDataFailedStage = "MVT decode",
        lastConfirmedRoadName = BilingualName(null, "Chuo-dori", null, localPrimary = "中央通り", secondaryLocal = "Chuo-dori"),
      )

    composeRule.setContent {
      RoadAwarenessTheme {
        RoadAwarenessScreenContent(
          uiState = state,
          onModeSelected = {},
          onSelectFeature = {},
          onGrantLocationClick = {},
          onOpenSearch = {},
          onRecenter = {},
          onOpenSettings = {},
          onSearchQueryChange = {},
          onExecuteSearch = {},
          onSelectPlace = {},
          onSelectTheme = {},
          onToggleArabicTts = {},
          onToggleShowInspector = {},
          onInspectorDetentChange = {},
          onMockHeadingChange = {},
          onToggleMockMode = {},
          onDebugFlagsChange = { _, _, _ -> },
          onClearCache = {},
          onRetryRoadData = { retried = true },
          mapContent = { mod, _, _, _, _, _ -> Box(modifier = mod) },
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithText("中央通り").assertIsDisplayed()
    composeRule.onNodeWithText("Road data failed: MVT decode").assertIsDisplayed()
    composeRule.onNodeWithTag("panel_retry_button").assertIsDisplayed()
  }

  @Test
  @Config(sdk = [36])
  fun overpassParser_outTagsGeomWithoutNodesArray_linksCrossingWaysAndDetectsFourWay() {
    val json =
      """
      {
        "elements": [
          {
            "type": "way",
            "id": 1001,
            "tags": {"highway": "primary", "name:ar": "شارع التحرير", "name:en": "Tahrir St"},
            "geometry": [
              {"lat": 30.0000000, "lon": 31.0000000},
              {"lat": 30.0020000, "lon": 31.0000000},
              {"lat": 30.0032000, "lon": 31.0000000}
            ]
          },
          {
            "type": "way",
            "id": 1002,
            "tags": {"highway": "secondary", "name:en": "Side Cross St", "destination": "Downtown"},
            "geometry": [
              {"lat": 30.0020000, "lon": 30.9990000},
              {"lat": 30.0020000, "lon": 31.0000000},
              {"lat": 30.0020000, "lon": 31.0010000}
            ]
          }
        ]
      }
      """.trimIndent()

    val graph = OverpassMapDataSource.parseOverpassJson(json, fetchedAtMs = 1234L, fromCache = false)
    assertEquals(2, graph.ways.size)
    // Both ways share (30.0020, 31.0000) so at least one synthesized node must reference both ways
    assertTrue(
      "Shared intersection vertex from out tags geom must link both ways",
      graph.nodeToWays.values.any { it.containsAll(listOf(1001L, 1002L)) },
    )

    val match = MapMatcher.match(graph, GeoPoint(30.0005, 31.0000), headingDeg = 0.0, TravelMode.DRIVING, null)
    val primary = match.primary
    assertNotNull(primary)
    assertEquals(1001L, primary!!.way.id)

    val features = AheadGraphAnalyzer.analyzeAhead(graph, primary, TravelMode.DRIVING, speedMps = 12.0)
    assertTrue(features.any { it.type == FeatureType.FOUR_WAY && it.branches.size == 3 })
  }

  @Test
  @Config(sdk = [36])
  fun overpassStream_usesTileCacheImmediatelyFetchesMissingEnforcesSingleFlightAndLogs() = runBlocking {
    val center = GeoPoint(30.0444, 31.2357)
    val latIdx = floor(center.lat / AppConfig.CACHE_CELL_SIZE_DEG).toInt()
    val lonIdx = floor(center.lon / AppConfig.CACHE_CELL_SIZE_DEG).toInt()
    val cachedCell = GridCellId(TravelMode.DRIVING, latIdx, lonIdx)

    val cachedJson =
      """
      {
        "elements": [
          {
            "type": "way",
            "id": 501,
            "tags": {"highway": "primary", "name:en": "Cached Main St"},
            "geometry": [
              {"lat": ${cachedCell.south + 0.0005}, "lon": ${cachedCell.centerLon}},
              {"lat": ${cachedCell.north - 0.0005}, "lon": ${cachedCell.centerLon}}
            ]
          }
        ]
      }
      """.trimIndent()

    val dao = FakeCorridorCacheDao()
    val now = 1_000_000_000L
    dao.upsertCell(
      CorridorCacheEntity(
        cellKey = cachedCell.key,
        mode = TravelMode.DRIVING.name,
        centerLat = cachedCell.centerLat,
        centerLon = cachedCell.centerLon,
        rawOverpassJson = cachedJson,
        fetchedAtMs = now - 60_000L, // Fresh (< 7 days)
      )
    )

    val capturedQueries = CopyOnWriteArrayList<String>()
    val netJson =
      """
      {
        "elements": [
          {
            "type": "way",
            "id": 502,
            "tags": {"highway": "secondary", "name:en": "Fetched Ahead St"},
            "geometry": [
              {"lat": ${cachedCell.north - 0.0002}, "lon": ${cachedCell.centerLon}},
              {"lat": ${cachedCell.north + 0.0030}, "lon": ${cachedCell.centerLon}}
            ]
          }
        ]
      }
      """.trimIndent()

    val client =
      OkHttpClient.Builder()
        .addInterceptor(
          Interceptor { chain ->
            val req = chain.request()
            val buf = Buffer()
            req.body?.writeTo(buf)
            val bodyStr = URLDecoder.decode(buf.readUtf8(), "UTF-8")
            capturedQueries.add(bodyStr)
            Thread.sleep(40)
            Response.Builder()
              .request(req)
              .protocol(Protocol.HTTP_1_1)
              .code(200)
              .message("OK")
              .body(netJson.toResponseBody("application/json".toMediaType()))
              .build()
          }
        )
        .build()

    val ds =
      OverpassMapDataSource(
        cacheDao = dao,
        client = client,
        endpoints = listOf("https://overpass-primary.test/api/interpreter"),
        clockMs = { now },
      )

    val jobs =
      listOf(
        async { ds.fetchCorridorStream(center, headingDeg = 0.0, mode = TravelMode.DRIVING).toList() },
        async { ds.fetchCorridorStream(GeoMath.offsetPoint(center, 0.0, 20.0), headingDeg = 0.0, mode = TravelMode.DRIVING).toList() },
      )
    val allEmissions = jobs.awaitAll()
    val firstStream = allEmissions.first()

    assertTrue("Expected immediate cached emission + network completion", firstStream.size >= 2)
    val immediate = firstStream.first().getOrThrow()
    assertFalse(immediate.isFromNetwork)
    assertTrue(immediate.isPartialOrStale)
    assertTrue(immediate.graph.ways.containsKey(501L))

    val networkDone = firstStream.last().getOrThrow()
    assertTrue(networkDone.isFromNetwork)
    assertFalse(networkDone.isPartialOrStale)
    assertTrue(networkDone.graph.ways.containsKey(502L))

    assertTrue(
      "Semaphore must cap Overpass HTTP calls <= 2 (was ${ds.maxObservedConcurrentHttpCalls.get()})",
      ds.maxObservedConcurrentHttpCalls.get() in 1..2,
    )
    assertTrue(capturedQueries.isNotEmpty())
    val q = capturedQueries.first()
    assertTrue("Query must specify [timeout:10]", q.contains("[timeout:10]"))
    assertTrue("Query must specify out geom qt;", q.contains("out geom qt;"))

    val latestLog = ds.requestLogs.value.firstOrNull()
    assertNotNull(latestLog)
    assertTrue(latestLog!!.succeeded)
    assertTrue(latestLog.cellsCached >= 1)
    assertTrue(latestLog.cellsFetched >= 1)
  }

  @Test
  @Config(sdk = [36])
  fun overpassFailover_rotatesAndCoolsDownFailedEndpointsAndLogsFailures() = runBlocking {
    var fakeNow = 5_000_000L
    val dao = FakeCorridorCacheDao()
    val calledHosts = CopyOnWriteArrayList<String>()

    val validJson =
      """
      {
        "elements": [
          {
            "type": "way",
            "id": 900,
            "tags": {"highway": "residential", "name:en": "Failover St"},
            "geometry": [
              {"lat": 30.0440, "lon": 31.2357},
              {"lat": 30.0450, "lon": 31.2357}
            ]
          }
        ]
      }
      """.trimIndent()

    val client =
      OkHttpClient.Builder()
        .addInterceptor(
          Interceptor { chain ->
            val req: Request = chain.request()
            val host = req.url.host
            calledHosts += host
            val (code, body) =
              when (host) {
                "ep1.test" -> 429 to "Too Many Requests"
                "ep2.test" -> 503 to "Service Unavailable"
                else -> 200 to validJson
              }
            Response.Builder()
              .request(req)
              .protocol(Protocol.HTTP_1_1)
              .code(code)
              .message(if (code == 200) "OK" else "ERR")
              .body(body.toResponseBody("application/json".toMediaType()))
              .build()
          }
        )
        .build()

    val ds =
      OverpassMapDataSource(
        cacheDao = dao,
        client = client,
        endpoints =
          listOf(
            "https://ep1.test/api/interpreter",
            "https://ep2.test/api/interpreter",
            "https://ep3.test/api/interpreter",
          ),
        clockMs = { fakeNow },
      )

    val res1 = ds.fetchCorridorGraph(GeoPoint(30.0444, 31.2357), 0.0, TravelMode.DRIVING, radiusMeters = 300.0)
    assertTrue("Failover to ep3.test must succeed", res1.isSuccess)
    assertTrue(res1.getOrThrow().ways.containsKey(900L))

    val log1 = ds.requestLogs.value.first()
    assertEquals("https://ep3.test/api/interpreter", log1.endpoint)
    assertTrue(log1.succeeded)
    assertEquals(2, log1.failures.size)
    assertTrue(log1.failures[0].contains("ep1.test") && log1.failures[0].contains("429"))
    assertTrue(log1.failures[1].contains("ep2.test") && log1.failures[1].contains("503"))

    // Within 30s cooldown, ep1 and ep2 must be skipped on the next uncached fetch
    calledHosts.clear()
    fakeNow += 5_000L
    val res2 = ds.fetchCorridorGraph(GeoPoint(31.5000, 31.5000), 0.0, TravelMode.DRIVING, radiusMeters = 300.0)
    assertTrue(res2.isSuccess)
    assertTrue("Cooldown must skip ep1 & ep2 and hit ep3 directly", calledHosts.all { it == "ep3.test" })
  }
}
