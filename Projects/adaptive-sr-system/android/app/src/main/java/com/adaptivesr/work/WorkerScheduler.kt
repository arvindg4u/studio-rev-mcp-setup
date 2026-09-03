package com.adaptivesr.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
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

  // Daily pair: 8am digest + 2am drift check. Times are Asia/Kolkata wall
  // clock; the initial delay lands the first run on the next occurrence and
  // the 24h period holds it after that (exactness best-effort under Doze —
  // the FCM nudge in DuePushReceiver covers time-sensitive wakes).
  fun scheduleDaily(ctx: Context) {
    val wm = WorkManager.getInstance(ctx)
    wm.enqueueUniquePeriodicWork(
      "daily-reminder", ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
        .setInitialDelay(delayUntil(8, 0), TimeUnit.MILLISECONDS)
        .build()
    )
    wm.enqueueUniquePeriodicWork(
      "nightly-backup-check", ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<BackupCheckWorker>(24, TimeUnit.HOURS)
        .setInitialDelay(delayUntil(2, 0), TimeUnit.MILLISECONDS)
        .build()
    )
  }

  private fun delayUntil(hour: Int, minute: Int): Long {
    val zone = ZoneId.of("Asia/Kolkata")
    val now = ZonedDateTime.now(zone)
    var next = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    if (!next.isAfter(now)) next = next.plusDays(1)
    return Duration.between(now, next).toMillis()
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
