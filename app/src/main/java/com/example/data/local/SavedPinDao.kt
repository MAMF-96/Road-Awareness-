package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPinDao {
  @Query("SELECT * FROM saved_pins ORDER BY createdAtMs DESC")
  fun getAllPinsFlow(): Flow<List<SavedPinEntity>>

  @Query("SELECT * FROM saved_pins ORDER BY createdAtMs DESC")
  suspend fun getAllPins(): List<SavedPinEntity>

  @Query("SELECT * FROM saved_pins WHERE placeId = :placeId LIMIT 1")
  suspend fun findByPlaceId(placeId: Long): SavedPinEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertPin(entity: SavedPinEntity): Long

  @Query("DELETE FROM saved_pins WHERE id = :id")
  suspend fun deletePinById(id: Long)

  @Query("DELETE FROM saved_pins WHERE id IN (SELECT id FROM saved_pins ORDER BY createdAtMs ASC LIMIT :excess)")
  suspend fun deleteOldestPins(excess: Int)

  @Query("SELECT COUNT(*) FROM saved_pins")
  suspend fun countPins(): Int

  @Query("DELETE FROM saved_pins")
  suspend fun clearAll()
}
