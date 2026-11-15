package cloud.trotter.log.gig.core.data.feed

import cloud.trotter.feed.contract.Feed
import cloud.trotter.log.gig.core.network.feed.FeedClient
import cloud.trotter.log.gig.core.network.feed.FeedGate
import cloud.trotter.log.gig.core.network.feed.FeedReply
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exchange reads consent and source at that moment, not a flag a watcher sets later. No watcher runs in these
 * tests at all: the revocation lands between the request's suspending check and its network exchange, the window a
 * watcher that has not been dispatched yet would leave open.
 */
class RefreshGateTest {
    private val fake = FeedTestServer()
    private val client = FeedClient(fake.client) { }
    private val fuel = SignedSource(Feed.FUEL)
    private val policy = FakeFeedPolicy(enabled = true, source = fuel.source)
    private val sha = "ab".repeat(32)

    @After fun tearDown() = fake.close()

    /** The real gate's snapshot at the exchange, with [between] run after its suspending check passed. */
    private fun revokedBetween(gate: RefreshGate, between: () -> Unit) = object : FeedGate {
        override suspend fun allows(): Boolean = gate.allows().also { between() }
        override fun isOpen(): Boolean = gate.isOpen()
    }

    @Test fun `consent turned off after the check closes the next exchange`() = runBlocking {
        val gate = RefreshGate(Feed.FUEL, policy, fuel.source, grant = null)
        assertTrue(gate.isOpen())
        val reply = client.payload(fuel.source, Feed.FUEL, sha, 2, revokedBetween(gate) { policy.enabled.value = false })
        assertEquals(FeedReply.Denied, reply)
        assertEquals(0, fake.server.requestCount)
        assertFalse(gate.allows())
    }

    @Test fun `a source changed after the check closes the next exchange`() = runBlocking {
        val gate = RefreshGate(Feed.FUEL, policy, fuel.source, grant = null)
        val reply = client.payload(fuel.source, Feed.FUEL, sha, 2, revokedBetween(gate) { policy.source.value = SignedSource(Feed.FUEL).source })
        assertEquals(FeedReply.Denied, reply)
        assertEquals(0, fake.server.requestCount)
        assertFalse(gate.allows())
    }

    @Test fun `with a grant the switch does not matter, but the source and the grant's life do`() = runBlocking {
        policy.enabled.value = false
        val grant = OneShotGrant.forUserAct(Feed.FUEL, fuel.source)
        assertTrue(grant.claim(Feed.FUEL, fuel.source.id))
        val gate = RefreshGate(Feed.FUEL, policy, fuel.source, grant)
        assertTrue(gate.isOpen())
        grant.spend()
        assertFalse(gate.isOpen())
        assertFalse(gate.allows())
    }
}
