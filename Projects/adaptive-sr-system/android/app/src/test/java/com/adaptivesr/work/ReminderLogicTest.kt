package com.adaptivesr.work

import com.adaptivesr.data.androidRoomInMemory
import com.adaptivesr.data.local.CardEntity
import com.adaptivesr.data.local.SyncMeta
import com.adaptivesr.ui.stats.ReminderText
import com.adaptivesr.ui.stats.StatsComputer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric runner required (same Task 2 deviation as CardDaoTest):
// androidRoomInMemory() goes through ApplicationProvider.
@RunWith(RobolectricTestRunner::class)
class ReminderLogicTest {
  @Test fun degradedWhenDrift_andBodyCounts() = runTest {
    val db = androidRoomInMemory()
    db.cardDao().upsertAll(listOf(
      CardEntity(id = "1", title = "A", status = "REVIEW", dueAt = 1),
      CardEntity(id = "2", title = "B", status = "MASTERED", suspended = true)
    ))
    val stats = StatsComputer.fromDb(db.cardDao(), db.syncMetaDao())
    assertEquals(1, stats.active); assertEquals(1, stats.mastered)
    assertEquals("Daily Digest - 1 items (1 overdue)", ReminderText.body(due = 1, overdue = 1))
    db.syncMetaDao().put(SyncMeta("lastDuePull", "1"))
    assertEquals(true, StatsComputer.isDegraded(lastPull = 1, now = 1 + 26 * 3_600_000))
    db.close()
  }
}
