package com.example.data.local

import com.example.data.model.DistanceKind
import com.example.data.model.GeoPoint
import com.example.data.model.SavedPin
import com.example.data.model.SearchPlaceResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

class SavedPinRepository(
  private val dao: SavedPinDao,
  private val maxPins: Int = 30,
) {
  val allPins: Flow<List<SavedPin>> =
    dao.getAllPinsFlow().map { list -> list.map { it.toDomain() } }

  suspend fun getAllPinsSnapshot(): List<SavedPin> =
    dao.getAllPins().map { it.toDomain() }

  suspend fun addOrUpdatePin(place: SearchPlaceResult): SavedPin {
    val now = System.currentTimeMillis()
    val existing = dao.findByPlaceId(place.placeId)
    val entity =
      SavedPinEntity(
        id = existing?.id ?: 0L,
        placeId = place.placeId,
        nameAr = place.nameAr,
        nameEn = place.nameEn,
        primaryName = place.primaryName,
        secondaryName = place.secondaryName,
        placeType = place.placeType,
        lat = place.point.lat,
        lon = place.point.lon,
        isStreet = place.isStreet,
        streetLinesJson = serializeStreetLines(place.streetLines),
        createdAtMs = now,
      )
    val rowId = dao.upsertPin(entity)
    val total = dao.countPins()
    if (total > maxPins) {
      dao.deleteOldestPins(total - maxPins)
    }
    return entity.copy(id = if (entity.id != 0L) entity.id else rowId).toDomain().copy(
      distanceMeters = place.distanceMeters,
      distanceKind = place.distanceKind,
    )
  }

  suspend fun removePin(id: Long) {
    dao.deletePinById(id)
  }

  suspend fun clearAll() {
    dao.clearAll()
  }

  companion object {
    fun serializeStreetLines(lines: List<List<GeoPoint>>): String {
      if (lines.isEmpty()) return "[]"
      val outer = JSONArray()
      lines.forEach { line ->
        if (line.size >= 2) {
          val arr = JSONArray()
          line.forEach { pt ->
            arr.put(JSONArray().put(pt.lat).put(pt.lon))
          }
          outer.put(arr)
        }
      }
      return outer.toString()
    }

    fun deserializeStreetLines(raw: String): List<List<GeoPoint>> {
      if (raw.isBlank() || raw == "[]") return emptyList()
      return runCatching {
        val outer = JSONArray(raw)
        val out = ArrayList<List<GeoPoint>>(outer.length())
        for (i in 0 until outer.length()) {
          val arr = outer.optJSONArray(i) ?: continue
          val pts = ArrayList<GeoPoint>(arr.length())
          for (j in 0 until arr.length()) {
            val pair = arr.optJSONArray(j) ?: continue
            val lat = pair.optDouble(0, Double.NaN)
            val lon = pair.optDouble(1, Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) {
              pts += GeoPoint(lat, lon)
            }
          }
          if (pts.size >= 2) out += pts
        }
        out
      }.getOrDefault(emptyList())
    }
  }
}

private fun SavedPinEntity.toDomain(): SavedPin =
  SavedPin(
    id = id,
    placeId = placeId,
    nameAr = nameAr,
    nameEn = nameEn,
    primaryName = primaryName,
    secondaryName = secondaryName,
    placeType = placeType,
    point = GeoPoint(lat, lon),
    isStreet = isStreet,
    streetLines = SavedPinRepository.deserializeStreetLines(streetLinesJson),
    createdAtMs = createdAtMs,
    distanceMeters = null,
    distanceKind = DistanceKind.LOADING,
  )
