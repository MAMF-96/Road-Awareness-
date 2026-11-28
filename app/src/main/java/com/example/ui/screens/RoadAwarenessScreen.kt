package com.example.ui.screens

import android.Manifest
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.data.model.ChaseCameraDistance
import com.example.data.model.DeveloperOrientation
import com.example.data.model.FeatureCensusEntry
import com.example.data.model.FoundRoadMatch
import com.example.data.model.LadderOrder
import com.example.data.model.LadderTextSize
import com.example.data.model.LandscapePanelSide
import com.example.data.model.NameOrder
import com.example.data.model.PanelStyle
import com.example.data.model.QualitySetting
import com.example.data.model.SavedPin
import com.example.data.model.SearchPlaceResult
import com.example.data.model.SearchScope
import com.example.data.model.TestSpot
import com.example.data.model.ThemePreference
import com.example.data.model.TravelMode
import com.example.data.model.UnitPreference
import com.example.ui.FrameQualityMonitorEffect
import com.example.ui.InspectorSheetDetent
import com.example.ui.RoadAwarenessViewModel
import com.example.ui.RoadHudUiState
import com.example.ui.components.CompactModeSegmentedControl
import com.example.ui.components.DebugTouchRingsOverlay
import com.example.ui.components.FloatingActionPill
import com.example.ui.components.FloatingPinsButton
import com.example.ui.components.FloatingStatusChipsStrip
import com.example.ui.components.FoundRoadCalloutCard
import com.example.ui.components.GraphInspectorBottomSheet
import com.example.ui.components.IllustrationPanel
import com.example.ui.components.LocalNameOrder
import com.example.ui.components.MapLibreHudView
import com.example.ui.components.PinCalloutCard
import com.example.ui.components.PinsBottomSheet
import com.example.ui.components.SearchBottomSheet
import com.example.ui.components.SettingsBottomSheet
import com.example.ui.components.TouchTelemetry
import com.example.ui.components.TuneCameraSheet
import com.example.ui.components.WholeRoadSheet
import com.example.ui.components.formatRoadDistance
import com.example.ui.components.innerPressScale
import com.example.ui.components.rememberPressHapticInteractionSource
import com.example.ui.theme.GlassRenderConfig
import com.example.ui.theme.GlassSurface
import com.example.ui.theme.LocalGlassConfig
import com.example.ui.theme.LocalRoadColors
import com.example.ui.theme.RoadColors
import com.example.ui.theme.isSystemReduceTransparencyEnabled
import com.example.ui.theme.resolveIsMapDark
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RoadAwarenessScreen(
  viewModel: RoadAwarenessViewModel,
  windowSizeClass: WindowSizeClass,
) {
  val context = LocalContext.current
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val inspectorOnMsg = stringResource(R.string.snackbar_inspector_on)
  val inspectorOffMsg = stringResource(R.string.snackbar_inspector_off)
  val copiedMsg = stringResource(R.string.inspector_copied)
  val zoomInTapMsg = stringResource(R.string.zoom_in_tap_road)

  val permissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
      val granted =
        grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
          grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
      viewModel.onPermissionResult(granted)
    }

  val notifPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
      // Background tracking runs gracefully with or without notification grant
    }

  LaunchedEffect(Unit) {
    val fine =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val coarse =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (fine || coarse) {
      viewModel.onPermissionResult(true)
    } else {
      permissionLauncher.launch(
        arrayOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
      )
    }
  }

  FrameQualityMonitorEffect(
    enabled = uiState.settings.qualitySetting == QualitySetting.AUTO,
    onLowQualityChanged = { low -> viewModel.updateAutoLowQuality(low) },
  )

  var mapFoundTargetOffScreen by remember { mutableStateOf(false) }

  BackHandler(
    enabled =
      uiState.isWholeRoadOpen ||
        uiState.isTuneCameraOpen ||
        uiState.isSearchOpen ||
        uiState.isPinsSheetOpen ||
        uiState.isSettingsOpen ||
        uiState.isInspectorOpen ||
        uiState.selectedPinId != null ||
        uiState.activeFoundRoad != null ||
        uiState.offRoadState.selectedCandidate != null,
  ) {
    when {
      uiState.isWholeRoadOpen -> viewModel.openWholeRoad(false)
      uiState.isTuneCameraOpen -> viewModel.openTuneCamera(false)
      uiState.isSearchOpen -> viewModel.openSearch(false)
      uiState.isPinsSheetOpen -> viewModel.openPinsSheet(false)
      uiState.isSettingsOpen -> viewModel.openSettings(false)
      uiState.isInspectorOpen -> {
        when (uiState.inspectorDetent) {
          InspectorSheetDetent.FULL -> viewModel.setInspectorDetent(InspectorSheetDetent.HALF)
          InspectorSheetDetent.HALF -> viewModel.setInspectorDetent(InspectorSheetDetent.COLLAPSED)
          InspectorSheetDetent.COLLAPSED -> viewModel.setInspectorOpen(false)
        }
      }
      uiState.selectedPinId != null -> viewModel.selectPinForCallout(null)
      uiState.activeFoundRoad != null -> viewModel.clearFoundRoadMatch()
      uiState.offRoadState.selectedCandidate != null -> viewModel.clearOffRoadSelection()
    }
  }

  // Frame-time jank detector when experimental real glass blur is active
  var jankFallbackActive by remember(uiState.settings.experimentalBlurEnabled) { mutableStateOf(false) }
  val systemReduceTransparency = remember(context, uiState.settings.reduceTransparencyEnabled) {
    isSystemReduceTransparencyEnabled(context)
  }
  val effectiveReduceTransparency =
    uiState.settings.reduceTransparencyEnabled || systemReduceTransparency || jankFallbackActive
  val experimentalBlurRequested =
    Build.VERSION.SDK_INT >= 31 &&
      uiState.settings.experimentalBlurEnabled &&
      !effectiveReduceTransparency &&
      !uiState.settings.performanceModeEnabled

  LaunchedEffect(experimentalBlurRequested) {
    if (!experimentalBlurRequested) return@LaunchedEffect
    var prevNanos = 0L
    var slowCount = 0
    var sampleWindow = 0
    while (true) {
      withFrameNanos { frameNanos ->
        if (prevNanos > 0L) {
          val deltaMs = (frameNanos - prevNanos) / 1_000_000L
          sampleWindow++
          if (deltaMs in 35L..500L) {
            slowCount++
          }
          if (sampleWindow >= 30) {
            if (slowCount >= 8) {
              Log.w(
                "GlassPerf",
                "Detected sustained frame jank ($slowCount/30 slow frames) with glass blur; switching to solid surfaces.",
              )
              jankFallbackActive = true
            }
            slowCount = 0
            sampleWindow = 0
          }
        }
        prevNanos = frameNanos
      }
    }
  }

  val widthClassName =
    when (windowSizeClass.widthSizeClass) {
      WindowWidthSizeClass.Compact -> "Compact"
      WindowWidthSizeClass.Medium -> "Medium"
      WindowWidthSizeClass.Expanded -> "Expanded"
      else -> windowSizeClass.widthSizeClass.toString()
    }
  val heightClassName =
    when (windowSizeClass.heightSizeClass) {
      WindowHeightSizeClass.Compact -> "Compact"
      WindowHeightSizeClass.Medium -> "Medium"
      WindowHeightSizeClass.Expanded -> "Expanded"
      else -> windowSizeClass.heightSizeClass.toString()
    }
  val windowSizeClassSummary = "W:$widthClassName H:$heightClassName"
  var hoistedPanelExpanded by rememberSaveable { mutableStateOf<Boolean?>(null) }

  RoadAwarenessScreenContent(
    uiState = uiState,
    windowSizeClassSummary = windowSizeClassSummary,
    effectiveReduceTransparency = effectiveReduceTransparency,
    realGlassBlurActive = experimentalBlurRequested,
    snackbarHostState = snackbarHostState,
    isPanelExpandedOverride = hoistedPanelExpanded,
    onPanelExpandedChange = { hoistedPanelExpanded = it },
    onModeSelected = { viewModel.setTravelMode(it) },
    onSelectFeature = { viewModel.selectFeature(it) },
    onSelectOffRoadCandidate = { viewModel.selectOffRoadCandidate(it) },
    onSelectOffRoadDirection = { viewModel.selectOffRoadDirection(it) },
    onClearOffRoadSelection = { viewModel.clearOffRoadSelection() },
    onSelectSimulationBranch = { viewModel.selectSimulationBranch(it) },
    onGrantLocationClick = {
      permissionLauncher.launch(
        arrayOf(
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
        )
      )
    },
    onOpenSearch = { viewModel.openSearch(it) },
    onRecenter = { viewModel.recenter() },
    onOpenSettings = { viewModel.openSettings(it) },
    onSettingsLongClick = {
      if (BuildConfig.DEBUG) {
        val nowOpen = viewModel.toggleInspectorFromShortcut()
        scope.launch {
          snackbarHostState.currentSnackbarData?.dismiss()
          snackbarHostState.showSnackbar(if (nowOpen) inspectorOnMsg else inspectorOffMsg)
        }
      }
    },
    onSearchQueryChange = { viewModel.updateSearchQuery(it) },
    onExecuteSearch = { viewModel.executeSearch() },
    onSelectPlace = { viewModel.selectSearchPlace(it) },
    onSearchScopeChange = { viewModel.setSearchScope(it) },
    onSelectFoundMatch = { viewModel.selectFoundRoadMatch(it) },
    onSelectRecentQuery = { viewModel.selectRecentSearchQuery(it) },
    onOverviewFoundRoad = { viewModel.focusOverviewOnFoundRoad() },
    onToggleFoundRoadRoute = { viewModel.toggleRouteToFoundRoad() },
    onClearFoundRoad = { viewModel.clearFoundRoadMatch() },
    foundTargetOffScreenOverride = mapFoundTargetOffScreen,
    onOpenPins = { viewModel.openPinsSheet(it) },
    onTogglePinSort = { viewModel.togglePinSortOrder() },
    onFocusPin = { viewModel.focusOnPin(it) },
    onTogglePinRoute = { viewModel.routeToPin(it) },
    onRemovePin = { viewModel.removePin(it) },
    onClearAllPins = { viewModel.clearAllPins() },
    onDismissPinCallout = { viewModel.selectPinForCallout(null) },
    onSelectTheme = { viewModel.setThemePreference(it) },
    onSelectPanelStyle = { viewModel.setPanelStyle(it) },
    onToggleExperimentalBlur = { viewModel.setExperimentalBlurEnabled(it) },
    onToggleReduceTransparency = { viewModel.setReduceTransparencyEnabled(it) },
    onToggleArabicTts = { viewModel.setArabicTtsEnabled(it) },
    onSelectDeveloperOrientation = { viewModel.setDeveloperOrientation(it) },
    onSelectLandscapePanelSide = { viewModel.setLandscapePanelSide(it) },
    onToggleShowInspector = { viewModel.setInspectorOpen(it, openInHalf = false) },
    onInspectorDetentChange = { viewModel.setInspectorDetent(it) },
    onMockHeadingChange = { viewModel.onMockHeadingChanged(it) },
    onSnapHeadingToRoad = { viewModel.snapHeadingToMatchedRoad() },
    onToggleMockMode = { viewModel.setMockModeEnabled(it) },
    onStepAlongRoad = { viewModel.stepMockAlongRoad(it) },
    onToggleSimulateDrive = { viewModel.toggleSimulateDrive() },
    onMockSpeedChange = { viewModel.setMockSpeedKmh(it) },
    onMockAccuracyChange = { viewModel.setMockAccuracyMeters(it) },
    onMockJitterChange = { viewModel.setMockJitterEnabled(it) },
    onSaveSpot = { viewModel.saveTestSpot(it) },
    onLoadSpot = { viewModel.loadTestSpot(it) },
    onDeleteSpot = { viewModel.deleteTestSpot(it) },
    onRestoreDefaultSpots = { viewModel.restoreDefaultTestSpots() },
    onOpenInspectorSpots = { viewModel.openInspectorToTestSpots() },
    onDebugFlagsChange = { poor, offline, uncertain ->
      viewModel.setDebugStatusFlags(poor, offline, uncertain)
    },
    onDebugNetworkSimulationChange = { blockOverpass, blockTiles, slowNet, forceErr ->
      viewModel.setDebugNetworkSimulation(blockOverpass, blockTiles, slowNet, forceErr)
    },
    onTestOverpass = { viewModel.testOverpass() },
    onPingOverpass = { viewModel.pingOverpass() },
    onResetDebugOverrides = { viewModel.resetDebugOverrides() },
    onClearCache = { viewModel.clearCache() },
    onJumpToCensusFeature = { viewModel.jumpToCensusFeature(it) },
    onSaveCensusAsSpot = { viewModel.saveCensusEntryAsSpot(it) },
    onRetryRoadData = { viewModel.retryRoadData() },
    onReloadRoadData = { viewModel.reloadAllRoadData() },
    onSelectUnitPreference = { viewModel.setUnitPreference(it) },
    onSelectHorizon = { viewModel.setHorizonDistanceMeters(it) },
    onToggleShowMinorJunctions = { viewModel.setShowMinorJunctions(it) },
    onToggleLandmarksAhead = { viewModel.setLandmarksAheadEnabled(it) },
    onSelectLadderOrder = { viewModel.setLadderOrder(it) },
    onToggleChaseCamera = { viewModel.setChaseCameraEnabled(it) },
    onSelectChaseCameraDistance = { viewModel.setChaseCameraDistance(it) },
    onOpenTuneCamera = { viewModel.openTuneCamera(true) },
    onCloseTuneCamera = { viewModel.openTuneCamera(false) },
    onUpdateChaseCameraTuning = { zoomTrim, tiltDeg, posFrac, leanPct ->
      viewModel.updateChaseCameraTuning(
        zoomTrim = zoomTrim,
        tiltOverrideDeg = tiltDeg,
        vehiclePosFraction = posFrac,
        curveLeanPercent = leanPct,
      )
    },
    onResetChaseCameraTuning = { viewModel.resetChaseCameraTuning() },
    onTogglePerformanceMode = { viewModel.setPerformanceModeEnabled(it) },
    onSelectQualitySetting = { viewModel.setQualitySetting(it) },
    onToggleKeepScreenOn = { viewModel.setKeepScreenOn(it) },
    onToggleBackgroundTracking = { enabled ->
      if (enabled && Build.VERSION.SDK_INT >= 33) {
        val granted =
          ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
          notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
      }
      viewModel.setBackgroundTrackingWhileDriving(enabled)
    },
    onSelectLadderTextSize = { viewModel.setLadderTextSize(it) },
    onSelectNameOrder = { viewModel.setNameOrder(it) },
    onToggleShowDataSourceChips = { viewModel.setShowDataSourceChips(it) },
    onToggleOverview = { viewModel.toggleOverviewMode() },
    onToggleShowGraphEdges = { viewModel.setShowGraphEdges(it) },
    onOpenWholeRoad = { viewModel.openWholeRoad(it) },
    onDisplayContextChanged = { widthDp, isCarOrTv ->
      viewModel.updateDisplayContext(widthDp, isCarOrTv)
    },
    onReportCopied = {
      scope.launch {
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(copiedMsg)
      }
    },
    mapContent = { mod, topPadPx, botPadPx, leftPadPx, rightPadPx, landscapeFlag ->
      key(experimentalBlurRequested) {
        MapLibreHudView(
          uiState = uiState,
          useTextureMode = experimentalBlurRequested,
          isLandscape = landscapeFlag,
          isPanelExpanded = hoistedPanelExpanded == true,
          cameraPaddingTopPx = topPadPx,
          cameraPaddingBottomPx = botPadPx,
          cameraPaddingLeftPx = leftPadPx,
          cameraPaddingRightPx = rightPadPx,
          onFeaturePinClick = { idx -> viewModel.selectFeature(idx) },
          onOffRoadCandidateClick = { wayId -> viewModel.selectOffRoadCandidate(wayId) },
          onSavedPinClick = { pinId -> viewModel.selectPinForCallout(pinId) },
          onMapTap = { pt -> viewModel.onMapTappedInInspector(pt) },
          onZoomRequiredForTap = {
            scope.launch {
              snackbarHostState.currentSnackbarData?.dismiss()
              snackbarHostState.showSnackbar(zoomInTapMsg)
            }
          },
          onUserPan = { viewModel.onUserPannedMap() },
          onStyleErrorChange = { err -> viewModel.reportMapStyleError(err) },
          onTileRoadHintChanged = { hint -> viewModel.onTileRoadHintChanged(hint) },
          onLiveMapVectorFallback = { features -> viewModel.ingestLiveMapVectorFeatures(features) },
          onLiveMapVectorFallbackWithCount = { features, queried ->
            viewModel.ingestLiveMapVectorFeatures(features, queried)
          },
          onFoundTargetOffScreenChange = { off -> mapFoundTargetOffScreen = off },
          modifier = mod,
        )
      }
    },
  )
}

/**
 * Full-screen edge-to-edge map behind Liquid Glass floating info card, floating status strip + mode capsule,
 * pin callout/Pins sheet, 3-button floating pill, unobtrusive attribution control, and optional 3-state debug inspector bottom sheet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoadAwarenessScreenContent(
  uiState: RoadHudUiState,
  windowSizeClassSummary: String = "",
  effectiveReduceTransparency: Boolean = uiState.settings.reduceTransparencyEnabled,
  realGlassBlurActive: Boolean = false,
  snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
  onModeSelected: (TravelMode) -> Unit,
  onSelectFeature: (Int) -> Unit,
  onSelectOffRoadCandidate: (Long) -> Unit = {},
  onSelectOffRoadDirection: (Boolean) -> Unit = {},
  onClearOffRoadSelection: () -> Unit = {},
  onSelectSimulationBranch: (Long) -> Unit = {},
  onGrantLocationClick: () -> Unit,
  onOpenSearch: (Boolean) -> Unit,
  onRecenter: () -> Unit,
  onOpenSettings: (Boolean) -> Unit,
  @Suppress("UNUSED_PARAMETER") onSettingsLongClick: () -> Unit = {},
  onSearchQueryChange: (String) -> Unit,
  onExecuteSearch: () -> Unit,
  onSelectPlace: (SearchPlaceResult) -> Unit,
  onSearchScopeChange: (SearchScope) -> Unit = {},
  onSelectFoundMatch: (FoundRoadMatch) -> Unit = {},
  onSelectRecentQuery: (String) -> Unit = {},
  onOverviewFoundRoad: () -> Unit = {},
  onToggleFoundRoadRoute: () -> Unit = {},
  onClearFoundRoad: () -> Unit = {},
  foundTargetOffScreenOverride: Boolean? = null,
  onOpenPins: (Boolean) -> Unit = {},
  onTogglePinSort: () -> Unit = {},
  onFocusPin: (SavedPin) -> Unit = {},
  onTogglePinRoute: (Long) -> Unit = {},
  onRemovePin: (Long) -> Unit = {},
  onClearAllPins: () -> Unit = {},
  onDismissPinCallout: () -> Unit = {},
  onSelectTheme: (ThemePreference) -> Unit,
  onSelectPanelStyle: (PanelStyle) -> Unit = {},
  onToggleExperimentalBlur: (Boolean) -> Unit = {},
  onToggleReduceTransparency: (Boolean) -> Unit = {},
  onToggleArabicTts: (Boolean) -> Unit,
  onSelectDeveloperOrientation: (DeveloperOrientation) -> Unit = {},
  onSelectLandscapePanelSide: (LandscapePanelSide) -> Unit = {},
  onToggleShowInspector: (Boolean) -> Unit,
  onInspectorDetentChange: (InspectorSheetDetent) -> Unit,
  onMockHeadingChange: (Double) -> Unit,
  onSnapHeadingToRoad: () -> Unit = {},
  onToggleMockMode: (Boolean) -> Unit,
  onStepAlongRoad: (Double) -> Unit = {},
  onToggleSimulateDrive: () -> Unit = {},
  onMockSpeedChange: (Double) -> Unit = {},
  onMockAccuracyChange: (Float) -> Unit = {},
  onMockJitterChange: (Boolean) -> Unit = {},
  onSaveSpot: (String) -> Unit = {},
  onLoadSpot: (TestSpot) -> Unit = {},
  onDeleteSpot: (String) -> Unit = {},
  onRestoreDefaultSpots: () -> Unit = {},
  onOpenInspectorSpots: () -> Unit = {},
  onDebugFlagsChange: (Boolean, Boolean, Boolean) -> Unit,
  onDebugNetworkSimulationChange: (Boolean, Boolean, Boolean, Int?) -> Unit = { _, _, _, _ -> },
  onTestOverpass: () -> Unit = {},
  onPingOverpass: () -> Unit = {},
  onResetDebugOverrides: () -> Unit = {},
  onClearCache: () -> Unit,
  onJumpToCensusFeature: (FeatureCensusEntry) -> Unit = {},
  onSaveCensusAsSpot: (FeatureCensusEntry) -> Unit = {},
  onRetryRoadData: () -> Unit = {},
  onReloadRoadData: () -> Unit = {},
  onSelectUnitPreference: (UnitPreference) -> Unit = {},
  onSelectHorizon: (Double) -> Unit = {},
  onToggleShowMinorJunctions: (Boolean) -> Unit = {},
  onToggleLandmarksAhead: (Boolean) -> Unit = {},
  onSelectLadderOrder: (LadderOrder) -> Unit = {},
  onToggleChaseCamera: (Boolean) -> Unit = {},
  onSelectChaseCameraDistance: (ChaseCameraDistance) -> Unit = {},
  onOpenTuneCamera: () -> Unit = {},
  onCloseTuneCamera: () -> Unit = {},
  onUpdateChaseCameraTuning: (Double?, Double?, Double?, Int?) -> Unit = { _, _, _, _ -> },
  onResetChaseCameraTuning: () -> Unit = {},
  onTogglePerformanceMode: (Boolean) -> Unit = {},
  onSelectQualitySetting: (QualitySetting) -> Unit = {},
  onToggleKeepScreenOn: (Boolean) -> Unit = {},
  onToggleBackgroundTracking: (Boolean) -> Unit = {},
  onSelectLadderTextSize: (LadderTextSize) -> Unit = {},
  onSelectNameOrder: (NameOrder) -> Unit = {},
  onToggleShowDataSourceChips: (Boolean) -> Unit = {},
  onToggleOverview: () -> Unit = {},
  onToggleShowGraphEdges: (Boolean) -> Unit = {},
  onOpenWholeRoad: (Boolean) -> Unit = {},
  onDisplayContextChanged: (Int, Boolean) -> Unit = { _, _ -> },
  onReportCopied: () -> Unit = {},
  isPanelExpandedOverride: Boolean? = null,
  onPanelExpandedChange: ((Boolean) -> Unit)? = null,
  mapContent: @Composable (
    modifier: Modifier,
    topPadPx: Float,
    botPadPx: Float,
    leftPadPx: Float,
    rightPadPx: Float,
    isLandscape: Boolean,
  ) -> Unit,
) {
  val wakeTick by TouchTelemetry.wakeTick
  var controlsVisible by rememberSaveable { mutableStateOf(true) }
  var showAttributionDialog by rememberSaveable { mutableStateOf(false) }

  // Auto-fade floating controls after 5s in Driving mode unless a sheet is open
  LaunchedEffect(
    uiState.travelMode,
    wakeTick,
    uiState.isWholeRoadOpen,
    uiState.isSearchOpen,
    uiState.isPinsSheetOpen,
    uiState.isSettingsOpen,
    uiState.isInspectorOpen,
  ) {
    controlsVisible = true
    if (uiState.travelMode == TravelMode.DRIVING &&
      !uiState.isWholeRoadOpen &&
      !uiState.isSearchOpen &&
      !uiState.isPinsSheetOpen &&
      !uiState.isSettingsOpen &&
      !uiState.isInspectorOpen
    ) {
      delay(5_000L)
      controlsVisible = false
    }
  }

  val pillAlpha by animateFloatAsState(
    targetValue = if (controlsVisible || uiState.isInspectorOpen) 1f else 0f,
    animationSpec = tween(durationMillis = 240),
    label = "pillFadeAlpha",
  )

  val density = LocalDensity.current
  val layoutDir = LocalLayoutDirection.current
  val isDark = LocalRoadColors.current.isDark
  val isMapDark = resolveIsMapDark(uiState.settings.themePreference)
  val effectiveRealBlur = realGlassBlurActive && !uiState.isEffectiveLowQuality
  val hazeState = remember { HazeState() }
  val glassConfig =
    remember(
      isDark,
      isMapDark,
      effectiveReduceTransparency,
      effectiveRealBlur,
      uiState.isEffectiveLowQuality,
      hazeState,
    ) {
      GlassRenderConfig(
        isDark = isDark,
        isMapDark = isMapDark,
        reduceTransparency = effectiveReduceTransparency,
        useRealBlur = effectiveRealBlur,
        lowQuality = uiState.isEffectiveLowQuality,
        hazeState = if (effectiveRealBlur) hazeState else null,
      )
    }

  val safePadding = WindowInsets.safeDrawing.asPaddingValues()
  val statusTopDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
  val safeTopDp = maxOf(statusTopDp, safePadding.calculateTopPadding())
  val navBottomPx = with(density) { safePadding.calculateBottomPadding().toPx() }
  val safeLeftPx = with(density) { safePadding.calculateLeftPadding(layoutDir).toPx() }
  val safeRightPx = with(density) { safePadding.calculateRightPadding(layoutDir).toPx() }

  CompositionLocalProvider(
    LocalGlassConfig provides glassConfig,
    LocalNameOrder provides uiState.settings.nameOrder,
  ) {
    BoxWithConstraints(
      modifier = Modifier.fillMaxSize(),
    ) {
      val totalHeight = maxHeight
      val totalWidth = maxWidth
      val winWidthDp = maxWidth.value.roundToInt()
      val winHeightDp = maxHeight.value.roundToInt()
      val totalHeightPx = with(density) { maxHeight.toPx() }
      val isLandscape = maxWidth > maxHeight
      val shortestSide = minOf(maxWidth, maxHeight)
      val isTablet = shortestSide >= 600.dp

      val screenContext = LocalContext.current
      val isCarOrTvMode = remember(screenContext) {
        val uiModeMgr = screenContext.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val mode = uiModeMgr?.currentModeType ?: Configuration.UI_MODE_TYPE_NORMAL
        mode == Configuration.UI_MODE_TYPE_CAR || mode == Configuration.UI_MODE_TYPE_TELEVISION
      }
      LaunchedEffect(winWidthDp, isCarOrTvMode) {
        onDisplayContextChanged(winWidthDp, isCarOrTvMode)
      }

      val safeLeftDp = safePadding.calculateLeftPadding(LayoutDirection.Ltr)
      val safeRightDp = safePadding.calculateRightPadding(LayoutDirection.Ltr)
      val safeBottomDp = safePadding.calculateBottomPadding()
      val safeLeftSidePx = with(density) { safeLeftDp.toPx() }
      val safeRightSidePx = with(density) { safeRightDp.toPx() }

      var internalPanelExpanded by rememberSaveable(isTablet) { mutableStateOf(isTablet) }
      val isPanelExpanded = isPanelExpandedOverride ?: internalPanelExpanded
      LaunchedEffect(isTablet) {
        if (isPanelExpandedOverride == null) {
          onPanelExpandedChange?.invoke(isTablet)
        }
      }

      var cardBottomPx by remember(isLandscape) { mutableFloatStateOf(0f) }
      var stripBottomPx by remember(isLandscape) { mutableFloatStateOf(0f) }
      var calloutBottomPx by remember(isLandscape) { mutableFloatStateOf(0f) }
      var pillTopPx by remember(isLandscape) { mutableFloatStateOf(totalHeightPx) }
      var sheetTopPx by remember(isLandscape) { mutableFloatStateOf(totalHeightPx) }

      if (uiState.selectedPin == null && uiState.activeFoundRoad == null && calloutBottomPx != 0f) {
        calloutBottomPx = 0f
      }

      val gap8Px = with(density) { 8.dp.toPx() }
      val gap6Px = with(density) { 6.dp.toPx() }

      // Landscape side-panel sizing and animated Left <-> Right swap
      val resolvedPanelSide =
        uiState.settings.landscapePanelSide
          ?: if (layoutDir == LayoutDirection.Rtl) LandscapePanelSide.RIGHT else LandscapePanelSide.LEFT
      val panelOnLeft = resolvedPanelSide == LandscapePanelSide.LEFT
      val swapProgress by animateFloatAsState(
        targetValue = if (panelOnLeft) 0f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "landscapePanelSwap",
      )

      val landscapePanelWidthDp = (totalWidth * 0.45f).coerceIn(320.dp, 560.dp)
      val landscapePanelWidthPx = with(density) { landscapePanelWidthDp.toPx() }
      val panelLeftPosDp = safeLeftDp + 8.dp
      val panelRightPosDp = (totalWidth - safeRightDp - 8.dp - landscapePanelWidthDp).coerceAtLeast(safeLeftDp)
      val animatedPanelXDp = panelLeftPosDp + (panelRightPosDp - panelLeftPosDp) * swapProgress

      val freeLeftWhenPanelLeft = panelLeftPosDp + landscapePanelWidthDp + 8.dp
      val freeRightWhenPanelLeft = totalWidth - safeRightDp
      val freeLeftWhenPanelRight = safeLeftDp
      val freeRightWhenPanelRight = (panelRightPosDp - 8.dp).coerceAtLeast(safeLeftDp)
      val freeStartDp = freeLeftWhenPanelLeft + (freeLeftWhenPanelRight - freeLeftWhenPanelLeft) * swapProgress
      val freeEndDp = freeRightWhenPanelLeft + (freeRightWhenPanelRight - freeRightWhenPanelLeft) * swapProgress
      val freeWidthDp = (freeEndDp - freeStartDp).coerceAtLeast(180.dp)

      val cameraTopPadPx: Float
      val cameraBotPadPx: Float
      val cameraLeftPadPx: Float
      val cameraRightPadPx: Float

      val effectiveOffScreen =
        uiState.activeFoundRoad != null &&
          !uiState.isOverviewMode &&
          (foundTargetOffScreenOverride ?: false)
      val hasCalloutVisible = uiState.selectedPin != null || uiState.activeFoundRoad != null

      if (isLandscape) {
        val freeTopBasePx =
          if (hasCalloutVisible && calloutBottomPx > stripBottomPx) calloutBottomPx
          else stripBottomPx
        cameraTopPadPx = (freeTopBasePx + gap8Px).coerceAtLeast(with(density) { (safeTopDp + 8.dp).toPx() })
        cameraBotPadPx =
          when {
            BuildConfig.DEBUG && uiState.isInspectorOpen && sheetTopPx < totalHeightPx ->
              (totalHeightPx - sheetTopPx + gap8Px).coerceAtLeast(navBottomPx)
            (controlsVisible || uiState.isInspectorOpen) && pillTopPx < totalHeightPx ->
              (totalHeightPx - pillTopPx + gap8Px).coerceAtLeast(navBottomPx)
            else -> navBottomPx
          }
        val panelReservedLeftPx = (safeLeftSidePx + gap8Px + landscapePanelWidthPx + gap8Px) * (1f - swapProgress)
        val panelReservedRightPx = (safeRightSidePx + gap8Px + landscapePanelWidthPx + gap8Px) * swapProgress
        cameraLeftPadPx = maxOf(safeLeftSidePx, panelReservedLeftPx)
        cameraRightPadPx = maxOf(safeRightSidePx, panelReservedRightPx)
      } else {
        val cardAndStripBottom = maxOf(cardBottomPx, stripBottomPx)
        val topBasePx =
          if (hasCalloutVisible && calloutBottomPx > cardAndStripBottom) calloutBottomPx
          else cardAndStripBottom
        cameraTopPadPx = (topBasePx + gap8Px).coerceAtLeast(with(density) { safeTopDp.toPx() })
        cameraBotPadPx =
          when {
            BuildConfig.DEBUG && uiState.isInspectorOpen && sheetTopPx < totalHeightPx ->
              (totalHeightPx - sheetTopPx + gap8Px).coerceAtLeast(navBottomPx)
            controlsVisible && pillTopPx < totalHeightPx ->
              (totalHeightPx - pillTopPx + gap8Px).coerceAtLeast(navBottomPx)
            else -> navBottomPx
          }
        cameraLeftPadPx = safeLeftPx
        cameraRightPadPx = safeRightPx
      }

      // 1. Full-screen edge-to-edge Map filling the entire window
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .let { if (effectiveRealBlur) it.hazeSource(state = hazeState) else it }
            .testTag("map_area_root"),
      ) {
        mapContent(
          Modifier.fillMaxSize(),
          cameraTopPadPx,
          cameraBotPadPx,
          cameraLeftPadPx,
          cameraRightPadPx,
          isLandscape,
        )
      }

      // 2. Dark gradient scrims under status bar (~32dp) and above gesture bar (~24dp) in both map styles
      Box(
        modifier =
          Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(safeTopDp + 32.dp)
            .background(
              Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0f)),
              ),
            )
            .testTag("status_bar_scrim"),
      )
      Box(
        modifier =
          Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(safeBottomDp + 24.dp)
            .background(
              Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0f), Color.Black.copy(alpha = 0.40f)),
              ),
            )
            .testTag("nav_bar_scrim"),
      )

      if (isLandscape) {
        // LANDSCAPE: Floating side info card + FREE map area
        val panelTopDp = safeTopDp + 8.dp
        val panelBotDp = safeBottomDp + 8.dp

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
          Box(modifier = Modifier.fillMaxSize()) {
            // Side Panel positioned physically at animatedPanelXDp
            Box(
              modifier =
                Modifier
                  .align(AbsoluteAlignment.TopLeft)
                  .offset(x = animatedPanelXDp)
                  .padding(top = panelTopDp, bottom = panelBotDp)
                  .width(landscapePanelWidthDp)
                  .fillMaxHeight(),
            ) {
              CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                IllustrationPanel(
                  uiState = uiState,
                  maxScreenHeight = totalHeight,
                  outerVerticalMargin = panelTopDp + panelBotDp,
                  isLandscape = true,
                  isTablet = isTablet,
                  isExpanded = isPanelExpanded,
                  onToggleExpand = { expanded ->
                    internalPanelExpanded = expanded
                    onPanelExpandedChange?.invoke(expanded)
                  },
                  onSelectFeature = onSelectFeature,
                  onSelectOffRoadCandidate = onSelectOffRoadCandidate,
                  onSelectOffRoadDirection = onSelectOffRoadDirection,
                  onClearOffRoadSelection = onClearOffRoadSelection,
                  onSelectSimulationBranch = onSelectSimulationBranch,
                  onRetryRoadData = onRetryRoadData,
                  onOpenInspectorSpots = onOpenInspectorSpots,
                  onToggleOverview = onToggleOverview,
                  windowWidthDp = winWidthDp,
                  onOpenWholeRoad = { onOpenWholeRoad(true) },
                  modifier = Modifier.fillMaxSize(),
                )
              }
            }

            // FREE Map Area container
            Box(
              modifier =
                Modifier
                  .align(AbsoluteAlignment.TopLeft)
                  .offset(x = freeStartDp)
                  .width(freeWidthDp)
                  .fillMaxHeight()
                  .testTag("landscape_free_map_area"),
            ) {
              CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                Box(modifier = Modifier.fillMaxSize()) {
                  // Status strip at top of FREE area: chips at Start, mode capsule at End
                  Row(
                    modifier =
                      Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(top = safeTopDp + 8.dp, start = 8.dp, end = 8.dp)
                        .onGloballyPositioned { coords ->
                          stripBottomPx = coords.boundsInRoot().bottom
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Box(
                      modifier =
                        Modifier
                          .weight(1f)
                          .padding(end = 6.dp),
                    ) {
                      FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                      ) {
                        FloatingStatusChipsStrip(
                          uiState = uiState,
                          onGrantLocationClick = onGrantLocationClick,
                        )
                      }
                    }
                    CompactModeSegmentedControl(
                      selectedMode = uiState.travelMode,
                      compactHeight = 48.dp,
                      onSelect = onModeSelected,
                    )
                  }

                  // Optional Selected Pin or Found-on-my-road Callout below status strip inside FREE area
                  val selectedPin = uiState.selectedPin
                  val activeFound = uiState.activeFoundRoad
                  val calloutTopDp = with(density) { (stripBottomPx + gap8Px).toDp() }.coerceAtLeast(safeTopDp + 60.dp)
                  if (selectedPin != null) {
                    PinCalloutCard(
                      pin = selectedPin,
                      isRouteActive = uiState.activeRoutePinId == selectedPin.id,
                      isLoadingRoute = uiState.isLoadingRoute && uiState.activeRoutePinId == selectedPin.id,
                      onToggleRoute = { onTogglePinRoute(selectedPin.id) },
                      onRemove = { onRemovePin(selectedPin.id) },
                      onDismiss = onDismissPinCallout,
                      unitSystem = uiState.effectiveUnitSystem,
                      modifier =
                        Modifier
                          .align(Alignment.TopCenter)
                          .padding(top = calloutTopDp, start = 8.dp, end = 8.dp)
                          .widthIn(max = 560.dp)
                          .onGloballyPositioned { coords ->
                            calloutBottomPx = coords.boundsInRoot().bottom
                          },
                    )
                  } else if (activeFound != null) {
                    FoundRoadCalloutCard(
                      found = activeFound,
                      isRouteActive = uiState.isRoutingToFoundRoad,
                      isLoadingRoute = uiState.isLoadingRoute && uiState.isRoutingToFoundRoad,
                      onOverview = onOverviewFoundRoad,
                      onToggleRoute = onToggleFoundRoadRoute,
                      onClear = onClearFoundRoad,
                      unitSystem = uiState.effectiveUnitSystem,
                      modifier =
                        Modifier
                          .align(Alignment.TopCenter)
                          .padding(top = calloutTopDp, start = 8.dp, end = 8.dp)
                          .widthIn(max = 560.dp)
                          .onGloballyPositioned { coords ->
                            calloutBottomPx = coords.boundsInRoot().bottom
                          },
                    )
                  }

                  if (activeFound != null && effectiveOffScreen) {
                    val edgeTopDp =
                      with(density) {
                        ((if (calloutBottomPx > stripBottomPx) calloutBottomPx else stripBottomPx) + gap6Px).toDp()
                      }.coerceAtLeast(calloutTopDp)
                    FoundTargetOffScreenIndicator(
                      found = activeFound,
                      unitSystem = uiState.effectiveUnitSystem,
                      onClick = onOverviewFoundRoad,
                      modifier =
                        Modifier
                          .align(Alignment.TopEnd)
                          .padding(top = edgeTopDp, end = 8.dp),
                    )
                  }

                  // Floating Pins button ONLY when savedPins is non-empty, vertically centered on FREE edge farthest from panel
                  if (uiState.savedPins.isNotEmpty()) {
                    val pinsAlign =
                      if (panelOnLeft) AbsoluteAlignment.CenterRight
                      else AbsoluteAlignment.CenterLeft
                    FloatingPinsButton(
                      pinCount = uiState.savedPins.size,
                      onClick = { onOpenPins(true) },
                      modifier =
                        Modifier
                          .align(pinsAlign)
                          .padding(horizontal = 8.dp)
                          .graphicsLayer { alpha = pillAlpha },
                    )
                  }

                  // Attribution chip at bottom-start of FREE area (always dark in both map styles)
                  val narrowLandscapeAttr = !isTablet
                  MapAttributionChip(
                    narrow = narrowLandscapeAttr,
                    onClick = { showAttributionDialog = true },
                    modifier =
                      Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = safeBottomDp + 6.dp)
                        .widthIn(max = if (narrowLandscapeAttr) 78.dp else 96.dp),
                  )

                  // Bottom Pill (3 icon buttons, 56dp), centered horizontally at bottom of FREE map area.
                  // Alpha animation via graphicsLayer only; stays mounted & clickable so first touch works even when faded.
                  FloatingActionPill(
                    showLabels = false,
                    isFollowingUser = uiState.isFollowingUser,
                    buttonSize = 56.dp,
                    onSearchClick = { onOpenSearch(true) },
                    onRecenterClick = onRecenter,
                    onSettingsClick = { onOpenSettings(true) },
                    modifier =
                      Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = safeBottomDp + 8.dp, start = 8.dp, end = 8.dp)
                        .graphicsLayer { alpha = pillAlpha }
                        .onGloballyPositioned { coords ->
                          pillTopPx = coords.boundsInRoot().top
                        },
                  )

                  if (!controlsVisible && !uiState.isInspectorOpen) {
                    LaunchedEffect(Unit) {
                      pillTopPx = totalHeightPx
                    }
                  }

                  // Debug Inspector bottom sheet anchored in FREE area (max 560dp wide, max 60% height)
                  if (BuildConfig.DEBUG && uiState.isInspectorOpen) {
                    val pillClearDp = with(density) { (totalHeightPx - pillTopPx).coerceAtLeast(0f).toDp() }
                    val sheetBotDp = maxOf(pillClearDp + 6.dp, safeBottomDp + 72.dp)
                    GraphInspectorBottomSheet(
                      uiState = uiState,
                      detent = uiState.inspectorDetent,
                      maxScreenHeight = totalHeight,
                      windowWidthDp = winWidthDp,
                      windowHeightDp = winHeightDp,
                      windowSizeClassSummary = windowSizeClassSummary,
                      maxFullFraction = 0.60f,
                      onDetentChange = onInspectorDetentChange,
                      onHeadingChange = onMockHeadingChange,
                      onSnapHeadingToRoad = onSnapHeadingToRoad,
                      onToggleMockMode = onToggleMockMode,
                      onStepAlongRoad = onStepAlongRoad,
                      onToggleSimulateDrive = onToggleSimulateDrive,
                      onMockSpeedChange = onMockSpeedChange,
                      onMockAccuracyChange = onMockAccuracyChange,
                      onMockJitterChange = onMockJitterChange,
                      onSaveSpot = onSaveSpot,
                      onLoadSpot = onLoadSpot,
                      onDeleteSpot = onDeleteSpot,
                      onRestoreDefaultSpots = onRestoreDefaultSpots,
                      onDebugFlagsChange = onDebugFlagsChange,
                      onDebugNetworkSimulationChange = onDebugNetworkSimulationChange,
                      onClearCache = onClearCache,
                      onTestOverpass = onTestOverpass,
                      onReloadRoadData = onReloadRoadData,
                      onResetDebugOverrides = onResetDebugOverrides,
                      onJumpToCensusFeature = onJumpToCensusFeature,
                      onSaveCensusAsSpot = onSaveCensusAsSpot,
                      onReportCopied = onReportCopied,
                      onClose = { onToggleShowInspector(false) },
                      modifier =
                        Modifier
                          .align(Alignment.BottomCenter)
                          .padding(start = 8.dp, end = 8.dp, bottom = sheetBotDp)
                          .widthIn(max = 560.dp)
                          .onGloballyPositioned { coords ->
                            sheetTopPx = coords.boundsInRoot().top
                          },
                    )
                  } else {
                    sheetTopPx = totalHeightPx
                  }

                  // Anchored Search / Pins sheets over FREE map area only (side panel stays visible, no fillMaxSize overlay)
                  val anchoredMaxHeight = (totalHeight * 0.60f - safeBottomDp - 8.dp).coerceAtLeast(160.dp)

                  if (uiState.isSearchOpen) {
                    SearchBottomSheet(
                      uiState = uiState,
                      onQueryChange = onSearchQueryChange,
                      onExecuteSearch = onExecuteSearch,
                      onSelectPlace = onSelectPlace,
                      onSelectScope = onSearchScopeChange,
                      onSelectFoundMatch = onSelectFoundMatch,
                      onSelectRecentQuery = onSelectRecentQuery,
                      onDismiss = { onOpenSearch(false) },
                      isLandscape = true,
                      maxSheetHeight = anchoredMaxHeight,
                      modifier =
                        Modifier
                          .align(Alignment.BottomCenter)
                          .padding(start = 8.dp, end = 8.dp, bottom = safeBottomDp + 8.dp),
                    )
                  }

                  if (uiState.isPinsSheetOpen) {
                    PinsBottomSheet(
                      pins = uiState.savedPins,
                      sortOrder = uiState.pinSortOrder,
                      activeRoutePinId = uiState.activeRoutePinId,
                      isLoadingRoute = uiState.isLoadingRoute,
                      onToggleSort = onTogglePinSort,
                      onFocusPin = onFocusPin,
                      onToggleRoute = onTogglePinRoute,
                      onRemovePin = onRemovePin,
                      onClearAll = onClearAllPins,
                      onDismiss = { onOpenPins(false) },
                      isLandscape = true,
                      maxSheetHeight = anchoredMaxHeight,
                      unitSystem = uiState.effectiveUnitSystem,
                      modifier =
                        Modifier
                          .align(Alignment.BottomCenter)
                          .padding(start = 8.dp, end = 8.dp, bottom = safeBottomDp + 8.dp),
                    )
                  }

                  if (uiState.isTuneCameraOpen) {
                    val pillClearDp = with(density) { (totalHeightPx - pillTopPx).coerceAtLeast(0f).toDp() }
                    val tuneBotDp = maxOf(pillClearDp + 8.dp, safeBottomDp + 72.dp)
                    TuneCameraSheet(
                      uiState = uiState,
                      maxScreenHeight = totalHeight,
                      onUpdateTuning = onUpdateChaseCameraTuning,
                      onReset = onResetChaseCameraTuning,
                      onClose = onCloseTuneCamera,
                      modifier =
                        Modifier
                          .align(Alignment.BottomCenter)
                          .padding(start = 8.dp, end = 8.dp, bottom = tuneBotDp)
                          .widthIn(max = 560.dp),
                    )
                  }

                  SnackbarHost(
                    hostState = snackbarHostState,
                    modifier =
                      Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = safeBottomDp + 76.dp, start = 12.dp, end = 12.dp),
                  )
                }
              }
            }
          }
        }
      } else {
        // PORTRAIT (unchanged layout)
        // 3. Floating Top Liquid Glass Info Card (below status bar inset, 8dp side margins)
        val outerVerticalPad = 4.dp
        IllustrationPanel(
          uiState = uiState,
          maxScreenHeight = totalHeight,
          outerVerticalMargin = outerVerticalPad * 2 + statusTopDp,
          isLandscape = false,
          isTablet = isTablet,
          isExpanded = isPanelExpanded,
          onToggleExpand = { expanded ->
            internalPanelExpanded = expanded
            onPanelExpandedChange?.invoke(expanded)
          },
          onSelectFeature = onSelectFeature,
          onSelectOffRoadCandidate = onSelectOffRoadCandidate,
          onSelectOffRoadDirection = onSelectOffRoadDirection,
          onClearOffRoadSelection = onClearOffRoadSelection,
          onSelectSimulationBranch = onSelectSimulationBranch,
          onRetryRoadData = onRetryRoadData,
          onOpenInspectorSpots = onOpenInspectorSpots,
          onToggleOverview = onToggleOverview,
          windowWidthDp = winWidthDp,
          onOpenWholeRoad = { onOpenWholeRoad(true) },
          modifier =
            Modifier
              .align(Alignment.TopCenter)
              .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
              .padding(horizontal = 8.dp, vertical = outerVerticalPad)
              .let { if (isTablet) it.widthIn(max = 760.dp) else it }
              .onGloballyPositioned { coords ->
                cardBottomPx = coords.boundsInRoot().bottom
              },
        )

        // 4. Floating Status Strip directly BELOW the card over the map:
        //    Left = status chips (only when true); Right = compact 44dp Driving|Walking capsule
        val cardAndStripBottom = maxOf(cardBottomPx, stripBottomPx)
        val stripTopDp = with(density) { (cardBottomPx + gap6Px).toDp() }
        Row(
          modifier =
            Modifier
              .align(Alignment.TopCenter)
              .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
              .padding(top = stripTopDp, start = 8.dp, end = 8.dp)
              .let { if (isTablet) it.widthIn(max = 760.dp) else it }
              .fillMaxWidth()
              .onGloballyPositioned { coords ->
                stripBottomPx = coords.boundsInRoot().bottom
              },
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(
            modifier =
              Modifier
                .weight(1f)
                .padding(end = 6.dp),
          ) {
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(5.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              FloatingStatusChipsStrip(
                uiState = uiState,
                onGrantLocationClick = onGrantLocationClick,
              )
            }
          }
          CompactModeSegmentedControl(
            selectedMode = uiState.travelMode,
            compactHeight = 48.dp,
            onSelect = onModeSelected,
          )
        }

        // 5. Optional Selected Saved Pin or Found-on-my-road callout floating below status strip
        val selectedPin = uiState.selectedPin
        val activeFound = uiState.activeFoundRoad
        val calloutTopDp = with(density) { (cardAndStripBottom + gap8Px).toDp() }
        if (selectedPin != null) {
          PinCalloutCard(
            pin = selectedPin,
            isRouteActive = uiState.activeRoutePinId == selectedPin.id,
            isLoadingRoute = uiState.isLoadingRoute && uiState.activeRoutePinId == selectedPin.id,
            onToggleRoute = { onTogglePinRoute(selectedPin.id) },
            onRemove = { onRemovePin(selectedPin.id) },
            onDismiss = onDismissPinCallout,
            unitSystem = uiState.effectiveUnitSystem,
            modifier =
              Modifier
                .align(Alignment.TopCenter)
                .padding(top = calloutTopDp, start = 12.dp, end = 12.dp)
                .widthIn(max = 560.dp)
                .onGloballyPositioned { coords ->
                  calloutBottomPx = coords.boundsInRoot().bottom
                },
          )
        } else if (activeFound != null) {
          FoundRoadCalloutCard(
            found = activeFound,
            isRouteActive = uiState.isRoutingToFoundRoad,
            isLoadingRoute = uiState.isLoadingRoute && uiState.isRoutingToFoundRoad,
            onOverview = onOverviewFoundRoad,
            onToggleRoute = onToggleFoundRoadRoute,
            onClear = onClearFoundRoad,
            unitSystem = uiState.effectiveUnitSystem,
            modifier =
              Modifier
                .align(Alignment.TopCenter)
                .padding(top = calloutTopDp, start = 12.dp, end = 12.dp)
                .widthIn(max = 560.dp)
                .onGloballyPositioned { coords ->
                  calloutBottomPx = coords.boundsInRoot().bottom
                },
          )
        }

        if (activeFound != null && effectiveOffScreen) {
          val edgeTopDp =
            with(density) {
              ((if (calloutBottomPx > cardAndStripBottom) calloutBottomPx else cardAndStripBottom) + gap6Px).toDp()
            }.coerceAtLeast(calloutTopDp)
          FoundTargetOffScreenIndicator(
            found = activeFound,
            unitSystem = uiState.effectiveUnitSystem,
            onClick = onOverviewFoundRoad,
            modifier =
              Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(top = edgeTopDp, end = 12.dp),
          )
        }

        // 6. Unobtrusive OSM & style provider attribution info control (always dark in both map styles)
        val narrowPortraitAttribution = !isTablet
        MapAttributionChip(
          narrow = narrowPortraitAttribution,
          onClick = { showAttributionDialog = true },
          modifier =
            Modifier
              .align(Alignment.BottomStart)
              .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Start))
              .padding(start = 6.dp, bottom = 10.dp)
              .then(if (narrowPortraitAttribution) Modifier.widthIn(max = 78.dp) else Modifier),
        )

        // 7. Floating Bottom 3-Button Pill (graphicsLayer alpha fade only; always clickable on first touch)
        val pillBottomGapDp = 10.dp
        FloatingActionPill(
          showLabels = isTablet,
          isFollowingUser = uiState.isFollowingUser,
          onSearchClick = { onOpenSearch(true) },
          onRecenterClick = onRecenter,
          onSettingsClick = { onOpenSettings(true) },
          modifier =
            Modifier
              .align(Alignment.BottomCenter)
              .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
              .padding(bottom = pillBottomGapDp, start = 16.dp, end = 16.dp)
              .graphicsLayer { alpha = pillAlpha }
              .onGloballyPositioned { coords ->
                pillTopPx = coords.boundsInRoot().top
              },
        )

        // 7b. Portrait / Tablet floating Pins button + count badge at right edge above bottom pill
        if (uiState.savedPins.isNotEmpty()) {
          val pillClearanceDp =
            with(density) { (totalHeightPx - pillTopPx).coerceAtLeast(0f).toDp() }
              .coerceAtLeast(safePadding.calculateBottomPadding() + 62.dp)
          val inspectorTopClearanceDp =
            if (BuildConfig.DEBUG && uiState.isInspectorOpen && sheetTopPx < totalHeightPx) {
              with(density) { (totalHeightPx - sheetTopPx).coerceAtLeast(0f).toDp() }
            } else {
              0.dp
            }
          val pinsBottomDp = maxOf(pillClearanceDp, inspectorTopClearanceDp) + 10.dp
          FloatingPinsButton(
            pinCount = uiState.savedPins.size,
            onClick = { onOpenPins(true) },
            modifier =
              Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Right))
                .padding(end = 12.dp, bottom = pinsBottomDp)
                .graphicsLayer { alpha = pillAlpha },
          )
        }

        if (!controlsVisible && !uiState.isInspectorOpen) {
          LaunchedEffect(Unit) {
            pillTopPx = totalHeightPx
          }
        }

        // 8. Debug-only 3-state draggable Graph Inspector bottom sheet (clear of pill & system insets)
        if (BuildConfig.DEBUG && uiState.isInspectorOpen) {
          val pillHeightFromBottomDp = with(density) { (totalHeightPx - pillTopPx).coerceAtLeast(0f).toDp() }
          val sheetBottomClearanceDp = maxOf(pillHeightFromBottomDp + 8.dp, safePadding.calculateBottomPadding() + 72.dp)

          GraphInspectorBottomSheet(
            uiState = uiState,
            detent = uiState.inspectorDetent,
            maxScreenHeight = totalHeight,
            windowWidthDp = winWidthDp,
            windowHeightDp = winHeightDp,
            windowSizeClassSummary = windowSizeClassSummary,
            onDetentChange = onInspectorDetentChange,
            onHeadingChange = onMockHeadingChange,
            onSnapHeadingToRoad = onSnapHeadingToRoad,
            onToggleMockMode = onToggleMockMode,
            onStepAlongRoad = onStepAlongRoad,
            onToggleSimulateDrive = onToggleSimulateDrive,
            onMockSpeedChange = onMockSpeedChange,
            onMockAccuracyChange = onMockAccuracyChange,
            onMockJitterChange = onMockJitterChange,
            onSaveSpot = onSaveSpot,
            onLoadSpot = onLoadSpot,
            onDeleteSpot = onDeleteSpot,
            onRestoreDefaultSpots = onRestoreDefaultSpots,
            onDebugFlagsChange = onDebugFlagsChange,
            onDebugNetworkSimulationChange = onDebugNetworkSimulationChange,
            onClearCache = onClearCache,
            onTestOverpass = onTestOverpass,
            onPingOverpass = onPingOverpass,
            onReloadRoadData = onReloadRoadData,
            onResetDebugOverrides = onResetDebugOverrides,
            onJumpToCensusFeature = onJumpToCensusFeature,
            onSaveCensusAsSpot = onSaveCensusAsSpot,
            onReportCopied = onReportCopied,
            onClose = { onToggleShowInspector(false) },
            modifier =
              Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(
                  start = 8.dp,
                  end = 8.dp,
                  bottom = sheetBottomClearanceDp,
                )
                .widthIn(max = 720.dp)
                .onGloballyPositioned { coords ->
                  sheetTopPx = coords.boundsInRoot().top
                },
          )
        } else {
          sheetTopPx = totalHeightPx
        }

        // 9. Brief Snackbar feedback for long-press inspector shortcut & copied report
        SnackbarHost(
          hostState = snackbarHostState,
          modifier =
            Modifier
              .align(Alignment.BottomCenter)
              .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
              .padding(bottom = 78.dp, start = 16.dp, end = 16.dp),
        )
      }

      if (showAttributionDialog) {
        AlertDialog(
          onDismissRequest = { showAttributionDialog = false },
          title = { Text(text = stringResource(R.string.attribution_dialog_title)) },
          text = { Text(text = stringResource(R.string.attribution_dialog_body)) },
          confirmButton = {
            TextButton(onClick = { showAttributionDialog = false }) {
              Text(text = stringResource(R.string.action_close))
            }
          },
        )
      }

      if (!isLandscape) {
        if (uiState.isSearchOpen) {
          SearchBottomSheet(
            uiState = uiState,
            onQueryChange = onSearchQueryChange,
            onExecuteSearch = onExecuteSearch,
            onSelectPlace = onSelectPlace,
            onSelectScope = onSearchScopeChange,
            onSelectFoundMatch = onSelectFoundMatch,
            onSelectRecentQuery = onSelectRecentQuery,
            onDismiss = { onOpenSearch(false) },
          )
        }

        if (uiState.isTuneCameraOpen) {
          val pillHeightFromBottomDp = with(density) { (totalHeightPx - pillTopPx).coerceAtLeast(0f).toDp() }
          val tuneBottomClearanceDp = maxOf(pillHeightFromBottomDp + 8.dp, safePadding.calculateBottomPadding() + 72.dp)
          TuneCameraSheet(
            uiState = uiState,
            maxScreenHeight = totalHeight,
            onUpdateTuning = onUpdateChaseCameraTuning,
            onReset = onResetChaseCameraTuning,
            onClose = onCloseTuneCamera,
            modifier =
              Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(start = 8.dp, end = 8.dp, bottom = tuneBottomClearanceDp)
                .widthIn(max = 600.dp),
          )
        }

        if (uiState.isPinsSheetOpen) {
          PinsBottomSheet(
            pins = uiState.savedPins,
            sortOrder = uiState.pinSortOrder,
            activeRoutePinId = uiState.activeRoutePinId,
            isLoadingRoute = uiState.isLoadingRoute,
            onToggleSort = onTogglePinSort,
            onFocusPin = onFocusPin,
            onToggleRoute = onTogglePinRoute,
            onRemovePin = onRemovePin,
            onClearAll = onClearAllPins,
            onDismiss = { onOpenPins(false) },
            unitSystem = uiState.effectiveUnitSystem,
          )
        }
      }

      if (uiState.isSettingsOpen) {
        SettingsBottomSheet(
          uiState = uiState,
          onSelectTheme = onSelectTheme,
          onSelectPanelStyle = onSelectPanelStyle,
          onToggleExperimentalBlur = onToggleExperimentalBlur,
          onToggleReduceTransparency = onToggleReduceTransparency,
          onToggleArabicTts = onToggleArabicTts,
          onToggleShowInspector = onToggleShowInspector,
          onToggleMockMode = onToggleMockMode,
          onOfflineOverrideChange = { offline ->
            onDebugFlagsChange(uiState.debugPoorGps, offline, uiState.debugUncertain)
          },
          onSelectDeveloperOrientation = onSelectDeveloperOrientation,
          onSelectLandscapePanelSide = onSelectLandscapePanelSide,
          onSelectUnitPreference = onSelectUnitPreference,
          onSelectHorizon = onSelectHorizon,
          onToggleShowMinorJunctions = onToggleShowMinorJunctions,
          onToggleLandmarksAhead = onToggleLandmarksAhead,
          onSelectLadderOrder = onSelectLadderOrder,
          onToggleChaseCamera = onToggleChaseCamera,
          onSelectChaseCameraDistance = onSelectChaseCameraDistance,
          onOpenTuneCamera = {
            onOpenSettings(false)
            onOpenTuneCamera()
          },
          onTogglePerformanceMode = onTogglePerformanceMode,
          onSelectQualitySetting = onSelectQualitySetting,
          onToggleKeepScreenOn = onToggleKeepScreenOn,
          onToggleBackgroundTracking = onToggleBackgroundTracking,
          onSelectLadderTextSize = onSelectLadderTextSize,
          onSelectNameOrder = onSelectNameOrder,
          onToggleShowDataSourceChips = onToggleShowDataSourceChips,
          onToggleShowGraphEdges = onToggleShowGraphEdges,
          onResetDebugOverrides = onResetDebugOverrides,
          onClearCache = onClearCache,
          onDismiss = { onOpenSettings(false) },
        )
      }

      if (uiState.isWholeRoadOpen) {
        WholeRoadSheet(
          features = uiState.features,
          timelineEntries = uiState.timelineEntries,
          selectedFeatureIndex = uiState.selectedFeatureIndex,
          unitSystem = uiState.effectiveUnitSystem,
          horizonMeters = uiState.lookaheadMeters,
          showMinorJunctions = uiState.settings.showMinorJunctions,
          isDividedRoad = uiState.isDividedRoad,
          horizonStoppedAtUnloadedBorder = uiState.horizonStoppedAtUnloadedBorder,
          textSize = uiState.settings.ladderTextSize,
          nameOrder = uiState.settings.nameOrder,
          isLandscape = isLandscape,
          windowWidthDp = winWidthDp,
          onSelectFeature = onSelectFeature,
          onSelectHorizon = onSelectHorizon,
          onToggleShowMinor = onToggleShowMinorJunctions,
          onDismiss = { onOpenWholeRoad(false) },
        )
      }

      if (BuildConfig.DEBUG) {
        DebugTouchRingsOverlay(modifier = Modifier.fillMaxSize())
      }
    }
  }
}

@Composable
private fun FoundTargetOffScreenIndicator(
  found: FoundRoadMatch,
  unitSystem: com.example.data.model.UnitSystem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val interactionSource = rememberPressHapticInteractionSource()
  val distText =
    found.alongRoadDistanceMeters?.let { formatRoadDistance(it, unitSystem) }
      ?: stringResource(R.string.found_match_badge)
  FilledTonalButton(
    onClick = {
      TouchTelemetry.recordControlHandled("offscreen_found_indicator")
      onClick()
    },
    interactionSource = interactionSource,
    colors =
      ButtonDefaults.filledTonalButtonColors(
        containerColor = RoadColors.PinPurple,
        contentColor = Color.White,
      ),
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).testTag("offscreen_found_indicator"),
  ) {
    Row(
      modifier = Modifier.innerPressScale(interactionSource),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(
        imageVector = Icons.Default.Map,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
      )
      Text(
        text = stringResource(R.string.found_target_offscreen_format, distText),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun MapAttributionChip(
  narrow: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val currentGlass = LocalGlassConfig.current
  val darkChipGlass = remember(currentGlass) { currentGlass.copy(isDark = true) }
  val interaction = rememberPressHapticInteractionSource()
  val pressed by interaction.collectIsPressedAsState()

  Button(
    onClick = {
      TouchTelemetry.recordControlHandled("map_attribution_button")
      onClick()
    },
    interactionSource = interaction,
    shape = RoundedCornerShape(14.dp),
    colors =
      ButtonDefaults.buttonColors(
        containerColor = Color.Transparent,
        contentColor = RoadColors.DarkTextSecondary,
      ),
    elevation = null,
    contentPadding = PaddingValues(0.dp),
    modifier =
      modifier
        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        .testTag("map_attribution_button"),
  ) {
    CompositionLocalProvider(LocalGlassConfig provides darkChipGlass) {
      GlassSurface(
        shape = RoundedCornerShape(14.dp),
        isLighterInner = true,
        withShadow = false,
        tintOverlay = if (pressed) Color.White.copy(alpha = 0.14f) else Color.Transparent,
        modifier = Modifier.innerPressScale(interaction),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
          Icon(
            imageVector = Icons.Default.Info,
            contentDescription = stringResource(R.string.attribution_content_desc),
            tint = RoadColors.DarkTextSecondary,
            modifier = Modifier.size(11.dp),
          )
          Text(
            text = stringResource(R.string.attribution_compact),
            color = RoadColors.DarkTextSecondary,
            fontSize = if (narrow) 8.5.sp else 9.5.sp,
            lineHeight = if (narrow) 10.sp else 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = if (narrow) 2 else 1,
          )
        }
      }
    }
  }
}
