package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.config.AppConfig
import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.LocationSnapshot
import com.example.data.model.MatchResult
import com.example.data.model.OffRoadSide
import com.example.data.model.OffRoadState
import com.example.data.model.TravelMode
import com.example.data.remote.DarkMapStyleGenerator
import com.example.data.remote.OverpassMapDataSource
import com.example.domain.MapMatcher
import com.example.ui.screens.RoadAwarenessScreenContent
import com.example.ui.theme.RoadAwarenessTheme
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DarkStyleAndOffRoadTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun config_containsNoKeyedOrPaidCartoStyleUrls() {
    assertFalse(AppConfig.MAP_STYLE_LIGHT_URL.contains("carto", ignoreCase = true))
    assertFalse(AppConfig.MAP_STYLE_LIGHT_URL.contains("apikey", ignoreCase = true))
    assertTrue(AppConfig.MAP_STYLE_LIGHT_URL.startsWith("https://"))
    assertEquals("", AppConfig.MAP_API_KEY_PLACEHOLDER)
    assertEquals("", AppConfig.OVERPASS_API_KEY_PLACEHOLDER)
    assertEquals("", AppConfig.NOMINATIM_API_KEY_PLACEHOLDER)
    assertEquals("", AppConfig.VALHALLA_API_KEY_PLACEHOLDER)
  }

  @Test
  fun darkStyleGenerator_transformsExpressionsAndSpecialCases() {
    val lightStyle =
      """
      {
        "version": 8,
        "light": {"color": "#FFFFFF"},
        "layers": [
          {"id": "background", "type": "background", "paint": {"background-color": "#F8F4F0"}},
          {"id": "water", "type": "fill", "source-layer": "water", "paint": {"fill-color": "rgb(170, 211, 223)"}},
          {"id": "building", "type": "fill", "source-layer": "building", "paint": {"fill-color": "hsl(30, 12%, 85%)"}},
          {"id": "road_casing", "type": "line", "source-layer": "transportation", "paint": {"line-color": "#CFCECA"}},
          {
            "id": "road_primary_fill",
            "type": "line",
            "source-layer": "transportation",
            "paint": {
              "line-color": [
                "interpolate",
                ["linear"],
                ["zoom"],
                12, "#FFFFFF",
                16, ["case", ["==", ["get", "brunnel"], "bridge"], "rgba(255, 255, 255, 0.9)", ["rgb", 240, 240, 235]]
              ]
            }
          },
          {"id": "highway_motorway", "type": "line", "source-layer": "transportation", "paint": {"line-color": "#FC8D49"}},
          {
            "id": "place_label",
            "type": "symbol",
            "paint": {
              "text-color": "#222222",
              "text-halo-color": "rgba(255, 255, 255, 0.85)"
            }
          }
        ]
      }
      """.trimIndent()

    val darkJson = DarkMapStyleGenerator.transformLightStyleToDarkJson(lightStyle)
    val root = JSONObject(darkJson)
    val layers = root.getJSONArray("layers")

    val bgHex = layers.getJSONObject(0).getJSONObject("paint").getString("background-color")
    val waterHex = layers.getJSONObject(1).getJSONObject("paint").getString("fill-color")
    val buildingHex = layers.getJSONObject(2).getJSONObject("paint").getString("fill-color")
    val casingHex = layers.getJSONObject(3).getJSONObject("paint").getString("line-color")
    val roadExpr = layers.getJSONObject(4).getJSONObject("paint").getJSONArray("line-color")
    val roadFillHex = roadExpr.getString(4)
    val bridgeCaseExpr = roadExpr.getJSONArray(6)
    val bridgeRgba = bridgeCaseExpr.getString(2)
    val nestedRgbHex = bridgeCaseExpr.getString(3)
    val motorwayHex = layers.getJSONObject(5).getJSONObject("paint").getString("line-color")
    val labelPaint = layers.getJSONObject(6).getJSONObject("paint")
    val textColor = labelPaint.getString("text-color")
    val textHalo = labelPaint.getString("text-halo-color")

    assertEquals("#12161C", bgHex)
    assertEquals("#1B2A3A", waterHex)
    assertTrue("Background must be dark (~#12161C), got $bgHex", luminance(bgHex) < 0.10)
    assertTrue("Water must be muted navy (~#1B2A3A), got $waterHex", luminance(waterHex) in 0.10..0.22)
    assertTrue("Buildings ($buildingHex) must be slightly lighter than bg ($bgHex)", luminance(buildingHex) > luminance(bgHex))
    assertTrue("Road fill ($roadFillHex) must be lighter than casing ($casingHex)", luminance(roadFillHex) > luminance(casingHex))
    assertTrue("Bridge rgba expression preserved alpha, got $bridgeRgba", bridgeRgba.startsWith("rgba("))
    assertTrue("Nested [rgb, ...] converted to hex color, got $nestedRgbHex", nestedRgbHex.startsWith("#"))
    assertTrue("Motorway stays warm orange/amber, got $motorwayHex", isWarmColor(motorwayHex))
    assertTrue("Label text near-white, got $textColor", luminance(textColor) > 0.55)
    assertTrue("Label halo dark, got $textHalo", textHalo.startsWith("rgba("))
    assertTrue("Top-level light color also transformed", root.getJSONObject("light").getString("color") != "#FFFFFF")
  }

  @Test
  fun softLightStyleGenerator_transformsColorsAndPreservesNonParkPlazas() {
    val lightStyle =
      """
      {
        "version": 8,
        "layers": [
          {"id": "background", "type": "background", "paint": {"background-color": "#FFFFFF"}},
          {"id": "water", "type": "fill", "source-layer": "water", "paint": {"fill-color": "#88BBEE"}},
          {"id": "landuse_park", "type": "fill", "source-layer": "park", "paint": {"fill-color": "#A8E6A3"}},
          {"id": "landuse_commercial_plaza", "type": "fill", "source-layer": "landuse", "paint": {"fill-color": "#F5F5F2"}},
          {"id": "building-3d", "type": "fill-extrusion", "source-layer": "building", "paint": {"fill-extrusion-color": "#CCCCCC", "fill-extrusion-opacity": 0.9}},
          {"id": "road_casing", "type": "line", "source-layer": "transportation", "paint": {"line-color": "#D0D0D0"}},
          {"id": "road_primary_fill", "type": "line", "source-layer": "transportation", "paint": {"line-color": "#FFCC33"}},
          {"id": "road_residential_fill", "type": "line", "source-layer": "transportation", "paint": {"line-color": "#EAEAEA"}},
          {"id": "highway_name_major", "type": "symbol", "source-layer": "transportation_name", "paint": {"text-color": "#444444", "text-halo-width": 1.0}}
        ]
      }
      """.trimIndent()

    val softJson = DarkMapStyleGenerator.transformLightStyleToSoftLightJson(lightStyle)
    val layers = JSONObject(softJson).getJSONArray("layers")

    assertEquals("#EDEFF3", layers.getJSONObject(0).getJSONObject("paint").getString("background-color"))
    assertEquals("#BCD6EE", layers.getJSONObject(1).getJSONObject("paint").getString("fill-color"))
    assertEquals("#D7EBD0", layers.getJSONObject(2).getJSONObject("paint").getString("fill-color"))
    val plazaHex = layers.getJSONObject(3).getJSONObject("paint").getString("fill-color")
    assertTrue("Non-park plaza landuse ($plazaHex) must NOT turn park green #D7EBD0", plazaHex != "#D7EBD0")
    val bldgPaint = layers.getJSONObject(4).getJSONObject("paint")
    assertEquals("#E1E4EA", bldgPaint.getString("fill-extrusion-color"))
    assertEquals(0.65, bldgPaint.getDouble("fill-extrusion-opacity"), 0.01)
    val casingHex = layers.getJSONObject(5).getJSONObject("paint").getString("line-color")
    val primaryHex = layers.getJSONObject(6).getJSONObject("paint").getString("line-color")
    val minorHex = layers.getJSONObject(7).getJSONObject("paint").getString("line-color")
    assertEquals("#FFFFFF", minorHex)
    assertTrue("Major road ($primaryHex) stays muted warm yellow/orange", isWarmColor(primaryHex))
    assertTrue("Road casing ($casingHex) darker than minor fill ($minorHex)", luminance(casingHex) < luminance(minorHex))
    val labelPaint = layers.getJSONObject(8).getJSONObject("paint")
    assertEquals("#1B2430", labelPaint.getString("text-color"))
    assertEquals("#FFFFFF", labelPaint.getString("text-halo-color"))
    assertEquals(2.0, labelPaint.getDouble("text-halo-width"), 0.01)
  }

  @Test
  fun mapMatcher_offRoad_scalesRadiusAndPicksDiverseCandidatesAndResolvesR2() {
    // User inside a courtyard/building at (30.0010, 31.0010)
    // Roads surrounding on North (~55m), East (~72m), West (~95m), plus a footpath at ~42m
    val json =
      """
      {
        "elements": [
          {"type": "node", "id": 1, "lat": 30.0015, "lon": 31.0000},
          {"type": "node", "id": 2, "lat": 30.0015, "lon": 31.0010},
          {"type": "node", "id": 3, "lat": 30.0015, "lon": 31.0020},
          {"type": "node", "id": 4, "lat": 30.0025, "lon": 31.0010},

          {"type": "node", "id": 10, "lat": 30.0000, "lon": 31.00175},
          {"type": "node", "id": 11, "lat": 30.0010, "lon": 31.00175},
          {"type": "node", "id": 12, "lat": 30.0020, "lon": 31.00175},
          {"type": "node", "id": 13, "lat": 30.0020, "lon": 31.0025},
          {"type": "node", "id": 14, "lat": 30.0030, "lon": 31.00175},

          {"type": "node", "id": 20, "lat": 30.0000, "lon": 31.0000},
          {"type": "node", "id": 21, "lat": 30.0020, "lon": 31.0000},

          {"type": "node", "id": 30, "lat": 30.0010, "lon": 31.00056},
          {"type": "node", "id": 31, "lat": 30.0018, "lon": 31.00056},

          {"type": "way", "id": 100, "nodes": [1, 2, 3], "tags": {"highway": "secondary", "name:en": "North Courtyard Ave"}},
          {"type": "way", "id": 101, "nodes": [2, 4], "tags": {"highway": "tertiary", "name:en": "Plaza Cross St"}},
          {"type": "way", "id": 200, "nodes": [10, 11, 12, 14], "tags": {"highway": "primary", "name:en": "East Boulevard"}},
          {"type": "way", "id": 201, "nodes": [12, 13], "tags": {"highway": "secondary", "name:en": "Harbor Spur"}},
          {"type": "way", "id": 300, "nodes": [20, 21], "tags": {"highway": "residential", "name:en": "West Alley"}},
          {"type": "way", "id": 400, "nodes": [30, 31], "tags": {"highway": "footway", "name:en": "Garden Path"}}
        ]
      }
      """.trimIndent()

    val graph = OverpassMapDataSource.parseOverpassJson(json, 0L, false)
    val insideCourtyard = GeoPoint(30.0010, 31.0010)

    // Accuracy scaling checks
    assertEquals(30.0, MapMatcher.computeOnRoadMatchRadius(5f), 0.1)
    assertEquals(44.0, MapMatcher.computeOnRoadMatchRadius(22f), 0.1)
    assertEquals(150.0, MapMatcher.computeOnRoadMatchRadius(120f), 0.1)

    // Driving off-road check: primary is null, finds R1..R3 from different sides, excludes footway
    val drivingMatch =
      MapMatcher.match(
        graph = graph,
        position = insideCourtyard,
        headingDeg = 0.0,
        mode = TravelMode.DRIVING,
        previousWayId = null,
        accuracyMeters = 6f,
        speedMps = 0.0,
      )
    assertNull(drivingMatch.primary)
    assertEquals(3, drivingMatch.offRoadCandidates.size)
    assertEquals(listOf("R1", "R2", "R3"), drivingMatch.offRoadCandidates.map { it.badgeId })
    assertTrue(drivingMatch.offRoadCandidates.none { it.candidate.way.id == 400L })
    assertEquals(3, drivingMatch.offRoadCandidates.map { it.side }.distinct().size)

    // Walking off-road check: includes Garden Path (footway) within smaller walking radius
    val walkingMatch =
      MapMatcher.match(
        graph = graph,
        position = insideCourtyard,
        headingDeg = 0.0,
        mode = TravelMode.WALKING,
        previousWayId = null,
        accuracyMeters = 6f,
        speedMps = 0.0,
      )
    assertNull(walkingMatch.primary)
    assertTrue(walkingMatch.offRoadSearchRadiusMeters <= AppConfig.OFFROAD_WALKING_MAX_RADIUS_M)
    assertTrue(walkingMatch.offRoadCandidates.any { it.candidate.way.id == 400L })

    // Selecting R2 (East Boulevard) while heading=90 (perpendicular) with unknown heading -> 2 direction groups
    val r2 = drivingMatch.offRoadCandidates[1]
    val perpResolution =
      MapMatcher.resolveOffRoadSelection(
        graph = graph,
        candidate = r2,
        headingDeg = 90.0,
        hasReliableHeading = true,
        mode = TravelMode.DRIVING,
        speedMps = 5.0,
        explicitForwardAlongWay = null,
      )
    assertTrue(perpResolution.needsDirectionChoice)
    assertEquals(2, perpResolution.directionGroups.size)

    // Choosing forward direction computes features along R2
    val chosenResolution =
      MapMatcher.resolveOffRoadSelection(
        graph = graph,
        candidate = r2,
        headingDeg = 90.0,
        hasReliableHeading = true,
        mode = TravelMode.DRIVING,
        speedMps = 5.0,
        explicitForwardAlongWay = true,
      )
    assertFalse(chosenResolution.activeFeatures.isEmpty())
    assertEquals(FeatureType.T_JUNCTION, chosenResolution.activeFeatures.first().type)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun offRoadUi_showsNearestRoads_allowsTapR2AndClear_andNoAttributionCollision() {
    val json =
      """
      {
        "elements": [
          {"type": "node", "id": 1, "lat": 30.0015, "lon": 31.0000},
          {"type": "node", "id": 2, "lat": 30.0015, "lon": 31.0020},
          {"type": "node", "id": 10, "lat": 30.0000, "lon": 31.0017},
          {"type": "node", "id": 11, "lat": 30.0020, "lon": 31.0017},
          {"type": "node", "id": 12, "lat": 30.0020, "lon": 31.0024},
          {"type": "way", "id": 100, "nodes": [1, 2], "tags": {"highway": "secondary", "name:en": "North Courtyard Ave"}},
          {"type": "way", "id": 200, "nodes": [10, 11], "tags": {"highway": "primary", "name:en": "East Boulevard"}},
          {"type": "way", "id": 201, "nodes": [11, 12], "tags": {"highway": "secondary", "name:en": "Harbor Spur"}}
        ]
      }
      """.trimIndent()
    val graph = OverpassMapDataSource.parseOverpassJson(json, 0L, false)
    val userPos = GeoPoint(30.0010, 31.0010)
    val match = MapMatcher.match(graph, userPos, 0.0, TravelMode.DRIVING, null, accuracyMeters = 5f)
    assertTrue("Expected 2..3 off-road candidates, got ${match.offRoadCandidates.size}", match.offRoadCandidates.size in 2..3)

    var offRoadState by mutableStateOf(
      OffRoadState(
        isOffRoad = true,
        reason = "Nearest road 56 m > match radius 30 m",
        searchRadiusMeters = match.offRoadSearchRadiusMeters,
        candidates = match.offRoadCandidates,
      ),
    )

    val baseState =
      com.example.ui.RoadHudUiState(
        hasLocationPermission = true,
        isGpsSearching = false,
        location = LocationSnapshot(userPos, 0.0, 0.0, 5f, true, 1000L, hasReliableHeading = true),
        travelMode = TravelMode.DRIVING,
        isMockModeActive = true,
        matchResult = MatchResult(null, null, false, 30.0, match.offRoadSearchRadiusMeters, match.offRoadCandidates),
        features = emptyList(),
        selectedFeatureIndex = 1,
      )

    composeRule.setContent {
      RoadAwarenessTheme {
        RoadAwarenessScreenContent(
          uiState = baseState.copy(offRoadState = offRoadState),
          onModeSelected = {},
          onSelectFeature = {},
          onSelectOffRoadCandidate = { wayId ->
            val cand = offRoadState.candidates.first { it.candidate.way.id == wayId }
            val resolved =
              MapMatcher.resolveOffRoadSelection(
                graph = graph,
                candidate = cand,
                headingDeg = 0.0,
                hasReliableHeading = true,
                mode = TravelMode.DRIVING,
                speedMps = 5.0,
                explicitForwardAlongWay = null,
              )
            offRoadState =
              offRoadState.copy(
                selectedCandidate = resolved.selectedCandidate,
                chosenForwardAlongWay = resolved.activeForwardAlongWay,
              )
          },
          onClearOffRoadSelection = {
            offRoadState = offRoadState.copy(selectedCandidate = null, chosenForwardAlongWay = null)
          },
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
          mapContent = { mod, _, _, _, _, _ -> Box(modifier = mod.background(Color(0xFF12161C))) },
        )
      }
    }

    composeRule.waitForIdle()
    composeRule.onNodeWithTag("off_road_chip").assertIsDisplayed()
    composeRule.onNodeWithTag("offroad_panel_title").assertIsDisplayed()
    composeRule.onNodeWithTag("offroad_candidate_row_R1").assertIsDisplayed()
    composeRule.onNodeWithTag("offroad_candidate_row_R2").assertIsDisplayed()
    composeRule.onNodeWithTag("map_attribution_button").assertIsDisplayed()

    // Ensure attribution chip and centered bottom pill never overlap horizontally on 360dp portrait phone
    val attrBounds = composeRule.onNodeWithTag("map_attribution_button").fetchSemanticsNode().boundsInRoot
    val pillBounds = composeRule.onNodeWithTag("bottom_action_pill").fetchSemanticsNode().boundsInRoot
    assertTrue(
      "Attribution (${attrBounds.right}) must not overlap bottom pill (${pillBounds.left})",
      attrBounds.right <= pillBounds.left + 1f,
    )

    // Tap R2 -> selects East Boulevard + displays Clear button; tapping Clear returns to Nearest roads
    composeRule.onNodeWithTag("offroad_candidate_row_R2").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("offroad_clear_button").assertIsDisplayed()
    composeRule.onNodeWithText("East Boulevard").assertIsDisplayed()

    composeRule.onNodeWithTag("offroad_clear_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("offroad_panel_title").assertIsDisplayed()
  }

  private fun luminance(hex: String): Double {
    val clean = hex.removePrefix("#")
    val r = clean.substring(0, 2).toInt(16) / 255.0
    val g = clean.substring(2, 4).toInt(16) / 255.0
    val b = clean.substring(4, 6).toInt(16) / 255.0
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
  }

  private fun isWarmColor(hex: String): Boolean {
    val clean = hex.removePrefix("#")
    val r = clean.substring(0, 2).toInt(16)
    val g = clean.substring(2, 4).toInt(16)
    val b = clean.substring(4, 6).toInt(16)
    return r > g && g >= b && abs(r - b) > 20
  }
}
