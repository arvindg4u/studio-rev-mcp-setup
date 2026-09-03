package com.adaptivesr.data

import com.adaptivesr.core.ApiResult
import com.adaptivesr.core.ErrorCode
import com.adaptivesr.core.Rating
import com.adaptivesr.core.Sm2
import com.adaptivesr.data.local.CardDao
import com.adaptivesr.data.local.CardEntity
import com.adaptivesr.data.local.SyncMeta
import com.adaptivesr.data.local.SyncMetaDao
import com.adaptivesr.data.remote.SupabaseRemoteDataSource
import com.adaptivesr.di.ApplicationScope
import com.adaptivesr.ui.stats.StatsUi
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Plain carrier: title travels with the id so the repo can build the outbox
// row without a second fetch. UI-layer RaindropItem (Task 5) maps to it 1:1.
data class RaindropItemRef(val itemId: Long, val title: String, val link: String?)

// Full interface frozen here. Task 5 implements setRaindropEnabled /
// enqueueRaindropIfAbsent bodies but adds no new repo/DAO/remote methods.
interface SrRepository {
  fun dueQueue(): Flow<List<CardEntity>>
  fun searchAll(q: String): Flow<List<CardEntity>>
  fun stats(): Flow<StatsUi>
  suspend fun rateCard(id: String, rating: Rating)
  suspend fun enqueueAdd(title: String, link: String?)
  suspend fun enqueueRaindropIfAbsent(item: RaindropItemRef)
  suspend fun setRaindropEnabled(item: RaindropItemRef, enabled: Boolean)
  suspend fun pullDue()
  suspend fun flushPending()
}

class SrRepositoryImpl @Inject constructor(
  private val dao: CardDao,
  private val meta: SyncMetaDao,
  private val remote: SupabaseRemoteDataSource,
  @ApplicationScope private val bg: CoroutineContext
) : SrRepository {

  override fun dueQueue(): Flow<List<CardEntity>> =
    dao.dueQueue(System.currentTimeMillis())

  override fun searchAll(q: String): Flow<List<CardEntity>> = dao.searchAll(q)

  override fun stats(): Flow<StatsUi> = dueQueue().map { list ->
    StatsUi(
      due = list.size,
      pendingCount = list.count { it.pendingSync == 1 },
      lastPull = meta.get("lastDuePull")?.value?.toLongOrNull(),
      lastFlush = meta.get("lastFlush")?.value?.toLongOrNull()
    )
  }

  override suspend fun rateCard(id: String, rating: Rating) {
    val c = dao.byId(id) ?: return
    val key = UUID.randomUUID().toString()
    val next = Sm2.preview(c.intervalDays, c.reviewCount, rating)
    dao.upsertAll(
      listOf(
        c.copy(
          intervalDays = next,
          lastRating = rating.name,
          pendingSync = 1,
          idempotencyKey = key,
          lastReviewedAt = System.currentTimeMillis(),
          status = if (rating == Rating.MASTER) "MASTERED" else c.status,
          suspended = if (rating == Rating.MASTER) true else c.suspended
        )
      )
    )
  }

  override suspend fun enqueueAdd(title: String, link: String?) {
    dao.upsertAll(
      listOf(
        CardEntity(
          id = UUID.randomUUID().toString(),
          title = title,
          link = link,
          source = "APP",
          pendingSync = 1,
          idempotencyKey = UUID.randomUUID().toString()
        )
      )
    )
    // Best-effort: network failure keeps the pendingSync=1 flag for the next flush.
    runCatching { flushPending() }
  }

  override suspend fun enqueueRaindropIfAbsent(item: RaindropItemRef) {
    if (dao.byRaindrop(item.itemId) != null) return
    // Outbox row keyed on the UNIQUE(raindropId) constraint: if J4 inserts the same
    // raindrop concurrently, Room REPLACE on conflict keeps one row and the server-side
    // unique(raindrop_id) is the final dedupe authority on flush (conflict → ALREADY_PROCESSED clears flag).
    dao.upsertAll(listOf(CardEntity(id = UUID.randomUUID().toString(), title = item.title, link = item.link, source = "RAINDROP", raindropId = item.itemId, pendingSync = 1, idempotencyKey = UUID.randomUUID().toString())))
  }

  override suspend fun setRaindropEnabled(item: RaindropItemRef, enabled: Boolean) {
    if (enabled) enqueueRaindropIfAbsent(item)
    // toggle-off: intentionally no delete, card stays with pendingSync unchanged
    when (val r = remote.setSrTag(item.itemId, enabled)) {
      is ApiResult.Ok -> Unit
      is ApiResult.Err -> dao.byRaindrop(item.itemId)?.let { dao.markPending(it.id, it.pendingSync, r.code.name) }
    }
  }

  override suspend fun pullDue() {
    when (val r = remote.pullDue()) {
      is ApiResult.Ok -> {
        // Never overwrite local optimistic rows: pendingSync=1 rows stay as-is
        // until the serial flush resolves them server-side.
        val pendingIds = dao.pendingOrdered().map { it.id }.toSet()
        dao.upsertAll(r.data.filter { it.id !in pendingIds })
        meta.put(SyncMeta("lastDuePull", System.currentTimeMillis().toString()))
      }
      is ApiResult.Err -> meta.put(SyncMeta("lastPullError", r.code.name))
    }
  }

  override suspend fun flushPending() {
    for (c in dao.pendingOrdered()) {
      when (val r = remote.flushReview(c.id, Rating.valueOf(c.lastRating ?: "GOOD"), c.idempotencyKey ?: UUID.randomUUID().toString())) {
        is ApiResult.Ok -> dao.markFlushed(c.id, c.idempotencyKey ?: "")
        is ApiResult.Err -> when (r.code) {
          ErrorCode.ALREADY_PROCESSED -> dao.markFlushed(c.id, c.idempotencyKey ?: "")
          ErrorCode.INVALID_RATING, ErrorCode.NOT_FOUND -> {
            dao.markPending(c.id, 0, r.code.name); pullDue()
          }
          else -> dao.markPending(c.id, 1, r.code.name)
        }
      }
    }
    meta.put(SyncMeta("lastFlush", System.currentTimeMillis().toString()))
  }
}
