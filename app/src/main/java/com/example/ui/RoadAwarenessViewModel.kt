package com.example.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.config.AppConfig
import com.example.data.interfaces.MatrixDistanceResult
import com.example.data.interfaces.RouteResult
import com.example.data.interfaces.RoutingService
import com.example.data.interfaces.SearchService
import com.example.data.local.RoadAwarenessDatabase
import com.example.data.local.SavedPinRepository
import com.example.data.local.SettingsRepository
import com.example.data.local.UserSettings
import com.example.data.model.AheadAnalysisResult
import com.example.data.model.AheadTimelineEntry
import com.example.data.model.BilingualName
import com.example.data.model.DebugEdgeCategory
import com.example.data.model.DebugGraphEdge
import com.example.data.model.DeveloperOrientation
import com.example.data.model.DistanceKind
import com.example.data.model.DividedRoadInfo
import com.example.data.model.EntryLegalityStatus
import com.example.data.model.FeatureCensusEntry
import com.example.data.model.FeatureType
import com.example.data.model.FoundRoadMatch
import com.example.data.model.FoundRoadRelation
import com.example.data.model.GeoPoint
import com.example.data.model.GraphDataSource
import com.example.data.model.LandmarkAheadItem
import com.example.data.model.LandscapePanelSide
import com.example.data.model.LiveMapVectorFeature
import com.example.data.model.LocationSnapshot
import com.example.data.model.MatchResult
import com.example.data.model.OffRoadState
import com.example.data.model.OsmTags
import com.example.data.model.OverpassEndpointTestResult
import com.example.data.model.OverpassRequestLog
import com.example.data.model.PanelStyle
import com.example.data.model.PinSortOrder
import com.example.data.model.PipelineDiagnostics
import com.example.data.model.PipelineStageLine
import com.example.data.model.RoadFeature
import com.example.data.model.RoadGraph
import com.example.data.model.SavedPin
import com.example.data.model.SearchPlaceResult
import com.example.data.model.SearchScope
import com.example.data.model.TestSpot
import com.example.data.model.ThemePreference
import com.example.data.model.TileRoadHint
import com.example.data.model.TravelMode
import com.example.data.model.UnitPreference
import com.example.data.model.UnitSystem
import com.example.data.remote.DebugNetworkSimulator
import com.example.data.remote.NominatimSearchService
import com.example.data.remote.OverpassMapDataSource
import com.example.data.remote.TileRoadGraphSource
import com.example.data.remote.ValhallaRoutingService
import com.example.data.repository.RoadGraphRepository
import com.example.domain.AheadGraphAnalyzer
import com.example.domain.ArabicTtsAnnouncer
import com.example.domain.GeoMath
import com.example.domain.LocalRoadSearchIndex
import com.example.domain.MapMatcher
import com.example.domain.RoadGeometryStepper
import com.example.location.LocationTracker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class InspectorSheetDetent {
  COLLAPSED,
  HALF,
  FULL,
}

data class RoadHudUiState(
  val hasLocationPermission: Boolean = false,
  val isGpsSearching: Boolean = true,
  val location: LocationSnapshot? = null,
  val travelMode: TravelMode = TravelMode.DRIVING,
  val settings: UserSettings = UserSettings(),
  val isLoadingGraph: Boolean = false,
  val hasConfirmedGraphData: Boolean = false,
  val isGraphErrorState: Boolean = false,
  val isDataStaleOver10s: Boolean = false,
  val isUpdatingStaleData: Boolean = false,
  val showOfflineChipAfter5s: Boolean = false,
  val showSlowDataChipAfter5s: Boolean = false,
  val isUsingBasicTileData: Boolean = false,
  val lastConfirmedRoadName: BilingualName? = null,
  val tileFallbackRoad: TileRoadHint? = null,
  val roadNameSource: String = "none",
  val needsMapFallbackWithin150m: Boolean = false,
  val mapFallbackTriggerTick: Long = 0L,
  // Inspector & log diagnostics ONLY (never shown in main header/panel)
  val loadingStallReason: String? = null,
  val isOfflineCached: Boolean = false,
  val networkError: String? = null,
  val mapStyleError: String? = null,
  val recentOverpassLogs: List<OverpassRequestLog> = emptyList(),
  val overpassTestResults: List<OverpassEndpointTestResult> = emptyList(),
  val isTestingOverpass: Boolean = false,
  val matchResult: MatchResult = MatchResult(null, null, false),
  val offRoadState: OffRoadState = OffRoadState(),
  val features: List<RoadFeature> = emptyList(),
  val timelineEntries: List<AheadTimelineEntry> = emptyList(),
  val corridorWorldPoints: List<GeoPoint> = emptyList(),
  val loadedCorridorMeters: Double = 0.0,
  val hiddenMinorCount: Int = 0,
  val landmarksAhead: List<LandmarkAheadItem> = emptyList(),
  val isOverviewMode: Boolean = false,
  val overviewSignal: Int = 0,
  val featureCensus: List<FeatureCensusEntry> = emptyList(),
  val selectedFeatureIndex: Int = 1,
  val lookaheadMeters: Double = AppConfig.DRIVING_MIN_LOOKAHEAD_M,
  val isDividedRoad: Boolean = false,
  val dividedRoadSeparationMeters: Double? = null,
  val dividedRoadSideOffsetMeters: Double? = null,
  val dividedRoadRuleId: String? = null,
  val dividedRoadConfidence: Double = 0.0,
  val isFollowingUser: Boolean = true,
  val recenterSignal: Int = 0,
  // Secondary sheets
  val isSearchOpen: Boolean = false,
  val isPinsSheetOpen: Boolean = false,
  val isSettingsOpen: Boolean = false,
  val isInspectorOpen: Boolean = false,
  val inspectorDetent: InspectorSheetDetent = InspectorSheetDetent.COLLAPSED,
  val inspectorScrollToSpotsSignal: Int = 0,
  val isMockModeActive: Boolean = BuildConfig.DEBUG,
  val mockSpeedKmh: Double = 30.0,
  val mockAccuracyMeters: Float = 3.0f,
  val mockJitterEnabled: Boolean = false,
  val isSimulatingDrive: Boolean = false,
  val simulationStatusMessage: String? = null,
  val selectedSimulationBranchWayId: Long? = null,
  val savedTestSpots: List<TestSpot> = emptyList(),
  val ignoredLiveJumps: List<String> = emptyList(),
  val recentPositionSources: List<String> = emptyList(),
  val displayLocationPoint: GeoPoint? = null,
  val displayHeadingDeg: Double? = null,
  // Search, Saved Pins & Active Route
  val searchQuery: String = "",
  val searchScope: SearchScope = SearchScope.ON_MY_ROAD,
  val isSearchingPlaces: Boolean = false,
  val searchResults: List<SearchPlaceResult> = emptyList(),
  val localFoundResults: List<FoundRoadMatch> = emptyList(),
  val activeFoundRoad: FoundRoadMatch? = null,
  val isRoutingToFoundRoad: Boolean = false,
  val savedPins: List<SavedPin> = emptyList(),
  val pinSortOrder: PinSortOrder = PinSortOrder.NEAREST,
  val selectedPinId: Long? = null,
  val activeRoutePinId: Long? = null,
  val focusedPin: SavedPin? = null,
  val focusPinSignal: Int = 0,
  val explicitRoute: RouteResult? = null,
  val isLoadingRoute: Boolean = false,
  // Cache stats
  val cachedCellCount: Int = 0,
  val cachedTileCount: Int = 0,
  val oldestCacheAgeMs: Long? = null,
  val memoryTileCount: Int = 0,
  val memoryHits: Long = 0L,
  val memoryMisses: Long = 0L,
  val memoryEvictions: Int = 0,
  val diskTileCount: Int = 0,
  val diskTileBytes: Long = 0L,
  // Pipeline diagnostics, failure stage, worldwide units & debug graph overlay
  val roadDataFailedStage: String? = null,
  val pipelineDiagnostics: PipelineDiagnostics = PipelineDiagnostics(),
  val effectiveUnitSystem: UnitSystem = GeoMath.resolveEffectiveUnitSystem(UnitPreference.AUTO),
  val debugGraphEdges: List<DebugGraphEdge> = emptyList(),
  val currentActiveOverpassAgeMs: Long? = null,
  // Debug-only UI chip & network simulation flags
  val debugPoorGps: Boolean = false,
  val debugOffline: Boolean = false,
  val debugUncertain: Boolean = false,
  val debugBlockOverpass: Boolean = false,
  val debugBlockTiles: Boolean = false,
  val debugSlowNetwork: Boolean = false,
  val debugForceOverpassError: Int? = null,
) {
  val selectedFeature: RoadFeature?
    get() = features.firstOrNull { it.index == selectedFeatureIndex } ?: features.firstOrNull()

  val selectedPin: SavedPin?
    get() = selectedPinId?.let { id -> savedPins.firstOrNull { it.id == id } }

  val showSearchSpeedCaution: Boolean
    get() = (location?.speedMps ?: 0.0) * 3.6 > 8.0

  val hasAnyDebugOverride: Boolean
    get() =
      debugPoorGps ||
        debugOffline ||
        debugUncertain ||
        debugBlockOverpass ||
        debugBlockTiles ||
        debugSlowNetwork ||
        debugForceOverpassError != null
}

class RoadAwarenessViewModel(application: Application) : AndroidViewModel(application) {
  private val db = RoadAwarenessDatabase.getInstance(application)
  private val overpassDataSource = OverpassMapDataSource(db.corridorCacheDao())
  private val tileDataSource =
    TileRoadGraphSource(
      tileCacheDao = db.tileCacheDao(),
      styleCacheDir = File(application.filesDir, "map_styles"),
    )
  private val searchService: SearchService = NominatimSearchService()
  private val routingService: RoutingService = ValhallaRoutingService()
  private val settingsRepo = SettingsRepository(application)
  private val savedPinRepository = SavedPinRepository(db.savedPinDao())
  private val locationTracker = LocationTracker(application)
  private val ttsAnnouncer = ArabicTtsAnnouncer(application)

  private val _uiState = MutableStateFlow(RoadHudUiState())
  val uiState: StateFlow<RoadHudUiState> = _uiState.asStateFlow()

  private val roadGraphRepository = RoadGraphRepository()
  @Volatile private var overpassFailedForArea: Boolean = false
  @Volatile private var tileFailedForArea: Boolean = false

  private var lastFetchCenter: GeoPoint? = null
  private var lastFetchHeading: Double? = null
  private var lastFetchMode: TravelMode? = null
  private var lastSavedLivePoint: GeoPoint? = null
  @Volatile private var previousMatchedWayId: Long? = null
  @Volatile private var selectedOffRoadWayId: Long? = null
  @Volatile private var selectedOffRoadForwardOverride: Boolean? = null

  private val pinDistanceCache = mutableMapOf<Long, MatrixDistanceResult>()
  @Volatile private var rawSavedPins: List<SavedPin> = emptyList()
  private var lastPinsMatrixOrigin: GeoPoint? = null
  private var lastPinsMatrixMode: TravelMode? = null
  private var lastPinsMatrixAtMs: Long = 0L

  private var tileFetchJob: Job? = null
  private var overpassFetchJob: Job? = null
  private var statusTimingJob: Job? = null
  private var retryFetchJob: Job? = null
  private var analyzeJob: Job? = null
  private var censusJob: Job? = null
  private var searchJob: Job? = null
  private var routeJob: Job? = null
  private var pinsMatrixJob: Job? = null
  private var pinsTickerJob: Job? = null
  private var testOverpassJob: Job? = null
  private var simulateDriveJob: Job? = null
  private var jitterJob: Job? = null
  private var mockHeadingSaveJob: Job? = null
  private var mockSpeedSaveJob: Job? = null
  private var mockAccuracySaveJob: Job? = null
  private var initializedFromSettings = false
  private var userTouchedInspectorOrMock = false

  private var loadingOrFailureStartedMs: Long = 0L
  private var offlineStartedMs: Long = 0L
  private var lastConfirmedRoadPoint: GeoPoint? = null
  private var overpassAutoRetryCount: Int = 0
  private var overpassAutoRetryCellKey: String? = null
  @Volatile private var lastMapFeaturesQueriedCount: Int = 0
  @Volatile private var lastFallbackTriggerPoint: GeoPoint? = null
  private val trailingBreadcrumbs = ArrayDeque<GeoPoint>()

  internal fun currentGraphSnapshot(): RoadGraphRepository.GraphSnapshot =
    roadGraphRepository.currentSnapshot()

  init {
    if (BuildConfig.DEBUG) {
      viewModelScope.launch {
        settingsRepo.seedTestSpotsOnceIfNeeded(
          AppConfig.WORLDWIDE_SEED_TEST_SPOTS.map {
            TestSpot(
              id = it.id,
              name = it.name,
              lat = it.lat,
              lon = it.lon,
              headingDeg = it.headingDeg,
              travelMode = TravelMode.DRIVING,
              createdAtMs = 0L,
            )
          },
        )
      }
    }

    viewModelScope.launch {
      settingsRepo.settingsFlow.collect { prefs ->
        val modeChanged = _uiState.value.travelMode != prefs.travelMode
        val showEdgesChanged = _uiState.value.settings.showGraphEdges != prefs.showGraphEdges
        val shouldApplyInit = !initializedFromSettings
        initializedFromSettings = true

        if (shouldApplyInit) {
          if (prefs.lastLiveLat != null && prefs.lastLiveLon != null) {
            val savedLive = GeoPoint(prefs.lastLiveLat, prefs.lastLiveLon)
            lastSavedLivePoint = savedLive
            locationTracker.restoreLastLivePoint(savedLive)
          }
          if (BuildConfig.DEBUG && !userTouchedInspectorOrMock) {
            val savedMockPt =
              if (prefs.lastMockLat != null && prefs.lastMockLon != null) {
                GeoPoint(prefs.lastMockLat, prefs.lastMockLon)
              } else {
                null
              }
            locationTracker.restoreMockState(
              point = savedMockPt,
              headingDeg = prefs.lastMockHeadingDeg,
              speedKmh = prefs.mockSpeedKmh,
              accuracyMeters = prefs.mockAccuracyMeters,
              jitterEnabled = prefs.mockJitterEnabled,
              enabled = prefs.mockLocationEnabled,
            )
            if (prefs.mockLocationEnabled && savedMockPt != null) {
              recordPositionSource("MOCK_RESTORE")
            }
          }
        }

        val resolvedUnits = GeoMath.resolveEffectiveUnitSystem(prefs.unitPreference)
        _uiState.update { state ->
          state.copy(
            settings = prefs,
            travelMode = prefs.travelMode,
            effectiveUnitSystem = resolvedUnits,
            isInspectorOpen =
              if (!BuildConfig.DEBUG) false
              else if (shouldApplyInit && !userTouchedInspectorOrMock) prefs.showGraphInspector
              else state.isInspectorOpen,
            isMockModeActive =
              if (!BuildConfig.DEBUG) false
              else if (shouldApplyInit && !userTouchedInspectorOrMock) prefs.mockLocationEnabled
              else state.isMockModeActive,
            mockSpeedKmh =
              if (shouldApplyInit && !userTouchedInspectorOrMock) prefs.mockSpeedKmh else state.mockSpeedKmh,
            mockAccuracyMeters =
              if (shouldApplyInit && !userTouchedInspectorOrMock) prefs.mockAccuracyMeters
              else state.mockAccuracyMeters,
            mockJitterEnabled =
              if (!BuildConfig.DEBUG) false
              else if (shouldApplyInit && !userTouchedInspectorOrMock) prefs.mockJitterEnabled
              else state.mockJitterEnabled,
            savedTestSpots = if (BuildConfig.DEBUG) prefs.savedTestSpots else emptyList(),
          )
        }
        if (shouldApplyInit && BuildConfig.DEBUG) {
          ensureJitterLoop()
        }
        if (showEdgesChanged || shouldApplyInit) {
          rebuildDebugEdges()
        }
        if (!prefs.arabicTtsEnabled) {
          ttsAnnouncer.stop()
        }
        if (modeChanged) {
          lastFetchMode = prefs.travelMode
          val loc = _uiState.value.location
          if (loc != null) {
            val snap = roadGraphRepository.currentSnapshot()
            if (snap.graph.ways.isNotEmpty()) {
              runAnalysis(snap, loc, prefs.travelMode)
            } else {
              onLocationUpdated(loc, forceFetch = false)
            }
          }
        }
        refreshPipelineDiagnostics()
      }
    }

    viewModelScope.launch {
      locationTracker.isGpsSearching.collect { searching ->
        _uiState.update { it.copy(isGpsSearching = searching) }
      }
    }

    viewModelScope.launch {
      locationTracker.ignoredLiveJumps.collect { jumps ->
        _uiState.update { it.copy(ignoredLiveJumps = jumps) }
      }
    }

    viewModelScope.launch {
      locationTracker.liveSourceEvents.collect { sourceTag ->
        recordPositionSource(sourceTag)
      }
    }

    viewModelScope.launch {
      locationTracker.locationFlow.collect { snapshot ->
        if (snapshot != null) {
          onLocationUpdated(snapshot, forceFetch = false)
        } else {
          _uiState.update { it.copy(location = null) }
        }
      }
    }

    viewModelScope.launch {
      combine(overpassDataSource.requestLogs, tileDataSource.requestLogs) { ovLogs, tileLogs ->
        (ovLogs + tileLogs).sortedByDescending { it.timestampMs }.take(12)
      }.collect { mergedLogs ->
        _uiState.update { it.copy(recentOverpassLogs = mergedLogs) }
      }
    }

    viewModelScope.launch {
      combine(
        tileDataSource.tileUrlInfo,
        tileDataSource.tileFetchInfo,
        tileDataSource.mvtDecodeInfo,
      ) { u, f, d -> Triple(u, f, d) }.collect {
        refreshPipelineDiagnostics()
      }
    }

    viewModelScope.launch {
      savedPinRepository.allPins.collect { persisted ->
        rawSavedPins = persisted
        val activeIds = persisted.mapTo(HashSet()) { it.id }
        pinDistanceCache.keys.retainAll(activeIds)
        publishSavedPins()
        if (persisted.isNotEmpty()) {
          val missingAny = persisted.any { !pinDistanceCache.containsKey(it.id) }
          if (missingAny) {
            refreshSavedPinsDistances(force = true)
          }
          ensurePinsRefreshTicker()
        } else {
          pinsTickerJob?.cancel()
          pinsMatrixJob?.cancel()
        }
      }
    }

    refreshCacheCount()
    refreshPipelineDiagnostics()
  }

  fun onPermissionResult(granted: Boolean) {
    _uiState.update { it.copy(hasLocationPermission = granted) }
    if (granted) {
      locationTracker.startTracking()
    }
  }

  fun setTravelMode(mode: TravelMode) {
    if (_uiState.value.travelMode == mode) return
    lastFetchMode = mode
    val loc = _uiState.value.location
    val newLookahead =
      AheadGraphAnalyzer.computeLookaheadMeters(
        mode,
        loc?.speedMps ?: 0.0,
        _uiState.value.settings.horizonDistanceMeters,
      )
    _uiState.update {
      it.copy(
        travelMode = mode,
        selectedFeatureIndex = 1,
        lookaheadMeters = newLookahead,
      )
    }
    viewModelScope.launch { settingsRepo.setTravelMode(mode) }
    if (loc != null) {
      val snap = roadGraphRepository.currentSnapshot()
      if (snap.graph.ways.isNotEmpty()) {
        runAnalysis(snap, loc, mode)
      } else {
        onLocationUpdated(loc, forceFetch = false)
      }
    }
    if (rawSavedPins.isNotEmpty()) {
      refreshSavedPinsDistances(force = true)
    }
    _uiState.value.activeRoutePinId?.let { activeId ->
      routeToPin(activeId, forceRecompute = true)
    }
  }

  fun selectFeature(featureIndex: Int) {
    _uiState.update { it.copy(selectedFeatureIndex = featureIndex) }
  }

  fun selectOffRoadCandidate(wayId: Long) {
    selectedOffRoadWayId = wayId
    selectedOffRoadForwardOverride = null
    _uiState.update { it.copy(selectedFeatureIndex = 1) }
    val snap = roadGraphRepository.currentSnapshot()
    if (snap.graph.ways.isEmpty()) return
    val loc = _uiState.value.location ?: return
    runAnalysis(snap, loc, _uiState.value.travelMode)
  }

  fun selectOffRoadDirection(forwardAlongWay: Boolean) {
    if (selectedOffRoadWayId == null) return
    selectedOffRoadForwardOverride = forwardAlongWay
    _uiState.update { it.copy(selectedFeatureIndex = 1) }
    val snap = roadGraphRepository.currentSnapshot()
    if (snap.graph.ways.isEmpty()) return
    val loc = _uiState.value.location ?: return
    runAnalysis(snap, loc, _uiState.value.travelMode)
  }

  fun clearOffRoadSelection() {
    selectedOffRoadWayId = null
    selectedOffRoadForwardOverride = null
    _uiState.update { it.copy(selectedFeatureIndex = 1) }
    val snap = roadGraphRepository.currentSnapshot()
    if (snap.graph.ways.isEmpty()) return
    val loc = _uiState.value.location ?: return
    runAnalysis(snap, loc, _uiState.value.travelMode)
  }

  fun onTileRoadHintChanged(hint: TileRoadHint?) {
    if (_uiState.value.tileFallbackRoad != hint) {
      _uiState.update { it.copy(tileFallbackRoad = hint) }
    }
  }

  fun reportMapStyleError(error: String?) {
    if (_uiState.value.mapStyleError != error) {
      _uiState.update { it.copy(mapStyleError = error) }
    }
  }

  fun onUserPannedMap() {
    if (_uiState.value.isFollowingUser || _uiState.value.isOverviewMode) {
      _uiState.update { it.copy(isFollowingUser = false, isOverviewMode = false) }
    }
  }

  fun recenter() {
    _uiState.update {
      it.copy(
        isFollowingUser = true,
        isOverviewMode = false,
        recenterSignal = it.recenterSignal + 1,
      )
    }
  }

  fun toggleOverviewMode(enabled: Boolean? = null) {
    val next = enabled ?: !_uiState.value.isOverviewMode
    _uiState.update {
      it.copy(
        isOverviewMode = next,
        isFollowingUser = if (next) false else true,
        overviewSignal = if (next) it.overviewSignal + 1 else it.overviewSignal,
        recenterSignal = if (!next) it.recenterSignal + 1 else it.recenterSignal,
      )
    }
  }

  fun openSearch(open: Boolean) {
    _uiState.update { it.copy(isSearchOpen = open) }
  }

  fun openSettings(open: Boolean) {
    if (open) refreshCacheCount()
    _uiState.update { it.copy(isSettingsOpen = open) }
  }

  fun setInspectorOpen(open: Boolean, openInHalf: Boolean = false) {
    if (!BuildConfig.DEBUG) return
    userTouchedInspectorOrMock = true
    if (open) refreshCacheCount()
    _uiState.update {
      it.copy(
        isInspectorOpen = open,
        inspectorDetent =
          when {
            !open -> InspectorSheetDetent.COLLAPSED
            openInHalf -> InspectorSheetDetent.HALF
            else -> InspectorSheetDetent.HALF
          },
      )
    }
    viewModelScope.launch { settingsRepo.setShowGraphInspector(open) }
  }

  fun setInspectorDetent(detent: InspectorSheetDetent) {
    if (detent == InspectorSheetDetent.FULL) {
      refreshCacheCount()
      scheduleLazyCensus(roadGraphRepository.currentSnapshot())
    }
    _uiState.update { it.copy(inspectorDetent = detent) }
  }

  fun openInspectorToTestSpots() {
    if (!BuildConfig.DEBUG) return
    userTouchedInspectorOrMock = true
    refreshCacheCount()
    _uiState.update {
      it.copy(
        isInspectorOpen = true,
        inspectorDetent = InspectorSheetDetent.FULL,
        inspectorScrollToSpotsSignal = it.inspectorScrollToSpotsSignal + 1,
      )
    }
    viewModelScope.launch { settingsRepo.setShowGraphInspector(true) }
  }

  fun toggleInspectorFromShortcut(): Boolean {
    if (!BuildConfig.DEBUG) return false
    val nextOpen = !_uiState.value.isInspectorOpen
    setInspectorOpen(open = nextOpen, openInHalf = nextOpen)
    return nextOpen
  }

  fun setMockModeEnabled(enabled: Boolean) {
    if (!BuildConfig.DEBUG) return
    userTouchedInspectorOrMock = true
    if (!enabled) {
      pauseSimulateDrive()
    }
    _uiState.update {
      it.copy(
        isMockModeActive = enabled,
        simulationStatusMessage = if (!enabled) null else it.simulationStatusMessage,
      )
    }
    locationTracker.setMockMode(enabled)
    recordPositionSource(if (enabled) "MOCK_ENABLED" else "LIVE_ENABLED")
    ensureJitterLoop()
    viewModelScope.launch { settingsRepo.setMockLocationEnabled(enabled) }
  }

  fun setDebugStatusFlags(
    poorGps: Boolean = _uiState.value.debugPoorGps,
    offline: Boolean = _uiState.value.debugOffline,
    uncertain: Boolean = _uiState.value.debugUncertain,
  ) {
    if (!BuildConfig.DEBUG) return
    val prevOffline = _uiState.value.debugOffline
    DebugNetworkSimulator.setOffline(offline)
    _uiState.update {
      it.copy(
        debugPoorGps = poorGps,
        debugOffline = offline,
        debugUncertain = uncertain,
        showOfflineChipAfter5s = if (offline) true else it.showOfflineChipAfter5s,
        isOfflineCached = if (offline && roadGraphRepository.currentSnapshot().graph.ways.isNotEmpty()) true else if (!offline && isNetworkOnline()) false else it.isOfflineCached,
      )
    }
    if (prevOffline != offline) {
      _uiState.value.location?.let { loc ->
        startCorridorFetch(loc, _uiState.value.travelMode, forceFetch = false, debounceHeadingTurn = false)
      }
    }
  }

  fun setDebugNetworkSimulation(
    blockOverpass: Boolean = _uiState.value.debugBlockOverpass,
    blockTiles: Boolean = _uiState.value.debugBlockTiles,
    slowNetwork: Boolean = _uiState.value.debugSlowNetwork,
    forceOverpassError: Int? = _uiState.value.debugForceOverpassError,
  ) {
    if (!BuildConfig.DEBUG) return
    DebugNetworkSimulator.setBlockOverpass(blockOverpass)
    DebugNetworkSimulator.setBlockTiles(blockTiles)
    DebugNetworkSimulator.setSlowNetwork(slowNetwork)
    DebugNetworkSimulator.setForceOverpassError(forceOverpassError)
    _uiState.update {
      it.copy(
        debugBlockOverpass = blockOverpass,
        debugBlockTiles = blockTiles,
        debugSlowNetwork = slowNetwork,
        debugForceOverpassError = forceOverpassError,
      )
    }
  }

  fun resetDebugOverrides() {
    if (!BuildConfig.DEBUG) return
    val wasOfflineOrBlocked =
      _uiState.value.debugOffline ||
        _uiState.value.debugBlockOverpass ||
        _uiState.value.debugBlockTiles ||
        _uiState.value.debugForceOverpassError != null
    DebugNetworkSimulator.reset()
    _uiState.update {
      it.copy(
        debugPoorGps = false,
        debugOffline = false,
        debugUncertain = false,
        debugBlockOverpass = false,
        debugBlockTiles = false,
        debugSlowNetwork = false,
        debugForceOverpassError = null,
        showOfflineChipAfter5s = false,
      )
    }
    if (wasOfflineOrBlocked) {
      _uiState.value.location?.let { loc ->
        startCorridorFetch(loc, _uiState.value.travelMode, forceFetch = false, debounceHeadingTurn = false)
      }
    }
  }

  fun testOverpass() {
    if (!BuildConfig.DEBUG || testOverpassJob?.isActive == true) return
    val center =
      _uiState.value.location?.point
        ?: if (_uiState.value.isMockModeActive) locationTracker.getBaseMockPoint() else null
    if (center == null) return
    val mode = _uiState.value.travelMode
    testOverpassJob =
      viewModelScope.launch {
        _uiState.update { it.copy(isTestingOverpass = true) }
        val results =
          try {
            overpassDataSource.testAllEndpoints(center, mode)
          } catch (_: Throwable) {
            emptyList()
          }
        _uiState.update {
          it.copy(
            isTestingOverpass = false,
            overpassTestResults = results,
          )
        }
        refreshPipelineDiagnostics()
      }
  }

  fun pingOverpass() {
    if (!BuildConfig.DEBUG || testOverpassJob?.isActive == true) return
    val center =
      _uiState.value.location?.point
        ?: if (_uiState.value.isMockModeActive) locationTracker.getBaseMockPoint() else null
    if (center == null) return
    testOverpassJob =
      viewModelScope.launch {
        _uiState.update { it.copy(isTestingOverpass = true) }
        val results =
          try {
            overpassDataSource.pingAllEndpoints(center)
          } catch (_: Throwable) {
            emptyList()
          }
        _uiState.update {
          it.copy(
            isTestingOverpass = false,
            overpassTestResults = results,
          )
        }
        refreshPipelineDiagnostics()
      }
  }

  fun onMapTappedInInspector(point: GeoPoint) {
    if (!BuildConfig.DEBUG || !_uiState.value.isMockModeActive) return
    pauseSimulateDrive()
    userTouchedInspectorOrMock = true
    lastConfirmedRoadPoint = null
    trailingBreadcrumbs.clear()
    _uiState.update { it.copy(lastConfirmedRoadName = null, tileFallbackRoad = null, roadNameSource = "none") }
    val heading = locationTracker.getBaseMockHeadingDeg()
    locationTracker.updateMockLocation(point, heading)
    recordPositionSource("MOCK_TAP")
    viewModelScope.launch { settingsRepo.setLastMockState(point.lat, point.lon, heading) }
    val snapshot =
      LocationSnapshot(
        point = point,
        headingDeg = heading,
        speedMps = _uiState.value.mockSpeedKmh / 3.6,
        accuracyMeters = _uiState.value.mockAccuracyMeters,
        isMock = true,
        timestampMs = System.currentTimeMillis(),
        hasReliableHeading = true,
      )
    onLocationUpdated(snapshot, forceFetch = true)
  }

  fun onMockHeadingChanged(headingDeg: Double) {
    if (!BuildConfig.DEBUG) return
    pauseSimulateDrive()
    userTouchedInspectorOrMock = true
    val wasMock = _uiState.value.isMockModeActive
    if (!wasMock) {
      _uiState.update { it.copy(isMockModeActive = true) }
      viewModelScope.launch { settingsRepo.setMockLocationEnabled(true) }
    }
    locationTracker.updateMockHeading(headingDeg, enforceMock = true)
    recordPositionSource("MOCK_HEADING")
    ensureJitterLoop()
    mockHeadingSaveJob?.cancel()
    mockHeadingSaveJob = viewModelScope.launch {
      delay(180L)
      settingsRepo.setLastMockHeading(headingDeg)
    }
  }

  fun snapHeadingToMatchedRoad() {
    if (!BuildConfig.DEBUG) return
    val primary =
      _uiState.value.matchResult.primary
        ?: _uiState.value.offRoadState.selectedCandidate?.candidate
        ?: return
    val pts = primary.way.points
    if (pts.size < 2) return
    val idx = primary.segmentIndex.coerceIn(0, pts.lastIndex - 1)
    val rawBearing = GeoMath.bearingDeg(pts[idx], pts[idx + 1])
    val aligned = if (primary.forwardAlongWay) rawBearing else (rawBearing + 180.0) % 360.0
    onMockHeadingChanged(aligned)
  }

  fun stepMockAlongRoad(deltaMeters: Double) {
    if (!BuildConfig.DEBUG) return
    pauseSimulateDrive()
    userTouchedInspectorOrMock = true
    if (!_uiState.value.isMockModeActive) {
      setMockModeEnabled(true)
    }
    val basePt = locationTracker.getBaseMockPoint() ?: return
    val baseHdg = locationTracker.getBaseMockHeadingDeg()
    val mode = _uiState.value.travelMode
    val res =
      RoadGeometryStepper.stepAlongRoad(
        graph = roadGraphRepository.currentSnapshot().graph,
        matched = _uiState.value.matchResult.primary,
        currentPoint = basePt,
        currentHeadingDeg = baseHdg,
        deltaMeters = deltaMeters,
        mode = mode,
        preferredBranchWayId = _uiState.value.selectedSimulationBranchWayId,
      )
    _uiState.update {
      it.copy(
        selectedSimulationBranchWayId = if (res.consumedPreferredBranch) null else it.selectedSimulationBranchWayId,
        simulationStatusMessage = if (res.hitDeadEnd && deltaMeters > 0.0) "Stopped at dead end" else null,
      )
    }
    locationTracker.updateMockLocation(res.point, res.headingDeg)
    recordPositionSource("MOCK_STEP(${deltaMeters.toInt()}m)")
    viewModelScope.launch { settingsRepo.setLastMockState(res.point.lat, res.point.lon, res.headingDeg) }
  }

  fun toggleSimulateDrive() {
    if (!BuildConfig.DEBUG) return
    if (_uiState.value.isSimulatingDrive) {
      pauseSimulateDrive()
      return
    }
    userTouchedInspectorOrMock = true
    if (!_uiState.value.isMockModeActive) {
      setMockModeEnabled(true)
    }
    val initialMockPt = locationTracker.getBaseMockPoint() ?: return
    jitterJob?.cancel()
    jitterJob = null
    _uiState.update {
      it.copy(
        isSimulatingDrive = true,
        simulationStatusMessage = null,
        displayLocationPoint = initialMockPt,
        displayHeadingDeg = locationTracker.getBaseMockHeadingDeg(),
      )
    }
    simulateDriveJob?.cancel()
    simulateDriveJob =
      viewModelScope.launch {
        while (isActive && _uiState.value.isSimulatingDrive && _uiState.value.isMockModeActive) {
          val speedKmh = _uiState.value.mockSpeedKmh
          if (speedKmh <= 0.1) {
            delay(250L)
            continue
          }
          val speedMps = speedKmh / 3.6
          val mode = _uiState.value.travelMode
          val startPt = locationTracker.getBaseMockPoint() ?: break
          val startHdg = locationTracker.getBaseMockHeadingDeg()
          val prefWayId = _uiState.value.selectedSimulationBranchWayId
          val stepRes =
            RoadGeometryStepper.stepAlongRoad(
              graph = roadGraphRepository.currentSnapshot().graph,
              matched = _uiState.value.matchResult.primary,
              currentPoint = startPt,
              currentHeadingDeg = startHdg,
              deltaMeters = speedMps * 1.0,
              mode = mode,
              preferredBranchWayId = prefWayId,
            )

          if (stepRes.hitDeadEnd && stepRes.movedMeters < 0.05) {
            _uiState.update {
              it.copy(
                isSimulatingDrive = false,
                simulationStatusMessage = "Stopped at dead end",
                displayLocationPoint = null,
                displayHeadingDeg = null,
              )
            }
            ensureJitterLoop()
            break
          }

          val frames = 20
          for (frame in 1 until frames) {
            delay(50L)
            if (!isActive || !_uiState.value.isSimulatingDrive) break
            val frac = frame.toDouble() / frames.toDouble()
            val interpPt = RoadGeometryStepper.interpolatePoint(startPt, stepRes.point, frac)
            val interpHdg = RoadGeometryStepper.interpolateHeadingDeg(startHdg, stepRes.headingDeg, frac)
            _uiState.update {
              it.copy(
                displayLocationPoint = interpPt,
                displayHeadingDeg = interpHdg,
              )
            }
          }
          if (!isActive || !_uiState.value.isSimulatingDrive) break
          delay(50L)

          _uiState.update {
            it.copy(
              displayLocationPoint = null,
              displayHeadingDeg = null,
              selectedSimulationBranchWayId =
                if (stepRes.consumedPreferredBranch) null else it.selectedSimulationBranchWayId,
              simulationStatusMessage = if (stepRes.hitDeadEnd) "Stopped at dead end" else null,
            )
          }
          locationTracker.updateMockLocation(stepRes.point, stepRes.headingDeg)
          recordPositionSource("MOCK_SIM")
          viewModelScope.launch {
            settingsRepo.setLastMockState(stepRes.point.lat, stepRes.point.lon, stepRes.headingDeg)
          }
          if (stepRes.hitDeadEnd) {
            _uiState.update { it.copy(isSimulatingDrive = false) }
            ensureJitterLoop()
            break
          }
        }
      }
  }

  fun pauseSimulateDrive(reason: String? = null) {
    val wasSimulating = _uiState.value.isSimulatingDrive || simulateDriveJob?.isActive == true
    val midPt = _uiState.value.displayLocationPoint
    val midHdg = _uiState.value.displayHeadingDeg
    simulateDriveJob?.cancel()
    simulateDriveJob = null
    if (wasSimulating) {
      _uiState.update {
        it.copy(
          isSimulatingDrive = false,
          displayLocationPoint = null,
          displayHeadingDeg = null,
          simulationStatusMessage = reason ?: it.simulationStatusMessage,
        )
      }
      if (midPt != null && _uiState.value.isMockModeActive) {
        val hdg = midHdg ?: locationTracker.getBaseMockHeadingDeg()
        locationTracker.updateMockLocation(midPt, hdg)
        viewModelScope.launch { settingsRepo.setLastMockState(midPt.lat, midPt.lon, hdg) }
      }
      ensureJitterLoop()
    }
  }

  fun setMockSpeedKmh(speedKmh: Double) {
    if (!BuildConfig.DEBUG) return
    val clamped = speedKmh.coerceIn(0.0, 120.0)
    userTouchedInspectorOrMock = true
    _uiState.update { it.copy(mockSpeedKmh = clamped) }
    locationTracker.setMockSpeedKmh(clamped)
    mockSpeedSaveJob?.cancel()
    mockSpeedSaveJob = viewModelScope.launch {
      delay(180L)
      settingsRepo.setMockSpeedKmh(clamped)
    }
  }

  fun setMockAccuracyMeters(accuracyMeters: Float) {
    if (!BuildConfig.DEBUG) return
    val clamped = accuracyMeters.coerceIn(3.0f, 50.0f)
    userTouchedInspectorOrMock = true
    _uiState.update { it.copy(mockAccuracyMeters = clamped) }
    locationTracker.setMockAccuracyMeters(clamped)
    mockAccuracySaveJob?.cancel()
    mockAccuracySaveJob = viewModelScope.launch {
      delay(180L)
      settingsRepo.setMockAccuracyMeters(clamped)
    }
  }

  fun setMockJitterEnabled(enabled: Boolean) {
    if (!BuildConfig.DEBUG) return
    userTouchedInspectorOrMock = true
    _uiState.update { it.copy(mockJitterEnabled = enabled) }
    locationTracker.setMockJitterEnabled(enabled)
    recordPositionSource(if (enabled) "MOCK_JITTER_ON" else "MOCK_JITTER_OFF")
    ensureJitterLoop()
    viewModelScope.launch { settingsRepo.setMockJitterEnabled(enabled) }
  }

  private fun ensureJitterLoop() {
    jitterJob?.cancel()
    jitterJob = null
    if (!BuildConfig.DEBUG) return
    val st = _uiState.value
    if (st.isMockModeActive && st.mockJitterEnabled && !st.isSimulatingDrive) {
      jitterJob =
        viewModelScope.launch {
          while (isActive) {
            delay(1_000L)
            val cur = _uiState.value
            if (!cur.isMockModeActive || !cur.mockJitterEnabled || cur.isSimulatingDrive) break
            locationTracker.refreshJitterSnapshot()
            recordPositionSource("MOCK_JITTER")
          }
        }
    }
  }

  fun selectSimulationBranch(wayId: Long) {
    if (!BuildConfig.DEBUG || !_uiState.value.isMockModeActive) return
    _uiState.update { state ->
      val next = if (state.selectedSimulationBranchWayId == wayId) null else wayId
      state.copy(selectedSimulationBranchWayId = next)
    }
  }

  fun saveTestSpot(
    name: String,
    point: GeoPoint? = null,
    headingDeg: Double? = null,
    mode: TravelMode? = null,
  ) {
    if (!BuildConfig.DEBUG) return
    val pt = point ?: locationTracker.getBaseMockPoint() ?: return
    val hdg = ((headingDeg ?: locationTracker.getBaseMockHeadingDeg()) % 360.0 + 360.0) % 360.0
    val travelMode = mode ?: _uiState.value.travelMode
    val cleanName =
      name.trim().ifEmpty {
        String.format(Locale.US, "Spot %.4f, %.4f", pt.lat, pt.lon)
      }
    val newSpot =
      TestSpot(
        id = "spot_${System.currentTimeMillis()}_${_uiState.value.savedTestSpots.size}",
        name = cleanName,
        lat = pt.lat,
        lon = pt.lon,
        headingDeg = hdg,
        travelMode = travelMode,
        createdAtMs = System.currentTimeMillis(),
      )
    val updated = (listOf(newSpot) + _uiState.value.savedTestSpots).take(30)
    _uiState.update { it.copy(savedTestSpots = updated) }
    viewModelScope.launch { settingsRepo.setSavedTestSpots(updated) }
  }

  fun saveCensusEntryAsSpot(entry: FeatureCensusEntry, customName: String? = null) {
    if (!BuildConfig.DEBUG) return
    val label =
      customName?.trim()?.takeIf { it.isNotEmpty() }
        ?: "${entry.type.name.replace('_', ' ').lowercase(Locale.US)} (${entry.nearestDistanceMeters.toInt()}m)"
    saveTestSpot(
      name = label,
      point = entry.jumpPoint,
      headingDeg = entry.jumpHeadingDeg,
      mode = _uiState.value.travelMode,
    )
  }

  fun loadTestSpot(spot: TestSpot) {
    if (!BuildConfig.DEBUG) return
    pauseSimulateDrive()
    userTouchedInspectorOrMock = true
    selectedOffRoadWayId = null
    selectedOffRoadForwardOverride = null
    if (!_uiState.value.isMockModeActive) {
      _uiState.update { it.copy(isMockModeActive = true) }
      locationTracker.setMockMode(true)
      viewModelScope.launch { settingsRepo.setMockLocationEnabled(true) }
    }
    if (_uiState.value.travelMode != spot.travelMode) {
      _uiState.update { it.copy(travelMode = spot.travelMode, selectedFeatureIndex = 1) }
      viewModelScope.launch { settingsRepo.setTravelMode(spot.travelMode) }
    }
    val pt = GeoPoint(spot.lat, spot.lon)
    val heading = ((spot.headingDeg % 360.0) + 360.0) % 360.0
    lastConfirmedRoadPoint = null
    trailingBreadcrumbs.clear()
    locationTracker.updateMockLocation(pt, heading)
    recordPositionSource("MOCK_SPOT(${spot.name.take(12)})")
    viewModelScope.launch { settingsRepo.setLastMockState(spot.lat, spot.lon, heading) }
    _uiState.update {
      it.copy(
        isFollowingUser = true,
        recenterSignal = it.recenterSignal + 1,
        simulationStatusMessage = null,
        lastConfirmedRoadName = null,
        tileFallbackRoad = null,
        roadNameSource = "none",
      )
    }
    val snapshot =
      LocationSnapshot(
        point = pt,
        headingDeg = heading,
        speedMps = _uiState.value.mockSpeedKmh / 3.6,
        accuracyMeters = _uiState.value.mockAccuracyMeters,
        isMock = true,
        timestampMs = System.currentTimeMillis(),
        hasReliableHeading = true,
      )
    onLocationUpdated(snapshot, forceFetch = true)
  }

  fun deleteTestSpot(spotId: String) {
    if (!BuildConfig.DEBUG) return
    val updated = _uiState.value.savedTestSpots.filterNot { it.id == spotId }
    _uiState.update { it.copy(savedTestSpots = updated) }
    viewModelScope.launch { settingsRepo.setSavedTestSpots(updated) }
  }

  fun restoreDefaultTestSpots() {
    if (!BuildConfig.DEBUG) return
    val seeds =
      AppConfig.WORLDWIDE_SEED_TEST_SPOTS.map {
        TestSpot(
          id = it.id,
          name = it.name,
          lat = it.lat,
          lon = it.lon,
          headingDeg = it.headingDeg,
          travelMode = TravelMode.DRIVING,
          createdAtMs = 0L,
        )
      }
    val existing = _uiState.value.savedTestSpots
    val existingIds = existing.mapTo(HashSet()) { it.id }
    val restored = existing + seeds.filter { it.id !in existingIds }
    _uiState.update { it.copy(savedTestSpots = restored) }
    viewModelScope.launch { settingsRepo.setSavedTestSpots(restored) }
  }

  fun setDeveloperOrientation(orientation: DeveloperOrientation) {
    if (!BuildConfig.DEBUG) return
    _uiState.update { it.copy(settings = it.settings.copy(developerOrientation = orientation)) }
    viewModelScope.launch { settingsRepo.setDeveloperOrientation(orientation) }
  }

  fun setLandscapePanelSide(side: LandscapePanelSide) {
    _uiState.update { it.copy(settings = it.settings.copy(landscapePanelSide = side)) }
    viewModelScope.launch { settingsRepo.setLandscapePanelSide(side) }
  }

  private fun recordPositionSource(source: String) {
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    val entry = "$timeStr $source"
    _uiState.update { state ->
      state.copy(recentPositionSources = (listOf(entry) + state.recentPositionSources).take(5))
    }
  }

  fun jumpToCensusFeature(entry: FeatureCensusEntry) {
    if (!BuildConfig.DEBUG) return
    pauseSimulateDrive()
    userTouchedInspectorOrMock = true
    selectedOffRoadWayId = null
    selectedOffRoadForwardOverride = null
    if (!_uiState.value.isMockModeActive) {
      setMockModeEnabled(true)
    }
    lastConfirmedRoadPoint = null
    trailingBreadcrumbs.clear()
    _uiState.update { it.copy(lastConfirmedRoadName = null, tileFallbackRoad = null, roadNameSource = "none") }
    locationTracker.updateMockLocation(entry.jumpPoint, entry.jumpHeadingDeg)
    recordPositionSource("MOCK_JUMP")
    viewModelScope.launch {
      settingsRepo.setLastMockState(entry.jumpPoint.lat, entry.jumpPoint.lon, entry.jumpHeadingDeg)
    }
    _uiState.update { it.copy(isFollowingUser = true, recenterSignal = it.recenterSignal + 1) }
  }

  fun setThemePreference(pref: ThemePreference) {
    viewModelScope.launch { settingsRepo.setTheme(pref) }
  }

  fun setPanelStyle(style: PanelStyle) {
    viewModelScope.launch { settingsRepo.setPanelStyle(style) }
  }

  fun setUnitPreference(pref: UnitPreference) {
    val resolved = GeoMath.resolveEffectiveUnitSystem(pref)
    _uiState.update {
      it.copy(
        settings = it.settings.copy(unitPreference = pref),
        effectiveUnitSystem = resolved,
      )
    }
    viewModelScope.launch { settingsRepo.setUnitPreference(pref) }
    refreshPipelineDiagnostics()
  }

  fun setHorizonDistanceMeters(meters: Double) {
    val clamped = meters.coerceIn(AppConfig.DRIVING_MIN_LOOKAHEAD_M, AppConfig.DRIVING_MAX_LOOKAHEAD_M)
    _uiState.update { it.copy(settings = it.settings.copy(horizonDistanceMeters = clamped)) }
    viewModelScope.launch { settingsRepo.setHorizonDistanceMeters(clamped) }
    val loc = _uiState.value.location ?: return
    val newLookahead = AheadGraphAnalyzer.computeLookaheadMeters(_uiState.value.travelMode, loc.speedMps, clamped)
    _uiState.update { it.copy(lookaheadMeters = newLookahead) }
    val snap = roadGraphRepository.currentSnapshot()
    if (snap.graph.ways.isNotEmpty()) runAnalysis(snap, loc, _uiState.value.travelMode)
    startCorridorFetch(loc, _uiState.value.travelMode, forceFetch = false, debounceHeadingTurn = false)
  }

  fun setShowMinorJunctions(enabled: Boolean) {
    _uiState.update { it.copy(settings = it.settings.copy(showMinorJunctions = enabled)) }
    viewModelScope.launch { settingsRepo.setShowMinorJunctions(enabled) }
    val loc = _uiState.value.location ?: return
    val snap = roadGraphRepository.currentSnapshot()
    if (snap.graph.ways.isNotEmpty()) runAnalysis(snap, loc, _uiState.value.travelMode)
  }

  fun setLandmarksAheadEnabled(enabled: Boolean) {
    _uiState.update { it.copy(settings = it.settings.copy(landmarksAheadEnabled = enabled)) }
    viewModelScope.launch { settingsRepo.setLandmarksAheadEnabled(enabled) }
    val loc = _uiState.value.location ?: return
    val snap = roadGraphRepository.currentSnapshot()
    if (snap.graph.ways.isNotEmpty()) runAnalysis(snap, loc, _uiState.value.travelMode)
  }

  fun setShowGraphEdges(enabled: Boolean) {
    if (!BuildConfig.DEBUG) return
    _uiState.update { it.copy(settings = it.settings.copy(showGraphEdges = enabled)) }
    rebuildDebugEdges()
    viewModelScope.launch { settingsRepo.setShowGraphEdges(enabled) }
  }

  fun setArabicTtsEnabled(enabled: Boolean) {
    viewModelScope.launch { settingsRepo.setArabicTtsEnabled(enabled) }
  }

  fun setExperimentalBlurEnabled(enabled: Boolean) {
    viewModelScope.launch { settingsRepo.setExperimentalBlurEnabled(enabled) }
  }

  fun setReduceTransparencyEnabled(enabled: Boolean) {
    viewModelScope.launch { settingsRepo.setReduceTransparencyEnabled(enabled) }
  }

  fun retryRoadData() {
    val loc = _uiState.value.location ?: return
    overpassAutoRetryCount = 0
    startCorridorFetch(loc, _uiState.value.travelMode, forceFetch = true, debounceHeadingTurn = false)
  }

  fun reloadAllRoadData() {
    viewModelScope.launch {
      tileFetchJob?.cancel()
      overpassFetchJob?.cancel()
      retryFetchJob?.cancel()
      overpassDataSource.cancelAllActiveRequests()
      overpassDataSource.clearCache()
      tileDataSource.forceInvalidateTileJsonAndClearCache()
      AheadGraphAnalyzer.clearCompositeCache()
      roadGraphRepository.clear()
      overpassFailedForArea = false
      tileFailedForArea = false
      overpassAutoRetryCount = 0
      overpassAutoRetryCellKey = null
      previousMatchedWayId = null
      selectedOffRoadWayId = null
      selectedOffRoadForwardOverride = null
      lastConfirmedRoadPoint = null
      lastMapFeaturesQueriedCount = 0
      lastFallbackTriggerPoint = null
      trailingBreadcrumbs.clear()
      loadingOrFailureStartedMs = System.currentTimeMillis()
      _uiState.update {
        it.copy(
          isLoadingGraph = true,
          hasConfirmedGraphData = false,
          isGraphErrorState = false,
          isDataStaleOver10s = false,
          isUpdatingStaleData = false,
          isUsingBasicTileData = false,
          roadDataFailedStage = null,
          networkError = null,
          loadingStallReason = null,
          lastConfirmedRoadName = null,
          tileFallbackRoad = null,
          matchResult = MatchResult(null, null, false),
          offRoadState = OffRoadState(),
          features = emptyList(),
          featureCensus = emptyList(),
          debugGraphEdges = emptyList(),
          isDividedRoad = false,
          dividedRoadSeparationMeters = null,
          dividedRoadSideOffsetMeters = null,
          dividedRoadRuleId = null,
          dividedRoadConfidence = 0.0,
          roadNameSource = "none",
          needsMapFallbackWithin150m = false,
        )
      }
      refreshCacheCount()
      refreshPipelineDiagnostics()
      _uiState.value.location?.let { loc ->
        startCorridorFetch(loc, _uiState.value.travelMode, forceFetch = true, debounceHeadingTurn = false)
      }
    }
  }

  fun clearCache() {
    viewModelScope.launch {
      overpassDataSource.cancelAllActiveRequests()
      overpassDataSource.clearCache()
      tileDataSource.clearCache()
      AheadGraphAnalyzer.clearCompositeCache()
      roadGraphRepository.clear()
      overpassFailedForArea = false
      tileFailedForArea = false
      overpassAutoRetryCount = 0
      overpassAutoRetryCellKey = null
      previousMatchedWayId = null
      selectedOffRoadWayId = null
      selectedOffRoadForwardOverride = null
      lastConfirmedRoadPoint = null
      lastMapFeaturesQueriedCount = 0
      lastFallbackTriggerPoint = null
      trailingBreadcrumbs.clear()
      _uiState.update {
        it.copy(
          hasConfirmedGraphData = false,
          isUpdatingStaleData = false,
          isUsingBasicTileData = false,
          roadDataFailedStage = null,
          lastConfirmedRoadName = null,
          tileFallbackRoad = null,
          matchResult = MatchResult(null, null, false),
          offRoadState = OffRoadState(),
          features = emptyList(),
          featureCensus = emptyList(),
          debugGraphEdges = emptyList(),
          isDividedRoad = false,
          dividedRoadSeparationMeters = null,
          dividedRoadSideOffsetMeters = null,
          dividedRoadRuleId = null,
          dividedRoadConfidence = 0.0,
          roadNameSource = "none",
          needsMapFallbackWithin150m = false,
        )
      }
      refreshCacheCount()
      refreshPipelineDiagnostics()
      _uiState.value.location?.let { loc ->
        startCorridorFetch(loc, _uiState.value.travelMode, forceFetch = false, debounceHeadingTurn = false)
      }
    }
  }

  private fun refreshCacheCount() {
    viewModelScope.launch {
      val cells = overpassDataSource.getCachedCellCount()
      val tiles = tileDataSource.getCachedCellCount()
      val oldestCell = overpassDataSource.getOldestFetchedAtMs()
      val oldestTile = tileDataSource.getOldestFetchedAtMs()
      val oldestTs = listOfNotNull(oldestCell, oldestTile).minOrNull()
      val ageMs = oldestTs?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
      val snap = roadGraphRepository.currentSnapshot()
      val disk = tileDataSource.diskStats()
      _uiState.update {
        it.copy(
          cachedCellCount = cells,
          cachedTileCount = tiles,
          oldestCacheAgeMs = ageMs,
          memoryTileCount = snap.memoryTileCount,
          memoryHits = snap.memoryHits,
          memoryMisses = snap.memoryMisses,
          memoryEvictions = snap.evictions,
          diskTileCount = disk.fileCount,
          diskTileBytes = disk.totalBytes,
        )
      }
    }
  }

  fun updateSearchQuery(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
      searchJob?.cancel()
      _uiState.update { it.copy(localFoundResults = emptyList(), searchResults = emptyList(), isSearchingPlaces = false) }
      return
    }
    if (_uiState.value.searchScope == SearchScope.ON_MY_ROAD) {
      runLocalSearchNow(trimmed)
    }
  }

  fun setSearchScope(scope: SearchScope) {
    if (_uiState.value.searchScope == scope) return
    _uiState.update { it.copy(searchScope = scope) }
    val trimmed = _uiState.value.searchQuery.trim()
    if (trimmed.isNotEmpty()) {
      if (scope == SearchScope.ON_MY_ROAD) {
        searchJob?.cancel()
        _uiState.update { it.copy(isSearchingPlaces = false) }
        runLocalSearchNow(trimmed)
      } else {
        executeSearch()
      }
    }
  }

  fun selectRecentSearchQuery(query: String) {
    _uiState.update { it.copy(searchQuery = query) }
    executeSearch()
  }

  private fun runLocalSearchNow(query: String) {
    val snap = roadGraphRepository.currentSnapshot()
    val state = _uiState.value
    val matched = state.matchResult.primary ?: state.offRoadState.selectedCandidate?.candidate
    val locPt = state.location?.point
    val heading = state.location?.headingDeg
    val mode = state.travelMode
    val timeline = state.timelineEntries
    val crumbs = trailingBreadcrumbs.toList()

    searchJob?.cancel()
    searchJob =
      viewModelScope.launch {
        val localHits =
          withContext(Dispatchers.Default) {
            LocalRoadSearchIndex.searchLocal(
              query = query,
              graph = snap.graph,
              pois = snap.pois,
              matched = matched,
              locationPoint = locPt,
              headingDeg = heading,
              trailingBreadcrumbs = crumbs,
              mode = mode,
              timelineEntries = timeline,
            )
          }
        _uiState.update { it.copy(localFoundResults = localHits, isSearchingPlaces = false) }
      }
  }

  fun executeSearch() {
    val query = _uiState.value.searchQuery.trim()
    if (query.isEmpty()) return
    viewModelScope.launch { settingsRepo.addRecentSearch(query) }

    val stateSnapshot = _uiState.value
    val snap = roadGraphRepository.currentSnapshot()
    val matched = stateSnapshot.matchResult.primary ?: stateSnapshot.offRoadState.selectedCandidate?.candidate
    val activeOrigin = stateSnapshot.location?.point
    val activeHeading = stateSnapshot.location?.headingDeg
    val biasPoint = activeOrigin ?: GeoPoint(0.0, 0.0)
    val mode = stateSnapshot.travelMode
    val timeline = stateSnapshot.timelineEntries
    val crumbs = trailingBreadcrumbs.toList()

    searchJob?.cancel()
    searchJob =
      viewModelScope.launch {
        val localHits =
          withContext(Dispatchers.Default) {
            LocalRoadSearchIndex.searchLocal(
              query = query,
              graph = snap.graph,
              pois = snap.pois,
              matched = matched,
              locationPoint = activeOrigin,
              headingDeg = activeHeading,
              trailingBreadcrumbs = crumbs,
              mode = mode,
              timelineEntries = timeline,
            )
          }

        if (_uiState.value.searchScope == SearchScope.ON_MY_ROAD) {
          _uiState.update { it.copy(isSearchingPlaces = false, localFoundResults = localHits) }
          return@launch
        }

        _uiState.update { it.copy(isSearchingPlaces = true, searchResults = emptyList(), localFoundResults = localHits) }
        val result = searchService.searchPlaces(query, biasPoint, limit = 8)
        if (result.isFailure) {
          _uiState.update { it.copy(isSearchingPlaces = false, searchResults = emptyList()) }
          return@launch
        }
        val places = result.getOrThrow()
        if (activeOrigin == null) {
          _uiState.update {
            it.copy(
              isSearchingPlaces = false,
              searchResults = places.map { item -> item.copy(distanceMeters = null, distanceKind = DistanceKind.UNAVAILABLE) },
            )
          }
          return@launch
        }
        _uiState.update { it.copy(isSearchingPlaces = false, searchResults = places) }
        if (places.isEmpty()) return@launch

        // Compute road distances for all visible results in ONE Valhalla matrix request
        val matrix = routingService.computeDistanceMatrix(activeOrigin, places.map { it.point }, mode)
        _uiState.update { state ->
          state.copy(
            searchResults =
              state.searchResults.mapIndexed { idx, item ->
                val distRes = matrix.getOrNull(idx)
                if (distRes != null) {
                  item.copy(
                    distanceMeters = distRes.distanceMeters,
                    distanceKind = distRes.kind,
                  )
                } else {
                  item.copy(distanceMeters = null, distanceKind = DistanceKind.UNAVAILABLE)
                }
              },
          )
        }
      }
  }

  fun selectFoundRoadMatch(match: FoundRoadMatch) {
    if (_uiState.value.isRoutingToFoundRoad) {
      clearActiveRoute()
    }
    _uiState.update { state ->
      val nextIdx = match.matchedFeatureIndex ?: state.selectedFeatureIndex
      state.copy(
        activeFoundRoad = match,
        isSearchOpen = false,
        selectedPinId = null,
        selectedFeatureIndex = nextIdx,
      )
    }
  }

  fun clearFoundRoadMatch() {
    if (_uiState.value.isRoutingToFoundRoad) {
      clearActiveRoute()
    }
    _uiState.update { it.copy(activeFoundRoad = null) }
  }

  fun focusOverviewOnFoundRoad() {
    if (_uiState.value.activeFoundRoad == null) return
    toggleOverviewMode(true)
  }

  fun toggleRouteToFoundRoad() {
    if (_uiState.value.isRoutingToFoundRoad) {
      clearActiveRoute()
      return
    }
    val found = _uiState.value.activeFoundRoad ?: return
    val origin = _uiState.value.location?.point ?: return
    val mode = _uiState.value.travelMode

    routeJob?.cancel()
    routeJob =
      viewModelScope.launch {
        _uiState.update {
          it.copy(
            activeRoutePinId = null,
            isRoutingToFoundRoad = true,
            isLoadingRoute = true,
            explicitRoute = null,
          )
        }
        val route =
          routingService.computeRoute(
            origin = origin,
            destination = found.entryPoint,
            mode = mode,
            includeGeometry = true,
          )
        val valid = route.takeIf { r -> r.isAvailable && r.polyline.isNotEmpty() }
        _uiState.update {
          it.copy(
            explicitRoute = valid,
            isRoutingToFoundRoad = valid != null,
            isLoadingRoute = false,
          )
        }
      }
  }

  fun selectSearchPlace(place: SearchPlaceResult) {
    viewModelScope.launch {
      val saved = savedPinRepository.addOrUpdatePin(place)
      if (place.distanceKind != DistanceKind.LOADING) {
        pinDistanceCache[saved.id] =
          MatrixDistanceResult(
            distanceMeters = place.distanceMeters,
            kind = place.distanceKind,
          )
      }
      _uiState.update {
        it.copy(
          isSearchOpen = false,
          isFollowingUser = false,
          focusedPin = saved,
          focusPinSignal = it.focusPinSignal + 1,
          selectedPinId = null,
        )
      }
      publishSavedPins()
      if (place.distanceKind == DistanceKind.LOADING || place.distanceMeters == null) {
        refreshSavedPinsDistances(force = true)
      }
    }
  }

  fun openPinsSheet(open: Boolean) {
    _uiState.update { it.copy(isPinsSheetOpen = open) }
    if (open && rawSavedPins.isNotEmpty()) {
      refreshSavedPinsDistances(force = false)
    }
  }

  fun selectPinForCallout(pinId: Long?) {
    _uiState.update { it.copy(selectedPinId = pinId) }
  }

  fun focusOnPin(pin: SavedPin) {
    _uiState.update {
      it.copy(
        isPinsSheetOpen = false,
        isFollowingUser = false,
        focusedPin = pin,
        focusPinSignal = it.focusPinSignal + 1,
        selectedPinId = pin.id,
      )
    }
  }

  fun togglePinSortOrder() {
    _uiState.update {
      val next =
        if (it.pinSortOrder == PinSortOrder.NEAREST) PinSortOrder.NEWEST
        else PinSortOrder.NEAREST
      it.copy(pinSortOrder = next)
    }
    publishSavedPins()
  }

  fun routeToPin(pinId: Long, forceRecompute: Boolean = false) {
    if (!forceRecompute && _uiState.value.activeRoutePinId == pinId) {
      clearActiveRoute()
      return
    }
    val pin = _uiState.value.savedPins.firstOrNull { it.id == pinId } ?: return
    val origin = _uiState.value.location?.point ?: return
    val mode = _uiState.value.travelMode

    routeJob?.cancel()
    routeJob =
      viewModelScope.launch {
        _uiState.update {
          it.copy(
            activeRoutePinId = pinId,
            isRoutingToFoundRoad = false,
            isLoadingRoute = true,
            explicitRoute = if (forceRecompute) it.explicitRoute else null,
          )
        }
        val route =
          routingService.computeRoute(
            origin = origin,
            destination = pin.point,
            mode = mode,
            includeGeometry = true,
          )
        val valid = route.takeIf { r -> r.isAvailable && r.polyline.isNotEmpty() }
        _uiState.update {
          it.copy(
            explicitRoute = valid,
            activeRoutePinId = if (valid != null) pinId else null,
            isLoadingRoute = false,
          )
        }
      }
  }

  fun clearActiveRoute() {
    routeJob?.cancel()
    _uiState.update {
      it.copy(
        activeRoutePinId = null,
        isRoutingToFoundRoad = false,
        explicitRoute = null,
        isLoadingRoute = false,
      )
    }
  }

  fun removePin(pinId: Long) {
    if (_uiState.value.activeRoutePinId == pinId) {
      clearActiveRoute()
    }
    _uiState.update {
      it.copy(selectedPinId = if (it.selectedPinId == pinId) null else it.selectedPinId)
    }
    viewModelScope.launch { savedPinRepository.removePin(pinId) }
  }

  fun clearAllPins() {
    clearActiveRoute()
    _uiState.update {
      it.copy(
        selectedPinId = null,
        isPinsSheetOpen = false,
      )
    }
    viewModelScope.launch { savedPinRepository.clearAll() }
  }

  private fun publishSavedPins() {
    val sortOrder = _uiState.value.pinSortOrder
    val enriched =
      rawSavedPins.map { pin ->
        val cached = pinDistanceCache[pin.id]
        if (cached != null) {
          pin.copy(distanceMeters = cached.distanceMeters, distanceKind = cached.kind)
        } else {
          pin
        }
      }
    val sorted =
      when (sortOrder) {
        PinSortOrder.NEAREST ->
          enriched.sortedWith(
            compareBy<SavedPin> { it.distanceMeters ?: Double.MAX_VALUE }
              .thenByDescending { it.createdAtMs },
          )
        PinSortOrder.NEWEST -> enriched.sortedByDescending { it.createdAtMs }
      }
    _uiState.update { state ->
      val validSelected = state.selectedPinId?.takeIf { id -> sorted.any { it.id == id } }
      val validRoute = state.activeRoutePinId?.takeIf { id -> sorted.any { it.id == id } }
      state.copy(
        savedPins = sorted,
        selectedPinId = validSelected,
        activeRoutePinId = validRoute,
        explicitRoute = if (validRoute != null) state.explicitRoute else null,
      )
    }
  }

  private fun ensurePinsRefreshTicker() {
    if (pinsTickerJob?.isActive == true) return
    pinsTickerJob =
      viewModelScope.launch {
        while (rawSavedPins.isNotEmpty()) {
          delay(15_000L)
          if (rawSavedPins.isEmpty()) break
          refreshSavedPinsDistances(force = false)
        }
      }
  }

  private fun refreshSavedPinsDistances(force: Boolean) {
    val pins = rawSavedPins
    if (pins.isEmpty()) return
    val origin = _uiState.value.location?.point ?: return
    val mode = _uiState.value.travelMode
    val now = System.currentTimeMillis()
    val elapsed = now - lastPinsMatrixAtMs
    val moved = lastPinsMatrixOrigin?.let { GeoMath.haversineMeters(it, origin) } ?: Double.MAX_VALUE
    val modeChanged = lastPinsMatrixMode != mode
    val hasUnresolved = pins.any { !pinDistanceCache.containsKey(it.id) }

    if (!force && !hasUnresolved && !modeChanged && moved <= 50.0 && elapsed < 15_000L) {
      return
    }
    if (!force && elapsed < 3_000L && !modeChanged && !hasUnresolved) {
      return
    }

    pinsMatrixJob?.cancel()
    pinsMatrixJob =
      viewModelScope.launch {
        lastPinsMatrixOrigin = origin
        lastPinsMatrixMode = mode
        lastPinsMatrixAtMs = System.currentTimeMillis()
        val matrix = routingService.computeDistanceMatrix(origin, pins.map { it.point }, mode)
        pins.forEachIndexed { idx, pin ->
          val res = matrix.getOrNull(idx)
          if (res != null) {
            pinDistanceCache[pin.id] = res
          }
        }
        publishSavedPins()
      }
  }

  fun ingestLiveMapVectorFeatures(
    features: List<LiveMapVectorFeature>,
    queriedCount: Int = features.size,
  ) {
    lastMapFeaturesQueriedCount = queriedCount.coerceAtLeast(features.size)
    if (features.isEmpty()) {
      refreshPipelineDiagnostics()
      return
    }
    val loc = _uiState.value.location ?: return
    val mode = _uiState.value.travelMode
    val currentSnap = roadGraphRepository.currentSnapshot()
    if (hasPrimaryEdgesWithinRadius(currentSnap, loc.point, 150.0)) return

    viewModelScope.launch(Dispatchers.Default) {
      val mapGraph = TileRoadGraphSource.buildRoadGraphFromLiveFeatures(features, mode)
      if (mapGraph.ways.isEmpty()) {
        refreshPipelineDiagnostics()
        return@launch
      }
      val snap = roadGraphRepository.setMapFallbackGraph(mapGraph, evalCenter = loc.point)
      lastFallbackTriggerPoint = loc.point
      val hasAnyNearby = hasEdgesWithinRadius(snap, loc.point, 150.0)
      val basicOnly = snap.overpassCellCount == 0
      _uiState.update {
        it.copy(
          hasConfirmedGraphData = hasAnyNearby || snap.mapFallbackUsed || it.hasConfirmedGraphData,
          isGraphErrorState = if (hasAnyNearby || snap.mapFallbackUsed) false else it.isGraphErrorState,
          roadDataFailedStage = if (hasAnyNearby || snap.mapFallbackUsed) null else it.roadDataFailedStage,
          isUsingBasicTileData = basicOnly,
          isOfflineCached = snap.graph.fromCache && !isNetworkOnline(),
          needsMapFallbackWithin150m = !snap.hasPrimaryEdgeWithin150m,
          roadNameSource = if (snap.mapFallbackUsed && it.roadNameSource == "none") "map" else it.roadNameSource,
        )
      }
      runAnalysis(snap, loc, mode)
      rebuildDebugEdges(snap)
    }
  }

  private fun hasEdgesWithinRadius(snap: RoadGraphRepository.GraphSnapshot, center: GeoPoint, radiusMeters: Double): Boolean =
    snap.index.queryWaysAround(center, radiusMeters).any { w ->
      w.points.size >= 2 && GeoMath.snapToPolyline(center, w.points).distanceMeters <= radiusMeters
    }

  private fun hasPrimaryEdgesWithinRadius(snap: RoadGraphRepository.GraphSnapshot, center: GeoPoint, radiusMeters: Double): Boolean =
    snap.index.queryWaysAround(center, radiusMeters).any { w ->
      w.source != GraphDataSource.MAP &&
        w.points.size >= 2 &&
        GeoMath.snapToPolyline(center, w.points).distanceMeters <= radiusMeters
    }

  private fun recordTrailingBreadcrumb(point: GeoPoint) {
    val last = trailingBreadcrumbs.lastOrNull()
    if (last != null) {
      val jumpDist = GeoMath.haversineMeters(last, point)
      if (jumpDist > 500.0) {
        trailingBreadcrumbs.clear()
      } else if (jumpDist < 80.0) {
        return
      }
    }
    trailingBreadcrumbs.addLast(point)
    while (trailingBreadcrumbs.size > 30 ||
      (trailingBreadcrumbs.size > 1 && GeoMath.haversineMeters(trailingBreadcrumbs.first(), point) > 2_200.0)
    ) {
      trailingBreadcrumbs.removeFirst()
    }
  }

  private fun onLocationUpdated(snapshot: LocationSnapshot, forceFetch: Boolean) {
    val mode = _uiState.value.travelMode
    val horizonSetting = _uiState.value.settings.horizonDistanceMeters
    val lookahead = AheadGraphAnalyzer.computeLookaheadMeters(mode, snapshot.speedMps, horizonSetting)
    recordTrailingBreadcrumb(snapshot.point)

    if (!snapshot.isMock) {
      val savedDist = lastSavedLivePoint?.let { GeoMath.haversineMeters(it, snapshot.point) } ?: Double.MAX_VALUE
      if (savedDist >= 100.0) {
        lastSavedLivePoint = snapshot.point
        viewModelScope.launch { settingsRepo.setLastLivePoint(snapshot.point.lat, snapshot.point.lon) }
      }
    }

    val rawSnap = roadGraphRepository.currentSnapshot()
    val reevaluatedSnap = roadGraphRepository.reevaluateFallbackForPosition(snapshot.point, minMoveMeters = 50.0)
    val curSnap = reevaluatedSnap ?: rawSnap
    val primaryNear150 = hasPrimaryEdgesWithinRadius(curSnap, snapshot.point, 150.0)
    val movedFromFallbackTrigger =
      lastFallbackTriggerPoint?.let { GeoMath.haversineMeters(it, snapshot.point) } ?: Double.MAX_VALUE
    val shouldBumpFallback = !primaryNear150 && movedFromFallbackTrigger >= 50.0
    if (shouldBumpFallback) {
      lastFallbackTriggerPoint = snapshot.point
    } else if (primaryNear150) {
      lastFallbackTriggerPoint = null
    }
    _uiState.update {
      it.copy(
        location = snapshot,
        lookaheadMeters = lookahead,
        needsMapFallbackWithin150m = !primaryNear150,
        mapFallbackTriggerTick = if (shouldBumpFallback) it.mapFallbackTriggerTick + 1 else it.mapFallbackTriggerTick,
      )
    }

    if (curSnap.graph.ways.isNotEmpty()) {
      runAnalysis(curSnap, snapshot, mode)
    } else {
      refreshPipelineDiagnostics(curSnap)
    }

    if (rawSavedPins.isNotEmpty()) {
      val movedFromMatrix = lastPinsMatrixOrigin?.let { GeoMath.haversineMeters(it, snapshot.point) } ?: Double.MAX_VALUE
      if (movedFromMatrix > 50.0) {
        refreshSavedPinsDistances(force = false)
      }
    }

    val movedDist = lastFetchCenter?.let { GeoMath.haversineMeters(it, snapshot.point) } ?: Double.MAX_VALUE
    val headingDelta = lastFetchHeading?.let { GeoMath.absAngleDiff(it, snapshot.headingDeg) } ?: Double.MAX_VALUE
    val isFirstFetch =
      lastFetchCenter == null ||
        (curSnap.graph.ways.isEmpty() && curSnap.memoryTileCount == 0 && curSnap.overpassCellCount == 0)
    val isHeadingOnlyTurn = !forceFetch && movedDist < 30.0
    val shouldSkipCancelInitialFetch =
      isHeadingOnlyTurn &&
        curSnap.graph.ways.isEmpty() &&
        (tileFetchJob?.isActive == true || overpassFetchJob?.isActive == true)

    if (!shouldSkipCancelInitialFetch &&
      (forceFetch || isFirstFetch || movedDist >= AppConfig.REFETCH_DISTANCE_METERS || headingDelta >= AppConfig.REFETCH_HEADING_DELTA_DEG)
    ) {
      // Never wipe active graph on movement; updateKeepWindow handles rolling eviction cleanly.
      startCorridorFetch(snapshot, mode, forceFetch, isHeadingOnlyTurn)
    }
  }

  private fun startCorridorFetch(
    snapshot: LocationSnapshot,
    mode: TravelMode,
    forceFetch: Boolean,
    debounceHeadingTurn: Boolean,
  ) {
    lastFetchCenter = snapshot.point
    lastFetchHeading = snapshot.headingDeg
    lastFetchMode = mode

    retryFetchJob?.cancel()
    overpassFailedForArea = false
    tileFailedForArea = false
    val cellKey = OverpassMapDataSource.buildCellKey(snapshot.point, mode)
    if (cellKey != overpassAutoRetryCellKey) {
      overpassAutoRetryCellKey = cellKey
      overpassAutoRetryCount = 0
    }

    val now = System.currentTimeMillis()
    val horizonMeters = _uiState.value.lookaheadMeters
    val breadcrumbs = trailingBreadcrumbs.toList()
    val (_, reqTiles) =
      tileDataSource.computeRequiredTiles(snapshot.point, snapshot.headingDeg, mode, horizonMeters, breadcrumbs)
    val keepKeys = reqTiles.mapTo(HashSet()) { it.key }
    val preSnap = roadGraphRepository.updateKeepWindow(snapshot.point, horizonMeters, keepKeys)

    val alreadyInMemoryFreshKeys =
      if (forceFetch) {
        emptySet()
      } else {
        val fresh = HashSet<String>()
        for (tile in reqTiles) {
          val hitFresh = roadGraphRepository.isTileFreshInMemory(tile.key, AppConfig.DATA_UPDATING_STALE_AGE_MS, now)
          roadGraphRepository.recordMemoryCacheProbe(hitFresh)
          if (hitFresh) fresh += tile.key
        }
        fresh
      }

    val hadNearbyBefore = hasEdgesWithinRadius(preSnap, snapshot.point, 150.0)
    val oldestUnderUser = if (hadNearbyBefore) preSnap.oldestAgeUnderPoint(snapshot.point) else null
    val staleWhileValid =
      hadNearbyBefore &&
        oldestUnderUser != null &&
        oldestUnderUser > AppConfig.DATA_UPDATING_STALE_AGE_MS &&
        isNetworkOnline()

    if (loadingOrFailureStartedMs == 0L) loadingOrFailureStartedMs = now
    if (!isNetworkOnline() && offlineStartedMs == 0L) offlineStartedMs = now

    ensureStatusTimingTicker()

    val keepActiveOverpass = debounceHeadingTurn && !forceFetch && overpassFetchJob?.isActive == true

    tileFetchJob?.cancel()
    tileFetchJob =
      viewModelScope.launch {
        if (debounceHeadingTurn && roadGraphRepository.currentSnapshot().graph.ways.isNotEmpty()) {
          delay(220L)
        }
        _uiState.update {
          it.copy(
            isLoadingGraph = !hadNearbyBefore,
            isUpdatingStaleData = staleWhileValid || (hadNearbyBefore && forceFetch),
            networkError = null,
            roadDataFailedStage = if (hadNearbyBefore) null else it.roadDataFailedStage,
          )
        }

        tileDataSource.fetchCorridorStreamWithWindow(
          center = snapshot.point,
          headingDeg = snapshot.headingDeg,
          mode = mode,
          horizonMeters = horizonMeters,
          alreadyInMemoryFreshKeys = alreadyInMemoryFreshKeys,
          trailingBreadcrumbs = breadcrumbs,
          forceRefresh = forceFetch,
        ).collect { res ->
          res.fold(
            onSuccess = { update ->
              tileFailedForArea = false
              val newSnap =
                if (update.decodedArtifacts.isNotEmpty()) {
                  roadGraphRepository.upsertDecodedArtifacts(update.decodedArtifacts)
                } else {
                  val key = update.tileKey ?: "batch_${System.currentTimeMillis()}"
                  val bounds =
                    update.graph.loadedTiles.firstOrNull()
                      ?: TileRoadGraphSource.computeTileBounds(
                        AppConfig.TILE_GRAPH_ZOOM,
                        TileRoadGraphSource.latLonToTile(snapshot.point.lat, snapshot.point.lon, AppConfig.TILE_GRAPH_ZOOM).first,
                        TileRoadGraphSource.latLonToTile(snapshot.point.lat, snapshot.point.lon, AppConfig.TILE_GRAPH_ZOOM).second,
                      )
                  roadGraphRepository.upsertTileGraph(
                    tileKey = key,
                    bounds = bounds,
                    tileGraph = update.graph,
                    fetchedAtMs = update.graph.fetchedAtMs,
                    isEmptyDecoded = update.emptyTileKeys.isNotEmpty(),
                    pois = update.pois,
                  )
                }

              val latestLoc = _uiState.value.location ?: snapshot
              val hasNearby = hasEdgesWithinRadius(newSnap, latestLoc.point, 150.0)
              val hasPrimaryNear150 = hasPrimaryEdgesWithinRadius(newSnap, latestLoc.point, 150.0)
              if (!hasPrimaryNear150) {
                lastFallbackTriggerPoint = latestLoc.point
              } else {
                lastFallbackTriggerPoint = null
              }
              val basicOnly = newSnap.overpassCellCount == 0 && (overpassFailedForArea || _uiState.value.isLoadingGraph)
              val allTilesDone = !update.isPartialOrStale
              _uiState.update {
                it.copy(
                  isLoadingGraph = if (allTilesDone && (hasNearby || hadNearbyBefore)) false else it.isLoadingGraph,
                  isUpdatingStaleData = if (allTilesDone) false else it.isUpdatingStaleData,
                  hasConfirmedGraphData = hasNearby || it.hasConfirmedGraphData,
                  isGraphErrorState = if (hasNearby) false else it.isGraphErrorState,
                  roadDataFailedStage = if (hasNearby) null else it.roadDataFailedStage,
                  isUsingBasicTileData = basicOnly,
                  isOfflineCached = newSnap.graph.fromCache && !isNetworkOnline(),
                  needsMapFallbackWithin150m = !hasPrimaryNear150,
                  mapFallbackTriggerTick = if (!hasPrimaryNear150) it.mapFallbackTriggerTick + 1 else it.mapFallbackTriggerTick,
                )
              }
              refreshCacheCount()
              runAnalysis(newSnap, latestLoc, mode)
              rebuildDebugEdges(newSnap)
            },
            onFailure = { err ->
              tileFailedForArea = true
              Log.w("TileGraphData", "Tile road graph unavailable: ${err.message}")
              _uiState.update { it.copy(isUpdatingStaleData = false) }
              refreshPipelineDiagnostics()
            },
          )
        }

        val postSnap = roadGraphRepository.currentSnapshot()
        val latestPostLoc = _uiState.value.location ?: snapshot
        val postNearby = hasEdgesWithinRadius(postSnap, latestPostLoc.point, 150.0)
        val postPrimaryNear150 = hasPrimaryEdgesWithinRadius(postSnap, latestPostLoc.point, 150.0)
        if (!postPrimaryNear150) {
          lastFallbackTriggerPoint = latestPostLoc.point
        }
        _uiState.update {
          it.copy(
            isLoadingGraph = if (postNearby || hadNearbyBefore || tileFailedForArea) false else it.isLoadingGraph,
            isUpdatingStaleData = false,
            hasConfirmedGraphData = postNearby || it.hasConfirmedGraphData,
            needsMapFallbackWithin150m = !postPrimaryNear150,
            mapFallbackTriggerTick = if (!postPrimaryNear150) it.mapFallbackTriggerTick + 1 else it.mapFallbackTriggerTick,
          )
        }
        refreshCacheCount()
        refreshPipelineDiagnostics(postSnap)
      }

    if (!keepActiveOverpass) {
      overpassFetchJob?.cancel()
      overpassFetchJob =
        viewModelScope.launch {
          if (debounceHeadingTurn && roadGraphRepository.currentSnapshot().graph.ways.isNotEmpty()) {
            delay(280L)
          }
          overpassDataSource.fetchCorridorStream(
            center = snapshot.point,
            headingDeg = snapshot.headingDeg,
            mode = mode,
            forceRefresh = forceFetch,
          ).collect { result ->
            result.fold(
              onSuccess = { update ->
                overpassFailedForArea = false
                val cellKey = OverpassMapDataSource.buildCellKey(snapshot.point, mode)
                val newSnap =
                  roadGraphRepository.upsertOverpassCell(
                    cellKey = cellKey,
                    cellCenter = snapshot.point,
                    cellGraph = update.graph,
                    fetchedAtMs = update.graph.fetchedAtMs,
                    pois = update.pois,
                  )
                val freshComplete = !update.isPartialOrStale
                val online = isNetworkOnline()
                if (freshComplete) {
                  loadingOrFailureStartedMs = 0L
                  statusTimingJob?.cancel()
                }
                if (online && update.isFromNetwork) {
                  offlineStartedMs = 0L
                }
                val latestLoc = _uiState.value.location ?: snapshot
                val hasPrimaryNear150 = hasPrimaryEdgesWithinRadius(newSnap, latestLoc.point, 150.0)
                _uiState.update {
                  it.copy(
                    isLoadingGraph = update.isPartialOrStale && !hadNearbyBefore,
                    isUpdatingStaleData = if (freshComplete) false else it.isUpdatingStaleData,
                    hasConfirmedGraphData = true,
                    isGraphErrorState = false,
                    roadDataFailedStage = null,
                    isUsingBasicTileData = false,
                    isDataStaleOver10s = if (freshComplete) false else it.isDataStaleOver10s,
                    showSlowDataChipAfter5s = if (freshComplete) false else it.showSlowDataChipAfter5s,
                    showOfflineChipAfter5s = if (online && update.isFromNetwork) false else it.showOfflineChipAfter5s,
                    loadingStallReason = if (freshComplete) null else it.loadingStallReason,
                    isOfflineCached = newSnap.graph.fromCache && !online,
                    networkError = null,
                    needsMapFallbackWithin150m = !hasPrimaryNear150,
                  )
                }
                refreshCacheCount()
                runAnalysis(newSnap, latestLoc, mode)
                rebuildDebugEdges(newSnap)
              },
              onFailure = { err ->
                overpassFailedForArea = true
                val errMsg = err.message ?: "Network error"
                Log.w("OverpassData", "Corridor load failed: $errMsg")
                val online = isNetworkOnline()
                val stallReason =
                  if (!online) "Offline: no cached Overpass cells for this area"
                  else "Overpass request failed: $errMsg"
                if (loadingOrFailureStartedMs == 0L) {
                  loadingOrFailureStartedMs = System.currentTimeMillis()
                }
                if (!online && offlineStartedMs == 0L) {
                  offlineStartedMs = System.currentTimeMillis()
                }
                val snap = roadGraphRepository.currentSnapshot()
                refreshPipelineDiagnostics(snap)
                val latestPt = _uiState.value.location?.point ?: snapshot.point
                val hasNearbyEdges = hasEdgesWithinRadius(snap, latestPt, 150.0)
                val hasTileFallback = snap.tileEdgeCount > 0 && snap.overpassEdgeCount == 0 && hasNearbyEdges
                val failedStage =
                  if (!hasNearbyEdges) _uiState.value.pipelineDiagnostics.firstFailingStageName ?: "Overpass"
                  else null
                _uiState.update {
                  it.copy(
                    isLoadingGraph = false,
                    isUpdatingStaleData = false,
                    isGraphErrorState = !hasNearbyEdges,
                    roadDataFailedStage = failedStage,
                    isUsingBasicTileData = hasTileFallback,
                    loadingStallReason = stallReason,
                    isOfflineCached = !online || hasNearbyEdges,
                    networkError = errMsg,
                  )
                }
                ensureStatusTimingTicker()
                scheduleBackgroundRetry(mode)
              },
            )
          }
        }
    }
  }

  private fun ensureStatusTimingTicker() {
    if (statusTimingJob?.isActive == true) return
    statusTimingJob =
      viewModelScope.launch {
        while (true) {
          val state = _uiState.value
          if (!state.isLoadingGraph && !state.isGraphErrorState && !overpassFailedForArea && isNetworkOnline()) {
            offlineStartedMs = 0L
            loadingOrFailureStartedMs = 0L
            _uiState.update {
              it.copy(
                showSlowDataChipAfter5s = false,
                showOfflineChipAfter5s = false,
                isDataStaleOver10s = false,
                roadDataFailedStage = null,
                currentActiveOverpassAgeMs = null,
              )
            }
            break
          }

          val now = System.currentTimeMillis()
          val online = isNetworkOnline()
          if (!online && offlineStartedMs == 0L) {
            offlineStartedMs = now
          } else if (online) {
            offlineStartedMs = 0L
          }

          val loadOrFailElapsed =
            if (loadingOrFailureStartedMs > 0L) now - loadingOrFailureStartedMs else 0L
          val offlineElapsed =
            if (offlineStartedMs > 0L) now - offlineStartedMs else 0L
          val activeOverpassAge = overpassDataSource.currentActiveRequestAgeMs()

          val snap = roadGraphRepository.currentSnapshot()
          val curLocPt = state.location?.point
          val hasNearbyEdges = curLocPt != null && hasEdgesWithinRadius(snap, curLocPt, 150.0)
          val isEmptyArea = _uiState.value.pipelineDiagnostics.stages.any { it.isEmptyArea }
          val tileActive = tileFetchJob?.isActive == true
          val slowChip = tileActive && !hasNearbyEdges && !isEmptyArea && online && loadOrFailElapsed >= AppConfig.STATUS_CHIP_DELAY_MS
          val offlineChip = (!online && offlineElapsed >= AppConfig.STATUS_CHIP_DELAY_MS) || state.debugOffline
          val skeletonTimedOut = !hasNearbyEdges && !isEmptyArea && loadOrFailElapsed >= AppConfig.SKELETON_TIMEOUT_MS
          val staleOverThreshold =
            !hasNearbyEdges &&
              !isEmptyArea &&
              (state.isLoadingGraph || state.isGraphErrorState || overpassFailedForArea) &&
              loadOrFailElapsed >= AppConfig.STALE_FAILURE_THRESHOLD_MS

          val inspectorReason =
            when {
              !online -> "Offline: cached data only"
              state.networkError != null -> "Overpass request failed: ${state.networkError}"
              activeOverpassAge != null && activeOverpassAge >= AppConfig.LOADING_REASON_TIMEOUT_MS ->
                "Overpass request slow (>${activeOverpassAge / 1000L}s)"
              else -> state.loadingStallReason
            }

          if (skeletonTimedOut && state.roadDataFailedStage == null) {
            refreshPipelineDiagnostics(snap)
          }
          val failedStage =
            when {
              isEmptyArea -> null
              skeletonTimedOut || state.isGraphErrorState ->
                _uiState.value.pipelineDiagnostics.firstFailingStageName ?: state.roadDataFailedStage ?: "Overpass"
              else -> null
            }

          _uiState.update {
            it.copy(
              showSlowDataChipAfter5s = slowChip,
              showOfflineChipAfter5s = offlineChip,
              isDataStaleOver10s = staleOverThreshold || skeletonTimedOut,
              isGraphErrorState = if (isEmptyArea) false else (it.isGraphErrorState || skeletonTimedOut),
              roadDataFailedStage = failedStage,
              loadingStallReason = inspectorReason,
              currentActiveOverpassAgeMs = activeOverpassAge,
            )
          }
          refreshPipelineDiagnostics(snap)
          delay(1_000L)
        }
      }
  }

  private fun scheduleBackgroundRetry(mode: TravelMode) {
    if (retryFetchJob?.isActive == true) return
    val loc = _uiState.value.location ?: return
    val hasNearby = hasEdgesWithinRadius(roadGraphRepository.currentSnapshot(), loc.point, 150.0)
    if (hasNearby && overpassAutoRetryCount >= AppConfig.OVERPASS_MAX_RETRIES) return
    retryFetchJob =
      viewModelScope.launch {
        delay(6_000L)
        val latestLoc = _uiState.value.location ?: return@launch
        val snap = roadGraphRepository.currentSnapshot()
        val nearbyNow = hasEdgesWithinRadius(snap, latestLoc.point, 150.0)
        if (nearbyNow && overpassAutoRetryCount >= AppConfig.OVERPASS_MAX_RETRIES) return@launch
        if (_uiState.value.isGraphErrorState || (overpassFailedForArea && overpassAutoRetryCount < AppConfig.OVERPASS_MAX_RETRIES) || !_uiState.value.hasConfirmedGraphData) {
          overpassAutoRetryCount++
          startCorridorFetch(latestLoc, mode, forceFetch = false, debounceHeadingTurn = false)
        }
      }
  }

  private fun isNetworkOnline(): Boolean {
    if (DebugNetworkSimulator.isOfflineBlocked() || _uiState.value.debugOffline) return false
    return runCatching {
      val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
      val net = cm?.activeNetwork ?: return@runCatching false
      val caps = cm.getNetworkCapabilities(net) ?: return@runCatching false
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
  }

  private fun runAnalysis(
    snap: RoadGraphRepository.GraphSnapshot,
    snapshot: LocationSnapshot,
    mode: TravelMode,
  ) {
    analyzeJob?.cancel()
    val selWayId = selectedOffRoadWayId
    val selFwdOverride = selectedOffRoadForwardOverride
    val currentSettings = _uiState.value.settings
    val activeFoundSnapshot = _uiState.value.activeFoundRoad
    val crumbsSnapshot = trailingBreadcrumbs.toList()
    val graph = snap.graph

    analyzeJob =
      viewModelScope.launch {
        val output =
          withContext(Dispatchers.Default) {
            val m =
              MapMatcher.match(
                graph = graph,
                position = snapshot.point,
                headingDeg = snapshot.headingDeg,
                mode = mode,
                previousWayId = previousMatchedWayId,
                accuracyMeters = snapshot.accuracyMeters,
                speedMps = snapshot.speedMps,
                spatialIndex = snap.index,
                graphVersion = snap.version,
                sourceLabel = snap.sourceLabel,
              )

            if (m.primary != null) {
              previousMatchedWayId = m.primary.way.id
              selectedOffRoadWayId = null
              selectedOffRoadForwardOverride = null
              val res =
                AheadGraphAnalyzer.analyzeAheadDetailed(
                  graph = graph,
                  matched = m.primary,
                  mode = mode,
                  speedMps = snapshot.speedMps,
                  configuredHorizonMeters = currentSettings.horizonDistanceMeters,
                  showMinorJunctions = currentSettings.showMinorJunctions,
                  pois = snap.pois,
                  landmarksEnabled = currentSettings.landmarksAheadEnabled,
                )
              val offState =
                OffRoadState(
                  isOffRoad = false,
                  matchRadiusMeters = m.matchRadiusMeters,
                  searchRadiusMeters = m.matchRadiusMeters,
                )
              val updatedFound =
                activeFoundSnapshot?.let { found ->
                  LocalRoadSearchIndex.refreshFoundMatch(
                    existing = found,
                    graph = graph,
                    pois = snap.pois,
                    matched = m.primary,
                    locationPoint = snapshot.point,
                    headingDeg = snapshot.headingDeg,
                    trailingBreadcrumbs = crumbsSnapshot,
                    mode = mode,
                    timelineEntries = res.timelineEntries,
                  )
                }
              AnalysisOutput(m, offState, res.features, emptyList(), res.dividedRoad, res, updatedFound)
            } else {
              previousMatchedWayId = null
              val accStr = snapshot.accuracyMeters?.let { String.format(Locale.US, "GPS ±%.0fm", it) } ?: "GPS"
              val reason =
                String.format(
                  Locale.US,
                  "No road within %.0f m (%s); searched %.0f m",
                  m.matchRadiusMeters,
                  accStr,
                  m.offRoadSearchRadiusMeters,
                )

              val matchedCand =
                selWayId?.let { targetId ->
                  m.offRoadCandidates.firstOrNull { it.candidate.way.id == targetId }
                }

              if (matchedCand != null) {
                val resolved =
                  MapMatcher.resolveOffRoadSelection(
                    graph = graph,
                    candidate = matchedCand,
                    headingDeg = snapshot.headingDeg,
                    hasReliableHeading = snapshot.hasReliableHeading,
                    mode = mode,
                    speedMps = snapshot.speedMps,
                    explicitForwardAlongWay = selFwdOverride,
                  )
                val offState =
                  OffRoadState(
                    isOffRoad = true,
                    matchRadiusMeters = m.matchRadiusMeters,
                    searchRadiusMeters = m.offRoadSearchRadiusMeters,
                    reason = reason,
                    candidates = m.offRoadCandidates,
                    selectedCandidate = resolved.selectedCandidate,
                    needsDirectionChoice = resolved.needsDirectionChoice,
                    directionGroups = resolved.directionGroups,
                    chosenForwardAlongWay = resolved.activeForwardAlongWay,
                  )
                val updatedFound =
                  activeFoundSnapshot?.let { found ->
                    LocalRoadSearchIndex.refreshFoundMatch(
                      existing = found,
                      graph = graph,
                      pois = snap.pois,
                      matched = resolved.selectedCandidate?.candidate,
                      locationPoint = snapshot.point,
                      headingDeg = snapshot.headingDeg,
                      trailingBreadcrumbs = crumbsSnapshot,
                      mode = mode,
                      timelineEntries = emptyList(),
                    )
                  }
                AnalysisOutput(m, offState, resolved.activeFeatures, emptyList(), refreshedFound = updatedFound)
              } else {
                selectedOffRoadWayId = null
                selectedOffRoadForwardOverride = null
                val offState =
                  OffRoadState(
                    isOffRoad = true,
                    matchRadiusMeters = m.matchRadiusMeters,
                    searchRadiusMeters = m.offRoadSearchRadiusMeters,
                    reason = reason,
                    candidates = m.offRoadCandidates,
                  )
                val updatedFound =
                  activeFoundSnapshot?.let { found ->
                    LocalRoadSearchIndex.refreshFoundMatch(
                      existing = found,
                      graph = graph,
                      pois = snap.pois,
                      matched = null,
                      locationPoint = snapshot.point,
                      headingDeg = snapshot.headingDeg,
                      trailingBreadcrumbs = crumbsSnapshot,
                      mode = mode,
                      timelineEntries = emptyList(),
                    )
                  }
                AnalysisOutput(m, offState, emptyList(), emptyList(), refreshedFound = updatedFound)
              }
            }
          }

        val match = output.match
        val offRoad = output.offRoad
        val features = output.features
        val dividedRoad = output.dividedRoad
        val detailed = output.detailed
        val refreshedFound = output.refreshedFound

        _uiState.update { state ->
          val keptIndex =
            if (features.any { it.index == state.selectedFeatureIndex }) state.selectedFeatureIndex
            else features.firstOrNull()?.index ?: 1
          val movedFromConfirmed =
            lastConfirmedRoadPoint?.let { GeoMath.haversineMeters(it, snapshot.point) } ?: Double.MAX_VALUE
          val confirmedName =
            when {
              match.primary != null && match.primary.name.hasAny -> {
                lastConfirmedRoadPoint = snapshot.point
                match.primary.name
              }
              offRoad.selectedCandidate?.candidate?.name?.hasAny == true -> {
                lastConfirmedRoadPoint = snapshot.point
                offRoad.selectedCandidate.candidate.name
              }
              movedFromConfirmed > 300.0 -> {
                lastConfirmedRoadPoint = null
                null
              }
              else -> state.lastConfirmedRoadName
            }
          val activeWay = match.primary?.way ?: offRoad.selectedCandidate?.candidate?.way
          val source =
            when {
              activeWay?.source == GraphDataSource.TILES -> "tiles"
              activeWay?.source == GraphDataSource.MAP -> "map"
              activeWay?.source == GraphDataSource.OVERPASS -> "overpass"
              snap.mapFallbackUsed -> "map"
              snap.tileEdgeCount > 0 && snap.overpassEdgeCount > 0 -> "merged"
              snap.tileEdgeCount > 0 -> "tiles"
              snap.overpassEdgeCount > 0 -> "overpass"
              confirmedName != null -> "cached"
              else -> "none"
            }
          state.copy(
            matchResult = match,
            offRoadState = offRoad,
            features = features,
            timelineEntries = detailed?.timelineEntries ?: emptyList(),
            corridorWorldPoints = detailed?.corridorWorldPoints ?: emptyList(),
            loadedCorridorMeters = detailed?.loadedCorridorMeters ?: 0.0,
            hiddenMinorCount = detailed?.hiddenMinorCount ?: 0,
            landmarksAhead = detailed?.landmarksAhead ?: emptyList(),
            activeFoundRoad = if (state.activeFoundRoad != null) refreshedFound else null,
            selectedFeatureIndex = keptIndex,
            lastConfirmedRoadName = confirmedName,
            roadNameSource = source,
            tileFallbackRoad = if (match.primary != null) null else state.tileFallbackRoad,
            isDividedRoad = dividedRoad?.isDivided == true,
            dividedRoadSeparationMeters = dividedRoad?.separationMeters,
            dividedRoadSideOffsetMeters = dividedRoad?.partnerSideOffsetMeters,
            dividedRoadRuleId = dividedRoad?.ruleId,
            dividedRoadConfidence = dividedRoad?.confidence ?: 0.0,
          )
        }
        refreshPipelineDiagnostics(snap)
        rebuildDebugEdges(snap)
        scheduleLazyCensus(snap)

        if (_uiState.value.settings.arabicTtsEnabled && features.isNotEmpty()) {
          val first = features.first()
          val label = arabicFeatureAnnouncement(first)
          ttsAnnouncer.speakIfNew("${first.effectiveDisplayType()}_${first.anchorNodeId}", label)
        }
      }
  }

  private fun scheduleLazyCensus(snap: RoadGraphRepository.GraphSnapshot) {
    if (!BuildConfig.DEBUG) return
    val state = _uiState.value
    if (!state.isInspectorOpen || state.inspectorDetent != InspectorSheetDetent.FULL) return
    val loc = state.location ?: return
    val mode = state.travelMode
    censusJob?.cancel()
    censusJob =
      viewModelScope.launch {
        val census =
          withContext(Dispatchers.Default) {
            AheadGraphAnalyzer.computeFeatureCensus(snap.graph, loc.point, mode, 1500.0)
          }
        _uiState.update { it.copy(featureCensus = census) }
      }
  }

  private fun rebuildDebugEdges(snap: RoadGraphRepository.GraphSnapshot = roadGraphRepository.currentSnapshot()) {
    if (!BuildConfig.DEBUG || !_uiState.value.settings.showGraphEdges) {
      if (_uiState.value.debugGraphEdges.isNotEmpty()) {
        _uiState.update { it.copy(debugGraphEdges = emptyList()) }
      }
      return
    }
    val center = _uiState.value.location?.point
    val waysToShow =
      if (center != null) snap.index.queryWaysAround(center, 1_800.0)
      else snap.graph.ways.values
    val edges = ArrayList<DebugGraphEdge>(256)
    val matchedId = _uiState.value.matchResult.primary?.way?.id
      ?: _uiState.value.offRoadState.selectedCandidate?.candidate?.way?.id

    var tileCount = 0
    var ovpCount = 0
    for (way in waysToShow) {
      if (way.points.size < 2) continue
      if (way.id == matchedId) continue
      when (way.source) {
        GraphDataSource.OVERPASS -> if (ovpCount < 180) {
          edges += DebugGraphEdge(way.id, way.points, DebugEdgeCategory.OVERPASS)
          ovpCount++
        }
        else -> if (tileCount < 180) {
          edges += DebugGraphEdge(way.id, way.points, DebugEdgeCategory.TILE)
          tileCount++
        }
      }
      if (tileCount >= 180 && ovpCount >= 180) break
    }
    if (matchedId != null) {
      snap.graph.ways[matchedId]?.takeIf { it.points.size >= 2 }?.let { matchedWay ->
        edges += DebugGraphEdge(matchedWay.id, matchedWay.points, DebugEdgeCategory.MATCHED)
      }
    }
    _uiState.update { it.copy(debugGraphEdges = edges) }
  }

  private fun refreshPipelineDiagnostics(snap: RoadGraphRepository.GraphSnapshot = roadGraphRepository.currentSnapshot()) {
    val state = _uiState.value
    val loc = state.location
    val pt = loc?.point
    val urlInfo = tileDataSource.tileUrlInfo.value
    val fetchInfo = tileDataSource.tileFetchInfo.value
    val decodeInfo = tileDataSource.mvtDecodeInfo.value
    val ovpStats = overpassDataSource.endpointStats()
    val stats60s = overpassDataSource.loaderStats60s()
    val activeOvpAge = overpassDataSource.currentActiveRequestAgeMs()
    val g = snap.graph

    val localStats = if (pt != null) snap.index.computeLocalStats(pt) else null
    val edges150 = localStats?.edges150 ?: 0
    val edges500 = localStats?.edges500 ?: 0
    val nodes500 = localStats?.nodes500 ?: 0
    val edges1500 = localStats?.edges1500 ?: 0
    val nodes1500 = localStats?.nodes1500 ?: 0
    val nearestHit = localStats?.nearest
    val nearestDist = nearestHit?.distanceMeters ?: Double.MAX_VALUE
    val nearestWay = nearestHit?.way
    val nearestName =
      nearestWay?.let { w ->
        val bn = BilingualName.fromOsmTags(w.tags)
        bn.primaryText ?: bn.ref ?: "unnamed"
      } ?: "none"
    val nearestClass = nearestWay?.tags?.highway ?: "-"

    val tileBoundsStr =
      g.loadedTiles.takeIf { it.isNotEmpty() }?.let { tiles ->
        val minLat = tiles.minOf { it.south }
        val maxLat = tiles.maxOf { it.north }
        val minLon = tiles.minOf { it.west }
        val maxLon = tiles.maxOf { it.east }
        String.format(Locale.US, "[%.3f..%.3f, %.3f..%.3f]", minLat, maxLat, minLon, maxLon)
      } ?: "none"

    val nearestStr =
      if (nearestHit != null) {
        String.format(Locale.US, "nearest=%.0fm(%s/%s)", nearestDist, nearestName.take(18), nearestClass)
      } else {
        "nearest=none"
      }

    val centerTileKey =
      pt?.let {
        val (cx, cy) = TileRoadGraphSource.latLonToTile(it.lat, it.lon, AppConfig.TILE_GRAPH_ZOOM)
        "${AppConfig.TILE_GRAPH_ZOOM}_${cx}_${cy}"
      }
    val isEmptyArea =
      centerTileKey != null && snap.isCenterTileDecodedEmpty(centerTileKey) && edges1500 == 0

    val nearbyLabels =
      if (pt != null && nearestWay != null) {
        TileRoadGraphSource.inspectLabelsNearWay(nearestWay, snap.allLabelsNear(pt), radiusMeters = 20.0)
      } else {
        emptyList()
      }

    val stageA =
      PipelineStageLine(
        stageId = "a",
        label = "Tile URL",
        ok = urlInfo.ok,
        summary = "${urlInfo.howResolved} ${urlInfo.scheme} ${urlInfo.template.take(44)}",
        elapsedMs = if (urlInfo.fetchedAtMs > 0L) ((System.currentTimeMillis() - urlInfo.fetchedAtMs) / 1000L).coerceAtLeast(0L) else null,
        reason = urlInfo.reason,
      )
    val stageB =
      PipelineStageLine(
        stageId = "b",
        label = "Tile fetch",
        ok = fetchInfo.ok,
        summary = "z14 ${fetchInfo.centerTileKey} ${fetchInfo.okCount}/${fetchInfo.requestedCount} c=${fetchInfo.cacheHitCount} n=${fetchInfo.netHitCount} ${fetchInfo.totalBytes / 1024L}KB http=${fetchInfo.lastHttpStatus} enc=${fetchInfo.contentEncoding}",
        elapsedMs = fetchInfo.durationMs.takeIf { it > 0L },
        reason = fetchInfo.reason,
      )
    val timingPart = decodeInfo.timingBreakdown.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
    val stageC =
      PipelineStageLine(
        stageId = "c",
        label = "MVT decode",
        ok = decodeInfo.ok || isEmptyArea,
        summary = "tiles=${decodeInfo.tileCount} ext=${decodeInfo.extent} layers=[${decodeInfo.layersSummary}] ${decodeInfo.sampleCoords.take(36)}$timingPart",
        elapsedMs = decodeInfo.durationMs.takeIf { it > 0L },
        reason = decodeInfo.errorReason,
        isEmptyArea = isEmptyArea,
      )
    val graphIntegrityOk = snap.idCollisions == 0 && snap.danglingNodeRefs == 0
    val graphOk = edges1500 > 0 && graphIntegrityOk
    val avgPerTileStr = String.format(Locale.US, "%.1f", snap.waysPerTileAvg)
    val stageD =
      PipelineStageLine(
        stageId = "d",
        label = "Graph build",
        ok = graphOk || (isEmptyArea && graphIntegrityOk),
        summary = "v${snap.version} ways=${g.ways.size} tile=${snap.waysPerTileMin}/$avgPerTileStr/${snap.waysPerTileMax} col=${snap.idCollisions} dang=${snap.danglingNodeRefs} 150m:${edges150}e 500m:${nodes500}n/${edges500}e 1.5km:${edges1500}e bbox=$tileBoundsStr $nearestStr",
        reason = when {
          !graphIntegrityOk -> "col=${snap.idCollisions} dang=${snap.danglingNodeRefs}"
          isEmptyArea -> "empty area"
          edges1500 == 0 && tileFailedForArea -> "no decoded edges near user"
          else -> null
        },
        isEmptyArea = isEmptyArea,
      )
    val ovpOk = snap.overpassEdgeCount > 0 && !overpassFailedForArea
    val ovpSummary =
      ovpStats.joinToString(" | ") { st ->
        "${st.host.substringBefore('.')}:${st.lastStatus}/${st.durationMs}ms/${st.cellMeters}m/+${st.edgesAdded}"
      }
    val stageE =
      PipelineStageLine(
        stageId = "e",
        label = "Overpass",
        ok = ovpOk,
        summary = ovpSummary.ifBlank { "no attempts" },
        reason = if (!ovpOk) state.networkError else null,
      )
    val match = state.matchResult
    val matchOk = match.primary != null
    val matchDiag = match.diagnostics
    val matchSummary =
      when {
        match.primary != null ->
          String.format(
            Locale.US,
            "v%d(%s) idx=%d r=%.0fm off=%.1fm conf=%.2f way=%d cands=%d %s",
            matchDiag.graphVersionRead,
            matchDiag.sourceRead,
            matchDiag.indexEdgeCount,
            match.matchRadiusMeters,
            match.primary.lateralOffsetMeters,
            match.primary.confidence,
            match.primary.way.id,
            match.offRoadCandidates.size,
            matchDiag.toFilterSummary(),
          )
        loc == null -> "no GPS fix"
        else ->
          String.format(
            Locale.US,
            "v%d(%s) idx=%d r=%.0fm/%s cands=%d %s",
            matchDiag.graphVersionRead,
            matchDiag.sourceRead,
            matchDiag.indexEdgeCount,
            match.matchRadiusMeters,
            loc.accuracyMeters?.let { "±${it.toInt()}m" } ?: "acc--",
            state.offRoadState.candidates.size,
            matchDiag.toFilterSummary(),
          )
      }
    val stageF =
      PipelineStageLine(
        stageId = "f",
        label = "Map matching",
        ok = matchOk,
        summary = matchSummary,
        elapsedMs = matchDiag.matchDurationMs.takeIf { it > 0L },
        reason = if (!matchOk) state.offRoadState.reason else null,
      )
    val stageG =
      PipelineStageLine(
        stageId = "g",
        label = "Loader 60s",
        ok = true,
        summary = "start=${stats60s.started60s} done=${stats60s.completed60s} cancel=${stats60s.cancelled60s} act=${activeOvpAge?.let { "${it / 1000L}s" } ?: "-"}",
      )
    val hemiLat = pt?.let { if (it.lat >= 0.0) "N" else "S" } ?: "-"
    val hemiLon = pt?.let { if (it.lon >= 0.0) "E" else "W" } ?: "-"
    val script = GeoMath.detectLocaleScript()
    val amFlag = if (pt != null && kotlin.math.abs(pt.lon) > 179.0) " AM!" else ""
    val posTag = pt?.let { String.format(Locale.US, "%.4f%s,%.4f%s%s", kotlin.math.abs(it.lat), hemiLat, kotlin.math.abs(it.lon), hemiLon, amFlag) } ?: "no-fix"
    val devLocale = Locale.getDefault().toLanguageTag()
    val stageH =
      PipelineStageLine(
        stageId = "h",
        label = "Region",
        ok = true,
        summary = "$posTag script=$script units=${state.effectiveUnitSystem.name} ($devLocale)",
      )
    val stageI =
      PipelineStageLine(
        stageId = "i",
        label = "Map fallback",
        ok = true,
        summary = "used=${if (snap.mapFallbackUsed) "yes" else "no"} ways=${snap.mapFallbackWaysAdded} queried=$lastMapFeaturesQueriedCount",
      )

    val stages = listOf(stageA, stageB, stageC, stageD, stageE, stageF, stageG, stageH, stageI)
    // Stage e (Overpass) must never make overall tile-backed graph red when stage d already succeeded
    val firstFailing =
      if (graphOk || (isEmptyArea && graphIntegrityOk)) {
        null
      } else {
        listOf(stageA, stageB, stageC, stageD).firstOrNull { !it.ok && !it.isEmptyArea }?.label
      }

    val isInconsistent =
      pt != null &&
        edges1500 > 0 &&
        nearestHit != null &&
        nearestDist <= match.matchRadiusMeters &&
        match.primary == null &&
        matchDiag.graphVersionRead == snap.version
    val inconsistentBanner =
      if (isInconsistent) {
        String.format(
          Locale.US,
          "Inconsistent match: d=%.1fm<=%.0fm nearest=%s way=%d %s",
          nearestDist,
          match.matchRadiusMeters,
          nearestName,
          nearestWay?.id ?: 0L,
          matchDiag.toFilterSummary(),
        )
      } else {
        null
      }

    val rollingSummary =
      "mem=${snap.memoryTileCount}/${AppConfig.MAX_MEMORY_TILES} hit=${snap.memoryHits}/miss=${snap.memoryMisses} ev=${snap.evictions} trail=${trailingBreadcrumbs.size}"
    val horizonSummary =
      "horizon=${state.lookaheadMeters.toInt()}m loaded=${state.loadedCorridorMeters.toInt()}m timeline=${state.timelineEntries.size} lm=${state.landmarksAhead.size} hiddenMinor=${state.hiddenMinorCount}"

    _uiState.update {
      it.copy(
        currentActiveOverpassAgeMs = activeOvpAge,
        pipelineDiagnostics =
          PipelineDiagnostics(
            tileUrl = urlInfo,
            tileFetch = fetchInfo,
            mvtDecode = decodeInfo,
            stages = stages,
            firstFailingStageName = firstFailing,
            isInconsistentMatch = isInconsistent,
            inconsistentBannerText = inconsistentBanner,
            nearestEdgeSummary = nearestStr,
            nearbyTileLabels = nearbyLabels,
            graphVersion = snap.version,
            rollingWindowSummary = rollingSummary,
            horizonStatsSummary = horizonSummary,
            idCollisions = snap.idCollisions,
            danglingNodeRefs = snap.danglingNodeRefs,
          ),
      )
    }
  }

  private data class AnalysisOutput(
    val match: MatchResult,
    val offRoad: OffRoadState,
    val features: List<RoadFeature>,
    val census: List<FeatureCensusEntry>,
    val dividedRoad: DividedRoadInfo? = null,
    val detailed: AheadAnalysisResult? = null,
    val refreshedFound: FoundRoadMatch? = null,
  )

  private fun arabicFeatureAnnouncement(feature: RoadFeature): String {
    val distMeters = feature.distanceMeters.toInt()
    val typeAr =
      when (feature.effectiveDisplayType()) {
        FeatureType.FOUR_WAY -> "تقاطع رباعي"
        FeatureType.MULTI_WAY -> "تقاطع متعدد"
        FeatureType.T_JUNCTION -> "تقاطع"
        FeatureType.Y_JUNCTION -> "تفرع"
        FeatureType.ROUNDABOUT -> "دوار"
        FeatureType.MINI_ROUNDABOUT -> "دوار صغير"
        FeatureType.FORK -> "تفرع طريق"
        FeatureType.MERGE -> "اندماج طريق"
        FeatureType.ON_RAMP -> "منحدر دخول"
        FeatureType.OFF_RAMP -> "مخرج منحدر"
        FeatureType.BRIDGE -> "جسر"
        FeatureType.TUNNEL -> "نفق"
        FeatureType.OVERPASS_ABOVE -> "جسر علوي"
        FeatureType.UNDERPASS_BELOW -> "معبر سفلي"
        FeatureType.U_TURN -> "التفاف للخلف"
        FeatureType.DEAD_END -> "طريق مسدود"
        FeatureType.CUL_DE_SAC -> "نهاية دائرية"
        FeatureType.SERVICE_ROAD -> "طريق خدمة"
        FeatureType.SHARP_CURVE -> "منعطف حاد"
        FeatureType.FOOTPATH -> "ممر مشاة"
        FeatureType.CROSSWALK -> "معبر مشاة"
        FeatureType.FOOTBRIDGE -> "جسر مشاة"
        FeatureType.TOLL_PLAZA -> "بوابة رسوم"
        FeatureType.CHECKPOINT -> "نقطة تفتيش"
        FeatureType.BARRIER -> "حاجز طريق"
        FeatureType.ROAD_WORKS -> "أعمال طريق"
        FeatureType.STAGGERED_JUNCTION -> "تقاطع متدرج"
        FeatureType.DIVIDED_ROAD -> "طريق مفصول"
        FeatureType.MEDIAN_OPENING -> "فتحة دوران بالجزيرة"
        FeatureType.FRONTAGE_ROAD -> "طريق خدمة موازٍ"
        FeatureType.INTERCHANGE, FeatureType.DIRECTIONAL -> "تقاطع حُر"
        FeatureType.CLOVERLEAF -> "تقاطع ورقة برسيم"
        FeatureType.DIAMOND -> "تقاطع ماسي"
        FeatureType.TRUMPET -> "تقاطع بوق"
      }
    return "أمامك $typeAr على بعد $distMeters متر"
  }

  override fun onCleared() {
    super.onCleared()
    simulateDriveJob?.cancel()
    jitterJob?.cancel()
    tileFetchJob?.cancel()
    overpassFetchJob?.cancel()
    overpassDataSource.cancelAllActiveRequests()
    locationTracker.stopTracking()
    ttsAnnouncer.shutdown()
  }
}
