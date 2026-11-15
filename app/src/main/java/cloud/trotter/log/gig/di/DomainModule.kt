package cloud.trotter.log.gig.di

import android.os.SystemClock
import cloud.trotter.log.gig.domain.evaluation.OfferEvaluator
import cloud.trotter.log.gig.domain.time.ElapsedRealtime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DomainModule {

    @Provides
    @Singleton
    fun provideOfferEvaluator(): OfferEvaluator {
        // Because it has an empty constructor, it's trivial to build!
        return OfferEvaluator()
    }

    /**
     * The wall clock (#1271): the classifier, the side-effect engine, the event repo, the snapshot
     * store, the state manager and the offer-action receiver read their instants through it. System
     * UTC is exactly what `System.currentTimeMillis()` returned — only millis are read, never the
     * zone — so this binding changes no production behaviour; it exists so the end-to-end harness
     * can bind a virtual clock.
     */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    /** #640: elapsed time since boot, counting sleep; intervals measured with it survive a wall-clock correction. */
    @Provides
    @Singleton
    fun provideElapsedRealtime(): ElapsedRealtime = ElapsedRealtime { SystemClock.elapsedRealtime() }

    // Future Note: As you build more pure Domain classes (like UseCases),
    // you will just add more @Provides functions to this exact file.
}