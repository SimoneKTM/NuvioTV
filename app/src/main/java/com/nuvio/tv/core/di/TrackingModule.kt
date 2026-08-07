package com.nuvio.tv.core.di

import com.nuvio.tv.data.simkl.AndroidSimklAuthStorage
import com.nuvio.tv.data.simkl.AndroidSimklSyncStorage
import com.nuvio.tv.data.simkl.SimklAuthStorage
import com.nuvio.tv.data.simkl.SimklApiSyncRemote
import com.nuvio.tv.data.simkl.SimklSyncRemote
import com.nuvio.tv.data.simkl.SimklSyncStorage
import com.nuvio.tv.data.anilist.AndroidAniListAuthStorage
import com.nuvio.tv.data.anilist.AndroidAniListSyncStorage
import com.nuvio.tv.data.anilist.AniListAuthStorage
import com.nuvio.tv.data.anilist.AniListSyncStorage
import com.nuvio.tv.data.kitsu.AndroidKitsuAuthStorage
import com.nuvio.tv.data.kitsu.AndroidKitsuSyncStorage
import com.nuvio.tv.data.kitsu.KitsuAuthStorage
import com.nuvio.tv.data.kitsu.KitsuSyncStorage
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.data.repository.TraktTrackingLibraryProvider
import com.nuvio.tv.data.repository.TraktTrackingProvider
import com.nuvio.tv.data.simkl.SimklLibraryService
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.data.repository.TraktTrackingHistoryWriter
import com.nuvio.tv.data.repository.TraktTrackingProgressProvider
import com.nuvio.tv.data.simkl.SimklTrackingHistoryWriter
import com.nuvio.tv.data.simkl.SimklTrackingProgressProvider
import com.nuvio.tv.data.simkl.SimklTrackingProvider
import com.nuvio.tv.data.anilist.AniListLibraryProvider
import com.nuvio.tv.data.anilist.AniListTrackingProvider
import com.nuvio.tv.data.kitsu.KitsuLibraryProvider
import com.nuvio.tv.data.kitsu.KitsuTrackingProvider
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TrackingModule {
    @Binds
    @Singleton
    abstract fun bindSimklAuthStorage(storage: AndroidSimklAuthStorage): SimklAuthStorage

    @Binds
    @IntoSet
    abstract fun bindSimklProfileScopedCredentialStore(
        storage: AndroidSimklAuthStorage
    ): ProfileScopedCredentialStore

    @Binds
    @Singleton
    abstract fun bindSimklSyncStorage(storage: AndroidSimklSyncStorage): SimklSyncStorage

    @Binds
    @Singleton
    abstract fun bindSimklSyncRemote(remote: SimklApiSyncRemote): SimklSyncRemote

    @Binds
    @Singleton
    abstract fun bindAniListAuthStorage(storage: AndroidAniListAuthStorage): AniListAuthStorage

    @Binds
    @IntoSet
    abstract fun bindAniListProfileScopedCredentialStore(
        storage: AndroidAniListAuthStorage
    ): ProfileScopedCredentialStore

    @Binds
    @Singleton
    abstract fun bindAniListSyncStorage(storage: AndroidAniListSyncStorage): AniListSyncStorage

    @Binds
    @Singleton
    abstract fun bindKitsuAuthStorage(storage: AndroidKitsuAuthStorage): KitsuAuthStorage

    @Binds
    @IntoSet
    abstract fun bindKitsuProfileScopedCredentialStore(
        storage: AndroidKitsuAuthStorage
    ): ProfileScopedCredentialStore

    @Binds
    @Singleton
    abstract fun bindKitsuSyncStorage(storage: AndroidKitsuSyncStorage): KitsuSyncStorage

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingLibraryProvider(
        provider: TraktTrackingLibraryProvider
    ): TrackingLibraryProvider

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingLibraryProvider(
        provider: SimklLibraryService
    ): TrackingLibraryProvider

    @Binds
    @IntoSet
    abstract fun bindAniListTrackingLibraryProvider(
        provider: AniListLibraryProvider
    ): TrackingLibraryProvider

    @Binds
    @IntoSet
    abstract fun bindKitsuTrackingLibraryProvider(
        provider: KitsuLibraryProvider
    ): TrackingLibraryProvider

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingProgressProvider(
        provider: TraktTrackingProgressProvider
    ): TrackingProgressProvider

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingProgressProvider(
        provider: SimklTrackingProgressProvider
    ): TrackingProgressProvider

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingHistoryWriter(
        writer: TraktTrackingHistoryWriter
    ): TrackingHistoryWriter

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingHistoryWriter(
        writer: SimklTrackingHistoryWriter
    ): TrackingHistoryWriter

    @Binds
    @IntoSet
    abstract fun bindTraktTrackingProvider(
        provider: TraktTrackingProvider
    ): TrackingProvider

    @Binds
    @IntoSet
    abstract fun bindSimklTrackingProvider(
        provider: SimklTrackingProvider
    ): TrackingProvider

    @Binds
    @IntoSet
    abstract fun bindAniListTrackingProvider(
        provider: AniListTrackingProvider
    ): TrackingProvider

    @Binds
    @IntoSet
    abstract fun bindKitsuTrackingProvider(
        provider: KitsuTrackingProvider
    ): TrackingProvider
}
