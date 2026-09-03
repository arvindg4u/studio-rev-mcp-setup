package com.adaptivesr.ui.stats

import com.adaptivesr.data.local.CardDao
import com.adaptivesr.data.local.CardEntity
import com.adaptivesr.data.local.SyncMetaDao
import kotlinx.coroutines.flow.first

/**
 * Local-first stats derivation. Room is the truth: every count here is a
 * direct read of the cards table. Plan caps this at "count queries" — no
 * server round-trip, so stats stay meaningful offline.
 *
 * searchAll("") matches every row via LIKE '%%'; .first() takes one emission
 * (Room flows never complete, so collect would hang).
 */
object StatsComputer {
  suspend fun fromDb(dao: CardDao, meta: SyncMetaDao): StatsUi =
    fromList(dao.searchAll("").first(), meta, System.currentTimeMillis())

  internal suspend fun fromList(all: List<CardEntity>, meta: SyncMetaDao, now: Long): StatsUi {
    val active = all.count { !it.suspended && it.status != "MASTERED" }
    val mastered = all.count { it.status == "MASTERED" }
    val due = all.count { !it.suspended && (it.dueAt == null || it.dueAt <= now) }
    val denom = (active + mastered).coerceAtLeast(1)
    val ratings = all.mapNotNull { it.lastRating }.groupingBy { it }.eachCount()
    val hardTopics = all.filter { it.lastRating == "HARD" }
      .groupingBy { it.collection ?: "Untagged" }.eachCount()
      .entries.sortedByDescending { it.value }.take(5).map { it.key }
    val lastPull = meta.get("lastDuePull")?.value?.toLongOrNull()
    val lastFlush = meta.get("lastFlush")?.value?.toLongOrNull()
    return StatsUi(
      active = active,
      due = due,
      mastered = mastered,
      masteryRate = mastered.toDouble() / denom,
      ratings = ratings,
      hardTopics = hardTopics,
      lastPull = lastPull,
      lastFlush = lastFlush,
      pendingCount = all.count { it.pendingSync == 1 },
      degraded = isDegraded(lastPull, now)
    )
  }

  fun isDegraded(lastPull: Long?, now: Long): Boolean =
    lastPull == null || (now - lastPull) > 24 * 3_600_000
}

object ReminderText {
  fun body(due: Int, overdue: Int): String = "Daily Digest - $due items ($overdue overdue)"
}
