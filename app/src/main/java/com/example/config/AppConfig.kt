package com.example.config

/**
 * Centralized configuration for all external map, graph, search, and routing endpoints.
 * Replace any URL or API key placeholder here to switch to self-hosted or commercial providers.
 * Default build uses strictly keyless OpenStreetMap / OpenFreeMap endpoints.
 */
object AppConfig {
  // Replace CONTACT with your email or repository URL for compliant Overpass User-Agent identification
  const val CONTACT = "https://github.com/road-awareness/android"
  const val USER_AGENT = "RoadAwareness/1.0 ($CONTACT)"

  // Keyless MapLibre Style & Tile Fallback (used for both Light and runtime-generated Dark style)
  const val MAP_STYLE_LIGHT_URL = "https://tiles.openfreemap.org/styles/liberty"
  const val MAP_RASTER_LIGHT_FALLBACK_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
  const val MAP_ATTRIBUTION_OSM = "© OpenStreetMap contributors"
  const val MAP_ATTRIBUTION_PROVIDER = "OpenFreeMap"
  const val MAP_API_KEY_PLACEHOLDER = ""

  // Overpass API Mirrors (trusted TLS roots only; dynamically re-ranked by recent success)
  val OVERPASS_ENDPOINTS =
    listOf(
      "https://overpass-api.de/api/interpreter",
      "https://overpass.private.coffee/api/interpreter",
      "https://overpass.kumi.systems/api/interpreter",
    )
  const val OVERPASS_PRIMARY_URL = "https://overpass-api.de/api/interpreter"
  const val OVERPASS_FALLBACK_URL = "https://overpass.private.coffee/api/interpreter"
  const val OVERPASS_API_KEY_PLACEHOLDER = ""

  // Nominatim Search API
  const val NOMINATIM_BASE_URL = "https://nominatim.openstreetmap.org/"
  const val NOMINATIM_API_KEY_PLACEHOLDER = ""

  // Valhalla Routing API
  const val VALHALLA_BASE_URL = "https://valhalla1.openstreetmap.de/"
  const val VALHALLA_API_KEY_PLACEHOLDER = ""

  // Networking, Overpass Timeouts, Hedging & Cooldowns
  const val CONNECT_TIMEOUT_SEC = 6L
  const val READ_TIMEOUT_SEC = 15L
  const val CALL_TIMEOUT_SEC = 15L
  const val OVERPASS_QUERY_TIMEOUT_SEC = 10
  const val OVERPASS_TEST_QUERY_TIMEOUT_SEC = 5
  const val OVERPASS_HEDGE_DELAY_MS = 2_000L
  const val OVERPASS_MAX_IN_FLIGHT = 2
  const val OVERPASS_MAX_RETRIES = 2
  const val OVERPASS_INITIAL_BACKOFF_MS = 450L
  const val OVERPASS_ENDPOINT_COOLDOWN_MS = 60_000L
  const val OVERPASS_REJECT_406_COOLDOWN_MS = 10L * 60L * 1000L // 10 minutes
  const val OVERPASS_MAX_BACKOFF_MS = 15L * 60L * 1000L // 15 minutes max exponential backoff
  const val OVERPASS_DEFAULT_429_WAIT_MS = 5_000L
  const val OVERPASS_MIN_BBOX_HALF_METERS = 100.0

  // UI Status Timing Thresholds
  const val STATUS_CHIP_DELAY_MS = 5_000L
  const val SKELETON_TIMEOUT_MS = 6_000L
  const val STALE_FAILURE_THRESHOLD_MS = 6_000L
  const val LOADING_REASON_TIMEOUT_MS = 3_000L
  const val DATA_UPDATING_STALE_AGE_MS = 10L * 60L * 1000L // 10 min

  // Room ~400m Tile Grid & z14 Vector Tile Rolling Cache (30 mem tiles; 30-day / 200 MB disk TTL; 6h TileJSON)
  const val CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L // 30 days
  const val TILEJSON_TTL_MS = 6L * 60L * 60L * 1000L // 6 hours
  const val CACHE_CELL_SIZE_DEG = 0.0036 // ~400 m x 400 m grid cells
  const val REFETCH_DISTANCE_METERS = 180.0
  const val REFETCH_HEADING_DELTA_DEG = 35.0
  const val TILE_GRAPH_ZOOM = 14
  const val TILE_NAME_ATTACH_MAX_METERS = 6.0
  const val TILE_ENDPOINT_ROUND_SCALE = 1_000_000.0 // 1e-6 degrees
  const val MAX_MEMORY_TILES = 30
  const val MAX_MEMORY_OVERPASS_CELLS = 48
  const val MAX_DISK_TILE_CACHE_BYTES = 200L * 1024L * 1024L // 200 MB
  const val MAX_PARALLEL_TILE_DECODES = 3

  // Compact Overpass Query Corridors
  const val OVERPASS_DRIVING_RADIUS_M = 300.0
  const val OVERPASS_DRIVING_AHEAD_M = 600.0
  const val OVERPASS_WALKING_RADIUS_M = 200.0
  const val OVERPASS_WALKING_AHEAD_M = 200.0

  // Lookahead Horizons (configured 1/2/3/5 km, default 3 km Driving, 1 km Walking)
  const val DRIVING_MIN_LOOKAHEAD_M = 1_000.0
  const val DRIVING_DEFAULT_HORIZON_M = 3_000.0
  const val DRIVING_MAX_LOOKAHEAD_M = 5_000.0
  const val DRIVING_CORRIDOR_WIDTH_M = 300.0
  const val WALKING_LOOKAHEAD_M = 1_000.0
  const val WALKING_CORRIDOR_WIDTH_M = 200.0

  // Geometry, Stationary Heading & Matching Thresholds
  const val STATIONARY_SPEED_THRESHOLD_MPS = 1.0
  const val SHARP_CURVE_MIN_ANGLE_DEG = 42.0
  const val SHARP_CURVE_MAX_RADIUS_M = 85.0
  const val MATCH_BASE_RADIUS_M = 30.0
  const val MATCH_MAX_RADIUS_M = 150.0
  const val OFFROAD_WALKING_MAX_RADIUS_M = 95.0
  const val UNCERTAIN_CONFIDENCE_DELTA = 0.14

  const val DEFAULT_FALLBACK_HEADING_DEG = 0.0

  data class SeedTestSpotSpec(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val headingDeg: Double,
  )

  /**
   * 9 worldwide debug test spots seeded once on fresh Debug installs so developers can jump to any
   * region (all remain user-editable/deletable). Live GPS mode never reads or falls back to these.
   */
  val WORLDWIDE_SEED_TEST_SPOTS =
    listOf(
      SeedTestSpotSpec("seed_cairo", "Cairo", 30.0444, 31.2357, 0.0),
      SeedTestSpotSpec("seed_london", "London", 51.5027, -0.1527, 45.0),
      SeedTestSpotSpec("seed_new_york", "New York", 40.7580, -73.9855, 29.0),
      SeedTestSpotSpec("seed_tokyo", "Tokyo", 35.6595, 139.7004, 0.0),
      SeedTestSpotSpec("seed_paris", "Paris", 48.8738, 2.2950, 120.0),
      SeedTestSpotSpec("seed_sydney", "Sydney", -33.8523, 151.2108, 180.0),
      SeedTestSpotSpec("seed_sao_paulo", "São Paulo", -23.5614, -46.6559, 315.0),
      SeedTestSpotSpec("seed_los_angeles", "Los Angeles", 34.0522, -118.2437, 90.0),
      SeedTestSpotSpec("seed_dubai", "Dubai", 25.1972, 55.2744, 35.0),
    )
}
