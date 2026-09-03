package com.adaptivesr.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adaptivesr.data.local.AppDb

fun androidRoomInMemory(): AppDb =
  Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java).allowMainThreadQueries().build()
