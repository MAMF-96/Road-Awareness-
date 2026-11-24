package com.example

import android.app.Application
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.UserSettings
import com.example.data.model.BilingualName
import com.example.data.model.BranchStatus
import com.example.data.model.DistanceKind
import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.LandscapePanelSide
import com.example.data.model.LocalPoint
import com.example.data.model.LocationSnapshot
import com.example.data.model.MatchResult
import com.example.data.model.OsmTags
import com.example.data.model.OsmWay
import com.example.data.model.RelativeDirection
import com.example.data.model.RoadBranch
import com.example.data.model.RoadCandidate
import com.example.data.model.RoadFeature
import com.example.data.model.SavedPin
import com.example.data.model.ThemePreference
import com.example.data.model.TravelMode
import com.example.ui.InspectorSheetDetent
import com.example.ui.RoadAwarenessViewModel
import com.example.ui.RoadHudUiState
import com.example.ui.components.TouchTelemetry
import com.example.ui.screens.RoadAwarenessScreenContent
import com.example.ui.theme.RoadAwarenessTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LayoutResponsivenessTest {

  @get:Rule val composeRule = createComposeRule()

  @Before
  fun resetTelemetry() {
    TouchTelemetry.reset()
  }

  private fun sampleUiState(): RoadHudUiState {
    val pts = listOf(GeoPoint(30.0, 31.0), GeoPoint(30.002, 31.0))
    val way = OsmWay(1L, listOf(1L, 2L), pts, OsmTags(nameAr = "الطريق المركزي", nameEn = "Central Highway", ref = "M12"))
    val primary =
      RoadCandidate(
        way = way,
        name = BilingualName("الطريق المركزي", "Central Highway", "M12"),
        confidence = 0.95,
        lateralOffsetMeters = 2.0,
        bearingDiffDeg = 3.0,
        snappedPoint = pts[0],
        segmentIndex = 0,
        forwardAlongWay = true,
      )
    val branches =
      listOf(
        RoadBranch(
          badgeId = "1A",
          wayId = 10L,
          name = BilingualName("طريق الميناء الغربي", "West Port Rd", null),
          leadsTo = "West Port",
          distanceMeters = 35.0,
          status = BranchStatus.OPEN,
          relativeDirection = RelativeDirection.LEFT,
          bearingDeltaDeg = -65.0,
          worldPoints = pts,
          localPoints = listOf(LocalPoint(0.0, 0.0), LocalPoint(-28.0, 22.0)),
          osmTags = OsmTags(highway = "secondary", nameEn = "West Port Rd"),
        ),
        RoadBranch(
          badgeId = "1B",
          wayId = 20L,
          name = BilingualName("الطريق المركزي", "Central Highway", "M12"),
          leadsTo = null,
          distanceMeters = 35.0,
          status = BranchStatus.UNVERIFIED,
          relativeDirection = RelativeDirection.AHEAD,
          bearingDeltaDeg = 0.0,
          worldPoints = pts,
          localPoints = listOf(LocalPoint(0.0, 0.0), LocalPoint(0.0, 32.0)),
          osmTags = OsmTags(highway = "primary", ref = "M12"),
        ),
        RoadBranch(
          badgeId = "1C",
          wayId = 30L,
          name = BilingualName("طريق المحجر القديم", "Old Quarry Rd", null),
          leadsTo = "East Hills",
          distanceMeters = 35.0,
          status = BranchStatus.BLOCKED,
          relativeDirection = RelativeDirection.RIGHT,
          bearingDeltaDeg = 70.0,
          worldPoints = pts,
          localPoints = listOf(LocalPoint(0.0, 0.0), LocalPoint(28.0, 20.0)),
          osmTags = OsmTags(highway = "residential", oneway = "-1"),
        ),
        RoadBranch(
          badgeId = "1D",
          wayId = 40L,
          name = BilingualName("شارع الخدمة", "Service Rd", null),
          leadsTo = null,
          distanceMeters = 35.0,
          status = BranchStatus.OPEN,
          relativeDirection = RelativeDirection.RIGHT,
          bearingDeltaDeg = 105.0,
          worldPoints = pts,
          localPoints = listOf(LocalPoint(0.0, 0.0), LocalPoint(24.0, -4.0)),
          osmTags = OsmTags(highway = "service"),
        ),
      )
    val feature =
      RoadFeature(
        index = 1,
        type = FeatureType.FOUR_WAY,
        distanceMeters = 35.0,
        anchor = pts[1],
        anchorNodeId = 2L,
        incomingLocalPoints = listOf(LocalPoint(0.0, -30.0), LocalPoint(0.0, 0.0)),
        incomingWorldPoints = pts,
        outgoingMainLocalPoints = emptyList(),
        outgoingMainWorldPoints = emptyList(),
        branches = branches,
        osmTags = way.tags,
      )
    return RoadHudUiState(
      hasLocationPermission = true,
      isGpsSearching = false,
      location = LocationSnapshot(pts[0], 0.0, 12.0, 4.0f, true, 1000L),
      travelMode = TravelMode.DRIVING,
      isMockModeActive = true,
      matchResult = MatchResult(primary, null, false),
      features = listOf(feature),
      selectedFeatureIndex = 1,
    )
  }

  private fun sampleSavedPin(): SavedPin =
    SavedPin(
      id = 99L,
      placeId = 9901L,
      nameAr = "ميدان التحرير",
      nameEn = "Tahrir Square",
      primaryName = "Midan El Tahrir",
      secondaryName = "Downtown, Cairo",
      placeType = "square",
      point = GeoPoint(30.0444, 31.2357),
      isStreet = false,
      createdAtMs = 1000L,
      distanceMeters = 420.0,
      distanceKind = DistanceKind.ROAD_SHORTEST,
    )

  private fun verifyPortraitViewport(
    themePreference: ThemePreference = ThemePreference.LIGHT,
    reduceTransparency: Boolean = false,
  ) {
    var mapHeight = 0f
    var topPadRecorded = 0f

    val ui =
      sampleUiState().copy(
        settings =
          UserSettings(
            themePreference = themePreference,
            reduceTransparencyEnabled = reduceTransparency,
          ),
      )

    composeRule.setContent {
      RoadAwarenessTheme(themePreference = themePreference) {
        RoadAwarenessScreenContent(
          uiState = ui,
          effectiveReduceTransparency = reduceTransparency,
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
          mapContent = { mod, topPadPx, _, _, _, _ ->
            topPadRecorded = topPadPx
            Box(
              modifier =
                mod
                  .background(Color(0xFFE5EEF8))
                  .onGloballyPositioned { coords ->
                    mapHeight = coords.boundsInRoot().height
                  },
            )
          },
        )
      }
    }

    composeRule.waitForIdle()
    composeRule.onNodeWithTag("illustration_panel").assertIsDisplayed()
    composeRule.onNodeWithTag("schematic_canvas").assertIsDisplayed()
    composeRule.onNodeWithTag("map_area_root").assertIsDisplayed()
    composeRule.onNodeWithTag("status_bar_scrim").assertIsDisplayed()
    composeRule.onNodeWithTag("bottom_action_pill").assertIsDisplayed()
    composeRule.onNodeWithTag("pill_search_button").assertIsDisplayed()
    composeRule.onNodeWithTag("pill_recenter_button").assertIsDisplayed()
    composeRule.onNodeWithTag("pill_settings_button").assertIsDisplayed()
    composeRule.onNodeWithTag("pill_debug_button").assertDoesNotExist()
    composeRule.onNodeWithTag("landscape_free_map_area").assertDoesNotExist()

    val panelNode = composeRule.onNodeWithTag("illustration_panel").fetchSemanticsNode()
    val mapAreaNode = composeRule.onNodeWithTag("map_area_root").fetchSemanticsNode()
    val panelBottom = panelNode.boundsInRoot.bottom
    val rootHeight = mapAreaNode.boundsInRoot.height

    assertTrue("Edge-to-edge map ($mapHeight) must fill full root ($rootHeight)", abs(mapHeight - rootHeight) <= 2f)
    assertTrue("Floating top card ($panelBottom) must stay <= 40% of screen ($rootHeight)", panelBottom <= rootHeight * 0.405f)
    assertTrue("Camera top padding ($topPadRecorded) must clear floating card ($panelBottom)", topPadRecorded >= panelBottom)
    composeRule.onNodeWithTag("bad_gps_chip").assertDoesNotExist()
    composeRule.onNodeWithTag("offline_chip").assertDoesNotExist()
    composeRule.onNodeWithTag("uncertain_chip").assertDoesNotExist()
    composeRule.onNodeWithTag("upcoming_features_panel").assertDoesNotExist()
  }

  private fun verifyLandscapeViewport(
    panelSideSetting: LandscapePanelSide?,
    expectLeftSide: Boolean,
    expectTablet: Boolean,
    themePreference: ThemePreference = ThemePreference.LIGHT,
    withPin: Boolean = false,
  ) {
    var mapHeight = 0f
    var mapWidth = 0f
    var leftPadRecorded = 0f
    var rightPadRecorded = 0f
    var topPadRecorded = 0f
    var botPadRecorded = 0f
    var landscapeFlagRecorded = false

    val ui =
      sampleUiState().copy(
        savedPins = if (withPin) listOf(sampleSavedPin()) else emptyList(),
        settings =
          UserSettings(
            themePreference = themePreference,
            landscapePanelSide = panelSideSetting,
          ),
      )

    composeRule.setContent {
      RoadAwarenessTheme(themePreference = themePreference) {
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
          mapContent = { mod, topPadPx, botPadPx, leftPadPx, rightPadPx, isLandscape ->
            topPadRecorded = topPadPx
            botPadRecorded = botPadPx
            leftPadRecorded = leftPadPx
            rightPadRecorded = rightPadPx
            landscapeFlagRecorded = isLandscape
            Box(
              modifier =
                mod
                  .background(Color(0xFFE5EEF8))
                  .onGloballyPositioned { coords ->
                    val b = coords.boundsInRoot()
                    mapHeight = b.height
                    mapWidth = b.width
                  },
            )
          },
        )
      }
    }

    composeRule.waitForIdle()
    assertTrue("Map content must receive isLandscape=true", landscapeFlagRecorded)
    composeRule.onNodeWithTag("illustration_panel").assertIsDisplayed()
    composeRule.onNodeWithTag("schematic_canvas").assertIsDisplayed()
    composeRule.onNodeWithTag("landscape_free_map_area").assertIsDisplayed()
    composeRule.onNodeWithTag("bottom_action_pill").assertIsDisplayed()
    composeRule.onNodeWithTag("pill_search_button").assertIsDisplayed()
    composeRule.onNodeWithTag("pill_recenter_button").assertIsDisplayed()
    composeRule.onNodeWithTag("pill_settings_button").assertIsDisplayed()
    composeRule.onNodeWithTag("panel_chevron_toggle").assertIsDisplayed()

    val rootBounds = composeRule.onNodeWithTag("map_area_root").fetchSemanticsNode().boundsInRoot
    val panelBounds = composeRule.onNodeWithTag("illustration_panel").fetchSemanticsNode().boundsInRoot
    val freeBounds = composeRule.onNodeWithTag("landscape_free_map_area").fetchSemanticsNode().boundsInRoot
    val schematicBounds = composeRule.onNodeWithTag("schematic_canvas").fetchSemanticsNode().boundsInRoot
    val pillBounds = composeRule.onNodeWithTag("bottom_action_pill").fetchSemanticsNode().boundsInRoot
    val drivingBounds = composeRule.onNodeWithTag("mode_driving_button").fetchSemanticsNode().boundsInRoot

    // 1. Edge-to-edge map
    assertTrue("Edge-to-edge map must fill root ($mapWidth x $mapHeight vs ${rootBounds.width} x ${rootBounds.height})",
      abs(mapHeight - rootBounds.height) <= 2f && abs(mapWidth - rootBounds.width) <= 2f)

    // 2. Side panel dimensions: clamp(34%, 300dp, 420dp), full height minus 8dp margins
    val expectedWidth = (rootBounds.width * 0.34f).coerceIn(300f, 420f)
    assertTrue("Side panel width (${panelBounds.width}) expected ~$expectedWidth", abs(panelBounds.width - expectedWidth) <= 3f)
    assertTrue("Side panel height (${panelBounds.height}) expected ~${rootBounds.height - 16f}",
      abs(panelBounds.height - (rootBounds.height - 16f)) <= 4f)

    // 3. Panel side & camera padding
    if (expectLeftSide) {
      assertTrue("Panel should be on LEFT (panel.right=${panelBounds.right} <= free.left=${freeBounds.left})",
        panelBounds.right <= freeBounds.left + 1f)
      assertTrue("Left camera padding ($leftPadRecorded) must clear panel width + 8dp (${panelBounds.width + 8f})",
        leftPadRecorded >= panelBounds.width + 8f)
    } else {
      assertTrue("Panel should be on RIGHT (panel.left=${panelBounds.left} >= free.right=${freeBounds.right})",
        panelBounds.left >= freeBounds.right - 1f)
      assertTrue("Right camera padding ($rightPadRecorded) must clear panel width + 8dp (${panelBounds.width + 8f})",
        rightPadRecorded >= panelBounds.width + 8f)
    }
    assertTrue("Top camera padding ($topPadRecorded) must be positive", topPadRecorded > 0f)
    assertTrue("Bottom camera padding ($botPadRecorded) must be positive", botPadRecorded > 0f)

    // 4. Schematic keeps 1:1 ratio and 30-40% of panel height (min 84dp)
    assertTrue("Schematic keeps 1:1 ratio (${schematicBounds.width} x ${schematicBounds.height})",
      abs(schematicBounds.width - schematicBounds.height) <= 4f)
    assertTrue("Schematic height (${schematicBounds.height}) >= 84dp", schematicBounds.height >= 83f)
    assertTrue("Schematic height (${schematicBounds.height}) <= 42% panel height (${panelBounds.height})",
      schematicBounds.height <= panelBounds.height * 0.42f)

    // 5. Pill centered horizontally in FREE map area
    val freeCenterX = (freeBounds.left + freeBounds.right) / 2f
    val pillCenterX = (pillBounds.left + pillBounds.right) / 2f
    assertTrue("Bottom pill center ($pillCenterX) must match FREE map area center ($freeCenterX)",
      abs(pillCenterX - freeCenterX) <= 4f)
    assertTrue("Status strip mode capsule (${drivingBounds.top}) must be at top of free area",
      drivingBounds.top <= 60f)

    // 6. Branch packing / tablet upcoming features
    val branch1A = composeRule.onNodeWithTag("branch_row_1A").fetchSemanticsNode().boundsInRoot
    val branch1B = composeRule.onNodeWithTag("branch_row_1B").fetchSemanticsNode().boundsInRoot
    assertTrue("Branch 1A must not clip below panel", branch1A.bottom <= panelBounds.bottom)
    assertTrue("Branch 1B must not clip below panel", branch1B.bottom <= panelBounds.bottom)

    if (expectTablet) {
      composeRule.onNodeWithTag("landscape_expanded_branches_list").performScrollToNode(hasTestTag("upcoming_features_panel"))
      composeRule.onNodeWithTag("upcoming_features_panel").assertIsDisplayed()
    } else {
      val allFourFit =
        composeRule.onAllNodes(hasTestTag("branch_row_1D")).fetchSemanticsNodes().isNotEmpty()
      if (allFourFit) {
        val branch1D = composeRule.onNodeWithTag("branch_row_1D").fetchSemanticsNode().boundsInRoot
        assertTrue("Branch 1D must not clip below panel", branch1D.bottom <= panelBounds.bottom)
        composeRule.onNodeWithTag("more_branches_chip").assertDoesNotExist()
      } else {
        composeRule.onNodeWithTag("more_branches_chip").assertIsDisplayed()
        val moreBounds = composeRule.onNodeWithTag("more_branches_chip").fetchSemanticsNode().boundsInRoot
        assertTrue("More chip must not clip below panel", moreBounds.bottom <= panelBounds.bottom)
      }
    }

    if (withPin) {
      composeRule.onNodeWithTag("floating_pins_button").assertIsDisplayed()
      val pinsBounds = composeRule.onNodeWithTag("floating_pins_button").fetchSemanticsNode().boundsInRoot
      val freeMidY = (freeBounds.top + freeBounds.bottom) / 2f
      val pinsMidY = (pinsBounds.top + pinsBounds.bottom) / 2f
      assertTrue("Pins button must sit near vertical center of free area", abs(pinsMidY - freeMidY) <= 16f)
      if (expectLeftSide) {
        assertTrue("Pins button must be at RIGHT edge when panel is LEFT", pinsBounds.right >= freeBounds.right - 20f)
      } else {
        assertTrue("Pins button must be at LEFT edge when panel is RIGHT", pinsBounds.left <= freeBounds.left + 20f)
      }
    }
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun phonePortrait_360x800() {
    verifyPortraitViewport(themePreference = ThemePreference.LIGHT, reduceTransparency = false)
    composeRule.onNodeWithTag("panel_drag_handle").performClick()
    composeRule.waitForIdle()
    val panelNode = composeRule.onNodeWithTag("illustration_panel").fetchSemanticsNode()
    val mapArea = composeRule.onNodeWithTag("map_area_root").fetchSemanticsNode()
    val totalH = mapArea.boundsInRoot.height
    assertTrue(
      "Expanded floating card (${panelNode.boundsInRoot.bottom}) must stay <= 40% of root ($totalH)",
      panelNode.boundsInRoot.bottom <= totalH * 0.405f,
    )
  }

  @Test
  @Config(sdk = [36], qualifiers = "w412dp-h915dp")
  fun phonePortrait_darkAndReduceTransparency() {
    verifyPortraitViewport(themePreference = ThemePreference.DARK, reduceTransparency = true)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w800dp-h360dp")
  fun phoneLandscape_800x360_leftSideAndChevronExpand() {
    verifyLandscapeViewport(
      panelSideSetting = LandscapePanelSide.LEFT,
      expectLeftSide = true,
      expectTablet = false,
      themePreference = ThemePreference.LIGHT,
    )
    composeRule.onNodeWithTag("panel_chevron_toggle").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("landscape_expanded_branches_list").performScrollToNode(hasTestTag("branch_row_1D"))
    composeRule.onNodeWithTag("branch_row_1D").assertIsDisplayed()
  }

  @Test
  @Config(sdk = [36], qualifiers = "w800dp-h360dp")
  fun phoneLandscape_800x360_rightSideDark() {
    verifyLandscapeViewport(
      panelSideSetting = LandscapePanelSide.RIGHT,
      expectLeftSide = false,
      expectTablet = false,
      themePreference = ThemePreference.DARK,
      withPin = true,
    )
  }

  @Test
  @Config(sdk = [36], qualifiers = "w740dp-h360dp")
  fun phoneLandscape_740x360_withPinsButton() {
    verifyLandscapeViewport(
      panelSideSetting = LandscapePanelSide.LEFT,
      expectLeftSide = true,
      expectTablet = false,
      themePreference = ThemePreference.LIGHT,
      withPin = true,
    )
  }

  @Test
  @Config(sdk = [36], qualifiers = "ar-rEG-w960dp-h440dp")
  fun phoneLandscape_960x440_arabicRtlDefaultsRight() {
    verifyLandscapeViewport(
      panelSideSetting = null,
      expectLeftSide = false,
      expectTablet = false,
      themePreference = ThemePreference.LIGHT,
      withPin = true,
    )
  }

  @Test
  @Config(sdk = [36], qualifiers = "w960dp-h440dp")
  fun phoneLandscape_960x440_anchoredSearchSheetKeepsPanelVisible() {
    val ui = sampleUiState().copy(isSearchOpen = true)
    composeRule.setContent {
      RoadAwarenessTheme(themePreference = ThemePreference.LIGHT) {
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
    composeRule.onNodeWithTag("illustration_panel").assertIsDisplayed()
    composeRule.onNodeWithTag("search_anchored_sheet").assertIsDisplayed()
    val panelBounds = composeRule.onNodeWithTag("illustration_panel").fetchSemanticsNode().boundsInRoot
    val sheetBounds = composeRule.onNodeWithTag("search_anchored_sheet").fetchSemanticsNode().boundsInRoot
    val rootBounds = composeRule.onNodeWithTag("map_area_root").fetchSemanticsNode().boundsInRoot
    assertTrue("Anchored search sheet width <= 560dp", sheetBounds.width <= 562f)
    assertTrue("Anchored search sheet height <= 60% of screen", sheetBounds.height <= rootBounds.height * 0.605f)
    assertTrue("Anchored sheet must not overlap side panel", sheetBounds.left >= panelBounds.right - 1f)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w800dp-h1280dp")
  fun tabletPortrait_800x1280() {
    verifyPortraitViewport(themePreference = ThemePreference.LIGHT, reduceTransparency = false)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w1280dp-h800dp")
  fun tabletLandscape_1280x800() {
    verifyLandscapeViewport(
      panelSideSetting = LandscapePanelSide.LEFT,
      expectLeftSide = true,
      expectTablet = true,
      themePreference = ThemePreference.DARK,
      withPin = true,
    )
  }

  @Test
  @Config(sdk = [36], qualifiers = "w1600dp-h900dp")
  fun tabletLandscape_1600x900() {
    verifyLandscapeViewport(
      panelSideSetting = LandscapePanelSide.RIGHT,
      expectLeftSide = false,
      expectTablet = true,
      themePreference = ThemePreference.LIGHT,
      withPin = true,
    )
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun inspectorBottomSheet_singleTapSettings_halfFullCollapsedAndTapTest() {
    var isSettingsOpen by mutableStateOf(false)
    var isInspectorOpen by mutableStateOf(false)
    var detent by mutableStateOf(InspectorSheetDetent.HALF)
    var mapTapped = false

    val state =
      sampleUiState().copy(
        debugOffline = true,
        loadingStallReason = "Overpass request failed: timeout",
      )

    composeRule.setContent {
      RoadAwarenessTheme {
        RoadAwarenessScreenContent(
          uiState =
            state.copy(
              isSettingsOpen = isSettingsOpen,
              isInspectorOpen = isInspectorOpen,
              inspectorDetent = detent,
            ),
          onModeSelected = {},
          onSelectFeature = {},
          onGrantLocationClick = {},
          onOpenSearch = {},
          onRecenter = {},
          onOpenSettings = { isSettingsOpen = it },
          onSearchQueryChange = {},
          onExecuteSearch = {},
          onSelectPlace = {},
          onSelectTheme = {},
          onToggleArabicTts = {},
          onToggleShowInspector = { isInspectorOpen = it },
          onInspectorDetentChange = { detent = it },
          onMockHeadingChange = {},
          onToggleMockMode = {},
          onDebugFlagsChange = { _, _, _ -> },
          onClearCache = {},
          mapContent = { mod, _, _, _, _, _ ->
            Box(modifier = mod.clickable { mapTapped = true }.background(Color(0xFFE5EEF8)))
          },
        )
      }
    }

    composeRule.waitForIdle()
    composeRule.onNodeWithTag("graph_inspector_sheet").assertDoesNotExist()

    // Single tap on Settings opens Settings sheet, then single tap opens Inspector
    composeRule.onNodeWithTag("pill_settings_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("show_inspector_switch").performScrollTo().performClick()
    composeRule.waitForIdle()
    composeRule.runOnIdle { isSettingsOpen = false }
    composeRule.waitForIdle()

    val sheetHalf = composeRule.onNodeWithTag("graph_inspector_sheet").fetchSemanticsNode()
    val halfHandle = composeRule.onNodeWithTag("inspector_drag_handle").fetchSemanticsNode()
    assertTrue("Inspector drag handle must be >= 46dp (${halfHandle.boundsInRoot.height})", halfHandle.boundsInRoot.height >= 46f)
    assertTrue("Controls (Half) state (${sheetHalf.boundsInRoot.height}) must be <= 345dp", sheetHalf.boundsInRoot.height <= 345f)

    composeRule.onNodeWithTag("inspector_controls_container").assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_heading_slider").assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_snap_road_button").assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_controls_row").assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_touch_telemetry").assertIsDisplayed()
    composeRule.onNodeWithTag("debug_override_badge").assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_copy_report_button").assertIsDisplayed()
    composeRule.onNodeWithTag("close_inspector_button").assertIsDisplayed()

    // Tapping map outside sheet still works (between top card and bottom sheet)
    val panelBounds = composeRule.onNodeWithTag("illustration_panel").fetchSemanticsNode().boundsInRoot
    val exposedMapY = (panelBounds.bottom + sheetHalf.boundsInRoot.top) / 2f
    val exposedMapX = (sheetHalf.boundsInRoot.left + sheetHalf.boundsInRoot.right) / 2f
    composeRule.onNodeWithTag("map_area_root").performTouchInput { click(Offset(exposedMapX, exposedMapY)) }
    composeRule.waitForIdle()
    assertTrue("Map area outside sheet/card must stay clickable", mapTapped)

    // Tap drag handle in Controls -> opens Details (FULL)
    composeRule.onNodeWithTag("inspector_drag_handle").performClick()
    composeRule.waitForIdle()
    val sheetFull = composeRule.onNodeWithTag("graph_inspector_sheet").fetchSemanticsNode()
    assertTrue("Full state (${sheetFull.boundsInRoot.height}) must be <= 72% of 800dp", sheetFull.boundsInRoot.height <= 800f * 0.725f)
    composeRule.onNodeWithTag("inspector_details_container").assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_diagnostic_reason").assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_name_source").assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_touch_telemetry").assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_tap_test_section").performScrollTo().assertIsDisplayed()

    // Verify all 9 Tap test buttons respond on a single click + slider moves smoothly
    for (idx in 1..9) {
      val tag = "tap_test_btn_$idx"
      composeRule.onNodeWithTag(tag).performScrollTo().performClick()
      composeRule.waitForIdle()
      composeRule.onNodeWithText("#$idx (1)").assertIsDisplayed()
      assertEquals(tag, TouchTelemetry.debugLastControlId.value)
    }
    composeRule.onNodeWithTag("tap_test_slider").performScrollTo().performTouchInput { swipeRight() }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("tap_test_slider_value").assertIsDisplayed()

    composeRule.onNodeWithTag("inspector_simulation_section").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_block_overpass_switch").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_block_tiles_switch").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_slow_network_switch").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_force_error_dropdown").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_cache_stats").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_clear_caches_button").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_test_overpass_button").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_reset_overrides_button").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_feature_row_1").performScrollTo().assertIsDisplayed()
    composeRule.onNodeWithTag("inspector_branch_tags_1_1A").performScrollTo().assertIsDisplayed()

    // Details -> Controls -> Collapsed -> Close
    composeRule.onNodeWithTag("inspector_expand_toggle_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("inspector_controls_container").assertIsDisplayed()
    composeRule.runOnIdle { detent = InspectorSheetDetent.COLLAPSED }
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("inspector_collapsed_bar").assertIsDisplayed()
    val sheetCollapsed = composeRule.onNodeWithTag("graph_inspector_sheet").fetchSemanticsNode()
    val pillNode = composeRule.onNodeWithTag("bottom_action_pill").fetchSemanticsNode()
    assertTrue("Collapsed bar height ~48dp", abs(sheetCollapsed.boundsInRoot.height - 48f) <= 4f)
    assertTrue("Sheet bottom (${sheetCollapsed.boundsInRoot.bottom}) must stay above pill (${pillNode.boundsInRoot.top})",
      sheetCollapsed.boundsInRoot.bottom <= pillNode.boundsInRoot.top)

    composeRule.onNodeWithTag("close_inspector_button").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("graph_inspector_sheet").assertDoesNotExist()
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun drivingMode_fiveSecondPillFade_firstTapStillFiresAction() {
    var searchOpenedCount = 0
    val ui = sampleUiState().copy(travelMode = TravelMode.DRIVING)
    composeRule.setContent {
      RoadAwarenessTheme {
        RoadAwarenessScreenContent(
          uiState = ui,
          onModeSelected = {},
          onSelectFeature = {},
          onGrantLocationClick = {},
          onOpenSearch = { if (it) searchOpenedCount++ },
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

    composeRule.mainClock.advanceTimeBy(5_600L)
    composeRule.waitForIdle()

    // First touch while faded MUST perform the control action directly
    composeRule.onNodeWithTag("pill_search_button").performClick()
    composeRule.waitForIdle()
    assertEquals("First tap after 5s Driving fade must fire immediately", 1, searchOpenedCount)
    assertEquals("pill_search_button", TouchTelemetry.debugLastControlId.value)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun touchTelemetry_recordsDispatchTouchEventWithoutConsuming() {
    val startWake = TouchTelemetry.wakeTick.longValue
    val now = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 120f, 240f, 0)
    val up = MotionEvent.obtain(now, now + 15L, MotionEvent.ACTION_UP, 120f, 240f, 0)
    try {
      TouchTelemetry.onDispatchTouchEvent(down)
      assertEquals("DOWN", TouchTelemetry.debugLastTouchAction.value)
      assertEquals(startWake + 1L, TouchTelemetry.wakeTick.longValue)

      TouchTelemetry.onDispatchTouchEvent(up)
      assertEquals("UP", TouchTelemetry.debugLastTouchAction.value)
      assertTrue(TouchTelemetry.debugLastTouchLatencyMs.longValue >= 0L)
    } finally {
      down.recycle()
      up.recycle()
    }
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun badStateChips_appearOnlyWhenDegraded() {
    val degraded =
      sampleUiState().copy(
        debugPoorGps = true,
        debugOffline = true,
        debugUncertain = true,
      )
    composeRule.setContent {
      RoadAwarenessTheme {
        RoadAwarenessScreenContent(
          uiState = degraded,
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
    composeRule.onNodeWithTag("bad_gps_chip").assertIsDisplayed()
    composeRule.onNodeWithTag("offline_chip").assertIsDisplayed()
    composeRule.onNodeWithTag("uncertain_chip").assertIsDisplayed()
  }

  @Test
  @Config(sdk = [36])
  fun viewModel_slidingHeading_enforcesMockMode() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val vm = RoadAwarenessViewModel(app)
    vm.onMapTappedInInspector(GeoPoint(35.6812, 139.7671))
    composeRule.waitForIdle()

    vm.setMockModeEnabled(false)
    composeRule.waitForIdle()
    assertFalse("Mock mode should turn OFF when toggled off", vm.uiState.value.isMockModeActive)

    vm.onMockHeadingChanged(147.0)
    composeRule.waitForIdle()
    assertTrue("Sliding heading must enforce mock mode ON", vm.uiState.value.isMockModeActive)
    assertEquals(147.0, vm.uiState.value.location?.headingDeg ?: -1.0, 0.5)
    assertEquals(true, vm.uiState.value.location?.isMock)
  }
}
