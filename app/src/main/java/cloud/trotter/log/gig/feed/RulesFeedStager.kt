package cloud.trotter.log.gig.feed

import cloud.trotter.feed.contract.VerifiedFeedFile
import cloud.trotter.log.gig.core.data.feed.FeedStage
import cloud.trotter.log.gig.core.data.feed.FeedStager
import cloud.trotter.log.gig.core.pipeline.rules.GenerationResult
import cloud.trotter.log.gig.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.log.gig.core.pipeline.rules.RuleFile
import cloud.trotter.log.gig.core.pipeline.rules.VerifiedRulesetBytes
import cloud.trotter.log.gig.domain.pipeline.RuleEngineConstants
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.serialization.json.JsonObject

/**
 * A rules generation the batch loader prepared and the feed has not yet made live (#640): its files, so a candidate
 * prepared on a generation that is no longer live can be prepared again, and the loader's [prepared] result.
 */
class RulesCandidate(
    val files: List<RuleFile.Remote>,
    val prepared: GenerationResult.Prepared,
    val sequence: Long,
)

/**
 * The rules feed's stage check (#640): every file through [VerifiedRulesetBytes.fromFeedFile], the only remote mint,
 * then the whole generation through the batch loader's [JsonRuleInterpreter.prepareGeneration], which compiles and
 * checks it on the bundled floor without touching the live rules. Nothing here activates; [RulesFeed] does, after the
 * feed channel commits. Rules carry no carry and no period.
 */
class RulesFeedStager(private val interpreter: JsonRuleInterpreter) : FeedStager<RulesCandidate> {
    override val contracts: Set<Long> = setOf(RuleEngineConstants.RULES_CONTRACT.toLong())

    /** A manifest file named for no platform this build carries is never fetched. */
    override fun readsFile(name: String): Boolean = Platform.fromWire(name)?.let { it != Platform.Unknown } == true

    override fun stage(files: List<VerifiedFeedFile>, carry: JsonObject?): FeedStage<RulesCandidate> = prepare(files)

    override fun restore(files: List<VerifiedFeedFile>, carry: JsonObject?): FeedStage<RulesCandidate> = prepare(files)

    private fun prepare(files: List<VerifiedFeedFile>): FeedStage<RulesCandidate> {
        if (files.isEmpty()) return FeedStage.Rejected("no_rules_files")
        val remote = files.map { file ->
            RuleFile.Remote(VerifiedRulesetBytes.fromFeedFile(file) ?: return FeedStage.Rejected("not_a_rules_file"))
        }
        return when (val result = interpreter.prepareGeneration(remote)) {
            is GenerationResult.Prepared ->
                FeedStage.Ready(RulesCandidate(remote, result, files.first().sequence), period = null, carry = null)
            is GenerationResult.Rejected -> FeedStage.Rejected("generation_rejected")
        }
    }
}
