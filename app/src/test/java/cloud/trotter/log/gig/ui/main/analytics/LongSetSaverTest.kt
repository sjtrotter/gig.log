package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** #1601: the receipt sheet's open-time suggestion and selection survive recreation, an empty set included. */
class LongSetSaverTest {
    private val scope = SaverScope { true }

    private fun roundTrip(value: Set<Long>): Set<Long> {
        val saved = with(longSetSaver) { scope.save(mutableStateOf(value)) }
        assertNotNull("an empty set must not be saved as nothing", saved)
        return longSetSaver.restore(saved!!)!!.value
    }

    @Test fun `an empty set is saved and restores empty`() = assertEquals(emptySet<Long>(), roundTrip(emptySet()))

    @Test fun `a set restores its ids`() = assertEquals(setOf(7L, 9L), roundTrip(setOf(9L, 7L)))
}
