package cloud.trotter.log.gig.core.pipeline.census

import cloud.trotter.log.gig.domain.capture.CensusSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #1634: whether a tap's window is read for click evidence. [armed] is the sink's live [CensusSink.clickEvidenceArmed]
 * (census on, `uinode.clicked.v1` in the sink's accepted ids, which drops it under a consent older than the disclosure
 * that names taps). The click path reads [isArmed] once per tap; while it is false nothing more is read.
 */
@Singleton
class ClickEvidenceArming internal constructor(val armed: StateFlow<Boolean>) {

    @Inject constructor(sink: CensusSink) : this(sink.clickEvidenceArmed)

    /** One read of [armed]; fail closed (a throwing source arms nothing). */
    fun isArmed(): Boolean = try {
        armed.value
    } catch (_: Exception) {
        false
    }

    companion object {
        /** Never armed: a pipeline built without the census reads no window. */
        val OFF = ClickEvidenceArming(MutableStateFlow(false))
    }
}
