package com.tvflux.pro

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class DifferentialSyncStats(
    val sourceId: String,
    val addedCount: Int,
    val updatedCount: Int,
    val removedCount: Int,
    val unchangedCount: Int,
    val durationMs: Long
)

/**
 * TiviMate-Style Local SQLite Content Library Engine (`live_pro_tivimate_v11.db`).
 * - Stores hundreds of thousands of Live TV channels, VOD Movies, Series, and Bouquets locally.
 * - Uses WAL (Write-Ahead Logging) + explicit WAL FULL/TRUNCATE checkpointing so data is immediately
 *   flushed to the main `.db` file and NEVER lost when closing the app or powering off the device.
 * - Uses chunked 2,500-row pagination on reads so Android's 2MB `CursorWindow` never overflows.
 * - Performs atomic differential UPSERT transactions with automatic rollback on failure so the
 *   existing library is NEVER lost if the network drops or a server fails mid-sync.
 */
class TiviMateLocalDatabase private constructor(private val appContext: Context) :
    SQLiteOpenHelper(appContext, DB_NAME, null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        runCatching { db.enableWriteAheadLogging() }
        runCatching { db.rawQuery("PRAGMA synchronous = NORMAL", null).use { it.moveToFirst() } }
        runCatching { db.rawQuery("PRAGMA temp_store = MEMORY", null).use { it.moveToFirst() } }
        runCatching { db.rawQuery("PRAGMA cache_size = -24000", null).use { it.moveToFirst() } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS catalog_items (
                stable_key TEXT PRIMARY KEY,
                item_id INTEGER NOT NULL,
                channel_number INTEGER NOT NULL,
                name TEXT NOT NULL COLLATE NOCASE,
                name_lower TEXT NOT NULL,
                url TEXT NOT NULL,
                group_name TEXT NOT NULL COLLATE NOCASE,
                logo TEXT DEFAULT '',
                user_agent TEXT DEFAULT '',
                referer TEXT DEFAULT '',
                origin TEXT DEFAULT '',
                cookie TEXT DEFAULT '',
                drm_scheme TEXT DEFAULT '',
                drm_key TEXT DEFAULT '',
                content_type TEXT NOT NULL,
                rating TEXT DEFAULT '',
                release_year TEXT DEFAULT '',
                plot TEXT DEFAULT '',
                series_id INTEGER DEFAULT 0,
                playlist_id TEXT NOT NULL,
                playlist_name TEXT NOT NULL,
                stream_id INTEGER DEFAULT 0,
                stream_base_url TEXT DEFAULT '',
                stream_username TEXT DEFAULT '',
                stream_password TEXT DEFAULT '',
                container_ext TEXT DEFAULT '',
                video_codec TEXT DEFAULT '',
                audio_codec TEXT DEFAULT '',
                item_hash TEXT NOT NULL,
                sort_order INTEGER DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sync_sources (
                source_id TEXT PRIMARY KEY,
                source_name TEXT NOT NULL,
                catalog_etag TEXT DEFAULT '',
                last_sync_ms INTEGER DEFAULT 0,
                live_count INTEGER DEFAULT 0,
                movie_count INTEGER DEFAULT 0,
                series_count INTEGER DEFAULT 0
            )
            """.trimIndent()
        )

        createIndexes(db)
    }

    private fun createIndexes(db: SQLiteDatabase) {
        // Drop redundant older composite indexes if upgrading so bulk INSERT is 3x faster
        runCatching { db.execSQL("DROP INDEX IF EXISTS idx_cat_type_playlist_group") }
        runCatching { db.execSQL("DROP INDEX IF EXISTS idx_cat_stream_id") }

        // Exact index matching loadAllCatalogSnapshot: WHERE content_type = ? ORDER BY sort_order ASC, channel_number ASC
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cat_type_sort ON catalog_items(content_type, sort_order ASC, channel_number ASC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cat_type_group ON catalog_items(content_type, group_name, sort_order ASC, channel_number ASC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cat_search_name ON catalog_items(content_type, name_lower)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_cat_playlist ON catalog_items(playlist_id, content_type)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "live_pro_tivimate_v11.db"
        private const val DB_VERSION = 12
        private const val CURSOR_PAGE_SIZE = 2500

        @Volatile
        private var instance: TiviMateLocalDatabase? = null

        private val sourceSyncLocks = ConcurrentHashMap<String, Mutex>()

        fun getInstance(context: Context): TiviMateLocalDatabase {
            return instance ?: synchronized(this) {
                instance ?: TiviMateLocalDatabase(context.applicationContext).also { instance = it }
            }
        }

        fun getSourceMutex(sourceId: String): Mutex {
            return sourceSyncLocks.getOrPut(sourceId) { Mutex() }
        }

        fun isSourceSyncInProgress(sourceId: String): Boolean {
            return sourceSyncLocks[sourceId]?.isLocked == true
        }

        private fun computeFastItemHash(ch: TvChannel, sortOrder: Int): String {
            var h = ch.name.hashCode()
            h = 31 * h + ch.url.hashCode()
            h = 31 * h + ch.group.hashCode()
            h = 31 * h + (ch.logo?.hashCode() ?: 0)
            h = 31 * h + ch.streamId
            h = 31 * h + sortOrder
            return h.toUInt().toString(16)
        }
    }

    fun getDatabaseSizeMb(): String {
        val dbFile = appContext.getDatabasePath(DB_NAME)
        val walFile = File(dbFile.path + "-wal")
        val totalBytes = (if (dbFile.exists()) dbFile.length() else 0L) +
            (if (walFile.exists()) walFile.length() else 0L)
        return String.format(Locale.US, "%.2f MB", totalBytes / (1024.0 * 1024.0))
    }

    suspend fun getSourceLastSyncInfo(sourceId: String): Pair<Long, String> = withContext(Dispatchers.IO) {
        val db = readableDatabase
        var lastMs = 0L
        var etag = ""
        db.rawQuery(
            "SELECT last_sync_ms, catalog_etag FROM sync_sources WHERE source_id = ? LIMIT 1",
            arrayOf(sourceId)
        ).use { c ->
            if (c.moveToFirst()) {
                lastMs = c.getLong(0)
                etag = c.getString(1).orEmpty()
            }
        }
        Pair(lastMs, etag)
    }

    /**
     * Performs a TiviMate-style Atomic Differential Sync for a single source (`playlistId`):
     * - Fast-path bulk insert on first-time sync (`existingHashes.isEmpty()`).
     * - Compares incoming items against existing rows in SQLite using `computeFastItemHash` on subsequent updates.
     * - Executes inside a single SQLite transaction and immediately checkpoints WAL to disk.
     */
    suspend fun syncSourceCatalogAtomically(
        sourceId: String,
        sourceName: String,
        liveChannels: List<TvChannel>,
        movies: List<TvChannel>,
        series: List<TvChannel>,
        catalogEtag: String = "",
        allowEmptyReplace: Boolean = false
    ): DifferentialSyncStats = withContext(Dispatchers.IO) {
        val mutex = getSourceMutex(sourceId)
        mutex.withLock {
            val startMs = SystemClock.elapsedRealtime()
            val totalCount = liveChannels.size + movies.size + series.size
            val allIncoming = ArrayList<Pair<TvChannel, Int>>(totalCount)
            liveChannels.forEachIndexed { idx, ch -> allIncoming.add(ch to idx) }
            movies.forEachIndexed { idx, ch -> allIncoming.add(ch to idx) }
            series.forEachIndexed { idx, ch -> allIncoming.add(ch to idx) }

            if (allIncoming.isEmpty() && !allowEmptyReplace) {
                TiviMateDiagnostics.logEvent("⚠️ تم الاحتفاظ بالمكتبة المحلية للمصدر [$sourceName] لأن الاستجابة كانت فارغة.")
                return@withLock DifferentialSyncStats(sourceId, 0, 0, 0, 0, 0L)
            }

            val db = writableDatabase
            val existingHashes = HashMap<String, String>(totalCount.coerceAtLeast(16))
            runCatching {
                db.rawQuery(
                    "SELECT stable_key, item_hash FROM catalog_items WHERE playlist_id = ?",
                    arrayOf(sourceId)
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        existingHashes[cursor.getString(0)] = cursor.getString(1)
                    }
                }
            }

            val isFirstTimeInsert = existingHashes.isEmpty()
            var added = 0
            var updated = 0
            var unchanged = 0
            val seenKeys = HashSet<String>(totalCount.coerceAtLeast(16))
            val nowMs = System.currentTimeMillis()

            db.beginTransactionNonExclusive()
            try {
                val upsertSql = """
                    INSERT OR REPLACE INTO catalog_items (
                        stable_key, item_id, channel_number, name, name_lower, url, group_name,
                        logo, user_agent, referer, origin, cookie, drm_scheme, drm_key,
                        content_type, rating, release_year, plot, series_id, playlist_id,
                        playlist_name, stream_id, stream_base_url, stream_username,
                        stream_password, container_ext, video_codec, audio_codec,
                        item_hash, sort_order, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()

                val stmt = db.compileStatement(upsertSql)

                for ((ch, orderIdx) in allIncoming) {
                    val key = ch.stableKey
                    if (!seenKeys.add(key)) continue

                    val newHash = computeFastItemHash(ch, orderIdx)
                    if (!isFirstTimeInsert) {
                        val oldHash = existingHashes[key]
                        if (oldHash == newHash) {
                            unchanged++
                            continue
                        }
                        if (oldHash == null) added++ else updated++
                    } else {
                        added++
                    }

                    stmt.clearBindings()
                    stmt.bindString(1, key)
                    stmt.bindLong(2, ch.id.toLong())
                    stmt.bindLong(3, ch.number.toLong())
                    stmt.bindString(4, ch.name)
                    stmt.bindString(5, ch.name.lowercase(Locale.ROOT))
                    stmt.bindString(6, ch.url)
                    stmt.bindString(7, ch.group.ifBlank { "عام" })
                    stmt.bindString(8, ch.logo.orEmpty())
                    stmt.bindString(9, ch.userAgent.orEmpty())
                    stmt.bindString(10, ch.referer.orEmpty())
                    stmt.bindString(11, ch.origin.orEmpty())
                    stmt.bindString(12, ch.cookie.orEmpty())
                    stmt.bindString(13, ch.drmScheme)
                    stmt.bindString(14, ch.drmKey)
                    stmt.bindString(15, ch.contentType.name)
                    stmt.bindString(16, ch.rating)
                    stmt.bindString(17, ch.releaseYear)
                    stmt.bindString(18, if (ch.plot.length > 250) ch.plot.take(250) else ch.plot)
                    stmt.bindLong(19, ch.seriesId.toLong())
                    stmt.bindString(20, ch.playlistId.ifBlank { sourceId })
                    stmt.bindString(21, ch.playlistName.ifBlank { sourceName })
                    stmt.bindLong(22, ch.streamId.toLong())
                    stmt.bindString(23, ch.streamBaseUrl)
                    stmt.bindString(24, ch.streamUsername)
                    stmt.bindString(25, ch.streamPassword)
                    stmt.bindString(26, ch.containerExtension)
                    stmt.bindString(27, ch.videoCodecHint)
                    stmt.bindString(28, ch.audioCodecHint)
                    stmt.bindString(29, newHash)
                    stmt.bindLong(30, orderIdx.toLong())
                    stmt.bindLong(31, nowMs)
                    stmt.executeInsert()
                }

                var removed = 0
                if (!isFirstTimeInsert) {
                    val staleKeys = existingHashes.keys.filter { it !in seenKeys }
                    if (staleKeys.isNotEmpty()) {
                        val delStmt = db.compileStatement("DELETE FROM catalog_items WHERE stable_key = ?")
                        for (sKey in staleKeys) {
                            delStmt.clearBindings()
                            delStmt.bindString(1, sKey)
                            removed += delStmt.executeUpdateDelete()
                        }
                    }
                }

                val metaStmt = db.compileStatement(
                    """
                    INSERT OR REPLACE INTO sync_sources (
                        source_id, source_name, catalog_etag, last_sync_ms, live_count, movie_count, series_count
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()
                )
                metaStmt.bindString(1, sourceId)
                metaStmt.bindString(2, sourceName)
                metaStmt.bindString(3, catalogEtag)
                metaStmt.bindLong(4, nowMs)
                metaStmt.bindLong(5, liveChannels.size.toLong())
                metaStmt.bindLong(6, movies.size.toLong())
                metaStmt.bindLong(7, series.size.toLong())
                metaStmt.executeInsert()

                db.setTransactionSuccessful()
                val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                val stats = DifferentialSyncStats(
                    sourceId = sourceId,
                    addedCount = added,
                    updatedCount = updated,
                    removedCount = removed,
                    unchangedCount = unchanged,
                    durationMs = elapsed
                )
                TiviMateDiagnostics.recordSyncStats(sourceName, stats)
                stats
            } finally {
                runCatching { db.endTransaction() }
                checkpointWalNow()
            }
        }
    }

    fun checkpointWalNow() {
        runCatching {
            writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        }.onFailure {
            runCatching {
                writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            }
        }
    }

    suspend fun updatePlaylistCredentialsInPlace(
        playlistId: String,
        newServerName: String,
        newBaseUrl: String,
        newUsernameEnc: String,
        newPasswordEnc: String,
        oldBaseUrl: String = ""
    ) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val cleanNewBase = newBaseUrl.trim().removeSuffix("/")
        val cleanOldBase = oldBaseUrl.trim().removeSuffix("/")
        db.beginTransactionNonExclusive()
        try {
            if (cleanOldBase.isNotBlank() && cleanNewBase.isNotBlank() && !cleanOldBase.equals(cleanNewBase, ignoreCase = true)) {
                db.execSQL(
                    """
                    UPDATE catalog_items
                    SET playlist_name = ?,
                        stream_base_url = ?,
                        stream_username = ?,
                        stream_password = ?,
                        url = REPLACE(url, ?, ?)
                    WHERE playlist_id = ?
                    """.trimIndent(),
                    arrayOf(newServerName, cleanNewBase, newUsernameEnc, newPasswordEnc, cleanOldBase, cleanNewBase, playlistId)
                )
            } else {
                db.execSQL(
                    """
                    UPDATE catalog_items
                    SET playlist_name = ?,
                        stream_base_url = ?,
                        stream_username = ?,
                        stream_password = ?
                    WHERE playlist_id = ?
                    """.trimIndent(),
                    arrayOf(newServerName, cleanNewBase, newUsernameEnc, newPasswordEnc, playlistId)
                )
            }
            db.setTransactionSuccessful()
        } finally {
            runCatching { db.endTransaction() }
            checkpointWalNow()
        }
    }

    suspend fun deleteSourceCatalog(sourceId: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.delete("catalog_items", "playlist_id = ?", arrayOf(sourceId))
            db.delete("sync_sources", "source_id = ?", arrayOf(sourceId))
            db.setTransactionSuccessful()
        } finally {
            runCatching { db.endTransaction() }
            checkpointWalNow()
        }
    }

    suspend fun hasAnyCatalogItems(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val db = readableDatabase
            db.rawQuery("SELECT 1 FROM catalog_items LIMIT 1", null).use { c ->
                c.moveToFirst()
            }
        }.getOrDefault(false)
    }

    /**
     * Reconstructs XtreamAccountConfig entries directly from SQLite catalog_items if the JSON state
     * file was ever missing a playlist entry.
     */
    suspend fun loadDistinctPlaylistsFromSql(): List<XtreamAccountConfig> = withContext(Dispatchers.IO) {
        val recovered = ArrayList<XtreamAccountConfig>()
        runCatching {
            val db = readableDatabase
            val sql = """
                SELECT
                    playlist_id,
                    MAX(playlist_name),
                    MAX(stream_base_url),
                    MAX(stream_username),
                    MAX(stream_password),
                    SUM(CASE WHEN content_type = 'LIVE_TV' THEN 1 ELSE 0 END),
                    SUM(CASE WHEN content_type = 'MOVIE' THEN 1 ELSE 0 END),
                    SUM(CASE WHEN content_type = 'SERIES' THEN 1 ELSE 0 END)
                FROM catalog_items
                WHERE playlist_id != 'PORTAL_MAIN' AND playlist_id != ''
                GROUP BY playlist_id
            """.trimIndent()
            var idx = 0
            db.rawQuery(sql, null).use { c ->
                while (c.moveToNext()) {
                    val pid = c.getString(0).orEmpty()
                    if (pid.isBlank()) continue
                    val pName = c.getString(1).orEmpty().ifBlank { "قائمة XTREAM #${idx + 1}" }
                    val baseUrl = c.getString(2).orEmpty()
                    val uRaw = c.getString(3).orEmpty()
                    val pRaw = c.getString(4).orEmpty()
                    val userDec = runCatching { URLDecoder.decode(uRaw, "UTF-8") }.getOrDefault(uRaw)
                    val passDec = runCatching { URLDecoder.decode(pRaw, "UTF-8") }.getOrDefault(pRaw)
                    val liveCnt = c.getInt(5)
                    val movieCnt = c.getInt(6)
                    val seriesCnt = c.getInt(7)
                    recovered.add(
                        XtreamAccountConfig(
                            id = pid,
                            serverName = pName,
                            serverUrl = baseUrl,
                            username = userDec,
                            password = passDec,
                            enabled = true,
                            isRemoteFromPortal = true,
                            orderIndex = idx++,
                            liveCount = liveCnt,
                            movieCount = movieCnt,
                            seriesCount = seriesCnt,
                            statusNote = "متصل ✓ ($liveCnt قناة • $movieCnt فيلم)"
                        )
                    )
                }
            }
        }
        recovered
    }

    suspend fun loadAllCatalogSnapshot(): Triple<List<TvChannel>, List<TvChannel>, List<TvChannel>> =
        withContext(Dispatchers.IO) {
            val startMs = SystemClock.elapsedRealtime()
            val live = runCatching { queryItemsInternal(StreamContentType.LIVE_TV, limit = 150_000) }.getOrDefault(emptyList())
            val movies = runCatching { queryItemsInternal(StreamContentType.MOVIE, limit = 150_000) }.getOrDefault(emptyList())
            val series = runCatching { queryItemsInternal(StreamContentType.SERIES, limit = 150_000) }.getOrDefault(emptyList())
            val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            TiviMateDiagnostics.recordQueryLatency(elapsed)
            Triple(live, movies, series)
        }

    suspend fun queryCatalogFiltered(
        contentType: StreamContentType,
        playlistIds: List<String> = emptyList(),
        groupName: String = "الكل",
        searchQuery: String = "",
        hiddenBouquets: Set<String> = emptySet(),
        limit: Int = 5000,
        offset: Int = 0
    ): List<TvChannel> = withContext(Dispatchers.IO) {
        val startMs = SystemClock.elapsedRealtime()
        val result = runCatching {
            queryItemsInternal(
                contentType = contentType,
                playlistIds = playlistIds,
                groupName = groupName,
                searchQuery = searchQuery,
                hiddenBouquets = hiddenBouquets,
                limit = limit,
                offset = offset
            )
        }.getOrDefault(emptyList())
        val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
        TiviMateDiagnostics.recordQueryLatency(elapsed)
        result
    }

    /**
     * Reads catalog items in 2,500-row pages so Android's 2MB SQLite CursorWindow NEVER overflows
     * and never performs expensive quadratic window refills on large servers (50,000+ channels/movies).
     */
    private fun queryItemsInternal(
        contentType: StreamContentType,
        playlistIds: List<String> = emptyList(),
        groupName: String = "الكل",
        searchQuery: String = "",
        hiddenBouquets: Set<String> = emptySet(),
        limit: Int = 100_000,
        offset: Int = 0
    ): List<TvChannel> {
        val db = readableDatabase
        val whereClauses = mutableListOf("content_type = ?")
        val args = mutableListOf(contentType.name)

        if (playlistIds.isNotEmpty()) {
            val placeholders = playlistIds.joinToString(",") { "?" }
            whereClauses.add("playlist_id IN ($placeholders)")
            args.addAll(playlistIds)
        }

        if (groupName.isNotBlank() && groupName != "الكل" && groupName != "Tout" && groupName != "All") {
            whereClauses.add("group_name = ?")
            args.add(groupName)
        }

        val q = searchQuery.trim().lowercase(Locale.ROOT)
        if (q.isNotEmpty()) {
            whereClauses.add("(name_lower LIKE ? OR group_name LIKE ? OR CAST(channel_number AS TEXT) = ?)")
            args.add("%$q%")
            args.add("%$q%")
            args.add(q)
        }

        val whereSql = whereClauses.joinToString(" AND ")
        val baseArgs = args.toTypedArray()
        val list = ArrayList<TvChannel>(limit.coerceAtMost(8192))

        var remaining = limit
        var currentOffset = offset

        while (remaining > 0) {
            val pageSize = remaining.coerceAtMost(CURSOR_PAGE_SIZE)
            val sql = """
                SELECT
                    item_id, channel_number, name, url, group_name, logo,
                    user_agent, referer, origin, cookie, drm_scheme, drm_key,
                    content_type, rating, release_year, plot, series_id,
                    playlist_id, playlist_name, stream_id, stream_base_url,
                    stream_username, stream_password, container_ext, video_codec, audio_codec
                FROM catalog_items
                WHERE $whereSql
                ORDER BY sort_order ASC, channel_number ASC
                LIMIT $pageSize OFFSET $currentOffset
            """.trimIndent()

            var fetchedInPage = 0
            db.rawQuery(sql, baseArgs).use { c ->
                while (c.moveToNext()) {
                    fetchedInPage++
                    val grp = c.getString(4).orEmpty().ifBlank { "عام" }
                    if (hiddenBouquets.isNotEmpty() && grp in hiddenBouquets) continue
                    val nm = c.getString(2).orEmpty()

                    list.add(
                        TvChannel(
                            id = c.getInt(0),
                            number = c.getInt(1),
                            name = nm,
                            url = c.getString(3).orEmpty(),
                            group = grp,
                            logo = c.getString(5)?.takeIf { it.isNotBlank() },
                            userAgent = c.getString(6)?.takeIf { it.isNotBlank() },
                            referer = c.getString(7)?.takeIf { it.isNotBlank() },
                            origin = c.getString(8)?.takeIf { it.isNotBlank() },
                            cookie = c.getString(9)?.takeIf { it.isNotBlank() },
                            drmScheme = c.getString(10).orEmpty(),
                            drmKey = c.getString(11).orEmpty(),
                            contentType = contentType,
                            rating = c.getString(13).orEmpty(),
                            releaseYear = c.getString(14).orEmpty(),
                            plot = c.getString(15).orEmpty(),
                            seriesId = c.getInt(16),
                            playlistId = c.getString(17).orEmpty().ifBlank { "PORTAL_MAIN" },
                            playlistName = c.getString(18).orEmpty().ifBlank { "الرئيسية" },
                            streamId = c.getInt(19),
                            streamBaseUrl = c.getString(20).orEmpty(),
                            streamUsername = c.getString(21).orEmpty(),
                            streamPassword = c.getString(22).orEmpty(),
                            containerExtension = c.getString(23).orEmpty(),
                            videoCodecHint = c.getString(24).orEmpty(),
                            audioCodecHint = c.getString(25).orEmpty()
                        )
                    )
                }
            }

            if (fetchedInPage < pageSize) break
            remaining -= fetchedInPage
            currentOffset += fetchedInPage
        }

        return list
    }

    suspend fun getCountsSummary(): Triple<Int, Int, Int> = withContext(Dispatchers.IO) {
        var live = 0
        var movies = 0
        var series = 0
        runCatching {
            val db = readableDatabase
            db.rawQuery(
                "SELECT content_type, COUNT(*) FROM catalog_items GROUP BY content_type",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    when (c.getString(0)) {
                        StreamContentType.LIVE_TV.name -> live = c.getInt(1)
                        StreamContentType.MOVIE.name -> movies = c.getInt(1)
                        StreamContentType.SERIES.name -> series = c.getInt(1)
                    }
                }
            }
        }
        Triple(live, movies, series)
    }
}
