package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [CorridorCacheEntity::class, TileCacheEntity::class, SavedPinEntity::class],
  version = 3,
  exportSchema = false,
)
abstract class RoadAwarenessDatabase : RoomDatabase() {
  abstract fun corridorCacheDao(): CorridorCacheDao
  abstract fun tileCacheDao(): TileCacheDao
  abstract fun savedPinDao(): SavedPinDao

  companion object {
    @Volatile private var instance: RoadAwarenessDatabase? = null

    private val MIGRATION_2_3 =
      object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
          db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `saved_pins` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `placeId` INTEGER NOT NULL,
              `nameAr` TEXT,
              `nameEn` TEXT,
              `primaryName` TEXT NOT NULL,
              `secondaryName` TEXT,
              `placeType` TEXT NOT NULL,
              `lat` REAL NOT NULL,
              `lon` REAL NOT NULL,
              `isStreet` INTEGER NOT NULL,
              `streetLinesJson` TEXT NOT NULL,
              `createdAtMs` INTEGER NOT NULL
            )
            """.trimIndent(),
          )
          db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_saved_pins_placeId` ON `saved_pins` (`placeId`)")
        }
      }

    fun getInstance(context: Context): RoadAwarenessDatabase =
      instance
        ?: synchronized(this) {
          instance
            ?: Room.databaseBuilder(
                context.applicationContext,
                RoadAwarenessDatabase::class.java,
                "road_awareness_cache.db",
              )
              .addMigrations(MIGRATION_2_3)
              .fallbackToDestructiveMigration(dropAllTables = true)
              .build()
              .also { instance = it }
        }
  }
}
