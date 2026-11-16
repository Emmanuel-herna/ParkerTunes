package com.parker.tunes.data.sources

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "addon_sources")
data class AddonEntity(
    @PrimaryKey val id: String,
    val baseUrl: String,
    val name: String,
    val version: String,
    val bitchordAuth: Boolean,
    val lastSeenAt: Long,
    val enabled: Boolean = true
)
@Entity(tableName = "addon_settings", primaryKeys = ["addonId","key"])
data class AddonSettingsEntity(val addonId: String, val key: String, val value: String)

@Dao
interface SourceRegistryDao {
    @Query("SELECT * FROM addon_sources ORDER BY name COLLATE NOCASE") fun list(): Flow<List<AddonEntity>>
    @Query("SELECT * FROM addon_sources WHERE enabled = 1 ORDER BY name COLLATE NOCASE") fun enabled(): Flow<List<AddonEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(source: AddonEntity)
    @Query("DELETE FROM addon_sources WHERE id = :id") suspend fun delete(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun updateSettings(settings: List<AddonSettingsEntity>)
    @Query("SELECT * FROM addon_settings WHERE addonId = :addonId") suspend fun settings(addonId: String): List<AddonSettingsEntity>
    @Query("UPDATE addon_sources SET enabled = :enabled WHERE id = :id") suspend fun setEnabled(id: String, enabled: Boolean)
}
