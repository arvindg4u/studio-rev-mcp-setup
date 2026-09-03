package com.adaptivesr.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WorkerScheduler {
  fun schedulePeriodic(ctx: Context) {
    WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
      "due-pull", ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<DuePullWorker>(2, TimeUnit.HOURS).build()
    )
    WorkManager.getInstance(ctx).enqueueUniqueWork(
      "review-flush", ExistingWorkPolicy.APPEND_OR_REPLACE,
      OneTimeWorkRequestBuilder<ReviewFlushWorker>()
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
        .build()
    )
  }

  fun pullNow(ctx: Context) {
    WorkManager.getInstance(ctx).enqueueUniqueWork(
      "due-pull-now", ExistingWorkPolicy.REPLACE,
      OneTimeWorkRequestBuilder<DuePullWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()
    )
  }

  fun flushNow(ctx: Context) {
    WorkManager.getInstance(ctx).enqueueUniqueWork(
      "review-flush", ExistingWorkPolicy.APPEND,
      OneTimeWorkRequestBuilder<ReviewFlushWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()
    )
  }
}
