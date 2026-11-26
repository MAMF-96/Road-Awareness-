package com.example.ui.components

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.BuildConfig
import com.example.config.AppConfig
import com.example.data.model.DebugEdgeCategory
import com.example.data.model.FeatureType
import com.example.data.model.GeoPoint
import com.example.data.model.LiveMapVectorFeature
import com.example.data.model.SavedPin
import com.example.data.model.TileRoadHint
import com.example.data.model.TravelMode
import com.example.data.remote.DarkMapStyleGenerator
import com.example.data.remote.DebugNetworkSimulator
import com.example.domain.GeoMath
import com.example.ui.ChaseCameraPose
import com.example.ui.ChaseCameraSmoother
import com.example.ui.RoadHudUiState
import com.example.ui.theme.RoadColors
import com.example.ui.theme.resolveIsMapDark
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

private const val SRC_DEBUG_EDGES = "src_debug_edges"
private const val SRC_SAVED_STREETS = "src_saved_streets"
private const val SRC_SAVED_PINS = "src_saved_pins"
private const val SRC_CONE = "src_user_cone"
private const val SRC_USER = "src_user_dot"
private const val SRC_STUBS = "src_selected_stubs"
private const val SRC_UNCERTAIN = "src_uncertain_road"
private const val SRC_ROUTE = "src_explicit_route"
private const val SRC_FOUND_ROAD = "src_found_road"
private const val SRC_FOUND_PIN = "src_found_pin"
private const val SRC_JUNCTION_DOT = "src_junction_dot"
private const val SRC_PINS = "src_feature_pins"
private const val SRC_LANDMARKS = "src_landmarks_ahead"

private const val LYR_DEBUG_EDGES = "lyr_debug_edges"
private const val LYR_SAVED_STREETS_CASING = "lyr_saved_streets_casing"
private const val LYR_SAVED_STREETS = "lyr_saved_streets"
private const val LYR_SAVED_STREET_LABELS = "lyr_saved_street_labels"
private const val LYR_SAVED_PINS = "lyr_saved_pins"
private const val LYR_FOUND_ROAD_CASING = "lyr_found_road_casing"
private const val LYR_FOUND_ROAD = "lyr_found_road"
private const val LYR_FOUND_PIN = "lyr_found_pin"
private const val LYR_CONE = "lyr_user_cone"
private const val LYR_USER_HALO = "lyr_user_halo"
private const val LYR_USER_DOT = "lyr_user_dot"
private const val LYR_USER_CHEVRON = "lyr_user_chevron"
private const val LYR_USER_MOCK_BADGE = "lyr_user_mock_badge"
private const val LYR_STUBS_CASING = "lyr_selected_stubs_casing"
private const val LYR_STUBS = "lyr_selected_stubs"
private const val LYR_JUNCTION_DOT = "lyr_junction_dot"
private const val LYR_UNCERTAIN = "lyr_uncertain_road"
private const val LYR_ROUTE_CASING = "lyr_explicit_route_casing"
private const val LYR_ROUTE = "lyr_explicit_route"
private const val LYR_LANDMARKS = "lyr_landmarks_ahead"
private const val LYR_PINS = "lyr_feature_pins"
private const val IMG_MOCK_LOCK = "img_user_mock_lock"
private const val IMG_USER_CHEVRON = "img_user_chevron"

private val mapLibreInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

internal fun ensureMapLibreInitialized(context: Context) {
  if (mapLibreInitialized.compareAndSet(false, true)) {
    runCatching {
      MapLibre.getInstance(context.applicationContext)
      HttpRequestUtil.setOkHttpClient(
        OkHttpClient.Builder()
          .protocols(listOf(Protocol.HTTP_1_1))
          .connectTimeout(AppConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
          .readTimeout(AppConfig.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
          .addInterceptor(DebugNetworkSimulator.interceptor)
          .build(),
      )
    }.onFailure {
      mapLibreInitialized.set(false)
    }
  }
}

@Composable
fun MapLibreHudView(
  uiState: RoadHudUiState,
  useTextureMode: Boolean = false,
  isLandscape: Boolean = false,
  isPanelExpanded: Boolean = false,
  cameraPaddingTopPx: Float,
  cameraPaddingBottomPx: Float,
  cameraPaddingLeftPx: Float,
  cameraPaddingRightPx: Float,
  onFeaturePinClick: (Int) -> Unit,
  onOffRoadCandidateClick: (Long) -> Unit,
  onSavedPinClick: (Long) -> Unit = {},
  onMapTap: (GeoPoint) -> Unit,
  onZoomRequiredForTap: () -> Unit = {},
  onUserPan: () -> Unit,
  onStyleErrorChange: (String?) -> Unit,
  onTileRoadHintChanged: (TileRoadHint?) -> Unit = {},
  onLiveMapVectorFallback: (List<LiveMapVectorFeature>) -> Unit = {},
  onLiveMapVectorFallbackWithCount: ((List<LiveMapVectorFeature>, Int) -> Unit)? = null,
  onFoundTargetOffScreenChange: (Boolean) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  MapLibreHudViewInternal(
    uiState = uiState,
    useTextureMode = useTextureMode,
    isLandscape = isLandscape,
    isPanelExpanded = isPanelExpanded,
    cameraPaddingTopPx = cameraPaddingTopPx,
    cameraPaddingBottomPx = cameraPaddingBottomPx,
    cameraPaddingLeftPx = cameraPaddingLeftPx,
    cameraPaddingRightPx = cameraPaddingRightPx,
    onFeaturePinClick = onFeaturePinClick,
    onOffRoadCandidateClick = onOffRoadCandidateClick,
    onSavedPinClick = onSavedPinClick,
    onMapTap = onMapTap,
    onZoomRequiredForTap = onZoomRequiredForTap,
    onUserPan = onUserPan,
    onStyleErrorChange = onStyleErrorChange,
    onTileRoadHintChanged = onTileRoadHintChanged,
    onLiveMapVectorFallback = onLiveMapVectorFallback,
    onLiveMapVectorFallbackWithCount = onLiveMapVectorFallbackWithCount,
    onFoundTargetOffScreenChange = onFoundTargetOffScreenChange,
    modifier = modifier,
  )
}

@Composable
private fun MapLibreHudViewInternal(
  uiState: RoadHudUiState,
  useTextureMode: Boolean,
  isLandscape: Boolean,
  isPanelExpanded: Boolean,
  cameraPaddingTopPx: Float,
  cameraPaddingBottomPx: Float,
  cameraPaddingLeftPx: Float,
  cameraPaddingRightPx: Float,
  onFeaturePinClick: (Int) -> Unit,
  onOffRoadCandidateClick: (Long) -> Unit,
  onSavedPinClick: (Long) -> Unit,
  onMapTap: (GeoPoint) -> Unit,
  onZoomRequiredForTap: () -> Unit,
  onUserPan: () -> Unit,
  onStyleErrorChange: (String?) -> Unit,
  onTileRoadHintChanged: (TileRoadHint?) -> Unit,
  onLiveMapVectorFallback: (List<LiveMapVectorFeature>) -> Unit,
  onLiveMapVectorFallbackWithCount: ((List<LiveMapVectorFeature>, Int) -> Unit)?,
  onFoundTargetOffScreenChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val isMapDark = resolveIsMapDark(uiState.settings.themePreference)

  val currentUiState by rememberUpdatedState(uiState)
  val currentOnFeaturePinClick by rememberUpdatedState(onFeaturePinClick)
  val currentOnOffRoadCandidateClick by rememberUpdatedState(onOffRoadCandidateClick)
  val currentOnSavedPinClick by rememberUpdatedState(onSavedPinClick)
  val currentOnMapTap by rememberUpdatedState(onMapTap)
  val currentOnZoomRequiredForTap by rememberUpdatedState(onZoomRequiredForTap)
  val currentOnUserPan by rememberUpdatedState(onUserPan)
  val currentOnStyleErrorChange by rememberUpdatedState(onStyleErrorChange)
  val currentOnTileRoadHintChanged by rememberUpdatedState(onTileRoadHintChanged)
  val currentOnLiveMapVectorFallback by rememberUpdatedState(onLiveMapVectorFallback)
  val currentOnLiveMapVectorFallbackWithCount by rememberUpdatedState(onLiveMapVectorFallbackWithCount)
  val currentOnFoundTargetOffScreenChange by rememberUpdatedState(onFoundTargetOffScreenChange)

  remember(context) {
    ensureMapLibreInitialized(context)
    true
  }

  val mapView = remember(context, useTextureMode) {
    val options = MapLibreMapOptions.createFromAttributes(context).textureMode(useTextureMode)
    MapView(context, options).apply { onCreate(null) }
  }

  var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
  var loadedStyle by remember { mutableStateOf<Style?>(null) }
  var lastLoadedDarkTheme by remember { mutableStateOf<Boolean?>(null) }
  var isMapDestroyed by remember { mutableStateOf(false) }
  var hasStarted by remember { mutableStateOf(false) }
  var hasResumed by remember { mutableStateOf(false) }
  var mapSizePx by remember { mutableStateOf(IntSize.Zero) }
  var cameraIdleTick by remember { mutableIntStateOf(0) }
  val chaseSmoother = remember { ChaseCameraSmoother() }
  var lastChasePose by remember { mutableStateOf<ChaseCameraPose?>(null) }

  fun pauseAndStopMapView(mv: MapView) {
    if (isMapDestroyed || mv.isDestroyed) return
    if (hasResumed) {
      hasResumed = false
      mapInstance?.cancelTransitions()
      mv.onPause()
    }
    if (hasStarted) {
      hasStarted = false
      mv.onStop()
    }
  }

  fun destroyMapViewSafely(mv: MapView) {
    if (isMapDestroyed || mv.isDestroyed) return
    pauseAndStopMapView(mv)
    isMapDestroyed = true
    mapInstance?.cancelTransitions()
    mapInstance = null
    loadedStyle = null
    mv.onDestroy()
  }

  DisposableEffect(lifecycleOwner, mapView) {
    val lowMemoryCallback =
      object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {
          if (!isMapDestroyed && !mapView.isDestroyed) {
            mapView.onLowMemory()
          }
        }

        override fun onTrimMemory(level: Int) {
          if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !isMapDestroyed && !mapView.isDestroyed) {
            mapView.onLowMemory()
          }
        }
      }
    context.applicationContext.registerComponentCallbacks(lowMemoryCallback)

    val observer = LifecycleEventObserver { _, event ->
      if (isMapDestroyed || mapView.isDestroyed) return@LifecycleEventObserver
      when (event) {
        Lifecycle.Event.ON_START -> {
          if (!hasStarted) {
            hasStarted = true
            mapView.onStart()
          }
        }
        Lifecycle.Event.ON_RESUME -> {
          if (!hasStarted) {
            hasStarted = true
            mapView.onStart()
          }
          if (!hasResumed) {
            hasResumed = true
            mapView.onResume()
          }
        }
        Lifecycle.Event.ON_PAUSE -> {
          if (hasResumed) {
            hasResumed = false
            mapInstance?.cancelTransitions()
            mapView.onPause()
          }
        }
        Lifecycle.Event.ON_STOP -> {
          pauseAndStopMapView(mapView)
        }
        Lifecycle.Event.ON_DESTROY -> {
          destroyMapViewSafely(mapView)
        }
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      context.applicationContext.unregisterComponentCallbacks(lowMemoryCallback)
      lifecycleOwner.lifecycle.removeObserver(observer)
      pauseAndStopMapView(mapView)
      if (!mapView.isAttachedToWindow) {
        destroyMapViewSafely(mapView)
      }
    }
  }

  LaunchedEffect(mapView) {
    mapView.getMapAsync { map ->
      if (isMapDestroyed || mapView.isDestroyed) return@getMapAsync
      mapInstance = map
      map.setMaxPitchPreference(60.0)
      map.uiSettings.apply {
        isCompassEnabled = false
        isLogoEnabled = false
        isAttributionEnabled = false
        isRotateGesturesEnabled = true
        isTiltGesturesEnabled = true
      }

      map.addOnMoveListener(
        object : MapLibreMap.OnMoveListener {
          override fun onMoveBegin(detector: MoveGestureDetector) {
            currentOnUserPan()
          }

          override fun onMove(detector: MoveGestureDetector) = Unit

          override fun onMoveEnd(detector: MoveGestureDetector) = Unit
        },
      )

      map.addOnCameraIdleListener {
        cameraIdleTick++
      }

      map.addOnMapClickListener { latLng ->
        val screenPt = map.projection.toScreenLocation(latLng)
        // 1) Feature pins & off-road R1..R3 pins always have top click priority
        val tappedFeaturePin = map.queryRenderedFeatures(screenPt, LYR_PINS).firstOrNull()
        val offRoadWayId = tappedFeaturePin?.getNumberProperty("offroadWayId")?.toLong()
        val clickedIdx = tappedFeaturePin?.getNumberProperty("featureIndex")?.toInt()
        when {
          offRoadWayId != null -> {
            currentOnOffRoadCandidateClick(offRoadWayId)
            true
          }
          clickedIdx != null -> {
            currentOnFeaturePinClick(clickedIdx)
            true
          }
          else -> {
            // 2) Saved pins & street lines
            val tappedSaved =
              map.queryRenderedFeatures(
                screenPt,
                LYR_SAVED_PINS,
                LYR_SAVED_STREET_LABELS,
                LYR_SAVED_STREETS,
              ).firstOrNull()
            val savedPinId = tappedSaved?.getNumberProperty("savedPinId")?.toLong()
            if (savedPinId != null) {
              currentOnSavedPinClick(savedPinId)
              true
            } else if (BuildConfig.DEBUG && currentUiState.isMockModeActive && map.cameraPosition.zoom < 12.0) {
              val nextZoom = (map.cameraPosition.zoom + 4.0).coerceAtMost(15.5)
              map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(latLng, nextZoom),
                450,
              )
              currentOnZoomRequiredForTap()
              true
            } else {
              currentOnMapTap(GeoPoint(latLng.latitude, latLng.longitude))
              currentUiState.isInspectorOpen || (BuildConfig.DEBUG && currentUiState.isMockModeActive)
            }
          }
        }
      }

      val initTarget = currentUiState.location?.point
      map.cameraPosition =
        if (initTarget != null) {
          CameraPosition.Builder()
            .target(LatLng(initTarget.lat, initTarget.lon))
            .zoom(16.3)
            .tilt(30.0)
            .bearing(currentUiState.location?.headingDeg ?: 0.0)
            .build()
        } else {
          CameraPosition.Builder()
            .target(LatLng(0.0, 0.0))
            .zoom(2.0)
            .tilt(0.0)
            .bearing(0.0)
            .build()
        }
    }
  }

  var fallbackApplied by remember(isMapDark) { mutableStateOf(false) }

  fun applyStyleWithHudPreserved(
    map: MapLibreMap,
    builder: Style.Builder,
    savedCamera: CameraPosition,
    clearErrorOnSuccess: Boolean,
    darkTheme: Boolean,
  ) {
    val liveCam =
      map.cameraPosition.takeIf { cam ->
        cam.target?.let { it.latitude != 0.0 || it.longitude != 0.0 } == true
      } ?: savedCamera
    map.setStyle(builder) { style ->
      if (!isMapDestroyed && !mapView.isDestroyed && map.style === style && style.isFullyLoaded) {
        if (clearErrorOnSuccess) {
          currentOnStyleErrorChange(null)
        }
        setupHudLayers(style)
        loadedStyle = style
        syncHudLayers(
          context = context,
          style = style,
          uiState = currentUiState,
          isMapDark = darkTheme,
          chasePose = lastChasePose,
        )
        if (liveCam.target != null) {
          map.moveCamera(CameraUpdateFactory.newCameraPosition(liveCam))
        }
      }
    }
  }

  LaunchedEffect(mapInstance, isMapDark) {
    val map = mapInstance ?: return@LaunchedEffect
    if (isMapDestroyed || mapView.isDestroyed) return@LaunchedEffect
    if (lastLoadedDarkTheme == isMapDark && loadedStyle != null) return@LaunchedEffect
    lastLoadedDarkTheme = isMapDark
    loadedStyle = null
    val savedCam = map.cameraPosition

    val result = DarkMapStyleGenerator.loadStyleForTheme(context.applicationContext, isDark = isMapDark)
    if (isMapDestroyed || mapView.isDestroyed) return@LaunchedEffect

    if (isMapDark) {
      when {
        result.darkStyleJson != null -> {
          currentOnStyleErrorChange(result.diagnosticError)
          applyStyleWithHudPreserved(
            map = map,
            builder = Style.Builder().fromJson(result.darkStyleJson),
            savedCamera = savedCam,
            clearErrorOnSuccess = result.diagnosticError == null,
            darkTheme = true,
          )
        }
        result.lightFallbackJson != null -> {
          currentOnStyleErrorChange("${result.diagnosticError ?: "Dark style unavailable"}; fell back to light style")
          applyStyleWithHudPreserved(
            map = map,
            builder = Style.Builder().fromJson(result.lightFallbackJson),
            savedCamera = savedCam,
            clearErrorOnSuccess = false,
            darkTheme = true,
          )
        }
        else -> {
          currentOnStyleErrorChange("${result.diagnosticError ?: "Dark style unavailable"}; fell back to light style")
          applyStyleWithHudPreserved(
            map = map,
            builder = Style.Builder().fromUri(AppConfig.MAP_STYLE_LIGHT_URL),
            savedCamera = savedCam,
            clearErrorOnSuccess = false,
            darkTheme = true,
          )
        }
      }
    } else {
      val softOrFallbackJson = result.softLightStyleJson ?: result.lightFallbackJson
      if (softOrFallbackJson != null) {
        currentOnStyleErrorChange(result.diagnosticError)
        applyStyleWithHudPreserved(
          map = map,
          builder = Style.Builder().fromJson(softOrFallbackJson),
          savedCamera = savedCam,
          clearErrorOnSuccess = result.diagnosticError == null,
          darkTheme = false,
        )
      } else {
        applyStyleWithHudPreserved(
          map = map,
          builder = Style.Builder().fromUri(AppConfig.MAP_STYLE_LIGHT_URL),
          savedCamera = savedCam,
          clearErrorOnSuccess = true,
          darkTheme = false,
        )
      }
    }
  }

  DisposableEffect(mapView, mapInstance, isMapDark) {
    val failListener =
      MapView.OnDidFailLoadingMapListener { errorMsg ->
        val map = mapInstance ?: return@OnDidFailLoadingMapListener
        if (isMapDestroyed || mapView.isDestroyed) return@OnDidFailLoadingMapListener
        if (fallbackApplied || map.style?.isFullyLoaded == true) return@OnDidFailLoadingMapListener
        fallbackApplied = true
        loadedStyle = null
        val savedCam = map.cameraPosition
        val reason = errorMsg.ifBlank { "Map style load failed" }
        currentOnStyleErrorChange("Map style error ($reason); using keyless OSM fallback")
        val fallbackUrl = AppConfig.MAP_RASTER_LIGHT_FALLBACK_URL
        applyStyleWithHudPreserved(
          map = map,
          builder = Style.Builder().fromJson(buildRasterStyleJson(fallbackUrl)),
          savedCamera = savedCam,
          clearErrorOnSuccess = false,
          darkTheme = isMapDark,
        )
      }
    mapView.addOnDidFailLoadingMapListener(failListener)
    onDispose { mapView.removeOnDidFailLoadingMapListener(failListener) }
  }

  LaunchedEffect(
    mapInstance,
    loadedStyle,
    isMapDark,
    uiState.location,
    uiState.displayLocationPoint,
    uiState.displayHeadingDeg,
    uiState.isMockModeActive,
    uiState.features,
    uiState.landmarksAhead,
    uiState.isOverviewMode,
    isPanelExpanded,
    uiState.selectedFeatureIndex,
    uiState.matchResult,
    uiState.offRoadState,
    uiState.hasConfirmedGraphData,
    uiState.savedPins,
    uiState.activeFoundRoad,
    uiState.isRoutingToFoundRoad,
    uiState.activeRoutePinId,
    uiState.explicitRoute,
    uiState.debugGraphEdges,
    uiState.settings.showGraphEdges,
    uiState.settings.landmarksAheadEnabled,
    uiState.settings.chaseCameraEnabled,
    uiState.settings.chaseCameraDistance,
    uiState.settings.performanceModeEnabled,
    uiState.travelMode,
    lastChasePose,
  ) {
    if (isMapDestroyed || mapView.isDestroyed) return@LaunchedEffect
    val map = mapInstance ?: return@LaunchedEffect
    val style = map.style ?: return@LaunchedEffect
    if (style !== loadedStyle || !style.isFullyLoaded) return@LaunchedEffect
    syncHudLayers(
      context = context,
      style = style,
      uiState = uiState,
      isMapDark = isMapDark,
      isPanelExpanded = isPanelExpanded,
      chasePose = lastChasePose,
    )
    currentOnTileRoadHintChanged(null)
  }

  // Part E: Live rendered map fallback — extract transportation & transportation_name features when tile/graph pipeline lacks nearby edges
  LaunchedEffect(
    mapInstance,
    loadedStyle,
    uiState.needsMapFallbackWithin150m,
    uiState.mapFallbackTriggerTick,
  ) {
    if (isMapDestroyed || mapView.isDestroyed) return@LaunchedEffect
    val loc = currentUiState.location ?: return@LaunchedEffect
    val needsLiveFallback =
      uiState.needsMapFallbackWithin150m ||
        !currentUiState.hasConfirmedGraphData ||
        currentUiState.roadNameSource == "none" ||
        currentUiState.isGraphErrorState
    if (!needsLiveFallback) return@LaunchedEffect
    val map = mapInstance ?: return@LaunchedEffect
    val style = map.style ?: return@LaunchedEffect
    if (style !== loadedStyle || !style.isFullyLoaded) return@LaunchedEffect

    delay(350L)
    if (isMapDestroyed || mapView.isDestroyed || !style.isFullyLoaded) return@LaunchedEffect

    val latestLoc = currentUiState.location ?: loc
    val extracted = mutableListOf<LiveMapVectorFeature>()
    var queriedTotal = 0
    val vectorSources = style.sources.filterIsInstance<VectorSource>()
    val layerNames = listOf("transportation", "transportation_name")
    for (vSource in vectorSources) {
      for (layerName in layerNames) {
        val features =
          runCatching { vSource.querySourceFeatures(arrayOf(layerName), null) }
            .getOrDefault(emptyList())
        queriedTotal += features.size
        for (feature in features) {
          val lines: List<List<GeoPoint>> =
            when (val geom = feature.geometry()) {
              is LineString -> {
                val pts = geom.coordinates().map { GeoPoint(it.latitude(), it.longitude()) }
                if (pts.size >= 2) listOf(pts) else emptyList()
              }
              is MultiLineString ->
                geom.coordinates()
                  .map { ring -> ring.map { GeoPoint(it.latitude(), it.longitude()) } }
                  .filter { it.size >= 2 }
              else -> emptyList()
            }
          if (lines.isEmpty()) continue
          val localLines =
            lines.filter { line ->
              line.any { pt -> GeoMath.haversineMeters(latestLoc.point, pt) <= 2_500.0 }
            }
          if (localLines.isEmpty()) continue
          val tags = mutableMapOf<String, String>()
          val props = feature.properties()
          if (props != null) {
            for ((k, v) in props.entrySet()) {
              if (v != null && v.isJsonPrimitive) {
                tags[k] = v.asString
              }
            }
          }
          extracted += LiveMapVectorFeature(layerName = layerName, tags = tags, geometries = localLines)
          if (extracted.size >= 400) break
        }
        if (extracted.size >= 400) break
      }
      if (extracted.size >= 400) break
    }
    val withCountCb = currentOnLiveMapVectorFallbackWithCount
    if (withCountCb != null) {
      withCountCb(extracted, queriedTotal)
    } else if (extracted.isNotEmpty()) {
      currentOnLiveMapVectorFallback(extracted)
    }
  }

  var lastAppliedPad by remember { mutableStateOf(listOf(0f, 0f, 0f, 0f)) }
  var lastCameraMoveMs by remember { mutableLongStateOf(0L) }
  var lastChaseSettingsKey by remember { mutableStateOf<Pair<Boolean, Any>?>(null) }
  var lastRecenterSignal by remember { mutableIntStateOf(uiState.recenterSignal) }

  LaunchedEffect(
    mapInstance,
    uiState.location,
    uiState.displayLocationPoint,
    uiState.displayHeadingDeg,
    uiState.isSimulatingDrive,
    uiState.isFollowingUser,
    uiState.recenterSignal,
    uiState.travelMode,
    uiState.settings.chaseCameraEnabled,
    uiState.settings.chaseCameraDistance,
    uiState.corridorWorldPoints,
    uiState.matchResult,
    uiState.isOverviewMode,
    mapSizePx,
    isLandscape,
    cameraPaddingTopPx,
    cameraPaddingBottomPx,
    cameraPaddingLeftPx,
    cameraPaddingRightPx,
  ) {
    if (isMapDestroyed || mapView.isDestroyed) return@LaunchedEffect
    if (mapSizePx.width <= 0 || mapSizePx.height <= 0) return@LaunchedEffect
    val map = mapInstance ?: return@LaunchedEffect
    val loc = uiState.location ?: return@LaunchedEffect

    val curPad = listOf(cameraPaddingTopPx, cameraPaddingBottomPx, cameraPaddingLeftPx, cameraPaddingRightPx)
    val padChanged = curPad != lastAppliedPad
    if (padChanged) {
      delay(45)
      if (isMapDestroyed || mapView.isDestroyed) return@LaunchedEffect
      lastAppliedPad = curPad
    }

    val curChaseKey = uiState.settings.chaseCameraEnabled to uiState.settings.chaseCameraDistance
    val chaseSettingChanged = curChaseKey != lastChaseSettingsKey
    if (chaseSettingChanged) lastChaseSettingsKey = curChaseKey
    val recenterChanged = uiState.recenterSignal != lastRecenterSignal
    if (recenterChanged) lastRecenterSignal = uiState.recenterSignal

    val effectivePoint = uiState.displayLocationPoint ?: loc.point
    val effectiveHeading = uiState.displayHeadingDeg ?: loc.headingDeg
    val now = System.currentTimeMillis()
    val forceEmit = padChanged || chaseSettingChanged || recenterChanged

    val pose =
      chaseSmoother.update(
        nowMs = now,
        chaseCameraEnabled = uiState.settings.chaseCameraEnabled,
        distancePreset = uiState.settings.chaseCameraDistance,
        travelMode = uiState.travelMode,
        isLandscape = isLandscape,
        gpsHeadingDeg = effectiveHeading,
        speedMps = loc.speedMps,
        userPoint = effectivePoint,
        isMatchedToRoad = uiState.matchResult.primary != null,
        corridorWorldPoints = uiState.corridorWorldPoints,
        isOverviewMode = uiState.isOverviewMode,
        forceEmit = forceEmit,
      )
    lastChasePose = pose

    val h = mapSizePx.height.toDouble()
    val w = mapSizePx.width.toDouble()
    val visibleTop = cameraPaddingTopPx.toDouble().coerceIn(0.0, h * 0.70)
    val visibleBot = cameraPaddingBottomPx.toDouble().coerceIn(0.0, h * 0.80)
    val visibleHeight = (h - visibleTop - visibleBot).coerceAtLeast(80.0)
    var effectiveTop = if (h > 0) visibleTop + pose.topRatio * visibleHeight else 0.0
    var effectiveBot = if (h > 0) visibleBot else 0.0
    val maxSumH = (h - 48.0).coerceAtLeast(0.0)
    if (effectiveTop + effectiveBot > maxSumH && effectiveTop + effectiveBot > 0.0) {
      val scale = maxSumH / (effectiveTop + effectiveBot)
      effectiveTop *= scale
      effectiveBot *= scale
    }

    val leftPad = cameraPaddingLeftPx.toDouble().coerceIn(0.0, w * 0.60)
    val rightPad = cameraPaddingRightPx.toDouble().coerceIn(0.0, w * 0.60)

    if (!uiState.isFollowingUser || uiState.isOverviewMode) {
      if (padChanged && !uiState.isOverviewMode && map.cameraPosition.target != null) {
        val preserved =
          CameraPosition.Builder(map.cameraPosition)
            .padding(leftPad, effectiveTop, rightPad, effectiveBot)
            .build()
        map.moveCamera(CameraUpdateFactory.newCameraPosition(preserved))
      }
      return@LaunchedEffect
    }

    if (!pose.shouldEmitCameraUpdate) return@LaunchedEffect

    val cam =
      CameraPosition.Builder()
        .target(LatLng(effectivePoint.lat, effectivePoint.lon))
        .bearing(pose.bearingDeg)
        .zoom(pose.zoom)
        .tilt(pose.tiltDeg)
        .padding(leftPad, effectiveTop, rightPad, effectiveBot)
        .build()

    val isSmoothContinuous =
      (uiState.isSimulatingDrive || (loc.isMock && (now - lastCameraMoveMs) < 220L)) &&
        !padChanged &&
        !chaseSettingChanged &&
        !recenterChanged
    lastCameraMoveMs = now
    if (isSmoothContinuous) {
      map.cancelTransitions()
      map.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
    } else {
      map.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 200)
    }
  }

  // Focus camera on selected/saved pin or street when focused
  LaunchedEffect(mapInstance, uiState.focusPinSignal) {
    if (isMapDestroyed || mapView.isDestroyed) return@LaunchedEffect
    val map = mapInstance ?: return@LaunchedEffect
    if (uiState.focusPinSignal <= 0) return@LaunchedEffect
    val pin = uiState.focusedPin ?: return@LaunchedEffect
    val allStreetPts = pin.streetLines.flatten()
    if (pin.isStreet && allStreetPts.size >= 2) {
      val distinct = allStreetPts.distinctBy { "${it.lat}_${it.lon}" }
      if (distinct.size >= 2) {
        val bounds =
          LatLngBounds.Builder()
            .includes(distinct.map { LatLng(it.lat, it.lon) })
            .build()
        map.animateCamera(
          CameraUpdateFactory.newLatLngBounds(
            bounds,
            cameraPaddingLeftPx.toInt().coerceAtLeast(32),
            cameraPaddingTopPx.toInt().coerceAtLeast(64),
            cameraPaddingRightPx.toInt().coerceAtLeast(32),
            cameraPaddingBottomPx.toInt().coerceAtLeast(64),
          ),
          350,
        )
        return@LaunchedEffect
      }
    }
    val cam =
      CameraPosition.Builder()
        .target(LatLng(pin.point.lat, pin.point.lon))
        .zoom(16.4)
        .tilt(15.0)
        .padding(
          cameraPaddingLeftPx.toDouble(),
          cameraPaddingTopPx.toDouble(),
          cameraPaddingRightPx.toDouble(),
          cameraPaddingBottomPx.toDouble(),
        )
        .build()
    map.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 350)
  }

  // Overview camera bounds for the loaded 1-5 km corridor and/or Find-on-my-road target (never draws a route polyline)
  LaunchedEffect(
    mapInstance,
    uiState.isOverviewMode,
    uiState.overviewSignal,
    uiState.activeFoundRoad,
    mapSizePx,
    cameraPaddingTopPx,
    cameraPaddingBottomPx,
    cameraPaddingLeftPx,
    cameraPaddingRightPx,
  ) {
    if (isMapDestroyed || mapView.isDestroyed) return@LaunchedEffect
    if (!uiState.isOverviewMode) return@LaunchedEffect
    val map = mapInstance ?: return@LaunchedEffect
    if (mapSizePx.width <= 0 || mapSizePx.height <= 0) return@LaunchedEffect
    val pts = ArrayList<GeoPoint>(uiState.corridorWorldPoints.size + 16)
    uiState.location?.point?.let { pts += it }
    pts += uiState.corridorWorldPoints
    uiState.activeFoundRoad?.let { found ->
      pts += found.targetPoint
      pts += found.entryPoint
      if (found.polyline.isNotEmpty()) {
        pts += found.polyline.first()
        pts += found.polyline.last()
      }
    }
    val distinct = pts.distinctBy { "${(it.lat * 1e5).toInt()}_${(it.lon * 1e5).toInt()}" }
    if (distinct.size >= 2) {
      val bounds = LatLngBounds.Builder().includes(distinct.map { LatLng(it.lat, it.lon) }).build()
      map.animateCamera(
        CameraUpdateFactory.newLatLngBounds(
          bounds,
          cameraPaddingLeftPx.toInt().coerceAtLeast(36),
          cameraPaddingTopPx.toInt().coerceAtLeast(64),
          cameraPaddingRightPx.toInt().coerceAtLeast(36),
          cameraPaddingBottomPx.toInt().coerceAtLeast(64),
        ),
        380,
      )
    } else if (distinct.size == 1) {
      val p = distinct.first()
      val cam =
        CameraPosition.Builder()
          .target(LatLng(p.lat, p.lon))
          .zoom(14.5)
          .tilt(0.0)
          .padding(
            cameraPaddingLeftPx.toDouble(),
            cameraPaddingTopPx.toDouble(),
            cameraPaddingRightPx.toDouble(),
            cameraPaddingBottomPx.toDouble(),
          )
          .build()
      map.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 380)
    }
  }

  // Compute off-screen state for active Find-on-my-road target
  LaunchedEffect(
    mapInstance,
    cameraIdleTick,
    uiState.activeFoundRoad,
    uiState.location,
    uiState.isOverviewMode,
    mapSizePx,
    cameraPaddingTopPx,
    cameraPaddingBottomPx,
    cameraPaddingLeftPx,
    cameraPaddingRightPx,
  ) {
    val found = uiState.activeFoundRoad
    if (found == null || uiState.isOverviewMode || mapSizePx.width <= 0 || mapSizePx.height <= 0) {
      currentOnFoundTargetOffScreenChange(false)
      return@LaunchedEffect
    }
    val map = mapInstance ?: run {
      currentOnFoundTargetOffScreenChange(false)
      return@LaunchedEffect
    }
    val targetPt = found.entryPoint
    val screen = runCatching { map.projection.toScreenLocation(LatLng(targetPt.lat, targetPt.lon)) }.getOrNull()
    if (screen == null) {
      currentOnFoundTargetOffScreenChange(false)
      return@LaunchedEffect
    }
    val minX = cameraPaddingLeftPx.coerceIn(0f, mapSizePx.width * 0.7f)
    val maxX = (mapSizePx.width - cameraPaddingRightPx).coerceAtLeast(minX + 16f)
    val minY = cameraPaddingTopPx.coerceIn(0f, mapSizePx.height * 0.7f)
    val maxY = (mapSizePx.height - cameraPaddingBottomPx).coerceAtLeast(minY + 16f)
    val offScreen = screen.x < minX || screen.x > maxX || screen.y < minY || screen.y > maxY
    currentOnFoundTargetOffScreenChange(offScreen)
  }

  AndroidView(
    factory = { mapView },
    modifier = modifier.onSizeChanged { mapSizePx = it },
    onRelease = { mv -> destroyMapViewSafely(mv) },
  )
}

private fun setupHudLayers(style: Style) {
  fun ensureSource(id: String) {
    if (style.getSource(id) == null) {
      style.addSource(GeoJsonSource(id))
    }
  }
  ensureSource(SRC_DEBUG_EDGES)
  ensureSource(SRC_SAVED_STREETS)
  ensureSource(SRC_SAVED_PINS)
  ensureSource(SRC_FOUND_ROAD)
  ensureSource(SRC_FOUND_PIN)
  ensureSource(SRC_CONE)
  ensureSource(SRC_USER)
  ensureSource(SRC_STUBS)
  ensureSource(SRC_JUNCTION_DOT)
  ensureSource(SRC_UNCERTAIN)
  ensureSource(SRC_ROUTE)
  ensureSource(SRC_LANDMARKS)
  ensureSource(SRC_PINS)

  if (style.getLayer(LYR_DEBUG_EDGES) == null) {
    style.addLayer(
      LineLayer(LYR_DEBUG_EDGES, SRC_DEBUG_EDGES).withProperties(
        PropertyFactory.lineColor(Expression.get("color")),
        PropertyFactory.lineWidth(3.0f),
        PropertyFactory.lineOpacity(0.85f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
      ),
    )
  }

  // Saved street casing (light mode 2dp white border) & lines below feature pins & route
  if (style.getLayer(LYR_SAVED_STREETS_CASING) == null) {
    style.addLayer(
      LineLayer(LYR_SAVED_STREETS_CASING, SRC_SAVED_STREETS).withProperties(
        PropertyFactory.lineColor(android.graphics.Color.WHITE),
        PropertyFactory.lineWidth(8.5f),
        PropertyFactory.lineOpacity(1.0f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        PropertyFactory.visibility(Property.NONE),
      ),
    )
  }

  if (style.getLayer(LYR_SAVED_STREETS) == null) {
    style.addLayer(
      LineLayer(LYR_SAVED_STREETS, SRC_SAVED_STREETS).withProperties(
        PropertyFactory.lineColor(RoadColors.PinPurple.toArgb()),
        PropertyFactory.lineWidth(4.5f),
        PropertyFactory.lineOpacity(0.88f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
      ),
    )
  }

  if (style.getLayer(LYR_SAVED_STREET_LABELS) == null) {
    style.addLayer(
      SymbolLayer(LYR_SAVED_STREET_LABELS, SRC_SAVED_STREETS).withProperties(
        PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
        PropertyFactory.textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_MAP),
        PropertyFactory.textField(Expression.get("label")),
        PropertyFactory.textSize(14f),
        PropertyFactory.textColor(RoadColors.PinPurple.toArgb()),
        PropertyFactory.textHaloColor(android.graphics.Color.WHITE),
        PropertyFactory.textHaloWidth(2.0f),
        PropertyFactory.textAllowOverlap(false),
        PropertyFactory.textIgnorePlacement(false),
      ),
    )
  }

  // Active Find-on-my-road highlight polyline in #7B3FE4
  if (style.getLayer(LYR_FOUND_ROAD_CASING) == null) {
    style.addLayer(
      LineLayer(LYR_FOUND_ROAD_CASING, SRC_FOUND_ROAD).withProperties(
        PropertyFactory.lineColor(android.graphics.Color.WHITE),
        PropertyFactory.lineWidth(10.0f),
        PropertyFactory.lineOpacity(1.0f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        PropertyFactory.visibility(Property.NONE),
      ),
    )
  }

  if (style.getLayer(LYR_FOUND_ROAD) == null) {
    style.addLayer(
      LineLayer(LYR_FOUND_ROAD, SRC_FOUND_ROAD).withProperties(
        PropertyFactory.lineColor(RoadColors.PinPurple.toArgb()),
        PropertyFactory.lineWidth(6.0f),
        PropertyFactory.lineOpacity(0.95f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
      ),
    )
  }

  if (style.getLayer(LYR_UNCERTAIN) == null) {
    style.addLayer(
      LineLayer(LYR_UNCERTAIN, SRC_UNCERTAIN).withProperties(
        PropertyFactory.lineColor(RoadColors.StatusUnverified.toArgb()),
        PropertyFactory.lineWidth(3.5f),
        PropertyFactory.lineDasharray(arrayOf(2.0f, 1.5f)),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
      ),
    )
  }

  if (style.getLayer(LYR_STUBS_CASING) == null) {
    style.addLayer(
      LineLayer(LYR_STUBS_CASING, SRC_STUBS).withProperties(
        PropertyFactory.lineColor(android.graphics.Color.WHITE),
        PropertyFactory.lineWidth(Expression.coalesce(Expression.get("casingWidth"), Expression.literal(9.0f))),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        PropertyFactory.lineOpacity(1.0f),
        PropertyFactory.visibility(Property.NONE),
      ),
    )
  }

  if (style.getLayer(LYR_STUBS) == null) {
    style.addLayer(
      LineLayer(LYR_STUBS, SRC_STUBS).withProperties(
        PropertyFactory.lineColor(Expression.get("color")),
        PropertyFactory.lineWidth(Expression.coalesce(Expression.get("width"), Expression.literal(5.0f))),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        PropertyFactory.lineOpacity(0.92f),
      ),
    )
  }

  if (style.getLayer(LYR_JUNCTION_DOT) == null) {
    style.addLayer(
      CircleLayer(LYR_JUNCTION_DOT, SRC_JUNCTION_DOT).withProperties(
        PropertyFactory.circleRadius(5.0f),
        PropertyFactory.circleColor(Expression.get("color")),
        PropertyFactory.circleStrokeWidth(2.0f),
        PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
        PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_VIEWPORT),
        PropertyFactory.visibility(Property.NONE),
      ),
    )
  }

  if (style.getLayer(LYR_ROUTE_CASING) == null) {
    style.addLayer(
      LineLayer(LYR_ROUTE_CASING, SRC_ROUTE).withProperties(
        PropertyFactory.lineColor(android.graphics.Color.WHITE),
        PropertyFactory.lineWidth(10.0f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        PropertyFactory.lineOpacity(1.0f),
        PropertyFactory.visibility(Property.NONE),
      ),
    )
  }

  if (style.getLayer(LYR_ROUTE) == null) {
    style.addLayer(
      LineLayer(LYR_ROUTE, SRC_ROUTE).withProperties(
        PropertyFactory.lineColor(RoadColors.Primary.toArgb()),
        PropertyFactory.lineWidth(6.0f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
      ),
    )
  }

  if (style.getLayer(LYR_CONE) == null) {
    style.addLayer(
      FillLayer(LYR_CONE, SRC_CONE).withProperties(
        PropertyFactory.fillColor(RoadColors.Primary.toArgb()),
        PropertyFactory.fillOpacity(0.22f),
      ),
    )
  }

  if (style.getLayer(LYR_USER_HALO) == null) {
    style.addLayer(
      CircleLayer(LYR_USER_HALO, SRC_USER).withProperties(
        PropertyFactory.circleRadius(12f),
        PropertyFactory.circleColor(android.graphics.Color.WHITE),
        PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
      ),
    )
  }

  if (style.getLayer(LYR_USER_DOT) == null) {
    style.addLayer(
      CircleLayer(LYR_USER_DOT, SRC_USER).withProperties(
        PropertyFactory.circleRadius(8.5f),
        PropertyFactory.circleColor(RoadColors.Primary.toArgb()),
        PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
      ),
    )
  }

  if (style.getLayer(LYR_USER_CHEVRON) == null) {
    style.addLayer(
      SymbolLayer(LYR_USER_CHEVRON, SRC_USER).withProperties(
        PropertyFactory.iconImage(IMG_USER_CHEVRON),
        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        PropertyFactory.iconRotate(Expression.get("bearing")),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.visibility(Property.NONE),
      ),
    )
  }

  if (style.getLayer(LYR_USER_MOCK_BADGE) == null) {
    style.addLayer(
      SymbolLayer(LYR_USER_MOCK_BADGE, SRC_USER).withProperties(
        PropertyFactory.iconImage(IMG_MOCK_LOCK),
        PropertyFactory.iconOffset(arrayOf(10f, -10f)),
        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.visibility(Property.NONE),
      ),
    )
  }

  // Saved place pins sit below feature pins (LYR_PINS) so they never cover 1A, R1, etc.
  if (style.getLayer(LYR_SAVED_PINS) == null) {
    style.addLayer(
      SymbolLayer(LYR_SAVED_PINS, SRC_SAVED_PINS).withProperties(
        PropertyFactory.iconImage(Expression.get("iconId")),
        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
      ),
    )
  }

  if (style.getLayer(LYR_FOUND_PIN) == null) {
    style.addLayer(
      SymbolLayer(LYR_FOUND_PIN, SRC_FOUND_PIN).withProperties(
        PropertyFactory.iconImage(Expression.get("iconId")),
        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
      ),
    )
  }

  if (style.getLayer(LYR_LANDMARKS) == null) {
    style.addLayer(
      SymbolLayer(LYR_LANDMARKS, SRC_LANDMARKS).withProperties(
        PropertyFactory.iconImage(Expression.get("iconId")),
        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
        PropertyFactory.iconAllowOverlap(false),
        PropertyFactory.iconIgnorePlacement(false),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
      ).apply { minZoom = 14.2f },
    )
  }

  if (style.getLayer(LYR_PINS) == null) {
    style.addLayer(
      SymbolLayer(LYR_PINS, SRC_PINS).withProperties(
        PropertyFactory.iconImage(Expression.get("iconId")),
        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
      ),
    )
  }
}

private fun syncHudLayers(
  context: Context,
  style: Style,
  uiState: RoadHudUiState,
  isMapDark: Boolean,
  isPanelExpanded: Boolean = false,
  chasePose: ChaseCameraPose? = null,
) {
  if (!style.isFullyLoaded) return
  val lightVis = if (isMapDark) Property.NONE else Property.VISIBLE
  style.getLayer(LYR_STUBS_CASING)?.setProperties(PropertyFactory.visibility(lightVis))
  style.getLayer(LYR_JUNCTION_DOT)?.setProperties(PropertyFactory.visibility(lightVis))
  style.getLayer(LYR_ROUTE_CASING)?.setProperties(PropertyFactory.visibility(lightVis))
  style.getLayer(LYR_SAVED_STREETS_CASING)?.setProperties(PropertyFactory.visibility(lightVis))
  style.getLayer(LYR_FOUND_ROAD_CASING)?.setProperties(PropertyFactory.visibility(lightVis))
  style.getLayer(LYR_USER_HALO)?.setProperties(
    PropertyFactory.circleRadius(if (isMapDark) 12f else 10.5f),
    PropertyFactory.circleStrokeWidth(if (isMapDark) 0f else 1.0f),
    PropertyFactory.circleStrokeColor(android.graphics.Color.argb(46, 11, 18, 32)),
  )

  val buildingVis = if (uiState.settings.performanceModeEnabled) Property.NONE else Property.VISIBLE
  style.layers.filterIsInstance<FillExtrusionLayer>().forEach { layer ->
    layer.setProperties(PropertyFactory.visibility(buildingVis))
  }

  val chaseActive = uiState.settings.chaseCameraEnabled

  // 1) User dot / chase chevron, mock lock badge & heading cone
  val loc = uiState.location
  val renderPoint = uiState.displayLocationPoint ?: loc?.point
  val renderHeading = uiState.displayHeadingDeg ?: loc?.headingDeg
  if (renderPoint != null && renderHeading != null) {
    val pt = Point.fromLngLat(renderPoint.lon, renderPoint.lat)
    val userFeature =
      Feature.fromGeometry(pt).apply {
        addNumberProperty("bearing", renderHeading)
      }
    style.getSourceAs<GeoJsonSource>(SRC_USER)?.setGeoJson(userFeature)

    if (chaseActive) {
      if (style.getImage(IMG_USER_CHEVRON) == null) {
        style.addImage(IMG_USER_CHEVRON, createUserChevronBitmap(context))
      }
      style.getLayer(LYR_USER_CHEVRON)?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
      style.getLayer(LYR_USER_HALO)?.setProperties(PropertyFactory.visibility(Property.NONE))
      style.getLayer(LYR_USER_DOT)?.setProperties(PropertyFactory.visibility(Property.NONE))
    } else {
      style.getLayer(LYR_USER_CHEVRON)?.setProperties(PropertyFactory.visibility(Property.NONE))
      style.getLayer(LYR_USER_HALO)?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
      style.getLayer(LYR_USER_DOT)?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
    }

    if (BuildConfig.DEBUG && uiState.isMockModeActive) {
      if (style.getImage(IMG_MOCK_LOCK) == null) {
        style.addImage(IMG_MOCK_LOCK, createMockLockBadgeBitmap(context))
      }
      style.getLayer(LYR_USER_MOCK_BADGE)?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
    } else {
      style.getLayer(LYR_USER_MOCK_BADGE)?.setProperties(PropertyFactory.visibility(Property.NONE))
    }

    val coneDist =
      chasePose?.headingConeMeters
        ?: if (uiState.travelMode == TravelMode.DRIVING) 85.0 else 38.0
    val conePts = mutableListOf(pt)
    for (offset in -28..28 step 7) {
      val edge = GeoMath.offsetPoint(renderPoint, (renderHeading + offset + 360.0) % 360.0, coneDist)
      conePts += Point.fromLngLat(edge.lon, edge.lat)
    }
    conePts += pt
    style.getSourceAs<GeoJsonSource>(SRC_CONE)
      ?.setGeoJson(Feature.fromGeometry(Polygon.fromLngLats(listOf(conePts))))
  } else {
    style.getSourceAs<GeoJsonSource>(SRC_USER)?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    style.getSourceAs<GeoJsonSource>(SRC_CONE)?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    style.getLayer(LYR_USER_CHEVRON)?.setProperties(PropertyFactory.visibility(Property.NONE))
    style.getLayer(LYR_USER_MOCK_BADGE)?.setProperties(PropertyFactory.visibility(Property.NONE))
  }

  // 2) Secondary parallel road candidate outline when uncertain
  val secondary = if (uiState.matchResult.isUncertain) uiState.matchResult.secondary else null
  if (secondary != null && secondary.way.points.size >= 2) {
    val line = LineString.fromLngLats(secondary.way.points.map { Point.fromLngLat(it.lon, it.lat) })
    style.getSourceAs<GeoJsonSource>(SRC_UNCERTAIN)?.setGeoJson(Feature.fromGeometry(line))
  } else {
    style.getSourceAs<GeoJsonSource>(SRC_UNCERTAIN)?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
  }

  val offRoad = uiState.offRoadState
  val stubFeatures = mutableListOf<Feature>()
  val junctionDotFeatures = mutableListOf<Feature>()
  val pinFeatures = mutableListOf<Feature>()

  val themeKey = if (isMapDark) "d" else "l"
  val maxPinDistanceMeters =
    if (chaseActive && !uiState.isOverviewMode && renderPoint != null) {
      chasePose?.pinMaxDistanceMeters ?: ChaseCameraSmoother.CHASE_PIN_MAX_DIST_METERS
    } else {
      null
    }
  val stubClampMeters =
    if (!uiState.isOverviewMode && renderPoint != null) {
      val clampPitch = chasePose?.tiltDeg ?: if (uiState.travelMode == TravelMode.DRIVING) 32.0 else 16.0
      val clampZoom = chasePose?.zoom ?: if (uiState.travelMode == TravelMode.DRIVING) 16.3 else 17.8
      ChaseCameraSmoother.maxPitchedStubMeters(renderPoint.lat, clampZoom, clampPitch)
    } else {
      null
    }

  if (offRoad.isOffRoad && offRoad.selectedCandidate == null) {
    offRoad.candidates.forEachIndexed { idx, cand ->
      val colorInt = RoadColors.pinColorForIndex(idx + 1).toArgb()
      val hexColor = String.format("#%06X", 0xFFFFFF and colorInt)
      val clampedStub = ChaseCameraSmoother.clampPolylineByMeters(cand.stubWorldPoints, stubClampMeters)
      if (clampedStub.size >= 2) {
        val line = LineString.fromLngLats(clampedStub.map { Point.fromLngLat(it.lon, it.lat) })
        val sf = Feature.fromGeometry(line)
        sf.addStringProperty("color", hexColor)
        stubFeatures += sf
      }
      val iconId = "pin_${cand.badgeId}_${colorInt}_$themeKey"
      if (style.getImage(iconId) == null) {
        style.addImage(iconId, createPinBitmap(context, cand.badgeId, colorInt, isMapDark))
      }
      val pf = Feature.fromGeometry(Point.fromLngLat(cand.closestPoint.lon, cand.closestPoint.lat))
      pf.addStringProperty("iconId", iconId)
      pf.addNumberProperty("offroadWayId", cand.candidate.way.id)
      pinFeatures += pf
    }
  } else {
    val selected = uiState.selectedFeature
    if (selected != null) {
      val colorInt = RoadColors.pinColorForIndex(selected.index).toArgb()
      val hexColor = String.format("#%06X", 0xFFFFFF and colorInt)
      val selType = selected.effectiveDisplayType()
      val isCurveSel =
        selType == FeatureType.SHARP_CURVE ||
          selType == FeatureType.HAIRPIN ||
          selType == FeatureType.WINDING_ROAD
      if (isCurveSel) {
        val clampedCurve = ChaseCameraSmoother.clampPolylineByMeters(selected.outgoingMainWorldPoints, stubClampMeters)
        if (clampedCurve.size >= 2) {
          val line = LineString.fromLngLats(clampedCurve.map { Point.fromLngLat(it.lon, it.lat) })
          val feat = Feature.fromGeometry(line)
          feat.addStringProperty("color", hexColor)
          feat.addNumberProperty("width", 3.2f)
          feat.addNumberProperty("casingWidth", 6.0f)
          stubFeatures += feat
        }
      } else {
        selected.branches.forEach { branch ->
          val clampedBranch = ChaseCameraSmoother.clampPolylineByMeters(branch.worldPoints, stubClampMeters)
          if (clampedBranch.size >= 2) {
            val line = LineString.fromLngLats(clampedBranch.map { Point.fromLngLat(it.lon, it.lat) })
            val feat = Feature.fromGeometry(line)
            feat.addStringProperty("color", hexColor)
            stubFeatures += feat
          }
        }
        if (selected.branches.isNotEmpty()) {
          val jf = Feature.fromGeometry(Point.fromLngLat(selected.anchor.lon, selected.anchor.lat))
          jf.addStringProperty("color", hexColor)
          junctionDotFeatures += jf
        }
      }
    }

    uiState.features.forEach { feat ->
      val featWithinHorizon =
        maxPinDistanceMeters == null ||
          renderPoint == null ||
          GeoMath.haversineMeters(renderPoint, feat.anchor) <= maxPinDistanceMeters
      if (!featWithinHorizon && feat.index != selected?.index) return@forEach

      val colorInt = RoadColors.pinColorForIndex(feat.index).toArgb()
      val fType = feat.effectiveDisplayType()
      val isCurveFeat =
        fType == FeatureType.SHARP_CURVE ||
          fType == FeatureType.HAIRPIN ||
          fType == FeatureType.WINDING_ROAD
      if (!isCurveFeat && feat.index == selected?.index && feat.branches.isNotEmpty()) {
        val sortedBranches = feat.branches.sortedBy { it.distanceMeters }
        val limitPins = feat.isInterchangeGroup() && !uiState.isOverviewMode && !isPanelExpanded
        val visibleBranches =
          when {
            maxPinDistanceMeters != null && renderPoint != null ->
              sortedBranches.filter { br ->
                val pinLoc = br.departurePoint ?: br.worldPoints.firstOrNull() ?: feat.anchor
                GeoMath.haversineMeters(renderPoint, pinLoc) <= maxPinDistanceMeters
              }.let { if (limitPins) it.take(4) else it }
            limitPins -> sortedBranches.filter { it.distanceMeters <= 400.0 }.take(4)
            else -> sortedBranches
          }
        visibleBranches.forEach { branch ->
          val pinLoc = branch.departurePoint ?: branch.worldPoints.firstOrNull() ?: feat.anchor
          val iconId = "pin_${branch.badgeId}_${colorInt}_$themeKey"
          if (style.getImage(iconId) == null) {
            style.addImage(iconId, createPinBitmap(context, branch.badgeId, colorInt, isMapDark))
          }
          val f = Feature.fromGeometry(Point.fromLngLat(pinLoc.lon, pinLoc.lat))
          f.addStringProperty("iconId", iconId)
          f.addNumberProperty("featureIndex", feat.index)
          pinFeatures += f
        }
        val overflowCount = sortedBranches.size - visibleBranches.size
        if (limitPins && overflowCount > 0 && visibleBranches.isNotEmpty()) {
          val overflowLabel = "+$overflowCount"
          val basePt = visibleBranches.lastOrNull()?.departurePoint ?: feat.anchor
          val overflowPt = GeoMath.offsetPoint(basePt, uiState.location?.headingDeg ?: 0.0, 14.0)
          val iconId = "pin_ovf_${overflowCount}_${colorInt}_$themeKey"
          if (style.getImage(iconId) == null) {
            style.addImage(iconId, createPinBitmap(context, overflowLabel, colorInt, isMapDark))
          }
          val of = Feature.fromGeometry(Point.fromLngLat(overflowPt.lon, overflowPt.lat))
          of.addStringProperty("iconId", iconId)
          of.addNumberProperty("featureIndex", feat.index)
          pinFeatures += of
        }
      } else if (featWithinHorizon) {
        val label = "${feat.index}"
        val iconId = "pin_${label}_${colorInt}_$themeKey"
        if (style.getImage(iconId) == null) {
          style.addImage(iconId, createPinBitmap(context, label, colorInt, isMapDark))
        }
        val f = Feature.fromGeometry(Point.fromLngLat(feat.anchor.lon, feat.anchor.lat))
        f.addStringProperty("iconId", iconId)
        f.addNumberProperty("featureIndex", feat.index)
        pinFeatures += f
      }
    }
  }

  style.getSourceAs<GeoJsonSource>(SRC_STUBS)?.setGeoJson(FeatureCollection.fromFeatures(stubFeatures))
  style.getSourceAs<GeoJsonSource>(SRC_JUNCTION_DOT)?.setGeoJson(FeatureCollection.fromFeatures(junctionDotFeatures))
  style.getSourceAs<GeoJsonSource>(SRC_PINS)?.setGeoJson(FeatureCollection.fromFeatures(pinFeatures))

  // 4) Saved Pins (places: #7B3FE4 drop pin + white-capsule AR/EN label; streets: #7B3FE4 line + line label or centroid pin fallback)
  val savedPinFeatures = mutableListOf<Feature>()
  val savedStreetFeatures = mutableListOf<Feature>()
  val purpleInt = RoadColors.PinPurple.toArgb()

  uiState.savedPins.forEach { pin ->
    if (pin.isStreet && pin.streetLines.any { it.size >= 2 }) {
      val streetLabel = pin.mapLabelText().replace('\n', ' ')
      pin.streetLines.forEach { linePts ->
        if (linePts.size >= 2) {
          val line = LineString.fromLngLats(linePts.map { Point.fromLngLat(it.lon, it.lat) })
          val lf = Feature.fromGeometry(line)
          lf.addNumberProperty("savedPinId", pin.id)
          lf.addStringProperty("label", streetLabel)
          savedStreetFeatures += lf
        }
      }
    } else {
      val keepSavedPin =
        maxPinDistanceMeters == null ||
          renderPoint == null ||
          pin.id == uiState.activeRoutePinId ||
          pin.id == uiState.selectedPinId ||
          GeoMath.haversineMeters(renderPoint, pin.point) <= maxPinDistanceMeters
      if (!keepSavedPin) return@forEach
      val labelText = pin.mapLabelText()
      val iconId = "saved_pin_${pin.id}_${labelText.hashCode()}_$themeKey"
      if (style.getImage(iconId) == null) {
        style.addImage(iconId, createSavedPinWithCapsuleBitmap(context, labelText, purpleInt, isMapDark))
      }
      val pf = Feature.fromGeometry(Point.fromLngLat(pin.point.lon, pin.point.lat))
      pf.addNumberProperty("savedPinId", pin.id)
      pf.addStringProperty("iconId", iconId)
      savedPinFeatures += pf
    }
  }

  style.getSourceAs<GeoJsonSource>(SRC_SAVED_STREETS)
    ?.setGeoJson(FeatureCollection.fromFeatures(savedStreetFeatures))
  style.getSourceAs<GeoJsonSource>(SRC_SAVED_PINS)
    ?.setGeoJson(FeatureCollection.fromFeatures(savedPinFeatures))

  // 4b) Active Find-on-my-road highlight in #7B3FE4 (never adds saved pin, never auto-draws route)
  val activeFound = uiState.activeFoundRoad
  if (activeFound != null) {
    if (activeFound.polyline.size >= 2) {
      val line = LineString.fromLngLats(activeFound.polyline.map { Point.fromLngLat(it.lon, it.lat) })
      style.getSourceAs<GeoJsonSource>(SRC_FOUND_ROAD)?.setGeoJson(Feature.fromGeometry(line))
    } else {
      style.getSourceAs<GeoJsonSource>(SRC_FOUND_ROAD)?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    }
    val foundTitle =
      activeFound.name.primaryText ?: activeFound.name.ref ?: activeFound.placeType
    val iconId = "found_pin_${activeFound.id}_${foundTitle.hashCode()}_$themeKey"
    if (style.getImage(iconId) == null) {
      style.addImage(iconId, createSavedPinWithCapsuleBitmap(context, foundTitle, purpleInt, isMapDark))
    }
    val pf = Feature.fromGeometry(Point.fromLngLat(activeFound.entryPoint.lon, activeFound.entryPoint.lat))
    pf.addStringProperty("iconId", iconId)
    style.getSourceAs<GeoJsonSource>(SRC_FOUND_PIN)?.setGeoJson(FeatureCollection.fromFeatures(listOf(pf)))
  } else {
    style.getSourceAs<GeoJsonSource>(SRC_FOUND_ROAD)?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
    style.getSourceAs<GeoJsonSource>(SRC_FOUND_PIN)?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
  }

  // 5) Active route polyline (only when explicitly requested via "Route" button)
  val route =
    if (uiState.activeRoutePinId != null || uiState.isRoutingToFoundRoad) uiState.explicitRoute
    else null
  if (route != null && route.polyline.size >= 2) {
    val line = LineString.fromLngLats(route.polyline.map { Point.fromLngLat(it.lon, it.lat) })
    style.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(Feature.fromGeometry(line))
  } else {
    style.getSourceAs<GeoJsonSource>(SRC_ROUTE)?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
  }

  // 6) Part C: Developer "Show graph edges on map" overlay (light map: teal/magenta/black; dark map: cyan/magenta/white)
  val debugEdgeFeatures =
    if (BuildConfig.DEBUG && uiState.settings.showGraphEdges && uiState.debugGraphEdges.isNotEmpty()) {
      uiState.debugGraphEdges.mapNotNull { edge ->
        if (edge.points.size < 2) return@mapNotNull null
        val hex = debugGraphEdgeColorHex(edge.category, isMapDark)
        val feat =
          Feature.fromGeometry(
            LineString.fromLngLats(edge.points.map { Point.fromLngLat(it.lon, it.lat) }),
          )
        feat.addStringProperty("color", hex)
        feat
      }
    } else {
      emptyList()
    }
  style.getSourceAs<GeoJsonSource>(SRC_DEBUG_EDGES)
    ?.setGeoJson(FeatureCollection.fromFeatures(debugEdgeFeatures))

  // 7) Notable landmarks along the road ahead (zoom >= 14.2 normally, always in Overview)
  val landmarkLayer = style.getLayer(LYR_LANDMARKS) as? SymbolLayer
  landmarkLayer?.minZoom = if (uiState.isOverviewMode) 0f else 14.2f
  val landmarkFeatures =
    if (uiState.settings.landmarksAheadEnabled && uiState.landmarksAhead.isNotEmpty()) {
      uiState.landmarksAhead.mapNotNull { lm ->
        if (maxPinDistanceMeters != null && renderPoint != null &&
          GeoMath.haversineMeters(renderPoint, lm.point) > maxPinDistanceMeters
        ) {
          return@mapNotNull null
        }
        val title = lm.name.primaryText ?: return@mapNotNull null
        val iconId = "lm_${lm.id}_${title.hashCode()}_$themeKey"
        if (style.getImage(iconId) == null) {
          style.addImage(iconId, createLandmarkBadgeBitmap(context, title, isMapDark))
        }
        Feature.fromGeometry(Point.fromLngLat(lm.point.lon, lm.point.lat)).apply {
          addStringProperty("iconId", iconId)
        }
      }
    } else {
      emptyList()
    }
  style.getSourceAs<GeoJsonSource>(SRC_LANDMARKS)
    ?.setGeoJson(FeatureCollection.fromFeatures(landmarkFeatures))
}

internal fun debugGraphEdgeColorHex(
  category: DebugEdgeCategory,
  isMapDark: Boolean,
): String =
  if (isMapDark) {
    when (category) {
      DebugEdgeCategory.TILE -> "#00E5FF"
      DebugEdgeCategory.OVERPASS -> "#FF2EDB"
      DebugEdgeCategory.MATCHED -> "#FFFFFF"
    }
  } else {
    when (category) {
      DebugEdgeCategory.TILE -> "#0097A7"
      DebugEdgeCategory.OVERPASS -> "#C2185B"
      DebugEdgeCategory.MATCHED -> "#000000"
    }
  }

/**
 * Generates a purple #7B3FE4 drop pin with a crisp white-capsule bilingual label (max 2 lines)
 * directly above it, anchored at the bottom tip of the pin.
 */
private fun createSavedPinWithCapsuleBitmap(
  context: Context,
  label: String,
  pinColorArgb: Int,
  isDark: Boolean,
): Bitmap {
  val density = context.resources.displayMetrics.density
  val scaledDensity = density * context.resources.configuration.fontScale
  val maxLabelWidthPx = (160f * density).toInt().coerceAtLeast(120)

  val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.parseColor("#0B1220")
    textSize = 11.5f * scaledDensity
    typeface = Typeface.DEFAULT_BOLD
  }

  val staticLayout =
    StaticLayout.Builder.obtain(label, 0, label.length, textPaint, maxLabelWidthPx)
      .setAlignment(Layout.Alignment.ALIGN_CENTER)
      .setMaxLines(2)
      .setEllipsize(TextUtils.TruncateAt.END)
      .setIncludePad(false)
      .build()

  var maxLineW = 0f
  for (i in 0 until staticLayout.lineCount) {
    maxLineW = maxOf(maxLineW, staticLayout.getLineWidth(i))
  }
  val hPad = 10f * density
  val vPad = 5f * density
  val capsuleW = (maxLineW + hPad * 2f).coerceAtLeast(40f * density)
  val capsuleH = staticLayout.height + vPad * 2f
  val gapPx = 3f * density
  val pinSizePx = 34f * density

  val totalW = maxOf(capsuleW, pinSizePx).toInt() + (4f * density).toInt()
  val totalH = (capsuleH + gapPx + pinSizePx + 4f * density).toInt()
  val bmp = Bitmap.createBitmap(totalW, totalH, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bmp)
  val centerX = totalW / 2f

  // White rounded capsule with soft border & subtle shadow
  val capsuleTop = 2f * density
  val capsuleRect =
    RectF(
      centerX - capsuleW / 2f,
      capsuleTop,
      centerX + capsuleW / 2f,
      capsuleTop + capsuleH,
    )
  val capsuleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.argb(55, 0, 0, 0)
  }
  val capsuleBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.argb(245, 255, 255, 255)
    style = Paint.Style.FILL
  }
  val capsuleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = if (!isDark) android.graphics.Color.WHITE else pinColorArgb
    style = Paint.Style.STROKE
    strokeWidth = (if (!isDark) 2.0f else 1.4f) * density
  }
  val corner = 10f * density
  canvas.drawRoundRect(
    RectF(capsuleRect.left, capsuleRect.top + 1.5f * density, capsuleRect.right, capsuleRect.bottom + 1.5f * density),
    corner,
    corner,
    capsuleShadow,
  )
  canvas.drawRoundRect(capsuleRect, corner, corner, capsuleBg)
  canvas.drawRoundRect(capsuleRect, corner, corner, capsuleBorder)
  if (!isDark) {
    val innerAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = pinColorArgb
      style = Paint.Style.STROKE
      strokeWidth = 1.2f * density
    }
    canvas.drawRoundRect(capsuleRect, corner, corner, innerAccent)
  }

  canvas.save()
  canvas.translate(centerX - maxLabelWidthPx / 2f, capsuleTop + vPad)
  staticLayout.draw(canvas)
  canvas.restore()

  // Purple drop pin below capsule, tip at bottom center
  val pinTop = capsuleTop + capsuleH + gapPx
  val circleRadius = pinSizePx * 0.36f
  val cy = pinTop + circleRadius
  val pinBottom = pinTop + pinSizePx

  val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = pinColorArgb
    style = Paint.Style.FILL
  }
  val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.WHITE
    style = Paint.Style.STROKE
    strokeWidth = 2f * density
  }
  val tailPath = Path().apply {
    moveTo(centerX - circleRadius * 0.56f, cy + circleRadius * 0.54f)
    lineTo(centerX, pinBottom)
    lineTo(centerX + circleRadius * 0.56f, cy + circleRadius * 0.54f)
    close()
  }
  if (!isDark) {
    val outerCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.WHITE
      style = Paint.Style.STROKE
      strokeWidth = 4f * density
      strokeJoin = Paint.Join.ROUND
    }
    val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.argb(55, 11, 18, 32)
      style = Paint.Style.STROKE
      strokeWidth = 5f * density
      strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(tailPath, rimPaint)
    canvas.drawCircle(centerX, cy, circleRadius + 1.5f * density, rimPaint)
    canvas.drawPath(tailPath, outerCasing)
  }
  canvas.drawPath(tailPath, fillPaint)
  canvas.drawCircle(centerX, cy, circleRadius, fillPaint)
  canvas.drawCircle(centerX, cy, circleRadius, borderPaint)

  val innerDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.WHITE
    style = Paint.Style.FILL
  }
  canvas.drawCircle(centerX, cy, circleRadius * 0.34f, innerDot)

  return bmp
}

/**
 * Generates a 44dp teardrop pin bitmap with 18sp bold ID text.
 */
private fun createPinBitmap(
  context: Context,
  label: String,
  colorArgb: Int,
  isDark: Boolean,
): Bitmap {
  val density = context.resources.displayMetrics.density
  val scaledDensity = density * context.resources.configuration.fontScale
  val sizePx = (44f * density).toInt().coerceAtLeast(44)
  val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bmp)

  val cx = sizePx / 2f
  val circleRadius = sizePx * 0.36f
  val cy = circleRadius + 2f * density

  val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.argb(65, 0, 0, 0)
  }
  canvas.drawCircle(cx, cy + 2f * density, circleRadius, shadowPaint)

  val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = colorArgb
    style = Paint.Style.FILL
  }
  val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.WHITE
    style = Paint.Style.STROKE
    strokeWidth = (if (!isDark) 2.5f else 2.2f) * density
  }

  val tailPath = Path().apply {
    moveTo(cx - circleRadius * 0.55f, cy + circleRadius * 0.55f)
    lineTo(cx, sizePx - 2f * density)
    lineTo(cx + circleRadius * 0.55f, cy + circleRadius * 0.55f)
    close()
  }
  if (!isDark) {
    // 2dp crisp white ring + subtle ink hairline for visibility over light yellow roads & white plazas
    val inkRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.argb(55, 11, 18, 32)
      style = Paint.Style.STROKE
      strokeWidth = 5f * density
      strokeJoin = Paint.Join.ROUND
    }
    val whiteTailCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.WHITE
      style = Paint.Style.STROKE
      strokeWidth = 4f * density
      strokeJoin = Paint.Join.ROUND
    }
    canvas.drawPath(tailPath, inkRim)
    canvas.drawCircle(cx, cy, circleRadius + 1.5f * density, inkRim)
    canvas.drawPath(tailPath, whiteTailCasing)
  }
  canvas.drawPath(tailPath, fillPaint)
  canvas.drawCircle(cx, cy, circleRadius, fillPaint)
  canvas.drawCircle(cx, cy, circleRadius, borderPaint)

  val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.WHITE
    textAlign = Paint.Align.CENTER
    textSize = (if (label.length >= 2) 15f else 18f) * scaledDensity
    typeface = Typeface.DEFAULT_BOLD
  }
  val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
  canvas.drawText(label, cx, textY, textPaint)
  return bmp
}

private fun createMockLockBadgeBitmap(context: Context): Bitmap {
  val density = context.resources.displayMetrics.density
  val sizePx = (18f * density).toInt().coerceAtLeast(18)
  val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bmp)
  val cx = sizePx / 2f
  val cy = sizePx / 2f
  val r = sizePx * 0.44f

  val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.parseColor("#0B57D0")
    style = Paint.Style.FILL
  }
  val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.WHITE
    style = Paint.Style.STROKE
    strokeWidth = 1.6f * density
  }
  canvas.drawCircle(cx, cy, r, bgPaint)
  canvas.drawCircle(cx, cy, r, borderPaint)

  // Draw crisp white lock shackle + body inside circle
  val shacklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.WHITE
    style = Paint.Style.STROKE
    strokeWidth = 1.4f * density
  }
  val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.WHITE
    style = Paint.Style.FILL
  }
  val shackleRect = RectF(cx - 2.3f * density, cy - 3.6f * density, cx + 2.3f * density, cy + 0.4f * density)
  canvas.drawArc(shackleRect, 180f, 180f, false, shacklePaint)
  val bodyRect = RectF(cx - 3.2f * density, cy - 0.6f * density, cx + 3.2f * density, cy + 3.4f * density)
  canvas.drawRoundRect(bodyRect, 1f * density, 1f * density, bodyPaint)
  return bmp
}

private fun createLandmarkBadgeBitmap(
  context: Context,
  label: String,
  isDark: Boolean,
): Bitmap {
  val density = context.resources.displayMetrics.density
  val scaledDensity = density * context.resources.configuration.fontScale
  val maxWidthPx = (128f * density).toInt().coerceAtLeast(80)
  val shortLabel = label.take(22)
  val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#0B1220")
    textSize = 10.5f * scaledDensity
    typeface = Typeface.DEFAULT_BOLD
  }
  val textW = textPaint.measureText(shortLabel).coerceAtMost(maxWidthPx.toFloat())
  val hPad = 7f * density
  val vPad = 3.5f * density
  val w = (textW + hPad * 2f).toInt().coerceAtLeast(24)
  val h = (textPaint.textSize + vPad * 2f + 6f * density).toInt().coerceAtLeast(20)
  val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bmp)
  val rect = RectF(1f, 1f, w - 1f, h - 6f * density)
  val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = if (isDark) android.graphics.Color.argb(225, 22, 32, 48) else android.graphics.Color.argb(242, 255, 255, 255)
  }
  val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.parseColor("#00A389")
    style = Paint.Style.STROKE
    strokeWidth = 1.4f * density
  }
  canvas.drawRoundRect(rect, 7f * density, 7f * density, bgPaint)
  canvas.drawRoundRect(rect, 7f * density, 7f * density, borderPaint)
  val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
  canvas.drawText(shortLabel, hPad, textY, textPaint)
  canvas.drawCircle(w / 2f, h - 3f * density, 2.5f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.parseColor("#00A389")
  })
  return bmp
}

private fun createUserChevronBitmap(context: Context): Bitmap {
  val density = context.resources.displayMetrics.density
  val sizePx = (44f * density).toInt().coerceAtLeast(44)
  val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bmp)
  val cx = sizePx / 2f
  val cy = sizePx / 2f

  val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.argb(60, 45, 124, 255)
    style = Paint.Style.FILL
  }
  canvas.drawCircle(cx, cy, sizePx * 0.42f, glowPaint)

  val chevronPath = Path().apply {
    moveTo(cx, 5f * density)
    lineTo(sizePx - 8f * density, sizePx - 8f * density)
    lineTo(cx, cy + 5f * density)
    lineTo(8f * density, sizePx - 8f * density)
    close()
  }

  val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.argb(85, 0, 0, 0)
    style = Paint.Style.STROKE
    strokeWidth = 5.5f * density
    strokeJoin = Paint.Join.ROUND
  }
  canvas.drawPath(chevronPath, shadowPaint)

  val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.WHITE
    style = Paint.Style.STROKE
    strokeWidth = 3.5f * density
    strokeJoin = Paint.Join.ROUND
  }
  val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = RoadColors.Primary.toArgb()
    style = Paint.Style.FILL
  }
  canvas.drawPath(chevronPath, borderPaint)
  canvas.drawPath(chevronPath, fillPaint)
  return bmp
}

private fun buildRasterStyleJson(tileUrlTemplate: String): String =
  """
  {
    "version": 8,
    "sources": {
      "osm-tiles": {
        "type": "raster",
        "tiles": ["$tileUrlTemplate"],
        "tileSize": 256
      }
    },
    "layers": [
      {
        "id": "osm-raster-layer",
        "type": "raster",
        "source": "osm-tiles"
      }
    ]
  }
  """.trimIndent()
