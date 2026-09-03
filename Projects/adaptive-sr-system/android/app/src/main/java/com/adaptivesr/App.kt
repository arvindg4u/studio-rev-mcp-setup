package com.adaptivesr

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.adaptivesr.work.WorkerScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : Application(), Configuration.Provider {
  @Inject lateinit var workerFactory: HiltWorkerFactory

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

  override fun onCreate() {
    super.onCreate()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = getSystemService(NotificationManager::class.java)
      nm?.createNotificationChannel(NotificationChannel("due", "Due reviews", NotificationManager.IMPORTANCE_DEFAULT))
      nm?.createNotificationChannel(NotificationChannel("sr-reminders", "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
    }
    WorkerScheduler.schedulePeriodic(this)
    WorkerScheduler.scheduleDaily(this)
  }
}
