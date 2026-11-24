package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "saved_pins",
  indices = [Index(value = ["placeId"], unique = true)],
)
data class SavedPinEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val placeId: Long,
  val nameAr: String?,
  val nameEn: String?,
  val primaryName: String,
  val secondaryName: String?,
  val placeType: String,
  val lat: Double,
  val lon: Double,
  val isStreet: Boolean,
  val streetLinesJson: String,
  val createdAtMs: Long,
)
