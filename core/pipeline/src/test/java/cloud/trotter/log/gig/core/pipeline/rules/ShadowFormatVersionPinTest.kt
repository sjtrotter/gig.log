package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.pipeline.RuleEngineConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1630: the matchers build writes the shadow file's `format_version` and the app's shadow reader accepts exactly
 * [RuleEngineConstants.SHADOW_FORMAT_VERSION]; the two builds share no code, so this holds them equal.
 */
class ShadowFormatVersionPinTest {

    @Test
    fun `the matchers build writes the version the shadow reader accepts`() {
        var dir = File(".").absoluteFile.normalize()
        while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile ?: error("could not locate the repo root")
        val script = File(dir, "matchers/build.gradle.kts").readText()
        val written = Regex("""val SHADOW_FORMAT_VERSION = (\d+)""").find(script)?.groupValues?.get(1)?.toInt()
        assertEquals(RuleEngineConstants.SHADOW_FORMAT_VERSION, written)
        assertTrue(RuleEngineConstants.SHADOW_FORMAT_VERSION > RuleEngineConstants.MAX_SUPPORTED_FORMAT_VERSION)
    }
}
