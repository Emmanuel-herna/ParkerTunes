package com.parker.tunes.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "songId"])
data class PlaylistTrackEntity(
    val playlistId: String,
    val songId: String,
    val position: Int,
    @ColumnInfo(defaultValue = "''") val title: String = "",
    @ColumnInfo(defaultValue = "''") val artist: String = ""
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val songId: String,
    val addedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val title: String = "",
    @ColumnInfo(defaultValue = "''") val artist: String = "",
    val coverUrl: String? = null
)

@Entity(tableName = "play_history")
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val playedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val title: String = "",
    @ColumnInfo(defaultValue = "''") val artist: String = "",
    val coverUrl: String? = null,
    @ColumnInfo(defaultValue = "0") val playDurationMs: Long = 0L
)

@Entity(tableName = "equalizer_settings")
data class EqualizerSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val preset: Int = -1,
    val bandLevels: String = "",
    val bassEnabled: Boolean = false,
    val bassStrength: Int = 0,
    val virtualizerEnabled: Boolean = false,
    val virtualizerStrength: Int = 0,
    val loudnessEnabled: Boolean = false,
    val loudnessGain: Int = 0,
    val monoEnabled: Boolean = false
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val songId: String,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val filePath: String,
    val fileSizeBytes: Long,
    val downloadedAt: Long,
    val durationSec: Long,
    @ColumnInfo(defaultValue = "'COMPLETE'") val status: String = STATUS_COMPLETE,
    val errorMessage: String? = null
) {
    companion object {
        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_COMPLETE = "COMPLETE"
        const val STATUS_FAILED = "FAILED"
    }
}

@Entity(tableName = "lyrics_cache")
data class LyricsCacheEntity(
    @PrimaryKey val songId: String,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
    val cachedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long = System.currentTimeMillis(),
    val title: String = "",
    val artist: String = "",
    val coverUrl: String? = null,
    val songId: String? = null
)

data class ActiveHour(val hour: Int, val plays: Int)
data class ArtistListeningTime(val artist: String, val durationMs: Long)

data class PlayCountItem(
    val songId: String,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val playCount: Int
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM downloads ORDER BY downloadedAt DESC")
    fun downloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE songId = :songId LIMIT 1")
    suspend fun download(songId: String): DownloadEntity?

    @Query("SELECT * FROM downloads ORDER BY downloadedAt DESC")
    suspend fun downloadsSnapshot(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDownload(item: DownloadEntity)

    @Query("DELETE FROM downloads WHERE songId = :songId")
    suspend fun removeDownload(songId: String)

    @Query("DELETE FROM downloads")
    suspend fun clearDownloads()

    @Query("SELECT * FROM lyrics_cache WHERE songId = :songId LIMIT 1")
    suspend fun lyrics(songId: String): LyricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLyrics(item: LyricsCacheEntity)

    @Query("SELECT * FROM equalizer_settings WHERE id = 1 LIMIT 1")
    fun equalizerSettings(): Flow<EqualizerSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveEqualizerSettings(settings: EqualizerSettingsEntity)

    @Query("SELECT * FROM recent_searches ORDER BY searchedAt DESC LIMIT 20")
    fun recentSearches(): Flow<List<RecentSearchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveRecentSearch(item: RecentSearchEntity)

    @Query("DELETE FROM recent_searches")
    suspend fun clearRecentSearches()

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun playlists(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTrack(track: PlaylistTrackEntity)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    fun playlistTracks(playlistId: String): Flow<List<PlaylistTrackEntity>>

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeTrack(playlistId: String, songId: String)

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun favorites(): Flow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(item: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE songId = :songId")
    suspend fun removeFavorite(songId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE songId = :songId)")
    suspend fun isFavorite(songId: String): Boolean

    @Insert
    suspend fun recordPlay(item: PlayHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun restorePlayHistory(item: PlayHistoryEntity)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun playlistTracksSnapshot(playlistId: String): List<PlaylistTrackEntity>

    @Query("UPDATE play_history SET playDurationMs = playDurationMs + :durationMs WHERE id = (SELECT id FROM play_history WHERE songId = :songId ORDER BY playedAt DESC LIMIT 1)")
    suspend fun addPlayDuration(songId: String, durationMs: Long)

    @Query("SELECT COALESCE(SUM(playDurationMs), 0) FROM play_history")
    fun totalListeningTime(): Flow<Long>

    @Query("SELECT COALESCE(SUM(playDurationMs), 0) FROM play_history WHERE playedAt >= :since")
    fun listeningTimeSince(since: Long): Flow<Long>

    @Query("SELECT CAST(strftime('%H', playedAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour, COUNT(*) AS plays FROM play_history GROUP BY hour ORDER BY plays DESC LIMIT 1")
    fun mostActiveHour(): Flow<ActiveHour?>

    @Query("SELECT artist, SUM(playDurationMs) AS durationMs FROM play_history WHERE artist != '' GROUP BY artist ORDER BY durationMs DESC LIMIT 5")
    fun topArtistsByTime(): Flow<List<ArtistListeningTime>>

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT 100")
    fun history(): Flow<List<PlayHistoryEntity>>

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC")
    suspend fun historySnapshot(): List<PlayHistoryEntity>

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT 20")
    fun recentlyPlayed(): Flow<List<PlayHistoryEntity>>

    @Query("DELETE FROM play_history WHERE id = :historyId")
    suspend fun removeHistory(historyId: Long)

    @Query("""
        SELECT songId, MAX(title) AS title, MAX(artist) AS artist,
               MAX(coverUrl) AS coverUrl, COUNT(*) AS playCount
        FROM play_history
        GROUP BY songId
        ORDER BY playCount DESC, MAX(playedAt) DESC
        LIMIT 10
    """)
    fun mostPlayed(): Flow<List<PlayCountItem>>
}

@Database(
    entities = [PlaylistEntity::class, PlaylistTrackEntity::class, FavoriteEntity::class, PlayHistoryEntity::class, RecentSearchEntity::class, EqualizerSettingsEntity::class, LyricsCacheEntity::class, DownloadEntity::class],
    version = 8,
    exportSchema = false
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        @Volatile private var INSTANCE: LibraryDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN title TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE favorites ADD COLUMN artist TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE play_history ADD COLUMN title TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE play_history ADD COLUMN artist TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE playlist_tracks ADD COLUMN title TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE playlist_tracks ADD COLUMN artist TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN coverUrl TEXT")
                db.execSQL("ALTER TABLE play_history ADD COLUMN coverUrl TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS recent_searches (query TEXT NOT NULL, searchedAt INTEGER NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, coverUrl TEXT, songId TEXT, PRIMARY KEY(query))")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS equalizer_settings (id INTEGER NOT NULL, preset INTEGER NOT NULL, bandLevels TEXT NOT NULL, bassEnabled INTEGER NOT NULL, bassStrength INTEGER NOT NULL, virtualizerEnabled INTEGER NOT NULL, virtualizerStrength INTEGER NOT NULL, loudnessEnabled INTEGER NOT NULL, loudnessGain INTEGER NOT NULL, monoEnabled INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS lyrics_cache (songId TEXT NOT NULL, syncedLyrics TEXT, plainLyrics TEXT, cachedAt INTEGER NOT NULL, PRIMARY KEY(songId))")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE play_history ADD COLUMN playDurationMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS downloads (songId TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, coverUrl TEXT, filePath TEXT NOT NULL, fileSizeBytes INTEGER NOT NULL, downloadedAt INTEGER NOT NULL, durationSec INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'COMPLETE', errorMessage TEXT, PRIMARY KEY(songId))")
            }
        }

        fun get(context: Context): LibraryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibraryDatabase::class.java,
                    "parker_tunes.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { INSTANCE = it }
            }
    }
}
