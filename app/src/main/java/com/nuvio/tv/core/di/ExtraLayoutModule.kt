package com.nuvio.tv.core.di

import android.content.Context
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.ExtraAnimeSkipSettingsDataStore
import com.nuvio.tv.data.local.ExtraOpenSubtitlesDirectDataStore
import com.nuvio.tv.data.local.ExtraTvdbSettingsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.core.profile.ProfileManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ExtraLayoutModule {

    @Provides
    @Singleton
    @Named("extra_layout")
    fun provideExtraLayoutPreferenceDataStore(
        factory: ProfileDataStoreFactory,
        profileManager: ProfileManager
    ): LayoutPreferenceDataStore =
        LayoutPreferenceDataStore(factory, profileManager, "extra_layout_settings")

    @Provides
    @Singleton
    @Named("extra_cw_cache")
    fun provideExtraCwEnrichmentCache(
        @ApplicationContext context: Context,
        profileManager: ProfileManager
    ): ContinueWatchingEnrichmentCache =
        ContinueWatchingEnrichmentCache(context, profileManager, namespace = "extra")

    @Provides
    @Singleton
    @Named("extra_tmdb")
    fun provideExtraTmdbSettingsDataStore(
        factory: ProfileDataStoreFactory,
        profileManager: ProfileManager
    ): TmdbSettingsDataStore =
        TmdbSettingsDataStore(factory, profileManager, "extra_tmdb_settings")

    @Provides
    @Singleton
    @Named("extra_mdblist")
    fun provideExtraMdbListSettingsDataStore(
        factory: ProfileDataStoreFactory,
        profileManager: ProfileManager
    ): MDBListSettingsDataStore =
        MDBListSettingsDataStore(factory, profileManager, "extra_mdblist_settings")

    @Provides
    @Singleton
    fun provideExtraTvdbSettingsDataStore(
        factory: ProfileDataStoreFactory,
        profileManager: ProfileManager
    ): ExtraTvdbSettingsDataStore =
        ExtraTvdbSettingsDataStore(factory, profileManager)

    @Provides
    @Singleton
    fun provideExtraAnimeSkipSettingsDataStore(
        factory: ProfileDataStoreFactory,
        profileManager: ProfileManager
    ): ExtraAnimeSkipSettingsDataStore =
        ExtraAnimeSkipSettingsDataStore(factory, profileManager)

    @Provides
    @Singleton
    fun provideExtraOpenSubtitlesDirectDataStore(
        @ApplicationContext context: Context
    ): ExtraOpenSubtitlesDirectDataStore =
        ExtraOpenSubtitlesDirectDataStore(context)
}
