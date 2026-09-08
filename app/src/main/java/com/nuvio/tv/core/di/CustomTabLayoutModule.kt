package com.nuvio.tv.core.di

import android.content.Context
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
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
object CustomTabLayoutModule {

    @Provides
    @Singleton
    @Named("custom_tab_layout")
    fun provideCustomTabLayoutPreferenceDataStore(
        factory: ProfileDataStoreFactory,
        profileManager: ProfileManager
    ): LayoutPreferenceDataStore =
        LayoutPreferenceDataStore(factory, profileManager, "custom_tab_layout_settings")

    @Provides
    @Singleton
    @Named("custom_tab_cw_cache")
    fun provideCustomTabCwEnrichmentCache(
        @ApplicationContext context: Context,
        profileManager: ProfileManager
    ): ContinueWatchingEnrichmentCache =
        ContinueWatchingEnrichmentCache(context, profileManager, namespace = "custom_tab")

    @Provides
    @Singleton
    @Named("custom_tab_tmdb")
    fun provideCustomTabTmdbSettingsDataStore(
        factory: ProfileDataStoreFactory,
        profileManager: ProfileManager
    ): TmdbSettingsDataStore =
        TmdbSettingsDataStore(factory, profileManager, "custom_tab_tmdb_settings")

    @Provides
    @Singleton
    @Named("custom_tab_mdblist")
    fun provideCustomTabMdbListSettingsDataStore(
        factory: ProfileDataStoreFactory,
        profileManager: ProfileManager
    ): MDBListSettingsDataStore =
        MDBListSettingsDataStore(factory, profileManager, "custom_tab_mdblist_settings")
}