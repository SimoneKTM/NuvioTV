package com.nuvio.tv.core.player

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Lets composables reach the singleton [SeekThumbnailGenerator] without a ViewModel. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SeekThumbnailEntryPoint {
    fun seekThumbnailGenerator(): SeekThumbnailGenerator
}
