package cloud.trotter.log.gig.di

import android.content.Context
import cloud.trotter.feed.contract.Feed
import cloud.trotter.log.gig.BuildConfig
import cloud.trotter.log.gig.core.data.feed.FeedPolicy
import cloud.trotter.log.gig.core.data.feed.FeedRepository
import cloud.trotter.log.gig.core.data.fuel.FuelFeedStager
import cloud.trotter.log.gig.core.network.feed.FeedClient
import cloud.trotter.log.gig.domain.fuel.FuelPrices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Clock
import javax.inject.Singleton

/**
 * #1566: the fuel feed's channel, beside the rules one. The cache is `noBackupFilesDir/feeds`; fuel has no bundled
 * generation. Its consent is "Auto gas price" (`FuelFeedSwitch`, through [FeedPolicy]).
 */
@Module
@InstallIn(SingletonComponent::class)
object FuelFeedModule {

    @Provides
    @Singleton
    fun provideFuelFeedRepository(
        @ApplicationContext context: Context,
        client: FeedClient,
        policy: FeedPolicy,
        clock: Clock,
    ): FeedRepository<FuelPrices> = FeedRepository(
        feed = Feed.FUEL,
        stager = FuelFeedStager(),
        client = client,
        policy = policy,
        root = File(context.noBackupFilesDir, "feeds"),
        clock = clock,
        buildTimeMs = BuildConfig.BUILD_TIME_MS,
    )
}
