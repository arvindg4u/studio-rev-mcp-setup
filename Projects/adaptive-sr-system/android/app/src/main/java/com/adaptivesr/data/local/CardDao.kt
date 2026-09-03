package com.adaptivesr.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {
  // Portable ordering: SQLite on minSdk 28 (3.22) lacks NULLS FIRST, so the
  // CASE expression reproduces NULLS FIRST semantics identically.
  @Query("SELECT * FROM cards WHERE suspended = 0 AND (dueAt IS NULL OR dueAt <= :now) ORDER BY CASE WHEN dueAt IS NULL THEN 0 ELSE 1 END, dueAt ASC, id ASC")
  fun dueQueue(now: Long): Flow<List<CardEntity>>

  @Query("SELECT * FROM cards WHERE title LIKE '%' || :q || '%' OR link LIKE '%' || :q || '%' ORDER BY updatedAt DESC LIMIT 200")
  fun searchAll(q: String): Flow<List<CardEntity>>

  @Query("SELECT * FROM cards WHERE pendingSync = 1 ORDER BY CASE WHEN dueAt IS NULL THEN 0 ELSE 1 END, dueAt ASC, id ASC")
  suspend fun pendingOrdered(): List<CardEntity>

  @Query("SELECT * FROM cards WHERE id = :id")
  suspend fun byId(id: String): CardEntity?

  @Query("SELECT * FROM cards WHERE raindropId = :id LIMIT 1")
  suspend fun byRaindrop(id: Long): CardEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(rows: List<CardEntity>)

  @Query("UPDATE cards SET pendingSync = :pending, lastError = :lastError WHERE id = :id")
  suspend fun markPending(id: String, pending: Int, lastError: String?)

  @Query("UPDATE cards SET pendingSync = 0, lastError = NULL, idempotencyKey = :key WHERE id = :id")
  suspend fun markFlushed(id: String, key: String)
}
