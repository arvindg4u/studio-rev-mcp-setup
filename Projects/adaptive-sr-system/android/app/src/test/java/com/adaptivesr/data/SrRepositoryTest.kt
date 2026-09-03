package com.adaptivesr.data

import app.cash.turbine.test
import com.adaptivesr.core.*
import com.adaptivesr.data.local.*
import com.adaptivesr.data.remote.SupabaseRemoteDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric runner is required (plan deviation): androidRoomInMemory() goes
// through ApplicationProvider, which throws on a plain JVM unit test.
@RunWith(RobolectricTestRunner::class)
class SrRepositoryTest {
  @Test fun rateCardIsOptimisticAndFlushClearsInOrder() = runTest {
    val db = androidRoomInMemory()
    val dao = db.cardDao()
    dao.upsertAll(listOf(CardEntity(id = "1", title = "T", dueAt = 5), CardEntity(id = "2", title = "T2", dueAt = 6)))
    val calls = mutableListOf<String>()
    val remote = object : SupabaseRemoteDataSource {
      override suspend fun pullDue() = ApiResult.Ok(emptyList<CardEntity>())
      override suspend fun flushReview(cardId: String, rating: Rating, key: String): ApiResult<Unit> { calls += cardId; return ApiResult.Ok(Unit) }
      override suspend fun insertCard(card: CardEntity) = ApiResult.Ok(Unit)
      override suspend fun fetchStats() = ApiResult.Ok("{}")
      override suspend fun searchRemote(q: String) = ApiResult.Ok(emptyList<CardEntity>())
      override suspend fun setSrTag(raindropId: Long, enabled: Boolean) = ApiResult.Ok(Unit)
    }
    val repo = SrRepositoryImpl(dao, db.syncMetaDao(), remote, bg = coroutineContext)
    repo.rateCard("2", Rating.GOOD); repo.rateCard("1", Rating.HARD)
    repo.dueQueue().test { awaitItem() ; cancelAndIgnoreRemainingEvents() }
    repo.flushPending()
    assertEquals(listOf("1", "2"), calls)
    assertTrue(dao.pendingOrdered().isEmpty())
    db.close()
  }
}
