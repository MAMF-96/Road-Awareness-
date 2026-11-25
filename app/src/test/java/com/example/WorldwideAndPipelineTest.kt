package com.example

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.config.AppConfig
import com.example.data.local.CorridorCacheDao
import com.example.data.local.CorridorCacheEntity
import com.example.data.local.SettingsRepository
import com.example.data.local.TileCacheDao
import com.example.data.local.TileCacheEntity
import com.example.data.local.UserSettings
import com.example.data.model.BilingualName
import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.LiveMapVectorFeature
import com.example.data.model.LocalPoint
import com.example.data.model.OsmNode
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.OverpassRequestLog
import com.example.data.model.PipelineDiagnostics
import com.example.data.model.PipelineStageLine
import com.example.data.model.RoadBranch
import com.example.data.model.RoadFeature
import com.example.data.model.RoadGraph
import com.example.data.model.TestSpot
import com.example.data.model.TravelMode
import com.example.data.model.UnitPreference
import com.example.data.model.UnitSystem
import com.example.data.remote.GeoBbox
import com.example.data.remote.MvtDecoder
import com.example.data.remote.OverpassMapDataSource
import com.example.data.remote.TileRoadGraphSource
import com.example.domain.GeoMath
import com.example.domain.MapMatcher
import com.example.location.LocationTracker
import com.example.ui.InspectorSheetDetent
import com.example.ui.RoadAwarenessViewModel
import com.example.ui.RoadHudUiState
import com.example.ui.components.buildInspectorPlainText
import com.example.ui.screens.RoadAwarenessScreenContent
import com.example.ui.theme.RoadAwarenessTheme
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertNull
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
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
class WorldwideAndPipelineTest {

  @get:Rule val composeRule = createComposeRule()

  private class FakeCorridorCacheDao : CorridorCacheDao {
    private val map = ConcurrentHashMap<String, CorridorCacheEntity>()

    override suspend fun getCell(cellKey: String): CorridorCacheEntity? = map[cellKey]

    override suspend fun getCells(cellKeys: List<String>): List<CorridorCacheEntity> =
      cellKeys.mapNotNull { map[it] }

    override suspend fun getNearestCell(mode: String, lat: Double, lon: Double): CorridorCacheEntity? =
      map.values.filter { it.mode == mode }.minByOrNull {
        val dLat = it.centerLat - lat
        val dLon = GeoMath.unwrapLonDelta(it.centerLon - lon)
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

  private class FakeTileCacheDao : TileCacheDao {
    private val map = ConcurrentHashMap<String, TileCacheEntity>()

    override suspend fun getTile(tileKey: String): TileCacheEntity? = map[tileKey]

    override suspend fun getTiles(tileKeys: List<String>): List<TileCacheEntity> =
      tileKeys.mapNotNull { map[it] }

    override suspend fun upsertTiles(entities: List<TileCacheEntity>) {
      entities.forEach { map[it.tileKey] = it }
    }

    override suspend fun countTiles(): Int = map.size

    override suspend fun getOldestFetchedAtMs(): Long? = map.values.minOfOrNull { it.fetchedAtMs }

    override suspend fun clearAll() {
      map.clear()
    }
  }

  @Test
  @Config(sdk = [36])
  fun config_excludesMailRuAndIncludesThreeMirrorsAndNineSeedSpots() {
    assertFalse(
      "maps.mail.ru must be removed due to untrusted certificate",
      AppConfig.OVERPASS_ENDPOINTS.any { it.contains("maps.mail.ru", ignoreCase = true) },
    )
    assertEquals(3, AppConfig.OVERPASS_ENDPOINTS.size)
    assertTrue(AppConfig.OVERPASS_ENDPOINTS.any { it.contains("overpass-api.de") })
    assertTrue(AppConfig.OVERPASS_ENDPOINTS.any { it.contains("overpass.private.coffee") })
    assertTrue(AppConfig.OVERPASS_ENDPOINTS.any { it.contains("overpass.kumi.systems") })
    assertEquals(9, AppConfig.WORLDWIDE_SEED_TEST_SPOTS.size)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun liveGpsWithoutFix_showsWaitingForGpsAndNeverFallsBackToCairo() {
    val waitingState =
      RoadHudUiState(
        hasLocationPermission = true,
        isMockModeActive = false,
        isGpsSearching = true,
        location = null,
      )

    composeRule.setContent {
      RoadAwarenessTheme {
        RoadAwarenessScreenContent(
          uiState = waitingState,
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

    composeRule.onNodeWithTag("header_waiting_gps").assertIsDisplayed()
    composeRule.onNodeWithText("Waiting for GPS…").assertIsDisplayed()
    composeRule.onNodeWithText("Cairo", substring = true).assertDoesNotExist()
  }

  @Test
  @Config(sdk = [36])
  fun antimeridian_mathSplitsBboxAndWrapsTilesCleanly() {
    assertEquals(-179.5, GeoMath.normalizeLon(180.5), 1e-6)
    assertEquals(179.5, GeoMath.normalizeLon(-180.5), 1e-6)
    assertEquals(0.2, GeoMath.unwrapLonDelta(-179.9 - 179.9), 1e-6)

    val boxes = GeoMath.splitBboxAtAntimeridian(-18.0, 179.9, -17.9, -179.9)
    assertEquals(2, boxes.size)
    assertEquals(179.9, boxes[0].west, 1e-6)
    assertEquals(180.0, boxes[0].east, 1e-6)
    assertEquals(-180.0, boxes[1].west, 1e-6)
    assertEquals(-179.9, boxes[1].east, 1e-6)

    val tiles = TileRoadGraphSource.tilesCoveringBbox(GeoBbox(-18.0, 179.9, -17.9, -179.9), 14)
    val maxTileX = (1 shl 14) - 1
    assertTrue("Expected easternmost tile x=$maxTileX", tiles.any { it.x == maxTileX })
    assertTrue("Expected westernmost wrapped tile x=0", tiles.any { it.x == 0 })
  }

  @Test
  @Config(sdk = [36])
  fun worldwideRegions_matchingAndMultilingualScriptsWorkAcrossEightCities() {
    data class RegionCase(
      val city: String,
      val pt: GeoPoint,
      val tags: Map<String, String>,
      val locale: Locale,
      val expectedPrimary: String,
      val expectedRtl: Boolean,
      val expectedScript: String,
    )

    val cases =
      listOf(
        RegionCase(
          city = "London",
          pt = GeoPoint(51.5027, -0.1527),
          tags = mapOf("highway" to "primary", "name" to "Baker Street", "name:en" to "Baker Street"),
          locale = Locale.UK,
          expectedPrimary = "Baker Street",
          expectedRtl = false,
          expectedScript = "Latn",
        ),
        RegionCase(
          city = "New York",
          pt = GeoPoint(40.7580, -73.9855),
          tags = mapOf("highway" to "primary", "name" to "Broadway", "name:en" to "Broadway"),
          locale = Locale.US,
          expectedPrimary = "Broadway",
          expectedRtl = false,
          expectedScript = "Latn",
        ),
        RegionCase(
          city = "Tokyo",
          pt = GeoPoint(35.6812, 139.7671),
          tags = mapOf("highway" to "primary", "name" to "中央通り", "name:en" to "Chuo-dori", "name:ja" to "中央通り"),
          locale = Locale.JAPAN,
          expectedPrimary = "中央通り",
          expectedRtl = false,
          expectedScript = "Jpan",
        ),
        RegionCase(
          city = "Paris",
          pt = GeoPoint(48.8738, 2.2950),
          tags = mapOf("highway" to "primary", "name" to "Avenue des Champs-Élysées", "name:fr" to "Avenue des Champs-Élysées"),
          locale = Locale.FRANCE,
          expectedPrimary = "Avenue des Champs-Élysées",
          expectedRtl = false,
          expectedScript = "Latn",
        ),
        RegionCase(
          city = "Sydney",
          pt = GeoPoint(-33.8523, 151.2108),
          tags = mapOf("highway" to "secondary", "name" to "George Street"),
          locale = Locale("en", "AU"),
          expectedPrimary = "George Street",
          expectedRtl = false,
          expectedScript = "Latn",
        ),
        RegionCase(
          city = "São Paulo",
          pt = GeoPoint(-23.5614, -46.6559),
          tags = mapOf("highway" to "primary", "name" to "Avenida Paulista", "name:pt" to "Avenida Paulista"),
          locale = Locale("pt", "BR"),
          expectedPrimary = "Avenida Paulista",
          expectedRtl = false,
          expectedScript = "Latn",
        ),
        RegionCase(
          city = "Mumbai",
          pt = GeoPoint(19.0760, 72.8777),
          tags = mapOf("highway" to "primary", "name" to "नेहरू मार्ग", "name:hi" to "नेहरू मार्ग", "name:en" to "Nehru Marg"),
          locale = Locale("hi", "IN"),
          expectedPrimary = "नेहरू मार्ग",
          expectedRtl = false,
          expectedScript = "Deva",
        ),
        RegionCase(
          city = "Dubai",
          pt = GeoPoint(25.1972, 55.2744),
          tags = mapOf("highway" to "motorway", "name:ar" to "شارع الشيخ زايد", "name:en" to "Sheikh Zayed Rd", "ref" to "E11"),
          locale = Locale("ar", "AE"),
          expectedPrimary = "شارع الشيخ زايد",
          expectedRtl = true,
          expectedScript = "Arab",
        ),
      )

    cases.forEachIndexed { idx, rc ->
      assertEquals(rc.expectedScript, GeoMath.detectLocaleScript(rc.locale))
      val osmTags = OsmTags.fromMap(rc.tags, rc.locale.language)
      val bilingual = BilingualName.fromOsmTags(osmTags, rc.locale.language)
      assertEquals("Primary text mismatch for ${rc.city}", rc.expectedPrimary, bilingual.primaryText)
      assertEquals("RTL check mismatch for ${rc.city}", rc.expectedRtl, GeoMath.isRtlScript(bilingual.primaryText))

      val p0 = GeoMath.offsetPoint(rc.pt, 180.0, 80.0)
      val p1 = GeoMath.offsetPoint(rc.pt, 0.0, 120.0)
      val n1 = OsmNode(idx * 10L + 1L, p0)
      val n2 = OsmNode(idx * 10L + 2L, p1)
      val way = OsmWay(idx * 100L + 1L, listOf(n1.id, n2.id), listOf(p0, p1), osmTags)
      val graph =
        RoadGraph(
          nodes = mapOf(n1.id to n1, n2.id to n2),
          ways = mapOf(way.id to way),
          nodeToWays = mapOf(n1.id to listOf(way.id), n2.id to listOf(way.id)),
          fetchedAtMs = 1_000L,
          fromCache = false,
        )
      val match = MapMatcher.match(graph, rc.pt, headingDeg = 0.0, TravelMode.DRIVING, null)
      assertNotNull("Expected match in ${rc.city}", match.primary)
      assertEquals(way.id, match.primary!!.way.id)
    }
  }

  @Test
  @Config(sdk = [36])
  fun unitSystem_resolvesMetricAndImperialFormattingAccurately() {
    assertEquals(UnitSystem.IMPERIAL, GeoMath.resolveEffectiveUnitSystem(UnitPreference.AUTO, Locale.US))
    assertEquals(UnitSystem.IMPERIAL, GeoMath.resolveEffectiveUnitSystem(UnitPreference.AUTO, Locale.UK))
    assertEquals(UnitSystem.METRIC, GeoMath.resolveEffectiveUnitSystem(UnitPreference.AUTO, Locale.JAPAN))
    assertEquals(UnitSystem.METRIC, GeoMath.resolveEffectiveUnitSystem(UnitPreference.METRIC, Locale.US))
    assertEquals(UnitSystem.IMPERIAL, GeoMath.resolveEffectiveUnitSystem(UnitPreference.IMPERIAL, Locale.FRANCE))

    assertEquals("330ft", GeoMath.formatDistance(100.0, UnitSystem.IMPERIAL, "%dm", "%.1f km", "%dft", "%.1f mi"))
    assertEquals("1.0 mi", GeoMath.formatDistance(1609.344, UnitSystem.IMPERIAL, "%dm", "%.1f km", "%dft", "%.1f mi"))
    assertEquals("100m", GeoMath.formatDistance(100.0, UnitSystem.METRIC, "%dm", "%.1f km", "%dft", "%.1f mi"))
    assertEquals("1.5 km", GeoMath.formatDistance(1500.0, UnitSystem.METRIC, "%dm", "%.1f km", "%dft", "%.1f mi"))

    assertEquals(62 to "mph", GeoMath.formatSpeedValueAndUnit(100.0, UnitSystem.IMPERIAL))
    assertEquals(100 to "km/h", GeoMath.formatSpeedValueAndUnit(100.0, UnitSystem.METRIC))
  }

  @Test
  @Config(sdk = [36])
  fun mvtDecoder_scalesExtent8192AndReportsCorruptTileDecodeErrors() {
    val target = GeoPoint(51.5027, -0.1527)
    val (tx, ty) = TileRoadGraphSource.latLonToTile(target.lat, target.lon, 14)
    val tileBytes =
      buildSyntheticMvtTileBytes(
        layerName = "transportation",
        extent = 8192,
        key = "class",
        value = "primary",
        geomCommands =
          listOf(
            commandInt(1, 1), zigzag32(4096), zigzag32(2048), // MoveTo centerX, upper half
            commandInt(2, 1), zigzag32(0), zigzag32(4096),    // LineTo +0, +4096
          ),
      )

    val entity =
      TileCacheEntity(
        tileKey = "14_${tx}_${ty}",
        z = 14,
        x = tx,
        y = ty,
        rawBytes = tileBytes,
        fetchedAtMs = 5_000L,
      )
    val okOut = TileRoadGraphSource.buildRoadGraphWithStats(listOf(entity), TravelMode.DRIVING, fromCache = false)
    assertTrue("Expected decodeInfo.ok=true", okOut.decodeInfo.ok)
    assertEquals(8192, okOut.decodeInfo.extent)
    assertTrue("Expected ways decoded from extent 8192 layer", okOut.graph.ways.isNotEmpty())

    val expectedStart = MvtDecoder.tilePxToGeoPoint(14, tx, ty, 4096, 2048, 8192)
    val expectedEnd = MvtDecoder.tilePxToGeoPoint(14, tx, ty, 4096, 6144, 8192)
    val wayPts = okOut.graph.ways.values.first().points
    assertEquals(expectedStart.lat, wayPts.first().lat, 1e-5)
    assertEquals(expectedStart.lon, wayPts.first().lon, 1e-5)
    assertEquals(expectedEnd.lat, wayPts.last().lat, 1e-5)
    assertEquals(expectedEnd.lon, wayPts.last().lon, 1e-5)

    // Corrupt GZIP-prefixed bytes must trigger a real decode failure recorded in MvtDecodeStageInfo
    val corruptEntity =
      TileCacheEntity(
        tileKey = "14_${tx}_${ty}",
        z = 14,
        x = tx,
        y = ty,
        rawBytes = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00, 0x7f, 0x7f, 0x7f),
        fetchedAtMs = 5_000L,
      )
    val failOut = TileRoadGraphSource.buildRoadGraphWithStats(listOf(corruptEntity), TravelMode.DRIVING, fromCache = false)
    assertFalse("Expected corrupt bytes to fail decoding", failOut.decodeInfo.ok)
    assertNotNull("Expected non-null errorReason on decode error", failOut.decodeInfo.errorReason)
  }

  @Test
  @Config(sdk = [36])
  fun tileSource_supportsTmsFlipAndRefreshesTileJsonOn404And6hTtl() = runBlocking {
    var fakeNow = 10_000_000L
    val dao = FakeTileCacheDao()
    val tmpDir = Files.createTempDirectory("tilejson_test").toFile().apply { deleteOnExit() }
    val requestedTileUrls = CopyOnWriteArrayList<String>()
    val styleFetchCount = AtomicInteger(0)

    val tokyo = GeoPoint(35.6812, 139.7671)
    val (cx, cy) = TileRoadGraphSource.latLonToTile(tokyo.lat, tokyo.lon, 14)
    val expectedTmsY = (1 shl 14) - 1 - cy
    val samplePbf =
      buildSyntheticMvtTileBytes(
        layerName = "transportation",
        extent = 4096,
        key = "class",
        value = "primary",
        geomCommands =
          listOf(
            commandInt(1, 1), zigzag32(2048), zigzag32(1024),
            commandInt(2, 1), zigzag32(0), zigzag32(2048),
          ),
      )

    val client =
      OkHttpClient.Builder()
        .addInterceptor(
          Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            when {
              url == "https://style.test/liberty" -> {
                styleFetchCount.incrementAndGet()
                val json =
                  """
                  {
                    "sources": {
                      "openmaptiles": {
                        "type": "vector",
                        "url": "https://tiles.test/tilejson"
                      }
                    }
                  }
                  """.trimIndent()
                jsonResponse(req, 200, json)
              }
              url == "https://tiles.test/tilejson" -> {
                val ver = if (styleFetchCount.get() >= 2) "v2" else "v1"
                val json =
                  """
                  {
                    "scheme": "tms",
                    "maxzoom": 14,
                    "tiles": ["https://tiles.test/$ver/{z}/{x}/{y}.pbf"],
                    "vector_layers": [{"id": "transportation"}, {"id": "transportation_name"}]
                  }
                  """.trimIndent()
                jsonResponse(req, 200, json)
              }
              url.startsWith("https://tiles.test/v1/") -> {
                requestedTileUrls += url
                // Force 404 on v1 so TileRoadGraphSource invalidates & refreshes TileJSON -> v2
                jsonResponse(req, 404, "Not Found")
              }
              url.startsWith("https://tiles.test/v2/") -> {
                requestedTileUrls += url
                Response.Builder()
                  .request(req)
                  .protocol(Protocol.HTTP_1_1)
                  .code(200)
                  .message("OK")
                  .body(samplePbf.toResponseBody("application/x-protobuf".toMediaType()))
                  .build()
              }
              else -> jsonResponse(req, 404, "Unknown")
            }
          }
        )
        .build()

    val src =
      TileRoadGraphSource(
        tileCacheDao = dao,
        styleCacheDir = tmpDir,
        client = client,
        styleUrl = "https://style.test/liberty",
        clockMs = { fakeNow },
      )

    val graphRes = src.fetchCorridorGraph(tokyo, headingDeg = 0.0, mode = TravelMode.DRIVING, radiusMeters = 300.0)
    assertTrue("Expected tile graph to succeed after 404 TileJSON refresh", graphRes.isSuccess)
    assertTrue("Expected >= 2 style fetches due to 404 refresh", styleFetchCount.get() >= 2)
    assertTrue(
      "Expected TMS flipped Y=$expectedTmsY in tile request URLs: $requestedTileUrls",
      requestedTileUrls.any { it.contains("/14/$cx/$expectedTmsY.pbf") },
    )
    assertTrue(src.tileUrlInfo.value.ok)
    assertEquals("tms", src.tileUrlInfo.value.scheme)

    // Advance clock past 6h TTL and verify forced refresh re-fetches style/TileJSON
    val beforeTtlFetches = styleFetchCount.get()
    fakeNow += AppConfig.TILEJSON_TTL_MS + 10_000L
    src.fetchCorridorGraph(tokyo, headingDeg = 0.0, mode = TravelMode.DRIVING, radiusMeters = 300.0, forceRefresh = true)
    assertTrue("Expected style/TileJSON re-fetch after 6h TTL", styleFetchCount.get() > beforeTtlFetches)
  }

  @Test
  @Config(sdk = [36])
  fun overpass_halvesBboxOnTimeoutExecutesDrivingServicePass2AndTracksStats() = runBlocking {
    var fakeNow = 20_000_000L
    val dao = FakeCorridorCacheDao()
    val capturedBboxes = CopyOnWriteArrayList<Pair<Double, Double>>()
    val capturedQueries = CopyOnWriteArrayList<String>()
    var ageObservedDuringCall: Long? = null
    lateinit var ds: OverpassMapDataSource

    val pass1Json =
      """
      {
        "elements": [
          {
            "type": "way",
            "id": 7001,
            "tags": {"highway": "primary", "name": "Broadway"},
            "geometry": [
              {"lat": 40.7575, "lon": -73.9855},
              {"lat": 40.7585, "lon": -73.9855}
            ]
          }
        ]
      }
      """.trimIndent()
    val pass2Json =
      """
      {
        "elements": [
          {
            "type": "way",
            "id": 7002,
            "tags": {"highway": "service", "name": "Alley Service"},
            "geometry": [
              {"lat": 40.7580, "lon": -73.9855},
              {"lat": 40.7580, "lon": -73.9850}
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
            val query = URLDecoder.decode(buf.readUtf8(), "UTF-8")
            capturedQueries += query

            val match = Regex("\\(([\\-0-9.]+),([\\-0-9.]+),([\\-0-9.]+),([\\-0-9.]+)\\)").find(query)
            if (match != null) {
              val s = match.groupValues[1].toDouble()
              val w = match.groupValues[2].toDouble()
              val n = match.groupValues[3].toDouble()
              val e = match.groupValues[4].toDouble()
              capturedBboxes += (n - s) to (e - w)
            }

            if (req.url.host == "slow.test") {
              fakeNow += 2_500L
              ageObservedDuringCall = ds.currentActiveRequestAgeMs()
              throw SocketTimeoutException("Read timed out")
            }

            val body =
              if (query.contains("highway\"=\"service\"")) pass2Json else pass1Json
            jsonResponse(req, 200, body)
          }
        )
        .build()

    ds =
      OverpassMapDataSource(
        cacheDao = dao,
        client = client,
        endpoints = listOf("https://slow.test/api/interpreter", "https://fast.test/api/interpreter"),
        clockMs = { fakeNow },
        hedgeDelayMs = 10_000L,
      )

    val nyc = GeoPoint(40.7580, -73.9855)
    val res = ds.fetchCorridorGraph(nyc, headingDeg = 0.0, mode = TravelMode.DRIVING, radiusMeters = 300.0)
    assertTrue("Expected retry on fast.test after slow.test timeout to succeed", res.isSuccess)
    val graph = res.getOrThrow()
    assertTrue("Pass 1 road missing", graph.ways.containsKey(7001L))
    assertTrue("Pass 2 driving service road missing", graph.ways.containsKey(7002L))
    assertNotNull("Expected active request age to be tracked while HTTP call running", ageObservedDuringCall)
    assertTrue("Expected age >= 2000ms, got $ageObservedDuringCall", ageObservedDuringCall!! >= 2_000L)

    assertTrue("Expected at least 2 bboxes captured (initial + halved)", capturedBboxes.size >= 2)
    val firstLatSpan = capturedBboxes[0].first
    val halvedLatSpan = capturedBboxes[1].first
    assertTrue(
      "Expected bbox lat span to be halved after timeout ($halvedLatSpan vs $firstLatSpan)",
      halvedLatSpan < firstLatSpan * 0.65,
    )
    assertTrue(capturedQueries.any { it.contains("highway\"=\"service\"") })

    val stats = ds.loaderStats60s()
    assertTrue(stats.started60s >= 1)
    assertTrue(stats.completed60s >= 1)
  }

  @Test
  @Config(sdk = [36])
  fun liveMapFallbackAndInspectorReportContractAreValid() {
    val liveFeatures =
      listOf(
        LiveMapVectorFeature(
          layerName = "transportation_name",
          tags = mapOf("class" to "primary", "name" to "Avenida Paulista"),
          geometries = listOf(listOf(GeoPoint(-23.5614, -46.6559))),
        ),
        LiveMapVectorFeature(
          layerName = "transportation",
          tags = mapOf("class" to "primary", "oneway" to "yes"),
          geometries =
            listOf(
              listOf(
                GeoPoint(-23.5620, -46.6559),
                GeoPoint(-23.5610, -46.6559),
              )
            ),
        ),
      )

    val mapGraph = TileRoadGraphSource.buildRoadGraphFromLiveFeatures(liveFeatures, TravelMode.DRIVING)
    assertEquals(GraphDataSource.MAP, mapGraph.source)
    assertEquals(1, mapGraph.ways.size)
    val way = mapGraph.ways.values.first()
    assertEquals(GraphDataSource.MAP, way.source)
    assertEquals("Avenida Paulista", way.tags.name)

    val feat =
      RoadFeature(
        index = 1,
        type = FeatureType.FOUR_WAY,
        distanceMeters = 120.0,
        anchor = GeoPoint(-23.5610, -46.6559),
        anchorNodeId = 1L,
        incomingLocalPoints = listOf(LocalPoint(0.0, 0.0), LocalPoint(0.0, 120.0)),
        incomingWorldPoints = listOf(GeoPoint(-23.5620, -46.6559), GeoPoint(-23.5610, -46.6559)),
        outgoingMainLocalPoints = listOf(LocalPoint(0.0, 120.0), LocalPoint(0.0, 220.0)),
        outgoingMainWorldPoints = listOf(GeoPoint(-23.5610, -46.6559), GeoPoint(-23.5600, -46.6559)),
        branches =
          listOf(
            RoadBranch(
              badgeId = "B1",
              wayId = 99L,
              name = BilingualName(null, "Rua Bela Cintra", null),
              leadsTo = null,
              distanceMeters = 120.0,
              status = com.example.data.model.BranchStatus.OPEN,
              relativeDirection = com.example.data.model.RelativeDirection.LEFT,
              bearingDeltaDeg = -90.0,
              worldPoints = listOf(GeoPoint(-23.5610, -46.6559), GeoPoint(-23.5610, -46.6569)),
              localPoints = listOf(LocalPoint(0.0, 120.0), LocalPoint(-80.0, 120.0)),
              osmTags = OsmTags(highway = "secondary", name = "Rua Bela Cintra"),
            )
          ),
        osmTags = way.tags,
        source = GraphDataSource.MAP,
      )

    val uiState =
      RoadHudUiState(
        roadNameSource = "map",
        features = listOf(feat),
        pipelineDiagnostics =
          PipelineDiagnostics(
            stages =
              ('a'..'h').map { ch ->
                PipelineStageLine(stageId = ch.toString(), label = "Stage $ch", ok = true, summary = "ok", elapsedMs = 10L)
              }
          ),
        recentOverpassLogs =
          listOf(
            OverpassRequestLog(
              endpoint = "https://overpass-api.de/api/interpreter",
              durationMs = 250L,
              cellsFetched = 1,
              cellsCached = 0,
              succeeded = true,
            )
          ),
      )

    val report = buildInspectorPlainText(uiState)
    val lines = report.lines()
    assertTrue("Inspector report must be <= 60 lines (was ${lines.size})", lines.size <= 60)
    listOf(
      "[POSITION]",
      "[MODE]",
      "[ROAD]",
      "[SOURCES]",
      "[PIPELINE]",
      "[OVERRIDES]",
      "[LAST_3_REQUESTS]",
      "[FEATURES]",
    ).forEach { section ->
      assertTrue("Missing $section in report", report.contains(section))
    }
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun mockNoSavedPosition_showsOneLinePromptAndOpensDetailsSpotsWithoutCairoOrOrigin() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val defaults = UserSettings()
    assertNull("Fresh UserSettings should have null lastMockLat", defaults.lastMockLat)
    assertNull("Fresh UserSettings should have null lastMockLon", defaults.lastMockLon)

    val tracker = LocationTracker(context)
    tracker.restoreMockState(
      point = null,
      headingDeg = 0.0,
      speedKmh = 30.0,
      accuracyMeters = 3.0f,
      jitterEnabled = false,
      enabled = true,
    )
    assertNull("LocationTracker mockPoint must remain null until set", tracker.getBaseMockPoint())
    assertNull("LocationTracker locationFlow must remain null without saved mock pos", tracker.locationFlow.value)

    var openedSpots = false
    val state =
      RoadHudUiState(
        isMockModeActive = true,
        location = null,
        hasConfirmedGraphData = false,
        isLoadingGraph = false,
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
          onOpenInspectorSpots = { openedSpots = true },
          mapContent = { mod, _, _, _, _, _ -> Box(modifier = mod) },
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("header_mock_no_position_prompt").assertIsDisplayed()
    composeRule.onNodeWithText("Tap the map or choose a test spot").assertIsDisplayed()
    composeRule.onNodeWithTag("header_mock_no_position_prompt").performClick()
    composeRule.waitForIdle()
    assertTrue("Expected tapping no-position prompt to open Details test spots", openedSpots)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun detailsSheet_showsPipelineSummaryAndRestoreDefaultSpotsWhenEmpty() {
    var restoredClicked = false
    val stages =
      listOf(
        PipelineStageLine("a", "Tile URL", true, "ok"),
        PipelineStageLine("b", "Tile fetch", true, "ok"),
        PipelineStageLine("c", "MVT decode", false, "err"),
        PipelineStageLine("d", "Graph build", true, "ok"),
        PipelineStageLine("e", "Overpass", false, "timeout"),
        PipelineStageLine("f", "Map matching", true, "ok"),
        PipelineStageLine("g", "Loader 60s", true, "ok"),
        PipelineStageLine("h", "Region", true, "ok"),
      )
    val state =
      RoadHudUiState(
        isMockModeActive = true,
        location = null,
        isInspectorOpen = true,
        inspectorDetent = InspectorSheetDetent.FULL,
        savedTestSpots = emptyList(),
        pipelineDiagnostics = PipelineDiagnostics(stages = stages),
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
          onRestoreDefaultSpots = { restoredClicked = true },
          mapContent = { mod, _, _, _, _, _ -> Box(modifier = mod) },
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithTag("inspector_pipeline_summary").assertIsDisplayed()
    composeRule.onNodeWithText("Pipeline summary: a✓ b✓ c✗ d✓ e✗ f✓ g✓ h✓", substring = true).assertIsDisplayed()
    composeRule.onNodeWithText("col=0 dang=0", substring = true).assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_restore_default_spots_button").performScrollTo()
    composeRule.onNodeWithTag("inspector_restore_default_spots_button").assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_restore_default_spots_button").performClick()
    composeRule.waitForIdle()
    assertTrue("Expected tapping Restore default spots to invoke callback", restoredClicked)
  }

  @Test
  @Config(sdk = [36])
  fun viewModel_restoreDefaultSpotsPreservesUserSpotsAndLoadSpotRunsPipeline() = runBlocking {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val vm = RoadAwarenessViewModel(app)
    val customSpot =
      TestSpot(
        id = "user_custom_1",
        name = "Custom Garage",
        lat = 48.8566,
        lon = 2.3522,
        headingDeg = 90.0,
        travelMode = TravelMode.DRIVING,
        createdAtMs = 123L,
      )
    vm.saveTestSpot(
      name = customSpot.name,
      point = GeoPoint(customSpot.lat, customSpot.lon),
      headingDeg = customSpot.headingDeg,
      mode = customSpot.travelMode,
    )
    vm.uiState.value.savedTestSpots.filter { it.id.startsWith("seed_") }.forEach {
      vm.deleteTestSpot(it.id)
    }
    assertEquals(1, vm.uiState.value.savedTestSpots.size)

    vm.restoreDefaultTestSpots()
    val afterRestore = vm.uiState.value.savedTestSpots
    assertEquals(10, afterRestore.size)
    assertTrue("User spot must be preserved", afterRestore.any { it.name == "Custom Garage" })
    assertEquals(9, afterRestore.count { it.id.startsWith("seed_") })

    val londonSeed = afterRestore.first { it.id.startsWith("seed_") }
    vm.loadTestSpot(londonSeed)
    val updatedLoc = vm.uiState.value.location
    assertNotNull("Picking a spot must set location", updatedLoc)
    assertEquals(londonSeed.lat, updatedLoc!!.point.lat, 1e-6)
    assertEquals(londonSeed.lon, updatedLoc.point.lon, 1e-6)
    assertEquals(londonSeed.headingDeg, updatedLoc.headingDeg, 1e-3)
    assertTrue("Picking a spot must trigger corridor pipeline fetch", vm.uiState.value.isLoadingGraph)
  }

  private fun jsonResponse(req: okhttp3.Request, code: Int, body: String): Response =
    Response.Builder()
      .request(req)
      .protocol(Protocol.HTTP_1_1)
      .code(code)
      .message(if (code == 200) "OK" else "ERR")
      .body(body.toResponseBody("application/json".toMediaType()))
      .build()

  private fun buildSyntheticMvtTileBytes(
    layerName: String,
    extent: Int,
    key: String,
    value: String,
    geomCommands: List<Int>,
  ): ByteArray {
    // Value message: field 1 = string value
    val valueMsg =
      ByteArrayOutputStream().apply {
        writeStringField(1, value)
      }.toByteArray()

    // Feature message: field 2 = tags [0, 0], field 3 = type (LINESTRING=2), field 4 = packed geom
    val featMsg =
      ByteArrayOutputStream().apply {
        writePackedVarintsField(2, listOf(0, 0))
        writeVarintField(3, 2)
        writePackedVarintsField(4, geomCommands)
      }.toByteArray()

    // Layer message: field 15 = version(2), field 1 = name, field 2 = feature, field 3 = key, field 4 = value, field 5 = extent
    val layerMsg =
      ByteArrayOutputStream().apply {
        writeVarintField(15, 2)
        writeStringField(1, layerName)
        writeBytesField(2, featMsg)
        writeStringField(3, key)
        writeBytesField(4, valueMsg)
        writeVarintField(5, extent)
      }.toByteArray()

    // Tile message: field 3 = layer
    return ByteArrayOutputStream().apply {
      writeBytesField(3, layerMsg)
    }.toByteArray()
  }

  private fun commandInt(cmd: Int, count: Int): Int = (cmd and 0x7) or (count shl 3)

  private fun zigzag32(n: Int): Int = (n shl 1) xor (n shr 31)

  private fun ByteArrayOutputStream.writeVarintRaw(v: Int) {
    var cur = v
    while ((cur and 0x7F.inv()) != 0) {
      write((cur and 0x7F) or 0x80)
      cur = cur ushr 7
    }
    write(cur and 0x7F)
  }

  private fun ByteArrayOutputStream.writeVarintField(field: Int, v: Int) {
    writeVarintRaw((field shl 3) or 0)
    writeVarintRaw(v)
  }

  private fun ByteArrayOutputStream.writeBytesField(field: Int, bytes: ByteArray) {
    writeVarintRaw((field shl 3) or 2)
    writeVarintRaw(bytes.size)
    write(bytes)
  }

  private fun ByteArrayOutputStream.writeStringField(field: Int, s: String) {
    writeBytesField(field, s.toByteArray(Charsets.UTF_8))
  }

  private fun ByteArrayOutputStream.writePackedVarintsField(field: Int, vals: List<Int>) {
    val tmp = ByteArrayOutputStream()
    for (v in vals) tmp.writeVarintRaw(v)
    writeBytesField(field, tmp.toByteArray())
  }
}
