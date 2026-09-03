package com.adaptivesr.di

import android.content.Context
import androidx.room.Room
import com.adaptivesr.data.local.AppDb
import com.adaptivesr.data.local.CardDao
import com.adaptivesr.data.local.SyncMetaDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object DbModule {
  @Provides
  @Singleton
  fun provideDb(@ApplicationContext ctx: Context): AppDb =
    Room.databaseBuilder(ctx, AppDb::class.java, "adaptivesr.db").build()

  @Provides
  fun provideCardDao(db: AppDb): CardDao = db.cardDao()

  @Provides
  fun provideSyncMetaDao(db: AppDb): SyncMetaDao = db.syncMetaDao()

  @Provides
  @ApplicationScope
  fun provideApplicationScope(): CoroutineContext = SupervisorJob() + Dispatchers.IO
}
