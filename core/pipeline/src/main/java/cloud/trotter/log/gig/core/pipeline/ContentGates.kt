package cloud.trotter.log.gig.core.pipeline

import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.ParsedFields
import timber.log.Timber

/**
 * THE content gate (#399): sensitive and noise observations are dropped
 * before capture and before the state machine — pledge: never store or
 * forward sensitive screens/notifications. One definition shared by both
 * sensor pipelines, so the gates can't drift apart again.
 */
internal fun passesContentGates(obs: Observation.FlowObservation): Boolean {
    val isSensitive = obs.parsed is ParsedFields.SensitiveFields
    val isNoise = obs.parsed is ParsedFields.NoiseFields
    if (isSensitive) Timber.d("Sensitive gate: dropped %s", obs.target)
    if (isNoise) Timber.v("Noise gate: dropped %s", obs.target)
    return passesContentGates(obs.parsed)
}

/**
 * The gate's predicate alone, for a parse that is not an observation (#1634: the classification of a tap's own window,
 * which must refuse exactly what the gate drops). Logs nothing.
 */
internal fun passesContentGates(parsed: ParsedFields): Boolean =
    parsed !is ParsedFields.SensitiveFields && parsed !is ParsedFields.NoiseFields
