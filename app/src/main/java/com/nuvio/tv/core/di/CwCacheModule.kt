package com.nuvio.tv.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object CwCacheModule {

    @Provides
    @Named("cw_enrichment_namespace")
    fun provideCwEnrichmentNamespace(): String = ""
}
