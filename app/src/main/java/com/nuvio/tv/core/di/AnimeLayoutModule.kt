package com.nuvio.tv.core.di

import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.core.profile.ProfileManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AnimeLayoutModule {

    @Provides
    @Singleton
    @Named("anime_layout")
    fun provideAnimeLayoutPreferenceDataStore(
        factory: ProfileDataStoreFactory,
        profileManager: ProfileManager
    ): LayoutPreferenceDataStore =
        LayoutPreferenceDataStore(factory, profileManager, "anime_layout_settings")
}
