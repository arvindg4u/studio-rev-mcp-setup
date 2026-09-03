package com.adaptivesr.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adaptivesr.data.SrRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ReviewFlushWorker @AssistedInject constructor(
  @Assisted ctx: Context,
  @Assisted params: WorkerParameters,
  private val repo: SrRepository
) : CoroutineWorker(ctx, params) {
  override suspend fun doWork(): Result {
    return try {
      // flushPending() never throws for server envelopes (it records lastError
      // per row); only transport-level crashes reach here and merit a retry.
      repo.flushPending()
      Result.success()
    } catch (e: Exception) {
      Result.retry()
    }
  }
}
