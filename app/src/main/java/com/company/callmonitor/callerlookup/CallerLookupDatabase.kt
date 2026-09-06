package com.company.callmonitor.callerlookup

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CallerLookupJobEntity::class,
        CallerLookupResultEntity::class,
        CallerLookupLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class CallerLookupDatabase : RoomDatabase() {
    abstract fun callerLookupDao(): CallerLookupDao

    companion object {
        @Volatile private var instance: CallerLookupDatabase? = null

        fun getInstance(context: Context): CallerLookupDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CallerLookupDatabase::class.java,
                    "caller_lookup.db"
                ).build().also { instance = it }
            }
    }
}

