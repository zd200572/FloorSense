package com.floorsense.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.floorsense.app.data.model.ParkingRecord

@Database(
    entities = [ParkingRecord::class],
    version = 1,
    exportSchema = true
)
abstract class FloorSenseDatabase : RoomDatabase() {

    abstract fun parkingRecordDao(): ParkingRecordDao

    companion object {
        @Volatile
        private var instance: FloorSenseDatabase? = null

        fun get(context: Context): FloorSenseDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FloorSenseDatabase::class.java,
                    "floorsense.db"
                ).build().also { instance = it }
            }
    }
}
