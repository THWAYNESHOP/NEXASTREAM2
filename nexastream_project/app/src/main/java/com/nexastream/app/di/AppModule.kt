package com.nexastream.app.di

import android.content.Context
import com.nexastream.app.database.AppDatabase
import com.nexastream.app.repositories.HomeRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    fun provideHomeRepository(
        @ApplicationContext context: Context,
        database: AppDatabase
    ): HomeRepository {
        return HomeRepository(context, database)
    }
}
