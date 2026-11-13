package com.fibstein.wakestop.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "favorite_stops")
data class FavoriteStop(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface FavoriteStopDao {
    @Query("SELECT * FROM favorite_stops ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<FavoriteStop>>

    @Insert
    suspend fun insert(stop: FavoriteStop): Long

    @Query("DELETE FROM favorite_stops WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [FavoriteStop::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteStopDao(): FavoriteStopDao
}
