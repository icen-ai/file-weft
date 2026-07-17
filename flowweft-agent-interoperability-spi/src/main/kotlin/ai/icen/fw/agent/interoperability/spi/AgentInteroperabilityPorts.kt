package ai.icen.fw.agent.interoperability.spi

import ai.icen.fw.agent.api.ProviderId
import java.util.concurrent.CompletionStage

/**
 * Read-only extension discovery. Implementations must not issue an untracked network call: remote
 * transport evidence is supplied through [AgentInteroperabilityCapabilityRequest.dispatchEvidence].
 */
interface AgentInteroperabilityCapabilityProvider {
    fun providerId(): ProviderId

    fun capabilities(
        request: AgentInteroperabilityCapabilityRequest,
    ): CompletionStage<AgentInteroperabilityCapabilityResult>
}

/**
 * Side-effect-free interoperability Doctor. It may inspect configuration and bounded aggregate
 * state, but must not dispatch, retry, reconcile, cancel, or mutate a remote operation.
 */
interface AgentInteroperabilityDoctor {
    fun providerId(): ProviderId

    fun inspect(request: AgentInteroperabilityDoctorRequest): CompletionStage<AgentInteroperabilityDoctorResult>
}
