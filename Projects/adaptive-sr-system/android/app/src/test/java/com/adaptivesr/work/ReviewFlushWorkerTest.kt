package com.adaptivesr.work

import com.adaptivesr.core.ApiResult
import com.adaptivesr.core.ErrorCode
import com.adaptivesr.core.Rating
import com.adaptivesr.data.SrRepositoryImpl
import com.adaptivesr.data.androidRoomInMemory
import com.adaptivesr.data.local.CardEntity
import com.adaptivesr.data.remote.SupabaseRemoteDataSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReviewFlushWorkerTest {
  @Test fun alreadyProcessedClearsFlag() = runTest {
    val db = androidRoomInMemory()
    db.cardDao().upsertAll(listOf(CardEntity(id = "x", title = "X", pendingSync = 1, lastRating = "GOOD", idempotencyKey = "k1")))
    val remote = object : SupabaseRemoteDataSource {
      override suspend fun pullDue() = ApiResult.Ok(emptyList<CardEntity>())
      override suspend fun flushReview(c: String, r: Rating, k: String): ApiResult<Unit> =
        ApiResult.Err(ErrorCode.ALREADY_PROCESSED)
      override suspend fun insertCard(card: CardEntity) = ApiResult.Ok(Unit)
      override suspend fun fetchStats() = ApiResult.Ok("{}")
      override suspend fun searchRemote(q: String) = ApiResult.Ok(emptyList<CardEntity>())
      override suspend fun setSrTag(raindropId: Long, enabled: Boolean) = ApiResult.Ok(Unit)
    }
    SrRepositoryImpl(db.cardDao(), db.syncMetaDao(), remote, backgroundScope.coroutineContext).flushPending()
    assertTrue(db.cardDao().pendingOrdered().isEmpty())
    db.close()
  }
}
