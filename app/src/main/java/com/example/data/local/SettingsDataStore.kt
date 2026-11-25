package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import com.example.config.AppConfig
import com.example.data.model.DeveloperOrientation
import com.example.data.model.LandscapePanelSide
import com.example.data.model.PanelStyle
import com.example.data.model.TestSpot
import com.example.data.model.ThemePreference
import com.example.data.model.TravelMode
import com.example.data.model.UnitPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsDataStore by preferencesDataStore(name = "road_awareness_prefs")

data class UserSettings(
  val themePreference: ThemePreference = ThemePreference.AUTO,
  val panelStyle: PanelStyle = PanelStyle.ALWAYS_DARK,
  val unitPreference: UnitPreference = UnitPreference.AUTO,
  val arabicTtsEnabled: Boolean = false,
  val travelMode: TravelMode = TravelMode.DRIVING,
  val showGraphInspector: Boolean = false,
  val showGraphEdges: Boolean = false,
  val mockLocationEnabled: Boolean = BuildConfig.DEBUG,
  val experimentalBlurEnabled: Boolean = false,
  val reduceTransparencyEnabled: Boolean = false,
  val lastMockLat: Double? = null,
  val lastMockLon: Double? = null,
  val lastMockHeadingDeg: Double = AppConfig.DEFAULT_FALLBACK_HEADING_DEG,
  val lastLiveLat: Double? = null,
  val lastLiveLon: Double? = null,
  val mockSpeedKmh: Double = 30.0,
  val mockAccuracyMeters: Float = 3.0f,
  val mockJitterEnabled: Boolean = false,
  val savedTestSpots: List<TestSpot> = emptyList(),
  val testSpotsSeeded: Boolean = false,
  val developerOrientation: DeveloperOrientation = DeveloperOrientation.AUTO,
  val landscapePanelSide: LandscapePanelSide? = null,
  val horizonDistanceMeters: Double = AppConfig.DRIVING_DEFAULT_HORIZON_M,
  val showMinorJunctions: Boolean = false,
  val landmarksAheadEnabled: Boolean = true,
  val recentSearches: List<String> = emptyList(),
)

class SettingsRepository(private val context: Context) {
  private val keyTheme = stringPreferencesKey("theme_preference")
  private val keyPanelStyle = stringPreferencesKey("panel_style")
  private val keyUnitPref = stringPreferencesKey("unit_preference")
  private val keyArabicTts = booleanPreferencesKey("arabic_tts_enabled")
  private val keyTravelMode = stringPreferencesKey("travel_mode")
  private val keyShowInspector = booleanPreferencesKey("show_graph_inspector")
  private val keyShowGraphEdges = booleanPreferencesKey("show_graph_edges")
  private val keyMockLocation = booleanPreferencesKey("mock_location_enabled")
  private val keyExperimentalBlur = booleanPreferencesKey("experimental_glass_blur")
  private val keyReduceTransparency = booleanPreferencesKey("reduce_transparency")
  private val keyLastMockLat = doublePreferencesKey("last_mock_lat")
  private val keyLastMockLon = doublePreferencesKey("last_mock_lon")
  private val keyLastMockHeading = doublePreferencesKey("last_mock_heading_deg")
  private val keyLastLiveLat = doublePreferencesKey("last_live_lat")
  private val keyLastLiveLon = doublePreferencesKey("last_live_lon")
  private val keyMockSpeedKmh = doublePreferencesKey("mock_speed_kmh")
  private val keyMockAccuracyMeters = floatPreferencesKey("mock_accuracy_meters")
  private val keyMockJitterEnabled = booleanPreferencesKey("mock_jitter_enabled")
  private val keySavedTestSpots = stringPreferencesKey("saved_test_spots_json")
  private val keyTestSpotsSeeded = booleanPreferencesKey("test_spots_seeded")
  private val keyDeveloperOrientation = stringPreferencesKey("developer_orientation")
  private val keyLandscapePanelSide = stringPreferencesKey("landscape_panel_side")
  private val keyHorizonDistanceMeters = doublePreferencesKey("horizon_distance_meters")
  private val keyShowMinorJunctions = booleanPreferencesKey("show_minor_junctions")
  private val keyLandmarksAheadEnabled = booleanPreferencesKey("landmarks_ahead_enabled")
  private val keyRecentSearches = stringPreferencesKey("recent_searches_json")

  val settingsFlow: Flow<UserSettings> =
    context.settingsDataStore.data.map { prefs ->
      val theme =
        runCatching { ThemePreference.valueOf(prefs[keyTheme] ?: ThemePreference.AUTO.name) }
          .getOrDefault(ThemePreference.AUTO)
      val panelStyle =
        runCatching { PanelStyle.valueOf(prefs[keyPanelStyle] ?: PanelStyle.ALWAYS_DARK.name) }
          .getOrDefault(PanelStyle.ALWAYS_DARK)
      val unitPref =
        runCatching { UnitPreference.valueOf(prefs[keyUnitPref] ?: UnitPreference.AUTO.name) }
          .getOrDefault(UnitPreference.AUTO)
      val mode =
        runCatching { TravelMode.valueOf(prefs[keyTravelMode] ?: TravelMode.DRIVING.name) }
          .getOrDefault(TravelMode.DRIVING)
      val devOrientation =
        if (BuildConfig.DEBUG) {
          runCatching {
            DeveloperOrientation.valueOf(prefs[keyDeveloperOrientation] ?: DeveloperOrientation.AUTO.name)
          }.getOrDefault(DeveloperOrientation.AUTO)
        } else {
          DeveloperOrientation.AUTO
        }
      val spots =
        if (BuildConfig.DEBUG) decodeSpots(prefs[keySavedTestSpots]) else emptyList()
      val panelSide =
        prefs[keyLandscapePanelSide]?.let { raw ->
          runCatching { LandscapePanelSide.valueOf(raw) }.getOrNull()
        }
      UserSettings(
        themePreference = theme,
        panelStyle = panelStyle,
        unitPreference = unitPref,
        arabicTtsEnabled = prefs[keyArabicTts] ?: false,
        travelMode = mode,
        showGraphInspector = if (BuildConfig.DEBUG) (prefs[keyShowInspector] ?: false) else false,
        showGraphEdges = if (BuildConfig.DEBUG) (prefs[keyShowGraphEdges] ?: false) else false,
        mockLocationEnabled = if (BuildConfig.DEBUG) (prefs[keyMockLocation] ?: true) else false,
        experimentalBlurEnabled = prefs[keyExperimentalBlur] ?: false,
        reduceTransparencyEnabled = prefs[keyReduceTransparency] ?: false,
        lastMockLat = prefs[keyLastMockLat],
        lastMockLon = prefs[keyLastMockLon],
        lastMockHeadingDeg = ((prefs[keyLastMockHeading] ?: AppConfig.DEFAULT_FALLBACK_HEADING_DEG) % 360.0 + 360.0) % 360.0,
        lastLiveLat = prefs[keyLastLiveLat],
        lastLiveLon = prefs[keyLastLiveLon],
        mockSpeedKmh = (prefs[keyMockSpeedKmh] ?: 30.0).coerceIn(0.0, 120.0),
        mockAccuracyMeters = (prefs[keyMockAccuracyMeters] ?: 3.0f).coerceIn(3.0f, 50.0f),
        mockJitterEnabled = if (BuildConfig.DEBUG) (prefs[keyMockJitterEnabled] ?: false) else false,
        savedTestSpots = spots,
        testSpotsSeeded = prefs[keyTestSpotsSeeded] ?: false,
        developerOrientation = devOrientation,
        landscapePanelSide = panelSide,
        horizonDistanceMeters =
          (prefs[keyHorizonDistanceMeters] ?: AppConfig.DRIVING_DEFAULT_HORIZON_M)
            .coerceIn(AppConfig.DRIVING_MIN_LOOKAHEAD_M, AppConfig.DRIVING_MAX_LOOKAHEAD_M),
        showMinorJunctions = prefs[keyShowMinorJunctions] ?: false,
        landmarksAheadEnabled = prefs[keyLandmarksAheadEnabled] ?: true,
        recentSearches = decodeRecentSearches(prefs[keyRecentSearches]),
      )
    }

  suspend fun setTheme(theme: ThemePreference) {
    context.settingsDataStore.edit { it[keyTheme] = theme.name }
  }

  suspend fun setPanelStyle(style: PanelStyle) {
    context.settingsDataStore.edit { it[keyPanelStyle] = style.name }
  }

  suspend fun setUnitPreference(pref: UnitPreference) {
    context.settingsDataStore.edit { it[keyUnitPref] = pref.name }
  }

  suspend fun setArabicTtsEnabled(enabled: Boolean) {
    context.settingsDataStore.edit { it[keyArabicTts] = enabled }
  }

  suspend fun setTravelMode(mode: TravelMode) {
    context.settingsDataStore.edit { it[keyTravelMode] = mode.name }
  }

  suspend fun setShowGraphInspector(enabled: Boolean) {
    context.settingsDataStore.edit { it[keyShowInspector] = enabled }
  }

  suspend fun setShowGraphEdges(enabled: Boolean) {
    context.settingsDataStore.edit { it[keyShowGraphEdges] = enabled }
  }

  suspend fun setMockLocationEnabled(enabled: Boolean) {
    context.settingsDataStore.edit { it[keyMockLocation] = enabled }
  }

  suspend fun setExperimentalBlurEnabled(enabled: Boolean) {
    context.settingsDataStore.edit { it[keyExperimentalBlur] = enabled }
  }

  suspend fun setReduceTransparencyEnabled(enabled: Boolean) {
    context.settingsDataStore.edit { it[keyReduceTransparency] = enabled }
  }

  suspend fun setLastMockPoint(lat: Double, lon: Double) {
    context.settingsDataStore.edit {
      it[keyLastMockLat] = lat
      it[keyLastMockLon] = lon
    }
  }

  suspend fun setLastMockState(lat: Double, lon: Double, headingDeg: Double) {
    context.settingsDataStore.edit {
      it[keyLastMockLat] = lat
      it[keyLastMockLon] = lon
      it[keyLastMockHeading] = ((headingDeg % 360.0) + 360.0) % 360.0
    }
  }

  suspend fun setLastMockHeading(headingDeg: Double) {
    context.settingsDataStore.edit {
      it[keyLastMockHeading] = ((headingDeg % 360.0) + 360.0) % 360.0
    }
  }

  suspend fun setLastLivePoint(lat: Double, lon: Double) {
    context.settingsDataStore.edit {
      it[keyLastLiveLat] = lat
      it[keyLastLiveLon] = lon
    }
  }

  suspend fun setMockSpeedKmh(speedKmh: Double) {
    context.settingsDataStore.edit {
      it[keyMockSpeedKmh] = speedKmh.coerceIn(0.0, 120.0)
    }
  }

  suspend fun setMockAccuracyMeters(accuracyMeters: Float) {
    context.settingsDataStore.edit {
      it[keyMockAccuracyMeters] = accuracyMeters.coerceIn(3.0f, 50.0f)
    }
  }

  suspend fun setMockJitterEnabled(enabled: Boolean) {
    context.settingsDataStore.edit { it[keyMockJitterEnabled] = enabled }
  }

  suspend fun setSavedTestSpots(spots: List<TestSpot>) {
    val json = encodeSpots(spots)
    context.settingsDataStore.edit { it[keySavedTestSpots] = json }
  }

  suspend fun seedTestSpotsOnceIfNeeded(seedSpots: List<TestSpot>) {
    if (!BuildConfig.DEBUG) return
    context.settingsDataStore.edit { prefs ->
      if (prefs[keyTestSpotsSeeded] == true) return@edit
      val existing = decodeSpots(prefs[keySavedTestSpots])
      val existingIds = existing.mapTo(HashSet()) { it.id }
      val merged = existing + seedSpots.filter { it.id !in existingIds }
      prefs[keySavedTestSpots] = encodeSpots(merged)
      prefs[keyTestSpotsSeeded] = true
    }
  }

  suspend fun setDeveloperOrientation(orientation: DeveloperOrientation) {
    context.settingsDataStore.edit { it[keyDeveloperOrientation] = orientation.name }
  }

  suspend fun setLandscapePanelSide(side: LandscapePanelSide) {
    context.settingsDataStore.edit { it[keyLandscapePanelSide] = side.name }
  }

  suspend fun setHorizonDistanceMeters(meters: Double) {
    val clamped = meters.coerceIn(AppConfig.DRIVING_MIN_LOOKAHEAD_M, AppConfig.DRIVING_MAX_LOOKAHEAD_M)
    context.settingsDataStore.edit { it[keyHorizonDistanceMeters] = clamped }
  }

  suspend fun setShowMinorJunctions(enabled: Boolean) {
    context.settingsDataStore.edit { it[keyShowMinorJunctions] = enabled }
  }

  suspend fun setLandmarksAheadEnabled(enabled: Boolean) {
    context.settingsDataStore.edit { it[keyLandmarksAheadEnabled] = enabled }
  }

  suspend fun addRecentSearch(query: String) {
    val clean = query.trim()
    if (clean.isEmpty()) return
    context.settingsDataStore.edit { prefs ->
      val existing = decodeRecentSearches(prefs[keyRecentSearches])
      val updated = (listOf(clean) + existing.filterNot { it.equals(clean, ignoreCase = true) }).take(10)
      prefs[keyRecentSearches] = JSONArray(updated).toString()
    }
  }

  companion object {
    internal fun encodeSpots(spots: List<TestSpot>): String {
      val arr = JSONArray()
      for (spot in spots) {
        val obj = JSONObject()
        obj.put("id", spot.id)
        obj.put("name", spot.name)
        obj.put("lat", spot.lat)
        obj.put("lon", spot.lon)
        obj.put("headingDeg", spot.headingDeg)
        obj.put("travelMode", spot.travelMode.name)
        obj.put("createdAtMs", spot.createdAtMs)
        arr.put(obj)
      }
      return arr.toString()
    }

    internal fun decodeSpots(raw: String?): List<TestSpot> {
      if (raw.isNullOrBlank()) return emptyList()
      return runCatching {
        val arr = JSONArray(raw)
        buildList(arr.length()) {
          for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val lat = obj.optDouble("lat", Double.NaN)
            val lon = obj.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val mode =
              runCatching { TravelMode.valueOf(obj.optString("travelMode", TravelMode.DRIVING.name)) }
                .getOrDefault(TravelMode.DRIVING)
            add(
              TestSpot(
                id = obj.optString("id", "spot_$i"),
                name = obj.optString("name", "Spot ${i + 1}"),
                lat = lat,
                lon = lon,
                headingDeg = obj.optDouble("headingDeg", 0.0),
                travelMode = mode,
                createdAtMs = obj.optLong("createdAtMs", 0L),
              )
            )
          }
        }
      }.getOrDefault(emptyList())
    }

    internal fun decodeRecentSearches(raw: String?): List<String> {
      if (raw.isNullOrBlank()) return emptyList()
      return runCatching {
        val arr = JSONArray(raw)
        buildList(arr.length()) {
          for (i in 0 until arr.length()) {
            val v = arr.optString(i).trim()
            if (v.isNotEmpty()) add(v)
          }
        }.take(10)
      }.getOrDefault(emptyList())
    }
  }
}
