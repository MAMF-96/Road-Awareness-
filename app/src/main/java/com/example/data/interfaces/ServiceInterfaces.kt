package com.example.data.interfaces

import com.example.data.model.DecodedTileArtifact
import com.example.data.model.DistanceKind
import com.example.data.model.GeoPoint
import com.example.data.model.OverpassRequestLog
import com.example.data.model.PoiItem
import com.example.data.model.RoadGraph
import com.example.data.model.SearchPlaceResult
import com.example.data.model.TravelMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class CorridorGraphUpdate(
  val graph: RoadGraph,
  val isPartialOrStale: Boolean,
  val isFromNetwork: Boolean,
  val requestLog: OverpassRequestLog?,
  val tileKey: String? = null,
  val decodedArtifacts: List<DecodedTileArtifact> = emptyList(),
  val pois: List<PoiItem> = emptyList(),
  val emptyTileKeys: Set<String> = emptySet(),
)

interface MapDataSource {
  val requestLogs: StateFlow<List<OverpassRequestLog>>

  fun fetchCorridorStream(
    center: GeoPoint,
    headingDeg: Double,
    mode: TravelMode,
    forceRefresh: Boolean = false,
  ): Flow<Result<CorridorGraphUpdate>>

  suspend fun fetchCorridorGraph(
    center: GeoPoint,
    headingDeg: Double,
    mode: TravelMode,
    radiusMeters: Double,
    forceRefresh: Boolean = false,
  ): Result<RoadGraph>

  suspend fun getCachedCellCount(): Int

  suspend fun clearCache()
}

interface SearchService {
  suspend fun searchPlaces(
    query: String,
    near: GeoPoint,
    limit: Int = 10,
  ): Result<List<SearchPlaceResult>>
}

data class RouteResult(
  val distanceMeters: Double,
  val polyline: List<GeoPoint>,
  val isShortest: Boolean,
  val isAvailable: Boolean,
)

data class MatrixDistanceResult(
  val distanceMeters: Double?,
  val kind: DistanceKind,
)

interface RoutingService {
  suspend fun computeRoute(
    origin: GeoPoint,
    destination: GeoPoint,
    mode: TravelMode,
    includeGeometry: Boolean,
  ): RouteResult

  suspend fun computeDistanceMatrix(
    origin: GeoPoint,
    destinations: List<GeoPoint>,
    mode: TravelMode,
  ): List<MatrixDistanceResult>
}
