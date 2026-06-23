package com.slickstream.data.torrent.di

import com.slickstream.core.repository.TorrentStreamer
import com.slickstream.data.torrent.TorrentStreamerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the torrent streaming engine. [TorrentStreamerImpl], [com.slickstream.data.torrent.TorrentEngine]
 * and [com.slickstream.data.torrent.TorrentCacheManager] all have @Inject constructors and are
 * @Singleton, so no explicit @Provides are needed — Hilt constructs them directly.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TorrentModule {

    @Binds
    @Singleton
    abstract fun bindTorrentStreamer(impl: TorrentStreamerImpl): TorrentStreamer
}
