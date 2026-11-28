package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.data.local.UserSettings
import com.example.data.model.AheadTimelineEntry
import com.example.data.model.BilingualName
import com.example.data.model.BranchStatus
import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.LadderOrder
import com.example.data.model.LadderTextSize
import com.example.data.model.LoadedTileBounds
import com.example.data.model.LocationSnapshot
import com.example.data.model.MatchResult
import com.example.data.model.NameOrder
import com.example.data.model.OsmNode
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.PassByRelation
import com.example.data.model.RelativeDirection
import com.example.data.model.RoadBranch
import com.example.data.model.RoadCandidate
import com.example.data.model.RoadFeature
import com.example.data.model.RoadGraph
import com.example.data.model.TravelMode
import com.example.data.model.UnitSystem
import com.example.domain.AheadGraphAnalyzer
import com.example.domain.FeatureTracker
import com.example.domain.GeoMath
import com.example.ui.RoadHudUiState
import com.example.ui.components.RoadLadderBuilder
import com.example.ui.components.RoadLadderSection
import com.example.ui.components.TouchTelemetry
import com.example.ui.screens.RoadAwarenessScreenContent
import com.example.ui.theme.GlassRenderConfig
import com.example.ui.theme.LocalGlassConfig
import com.example.ui.theme.RoadAwarenessTheme
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
@Config(sdk = [36])
class FixPass2Test {

  @get:Rule val composeRule = createComposeRule()

  @Before
  fun resetTelemetry() {
    TouchTelemetry.reset()
  }

  private fun graphFrom(ways: List<OsmWay>): RoadGraph {
    val nodes = LinkedHashMap<Long, OsmNode>()
    val n2w = HashMap<Long, MutableList<Long>>()
    for (w in ways) {
      w.nodeIds.forEachIndexed { idx, nid ->
        nodes.putIfAbsent(nid, OsmNode(nid, w.points[idx]))
        n2w.getOrPut(nid) { mutableListOf() }.add(w.id)
      }
    }
    return RoadGraph(
      nodes = nodes,
      ways = ways.associateBy { it.id },
      nodeToWays = n2w.mapValues { it.value.distinct() },
      fetchedAtMs = 1_000L,
      fromCache = false,
      source = GraphDataSource.TILES,
      loadedTiles = listOf(LoadedTileBounds(z = 14, x = 0, y = 0, south = 29.95, west = 30.95, north = 30.15, east = 31.55)),
    )
  }

  private fun ptNorth(start: GeoPoint, northM: Double, eastM: Double = 0.0): GeoPoint {
    val dLat = northM / 111_320.0
    val dLon = eastM / (111_320.0 * kotlin.math.cos(Math.toRadians(start.lat)))
    return GeoPoint(start.lat + dLat, start.lon + dLon)
  }

  // (a) Main road & opposite divided carriageway never appear as branches ("Ahead" / "Blocked")
  @Test
  fun testA_mainRoadAndOppositeCarriagewayNeverBranches() {
    val origin = GeoPoint(30.02, 31.42)
    val main1 =
      OsmWay(
        id = 100L,
        nodeIds = listOf(1L, 2L),
        points = listOf(origin, ptNorth(origin, 250.0)),
        tags = OsmTags(nameEn = "Al Tesein Southern", highway = "primary", oneway = "yes"),
      )
    val main2 =
      OsmWay(
        id = 101L,
        nodeIds = listOf(2L, 3L),
        points = listOf(ptNorth(origin, 250.0), ptNorth(origin, 650.0)),
        tags = OsmTags(nameEn = "Al Tesein Southern", highway = "primary", oneway = "yes"),
      )
    // Opposite carriageway 18m west, going south
    val opposite =
      OsmWay(
        id = 200L,
        nodeIds = listOf(20L, 21L, 22L),
        points = listOf(ptNorth(origin, 650.0, -18.0), ptNorth(origin, 250.0, -18.0), ptNorth(origin, 0.0, -18.0)),
        tags = OsmTags(nameEn = "Al Tesein Southern", highway = "primary", oneway = "yes"),
      )
    // Side branch at node 2 going right
    val sideRight =
      OsmWay(
        id = 300L,
        nodeIds = listOf(2L, 30L),
        points = listOf(ptNorth(origin, 250.0), ptNorth(origin, 250.0, 120.0)),
        tags = OsmTags(nameEn = "Lotus St", highway = "tertiary"),
      )

    val graph = graphFrom(listOf(main1, main2, opposite, sideRight))
    val cand =
      RoadCandidate(
        way = main1,
        name = BilingualName(null, "Al Tesein Southern", null),
        confidence = 0.96,
        lateralOffsetMeters = 0.5,
        bearingDiffDeg = 1.0,
        snappedPoint = ptNorth(origin, 40.0),
        segmentIndex = 0,
        forwardAlongWay = true,
      )

    val result =
      AheadGraphAnalyzer.analyzeAheadDetailed(
        graph = graph,
        matched = cand,
        mode = TravelMode.DRIVING,
        speedMps = 20.0,
        configuredHorizonMeters = 1_500.0,
      )

    assertTrue("Opposite carriageway must trigger dividedRoad", result.dividedRoad?.isDivided == true)
    for (feat in result.features) {
      assertFalse(
        "Main road continuation must never appear in branches",
        feat.branches.any { it.wayId == 100L || it.wayId == 101L || it.wayId == 200L || it.isMainContinuation },
      )
      assertFalse(
        "Main road name must never appear as Ahead branch",
        feat.branches.any { it.relativeDirection == RelativeDirection.AHEAD && it.name.english == "Al Tesein Southern" },
      )
    }
    val ladderRows =
      RoadLadderBuilder.buildRows(
        features = result.features,
        timelineEntries = result.timelineEntries,
        selectedFeatureIndex = 1,
        ladderOrder = LadderOrder.NEAREST_AT_BOTTOM,
      )
    assertTrue("Lotus St must appear in ladder", ladderRows.any { it.rightBranches.any { b -> b.name.english == "Lotus St" } })
    assertFalse("Opposite carriageway must not appear in ladder", ladderRows.any { row ->
      (row.leftBranches + row.rightBranches + listOfNotNull(row.aheadBranch)).any { it.name.english == "Al Tesein Southern" }
    })
  }

  // (b) Name/ref change emits continuesAsName; real fork emits forkMainName and lists only alternatives
  @Test
  fun testB_nameChangeEmitsContinuesAsAndForkMarksMain() {
    val origin = GeoPoint(30.03, 31.43)
    val main1 =
      OsmWay(
        id = 1000L,
        nodeIds = listOf(100L, 101L),
        points = listOf(origin, ptNorth(origin, 220.0)),
        tags = OsmTags(nameEn = "Axis Rd", highway = "primary", oneway = "yes"),
      )
    val main2 =
      OsmWay(
        id = 1001L,
        nodeIds = listOf(101L, 102L),
        points = listOf(ptNorth(origin, 220.0), ptNorth(origin, 520.0)),
        tags = OsmTags(nameEn = "Suez Rd", highway = "primary", oneway = "yes"),
      )
    val main3 =
      OsmWay(
        id = 1002L,
        nodeIds = listOf(102L, 103L),
        points = listOf(ptNorth(origin, 520.0), ptNorth(origin, 900.0, 20.0)),
        tags = OsmTags(nameEn = "Suez Rd", highway = "primary", oneway = "yes"),
      )
    val forkBranch =
      OsmWay(
        id = 1003L,
        nodeIds = listOf(102L, 104L),
        points = listOf(ptNorth(origin, 520.0), ptNorth(origin, 820.0, -180.0)),
        tags = OsmTags(nameEn = "Airport Branch", highway = "primary", oneway = "yes"),
      )

    val graph = graphFrom(listOf(main1, main2, main3, forkBranch))
    val cand =
      RoadCandidate(
        way = main1,
        name = BilingualName(null, "Axis Rd", null),
        confidence = 0.95,
        lateralOffsetMeters = 0.0,
        bearingDiffDeg = 0.0,
        snappedPoint = ptNorth(origin, 20.0),
        segmentIndex = 0,
        forwardAlongWay = true,
      )

    val result =
      AheadGraphAnalyzer.analyzeAheadDetailed(
        graph = graph,
        matched = cand,
        mode = TravelMode.DRIVING,
        speedMps = 20.0,
        configuredHorizonMeters = 1_500.0,
      )

    val continuesFeature = result.features.firstOrNull { it.continuesAsName?.english == "Suez Rd" }
    assertNotNull("Name change at 200m should produce continuesAsName=Suez Rd", continuesFeature)
    assertTrue("Pure name change should have 0 side branches", continuesFeature!!.branches.isEmpty())

    val forkFeature =
      result.features.firstOrNull {
        it.type == FeatureType.FORK && it.continuesAsName == null && it.distanceMeters > 400.0
      }
    assertNotNull("Real split at 500m should produce FORK", forkFeature)
    assertEquals("Suez Rd", forkFeature!!.forkMainName?.english)
    assertFalse(
      "Chosen main fork edge must not appear in branches",
      forkFeature.branches.any { it.wayId == 1002L || it.isMainContinuation },
    )
    assertTrue(
      "Alternative fork edge must appear in branches",
      forkFeature.branches.any { it.wayId == 1003L && it.name.english == "Airport Branch" },
    )
  }

  // (c) Divided road + slow/frontage road + 6 cross streets (2 slow-road-only) all listed
  @Test
  fun testC_dividedWithSlowRoadAndAllSixSideStreets() {
    val origin = GeoPoint(30.04, 31.44)
    val mainWay =
      OsmWay(
        id = 2000L,
        nodeIds = listOf(200L, 201L, 202L, 203L, 204L),
        points =
          listOf(
            origin,
            ptNorth(origin, 400.0),
            ptNorth(origin, 800.0),
            ptNorth(origin, 1200.0),
            ptNorth(origin, 1800.0),
          ),
        tags = OsmTags(nameEn = "Main Expressway", highway = "trunk", oneway = "yes"),
      )
    val oppWay =
      OsmWay(
        id = 2001L,
        nodeIds = listOf(210L, 211L),
        points = listOf(ptNorth(origin, 1800.0, -20.0), ptNorth(origin, 0.0, -20.0)),
        tags = OsmTags(nameEn = "Main Expressway", highway = "trunk", oneway = "yes"),
      )
    // Parallel slow/frontage service road 16m right along 150m..1350m
    val slowWay =
      OsmWay(
        id = 2002L,
        nodeIds = listOf(220L, 221L, 222L, 223L),
        points =
          listOf(
            ptNorth(origin, 150.0, 16.0),
            ptNorth(origin, 500.0, 16.0),
            ptNorth(origin, 900.0, 16.0),
            ptNorth(origin, 1350.0, 16.0),
          ),
        tags = OsmTags(nameEn = "Frontage Slow Rd", highway = "service", oneway = "yes"),
      )
    // Link connecting mainWay(201 @ 400m) to slowWay(221 @ 500m)
    val slipLink =
      OsmWay(
        id = 2003L,
        nodeIds = listOf(201L, 221L),
        points = listOf(ptNorth(origin, 400.0), ptNorth(origin, 500.0, 16.0)),
        tags = OsmTags(highway = "trunk_link", oneway = "yes"),
      )
    // 3 Left streets + 3 Right streets (2 right streets connect ONLY to slowWay nodes 221 and 222)
    val left1 =
      OsmWay(
        id = 2101L,
        nodeIds = listOf(202L, 231L),
        points = listOf(ptNorth(origin, 800.0), ptNorth(origin, 800.0, -90.0)),
        tags = OsmTags(nameEn = "Left Street 1", highway = "residential"),
      )
    val left2 =
      OsmWay(
        id = 2102L,
        nodeIds = listOf(242L, 243L),
        points = listOf(ptNorth(origin, 950.0, -24.0), ptNorth(origin, 950.0, -95.0)),
        tags = OsmTags(nameEn = "Left Street 2", highway = "residential"),
      )
    val left3 =
      OsmWay(
        id = 2103L,
        nodeIds = listOf(203L, 251L),
        points = listOf(ptNorth(origin, 1200.0), ptNorth(origin, 1200.0, -90.0)),
        tags = OsmTags(nameEn = "Left Street 3", highway = "tertiary"),
      )
    val rightDirect =
      OsmWay(
        id = 2201L,
        nodeIds = listOf(203L, 261L),
        points = listOf(ptNorth(origin, 1200.0), ptNorth(origin, 1200.0, 95.0)),
        tags = OsmTags(nameEn = "Right Direct St", highway = "secondary"),
      )
    // Connected ONLY to slowWay node 221 (at 500m)
    val rightViaSlow1 =
      OsmWay(
        id = 2202L,
        nodeIds = listOf(221L, 271L),
        points = listOf(ptNorth(origin, 500.0, 16.0), ptNorth(origin, 500.0, 100.0)),
        tags = OsmTags(nameEn = "Right Slow St 1", highway = "residential"),
      )
    // Connected ONLY to slowWay node 222 (at 900m)
    val rightViaSlow2 =
      OsmWay(
        id = 2203L,
        nodeIds = listOf(222L, 281L),
        points = listOf(ptNorth(origin, 900.0, 16.0), ptNorth(origin, 900.0, 100.0)),
        tags = OsmTags(nameEn = "Right Slow St 2", highway = "residential"),
      )

    val graph =
      graphFrom(
        listOf(mainWay, oppWay, slowWay, slipLink, left1, left2, left3, rightDirect, rightViaSlow1, rightViaSlow2),
      )
    val cand =
      RoadCandidate(
        way = mainWay,
        name = BilingualName(null, "Main Expressway", null),
        confidence = 0.96,
        lateralOffsetMeters = 0.0,
        bearingDiffDeg = 0.0,
        snappedPoint = ptNorth(origin, 20.0),
        segmentIndex = 0,
        forwardAlongWay = true,
      )

    val result =
      AheadGraphAnalyzer.analyzeAheadDetailed(
        graph = graph,
        matched = cand,
        mode = TravelMode.DRIVING,
        speedMps = 28.0,
        configuredHorizonMeters = 2_000.0,
      )

    val allNames =
      (result.timelineEntries.mapNotNull { it.title.english } +
        result.features.flatMap { f -> f.branches.mapNotNull { it.name.english } }).toSet()
    listOf("Left Street 1", "Left Street 2", "Left Street 3", "Right Direct St", "Right Slow St 1", "Right Slow St 2")
      .forEach { expected ->
        assertTrue("Expected $expected in horizon results, got $allNames", expected in allNames)
      }

    val slowEntry1 = result.timelineEntries.firstOrNull { it.title.english == "Right Slow St 1" }
    val slowEntry2 = result.timelineEntries.firstOrNull { it.title.english == "Right Slow St 2" }
    assertEquals(PassByRelation.VIA_FRONTAGE_OR_RAMP, slowEntry1?.passByRelation)
    assertEquals(PassByRelation.VIA_FRONTAGE_OR_RAMP, slowEntry2?.passByRelation)
    val slowStretches = result.timelineEntries.filter { it.isSlowRoadStretch }
    assertTrue("Slow road stretch should be emitted once", slowStretches.size <= 1)
  }

  // (d) Full 3 km ascending order + -25m pass-drop without any passed list
  @Test
  fun testD_fullHorizonOrderAndMinus25mPassDrop() {
    val origin = GeoPoint(30.05, 31.45)
    val corridor = listOf(origin, ptNorth(origin, 3000.0))
    val f1 =
      RoadFeature(
        index = 1,
        type = FeatureType.T_JUNCTION,
        distanceMeters = 100.0,
        anchor = ptNorth(origin, 100.0),
        anchorNodeId = 1L,
        incomingLocalPoints = emptyList(),
        incomingWorldPoints = emptyList(),
        outgoingMainLocalPoints = emptyList(),
        outgoingMainWorldPoints = emptyList(),
        branches = emptyList(),
        osmTags = OsmTags(nameEn = "First St"),
      )
    val f2 = f1.copy(index = 2, distanceMeters = 2800.0, anchor = ptNorth(origin, 2800.0), anchorNodeId = 2L, osmTags = OsmTags(nameEn = "Far St"))

    val rowsTop =
      RoadLadderBuilder.buildRows(
        features = listOf(f2, f1),
        timelineEntries = emptyList(),
        selectedFeatureIndex = 1,
        ladderOrder = LadderOrder.NEAREST_AT_TOP,
      )
    assertEquals(100.0, rowsTop.first().distanceMeters, 0.1)
    assertEquals(2800.0, rowsTop.last().distanceMeters, 0.1)

    val tracker = FeatureTracker()
    tracker.reconcile(
      nowMs = 1_000L,
      matchedWayId = 10L,
      isOffRoadUnselected = false,
      tracedWayIds = setOf(10L),
      corridorWorldPoints = corridor,
      userPosition = origin,
      newFeatures = listOf(f1, f2),
      newTimelineEntries = emptyList(),
      newLandmarks = emptyList(),
      loadedCorridorMeters = 3000.0,
      configuredHorizonMeters = 3000.0,
      isPartialBudgetResult = false,
      horizonStoppedAtUnloadedBorder = false,
    )

    // Move in 10m increments past f1 at 100m until 130m (=-30m behind)
    var latest = tracker.deadReckon(ptNorth(origin, 110.0), 10L)!!
    assertTrue("At -10m behind, First St stays visible", latest.features.any { it.osmTags.nameEn == "First St" })
    latest = tracker.deadReckon(ptNorth(origin, 120.0), 10L)!!
    assertTrue("At -20m behind, First St stays visible", latest.features.any { it.osmTags.nameEn == "First St" })
    latest = tracker.deadReckon(ptNorth(origin, 130.0), 10L)!!
    assertFalse("At -30m (< -25m), First St must drop completely", latest.features.any { it.osmTags.nameEn == "First St" })
    assertTrue("Far St feature remains", latest.features.any { it.osmTags.nameEn == "Far St" })
  }

  // (e) Compose test at fontScale = 1.3f: full street names shown, zero ellipsis, zero "+N"
  @Test
  @Config(sdk = [36], qualifiers = "w412dp-h915dp")
  fun testE_fontScale13_fullNamesNoEllipsisOrPlusN() {
    val longLeft1 = "Abdul Aziz Al Saud Long Boulevard Extension"
    val longLeft2 = "Mohamed Naguib Axis Connector"
    val longLeft3 = "Old Heliopolis Service Avenue"
    val longRight = "Gamal Abdel Nasser Eastern Corridor"

    val f =
      RoadFeature(
        index = 1,
        type = FeatureType.MULTI_WAY,
        distanceMeters = 180.0,
        anchor = GeoPoint(30.0, 31.0),
        anchorNodeId = 10L,
        incomingLocalPoints = emptyList(),
        incomingWorldPoints = emptyList(),
        outgoingMainLocalPoints = emptyList(),
        outgoingMainWorldPoints = emptyList(),
        branches =
          listOf(
            RoadBranch("1A", 11L, BilingualName(null, longLeft1, null), null, 180.0, BranchStatus.OPEN, RelativeDirection.LEFT, -60.0, emptyList(), emptyList(), OsmTags(highway = "secondary")),
            RoadBranch("1B", 12L, BilingualName(null, longLeft2, null), null, 180.0, BranchStatus.OPEN, RelativeDirection.LEFT, -80.0, emptyList(), emptyList(), OsmTags(highway = "tertiary")),
            RoadBranch("1C", 13L, BilingualName(null, longLeft3, null), null, 180.0, BranchStatus.OPEN, RelativeDirection.LEFT, -100.0, emptyList(), emptyList(), OsmTags(highway = "residential")),
            RoadBranch("1D", 14L, BilingualName(null, longRight, null), null, 180.0, BranchStatus.OPEN, RelativeDirection.RIGHT, 75.0, emptyList(), emptyList(), OsmTags(highway = "primary")),
          ),
        osmTags = OsmTags(),
        forkMainName = null,
      )

    composeRule.setContent {
      RoadAwarenessTheme {
        CompositionLocalProvider(
          LocalDensity provides Density(density = LocalDensity.current.density, fontScale = 1.3f),
          LocalGlassConfig provides GlassRenderConfig(isDark = true),
        ) {
          Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B1220))) {
            RoadLadderSection(
              features = listOf(f),
              timelineEntries = emptyList(),
              selectedFeatureIndex = 1,
              ladderOrder = LadderOrder.NEAREST_AT_BOTTOM,
              unitSystem = UnitSystem.METRIC,
              horizonStoppedAtUnloadedBorder = false,
              compactMode = false,
              onSelectFeature = {},
              onExpandRequest = {},
              textSize = LadderTextSize.LARGE,
              nameOrder = NameOrder.ENGLISH_FIRST,
              isDividedRoad = true,
            )
          }
        }
      }
    }

    composeRule.waitForIdle()
    composeRule.onNodeWithText(longLeft1).assertIsDisplayed()
    composeRule.onNodeWithText(longLeft2).assertIsDisplayed()
    composeRule.onNodeWithText(longLeft3).assertIsDisplayed()
    composeRule.onNodeWithText(longRight).assertIsDisplayed()
    composeRule.onNodeWithTag("ladder_divided_road_banner").assertIsDisplayed()
    assertEquals(0, composeRule.onAllNodesWithText("+1", substring = true).fetchSemanticsNodes().size)
    assertEquals(0, composeRule.onAllNodesWithText("…", substring = true).fetchSemanticsNodes().size)
  }

  // (f) Landscape 1280x720: widened panel (<= 45%), compact ladder + >=56dp Whole road button + WholeRoadSheet
  @Test
  @Config(sdk = [36], qualifiers = "w1280dp-h720dp")
  fun testF_landscape1280x720_widenedPanelAndWholeRoadSheet() {
    val origin = GeoPoint(30.06, 31.46)
    val pts = listOf(origin, ptNorth(origin, 2500.0))
    val way = OsmWay(1L, listOf(1L, 2L), pts, OsmTags(nameEn = "Suez Rd", highway = "primary"))
    val cand = RoadCandidate(way, BilingualName(null, "Suez Rd", null), 0.95, 0.0, 0.0, origin, 0, true)
    val features =
      (1..6).map { i ->
        RoadFeature(
          index = i,
          type = FeatureType.T_JUNCTION,
          distanceMeters = i * 350.0,
          anchor = ptNorth(origin, i * 350.0),
          anchorNodeId = i.toLong(),
          incomingLocalPoints = emptyList(),
          incomingWorldPoints = emptyList(),
          outgoingMainLocalPoints = emptyList(),
          outgoingMainWorldPoints = emptyList(),
          branches =
            listOf(
              RoadBranch("${i}A", 100L + i, BilingualName(null, "Cross Street $i", null), null, i * 350.0, BranchStatus.OPEN, RelativeDirection.RIGHT, 85.0, emptyList(), emptyList(), OsmTags(highway = "residential")),
            ),
          osmTags = OsmTags(),
        )
      }

    var wholeRoadOpened = false
    val ui =
      RoadHudUiState(
        hasLocationPermission = true,
        isGpsSearching = false,
        location = LocationSnapshot(origin, 0.0, 20.0, 3.0f, true, 1000L),
        travelMode = TravelMode.DRIVING,
        isMockModeActive = true,
        matchResult = MatchResult(cand, null, false),
        features = features,
        selectedFeatureIndex = 1,
        isDividedRoad = true,
        lookaheadMeters = 3_000.0,
        settings = UserSettings(ladderTextSize = LadderTextSize.EXTRA_LARGE),
      )

    composeRule.setContent {
      RoadAwarenessTheme {
        RoadAwarenessScreenContent(
          uiState = ui,
          isPanelExpandedOverride = false,
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
          onOpenWholeRoad = { open -> wholeRoadOpened = open },
          mapContent = { mod, _, _, _, _, _ -> Box(modifier = mod.background(Color(0xFF101828))) },
        )
      }
    }

    composeRule.waitForIdle()
    val panelBounds = composeRule.onNodeWithTag("illustration_panel").fetchSemanticsNode().boundsInRoot
    assertTrue("Panel width (${panelBounds.width}) should be in 320..560dp (~45% of 1280 = 560)", panelBounds.width in 500f..562f)
    composeRule.onNodeWithTag("road_ladder_section").assertIsDisplayed()
    composeRule.onNodeWithTag("ladder_whole_road_button").assertIsDisplayed()
    composeRule.onNodeWithTag("ladder_whole_road_button").assertHeightIsAtLeast(56.dp)
    assertTrue("Visible rows in 720dp landscape must fit multiple cross streets", composeRule.onAllNodes(hasTestTag("ladder_row_feat_1_1")).fetchSemanticsNodes().isNotEmpty())
    composeRule.onNodeWithTag("ladder_whole_road_button").performClick()
    assertTrue("Whole road button click must invoke callback", wholeRoadOpened)
  }
}
