package com.example.data.remote

import com.example.config.AppConfig
import com.example.data.interfaces.MatrixDistanceResult
import com.example.data.interfaces.RouteResult
import com.example.data.interfaces.RoutingService
import com.example.data.model.DistanceKind
import com.example.data.model.GeoPoint
import com.example.data.model.TravelMode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ValhallaRoutingService(
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(AppConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
      .readTimeout(AppConfig.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
      .addInterceptor(DebugNetworkSimulator.interceptor)
      .build(),
) : RoutingService {

  override suspend fun computeDistanceMatrix(
    origin: GeoPoint,
    destinations: List<GeoPoint>,
    mode: TravelMode,
  ): List<MatrixDistanceResult> =
    withContext(Dispatchers.IO) {
      if (destinations.isEmpty()) return@withContext emptyList()
      val results =
        Array(destinations.size) {
          MatrixDistanceResult(distanceMeters = null, kind = DistanceKind.UNAVAILABLE)
        }

      // 1) One matrix/table request with shortest costing
      val shortestDistances = queryValhallaMatrix(origin, destinations, mode, useShortest = true)
      val missingIndices = mutableListOf<Int>()
      for (i in destinations.indices) {
        val distMeters = shortestDistances?.getOrNull(i)
        if (distMeters != null) {
          results[i] = MatrixDistanceResult(distanceMeters = distMeters, kind = DistanceKind.ROAD_SHORTEST)
        } else {
          missingIndices += i
        }
      }

      // 2) Fallback matrix request with fastest route only for targets where shortest failed
      if (missingIndices.isNotEmpty()) {
        val missingPoints = missingIndices.map { destinations[it] }
        val fastestDistances = queryValhallaMatrix(origin, missingPoints, mode, useShortest = false)
        if (fastestDistances != null) {
          missingIndices.forEachIndexed { subIdx, origIdx ->
            val distMeters = fastestDistances.getOrNull(subIdx)
            if (distMeters != null) {
              results[origIdx] =
                MatrixDistanceResult(distanceMeters = distMeters, kind = DistanceKind.FASTEST_ROUTE)
            }
          }
        }
      }

      results.toList()
    }

  private fun queryValhallaMatrix(
    origin: GeoPoint,
    destinations: List<GeoPoint>,
    mode: TravelMode,
    useShortest: Boolean,
  ): List<Double?>? {
    val costing = if (mode == TravelMode.DRIVING) "auto" else "pedestrian"
    val payload =
      JSONObject().apply {
        put(
          "sources",
          JSONArray().put(JSONObject().put("lat", origin.lat).put("lon", origin.lon)),
        )
        put(
          "targets",
          JSONArray().apply {
            destinations.forEach { d ->
              put(JSONObject().put("lat", d.lat).put("lon", d.lon))
            }
          },
        )
        put("costing", costing)
        if (useShortest) {
          put(
            "costing_options",
            JSONObject().put(costing, JSONObject().put("shortest", true)),
          )
        }
        put("units", "kilometers")
      }

    return try {
      val mediaType = "application/json; charset=utf-8".toMediaType()
      val req =
        Request.Builder()
          .url("${AppConfig.VALHALLA_BASE_URL}sources_to_targets")
          .header("User-Agent", AppConfig.USER_AGENT)
          .post(payload.toString().toRequestBody(mediaType))
          .build()

      client.newCall(req).execute().use { response ->
        if (!response.isSuccessful) return null
        val text = response.body?.string() ?: return null
        val json = JSONObject(text)
        val matrix = json.optJSONArray("sources_to_targets") ?: return null
        val row = matrix.optJSONArray(0) ?: return null
        List(destinations.size) { idx ->
          val cell = row.optJSONObject(idx) ?: return@List null
          if (cell.isNull("distance")) null
          else {
            val km = cell.optDouble("distance", Double.NaN)
            if (!km.isNaN() && km >= 0.0) km * 1000.0 else null
          }
        }
      }
    } catch (_: Throwable) {
      null
    }
  }

  override suspend fun computeRoute(
    origin: GeoPoint,
    destination: GeoPoint,
    mode: TravelMode,
    includeGeometry: Boolean,
  ): RouteResult =
    withContext(Dispatchers.IO) {
      // 1) Try shortest-distance costing first
      val shortest = queryValhalla(origin, destination, mode, useShortest = true, includeGeometry)
      if (shortest != null) {
        return@withContext shortest.copy(isShortest = true)
      }
      // 2) Fallback to fastest route if shortest costing is unavailable
      val fastest = queryValhalla(origin, destination, mode, useShortest = false, includeGeometry)
      if (fastest != null) {
        return@withContext fastest.copy(isShortest = false)
      }
      // 3) No route => unavailable (never return straight-line distance)
      RouteResult(distanceMeters = 0.0, polyline = emptyList(), isShortest = false, isAvailable = false)
    }

  private fun queryValhalla(
    origin: GeoPoint,
    destination: GeoPoint,
    mode: TravelMode,
    useShortest: Boolean,
    includeGeometry: Boolean,
  ): RouteResult? {
    val costing = if (mode == TravelMode.DRIVING) "auto" else "pedestrian"
    val payload =
      JSONObject().apply {
        put(
          "locations",
          JSONArray().apply {
            put(JSONObject().put("lat", origin.lat).put("lon", origin.lon))
            put(JSONObject().put("lat", destination.lat).put("lon", destination.lon))
          },
        )
        put("costing", costing)
        if (useShortest) {
          put(
            "costing_options",
            JSONObject().put(
              costing,
              JSONObject().put("shortest", true),
            ),
          )
        }
        put("directions_options", JSONObject().put("units", "kilometers"))
      }

    return try {
      val mediaType = "application/json; charset=utf-8".toMediaType()
      val req =
        Request.Builder()
          .url("${AppConfig.VALHALLA_BASE_URL}route")
          .header("User-Agent", AppConfig.USER_AGENT)
          .post(payload.toString().toRequestBody(mediaType))
          .build()

      client.newCall(req).execute().use { response ->
        if (!response.isSuccessful) return null
        val text = response.body?.string() ?: return null
        val json = JSONObject(text)
        val trip = json.optJSONObject("trip") ?: return null
        val summary = trip.optJSONObject("summary") ?: return null
        val lengthKm = summary.optDouble("length", Double.NaN)
        if (lengthKm.isNaN() || lengthKm < 0.0) return null

        val poly =
          if (includeGeometry) {
            val legs = trip.optJSONArray("legs")
            if (legs != null && legs.length() > 0) {
              val shape = legs.optJSONObject(0)?.optString("shape").orEmpty()
              if (shape.isNotBlank()) decodePolyline6(shape) else emptyList()
            } else {
              emptyList()
            }
          } else {
            emptyList()
          }

        RouteResult(
          distanceMeters = lengthKm * 1000.0,
          polyline = poly,
          isShortest = useShortest,
          isAvailable = true,
        )
      }
    } catch (_: Throwable) {
      null
    }
  }

  companion object {
    fun decodePolyline6(encoded: String): List<GeoPoint> {
      val result = mutableListOf<GeoPoint>()
      var index = 0
      var lat = 0L
      var lon = 0L
      while (index < encoded.length) {
        var b: Int
        var shift = 0
        var res = 0L
        do {
          b = encoded[index++].code - 63
          res = res or ((b and 0x1f).toLong() shl shift)
          shift += 5
        } while (b >= 0x20 && index < encoded.length)
        val dLat = if ((res and 1L) != 0L) (res shr 1).inv() else (res shr 1)
        lat += dLat

        if (index >= encoded.length) break
        shift = 0
        res = 0L
        do {
          b = encoded[index++].code - 63
          res = res or ((b and 0x1f).toLong() shl shift)
          shift += 5
        } while (b >= 0x20 && index < encoded.length)
        val dLon = if ((res and 1L) != 0L) (res shr 1).inv() else (res shr 1)
        lon += dLon

        result += GeoPoint(lat / 1_000_000.0, lon / 1_000_000.0)
      }
      return result
    }
  }
}
