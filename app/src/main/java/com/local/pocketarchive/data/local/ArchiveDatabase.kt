package com.local.pocketarchive.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ArchivedPage::class, ArchivedPageFts::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(CaptureStatusConverter::class)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun archiveDao(): ArchiveDao

    companion object {
        @Volatile
        private var INSTANCE: ArchiveDatabase? = null

        fun getInstance(context: Context): ArchiveDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    ArchiveDatabase::class.java,
                    "pocket_archive.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
