package com.example.data.remote

import com.example.config.AppConfig
import com.example.data.interfaces.SearchService
import com.example.data.model.BilingualName
import com.example.data.model.DistanceKind
import com.example.data.model.GeoPoint
import com.example.data.model.OsmTags
import com.example.data.model.SearchPlaceResult
import com.example.domain.GeoMath
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class NominatimSearchService(
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(AppConfig.CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
      .readTimeout(AppConfig.READ_TIMEOUT_SEC, TimeUnit.SECONDS)
      .addInterceptor(DebugNetworkSimulator.interceptor)
      .build(),
) : SearchService {

  override suspend fun searchPlaces(
    query: String,
    near: GeoPoint,
    limit: Int,
  ): Result<List<SearchPlaceResult>> =
    withContext(Dispatchers.IO) {
      val trimmed = query.trim()
      if (trimmed.isEmpty()) return@withContext Result.success(emptyList())

      try {
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        // Bias viewbox (~0.35 deg around current position) without strictly bounding worldwide search
        val vb =
          GeoMath.splitBboxAtAntimeridian(
            near.lat - 0.35,
            near.lon - 0.35,
            near.lat + 0.35,
            near.lon + 0.35,
          ).first()
        val left = String.format(Locale.US, "%.5f", vb.west)
        val top = String.format(Locale.US, "%.5f", vb.north)
        val right = String.format(Locale.US, "%.5f", vb.east)
        val bottom = String.format(Locale.US, "%.5f", vb.south)
        val acceptLang =
          Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() && it != "und" }?.let { "$it,en;q=0.7" }
            ?: "en"
        val url =
          "${AppConfig.NOMINATIM_BASE_URL}search?q=$encoded&format=jsonv2&addressdetails=1&namedetails=1&polygon_geojson=1&viewbox=$left,$top,$right,$bottom&limit=$limit"

        val request =
          Request.Builder()
            .url(url)
            .header("User-Agent", AppConfig.USER_AGENT)
            .header("Accept-Language", acceptLang)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            return@withContext Result.failure(IOException("Nominatim HTTP ${response.code}"))
          }
          val body = response.body?.string() ?: "[]"
          Result.success(parseNominatimJson(body, trimmed))
        }
      } catch (t: Throwable) {
        Result.failure(t)
      }
    }

  companion object {
    private val ARABIC_REGEX = Regex("[\\u0600-\\u06FF]")

    fun parseNominatimJson(body: String, fallbackQuery: String): List<SearchPlaceResult> {
      val arr = JSONArray(body)
      val results = ArrayList<SearchPlaceResult>(arr.length())
      for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val lat = obj.optString("lat").toDoubleOrNull() ?: continue
        val rawLon = obj.optString("lon").toDoubleOrNull() ?: continue
        val lon = GeoMath.normalizeLon(rawLon)
        val placeId = obj.optLong("place_id", i.toLong())

        val nameDetails = obj.optJSONObject("namedetails")
        val tagMap = LinkedHashMap<String, String>()
        if (nameDetails != null) {
          val keys = nameDetails.keys()
          while (keys.hasNext()) {
            val k = keys.next()
            val v = nameDetails.optString(k).trim()
            if (v.isNotEmpty() && OsmTags.isAllowedKey(k)) {
              tagMap[k] = v
            }
          }
        }
        val rawName = obj.optString("name").trim().takeIf { it.isNotEmpty() }
        if (rawName != null && !tagMap.containsKey("name")) {
          tagMap["name"] = rawName
        }
        val bilingual = BilingualName.fromOsmTags(OsmTags.fromMap(tagMap))
        val nameAr = bilingual.arabic
        val nameEn = bilingual.english

        val display = obj.optString("display_name").takeIf { it.isNotBlank() }
        val primary =
          bilingual.primaryText ?: rawName ?: display?.substringBefore(",")?.trim() ?: fallbackQuery
        val secondary =
          when {
            bilingual.secondaryText != null && bilingual.secondaryText != primary -> bilingual.secondaryText
            display != null && display != primary -> display
            else -> null
          }

        val osmClass = obj.optString("class").ifBlank { obj.optString("category") }
        val osmType = obj.optString("type")
        val isStreet = osmClass.equals("highway", ignoreCase = true) ||
          osmType.equals("street", ignoreCase = true) ||
          osmType.equals("road", ignoreCase = true)

        val placeTypeLabel = formatPlaceType(osmClass, osmType)
        val streetLines =
          if (isStreet) parseGeoJsonLines(obj.optJSONObject("geojson")) else emptyList()

        results +=
          SearchPlaceResult(
            placeId = placeId,
            primaryName = primary,
            secondaryName = secondary,
            point = GeoPoint(lat, lon),
            distanceMeters = null,
            distanceKind = DistanceKind.LOADING,
            nameAr = nameAr,
            nameEn = nameEn,
            placeType = placeTypeLabel,
            isStreet = isStreet,
            streetLines = streetLines,
          )
      }
      return results
    }

    private fun parseGeoJsonLines(geojson: JSONObject?): List<List<GeoPoint>> {
      if (geojson == null) return emptyList()
      val type = geojson.optString("type")
      val coords = geojson.optJSONArray("coordinates") ?: return emptyList()
      return when (type) {
        "LineString" -> {
          val line = parseCoordRing(coords)
          if (line.size >= 2) listOf(line) else emptyList()
        }
        "MultiLineString" -> {
          val out = ArrayList<List<GeoPoint>>(coords.length())
          for (i in 0 until coords.length()) {
            val sub = coords.optJSONArray(i) ?: continue
            val line = parseCoordRing(sub)
            if (line.size >= 2) out += line
          }
          out
        }
        else -> emptyList()
      }
    }

    private fun parseCoordRing(arr: JSONArray): List<GeoPoint> {
      val pts = ArrayList<GeoPoint>(arr.length())
      for (i in 0 until arr.length()) {
        val pair = arr.optJSONArray(i) ?: continue
        if (pair.length() < 2) continue
        val lon = pair.optDouble(0, Double.NaN)
        val lat = pair.optDouble(1, Double.NaN)
        if (!lat.isNaN() && !lon.isNaN()) {
          pts += GeoPoint(lat, lon)
        }
      }
      return pts
    }

    private fun formatPlaceType(osmClass: String, osmType: String): String {
      if (osmClass.equals("highway", ignoreCase = true)) {
        return when (osmType.lowercase(Locale.US)) {
          "motorway", "trunk", "primary", "secondary", "tertiary" ->
            "${osmType.replaceFirstChar { it.uppercase(Locale.US) }} road"
          else -> "Street"
        }
      }
      val raw = osmType.ifBlank { osmClass }.ifBlank { "place" }
      return raw.replace('_', ' ').replaceFirstChar { it.uppercase(Locale.US) }
    }
  }
}
