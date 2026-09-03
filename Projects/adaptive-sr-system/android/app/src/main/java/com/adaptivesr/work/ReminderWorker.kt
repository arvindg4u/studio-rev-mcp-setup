package com.adaptivesr.work

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.adaptivesr.data.local.CardDao
import com.adaptivesr.ui.stats.ReminderText
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Daily 8am digest (scheduled via [WorkerScheduler.scheduleDaily] with the
 * initial delay computed to next 8am Asia/Kolkata). Counts come from Room —
 * never from any push payload — and post to channel `sr-reminders`.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
  @Assisted ctx: Context,
  @Assisted params: WorkerParameters,
  private val dao: CardDao
) : CoroutineWorker(ctx, params) {
  override suspend fun doWork(): Result {
    return try {
      val now = System.currentTimeMillis()
      val due = dao.dueQueue(now).first()
      val overdue = due.count { it.dueAt != null && it.dueAt < now }
      notifyBody(ReminderText.body(due.size, overdue))
      Result.success()
    } catch (e: Exception) {
      Result.retry()
    }
  }

  private fun notifyBody(body: String) {
    val ctx = applicationContext
    val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
    nm.notify(
      1001,
      Notification.Builder(ctx, "sr-reminders")
        .setContentTitle("Adaptive SR")
        .setContentText(body)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .build()
    )
  }
}
