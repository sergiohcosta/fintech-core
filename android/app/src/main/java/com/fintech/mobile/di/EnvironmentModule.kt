package com.fintech.mobile.di

import com.fintech.mobile.data.EnvironmentPreferences
import com.fintech.mobile.data.EnvironmentUrlProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EnvironmentModule {

    @Binds
    @Singleton
    abstract fun bindEnvironmentUrlProvider(environmentPreferences: EnvironmentPreferences): EnvironmentUrlProvider
}
