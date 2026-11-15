package cloud.trotter.log.gig.di

import android.content.Context
import cloud.trotter.feed.contract.Feed
import cloud.trotter.log.gig.BuildConfig
import cloud.trotter.log.gig.core.data.feed.FeedPolicy
import cloud.trotter.log.gig.core.data.feed.FeedRepository
import cloud.trotter.log.gig.core.data.feed.RulesFeedChecks
import cloud.trotter.log.gig.core.network.feed.FeedClient
import cloud.trotter.log.gig.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.log.gig.feed.RulesCandidate
import cloud.trotter.log.gig.feed.RulesFeed
import cloud.trotter.log.gig.feed.RulesFeedStager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Clock
import javax.inject.Singleton

/**
 * #640: the rules feed's channel. The cache is `noBackupFilesDir/feeds` (design-r3 § App client). The APK's own rules
 * generation (`FEED_SEQUENCE`, `FEED_DIGEST`) arrives with the publishing half (#641); until then [FeedRepository]
 * has no bundled mark, and gig.log's pin file holds no rules key, so no rules request is sent.
 */
@Module
@InstallIn(SingletonComponent::class)
object RulesFeedModule {

    @Provides
    @Singleton
    fun provideRulesFeedRepository(
        @ApplicationContext context: Context,
        interpreter: JsonRuleInterpreter,
        client: FeedClient,
        policy: FeedPolicy,
        clock: Clock,
    ): FeedRepository<RulesCandidate> = FeedRepository(
        feed = Feed.RULES,
        stager = RulesFeedStager(interpreter),
        client = client,
        policy = policy,
        root = File(context.noBackupFilesDir, "feeds"),
        clock = clock,
        buildTimeMs = BuildConfig.BUILD_TIME_MS,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RulesFeedBindModule {
    @Binds
    abstract fun bindRulesFeedChecks(impl: RulesFeed): RulesFeedChecks
}
