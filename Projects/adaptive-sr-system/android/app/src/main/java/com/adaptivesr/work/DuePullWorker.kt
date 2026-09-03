package com.adaptivesr.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adaptivesr.data.SrRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class DuePullWorker @AssistedInject constructor(
  @Assisted ctx: Context,
  @Assisted params: WorkerParameters,
  private val repo: SrRepository
) : CoroutineWorker(ctx, params) {
  override suspend fun doWork(): Result {
    return try {
      repo.pullDue()
      Result.success()
    } catch (e: Exception) {
      Result.retry()
    }
  }
}
