package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TileCacheDao {
  @Query("SELECT * FROM tile_cache WHERE tileKey = :tileKey LIMIT 1")
  suspend fun getTile(tileKey: String): TileCacheEntity?

  @Query("SELECT * FROM tile_cache WHERE tileKey IN (:tileKeys)")
  suspend fun getTiles(tileKeys: List<String>): List<TileCacheEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertTiles(entities: List<TileCacheEntity>)

  @Query("SELECT COUNT(*) FROM tile_cache")
  suspend fun countTiles(): Int

  @Query("SELECT MIN(fetchedAtMs) FROM tile_cache")
  suspend fun getOldestFetchedAtMs(): Long?

  @Query("DELETE FROM tile_cache")
  suspend fun clearAll()
}
