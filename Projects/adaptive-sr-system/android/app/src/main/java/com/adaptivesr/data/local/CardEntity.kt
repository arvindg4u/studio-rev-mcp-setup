package com.adaptivesr.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "cards", indices = [Index("dueAt"), Index("status"), Index(value = ["raindropId"], unique = true)])
data class CardEntity(
  @PrimaryKey val id: String,
  val title: String,
  val link: String? = null,
  val source: String = "APP",
  val raindropId: Long? = null,
  val collection: String? = null,
  val reviewCount: Int = 0,
  val intervalDays: Int = 0,
  val lastRating: String? = null,
  val status: String = "NEW",
  val suspended: Boolean = false,
  val dueAt: Long? = null,
  val lastReviewedAt: Long? = null,
  val pendingSync: Int = 0,
  val lastError: String? = null,
  val idempotencyKey: String? = null,
  val updatedAt: Long = System.currentTimeMillis()
)
