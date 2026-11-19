package cloud.trotter.log.gig.core.network.di

import cloud.trotter.log.gig.core.network.feed.FeedClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** #1565: the one feed transport. The rules (#640) and fuel (#1566) feed channels inject it. */
@Module
@InstallIn(SingletonComponent::class)
object FeedNetworkModule {

    @Provides
    @Singleton
    fun provideFeedClient(): FeedClient = FeedClient(NetworkClientFactory.okHttpClient("Feeds", ClientProfile.Feed))
}
