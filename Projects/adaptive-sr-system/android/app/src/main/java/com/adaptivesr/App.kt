package com.adaptivesr

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class App : Application() {
  override fun onCreate() {
    super.onCreate()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel("due", "Due reviews", NotificationManager.IMPORTANCE_DEFAULT)
      getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
  }
}
