package com.adaptivesr.di

import com.adaptivesr.data.SrRepository
import com.adaptivesr.data.SrRepositoryImpl
import com.adaptivesr.data.remote.SupabaseRemoteDataSource
import com.adaptivesr.data.remote.SupabaseRemoteDataSourceImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
  @Binds
  @Singleton
  abstract fun bindSrRepository(impl: SrRepositoryImpl): SrRepository

  @Binds
  @Singleton
  abstract fun bindRemote(impl: SupabaseRemoteDataSourceImpl): SupabaseRemoteDataSource
}
