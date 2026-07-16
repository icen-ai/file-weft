package ai.icen.fw.workflow.api

import java.util.concurrent.CompletionStage

/**
 * Host/plugin boundary for resolving organization participants from a pinned snapshot.
 *
 * Implementations read host-owned HR/identity data; FlowWeft does not own or mutate that master
 * data. They must honor the request deadline, never evaluate caller-supplied scripts, and return a
 * value-free error reason instead of leaking a vendor exception. The runtime must reject nulls,
 * exceptional stages, late responses, mismatched digests/authority/revision and responses not
 * produced by the configured resolver invocation. Production callers must additionally require an
 * authorization-bound request; this SPI result is never authorization evidence on its own.
 */
fun interface WorkflowParticipantResolver {
    fun resolve(request: WorkflowParticipantResolutionRequest): CompletionStage<WorkflowParticipantResolution>
}
