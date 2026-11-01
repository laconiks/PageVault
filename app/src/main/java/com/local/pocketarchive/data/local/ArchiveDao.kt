package com.local.pocketarchive.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ArchiveDao {
    @Query("SELECT * FROM archived_pages ORDER BY timestampEpochMillis DESC")
    fun observeAllArchives(): Flow<List<ArchivedPage>>

    @Query("""
        SELECT archived_pages.* FROM archived_pages
        JOIN archived_pages_fts ON archived_pages.rowid = archived_pages_fts.rowid
        WHERE archived_pages_fts MATCH :ftsQuery
        ORDER BY archived_pages.timestampEpochMillis DESC
    """)
    fun searchArchivesFts(ftsQuery: String): Flow<List<ArchivedPage>>

    @Query("SELECT * FROM archived_pages WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): ArchivedPage?

    @Query("SELECT * FROM archived_pages WHERE originalUrl = :url ORDER BY versionNumber DESC LIMIT 1")
    suspend fun getLatestByUrl(url: String): ArchivedPage?

    @Query("SELECT * FROM archived_pages WHERE parentUuid = :parentUuid OR uuid = :parentUuid ORDER BY versionNumber ASC")
    fun observeRevisions(parentUuid: String): Flow<List<ArchivedPage>>

    @Query("SELECT COUNT(*) FROM archived_pages WHERE uuid = :rootUuid OR parentUuid = :rootUuid")
    suspend fun getVersionCountForChain(rootUuid: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: ArchivedPage): Long

    @Update
    suspend fun update(page: ArchivedPage)

    @Upsert
    suspend fun upsertPage(page: ArchivedPage)

    @Delete
    suspend fun deletePage(page: ArchivedPage)

    @Query("DELETE FROM archived_pages WHERE uuid = :uuid")
    suspend fun deleteByUuid(uuid: String)

    @Query("INSERT INTO archived_pages_fts(archived_pages_fts) VALUES('rebuild')")
    suspend fun rebuildFts()

    @Query("SELECT * FROM rss_subscriptions ORDER BY id DESC")
    fun observeRssSubscriptions(): Flow<List<RssSubscription>>

    @Query("SELECT * FROM rss_subscriptions")
    suspend fun getAllRssSubscriptions(): List<RssSubscription>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRssSubscription(subscription: RssSubscription): Long

    @Delete
    suspend fun deleteRssSubscription(subscription: RssSubscription)

    @Query("SELECT EXISTS(SELECT 1 FROM archived_pages WHERE originalUrl = :url LIMIT 1)")
    suspend fun isUrlArchived(url: String): Boolean
}
