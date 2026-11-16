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
    val coverUrl: String? = null
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

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long = System.currentTimeMillis(),
    val title: String = "",
    val artist: String = "",
    val coverUrl: String? = null,
    val songId: String? = null
)

data class PlayCountItem(
    val songId: String,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val playCount: Int
)

@Dao
interface LibraryDao {
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

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT 100")
    fun history(): Flow<List<PlayHistoryEntity>>

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
    entities = [PlaylistEntity::class, PlaylistTrackEntity::class, FavoriteEntity::class, PlayHistoryEntity::class, RecentSearchEntity::class, EqualizerSettingsEntity::class],
    version = 5,
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

        fun get(context: Context): LibraryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibraryDatabase::class.java,
                    "parker_tunes.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { INSTANCE = it }
            }
    }
}
