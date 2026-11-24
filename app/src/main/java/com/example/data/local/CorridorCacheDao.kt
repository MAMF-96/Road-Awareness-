package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CorridorCacheDao {
  @Query("SELECT * FROM corridor_cache WHERE cellKey = :cellKey LIMIT 1")
  suspend fun getCell(cellKey: String): CorridorCacheEntity?

  @Query("SELECT * FROM corridor_cache WHERE cellKey IN (:cellKeys)")
  suspend fun getCells(cellKeys: List<String>): List<CorridorCacheEntity>

  @Query(
    """
    SELECT * FROM corridor_cache
    WHERE mode = :mode
    ORDER BY ((centerLat - :lat) * (centerLat - :lat) + (centerLon - :lon) * (centerLon - :lon)) ASC
    LIMIT 1
    """
  )
  suspend fun getNearestCell(mode: String, lat: Double, lon: Double): CorridorCacheEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertCell(entity: CorridorCacheEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertCells(entities: List<CorridorCacheEntity>)

  @Query("SELECT COUNT(*) FROM corridor_cache")
  suspend fun countCells(): Int

  @Query("SELECT MIN(fetchedAtMs) FROM corridor_cache")
  suspend fun getOldestFetchedAtMs(): Long?

  @Query("DELETE FROM corridor_cache")
  suspend fun clearAll()
}
