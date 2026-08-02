package com.wirefinder.di

import android.content.Context
import com.wirefinder.data.AppDatabase
import com.wirefinder.data.APNetworkDao
import com.wirefinder.util.NetworkMatcher
import com.wirefinder.util.NotificationService
import com.wirefinder.util.PreferencesManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@dagger.hilt.android.qualifiers.ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideApNetworkDao(database: AppDatabase): APNetworkDao {
        return database.apNetworkDao()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideNetworkMatcher(): NetworkMatcher {
        return NetworkMatcher()
    }

    @Provides
    @Singleton
    fun provideNotificationService(@dagger.hilt.android.qualifiers.ApplicationContext context: Context): NotificationService {
        return NotificationService(context)
    }

    @Provides
    @Singleton
    fun providePreferencesManager(@dagger.hilt.android.qualifiers.ApplicationContext context: Context): PreferencesManager {
        return PreferencesManager(context)
    }
}