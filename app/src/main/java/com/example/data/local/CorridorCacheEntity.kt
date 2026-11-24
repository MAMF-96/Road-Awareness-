package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "corridor_cache")
data class CorridorCacheEntity(
  @PrimaryKey val cellKey: String,
  val mode: String,
  val centerLat: Double,
  val centerLon: Double,
  val rawOverpassJson: String,
  val fetchedAtMs: Long,
)
