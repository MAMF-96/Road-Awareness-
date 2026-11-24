package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.config.AppConfig
import com.example.data.local.TileCacheEntity
import com.example.data.local.UserSettings
import com.example.data.model.AheadTimelineEntry
import com.example.data.model.BilingualName
import com.example.data.model.BranchStatus
import com.example.data.model.DistanceKind
import com.example.data.model.EntryLegalityStatus
import com.example.data.model.FeatureType
import com.example.data.model.FoundRoadRelation
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.LandscapePanelSide
import com.example.data.model.LoadedTileBounds
import com.example.data.model.LocationSnapshot
import com.example.data.model.OsmNode
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.RoadGraph
import com.example.data.model.TravelMode
import com.example.data.model.UnitPreference
import com.example.data.remote.MvtDecoder
import com.example.data.remote.OverpassMapDataSource
import com.example.data.remote.TileRoadGraphSource
import com.example.data.repository.RoadGraphRepository
import com.example.domain.AheadGraphAnalyzer
import com.example.domain.GeoMath
import com.example.domain.LocalRoadSearchIndex
import com.example.domain.MapMatcher
import com.example.ui.RoadHudUiState
import com.example.ui.components.TouchTelemetry
import com.example.ui.screens.RoadAwarenessScreenContent
import com.example.ui.theme.RoadAwarenessTheme
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MasterPassFeaturesTest {

  @get:Rule val composeRule = createComposeRule()

  @Before
  fun setUp() {
    TouchTelemetry.reset()
  }

  @Test
  @Config(sdk = [36])
  fun check1_namedAndUnnamedRoadMatching_atCairoWorksCleanly() {
    val json =
      """
      {
        "elements": [
          {"type": "node", "id": 1, "lat": 30.0440, "lon": 31.2357},
          {"type": "node", "id": 2, "lat": 30.0450, "lon": 31.2357},
          {"type": "node", "id": 3, "lat": 30.0445, "lon": 31.2362},
          {"type": "node", "id": 4, "lat": 30.0455, "lon": 31.2362},
          {"type": "way", "id": 100, "nodes": [1, 2], "tags": {"highway": "tertiary"}},
          {"type": "way", "id": 101, "nodes": [3, 4], "tags": {"highway": "primary", "name:ar": "شارع طلعت حرب", "name:en": "Talaat Harb Street"}}
        ]
      }
      """.trimIndent()
    val graph = OverpassMapDataSource.parseOverpassJson(json, 1_000L, false)

    val unnamedMatch = MapMatcher.match(graph, GeoPoint(30.0445, 31.2357), headingDeg = 0.0, TravelMode.DRIVING, null)
    assertNotNull("Expected match on unnamed tertiary Cairo road", unnamedMatch.primary)
    assertEquals(100L, unnamedMatch.primary!!.way.id)
    assertFalse(unnamedMatch.primary!!.name.hasAny)

    val namedMatch = MapMatcher.match(graph, GeoPoint(30.0450, 31.2362), headingDeg = 0.0, TravelMode.DRIVING, null)
    assertNotNull("Expected match on named Cairo street", namedMatch.primary)
    assertEquals(101L, namedMatch.primary!!.way.id)
    assertEquals("Talaat Harb Street", namedMatch.primary!!.name.english)
  }

  @Test
  @Config(sdk = [36])
  fun check2_multiTileMvtDecodeAndTimings_succeed() {
    val pos = GeoPoint(30.0444, 31.2357)
    val (tx, ty) = TileRoadGraphSource.latLonToTile(pos.lat, pos.lon, 14)
    val pbf1 = gzipBytes(
      buildMvtBytes(
        layerName = "transportation",
        extent = 4096,
        key = "class",
        value = "primary",
        cmds = listOf(cmd(1, 1), zz(2048), zz(1000), cmd(2, 1), zz(0), zz(2000)),
      ),
    )
    val pbf2 = gzipBytes(
      buildMvtBytes(
        layerName = "transportation",
        extent = 4096,
        key = "class",
        value = "secondary",
        cmds = listOf(cmd(1, 1), zz(1000), zz(2048), cmd(2, 1), zz(2000), zz(0)),
      ),
    )

    val timed = MvtDecoder.decodeWithTiming(pbf1, 14, tx, ty, setOf("transportation", "transportation_name"))
    assertTrue(timed.gunzipMs >= 0L)
    assertTrue(timed.decodeMs >= 0L)
    assertTrue(timed.tile.layers.containsKey("transportation"))

    val entities =
      listOf(
        TileCacheEntity("14_${tx}_$ty", 14, tx, ty, pbf1, 1_000L),
        TileCacheEntity("14_${tx + 1}_$ty", 14, tx + 1, ty, pbf2, 1_000L),
      )
    val stats = TileRoadGraphSource.buildRoadGraphWithStats(entities, TravelMode.DRIVING, fromCache = false)
    assertTrue(stats.decodeInfo.ok)
    assertEquals(2, stats.graph.loadedTiles.size)
    assertTrue(stats.graph.ways.size >= 2)
  }

  @Test
  @Config(sdk = [36])
  fun check3_overpassBlockedFallback_keepsTileGraphUsable() {
    val london = AppConfig.WORLDWIDE_SEED_TEST_SPOTS.first { it.name.contains("London") }
    val pt = GeoPoint(london.lat, london.lon)
    val (tx, ty) = TileRoadGraphSource.latLonToTile(pt.lat, pt.lon, 14)
    val pbf =
      buildMvtBytes(
        layerName = "transportation",
        extent = 4096,
        key = "class",
        value = "primary",
        cmds = listOf(cmd(1, 1), zz(2048), zz(500), cmd(2, 1), zz(0), zz(3000)),
      )
    val tileGraph = TileRoadGraphSource.buildRoadGraphWithStats(
      listOf(TileCacheEntity("14_${tx}_$ty", 14, tx, ty, pbf, 2_000L)),
      TravelMode.DRIVING,
      fromCache = false,
    ).graph

    val repo = RoadGraphRepository()
    val snap =
      repo.upsertTileGraph(
        tileKey = "14_${tx}_$ty",
        bounds = LoadedTileBounds(14, tx, ty, pt.lat - 0.01, pt.lat + 0.01, pt.lon - 0.01, pt.lon + 0.01),
        tileGraph = tileGraph,
        fetchedAtMs = 2_000L,
        isEmptyDecoded = false,
      )
    assertTrue(snap.graph.ways.isNotEmpty())
    val centerPt = MvtDecoder.tilePxToGeoPoint(14, tx, ty, 2048, 2000, 4096)
    val match = MapMatcher.match(snap.graph, centerPt, headingDeg = 180.0, TravelMode.DRIVING, null, spatialIndex = snap.index)
    assertNotNull("Tile graph alone must sustain road match when Overpass fails", match.primary)
  }

  @Test
  @Config(sdk = [36])
  fun check4_worldwideSpotCheck_matchesLondonCorridorWithoutCairoAssumption() {
    val londonSpot = AppConfig.WORLDWIDE_SEED_TEST_SPOTS.first { it.name.contains("London") }
    val origin = GeoPoint(londonSpot.lat, londonSpot.lon)
    val p2 = GeoMath.offsetPoint(origin, londonSpot.headingDeg, 600.0)
    val p3 = GeoMath.offsetPoint(p2, (londonSpot.headingDeg + 75.0) % 360.0, 220.0)
    val graph =
      buildGraph(
        ways =
          listOf(
            OsmWay(
              id = 5001L,
              nodeIds = listOf(1L, 2L),
              points = listOf(origin, p2),
              tags = OsmTags(highway = "primary", name = "Knightsbridge", nameEn = "Knightsbridge"),
              source = GraphDataSource.OVERPASS,
            ),
            OsmWay(
              id = 5002L,
              nodeIds = listOf(2L, 3L),
              points = listOf(p2, p3),
              tags = OsmTags(highway = "secondary", name = "Park Lane", nameEn = "Park Lane"),
              source = GraphDataSource.OVERPASS,
            ),
          ),
      )

    val match = MapMatcher.match(graph, origin, londonSpot.headingDeg, TravelMode.DRIVING, null)
    assertNotNull(match.primary)
    assertEquals("Knightsbridge", match.primary!!.name.english)
    val ahead = AheadGraphAnalyzer.analyzeAheadDetailed(graph, match.primary!!, TravelMode.DRIVING, 12.0, configuredHorizonMeters = 3_000.0)
    assertTrue(ahead.features.isNotEmpty())
    assertTrue(ahead.features.first().branches.any { it.name.english == "Park Lane" })
  }

  @Test
  @Config(sdk = [36])
  fun check5_rollingThreeKmCacheRetainsKeepWindowOffline() {
    val repo = RoadGraphRepository(maxMemoryTiles = 16, maxOverpassCells = 16)
    val start = GeoPoint(30.0444, 31.2357)
    val ahead2Km = GeoMath.offsetPoint(start, 0.0, 2_000.0)
    val farAway = GeoMath.offsetPoint(start, 0.0, 25_000.0)

    val nearWay = OsmWay(901L, listOf(1L, 2L), listOf(start, ahead2Km), OsmTags(highway = "primary", nameEn = "Corridor A"))
    val farWay = OsmWay(902L, listOf(3L, 4L), listOf(farAway, GeoMath.offsetPoint(farAway, 0.0, 400.0)), OsmTags(highway = "primary", nameEn = "Far Road"))

    val keepTile = "14_9612_6750"
    val farTile = "14_9690_6800"
    repo.upsertTileGraph(
      tileKey = keepTile,
      bounds = LoadedTileBounds(14, 9612, 6750, start.lat - 0.01, ahead2Km.lat + 0.01, start.lon - 0.01, start.lon + 0.01),
      tileGraph = buildGraph(ways = listOf(nearWay)),
      fetchedAtMs = 1_000L,
      isEmptyDecoded = false,
    )
    repo.upsertTileGraph(
      tileKey = farTile,
      bounds = LoadedTileBounds(14, 9690, 6800, farAway.lat - 0.01, farAway.lat + 0.01, farAway.lon - 0.01, farAway.lon + 0.01),
      tileGraph = buildGraph(ways = listOf(farWay)),
      fetchedAtMs = 1_000L,
      isEmptyDecoded = false,
    )

    val retained = repo.updateKeepWindow(center = ahead2Km, horizonMeters = 3_000.0, keepTileKeys = setOf(keepTile))
    assertTrue("Tile inside 3 km keep window must remain retained offline", retained.graph.ways.containsKey(901L))
    assertTrue(repo.hasTileInMemory(keepTile))
  }

  @Test
  @Config(sdk = [36])
  fun check6_horizonThreeKmAndMinorJunctionFilterOnPrimaryCorridor() {
    val n0 = GeoPoint(30.0000, 31.0000)
    val n1 = GeoMath.offsetPoint(n0, 0.0, 500.0)
    val n2 = GeoMath.offsetPoint(n0, 0.0, 1_500.0)
    val n3 = GeoMath.offsetPoint(n0, 0.0, 2_400.0)
    val n4 = GeoMath.offsetPoint(n0, 0.0, 3_200.0)
    val sideMinorEnd = GeoMath.offsetPoint(n1, 90.0, 120.0)
    val sideMajorEnd = GeoMath.offsetPoint(n2, 90.0, 200.0)
    val farMajorEnd = GeoMath.offsetPoint(n3, 270.0, 200.0)

    val graph =
      buildGraph(
        ways =
          listOf(
            OsmWay(1L, listOf(10L, 11L, 12L, 13L, 14L), listOf(n0, n1, n2, n3, n4), OsmTags(highway = "primary", nameEn = "Ring Axis")),
            OsmWay(2L, listOf(11L, 21L), listOf(n1, sideMinorEnd), OsmTags(highway = "residential")),
            OsmWay(3L, listOf(12L, 22L), listOf(n2, sideMajorEnd), OsmTags(highway = "secondary", nameEn = "Midway Blvd")),
            OsmWay(4L, listOf(13L, 23L), listOf(n3, farMajorEnd), OsmTags(highway = "primary", nameEn = "North Loop")),
          ),
      )

    val matched = MapMatcher.match(graph, GeoMath.offsetPoint(n0, 0.0, 50.0), 0.0, TravelMode.DRIVING, null).primary!!
    val hiddenMinorRes = AheadGraphAnalyzer.analyzeAheadDetailed(graph, matched, TravelMode.DRIVING, 22.0, configuredHorizonMeters = 3_000.0, showMinorJunctions = false)
    assertTrue("Expected minor residential branch to be hidden by default on primary corridor", hiddenMinorRes.hiddenMinorCount >= 1)
    assertTrue("Expected timeline entries out past 2 km", hiddenMinorRes.timelineEntries.any { it.distanceMeters >= 2_000.0 })
    assertFalse(hiddenMinorRes.features.any { f -> f.branches.any { it.wayId == 2L } })

    val shownMinorRes = AheadGraphAnalyzer.analyzeAheadDetailed(graph, matched, TravelMode.DRIVING, 22.0, configuredHorizonMeters = 3_000.0, showMinorJunctions = true)
    assertEquals(0, shownMinorRes.hiddenMinorCount)
    assertTrue(shownMinorRes.features.size > hiddenMinorRes.features.size)
    assertTrue(shownMinorRes.features.any { f -> f.branches.any { it.wayId == 2L } })
  }

  @Test
  @Config(sdk = [36], qualifiers = "w400dp-h800dp")
  fun check7_findOnMyRoad2KmAhead_highlightsCalloutOffScreenIndicatorAndOverview() {
    val p0 = GeoPoint(30.0000, 31.0000)
    val p1 = GeoMath.offsetPoint(p0, 0.0, 1_000.0)
    val p2 = GeoMath.offsetPoint(p0, 0.0, 2_000.0)
    val p3 = GeoMath.offsetPoint(p0, 0.0, 2_600.0)
    val targetEnd = GeoMath.offsetPoint(p2, 90.0, 300.0)

    val graph =
      buildGraph(
        ways =
          listOf(
            OsmWay(1L, listOf(1L, 2L, 3L, 4L), listOf(p0, p1, p2, p3), OsmTags(highway = "primary", nameEn = "Main Corridor")),
            OsmWay(2L, listOf(3L, 5L), listOf(p2, targetEnd), OsmTags(highway = "secondary", nameEn = "Nasr Street", nameAr = "شارع النصر")),
          ),
      )

    val matched = MapMatcher.match(graph, p0, 0.0, TravelMode.DRIVING, null).primary!!
    val timeline =
      listOf(
        AheadTimelineEntry(
          featureIndex = 1,
          type = FeatureType.T_JUNCTION,
          distanceMeters = 2_000.0,
          title = BilingualName("شارع النصر", "Nasr Street", null),
          branchSummary = "Nasr Street",
          status = BranchStatus.OPEN,
          anchor = p2,
          primaryWayId = 2L,
        ),
      )
    val hits =
      LocalRoadSearchIndex.searchLocal(
        query = "Nasr",
        graph = graph,
        pois = emptyList(),
        matched = matched,
        locationPoint = p0,
        headingDeg = 0.0,
        mode = TravelMode.DRIVING,
        timelineEntries = timeline,
      )
    assertTrue("Expected Nasr Street hit", hits.isNotEmpty())
    val found = hits.first()
    assertEquals(FoundRoadRelation.AHEAD, found.relation)
    assertEquals(EntryLegalityStatus.OPEN, found.entryStatus)
    assertEquals(DistanceKind.ROAD_SHORTEST, found.distanceKind)
    assertEquals(2_000.0, found.alongRoadDistanceMeters!!, 35.0)
    assertEquals(1, found.matchedFeatureIndex)

    var overviewClicked = false
    composeRule.setContent {
      RoadAwarenessTheme {
        RoadAwarenessScreenContent(
          uiState =
            RoadHudUiState(
              location = LocationSnapshot(p0, 0.0, 12.0, 3f, isMock = true, timestampMs = 1_000L),
              hasConfirmedGraphData = true,
              activeFoundRoad = found,
              timelineEntries = timeline,
            ),
          foundTargetOffScreenOverride = true,
          onModeSelected = {},
          onSelectFeature = {},
          onGrantLocationClick = {},
          onOpenSearch = {},
          onRecenter = {},
          onOpenSettings = {},
          onSearchQueryChange = {},
          onExecuteSearch = {},
          onSelectPlace = {},
          onOverviewFoundRoad = { overviewClicked = true },
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

    composeRule.onNodeWithTag("found_road_callout").assertIsDisplayed()
    composeRule.onNodeWithTag("offscreen_found_indicator").assertIsDisplayed()
    composeRule.onNodeWithTag("found_callout_overview_button").performClick()
    composeRule.waitForIdle()
    assertTrue("Expected Overview button click to trigger overview framing", overviewClicked)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w400dp-h800dp")
  fun check8_findStreet1KmBehind_reportsBehindPassedAgo() {
    val pBackEnd = GeoPoint(30.0000, 31.0000)
    val pBackJunction = GeoMath.offsetPoint(pBackEnd, 0.0, 400.0)
    val pUser = GeoMath.offsetPoint(pBackJunction, 0.0, 1_000.0)
    val pAhead = GeoMath.offsetPoint(pUser, 0.0, 800.0)
    val sideWest = GeoMath.offsetPoint(pBackJunction, 270.0, 200.0)

    val graph =
      buildGraph(
        ways =
          listOf(
            OsmWay(10L, listOf(1L, 2L, 3L, 4L), listOf(pBackEnd, pBackJunction, pUser, pAhead), OsmTags(highway = "primary", nameEn = "Autostrad")),
            OsmWay(11L, listOf(2L, 5L), listOf(pBackJunction, sideWest), OsmTags(highway = "secondary", nameEn = "Maadi Exit")),
          ),
      )

    val matched = MapMatcher.match(graph, pUser, headingDeg = 0.0, TravelMode.DRIVING, null).primary!!
    val hits =
      LocalRoadSearchIndex.searchLocal(
        query = "Maadi Exit",
        graph = graph,
        pois = emptyList(),
        matched = matched,
        locationPoint = pUser,
        headingDeg = 0.0,
        mode = TravelMode.DRIVING,
      )
    assertTrue(hits.isNotEmpty())
    val found = hits.first()
    assertEquals(FoundRoadRelation.BEHIND, found.relation)
    assertEquals(DistanceKind.ROAD_SHORTEST, found.distanceKind)
    assertEquals(1_000.0, found.alongRoadDistanceMeters!!, 35.0)

    composeRule.setContent {
      RoadAwarenessTheme {
        RoadAwarenessScreenContent(
          uiState =
            RoadHudUiState(
              location = LocationSnapshot(pUser, 0.0, 10.0, 3f, isMock = true, timestampMs = 1_000L),
              hasConfirmedGraphData = true,
              activeFoundRoad = found,
              settings = UserSettings(unitPreference = UnitPreference.METRIC),
              effectiveUnitSystem = com.example.data.model.UnitSystem.METRIC,
            ),
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
    composeRule.onNodeWithText("Passed 1.0 km ago", substring = true).assertIsDisplayed()
  }

  @Test
  @Config(sdk = [36])
  fun check9_oneWayAgainstBlockedAndArabicNormalization() {
    assertEquals("النصر 123", LocalRoadSearchIndex.normalizeSearchText("شَارِع النَّصْر ١٢٣"))
    assertEquals("اهرام هرم", LocalRoadSearchIndex.normalizeSearchText("أهرام هرم"))

    val p0 = GeoPoint(30.0000, 31.0000)
    val pJ = GeoMath.offsetPoint(p0, 0.0, 400.0)
    val pA = GeoMath.offsetPoint(pJ, 0.0, 400.0)
    val pSideStart = GeoMath.offsetPoint(pJ, 90.0, 250.0)
    val graph =
      buildGraph(
        ways =
          listOf(
            OsmWay(1L, listOf(1L, 2L, 3L), listOf(p0, pJ, pA), OsmTags(highway = "primary", nameEn = "Main Rd")),
            OsmWay(
              id = 2L,
              nodeIds = listOf(4L, 2L),
              points = listOf(pSideStart, pJ),
              tags = OsmTags(highway = "secondary", name = "شارع التحرير ١٢", nameAr = "شارع التحرير ١٢", oneway = "yes"),
            ),
          ),
      )

    val matched = MapMatcher.match(graph, p0, 0.0, TravelMode.DRIVING, null).primary!!
    val hits =
      LocalRoadSearchIndex.searchLocal(
        query = "التحرير 12",
        graph = graph,
        pois = emptyList(),
        matched = matched,
        locationPoint = p0,
        headingDeg = 0.0,
        mode = TravelMode.DRIVING,
      )
    assertTrue("Expected Arabic normalized match ignoring diacritics/stopword/digits", hits.isNotEmpty())
    assertEquals(EntryLegalityStatus.BLOCKED, hits.first().entryStatus)
    assertEquals(FoundRoadRelation.AHEAD, hits.first().relation)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w840dp-h400dp")
  fun check10_touchTelemetryAndLandscapePanelSideLeftRightSwap() {
    val baseState =
      RoadHudUiState(
        location = LocationSnapshot(GeoPoint(30.0444, 31.2357), 0.0, 0.0, 3f, isMock = true, timestampMs = 1_000L),
        hasConfirmedGraphData = true,
        settings = UserSettings(landscapePanelSide = LandscapePanelSide.LEFT),
      )

    var panelSide by mutableStateOf(LandscapePanelSide.LEFT)

    composeRule.setContent {
      RoadAwarenessTheme {
        RoadAwarenessScreenContent(
          uiState = baseState.copy(settings = UserSettings(landscapePanelSide = panelSide)),
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

    val panelLeftBoundsLeft = composeRule.onNodeWithTag("illustration_panel").fetchSemanticsNode().boundsInRoot.left
    composeRule.onNodeWithTag("pill_search_button").performClick()
    composeRule.waitForIdle()
    assertEquals("First tap must register in TouchTelemetry", "pill_search_button", TouchTelemetry.debugLastControlId.value)
    assertTrue(TouchTelemetry.wakeTick.longValue >= 1L)

    panelSide = LandscapePanelSide.RIGHT
    composeRule.mainClock.advanceTimeBy(400L)
    composeRule.waitForIdle()
    val panelRightBoundsLeft = composeRule.onNodeWithTag("illustration_panel").fetchSemanticsNode().boundsInRoot.left

    assertTrue("Left panel x ($panelLeftBoundsLeft) should be near start", panelLeftBoundsLeft < 100f)
    assertTrue(
      "Right panel x ($panelRightBoundsLeft) must sit well to the right of Left panel x ($panelLeftBoundsLeft)",
      panelRightBoundsLeft > panelLeftBoundsLeft + 250f,
    )
    assertTrue(abs(panelRightBoundsLeft - panelLeftBoundsLeft) > 250f)
  }

  private fun buildGraph(ways: List<OsmWay>): RoadGraph {
    val nodes = HashMap<Long, OsmNode>()
    val wayMap = HashMap<Long, OsmWay>()
    val nodeToWays = HashMap<Long, MutableList<Long>>()
    for (w in ways) {
      wayMap[w.id] = w
      for (i in w.nodeIds.indices) {
        val nid = w.nodeIds[i]
        val pt = w.points[i]
        nodes.putIfAbsent(nid, OsmNode(nid, pt))
        nodeToWays.getOrPut(nid) { mutableListOf() }.add(w.id)
      }
    }
    return RoadGraph(
      nodes = nodes,
      ways = wayMap,
      nodeToWays = nodeToWays,
      fetchedAtMs = 1_000L,
      fromCache = false,
      source = GraphDataSource.OVERPASS,
    )
  }

  private fun gzipBytes(raw: ByteArray): ByteArray =
    ByteArrayOutputStream().use { out ->
      GZIPOutputStream(out).use { it.write(raw) }
      out.toByteArray()
    }

  private fun buildMvtBytes(
    layerName: String,
    extent: Int,
    key: String,
    value: String,
    cmds: List<Int>,
  ): ByteArray {
    val valueMsg = ByteArrayOutputStream().apply { writeStringField(1, value) }.toByteArray()
    val featMsg =
      ByteArrayOutputStream().apply {
        writePackedVarintsField(2, listOf(0, 0))
        writeVarintField(3, 2)
        writePackedVarintsField(4, cmds)
      }.toByteArray()
    val layerMsg =
      ByteArrayOutputStream().apply {
        writeVarintField(15, 2)
        writeStringField(1, layerName)
        writeBytesField(2, featMsg)
        writeStringField(3, key)
        writeBytesField(4, valueMsg)
        writeVarintField(5, extent)
      }.toByteArray()
    return ByteArrayOutputStream().apply { writeBytesField(3, layerMsg) }.toByteArray()
  }

  private fun cmd(c: Int, count: Int): Int = (cmdMask(c)) or (count shl 3)
  private fun cmdMask(c: Int): Int = c and 0x7
  private fun zz(n: Int): Int = (n shl 1) xor (n shr 31)

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
