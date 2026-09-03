package com.adaptivesr.push

import android.app.Notification
import android.app.NotificationManager
import com.adaptivesr.data.TokenStore
import com.adaptivesr.data.local.CardDao
import com.adaptivesr.ui.stats.ReminderText
import com.adaptivesr.work.WorkerScheduler
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * FCM entry point. The `{due_count}` data payload is never trusted — it only
 * wakes us: we enqueue an expedited pull, then re-read Room for the real
 * counts before notifying on `sr-reminders`.
 */
@AndroidEntryPoint
class DuePushReceiver : FirebaseMessagingService() {
  @Inject lateinit var dao: CardDao
  @Inject lateinit var tokens: TokenStore

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onMessageReceived(msg: RemoteMessage) {
    WorkerScheduler.pullNow(this)
    scope.launch {
      val now = System.currentTimeMillis()
      val due = runCatching { dao.dueQueue(now).first() }.getOrDefault(emptyList())
      val overdue = due.count { it.dueAt != null && it.dueAt < now }
      val nm = getSystemService(NotificationManager::class.java) ?: return@launch
      nm.notify(
        1002,
        Notification.Builder(this@DuePushReceiver, "sr-reminders")
          .setContentTitle("Adaptive SR")
          .setContentText(ReminderText.body(due.size, overdue))
          .setSmallIcon(android.R.drawable.ic_dialog_info)
          .build()
      )
    }
  }

  override fun onNewToken(token: String) {
    scope.launch { runCatching { tokens.setFcmToken(token) } }
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }
}
