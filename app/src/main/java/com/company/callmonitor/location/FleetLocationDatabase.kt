package com.company.callmonitor.location

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [LocationSessionEntity::class, LocationPointEntity::class],
    version = 2,
    exportSchema = false
)
abstract class FleetLocationDatabase : RoomDatabase() {

    abstract fun locationSessionDao(): LocationSessionDao
    abstract fun locationPointDao(): LocationPointDao

    companion object {
        @Volatile
        private var INSTANCE: FleetLocationDatabase? = null

        fun getInstance(context: Context): FleetLocationDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FleetLocationDatabase::class.java,
                    "fleet_location.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
