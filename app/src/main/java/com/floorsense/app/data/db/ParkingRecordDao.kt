package com.floorsense.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.floorsense.app.data.model.ParkingRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface ParkingRecordDao {

    @Insert
    suspend fun insert(record: ParkingRecord): Long

    @Query("SELECT * FROM parking_records ORDER BY parkedAtMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<ParkingRecord>>

    @Query("SELECT * FROM parking_records ORDER BY parkedAtMs DESC LIMIT 1")
    suspend fun latest(): ParkingRecord?

    @Query("UPDATE parking_records SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String)

    @Delete
    suspend fun delete(record: ParkingRecord)
}
