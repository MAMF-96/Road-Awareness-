package com.example

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.config.AppConfig
import com.example.data.local.CorridorCacheDao
import com.example.data.local.CorridorCacheEntity
import com.example.data.local.TileCacheDao
import com.example.data.local.TileCacheEntity
import com.example.data.model.BilingualName
import com.example.data.model.BranchStatus
import com.example.data.model.DecodedTileArtifact
import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.LiveMapVectorFeature
import com.example.data.model.LocalPoint
import com.example.data.model.LocationSnapshot
import com.example.data.model.MatchResult
import com.example.data.model.OsmNode
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.OverpassRequestLog
import com.example.data.model.RelativeDirection
import com.example.data.model.RoadBranch
import com.example.data.model.RoadCandidate
import com.example.data.model.RoadFeature
import com.example.data.model.RoadGraph
import com.example.data.model.ThemePreference
import com.example.data.model.TravelMode
import com.example.data.remote.DebugNetworkSimulator
import com.example.data.remote.MvtDecoder
import com.example.data.remote.OverpassMapDataSource
import com.example.data.remote.TileRoadGraphSource
import com.example.data.repository.RoadGraphRepository
import com.example.domain.AheadGraphAnalyzer
import com.example.domain.GeoMath
import com.example.domain.GraphMerger
import com.example.domain.MapMatcher
import com.example.ui.RoadAwarenessViewModel
import com.example.ui.RoadHudUiState
import com.example.ui.components.buildInspectorPlainText
import com.example.ui.components.formatCacheStatsText
import com.example.ui.screens.RoadAwarenessScreenContent
import com.example.ui.theme.RoadAwarenessTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ReliableDataAndSimTest {

  @get:Rule val composeRule = createComposeRule()

  @After
  fun tearDown() {
    DebugNetworkSimulator.delayRangeMsOverride = null
    DebugNetworkSimulator.reset()
  }

  private class InMemoryCorridorDao : CorridorCacheDao {
    private val map = ConcurrentHashMap<String, CorridorCacheEntity>()
    override suspend fun getCell(cellKey: String): CorridorCacheEntity? = map[cellKey]
    override suspend fun getCells(cellKeys: List<String>): List<CorridorCacheEntity> = cellKeys.mapNotNull { map[it] }
    override suspend fun getNearestCell(mode: String, lat: Double, lon: Double): CorridorCacheEntity? =
      map.values.filter { it.mode == mode }.minByOrNull { (it.centerLat - lat) * (it.centerLat - lat) + (it.centerLon - lon) * (it.centerLon - lon) }
    override suspend fun upsertCell(entity: CorridorCacheEntity) { map[entity.cellKey] = entity }
    override suspend fun upsertCells(entities: List<CorridorCacheEntity>) { entities.forEach { map[it.cellKey] = it } }
    override suspend fun countCells(): Int = map.size
    override suspend fun getOldestFetchedAtMs(): Long? = map.values.minOfOrNull { it.fetchedAtMs }
    override suspend fun clearAll() { map.clear() }
  }

  private class InMemoryTileDao : TileCacheDao {
    private val map = ConcurrentHashMap<String, TileCacheEntity>()
    override suspend fun getTile(tileKey: String): TileCacheEntity? = map[tileKey]
    override suspend fun getTiles(tileKeys: List<String>): List<TileCacheEntity> = tileKeys.mapNotNull { map[it] }
    override suspend fun upsertTiles(entities: List<TileCacheEntity>) { entities.forEach { map[it.tileKey] = it } }
    override suspend fun countTiles(): Int = map.size
    override suspend fun getOldestFetchedAtMs(): Long? = map.values.minOfOrNull { it.fetchedAtMs }
    override suspend fun clearAll() { map.clear() }
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun basicDataChip_visibleOnlyWhenUsingTilesAloneWithoutOverpass() {
    val stateBasic =
      RoadHudUiState(
        hasLocationPermission = true,
        isGpsSearching = false,
        location = LocationSnapshot(GeoPoint(30.0444, 31.2357), 0.0, 10.0, 4f, true, 1000L),
        isUsingBasicTileData = true,
      )
    composeRule.setContent {
      RoadAwarenessTheme(themePreference = ThemePreference.LIGHT) {
        RoadAwarenessScreenContent(
          uiState = stateBasic,
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
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("basic_data_chip").assertIsDisplayed()
    composeRule.onNodeWithTag("mode_segmented_control").assertIsDisplayed()
    composeRule.onNodeWithTag("mode_segmented_thumb").assertIsDisplayed()
  }

  @Test
  @Config(sdk = [36])
  fun graphMerger_overpassWinsTagsAndDestinationsPreservesNonOverlappedTiles() {
    val p1 = GeoPoint(30.000, 31.000)
    val p2 = GeoPoint(30.001, 31.000)
    val p3 = GeoPoint(30.002, 31.000)

    val tileWayA = OsmWay(1L, listOf(10L, 11L), listOf(p1, p2), OsmTags(highway = "primary", nameEn = "Tile Rd"), source = GraphDataSource.TILES)
    val tileWayB = OsmWay(2L, listOf(11L, 12L), listOf(p2, p3), OsmTags(highway = "secondary", nameEn = "Unoverlapped Tile Rd"), source = GraphDataSource.TILES)
    val tileGraph =
      RoadGraph(
        nodes = mapOf(10L to OsmNode(10L, p1), 11L to OsmNode(11L, p2), 12L to OsmNode(12L, p3)),
        ways = mapOf(1L to tileWayA, 2L to tileWayB),
        nodeToWays = mapOf(10L to listOf(1L), 11L to listOf(1L, 2L), 12L to listOf(2L)),
        fetchedAtMs = 100L,
        fromCache = false,
        source = GraphDataSource.TILES,
      )

    val opWay = OsmWay(
      9001L,
      listOf(100L, 101L),
      listOf(p1, p2),
      OsmTags(highway = "primary", nameEn = "Tile Rd", destination = "Downtown", lanes = "3", oneway = "yes"),
    )
    val overpassGraph =
      RoadGraph(
        nodes = mapOf(100L to OsmNode(100L, p1), 101L to OsmNode(101L, p2)),
        ways = mapOf(9001L to opWay),
        nodeToWays = mapOf(100L to listOf(9001L), 101L to listOf(9001L)),
        fetchedAtMs = 200L,
        fromCache = false,
        source = GraphDataSource.OVERPASS,
      )

    val merged = GraphMerger.merge(tileGraph, overpassGraph)
    assertNotNull(merged)
    assertEquals(GraphDataSource.MERGED, merged!!.source)
    // Overlapping tile way (1L) replaced by richer Overpass way (9001L)
    assertFalse("Overlapped tile way must be replaced by Overpass", merged.ways.containsKey(1L))
    assertTrue("Overpass way present with destination & lanes", merged.ways[9001L]?.tags?.destination == "Downtown")
    assertEquals("3", merged.ways[9001L]?.tags?.lanes)
    assertTrue("Non-overlapped tile way preserved", merged.ways.containsKey(2L))
    assertEquals(GraphDataSource.TILES, merged.ways[2L]?.source)
  }

  @Test
  @Config(sdk = [36])
  fun mvtDecoderAndTileRoadGraphSource_decodesLayersAttachesNamesStitchesAcrossTiles() = runBlocking {
    val pt = GeoPoint(30.0444, 31.2357)
    val (tx, ty) = TileRoadGraphSource.latLonToTile(pt.lat, pt.lon, 14)

    val pbfTile0 = buildTestMvtTile(
      layerTransTags = mapOf("class" to "primary", "brunnel" to "bridge"),
      transCoords = listOf(1024 to 2048, 4096 to 2048),
      layerNameTags = mapOf("class" to "primary", "name:en" to "Tile Bridge Rd", "name:ar" to "طريق الكوبري"),
      nameCoords = listOf(1024 to 2048, 4096 to 2048),
    )
    val pbfTile1 = buildTestMvtTile(
      layerTransTags = mapOf("class" to "primary"),
      transCoords = listOf(0 to 2048, 2048 to 2048),
      layerNameTags = mapOf("class" to "primary", "name:en" to "Tile Bridge Rd"),
      nameCoords = listOf(0 to 2048, 2048 to 2048),
    )

    val t0 = MvtDecoder.decode(pbfTile0, z = 14, x = tx, y = ty, layerFilter = setOf("transportation", "transportation_name"))
    val t1 = MvtDecoder.decode(pbfTile0.let { pbfTile1 }, z = 14, x = tx + 1, y = ty, layerFilter = setOf("transportation", "transportation_name"))

    val graph = TileRoadGraphSource.buildRoadGraphFromDecodedTiles(listOf(t0, t1), TravelMode.DRIVING, fetchedAtMs = 500L, fromCache = false)
    assertEquals(GraphDataSource.TILES, graph.source)
    assertEquals(2, graph.ways.size)
    // Shared border node (px=4096 in tx == px=0 in tx+1) stitched cleanly to 1e-6 deg!
    assertTrue("Border node must link both tile ways", graph.nodeToWays.values.any { it.size >= 2 })
    val bridgeWay = graph.ways.values.firstOrNull { it.tags.bridge == "yes" }
    assertNotNull("Decoded tile graph must include bridge way from t0", bridgeWay)
    assertEquals("Tile Bridge Rd", bridgeWay!!.tags.nameEn)
    assertEquals("طريق الكوبري", bridgeWay.tags.nameAr)
    assertEquals("yes", bridgeWay.tags.bridge)
    assertNull("Tile graph must never infer destinations", bridgeWay.tags.destination)
  }

  @Test
  @Config(sdk = [36])
  fun tileRoadGraphSource_missingTransportationLayerReturnsFailureWithoutBreaking(): Unit = runBlocking {
    val styleDir = File.createTempFile("styledir", "").apply { delete(); mkdirs() }
    val styleJson =
      """
      {
        "sources": {
          "openmaptiles": {
            "type": "vector",
            "tiles": ["https://tiles.test/{z}/{x}/{y}.pbf"],
            "vector_layers": [{"id": "landuse"}]
          }
        }
      }
      """.trimIndent()
    val client =
      OkHttpClient.Builder()
        .addInterceptor { chain ->
          Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(styleJson.toResponseBody("application/json".toMediaType()))
            .build()
        }
        .build()

    val ds = TileRoadGraphSource(InMemoryTileDao(), styleDir, client = client, styleUrl = "https://style.test/liberty.json")
    val emissions = ds.fetchCorridorStream(GeoPoint(30.0444, 31.2357), 0.0, TravelMode.DRIVING).toList()
    assertTrue("Missing transportation layer must fail gracefully", emissions.last().isFailure)
    styleDir.deleteRecursively()
  }

  @Test
  @Config(sdk = [36])
  fun overpassClient_enforcesCompliantHeaders406CooldownAndTestAllEndpoints(): Unit = runBlocking {
    var fakeNow = 10_000_000L
    val dao = InMemoryCorridorDao()
    val seenHeaders = CopyOnWriteArrayList<Map<String, String>>()
    val hostsHit = CopyOnWriteArrayList<String>()

    val validJson = """{"elements":[{"type":"way","id":77,"tags":{"highway":"primary","name:en":"Valid Way"},"geometry":[{"lat":30.0440,"lon":31.2357},{"lat":30.0450,"lon":31.2357}]}]}"""

    val client =
      OkHttpClient.Builder()
        .addInterceptor { chain ->
          val req = chain.request()
          hostsHit += req.url.host
          val hdrs = mutableMapOf<String, String>()
          for (i in 0 until req.headers.size) {
            hdrs[req.headers.name(i).lowercase()] = req.headers.value(i)
          }
          seenHeaders += hdrs
          val (code, body) =
            if (req.url.host == "bad406.test") 406 to "Not Acceptable due to Accept-Encoding"
            else 200 to validJson
          Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Not Acceptable")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        }
        .build()

    val ds =
      OverpassMapDataSource(
        cacheDao = dao,
        client = client,
        endpoints = listOf("https://bad406.test/api/interpreter", "https://good.test/api/interpreter"),
        clockMs = { fakeNow },
        hedgeDelayMs = 500L,
      )

    val res = ds.fetchCorridorGraph(GeoPoint(30.0444, 31.2357), 0.0, TravelMode.DRIVING, radiusMeters = 300.0)
    assertTrue(res.isSuccess)
    assertTrue("406 must capture snippet & headers in logs", ds.requestLogs.value.any { it.status == "406" && it.responseSnippet?.contains("Not Acceptable") == true })
    val h = seenHeaders.first()
    assertEquals("*/*", h["accept"])
    assertNotNull(h["user-agent"])
    assertNull("Must NOT send Origin header", h["origin"])
    assertNull("Must NOT send Referer header", h["referer"])

    // Within 10m 406 cooldown, bad406.test is skipped
    hostsHit.clear()
    fakeNow += 60_000L
    ds.fetchCorridorGraph(GeoPoint(31.2000, 31.3000), 0.0, TravelMode.DRIVING, radiusMeters = 300.0)
    assertFalse("406 endpoint must be in 10m cooldown", hostsHit.contains("bad406.test"))

    // Test Overpass runs tiny + road queries against all endpoints
    val testResults = ds.testAllEndpoints(GeoPoint(30.0444, 31.2357), TravelMode.DRIVING)
    assertEquals(2, testResults.size)
    assertEquals("HTTP 406", testResults[0].tinyStatus)
    assertEquals("200", testResults[1].tinyStatus)
    assertEquals("200", testResults[1].roadStatus)
  }

  @Test
  @Config(sdk = [36])
  fun debugNetworkSimulator_blocksTrafficForcesErrorsAndSlowDelaysAccurately() {
    val mockTransportHits = AtomicInteger(0)
    val client =
      OkHttpClient.Builder()
        .addInterceptor(DebugNetworkSimulator.interceptor)
        .addInterceptor { chain ->
          mockTransportHits.incrementAndGet()
          Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()
        }
        .build()

    val opReq = Request.Builder().url("https://overpass-api.de/api/interpreter").build()
    val tileReq = Request.Builder().url("https://tiles.openfreemap.org/planet/14/10/10.pbf").build()

    // 1. Offline throws UnknownHostException for ALL requests
    DebugNetworkSimulator.setOffline(true)
    val offlineThrown = runCatching { client.newCall(opReq).execute() }.exceptionOrNull()
    assertTrue("Offline must throw UnknownHostException", offlineThrown is UnknownHostException)
    assertEquals(0, mockTransportHits.get())
    DebugNetworkSimulator.setOffline(false)

    // 2. Block Overpass throws SocketTimeoutException on Overpass but lets tiles through
    DebugNetworkSimulator.setBlockOverpass(true)
    val opTimeout = runCatching { client.newCall(opReq).execute() }.exceptionOrNull()
    assertTrue("Block Overpass must throw SocketTimeoutException", opTimeout is SocketTimeoutException)
    client.newCall(tileReq).execute().use { assertEquals(200, it.code) }
    DebugNetworkSimulator.setBlockOverpass(false)

    // 3. Block tiles throws IOException on tile/style URLs
    DebugNetworkSimulator.setBlockTiles(true)
    val tileFail = runCatching { client.newCall(tileReq).execute() }.exceptionOrNull()
    assertTrue("Block tiles must throw IOException", tileFail is IOException)
    DebugNetworkSimulator.setBlockTiles(false)

    // 4. Force Overpass error synthesizes 406 / 429 / 502 / 504
    listOf(406, 429, 502, 504).forEach { code ->
      DebugNetworkSimulator.setForceOverpassError(code)
      client.newCall(opReq).execute().use { resp ->
        assertEquals(code, resp.code)
        if (code == 429) assertNotNull(resp.header("Retry-After"))
      }
    }

    // 5. Slow network delays requests
    DebugNetworkSimulator.setForceOverpassError(null)
    DebugNetworkSimulator.delayRangeMsOverride = 60L..60L
    DebugNetworkSimulator.setSlowNetwork(true)
    val t0 = System.currentTimeMillis()
    client.newCall(tileReq).execute().close()
    assertTrue(System.currentTimeMillis() - t0 >= 50L)
  }

  @Test
  @Config(sdk = [36])
  fun viewModelAndCopyReport_resetDebugOverridesClearsEverythingAndPlainTextIncludesFullLogs() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val vm = RoadAwarenessViewModel(app)

    vm.setDebugStatusFlags(poorGps = true, offline = true, uncertain = true)
    vm.setDebugNetworkSimulation(blockOverpass = true, blockTiles = true, slowNetwork = true, forceOverpassError = 406)
    val st = vm.uiState.value
    assertTrue(st.hasAnyDebugOverride)
    assertTrue(DebugNetworkSimulator.state.value.offline)
    assertEquals(406, DebugNetworkSimulator.state.value.forceOverpassErrorCode)

    val richState =
      st.copy(
        cachedCellCount = 4,
        cachedTileCount = 2,
        oldestCacheAgeMs = 120_000L,
        recentOverpassLogs =
          listOf(
            OverpassRequestLog(
              endpoint = "https://overpass-api.de/api/interpreter",
              durationMs = 420L,
              cellsFetched = 1,
              cellsCached = 3,
              succeeded = false,
              source = "overpass",
              method = "POST",
              protocol = "http/1.1",
              requestHeaders = mapOf("Accept" to "*/*", "User-Agent" to AppConfig.USER_AGENT),
              queryText = "[out:json][timeout:8];",
              encodedLength = 128,
              status = "406",
              responseSizeKb = 1.2,
              responseSnippet = "Not Acceptable test body",
              cooldownSummary = "overpass-api.de: 590s",
            )
          ),
        features =
          listOf(
            RoadFeature(
              index = 1,
              type = FeatureType.BRIDGE,
              distanceMeters = 50.0,
              anchor = GeoPoint(30.0, 31.0),
              anchorNodeId = 1L,
              incomingLocalPoints = emptyList(),
              incomingWorldPoints = emptyList(),
              outgoingMainLocalPoints = emptyList(),
              outgoingMainWorldPoints = emptyList(),
              branches =
                listOf(
                  RoadBranch(
                    badgeId = "1A",
                    wayId = 10L,
                    name = BilingualName(null, "Bridge Rd", null),
                    leadsTo = null,
                    distanceMeters = 50.0,
                    status = BranchStatus.OPEN,
                    relativeDirection = RelativeDirection.AHEAD,
                    bearingDeltaDeg = 0.0,
                    worldPoints = emptyList(),
                    localPoints = emptyList(),
                    osmTags = OsmTags(bridge = "yes"),
                  )
                ),
              osmTags = OsmTags(bridge = "yes"),
              source = GraphDataSource.TILES,
            )
          ),
      )

    val report = buildInspectorPlainText(richState)
    assertTrue(report.contains("Cache: 4 cells · 2 tiles"))
    assertTrue(report.contains("status=406"))
    assertTrue(report.contains("Not Acceptable test body"))
    assertTrue(report.contains("QUERY: [out:json][timeout:8];"))
    assertTrue(report.contains("[tiles]"))

    vm.resetDebugOverrides()
    val reset = vm.uiState.value
    assertFalse(reset.hasAnyDebugOverride)
    assertFalse(DebugNetworkSimulator.state.value.hasAnyActive)
  }

  private fun makeSyntheticMvtTile(
    z: Int,
    x: Int,
    y: Int,
    segmentCount: Int,
    highwayClass: String = "primary",
  ): MvtDecoder.MvtTile {
    val bounds = TileRoadGraphSource.computeTileBounds(z, x, y)
    val dLat = bounds.north - bounds.south
    val dLon = bounds.east - bounds.west
    val features =
      (0 until segmentCount).map { idx ->
        val row = (idx % 40) + 1
        val col = idx / 40
        val lat = bounds.south + (row.toDouble() / 42.0) * dLat
        val lon0 = bounds.west + ((col * 2 + 1).toDouble() / 120.0).coerceIn(0.02, 0.90) * dLon
        val lon1 = bounds.west + ((col * 2 + 2).toDouble() / 120.0).coerceIn(0.04, 0.98) * dLon
        MvtDecoder.MvtFeature(
          id = idx.toLong() + 1L,
          geomType = MvtDecoder.GeomType.LINESTRING,
          tags = mapOf("class" to highwayClass),
          geometries = listOf(listOf(GeoPoint(lat, lon0), GeoPoint(lat, lon1))),
        )
      }
    val layer = MvtDecoder.MvtLayer(name = "transportation", extent = 4096, features = features)
    return MvtDecoder.MvtTile(z = z, x = x, y = y, layers = mapOf("transportation" to layer))
  }

  @Test
  @Config(sdk = [36])
  fun a_nineNeighbourZ14TilesTimes2000SegmentsProduce18000UniqueIds() {
    val baseX = 9613
    val baseY = 6757
    val tiles =
      (-1..1).flatMap { dy ->
        (-1..1).map { dx ->
          makeSyntheticMvtTile(z = 14, x = baseX + dx, y = baseY + dy, segmentCount = 2_000)
        }
      }
    val graph =
      TileRoadGraphSource.buildRoadGraphFromDecodedTiles(
        tiles = tiles,
        mode = TravelMode.DRIVING,
        fetchedAtMs = 1_000L,
        fromCache = false,
      )
    assertEquals(18_000, graph.ways.size)
    assertEquals(18_000, graph.ways.keys.distinct().size)
  }

  @Test
  @Config(sdk = [36])
  fun b_tileAndLiveMapNamespacesNeverCollide() {
    val idsTile = HashSet<Long>()
    val idsMap = HashSet<Long>()
    for (x in listOf(0, 9613, 16383)) {
      for (y in listOf(0, 6757, 6758, 16383)) {
        for (c in listOf(0L, 6L, 70L, 2000L)) {
          val tId = TileRoadGraphSource.encodeWayId(TileRoadGraphSource.WAY_NS_TILES, 14, x, y, c)
          val mId = TileRoadGraphSource.encodeWayId(TileRoadGraphSource.WAY_NS_MAP, 14, x, y, c)
          assertTrue("Tile way ID must be negative", tId < 0L)
          assertTrue("Map way ID must be negative", mId < 0L)
          assertNotEquals(tId, mId)
          idsTile += tId
          idsMap += mId
        }
      }
    }
    assertTrue(idsTile.intersect(idsMap).isEmpty())

    val tileGraph =
      TileRoadGraphSource.buildRoadGraphFromDecodedTiles(
        tiles = listOf(makeSyntheticMvtTile(14, 9613, 6757, 10)),
        mode = TravelMode.DRIVING,
        fetchedAtMs = 1_000L,
        fromCache = false,
      )
    val bounds = TileRoadGraphSource.computeTileBounds(14, 9613, 6757)
    val liveFeatures =
      (0 until 10).map { i ->
        val lat = bounds.south + (i + 1) * 0.0005
        LiveMapVectorFeature(
          layerName = "transportation",
          tags = mapOf("class" to "primary"),
          geometries = listOf(listOf(GeoPoint(lat, bounds.west + 0.001), GeoPoint(lat, bounds.west + 0.002))),
        )
      }
    val mapGraph = TileRoadGraphSource.buildRoadGraphFromLiveFeatures(liveFeatures, TravelMode.DRIVING, fetchedAtMs = 1_000L)
    assertTrue(tileGraph.ways.keys.intersect(mapGraph.ways.keys).isEmpty())
  }

  @Test
  @Config(sdk = [36])
  fun c_verticalNeighbourRegressionSegment70VsSegment6DistinctIds() {
    val idTileASeg70 = TileRoadGraphSource.encodeWayId(TileRoadGraphSource.WAY_NS_TILES, 14, 9613, 6757, 70L)
    val idTileBSeg6 = TileRoadGraphSource.encodeWayId(TileRoadGraphSource.WAY_NS_TILES, 14, 9613, 6758, 6L)
    assertNotEquals(idTileASeg70, idTileBSeg6)

    val tileA = makeSyntheticMvtTile(14, 9613, 6757, segmentCount = 75)
    val tileB = makeSyntheticMvtTile(14, 9613, 6758, segmentCount = 10)
    val gA = TileRoadGraphSource.buildRoadGraphFromDecodedTiles(listOf(tileA), TravelMode.DRIVING, 1_000L, false)
    val gB = TileRoadGraphSource.buildRoadGraphFromDecodedTiles(listOf(tileB), TravelMode.DRIVING, 1_000L, false)
    assertEquals(75, gA.ways.size)
    assertEquals(10, gB.ways.size)
    assertTrue("No ID collision between (9613,6757) and (9613,6758)", gA.ways.keys.intersect(gB.ways.keys).isEmpty())
  }

  @Test
  @Config(sdk = [36])
  fun d_publishingNineTileGraphsKeepsEveryWayWithZeroCollisionsAndDanglingRefs() {
    val repo = RoadGraphRepository(maxMemoryTiles = 16)
    val baseX = 9613
    val baseY = 6757
    val artifacts =
      (-1..1).flatMap { dy ->
        (-1..1).map { dx ->
          val x = baseX + dx
          val y = baseY + dy
          val tile = makeSyntheticMvtTile(14, x, y, segmentCount = 25)
          val g = TileRoadGraphSource.buildRoadGraphFromDecodedTiles(listOf(tile), TravelMode.DRIVING, 2_000L, false)
          DecodedTileArtifact(
            tileKey = "14_${x}_${y}",
            z = 14,
            x = x,
            y = y,
            bounds = TileRoadGraphSource.computeTileBounds(14, x, y),
            graph = g,
            labels = emptyList(),
            pois = emptyList(),
            fetchedAtMs = 2_000L,
            isEmptyDecoded = false,
          )
        }
      }

    val expectedSum = artifacts.sumOf { it.graph.ways.size }
    assertEquals(225, expectedSum)
    val snap = repo.upsertDecodedArtifacts(artifacts)
    assertEquals(expectedSum, snap.graph.ways.size)
    assertEquals(0, snap.idCollisions)
    assertEquals(0, snap.danglingNodeRefs)
    assertTrue(
      "Every nodeToWays list must be duplicate-free",
      snap.graph.nodeToWays.values.all { list -> list.size == list.distinct().size },
    )
  }

  @Test
  @Config(sdk = [36])
  fun e_roadCrossingTileBorderMatchesWithinTwoMetersOnBothSides() {
    val pt = GeoPoint(30.0444, 31.2357)
    val (tx, ty) = TileRoadGraphSource.latLonToTile(pt.lat, pt.lon, 14)

    val pbfWest =
      buildTestMvtTile(
        layerTransTags = mapOf("class" to "primary"),
        transCoords = listOf(1024 to 2048, 4096 to 2048),
        layerNameTags = mapOf("class" to "primary", "name:en" to "Border Crossing Rd"),
        nameCoords = listOf(1024 to 2048, 4096 to 2048),
      )
    val pbfEast =
      buildTestMvtTile(
        layerTransTags = mapOf("class" to "primary"),
        transCoords = listOf(0 to 2048, 3072 to 2048),
        layerNameTags = mapOf("class" to "primary", "name:en" to "Border Crossing Rd"),
        nameCoords = listOf(0 to 2048, 3072 to 2048),
      )

    val tWest = MvtDecoder.decode(pbfWest, z = 14, x = tx, y = ty, layerFilter = setOf("transportation", "transportation_name"))
    val tEast = MvtDecoder.decode(pbfEast, z = 14, x = tx + 1, y = ty, layerFilter = setOf("transportation", "transportation_name"))

    val repo = RoadGraphRepository()
    val gWest = TileRoadGraphSource.buildRoadGraphFromDecodedTiles(listOf(tWest), TravelMode.DRIVING, 1_000L, false)
    val gEast = TileRoadGraphSource.buildRoadGraphFromDecodedTiles(listOf(tEast), TravelMode.DRIVING, 1_000L, false)
    val snap =
      repo.upsertTileGraphsBatch(
        listOf(
          RoadGraphRepository.TileBatchItem("14_${tx}_${ty}", TileRoadGraphSource.computeTileBounds(14, tx, ty), gWest, 1_000L, false),
          RoadGraphRepository.TileBatchItem("14_${tx + 1}_${ty}", TileRoadGraphSource.computeTileBounds(14, tx + 1, ty), gEast, 1_000L, false),
        ),
      )
    assertEquals(2, snap.graph.ways.size)
    assertEquals(0, snap.idCollisions)

    val westBorderPt = MvtDecoder.tilePxToGeoPoint(14, tx, ty, 3950, 2048, 4096)
    val eastBorderPt = MvtDecoder.tilePxToGeoPoint(14, tx + 1, ty, 150, 2048, 4096)
    val mWest = MapMatcher.match(snap.graph, westBorderPt, headingDeg = 90.0, mode = TravelMode.DRIVING, previousWayId = null, spatialIndex = snap.index, graphVersion = snap.version)
    val mEast = MapMatcher.match(snap.graph, eastBorderPt, headingDeg = 90.0, mode = TravelMode.DRIVING, previousWayId = null, spatialIndex = snap.index, graphVersion = snap.version)

    assertNotNull(mWest.primary)
    assertNotNull(mEast.primary)
    assertTrue("West side offset <= 2m (was ${mWest.primary!!.lateralOffsetMeters})", mWest.primary!!.lateralOffsetMeters <= 2.0)
    assertTrue("East side offset <= 2m (was ${mEast.primary!!.lateralOffsetMeters})", mEast.primary!!.lateralOffsetMeters <= 2.0)
  }

  @Test
  @Config(sdk = [36])
  fun f_fallbackMergeAddsWaysWhenTileEdgesExistBeyond150mButNoneWithin150m() {
    val userPt = GeoPoint(30.0444, 31.2357)
    val distantTilePtA = GeoMath.offsetPoint(userPt, 0.0, 500.0)
    val distantTilePtB = GeoMath.offsetPoint(distantTilePtA, 90.0, 120.0)
    val tileWayId = TileRoadGraphSource.encodeWayId(TileRoadGraphSource.WAY_NS_TILES, 14, 9613, 6757, 1L)
    val tileWay = OsmWay(tileWayId, listOf(-10L, -11L), listOf(distantTilePtA, distantTilePtB), OsmTags(highway = "primary", nameEn = "Distant Tile Rd"), source = GraphDataSource.TILES)
    val tileGraph =
      RoadGraph(
        nodes = mapOf(-10L to OsmNode(-10L, distantTilePtA), -11L to OsmNode(-11L, distantTilePtB)),
        ways = mapOf(tileWayId to tileWay),
        nodeToWays = mapOf(-10L to listOf(tileWayId), -11L to listOf(tileWayId)),
        fetchedAtMs = 1_000L,
        fromCache = false,
        source = GraphDataSource.TILES,
      )

    val repo = RoadGraphRepository()
    repo.upsertTileGraph(
      tileKey = "14_9613_6757",
      bounds = TileRoadGraphSource.computeTileBounds(14, 9613, 6757),
      tileGraph = tileGraph,
      fetchedAtMs = 1_000L,
      isEmptyDecoded = false,
    )
    repo.updateKeepWindow(userPt, 3000.0, setOf("14_9613_6757"))
    val beforeFallback = repo.setMapFallbackGraph(null, evalCenter = userPt)
    assertEquals(1, beforeFallback.graph.ways.size)
    assertFalse(beforeFallback.hasPrimaryEdgeWithin150m)
    assertFalse(beforeFallback.mapFallbackUsed)

    val nearbyA = GeoMath.offsetPoint(userPt, 270.0, 50.0)
    val nearbyB = GeoMath.offsetPoint(userPt, 90.0, 50.0)
    val farFallbackA = GeoMath.offsetPoint(userPt, 180.0, 700.0)
    val farFallbackB = GeoMath.offsetPoint(farFallbackA, 90.0, 80.0)
    val mapGraph =
      TileRoadGraphSource.buildRoadGraphFromLiveFeatures(
        features =
          listOf(
            LiveMapVectorFeature("transportation", mapOf("class" to "secondary"), listOf(listOf(nearbyA, nearbyB), listOf(farFallbackA, farFallbackB))),
            LiveMapVectorFeature("transportation_name", mapOf("class" to "secondary", "name:en" to "Fallback Local St"), listOf(listOf(nearbyA, nearbyB))),
          ),
        mode = TravelMode.DRIVING,
        fetchedAtMs = 2_000L,
      )

    val mergedSnap = repo.setMapFallbackGraph(mapGraph, evalCenter = userPt)
    assertTrue("Fallback must activate when primary edges are >150m away", mergedSnap.mapFallbackUsed)
    assertEquals(1, mergedSnap.mapFallbackWaysAdded)
    assertEquals(0, mergedSnap.idCollisions)
    assertEquals(GraphDataSource.TILES, mergedSnap.graph.ways[tileWayId]?.source)
    assertEquals(2, mergedSnap.graph.ways.size)

    val match = MapMatcher.match(mergedSnap.graph, userPt, 90.0, TravelMode.DRIVING, previousWayId = null, spatialIndex = mergedSnap.index, graphVersion = mergedSnap.version)
    assertNotNull(match.primary)
    assertEquals(GraphDataSource.MAP, match.primary!!.way.source)
    assertEquals("Fallback Local St", match.primary!!.name.english)
  }

  @Test
  @Config(sdk = [36])
  fun g_drivingWalkingSwitchReturnsSameGraphInstanceWithoutRefetchAndAppliesModeFilters() = runBlocking {
    val userPt = GeoPoint(30.0444, 31.2357)
    val (tx, ty) = TileRoadGraphSource.latLonToTile(userPt.lat, userPt.lon, 14)
    val bounds = TileRoadGraphSource.computeTileBounds(14, tx, ty)
    val midLat = (bounds.south + bounds.north) * 0.5
    val midLon = (bounds.west + bounds.east) * 0.5
    val testPt = GeoPoint(midLat, midLon)

    val west = GeoMath.offsetPoint(testPt, 270.0, 120.0)
    val east = GeoMath.offsetPoint(testPt, 90.0, 120.0)
    val north = GeoMath.offsetPoint(testPt, 0.0, 100.0)
    val userStart = GeoMath.offsetPoint(testPt, 270.0, 40.0)

    val motorwayPts = listOf(west, testPt, east)
    val footwayPts = listOf(west, testPt, east)
    val branchWalkPts = listOf(testPt, north)

    val mixedTile =
      MvtDecoder.MvtTile(
        z = 14,
        x = tx,
        y = ty,
        layers =
          mapOf(
            "transportation" to
              MvtDecoder.MvtLayer(
                name = "transportation",
                extent = 4096,
                features =
                  listOf(
                    MvtDecoder.MvtFeature(1L, MvtDecoder.GeomType.LINESTRING, mapOf("class" to "motorway"), listOf(motorwayPts)),
                    MvtDecoder.MvtFeature(2L, MvtDecoder.GeomType.LINESTRING, mapOf("class" to "path", "subclass" to "footway"), listOf(footwayPts)),
                    MvtDecoder.MvtFeature(3L, MvtDecoder.GeomType.LINESTRING, mapOf("class" to "path", "subclass" to "footway"), listOf(branchWalkPts)),
                  ),
              ),
            "transportation_name" to
              MvtDecoder.MvtLayer(
                name = "transportation_name",
                extent = 4096,
                features =
                  listOf(
                    MvtDecoder.MvtFeature(10L, MvtDecoder.GeomType.LINESTRING, mapOf("class" to "motorway", "name:en" to "Ring Motorway"), listOf(motorwayPts)),
                    MvtDecoder.MvtFeature(11L, MvtDecoder.GeomType.LINESTRING, mapOf("class" to "path", "name:en" to "Park Walk"), listOf(footwayPts)),
                  ),
              ),
          ),
      )

    // Graph contains ALL classes regardless of build mode
    val builtDriving = TileRoadGraphSource.buildRoadGraphFromDecodedTiles(listOf(mixedTile), TravelMode.DRIVING, 1_000L, false)
    assertEquals(3, builtDriving.ways.size)

    val driveMatch = MapMatcher.match(builtDriving, userStart, 90.0, TravelMode.DRIVING, previousWayId = null)
    assertNotNull(driveMatch.primary)
    assertEquals("motorway", driveMatch.primary!!.way.tags.highway)
    val driveAhead = AheadGraphAnalyzer.analyzeAheadDetailed(builtDriving, driveMatch.primary!!, TravelMode.DRIVING, 15.0)
    assertTrue(
      "Driving must filter out footway branches",
      driveAhead.features.none { f -> f.branches.any { b -> b.osmTags.highway == "footway" } },
    )

    val walkMatch = MapMatcher.match(builtDriving, userStart, 90.0, TravelMode.WALKING, previousWayId = null)
    assertNotNull(walkMatch.primary)
    assertEquals("footway", walkMatch.primary!!.way.tags.highway)
    val walkAhead = AheadGraphAnalyzer.analyzeAheadDetailed(builtDriving, walkMatch.primary!!, TravelMode.WALKING, 1.4)
    assertTrue(
      "Walking must see footway branch on same graph",
      walkAhead.features.any { f -> f.branches.any { b -> b.osmTags.highway == "footway" } },
    )

    // ViewModel Driving <-> Walking switch keeps exact same RoadGraph object without refetching
    val app = ApplicationProvider.getApplicationContext<Application>()
    val vm = RoadAwarenessViewModel(app)
    vm.setDebugNetworkSimulation(blockOverpass = true, blockTiles = true)
    vm.setMockModeEnabled(true)
    vm.onMapTappedInInspector(testPt)
    vm.ingestLiveMapVectorFeatures(
      features =
        listOf(
          LiveMapVectorFeature("transportation", mapOf("class" to "motorway"), listOf(motorwayPts)),
          LiveMapVectorFeature("transportation", mapOf("class" to "path", "subclass" to "footway"), listOf(footwayPts)),
          LiveMapVectorFeature("transportation_name", mapOf("class" to "motorway", "name:en" to "Ring Motorway"), listOf(motorwayPts)),
          LiveMapVectorFeature("transportation_name", mapOf("class" to "path", "name:en" to "Park Walk"), listOf(footwayPts)),
        ),
      queriedCount = 4,
    )
    var deadline = System.currentTimeMillis() + 2_000L
    while (vm.currentGraphSnapshot().graph.ways.isEmpty() && System.currentTimeMillis() < deadline) {
      delay(25L)
    }
    val snapBefore = vm.currentGraphSnapshot()
    assertTrue(snapBefore.graph.ways.size >= 2)

    vm.setTravelMode(TravelMode.WALKING)
    delay(200L)
    val snapAfterWalk = vm.currentGraphSnapshot()
    assertSame("Switching Driving -> Walking must return exact same RoadGraph object", snapBefore.graph, snapAfterWalk.graph)
    assertEquals(snapBefore.version, snapAfterWalk.version)

    vm.setTravelMode(TravelMode.DRIVING)
    delay(200L)
    val snapAfterDrive = vm.currentGraphSnapshot()
    assertSame("Switching Walking -> Driving must return exact same RoadGraph object", snapBefore.graph, snapAfterDrive.graph)
    assertEquals(snapBefore.version, snapAfterDrive.version)
  }

  // Minimal MVT protobuf v2 encoder for testing z14 `transportation` + `transportation_name` layers
  private fun buildTestMvtTile(
    layerTransTags: Map<String, String>,
    transCoords: List<Pair<Int, Int>>,
    layerNameTags: Map<String, String>,
    nameCoords: List<Pair<Int, Int>>,
  ): ByteArray {
    val tileOut = ByteArrayOutputStream()
    writeLenField(tileOut, 3, buildMvtLayer("transportation", 4096, 2, layerTransTags, transCoords))
    writeLenField(tileOut, 3, buildMvtLayer("transportation_name", 4096, 2, layerNameTags, nameCoords))
    return tileOut.toByteArray()
  }

  private fun buildMvtLayer(
    name: String,
    extent: Int,
    geomType: Int,
    tagsMap: Map<String, String>,
    coords: List<Pair<Int, Int>>,
  ): ByteArray {
    val keys = tagsMap.keys.toList()
    val vals = tagsMap.values.toList()

    val featOut = ByteArrayOutputStream()
    writeVarintField(featOut, 1, 1L) // id
    val tagInts = ArrayList<Int>(keys.size * 2)
    keys.forEachIndexed { idx, _ ->
      tagInts += idx
      tagInts += idx
    }
    writePackedVarints(featOut, 2, tagInts)
    writeVarintField(featOut, 3, geomType.toLong())

    // Encode LINESTRING MVT geometry commands
    val geomInts = ArrayList<Int>()
    var cx = 0
    var cy = 0
    coords.forEachIndexed { i, (x, y) ->
      if (i == 0) {
        geomInts += (1 or (1 shl 3)) // MoveTo(count=1)
      } else if (i == 1) {
        geomInts += (2 or ((coords.size - 1) shl 3)) // LineTo(count=n-1)
      }
      val dx = x - cx
      val dy = y - cy
      cx = x
      cy = y
      geomInts += ((dx shl 1) xor (dx shr 31))
      geomInts += ((dy shl 1) xor (dy shr 31))
    }
    writePackedVarints(featOut, 4, geomInts)

    val layerOut = ByteArrayOutputStream()
    writeStringField(layerOut, 1, name)
    writeLenField(layerOut, 2, featOut.toByteArray())
    keys.forEach { writeStringField(layerOut, 3, it) }
    vals.forEach { v ->
      val vOut = ByteArrayOutputStream()
      writeStringField(vOut, 1, v)
      writeLenField(layerOut, 4, vOut.toByteArray())
    }
    writeVarintField(layerOut, 5, extent.toLong())
    return layerOut.toByteArray()
  }

  private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
    var v = value
    while (true) {
      if ((v and 0x7FL.inv()) == 0L) {
        out.write(v.toInt())
        return
      }
      out.write(((v.toInt() and 0x7F) or 0x80))
      v = v ushr 7
    }
  }

  private fun writeVarintField(out: ByteArrayOutputStream, field: Int, value: Long) {
    writeVarint(out, ((field shl 3) or 0).toLong())
    writeVarint(out, value)
  }

  private fun writeLenField(out: ByteArrayOutputStream, field: Int, bytes: ByteArray) {
    writeVarint(out, ((field shl 3) or 2).toLong())
    writeVarint(out, bytes.size.toLong())
    out.write(bytes)
  }

  private fun writeStringField(out: ByteArrayOutputStream, field: Int, str: String) {
    writeLenField(out, field, str.toByteArray(Charsets.UTF_8))
  }

  private fun writePackedVarints(out: ByteArrayOutputStream, field: Int, ints: List<Int>) {
    val p = ByteArrayOutputStream()
    ints.forEach { writeVarint(p, it.toLong() and 0xFFFFFFFFL) }
    writeLenField(out, field, p.toByteArray())
  }
}
