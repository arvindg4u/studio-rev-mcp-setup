package com.adaptivesr.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "sync_meta")
data class SyncMeta(
  @PrimaryKey val key: String,
  val value: String
)

@Dao
interface SyncMetaDao {
  @Query("SELECT * FROM sync_meta WHERE `key` = :key LIMIT 1")
  suspend fun get(key: String): SyncMeta?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun put(meta: SyncMeta)
}
