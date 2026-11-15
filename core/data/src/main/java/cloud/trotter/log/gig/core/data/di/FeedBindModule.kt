package cloud.trotter.log.gig.core.data.di

import cloud.trotter.log.gig.core.data.feed.AppFeedPolicy
import cloud.trotter.log.gig.core.data.feed.FeedPolicy
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** #1565: the feed channel's per-feature consent and source. Each consumer builds its own `FeedRepository`. */
@Module
@InstallIn(SingletonComponent::class)
abstract class FeedBindModule {

    @Binds
    @Singleton
    abstract fun bindFeedPolicy(impl: AppFeedPolicy): FeedPolicy
}
