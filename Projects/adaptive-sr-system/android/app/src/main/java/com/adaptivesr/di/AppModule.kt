package com.adaptivesr.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.adaptivesr.data.TokenStore
import com.adaptivesr.data.remote.RaindropApi
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
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

/**
 * Runs [build], returning null instead of throwing. Keystore-backed crypto
 * init can fail on real devices (missing key managers, locked/broken
 * keystores); callers degrade to plaintext TokenStore rather than crashing
 * the process. Catches Throwable deliberately: this is the last line of
 * defense before a startup/worker crash.
 */
fun aeadOrNull(build: () -> Aead): Aead? = try {
  build()
} catch (t: Throwable) {
  null
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
  @Provides
  @Singleton
  fun provideAead(@ApplicationContext ctx: Context): Aead? = aeadOrNull {
    // Registers AesGcmKeyManager etc. Without this, AndroidKeysetManager.build()
    // throws "No key manager found for key type ...AesGcmKey" on first launch
    // (crashed DuePullWorker on 2026-09-03 per logcat).
    AeadConfig.register()
    AndroidKeysetManager.Builder()
      .withSharedPref(ctx, "adaptivesr_keyset", "adaptivesr_keyset_pref")
      .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
      .withMasterKeyUri("android-keystore://adaptivesr_master_key")
      .build()
      .keysetHandle
      .getPrimitive(Aead::class.java)
  }

  @Provides
  @Singleton
  fun provideDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
    PreferenceDataStoreFactory.create { ctx.filesDir.resolve("tokens.preferences_pb") }

  // Nullable Aead: TokenStore already degrades to plaintext storage when null,
  // so crypto-init failure costs encryption, never the process.
  @Provides
  @Singleton
  fun provideTokenStore(ds: DataStore<Preferences>, aead: Aead?): TokenStore =
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
