package com.adaptivesr.data.local

import com.adaptivesr.data.androidRoomInMemory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CardDaoTest {
  @Test fun dueQueueIsOverdueFirstThenId() = runTest {
    val db = androidRoomInMemory()
    val dao = db.cardDao()
    dao.upsertAll(listOf(
      CardEntity(id = "b", title = "B", dueAt = 200, status = "REVIEW"),
      CardEntity(id = "a", title = "A", dueAt = 100, status = "REVIEW"),
      CardEntity(id = "s", title = "S", dueAt = 50, status = "REVIEW", suspended = true),
      CardEntity(id = "m", title = "M", dueAt = 10, status = "MASTERED", suspended = true)
    ))
    assertEquals(listOf("a", "b"), dao.dueQueue(now = 300).first().map { it.id })
    db.close()
  }
}
