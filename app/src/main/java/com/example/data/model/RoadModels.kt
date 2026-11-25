package com.example.data.model

import androidx.compose.runtime.Immutable
import java.util.Locale

enum class TravelMode {
  DRIVING,
  WALKING,
}

enum class ThemePreference {
  AUTO,
  LIGHT,
  DARK,
}

enum class PanelStyle {
  ALWAYS_DARK,
  FOLLOW_MAP,
}

enum class UnitPreference {
  AUTO,
  METRIC,
  IMPERIAL,
}

enum class UnitSystem {
  METRIC,
  IMPERIAL,
}

enum class DeveloperOrientation {
  AUTO,
  LANDSCAPE,
  PORTRAIT,
}

enum class LandscapePanelSide {
  LEFT,
  RIGHT,
}

@Immutable
data class TestSpot(
  val id: String,
  val name: String,
  val lat: Double,
  val lon: Double,
  val headingDeg: Double,
  val travelMode: TravelMode,
  val createdAtMs: Long,
)

@Immutable
data class GeoPoint(
  val lat: Double,
  val lon: Double,
)

@Immutable
data class LocalPoint(
  val xMeters: Double, // +Right, -Left relative to heading
  val yMeters: Double, // +Ahead, -Behind relative to heading
)

/**
 * Strictly filtered OSM tags allowed by specification, plus generic worldwide multilingual name tags.
 */
@Immutable
data class OsmTags(
  val name: String? = null,
  val nameAr: String? = null,
  val nameEn: String? = null,
  val nameLocalized: String? = null,
  val nameNonLatin: String? = null,
  val nameLatin: String? = null,
  val ref: String? = null,
  val destination: String? = null,
  val destinationRef: String? = null,
  val oneway: String? = null,
  val junction: String? = null,
  val highway: String? = null,
  val service: String? = null,
  val bridge: String? = null,
  val tunnel: String? = null,
  val layer: String? = null,
  val lanes: String? = null,
  val footway: String? = null,
  val crossing: String? = null,
  val toll: String? = null,
  val access: String? = null,
  val construction: String? = null,
  val barrier: String? = null,
  val noexit: String? = null,
  val military: String? = null,
) {
  fun hasAnyTag(): Boolean =
    name != null ||
      nameAr != null ||
      nameEn != null ||
      nameLocalized != null ||
      nameNonLatin != null ||
      nameLatin != null ||
      ref != null ||
      destination != null ||
      destinationRef != null ||
      oneway != null ||
      junction != null ||
      highway != null ||
      service != null ||
      bridge != null ||
      tunnel != null ||
      layer != null ||
      lanes != null ||
      footway != null ||
      crossing != null ||
      toll != null ||
      access != null ||
      construction != null ||
      barrier != null ||
      noexit != null ||
      military != null

  /**
   * Per OSM tagging conventions: motorway, motorway_link, and junction=roundabout|circular
   * imply oneway=yes unless oneway=no|false|0 is explicitly tagged.
   */
  fun effectiveOneway(): String? {
    val explicit = oneway?.lowercase()?.trim()
    if (explicit == "no" || explicit == "false" || explicit == "0") return "no"
    if (explicit == "yes" || explicit == "true" || explicit == "1") return "yes"
    if (explicit == "-1" || explicit == "reverse") return "-1"
    val hw = highway?.lowercase()?.trim()
    val junc = junction?.lowercase()?.trim()
    if (hw == "motorway" || hw == "motorway_link" || junc == "roundabout" || junc == "circular") {
      return "yes"
    }
    return null
  }

  fun toDisplayMap(): Map<String, String> = buildMap {
    name?.let { put("name", it) }
    nameAr?.let { put("name:ar", it) }
    nameEn?.let { put("name:en", it) }
    nameLocalized?.let { put("name:local", it) }
    nameNonLatin?.let { put("name:nonlatin", it) }
    nameLatin?.let { put("name:latin", it) }
    ref?.let { put("ref", it) }
    destination?.let { put("destination", it) }
    destinationRef?.let { put("destination:ref", it) }
    oneway?.let { put("oneway", it) }
    junction?.let { put("junction", it) }
    highway?.let { put("highway", it) }
    service?.let { put("service", it) }
    bridge?.let { put("bridge", it) }
    tunnel?.let { put("tunnel", it) }
    layer?.let { put("layer", it) }
    lanes?.let { put("lanes", it) }
    footway?.let { put("footway", it) }
    crossing?.let { put("crossing", it) }
    toll?.let { put("toll", it) }
    access?.let { put("access", it) }
    construction?.let { put("construction", it) }
    barrier?.let { put("barrier", it) }
    noexit?.let { put("noexit", it) }
    military?.let { put("military", it) }
  }

  companion object {
    val ALLOWED_KEYS = setOf(
      "name",
      "name:ar",
      "name:en",
      "name:nonlatin",
      "name:latin",
      "ref",
      "destination",
      "destination:ref",
      "oneway",
      "junction",
      "highway",
      "service",
      "bridge",
      "tunnel",
      "layer",
      "lanes",
      "footway",
      "crossing",
      "toll",
      "access",
      "construction",
      "barrier",
      "noexit",
      "military",
    )

    fun isAllowedKey(key: String): Boolean =
      key in ALLOWED_KEYS || (key.startsWith("name:") && key.length in 6..16)

    fun fromMap(
      raw: Map<String, String>,
      deviceLang: String = Locale.getDefault().language,
    ): OsmTags {
      val cleanLang = deviceLang.lowercase(Locale.US).substringBefore('-').substringBefore('_')
      val devLocalized =
        raw["name:$deviceLang"]?.takeIf { it.isNotBlank() }
          ?: raw["name:$cleanLang"]?.takeIf { it.isNotBlank() }
      return OsmTags(
        name = raw["name"]?.takeIf { it.isNotBlank() },
        nameAr = raw["name:ar"]?.takeIf { it.isNotBlank() },
        nameEn = (raw["name:en"] ?: raw["name_en"])?.takeIf { it.isNotBlank() },
        nameLocalized = devLocalized,
        nameNonLatin = raw["name:nonlatin"]?.takeIf { it.isNotBlank() },
        nameLatin = raw["name:latin"]?.takeIf { it.isNotBlank() },
        ref = raw["ref"]?.takeIf { it.isNotBlank() },
        destination = raw["destination"]?.takeIf { it.isNotBlank() },
        destinationRef = raw["destination:ref"]?.takeIf { it.isNotBlank() },
        oneway = raw["oneway"]?.takeIf { it.isNotBlank() },
        junction = raw["junction"]?.takeIf { it.isNotBlank() },
        highway = raw["highway"]?.takeIf { it.isNotBlank() },
        service = raw["service"]?.takeIf { it.isNotBlank() },
        bridge = raw["bridge"]?.takeIf { it.isNotBlank() },
        tunnel = raw["tunnel"]?.takeIf { it.isNotBlank() },
        layer = raw["layer"]?.takeIf { it.isNotBlank() },
        lanes = raw["lanes"]?.takeIf { it.isNotBlank() },
        footway = raw["footway"]?.takeIf { it.isNotBlank() },
        crossing = raw["crossing"]?.takeIf { it.isNotBlank() },
        toll = raw["toll"]?.takeIf { it.isNotBlank() },
        access = raw["access"]?.takeIf { it.isNotBlank() },
        construction = raw["construction"]?.takeIf { it.isNotBlank() },
        barrier = raw["barrier"]?.takeIf { it.isNotBlank() },
        noexit = raw["noexit"]?.takeIf { it.isNotBlank() },
        military = raw["military"]?.takeIf { it.isNotBlank() },
      )
    }
  }
}

@Immutable
data class OsmNode(
  val id: Long,
  val point: GeoPoint,
  val tags: OsmTags = OsmTags(),
)

enum class GraphDataSource {
  TILES,
  OVERPASS,
  MERGED,
  MAP,
}

@Immutable
data class OsmWay(
  val id: Long,
  val nodeIds: List<Long>,
  val points: List<GeoPoint>,
  val tags: OsmTags,
  val source: GraphDataSource = GraphDataSource.OVERPASS,
)

@Immutable
data class LoadedTileBounds(
  val z: Int,
  val x: Int,
  val y: Int,
  val south: Double,
  val west: Double,
  val north: Double,
  val east: Double,
)

data class RoadGraph(
  val nodes: Map<Long, OsmNode>,
  val ways: Map<Long, OsmWay>,
  val nodeToWays: Map<Long, List<Long>>,
  val fetchedAtMs: Long,
  val fromCache: Boolean,
  val source: GraphDataSource = GraphDataSource.OVERPASS,
  val loadedTiles: List<LoadedTileBounds> = emptyList(),
)

enum class BranchStatus {
  OPEN,
  BLOCKED,
  UNVERIFIED,
}

enum class RelativeDirection {
  LEFT,
  RIGHT,
  AHEAD,
}

enum class OffRoadSide {
  AHEAD,
  LEFT,
  RIGHT,
  BEHIND,
}

enum class FeatureType {
  FOUR_WAY,
  MULTI_WAY,
  T_JUNCTION,
  Y_JUNCTION,
  ROUNDABOUT,
  MINI_ROUNDABOUT,
  FORK,
  MERGE,
  ON_RAMP,
  OFF_RAMP,
  BRIDGE,
  TUNNEL,
  OVERPASS_ABOVE,
  UNDERPASS_BELOW,
  U_TURN,
  DEAD_END,
  CUL_DE_SAC,
  SERVICE_ROAD,
  SHARP_CURVE,
  FOOTPATH,
  CROSSWALK,
  FOOTBRIDGE,
  TOLL_PLAZA,
  CHECKPOINT,
  BARRIER,
  ROAD_WORKS,
  STAGGERED_JUNCTION,
  DIVIDED_ROAD,
  MEDIAN_OPENING,
  FRONTAGE_ROAD,
  INTERCHANGE,
  CLOVERLEAF,
  DIAMOND,
  TRUMPET,
  DIRECTIONAL,
}

/**
 * Multilingual road name supporting all global scripts (Latin, Arabic, Hebrew, CJK, Cyrillic, Thai,
 * Devanagari, etc.). Preserves `arabic` & `english` properties for bilingual Arabic/Latin compatibility
 * while carrying `localPrimary` / `secondaryLocal` for worldwide localized & non-Latin display.
 */
@Immutable
data class BilingualName(
  val arabic: String?,
  val english: String?,
  val ref: String?,
  val localPrimary: String? = null,
  val secondaryLocal: String? = null,
) {
  val primaryText: String?
    get() = localPrimary ?: arabic ?: english ?: ref

  val secondaryText: String?
    get() {
      val prim = primaryText ?: return null
      val cand = secondaryLocal ?: if (localPrimary == null && arabic != null) english else null
      return cand?.takeIf { it.isNotBlank() && it != prim }
    }

  val hasAny: Boolean
    get() = localPrimary != null || arabic != null || english != null || secondaryLocal != null || ref != null

  companion object {
    private val ARABIC_REGEX = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF]")
    private val NON_LATIN_REGEX = Regex("[^\\u0000-\\u024F\\s0-9\\p{Punct}]")

    fun containsArabic(text: String?): Boolean = !text.isNullOrBlank() && ARABIC_REGEX.containsMatchIn(text)

    fun containsNonLatin(text: String?): Boolean = !text.isNullOrBlank() && NON_LATIN_REGEX.containsMatchIn(text)

    fun fromOsmTags(
      tags: OsmTags,
      deviceLang: String = Locale.getDefault().language,
    ): BilingualName {
      val cleanLang = deviceLang.lowercase(Locale.US).substringBefore('-').substringBefore('_')
      val explicitAr = tags.nameAr?.takeIf { it.isNotBlank() }
      val genericName = tags.name?.takeIf { it.isNotBlank() }
      val nonLatinTag = tags.nameNonLatin?.takeIf { it.isNotBlank() }
      val localizedTag = tags.nameLocalized?.takeIf { it.isNotBlank() }
      val explicitEn = tags.nameEn?.takeIf { it.isNotBlank() }
      val latinTag = tags.nameLatin?.takeIf { it.isNotBlank() }

      val ar =
        explicitAr
          ?: genericName?.takeIf { containsArabic(it) }
          ?: nonLatinTag?.takeIf { containsArabic(it) }

      val en =
        explicitEn
          ?: latinTag?.takeIf { !containsNonLatin(it) }
          ?: genericName?.takeIf { !containsArabic(it) && !containsNonLatin(it) }

      // If Arabic name is present without a non-Arabic localized override, keep Arabic on top & Latin below
      if (ar != null && (cleanLang == "ar" || localizedTag == null || localizedTag == ar)) {
        return BilingualName(
          arabic = ar,
          english = en ?: latinTag?.takeIf { it != ar },
          ref = tags.ref,
        )
      }

      // Worldwide priority: name:<deviceLang> -> device language fallback -> local name / name:nonlatin -> name:latin
      val pickedPrimary =
        localizedTag
          ?: (if (cleanLang == "en") explicitEn else null)
          ?: genericName
          ?: nonLatinTag
          ?: latinTag
          ?: ar
          ?: explicitEn

      val pickedSecondary =
        when {
          pickedPrimary == null -> null
          ar != null && ar != pickedPrimary -> ar
          genericName != null && genericName != pickedPrimary -> genericName
          nonLatinTag != null && nonLatinTag != pickedPrimary -> nonLatinTag
          latinTag != null && latinTag != pickedPrimary -> latinTag
          explicitEn != null && explicitEn != pickedPrimary -> explicitEn
          else -> null
        }

      val isSinglePureLatin =
        pickedSecondary == null &&
          pickedPrimary != null &&
          !containsNonLatin(pickedPrimary) &&
          ar == null

      return if (isSinglePureLatin) {
        BilingualName(
          arabic = null,
          english = pickedPrimary,
          ref = tags.ref,
        )
      } else {
        BilingualName(
          arabic = ar,
          english = en ?: pickedPrimary?.takeIf { !containsNonLatin(it) } ?: pickedSecondary?.takeIf { !containsNonLatin(it) },
          ref = tags.ref,
          localPrimary = pickedPrimary,
          secondaryLocal = pickedSecondary,
        )
      }
    }

    fun destinationFromOsmTags(tags: OsmTags): String? {
      val dest = tags.destination
      val destRef = tags.destinationRef
      return when {
        dest != null && destRef != null && !dest.contains(destRef) -> "$destRef · $dest"
        dest != null -> dest
        destRef != null -> destRef
        else -> null
      }
    }
  }
}

@Immutable
data class RoadBranch(
  val badgeId: String,
  val wayId: Long,
  val name: BilingualName,
  val leadsTo: String?,
  val distanceMeters: Double,
  val status: BranchStatus,
  val relativeDirection: RelativeDirection,
  val bearingDeltaDeg: Double,
  val worldPoints: List<GeoPoint>,
  val localPoints: List<LocalPoint>,
  val osmTags: OsmTags,
)

@Immutable
data class RoadFeature(
  val index: Int,
  val type: FeatureType,
  val distanceMeters: Double,
  val anchor: GeoPoint,
  val anchorNodeId: Long?,
  val incomingLocalPoints: List<LocalPoint>,
  val incomingWorldPoints: List<GeoPoint>,
  val outgoingMainLocalPoints: List<LocalPoint>,
  val outgoingMainWorldPoints: List<GeoPoint>,
  val branches: List<RoadBranch>,
  val osmTags: OsmTags,
  val curveAngleDeg: Double? = null,
  val curveRadiusMeters: Double? = null,
  val isBridge: Boolean = false,
  val isTunnel: Boolean = false,
  val isFootbridge: Boolean = false,
  val isCrosswalk: Boolean = false,
  val source: GraphDataSource = GraphDataSource.OVERPASS,
  val ruleId: String = "",
  val confidence: Double = 1.0,
  val crossingLocalPoints: List<LocalPoint> = emptyList(),
  val staggerOffsetMeters: Double? = null,
  val dualCarriagewayOffsetMeters: Double? = null,
  val frontageSide: RelativeDirection? = null,
  val hiddenSubFeatures: List<RoadFeature> = emptyList(),
) {
  fun isInterchangeGroup(): Boolean =
    type == FeatureType.INTERCHANGE ||
      type == FeatureType.CLOVERLEAF ||
      type == FeatureType.DIAMOND ||
      type == FeatureType.TRUMPET ||
      type == FeatureType.DIRECTIONAL

  /**
   * Uses specific composite label only when confidence >= 0.8; otherwise falls back to generic label.
   */
  fun effectiveDisplayType(): FeatureType =
    when {
      isInterchangeGroup() && type != FeatureType.INTERCHANGE && confidence < 0.8 -> FeatureType.INTERCHANGE
      else -> type
    }
}

@Immutable
data class DividedRoadInfo(
  val isDivided: Boolean,
  val separationMeters: Double,
  val partnerSideOffsetMeters: Double,
  val partnerWayId: Long,
  val ruleId: String,
  val confidence: Double,
)

@Immutable
data class AheadTimelineEntry(
  val featureIndex: Int?,
  val type: FeatureType,
  val distanceMeters: Double,
  val title: BilingualName,
  val branchSummary: String?,
  val status: BranchStatus?,
  val anchor: GeoPoint,
  val isLandmark: Boolean = false,
  val primaryWayId: Long? = null,
  val branchWayIds: List<Long> = emptyList(),
)

@Immutable
data class LandmarkAheadItem(
  val id: Long,
  val name: BilingualName,
  val category: PoiCategory,
  val point: GeoPoint,
  val distanceMeters: Double,
  val side: RelativeDirection,
)

@Immutable
data class AheadAnalysisResult(
  val features: List<RoadFeature>,
  val dividedRoad: DividedRoadInfo? = null,
  val timelineEntries: List<AheadTimelineEntry> = emptyList(),
  val corridorWorldPoints: List<GeoPoint> = emptyList(),
  val loadedCorridorMeters: Double = 0.0,
  val hiddenMinorCount: Int = 0,
  val landmarksAhead: List<LandmarkAheadItem> = emptyList(),
)

enum class SearchScope {
  ON_MY_ROAD,
  EVERYWHERE,
}

enum class FoundRoadRelation {
  AHEAD,
  BEHIND,
  PARALLEL,
}

enum class EntryLegalityStatus {
  OPEN,
  INDIRECT,
  BLOCKED,
  UNVERIFIED,
}

@Immutable
data class FoundRoadMatch(
  val id: String,
  val name: BilingualName,
  val wayId: Long?,
  val poiId: Long?,
  val isStreet: Boolean,
  val placeType: String,
  val targetPoint: GeoPoint,
  val entryPoint: GeoPoint,
  val polyline: List<GeoPoint>,
  val relation: FoundRoadRelation,
  val alongRoadDistanceMeters: Double?,
  val distanceKind: DistanceKind,
  val entryStatus: EntryLegalityStatus,
  val entryNote: String? = null,
  val matchedFeatureIndex: Int? = null,
)

@Immutable
data class FeatureCensusEntry(
  val type: FeatureType,
  val count: Int,
  val nearestDistanceMeters: Double,
  val jumpPoint: GeoPoint,
  val jumpHeadingDeg: Double,
)

@Immutable
data class RoadCandidate(
  val way: OsmWay,
  val name: BilingualName,
  val confidence: Double,
  val lateralOffsetMeters: Double,
  val bearingDiffDeg: Double,
  val snappedPoint: GeoPoint,
  val segmentIndex: Int,
  val forwardAlongWay: Boolean,
)

@Immutable
data class OffRoadCandidate(
  val badgeId: String, // R1, R2, R3
  val candidate: RoadCandidate,
  val side: OffRoadSide,
  val distanceMeters: Double,
  val closestPoint: GeoPoint,
  val bearingFromUserDeg: Double,
  val bearingDeltaDeg: Double,
  val stubWorldPoints: List<GeoPoint>,
  val stubLocalPoints: List<LocalPoint>,
  val forwardBearingDeg: Double,
  val reverseBearingDeg: Double,
)

@Immutable
data class OffRoadDirectionGroup(
  val forwardAlongWay: Boolean,
  val travelBearingDeg: Double,
  val compassCardinal: String,
  val destinationHint: String?,
  val features: List<RoadFeature>,
)

@Immutable
data class OffRoadState(
  val isOffRoad: Boolean = false,
  val matchRadiusMeters: Double = 30.0,
  val searchRadiusMeters: Double = 30.0,
  val reason: String? = null,
  val candidates: List<OffRoadCandidate> = emptyList(),
  val selectedCandidate: OffRoadCandidate? = null,
  val needsDirectionChoice: Boolean = false,
  val directionGroups: List<OffRoadDirectionGroup> = emptyList(),
  val chosenForwardAlongWay: Boolean? = null,
)

@Immutable
data class MatchDiagnostics(
  val graphVersionRead: Long = 0L,
  val sourceRead: String = "none",
  val indexEdgeCount: Int = 0,
  val nearestEdgeDistanceMeters: Double? = null,
  val nearestEdgeWayId: Long? = null,
  val nearestEdgeClass: String? = null,
  val candidatesExamined: Int = 0,
  val droppedByRadius: Int = 0,
  val droppedByClass: Int = 0,
  val droppedByAccess: Int = 0,
  val droppedByHeading: Int = 0,
  val droppedByOneway: Int = 0,
  val droppedByMissingName: Int = 0,
  val droppedByStatus: Int = 0,
  val matchDurationMs: Long = 0L,
) {
  fun toFilterSummary(): String =
    "drop[r=$droppedByRadius cls=$droppedByClass acc=$droppedByAccess hdg=$droppedByHeading 1w=$droppedByOneway name=$droppedByMissingName st=$droppedByStatus]"
}

@Immutable
data class MatchResult(
  val primary: RoadCandidate?,
  val secondary: RoadCandidate?,
  val isUncertain: Boolean,
  val matchRadiusMeters: Double = 30.0,
  val offRoadSearchRadiusMeters: Double = 30.0,
  val offRoadCandidates: List<OffRoadCandidate> = emptyList(),
  val diagnostics: MatchDiagnostics = MatchDiagnostics(),
)

enum class DistanceKind {
  ROAD_SHORTEST,
  FASTEST_ROUTE,
  UNAVAILABLE,
  LOADING,
}

@Immutable
data class SearchPlaceResult(
  val placeId: Long,
  val primaryName: String,
  val secondaryName: String?,
  val point: GeoPoint,
  val distanceMeters: Double?,
  val distanceKind: DistanceKind,
  val nameAr: String? = null,
  val nameEn: String? = null,
  val placeType: String = "place",
  val isStreet: Boolean = false,
  val streetLines: List<List<GeoPoint>> = emptyList(),
)

enum class PinSortOrder {
  NEAREST,
  NEWEST,
}

@Immutable
data class SavedPin(
  val id: Long,
  val placeId: Long,
  val nameAr: String?,
  val nameEn: String?,
  val primaryName: String,
  val secondaryName: String?,
  val placeType: String,
  val point: GeoPoint,
  val isStreet: Boolean,
  val streetLines: List<List<GeoPoint>> = emptyList(),
  val createdAtMs: Long,
  val distanceMeters: Double? = null,
  val distanceKind: DistanceKind = DistanceKind.LOADING,
) {
  fun toBilingualName(): BilingualName =
    BilingualName(
      arabic = nameAr?.takeIf { it.isNotBlank() },
      english = nameEn?.takeIf { it.isNotBlank() } ?: primaryName.takeIf { it.isNotBlank() && it != nameAr },
      ref = null,
    )

  fun mapLabelText(): String {
    val ar = nameAr?.takeIf { it.isNotBlank() }
    val en = nameEn?.takeIf { it.isNotBlank() }
    return when {
      ar != null && en != null && ar != en -> "$ar\n$en"
      ar != null -> ar
      en != null -> en
      else -> primaryName
    }
  }
}

@Immutable
data class LocationSnapshot(
  val point: GeoPoint,
  val headingDeg: Double,
  val speedMps: Double,
  val accuracyMeters: Float?,
  val isMock: Boolean,
  val timestampMs: Long,
  val hasReliableHeading: Boolean = true,
)

@Immutable
data class OverpassRequestLog(
  val endpoint: String,
  val durationMs: Long,
  val cellsFetched: Int,
  val cellsCached: Int,
  val cellsPrefetched: Int = 0,
  val failures: List<String> = emptyList(),
  val succeeded: Boolean = true,
  val timestampMs: Long = System.currentTimeMillis(),
  val source: String = "overpass",
  val method: String = "POST",
  val protocol: String = "http/1.1",
  val requestHeaders: Map<String, String> = emptyMap(),
  val queryText: String = "",
  val encodedLength: Int = 0,
  val status: String = if (succeeded) "200" else "ERR",
  val responseSizeKb: Double = 0.0,
  val responseSnippet: String? = null,
  val hedged: Boolean = false,
  val cancelled: Boolean = false,
  val cooldownSummary: String = "",
  val dnsMs: Long? = null,
  val connectMs: Long? = null,
  val tlsMs: Long? = null,
  val firstByteMs: Long? = null,
  val responseHeadersSummary: String? = null,
)

@Immutable
data class OverpassEndpointTestResult(
  val endpoint: String,
  val tinyStatus: String,
  val tinyDurationMs: Long,
  val roadStatus: String,
  val roadDurationMs: Long,
)

@Immutable
data class TileRoadHint(
  val name: BilingualName,
  val roadClass: String? = null,
)

@Immutable
data class TileUrlResolutionInfo(
  val ok: Boolean = false,
  val howResolved: String = "pending",
  val template: String = "",
  val scheme: String = "xyz",
  val fetchedAtMs: Long = 0L,
  val reason: String? = null,
)

@Immutable
data class TileFetchStageInfo(
  val ok: Boolean = false,
  val centerTileKey: String = "-",
  val requestedCount: Int = 0,
  val okCount: Int = 0,
  val cacheHitCount: Int = 0,
  val netHitCount: Int = 0,
  val totalBytes: Long = 0L,
  val lastHttpStatus: String = "-",
  val contentEncoding: String = "-",
  val durationMs: Long = 0L,
  val reason: String? = null,
)

@Immutable
data class MvtDecodeStageInfo(
  val ok: Boolean = false,
  val tileCount: Int = 0,
  val layersSummary: String = "-",
  val extent: Int = 0,
  val sampleCoords: String = "-",
  val durationMs: Long = 0L,
  val errorReason: String? = null,
  val timingBreakdown: String = "",
)

@Immutable
data class NearbyTileLabelDiagnostic(
  val name: String? = null,
  val nameAr: String? = null,
  val nameEn: String? = null,
  val ref: String? = null,
  val labelClass: String? = null,
  val distanceMeters: Double = 0.0,
  val rejectReason: String? = null,
) {
  fun toReportString(): String {
    val labelText = name ?: nameEn ?: nameAr ?: ref ?: "unnamed"
    return String.format(
      Locale.US,
      "%s [ar=%s en=%s ref=%s cls=%s] @%.1fm%s",
      labelText.take(24),
      nameAr?.take(18) ?: "-",
      nameEn?.take(18) ?: "-",
      ref ?: "-",
      labelClass ?: "-",
      distanceMeters,
      rejectReason?.let { " ($it)" } ?: " (ok)",
    )
  }
}

@Immutable
data class OverpassEndpointStats(
  val host: String,
  val lastStatus: String,
  val durationMs: Long,
  val cellMeters: Int,
  val edgesAdded: Int,
)

@Immutable
data class LoaderStats60s(
  val started60s: Int = 0,
  val completed60s: Int = 0,
  val cancelled60s: Int = 0,
)

@Immutable
data class PipelineStageLine(
  val stageId: String,
  val label: String,
  val ok: Boolean,
  val summary: String,
  val elapsedMs: Long? = null,
  val reason: String? = null,
  val isEmptyArea: Boolean = false,
) {
  fun toReportLine(): String {
    val status = if (isEmptyArea) "EMPTY" else if (ok) "OK" else "FAIL"
    val msPart = elapsedMs?.let { " ${it}ms" } ?: ""
    val reasonPart = reason?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
    return "$stageId) $label: $status · $summary$msPart$reasonPart"
  }
}

@Immutable
data class PipelineDiagnostics(
  val tileUrl: TileUrlResolutionInfo = TileUrlResolutionInfo(),
  val tileFetch: TileFetchStageInfo = TileFetchStageInfo(),
  val mvtDecode: MvtDecodeStageInfo = MvtDecodeStageInfo(),
  val stages: List<PipelineStageLine> = emptyList(),
  val firstFailingStageName: String? = null,
  val isInconsistentMatch: Boolean = false,
  val inconsistentBannerText: String? = null,
  val nearestEdgeSummary: String = "none",
  val nearbyTileLabels: List<NearbyTileLabelDiagnostic> = emptyList(),
  val graphVersion: Long = 0L,
  val rollingWindowSummary: String = "",
  val horizonStatsSummary: String = "",
  val idCollisions: Int = 0,
  val danglingNodeRefs: Int = 0,
)

enum class DebugEdgeCategory {
  TILE,
  OVERPASS,
  MATCHED,
}

@Immutable
data class DebugGraphEdge(
  val wayId: Long,
  val points: List<GeoPoint>,
  val category: DebugEdgeCategory,
)

@Immutable
data class LiveMapVectorFeature(
  val layerName: String,
  val tags: Map<String, String>,
  val geometries: List<List<GeoPoint>>,
)

enum class PoiCategory {
  FUEL,
  PARKING,
  HOSPITAL,
  POLICE,
  TRANSIT,
  RESTAURANT,
  CAFE,
  SHOP,
  LANDMARK,
  OTHER,
}

@Immutable
data class PoiItem(
  val id: Long,
  val name: BilingualName,
  val category: PoiCategory,
  val point: GeoPoint,
  val osmTags: Map<String, String> = emptyMap(),
  val source: GraphDataSource = GraphDataSource.TILES,
)

@Immutable
data class TileNameLabelRecord(
  val points: List<GeoPoint>,
  val labelClass: String?,
  val tagMap: Map<String, String>,
)

@Immutable
data class TileDecodeTiming(
  val tileKey: String,
  val gunzipMs: Long,
  val decodeMs: Long,
  val totalMs: Long,
)

data class DecodedTileArtifact(
  val tileKey: String,
  val z: Int,
  val x: Int,
  val y: Int,
  val bounds: LoadedTileBounds,
  val graph: RoadGraph,
  val labels: List<TileNameLabelRecord>,
  val pois: List<PoiItem>,
  val fetchedAtMs: Long,
  val isEmptyDecoded: Boolean,
  val extent: Int = 4096,
  val layerCounts: Map<String, Int> = emptyMap(),
  val sampleCoords: List<String> = emptyList(),
  val timing: TileDecodeTiming? = null,
)
