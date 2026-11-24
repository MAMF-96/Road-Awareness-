package com.example

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.RoadAwarenessDatabase
import com.example.data.local.SavedPinRepository
import com.example.data.model.BilingualName
import com.example.data.model.BranchStatus
import com.example.data.model.DistanceKind
import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.LocationSnapshot
import com.example.data.model.MatchResult
import com.example.data.model.OffRoadState
import com.example.data.model.OsmNode
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.PinSortOrder
import com.example.data.model.RelativeDirection
import com.example.data.model.RoadBranch
import com.example.data.model.RoadCandidate
import com.example.data.model.RoadFeature
import com.example.data.model.SavedPin
import com.example.data.model.SearchPlaceResult
import com.example.data.model.TravelMode
import com.example.data.remote.DarkMapStyleGenerator
import com.example.data.remote.NominatimSearchService
import com.example.data.remote.ValhallaRoutingService
import com.example.ui.InspectorSheetDetent
import com.example.ui.RoadHudUiState
import com.example.ui.components.PinCalloutCard
import com.example.ui.components.PinsBottomSheet
import com.example.ui.components.buildInspectorPlainText
import com.example.ui.screens.RoadAwarenessScreenContent
import com.example.ui.theme.RoadAwarenessTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SearchPinsAndHeaderTest {
  @get:Rule
  val composeRule = createComposeRule()

  private fun baseStateWithLongArabicRoad(): RoadHudUiState {
    val way =
      OsmWay(
        id = 777L,
        nodeIds = listOf(1L, 2L),
        points = listOf(GeoPoint(30.0444, 31.2357), GeoPoint(30.0460, 31.2357)),
        tags =
          OsmTags(
            highway = "primary",
            nameAr = "طريق الملك عبدالعزيز بن عبدالرحمن آل سعود المحوري السريع الممتد",
            nameEn = "King Abdulaziz Bin Abdulrahman Al Saud Central Expressway",
          ),
      )
    val bName = BilingualName.fromOsmTags(way.tags)
    val match =
      MatchResult(
        primary =
          RoadCandidate(
            way = way,
            name = bName,
            confidence = 0.94,
            lateralOffsetMeters = 2.0,
            bearingDiffDeg = 0.0,
            snappedPoint = GeoPoint(30.0444, 31.2357),
            segmentIndex = 0,
            forwardAlongWay = true,
          ),
        secondary = null,
        isUncertain = false,
      )
    val feat =
      RoadFeature(
        index = 1,
        type = FeatureType.FOUR_WAY,
        distanceMeters = 45.0,
        anchor = GeoPoint(30.0450, 31.2357),
        anchorNodeId = 2L,
        incomingLocalPoints = emptyList(),
        incomingWorldPoints = listOf(GeoPoint(30.0444, 31.2357), GeoPoint(30.0450, 31.2357)),
        outgoingMainLocalPoints = emptyList(),
        outgoingMainWorldPoints = listOf(GeoPoint(30.0450, 31.2357), GeoPoint(30.0460, 31.2357)),
        branches =
          listOf(
            RoadBranch(
              badgeId = "1A",
              wayId = 10L,
              name = BilingualName("شارع التحرير", "Tahrir St", null),
              leadsTo = "Downtown",
              distanceMeters = 45.0,
              status = BranchStatus.OPEN,
              relativeDirection = RelativeDirection.LEFT,
              bearingDeltaDeg = -90.0,
              worldPoints = listOf(GeoPoint(30.0450, 31.2357), GeoPoint(30.0450, 31.2345)),
              localPoints = emptyList(),
              osmTags = OsmTags(highway = "secondary", nameEn = "Tahrir St"),
            ),
          ),
        osmTags = way.tags,
      )
    return RoadHudUiState(
      hasLocationPermission = true,
      isGpsSearching = false,
      location = LocationSnapshot(GeoPoint(30.0444, 31.2357), 0.0, 10.0, 4f, true, 1000L),
      travelMode = TravelMode.DRIVING,
      hasConfirmedGraphData = true,
      lastConfirmedRoadName = bName,
      matchResult = match,
      features = listOf(feat),
      selectedFeatureIndex = 1,
      debugOffline = true,
    )
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun headerFullWidthLongArabicAndFloatingStripBelowCard_portrait() {
    val ui = baseStateWithLongArabicRoad()
    composeRule.setContent {
      RoadAwarenessTheme {
        RoadAwarenessScreenContent(
          uiState = ui,
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
          mapContent = { mod, _, _, _, _, _ -> Box(modifier = mod.background(Color.White)) },
        )
      }
    }
    composeRule.waitForIdle()

    val cardBounds = composeRule.onNodeWithTag("illustration_panel").fetchSemanticsNode().boundsInRoot
    val headerBounds = composeRule.onNodeWithTag("header_road_name_slot").fetchSemanticsNode().boundsInRoot
    val modeBounds = composeRule.onNodeWithTag("mode_segmented_control").fetchSemanticsNode().boundsInRoot
    val chipsBounds = composeRule.onNodeWithTag("floating_status_chips_row").fetchSemanticsNode().boundsInRoot

    // Top row is full card width (only padding inside card, not squeezed by mode control)
    assertTrue(
      "Header width (${headerBounds.width}) should use full card width (${cardBounds.width})",
      headerBounds.width >= cardBounds.width - 40f,
    )
    // Status strip and >=44dp (48dp touch-target) mode capsule are directly BELOW the card
    assertTrue("Status chips (${chipsBounds.top}) must be below card (${cardBounds.bottom})", chipsBounds.top >= cardBounds.bottom - 2f)
    assertTrue("Mode capsule (${modeBounds.top}) must be below card (${cardBounds.bottom})", modeBounds.top >= cardBounds.bottom - 2f)
    assertTrue("Mode capsule height (${modeBounds.height}) must be 44..50dp", modeBounds.height in 44f..50f)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w800dp-h360dp")
  fun headerAndFloatingStripBelowCard_landscape() {
    val ui = baseStateWithLongArabicRoad()
    composeRule.setContent {
      RoadAwarenessTheme {
        RoadAwarenessScreenContent(
          uiState = ui,
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
          mapContent = { mod, _, _, _, _, _ -> Box(modifier = mod.background(Color.White)) },
        )
      }
    }
    composeRule.waitForIdle()

    val cardBounds = composeRule.onNodeWithTag("illustration_panel").fetchSemanticsNode().boundsInRoot
    val freeBounds = composeRule.onNodeWithTag("landscape_free_map_area").fetchSemanticsNode().boundsInRoot
    val modeBounds = composeRule.onNodeWithTag("mode_segmented_control").fetchSemanticsNode().boundsInRoot
    val chipsBounds = composeRule.onNodeWithTag("floating_status_chips_row").fetchSemanticsNode().boundsInRoot

    assertTrue("Landscape side card (${cardBounds.right}) sits left of free area (${freeBounds.left})", cardBounds.right <= freeBounds.left + 2f)
    assertTrue("Landscape status chips (${chipsBounds.left}) sit at start of free area (${freeBounds.left})", chipsBounds.left >= freeBounds.left - 2f)
    assertTrue("Landscape status chips sit near top of free area (${chipsBounds.top})", chipsBounds.top <= 40f)
    assertTrue("Landscape mode capsule sits at top-end of free area", modeBounds.right > chipsBounds.right)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun headerMissingAndOffRoadRules_neverShowStatusWordsInHeader() {
    val offRoadNoHistory =
      RoadHudUiState(
        hasLocationPermission = true,
        isGpsSearching = false,
        hasConfirmedGraphData = true,
        offRoadState = OffRoadState(isOffRoad = true),
        lastConfirmedRoadName = null,
      )
    composeRule.setContent {
      RoadAwarenessTheme {
        RoadAwarenessScreenContent(
          uiState = offRoadNoHistory,
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
    composeRule.onNodeWithTag("header_road_name_slot").assertIsDisplayed()
    composeRule.onNodeWithText("—").assertIsDisplayed()
    composeRule.onNodeWithTag("off_road_chip").assertIsDisplayed()
  }

  @Test
  fun mapStyleGenerator_enhancesRoadLabelsAndHaloAndBilingualNames() {
    val sampleStyle =
      """
      {
        "version": 8,
        "layers": [
          {
            "id": "highway_name_major",
            "type": "symbol",
            "source-layer": "transportation_name",
            "layout": {
              "text-field": "{name:latin}",
              "text-size": ["interpolate", ["linear"], ["zoom"], 13, 10, 16, 14]
            },
            "paint": {
              "text-color": "#333333",
              "text-halo-width": 1.0
            }
          }
        ]
      }
      """.trimIndent()

    val enhanced = JSONObject(DarkMapStyleGenerator.enhanceRoadLabelsInStyleJson(sampleStyle))
    val layer = enhanced.getJSONArray("layers").getJSONObject(0)
    val layout = layer.getJSONObject("layout")
    val paint = layer.getJSONObject("paint")

    assertTrue("text-halo-width must be >= 1.5", paint.getDouble("text-halo-width") >= 1.5)
    val textFieldStr = layout.get("text-field").toString()
    assertTrue("Bilingual text-field must include Arabic tag", textFieldStr.contains("name:ar"))
    assertTrue("Bilingual text-field must include Latin fallback", textFieldStr.contains("name:latin"))

    val textSize = layout.getJSONArray("text-size")
    // Find zoom 16 value -> should be 14 * 1.15 = 16.1
    var zoom16Val = 0.0
    var i = 3
    while (i + 1 < textSize.length()) {
      if (textSize.getDouble(i) == 16.0) {
        zoom16Val = textSize.getDouble(i + 1)
      }
      i += 2
    }
    assertEquals(16.1, zoom16Val, 0.05)
  }

  @Test
  fun nominatimParser_parsesNamedetailsPlaceTypeAndStreetGeometry() {
    val raw =
      """
      [
        {
          "place_id": 1001,
          "lat": "30.0444",
          "lon": "31.2357",
          "class": "amenity",
          "type": "cafe",
          "name": "مقهى النيل",
          "display_name": "مقهى النيل, القاهرة",
          "namedetails": {"name:ar": "مقهى النيل", "name:en": "Nile Cafe"}
        },
        {
          "place_id": 1002,
          "lat": "30.0450",
          "lon": "31.2360",
          "class": "highway",
          "type": "primary",
          "name": "شارع التحرير",
          "display_name": "شارع التحرير, القاهرة",
          "namedetails": {"name:ar": "شارع التحرير", "name:en": "Tahrir Street"},
          "geojson": {
            "type": "LineString",
            "coordinates": [[31.2350, 30.0440], [31.2370, 30.0460]]
          }
        }
      ]
      """.trimIndent()

    val parsed = NominatimSearchService.parseNominatimJson(raw, "query")
    assertEquals(2, parsed.size)

    val place = parsed[0]
    assertFalse(place.isStreet)
    assertEquals("مقهى النيل", place.nameAr)
    assertEquals("Nile Cafe", place.nameEn)
    assertEquals("Cafe", place.placeType)
    assertTrue(place.streetLines.isEmpty())

    val street = parsed[1]
    assertTrue(street.isStreet)
    assertEquals("شارع التحرير", street.nameAr)
    assertEquals("Tahrir Street", street.nameEn)
    assertEquals("Primary road", street.placeType)
    assertEquals(1, street.streetLines.size)
    assertEquals(2, street.streetLines[0].size)
  }

  @Test
  fun valhallaMatrix_computesAllTargetsInSingleRequest() = runBlocking {
    val callCount = AtomicInteger(0)
    val fakeClient =
      OkHttpClient.Builder()
        .addInterceptor(
          Interceptor { chain ->
            callCount.incrementAndGet()
            assertTrue(chain.request().url.encodedPath.endsWith("sources_to_targets"))
            val bodyJson =
              """
              {
                "sources_to_targets": [
                  [
                    {"distance": 0.45, "time": 50},
                    {"distance": 1.80, "time": 180}
                  ]
                ]
              }
              """.trimIndent()
            Response.Builder()
              .request(chain.request())
              .protocol(Protocol.HTTP_1_1)
              .code(200)
              .message("OK")
              .body(bodyJson.toResponseBody("application/json".toMediaType()))
              .build()
          },
        )
        .build()

    val service = ValhallaRoutingService(client = fakeClient)
    val matrix =
      service.computeDistanceMatrix(
        origin = GeoPoint(30.0444, 31.2357),
        destinations = listOf(GeoPoint(30.046, 31.236), GeoPoint(30.052, 31.240)),
        mode = TravelMode.DRIVING,
      )

    assertEquals(1, callCount.get())
    assertEquals(2, matrix.size)
    assertEquals(450.0, matrix[0].distanceMeters!!, 0.5)
    assertEquals(DistanceKind.ROAD_SHORTEST, matrix[0].kind)
    assertEquals(1800.0, matrix[1].distanceMeters!!, 0.5)
    assertEquals(DistanceKind.ROAD_SHORTEST, matrix[1].kind)
  }

  @Test
  fun savedPinRepository_persistsAndCapsAt30() = runBlocking {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val db = Room.inMemoryDatabaseBuilder(app, RoadAwarenessDatabase::class.java).allowMainThreadQueries().build()
    try {
      val repo = SavedPinRepository(db.savedPinDao(), maxPins = 30)
      for (i in 1..32) {
        repo.addOrUpdatePin(
          SearchPlaceResult(
            placeId = i.toLong(),
            primaryName = "Place $i",
            secondaryName = null,
            point = GeoPoint(30.0 + i * 0.001, 31.2),
            distanceMeters = i * 100.0,
            distanceKind = DistanceKind.ROAD_SHORTEST,
            nameAr = "مكان $i",
            nameEn = "Place $i",
            placeType = if (i % 2 == 0) "Street" else "Cafe",
            isStreet = i % 2 == 0,
            streetLines =
              if (i % 2 == 0) listOf(listOf(GeoPoint(30.0, 31.2), GeoPoint(30.01, 31.21)))
              else emptyList(),
          ),
        )
        Thread.sleep(2)
      }
      val all = repo.getAllPinsSnapshot()
      assertEquals(30, all.size)
      assertTrue("Newest pin (32) must exist", all.any { it.placeId == 32L })
      assertFalse("Oldest pin (1) must be evicted", all.any { it.placeId == 1L })
      val street = all.first { it.placeId == 32L }
      assertTrue(street.isStreet)
      assertEquals(1, street.streetLines.size)
    } finally {
      db.close()
    }
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun inspectorDetailsButtonAndFullScreenReportDialog_max60Lines() {
    val ui =
      baseStateWithLongArabicRoad().copy(
        isInspectorOpen = true,
        inspectorDetent = InspectorSheetDetent.HALF,
        savedPins =
          listOf(
            SavedPin(
              id = 1L,
              placeId = 99L,
              nameAr = "نقطة اختبار",
              nameEn = "Test Pin",
              primaryName = "Test Pin",
              secondaryName = null,
              placeType = "Cafe",
              point = GeoPoint(30.045, 31.236),
              isStreet = false,
              streetLines = emptyList(),
              createdAtMs = 1000L,
              distanceMeters = 320.0,
              distanceKind = DistanceKind.ROAD_SHORTEST,
            ),
          ),
      )

    var detent = InspectorSheetDetent.HALF
    composeRule.setContent {
      RoadAwarenessTheme {
        RoadAwarenessScreenContent(
          uiState = ui.copy(inspectorDetent = detent),
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
          onInspectorDetentChange = { detent = it },
          onMockHeadingChange = {},
          onToggleMockMode = {},
          onDebugFlagsChange = { _, _, _ -> },
          onClearCache = {},
          mapContent = { mod, _, _, _, _, _ -> Box(modifier = mod.background(Color.White)) },
        )
      }
    }
    composeRule.waitForIdle()

    // Floating Pins button with badge visible when savedPins non-empty
    composeRule.onNodeWithTag("floating_pins_button").assertIsDisplayed()
    composeRule.onNodeWithTag("floating_pins_badge").assertIsDisplayed()

    // 48dp drag handle
    val handleBounds = composeRule.onNodeWithTag("inspector_drag_handle").fetchSemanticsNode().boundsInRoot
    assertTrue("Drag handle height (${handleBounds.height}) must be >= 46dp", handleBounds.height >= 46f)

    // Details chevron-up button is visible and reachable
    composeRule.onNodeWithTag("inspector_details_button").assertIsDisplayed()

    // Copy opens full-screen Report dialog with <= 60 lines and clipboard note
    composeRule.onNodeWithTag("inspector_copy_report_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("inspector_report_dialog").assertIsDisplayed()
    composeRule.onNodeWithTag("report_clipboard_note").assertIsDisplayed()
    composeRule.onNodeWithText("Clipboard may not reach your device", substring = true).assertIsDisplayed()

    val reportText = buildInspectorPlainText(ui)
    val lines = reportText.lines()
    assertTrue("Report must be <= 60 lines (was ${lines.size})", lines.size <= 60)
    assertTrue(reportText.contains("[POSITION]"))
    assertTrue(reportText.contains("[MODE]"))
    assertTrue(reportText.contains("[ROAD]"))
    assertTrue(reportText.contains("[SOURCES]"))
    assertTrue(reportText.contains("[OVERRIDES]"))
    assertTrue(reportText.contains("[LAST_3_REQUESTS]"))
    assertTrue(reportText.contains("[FEATURES]"))
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun pinCalloutAndPinsSheet_routeToggleAndSortAndClear() {
    val pins =
      listOf(
        SavedPin(1L, 10L, "أول", "First", "First", null, "Cafe", GeoPoint(30.04, 31.23), false, emptyList(), 10L, 420.0, DistanceKind.ROAD_SHORTEST),
        SavedPin(2L, 20L, "ثاني", "Second", "Second", null, "Street", GeoPoint(30.05, 31.24), true, emptyList(), 20L, 150.0, DistanceKind.FASTEST_ROUTE),
      )
    var routeActiveId: Long? = null
    var sortOrder = PinSortOrder.NEAREST
    var removedId: Long? = null

    composeRule.setContent {
      RoadAwarenessTheme {
        PinCalloutCard(
          pin = pins[0],
          isRouteActive = routeActiveId == pins[0].id,
          isLoadingRoute = false,
          onToggleRoute = { routeActiveId = if (routeActiveId == pins[0].id) null else pins[0].id },
          onRemove = { removedId = pins[0].id },
          onDismiss = {},
        )
        PinsBottomSheet(
          pins = pins,
          sortOrder = sortOrder,
          activeRoutePinId = routeActiveId,
          isLoadingRoute = false,
          onToggleSort = { sortOrder = PinSortOrder.NEWEST },
          onFocusPin = {},
          onToggleRoute = { id -> routeActiveId = if (routeActiveId == id) null else id },
          onRemovePin = { removedId = it },
          onClearAll = {},
          onDismiss = {},
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("pin_callout_card").assertIsDisplayed()
    composeRule.onNodeWithTag("pin_callout_route_button").performClick()
    assertEquals(1L, routeActiveId)
    composeRule.onNodeWithTag("pin_callout_remove_button").performClick()
    assertEquals(1L, removedId)
    composeRule.onNodeWithTag("pins_bottom_sheet").assertIsDisplayed()
    composeRule.onNodeWithTag("pins_sort_toggle").performClick()
    assertEquals(PinSortOrder.NEWEST, sortOrder)
    composeRule.onNodeWithTag("pins_clear_all_button").assertIsDisplayed()
  }
}
