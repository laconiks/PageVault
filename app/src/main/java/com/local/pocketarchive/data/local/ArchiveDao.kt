package com.local.pocketarchive.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ArchiveDao {
    @Query("SELECT * FROM archived_pages ORDER BY created_at_epoch_ms DESC")
    fun observeAllArchives(): Flow<List<ArchivedPage>>

    @Query("""
        SELECT archived_pages.* FROM archived_pages
        JOIN archived_pages_fts ON archived_pages.rowid = archived_pages_fts.rowid
        WHERE archived_pages_fts MATCH :ftsQuery
        ORDER BY archived_pages.created_at_epoch_ms DESC
    """)
    fun searchArchivesFts(ftsQuery: String): Flow<List<ArchivedPage>>

    @Query("SELECT * FROM archived_pages WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): ArchivedPage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: ArchivedPage): Long

    @Update
    suspend fun update(page: ArchivedPage)

    @Query("DELETE FROM archived_pages WHERE uuid = :uuid")
    suspend fun deleteByUuid(uuid: String)
}
