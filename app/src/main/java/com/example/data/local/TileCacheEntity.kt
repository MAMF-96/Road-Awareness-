package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tile_cache")
data class TileCacheEntity(
  @PrimaryKey val tileKey: String,
  val z: Int,
  val x: Int,
  val y: Int,
  val rawBytes: ByteArray,
  val fetchedAtMs: Long,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is TileCacheEntity) return false
    return tileKey == other.tileKey &&
      z == other.z &&
      x == other.x &&
      y == other.y &&
      fetchedAtMs == other.fetchedAtMs &&
      rawBytes.contentEquals(other.rawBytes)
  }

  override fun hashCode(): Int {
    var result = tileKey.hashCode()
    result = 31 * result + z
    result = 31 * result + x
    result = 31 * result + y
    result = 31 * result + fetchedAtMs.hashCode()
    result = 31 * result + rawBytes.contentHashCode()
    return result
  }
}
