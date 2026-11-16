package cloud.trotter.log.gig.feed

import android.content.Context
import cloud.trotter.feed.contract.Feed
import cloud.trotter.feed.contract.FeedFiles
import cloud.trotter.feed.contract.FeedResult
import cloud.trotter.feed.contract.FeedSources
import cloud.trotter.feed.contract.VerifiedManifest
import cloud.trotter.log.gig.core.data.feed.FeedStage
import cloud.trotter.log.gig.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.log.gig.core.pipeline.rules.TestRulesFeed
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import cloud.trotter.log.gig.domain.pipeline.RuleEngineConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.ByteArrayInputStream

/**
 * #1630: remote shadow files stay ignored. The rules stager selects files named for a registered platform only, so a
 * `doordash_shadow` file in a signed manifest is never fetched, and one handed to it anyway is refused before compile.
 */
class RulesFeedStagerShadowTest {

    private val stager = RulesFeedStager(JsonRuleInterpreter(mock<Context>(), mock<RuleCapabilityGrants>()))

    private val shadowJson = """
        { "format_version": ${RuleEngineConstants.SHADOW_FORMAT_VERSION}, "platform_id": "doordash.driver", "screens": [
          { "id": "doordash.screen.idle_map", "priority": 5, "shadow": true,
            "require": { "exists": { "hasText": "Dash Now" } } } ] }
    """.trimIndent()
    private val liveJson = """{ "format_version": 2, "platform_id": "doordash.driver", "screens": [] }"""

    @Test
    fun `a signed manifest's shadow file is not selected`() {
        val files = listOf(TestRulesFeed.File("doordash", liveJson), TestRulesFeed.File("doordash_shadow", shadowJson))
        val manifest = (
            VerifiedManifest.verify(
                TestRulesFeed.trust(), FeedSources.GIG_LOG, Feed.RULES,
                ByteArrayInputStream(TestRulesFeed.envelope(TestRulesFeed.manifest(files))),
            ) as FeedResult.Ok
            ).value
        assertFalse(stager.readsFile("doordash_shadow"))
        assertEquals(listOf("doordash"), FeedFiles.select(manifest, stager::readsFile).map { it.name })
    }

    @Test
    fun `a shadow file handed to the stager is refused before compile`() {
        val shadow = TestRulesFeed.verifiedFiles(listOf(TestRulesFeed.File("doordash_shadow", shadowJson))).single()
        assertEquals(FeedStage.Rejected("not_a_rules_file"), stager.stage(listOf(shadow), carry = null))
    }
}
