package com.adaptivesr.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.adaptivesr.data.TokenStore
import com.adaptivesr.data.remote.RaindropApi
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
  @Provides
  @Singleton
  fun provideAead(@ApplicationContext ctx: Context): Aead =
    AndroidKeysetManager.Builder()
      .withSharedPref(ctx, "adaptivesr_keyset", "adaptivesr_keyset_pref")
      .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
      .withMasterKeyUri("android-keystore://adaptivesr_master_key")
      .build()
      .keysetHandle
      .getPrimitive(Aead::class.java)

  @Provides
  @Singleton
  fun provideDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create { ctx.filesDir.resolve("tokens.preferences_pb") }

  @Provides
  @Singleton
  fun provideTokenStore(ds: DataStore<Preferences>, aead: Aead): TokenStore =
    TokenStore(ds, aead)

  @Provides
  @Singleton
  fun provideMoshi(): Moshi = Moshi.Builder().build()

  @Provides
  @Singleton
  fun provideRaindropRetrofit(moshi: Moshi): Retrofit =
    Retrofit.Builder()
      .baseUrl("https://api.raindrop.io/")
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()

  @Provides
  @Singleton
  fun provideRaindropApi(retrofit: Retrofit): RaindropApi =
    retrofit.create(RaindropApi::class.java)

  @Provides
  fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
