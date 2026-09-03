package com.adaptivesr.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Singleton

/**
 * Base client. Auth is injected per-call from [com.adaptivesr.data.TokenStore]
 * (bearer header override in SupabaseRemoteDataSource, Task 2) because the
 * paste-your-JWT token can change at runtime while this singleton cannot.
 */
@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {
  @Provides
  @Singleton
  fun provideSupabaseClient(): SupabaseClient =
    createSupabaseClient(
      supabaseUrl = "https://placeholder.supabase.co",
      supabaseKey = "placeholder-key"
    ) {
      httpEngine = OkHttp.create()
      install(Auth)
      install(Postgrest)
    }
}
