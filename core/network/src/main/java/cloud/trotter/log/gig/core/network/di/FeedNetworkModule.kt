package cloud.trotter.log.gig.core.network.di

import cloud.trotter.log.gig.core.network.feed.FeedClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** #1565: the one feed transport. It has no consumer yet; fuel (#1566) and rules (#640) inject it. */
@Module
@InstallIn(SingletonComponent::class)
object FeedNetworkModule {

    @Provides
    @Singleton
    fun provideFeedClient(): FeedClient = FeedClient(NetworkClientFactory.okHttpClient("Feeds", ClientProfile.Feed))
}
