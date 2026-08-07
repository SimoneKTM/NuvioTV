package com.nuvio.tv.core.di

import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TmdbSettingsDataStore
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

    @Provides
    @Singleton
    @Named("anime_tmdb")
    fun provideAnimeTmdbSettingsDataStore(
        factory: ProfileDataStoreFactory,
        profileManager: ProfileManager
    ): TmdbSettingsDataStore =
        TmdbSettingsDataStore(factory, profileManager, "anime_tmdb_settings")

    @Provides
    @Singleton
    @Named("anime_mdblist")
    fun provideAnimeMdbListSettingsDataStore(
        factory: ProfileDataStoreFactory,
        profileManager: ProfileManager
    ): MDBListSettingsDataStore =
        MDBListSettingsDataStore(factory, profileManager, "anime_mdblist_settings")
}
