package com.adaptivesr.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [CardEntity::class, SyncMeta::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
  abstract fun cardDao(): CardDao
  abstract fun syncMetaDao(): SyncMetaDao
}
