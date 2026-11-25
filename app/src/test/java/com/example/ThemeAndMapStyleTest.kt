package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
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
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SettingsRepository
import com.example.data.local.UserSettings
import com.example.data.model.DebugEdgeCategory
import com.example.data.model.GeoPoint
import com.example.data.model.LandscapePanelSide
import com.example.data.model.LocationSnapshot
import com.example.data.model.PanelStyle
import com.example.data.model.ThemePreference
import com.example.data.model.TravelMode
import com.example.ui.RoadHudUiState
import com.example.ui.components.SettingsBottomSheet
import com.example.ui.components.debugGraphEdgeColorHex
import com.example.ui.screens.RoadAwarenessScreenContent
import com.example.ui.theme.GlassTokens
import com.example.ui.theme.LocalRoadColors
import com.example.ui.theme.RoadAwarenessTheme
import com.example.ui.theme.RoadColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemeAndMapStyleTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun theme_alwaysDarkByDefaultOnLightMap_andFollowMapRestoresLight() {
    var panelStyle by mutableStateOf(PanelStyle.ALWAYS_DARK)
    var mapTheme by mutableStateOf(ThemePreference.LIGHT)
    var observedUiDark = false
    var observedMapDark = true
    var observedSurfaceColor = Color.Unspecified

    composeRule.setContent {
      RoadAwarenessTheme(themePreference = mapTheme, panelStyle = panelStyle) {
        observedUiDark = LocalRoadColors.current.isDark
        observedMapDark = LocalRoadColors.current.isMapDark
        observedSurfaceColor = MaterialTheme.colorScheme.surface
      }
    }

    composeRule.waitForIdle()
    assertTrue("Default ALWAYS_DARK must keep UI dark even with LIGHT map", observedUiDark)
    assertFalse("Map theme LIGHT must resolve isMapDark = false", observedMapDark)
    assertEquals(RoadColors.DarkCardSurface, observedSurfaceColor)

    panelStyle = PanelStyle.FOLLOW_MAP
    composeRule.waitForIdle()
    assertFalse("FOLLOW_MAP on LIGHT map must restore light UI", observedUiDark)
    assertFalse(observedMapDark)
    assertEquals(RoadColors.LightCardSurface, observedSurfaceColor)

    mapTheme = ThemePreference.DARK
    composeRule.waitForIdle()
    assertTrue("FOLLOW_MAP on DARK map must switch UI to dark", observedUiDark)
    assertTrue(observedMapDark)
    assertEquals(RoadColors.DarkCardSurface, observedSurfaceColor)
  }

  @Test
  fun glassTokens_darkOverLightMap_maintainsMinimum88PercentOpacity() {
    assertTrue(GlassTokens.DarkOverLightFillTop.alpha >= 0.88f)
    assertTrue(GlassTokens.DarkOverLightFillBottom.alpha >= 0.88f)
    assertTrue(GlassTokens.DarkOverLightCardFillTop.alpha >= 0.88f)
    assertTrue(GlassTokens.DarkOverLightCardFillBottom.alpha >= 0.88f)
  }

  @Test
  fun debugGraphEdgeColors_followMapStyle() {
    assertEquals("#0097A7", debugGraphEdgeColorHex(DebugEdgeCategory.TILE, isMapDark = false))
    assertEquals("#C2185B", debugGraphEdgeColorHex(DebugEdgeCategory.OVERPASS, isMapDark = false))
    assertEquals("#000000", debugGraphEdgeColorHex(DebugEdgeCategory.MATCHED, isMapDark = false))

    assertEquals("#00E5FF", debugGraphEdgeColorHex(DebugEdgeCategory.TILE, isMapDark = true))
    assertEquals("#FF2EDB", debugGraphEdgeColorHex(DebugEdgeCategory.OVERPASS, isMapDark = true))
    assertEquals("#FFFFFF", debugGraphEdgeColorHex(DebugEdgeCategory.MATCHED, isMapDark = true))
  }

  @Test
  fun settingsRepository_persistsPanelStyleAndTheme() = runBlocking {
    val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    val repo = SettingsRepository(ctx)
    assertEquals(PanelStyle.ALWAYS_DARK, repo.settingsFlow.first().panelStyle)

    repo.setTheme(ThemePreference.LIGHT)
    repo.setPanelStyle(PanelStyle.FOLLOW_MAP)
    val updated = repo.settingsFlow.first()
    assertEquals(ThemePreference.LIGHT, updated.themePreference)
    assertEquals(PanelStyle.FOLLOW_MAP, updated.panelStyle)

    repo.setPanelStyle(PanelStyle.ALWAYS_DARK)
    assertEquals(PanelStyle.ALWAYS_DARK, repo.settingsFlow.first().panelStyle)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w360dp-h800dp")
  fun settingsBottomSheet_showsMapStyleAndPanelStyle_andKeepsDarkSheetOnLightMap() {
    var selectedTheme = ThemePreference.AUTO
    var selectedPanelStyle = PanelStyle.ALWAYS_DARK
    var sheetUiDark = false

    val state =
      RoadHudUiState(
        isSettingsOpen = true,
        settings = UserSettings(themePreference = ThemePreference.LIGHT, panelStyle = PanelStyle.ALWAYS_DARK),
      )

    composeRule.setContent {
      RoadAwarenessTheme(
        themePreference = ThemePreference.LIGHT,
        panelStyle = PanelStyle.ALWAYS_DARK,
      ) {
        sheetUiDark = LocalRoadColors.current.isDark
        SettingsBottomSheet(
          uiState = state,
          onSelectTheme = { selectedTheme = it },
          onSelectPanelStyle = { selectedPanelStyle = it },
          onToggleExperimentalBlur = {},
          onToggleReduceTransparency = {},
          onToggleArabicTts = {},
          onToggleShowInspector = {},
          onToggleMockMode = {},
          onResetDebugOverrides = {},
          onClearCache = {},
          onDismiss = {},
        )
      }
    }

    composeRule.waitForIdle()
    assertTrue(sheetUiDark)
    composeRule.onNodeWithText("Map style").assertIsDisplayed()
    composeRule.onNodeWithText("Panel style").assertIsDisplayed()
    composeRule.onNodeWithTag("theme_auto_chip").assertIsDisplayed()
    composeRule.onNodeWithTag("theme_light_chip").assertIsDisplayed()
    composeRule.onNodeWithTag("theme_dark_chip").assertIsDisplayed()
    composeRule.onNodeWithTag("panel_style_always_dark_chip").assertIsDisplayed()
    composeRule.onNodeWithTag("panel_style_follow_map_chip").assertIsDisplayed()

    composeRule.onNodeWithTag("theme_dark_chip").performClick()
    composeRule.onNodeWithTag("panel_style_follow_map_chip").performClick()
    assertEquals(ThemePreference.DARK, selectedTheme)
    assertEquals(PanelStyle.FOLLOW_MAP, selectedPanelStyle)
  }

  @Test
  @Config(sdk = [36], qualifiers = "w800dp-h360dp")
  fun lightMap_darkUi_landscapeLeftAndRight_showsScrimsAndDarkPanel() {
    var panelSide by mutableStateOf(LandscapePanelSide.LEFT)
    var observedUiDark = false
    var observedMapDark = true

    val state =
      RoadHudUiState(
        hasLocationPermission = true,
        isGpsSearching = false,
        location = LocationSnapshot(GeoPoint(30.0444, 31.2357), 0.0, 12.0, 4f, true, 1000L),
        travelMode = TravelMode.DRIVING,
      )

    composeRule.setContent {
      RoadAwarenessTheme(
        themePreference = ThemePreference.LIGHT,
        panelStyle = PanelStyle.ALWAYS_DARK,
      ) {
        observedUiDark = LocalRoadColors.current.isDark
        observedMapDark = LocalRoadColors.current.isMapDark
        RoadAwarenessScreenContent(
          uiState =
            state.copy(
              settings =
                UserSettings(
                  themePreference = ThemePreference.LIGHT,
                  panelStyle = PanelStyle.ALWAYS_DARK,
                  landscapePanelSide = panelSide,
                ),
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
          mapContent = { mod, _, _, _, _, _ -> Box(modifier = mod.background(Color.White)) },
        )
      }
    }

    composeRule.waitForIdle()
    assertTrue(observedUiDark)
    assertFalse(observedMapDark)
    composeRule.onNodeWithTag("status_bar_scrim").assertIsDisplayed()
    composeRule.onNodeWithTag("nav_bar_scrim").assertIsDisplayed()
    composeRule.onNodeWithTag("illustration_panel").assertIsDisplayed()
    composeRule.onNodeWithTag("map_attribution_button").assertIsDisplayed()

    panelSide = LandscapePanelSide.RIGHT
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("illustration_panel").assertIsDisplayed()
    composeRule.onNodeWithTag("status_bar_scrim").assertIsDisplayed()
    composeRule.onNodeWithTag("nav_bar_scrim").assertIsDisplayed()
  }
}
