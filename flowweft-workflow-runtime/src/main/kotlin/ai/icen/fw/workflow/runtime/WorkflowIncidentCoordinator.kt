package ai.icen.fw.workflow.runtime

import java.security.MessageDigest

/**
 * Authorized incident control plane. Reconciliation evidence is durable before this coordinator
 * is called; resolving it never repeats the provider call and binds the exact opaque result bytes.
 */
class WorkflowIncidentCoordinator(
    private val authorizationPort: WorkflowRuntimeAuthorizationPort,
    private val persistencePort: WorkflowIncidentPersistencePort,
) {
    fun resolveEffectIncident(
        context: WorkflowTrustedCallContext,
        incidentId: String,
        expectedEffectVersion: Long,
        result: WorkflowEffectJobStoredResult,
        repairDigest: String,
        resolvedAt: Long,
    ): WorkflowIncidentOperationResult {
        val loaded = try {
            persistencePort.loadEffectIncident(context.tenantId, incidentId, resolvedAt)
        } catch (_: RuntimeException) {
            return failed(WorkflowIncidentOperationCode.STORE_OUTCOME_UNKNOWN)
        } ?: return failed(WorkflowIncidentOperationCode.AUTHORIZATION_DENIED)
        if (loaded.tenantId != context.tenantId) {
            return failed(WorkflowIncidentOperationCode.AUTHORIZATION_DENIED)
        }
        val effect = loaded.effect
        val requestDigest = try {
            operationDigest(
                context,
                loaded,
                expectedEffectVersion,
                result,
                repairDigest,
                resolvedAt,
            )
        } catch (_: IllegalArgumentException) {
            return failed(WorkflowIncidentOperationCode.NOT_ELIGIBLE)
        }
        val authorizationRequest = WorkflowRuntimeAuthorizationRequest.of(
            context,
            WorkflowRuntimeAction.RESOLVE_EFFECT_INCIDENT,
            effect.intent.instanceId,
            effect.intent.definitionId,
            effect.intent.definitionRef,
            effect.intent.subject,
            requestDigest,
            resolvedAt,
        )
        val authorization = try {
            authorizationPort.authorize(authorizationRequest)
        } catch (_: RuntimeException) {
            return failed(WorkflowIncidentOperationCode.AUTHORIZATION_DENIED)
        }
        if (authorization.status != WorkflowRuntimeAuthorizationStatus.AUTHORIZED ||
            !authorization.matches(authorizationRequest, resolvedAt)
        ) {
            return failed(WorkflowIncidentOperationCode.AUTHORIZATION_DENIED)
        }
        val resolution = try {
            WorkflowEffectIncidentResolution.of(
                context.tenantId,
                loaded.incidentId,
                effect.intent.effectId,
                expectedEffectVersion,
                requestDigest,
                authorization,
                result,
                repairDigest,
                resolvedAt,
            )
        } catch (_: IllegalArgumentException) {
            return failed(WorkflowIncidentOperationCode.NOT_ELIGIBLE)
        }
        val persisted = try {
            persistencePort.resolveEffectIncident(resolution)
        } catch (_: RuntimeException) {
            return failed(WorkflowIncidentOperationCode.STORE_OUTCOME_UNKNOWN)
        }
        return if (persisted.code == WorkflowIncidentOperationCode.NOT_FOUND) {
            // Incident identifiers are opaque tenant capabilities. Missing and unauthorized are
            // deliberately indistinguishable at this application boundary.
            failed(WorkflowIncidentOperationCode.AUTHORIZATION_DENIED)
        } else {
            persisted
        }
    }

    private fun operationDigest(
        context: WorkflowTrustedCallContext,
        incident: WorkflowEffectIncidentSnapshot,
        expectedEffectVersion: Long,
        result: WorkflowEffectJobStoredResult,
        repairDigest: String,
        resolvedAt: Long,
    ): String {
        WorkflowRuntimeSupport.nonNegative(expectedEffectVersion, "Workflow incident expected version is invalid.")
        WorkflowRuntimeSupport.sha256(repairDigest, "Workflow incident repair digest is invalid.")
        WorkflowRuntimeSupport.nonNegative(resolvedAt, "Workflow incident resolution time is invalid.")
        val bytesDigest = MessageDigest.getInstance("SHA-256")
            .digest(result.bytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-incident-operation-v1")
            .text(context.contextDigest)
            .text(incident.incidentId)
            .text(incident.snapshotDigest)
            .text(incident.effect.intent.effectId)
            .longValue(expectedEffectVersion)
            .text(result.outcome.code)
            .text(result.resultType)
            .text(result.resultDigest)
            .text(bytesDigest)
            .longValue(result.retryAt ?: -1L)
            .longValue(result.completedAt)
            .text(repairDigest)
            .longValue(resolvedAt)
            .finish()
    }

    private fun failed(code: WorkflowIncidentOperationCode): WorkflowIncidentOperationResult =
        WorkflowIncidentOperationResult.failed(code)
}
