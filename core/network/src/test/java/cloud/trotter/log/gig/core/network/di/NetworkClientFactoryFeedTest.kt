package cloud.trotter.log.gig.core.network.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1565: the feed client logs nothing over HTTP, not even headers, since a request line carries the source's URL. */
class NetworkClientFactoryFeedTest {
    @Test fun `the feed profile has no HTTP logging interceptor in any build`() {
        assertEquals(emptyList<Any>(), NetworkClientFactory.okHttpClient("Feeds", ClientProfile.Feed).interceptors)
    }

    @Test fun `debug unit tests do install it on the other profiles, so the check above is not vacuous`() {
        assertTrue(NetworkClientFactory.okHttpClient("Census", ClientProfile.Census).interceptors.isNotEmpty())
    }
}
