package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowPrincipalRef

/**
 * Authenticated host context. The runtime never accepts a tenant or actor from an untrusted command
 * field; adapters construct this value only after authentication and tenant resolution.
 */
class WorkflowTrustedCallContext private constructor(
    tenantId: String,
    val actor: WorkflowPrincipalRef,
    authenticationId: String,
    authorityContextDigest: String,
) {
    val tenantId: String = WorkflowRuntimeSupport.text(
        tenantId,
        WorkflowRuntimeSupport.MAX_ID_BYTES,
        "Workflow trusted tenant id is invalid.",
    )
    val authenticationId: String = WorkflowRuntimeSupport.text(
        authenticationId,
        WorkflowRuntimeSupport.MAX_ID_BYTES,
        "Workflow authentication id is invalid.",
    )
    val authorityContextDigest: String = WorkflowRuntimeSupport.sha256(
        authorityContextDigest,
        "Workflow authority context digest is invalid.",
    )
    val contextDigest: String = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-trusted-context-v1")
        .text(this.tenantId)
        .text(actor.type)
        .text(actor.id)
        .text(this.authenticationId)
        .text(this.authorityContextDigest)
        .finish()

    override fun toString(): String = "WorkflowTrustedCallContext(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            actor: WorkflowPrincipalRef,
            authenticationId: String,
            authorityContextDigest: String,
        ): WorkflowTrustedCallContext = WorkflowTrustedCallContext(
            tenantId,
            actor,
            authenticationId,
            authorityContextDigest,
        )
    }
}
