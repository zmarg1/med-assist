package com.example.medassist

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [RecordingItem::class], version = 1, exportSchema = false) // Set exportSchema to false for now to avoid a build warning
abstract class AppDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao

    companion object {
        // Volatile annotation ensures that the INSTANCE variable is always up-to-date
        // and visible to all execution threads.
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            // If the INSTANCE is not null, then return it,
            // if it is, then create the database
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "health_buddy_app_database" // Name of your database file
                )
                    // Wipes and rebuilds instead of migrating if no Migration object.
                    // Migration is not covered in this scope. Useful for development.
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                // return instance
                instance
            }
        }
    }
}