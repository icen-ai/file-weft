package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.domain.WorkflowEffectCode
import ai.icen.fw.workflow.domain.WorkflowEffectIntent
import ai.icen.fw.workflow.domain.WorkflowHumanDecisionAuthorizationReceipt
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class WorkflowIncidentCoordinatorTest {
    @Test
    fun `authorized repair binds exact result bytes and resolves without repeating provider call`() {
        val authorization = RecordingAuthorization(true)
        val persistence = RecordingPersistence(openIncident())
        val coordinator = WorkflowIncidentCoordinator(authorization, persistence)
        val result = storedResult("trusted-result".toByteArray())

        val resolved = coordinator.resolveEffectIncident(
            context(),
            INCIDENT_ID,
            EFFECT_VERSION,
            result,
            DIGEST_REPAIR,
            12L,
        )

        assertSame(WorkflowIncidentOperationCode.RESOLVED, resolved.code)
        assertSame(WorkflowIncidentStatus.RESOLVED, resolved.incident!!.status)
        assertEquals(result, persistence.lastResolution!!.result)
        assertEquals(DIGEST_REPAIR, persistence.lastResolution!!.repairDigest)
        assertSame(WorkflowRuntimeAction.RESOLVE_EFFECT_INCIDENT, authorization.lastRequest!!.action)
    }

    @Test
    fun `missing denied and unknown outcomes fail closed before persistence mutation`() {
        val missingPort = RecordingPersistence(null)
        val missing = WorkflowIncidentCoordinator(RecordingAuthorization(true), missingPort)
            .resolveEffectIncident(context(), INCIDENT_ID, EFFECT_VERSION, storedResult(), DIGEST_REPAIR, 12L)
        assertSame(WorkflowIncidentOperationCode.AUTHORIZATION_DENIED, missing.code)
        assertNull(missingPort.lastResolution)

        val deniedPort = RecordingPersistence(openIncident())
        val denied = WorkflowIncidentCoordinator(RecordingAuthorization(false), deniedPort)
            .resolveEffectIncident(context(), INCIDENT_ID, EFFECT_VERSION, storedResult(), DIGEST_REPAIR, 12L)
        assertSame(WorkflowIncidentOperationCode.AUTHORIZATION_DENIED, denied.code)
        assertNull(deniedPort.lastResolution)

        val unknownPort = RecordingPersistence(openIncident())
        val unknown = WorkflowEffectJobStoredResult.of(
            WorkflowEffectObservedOutcome.OUTCOME_UNKNOWN,
            "unknown-result",
            DIGEST_RESULT,
            byteArrayOf(1),
            null,
            9L,
        )
        val rejected = WorkflowIncidentCoordinator(RecordingAuthorization(true), unknownPort)
            .resolveEffectIncident(context(), INCIDENT_ID, EFFECT_VERSION, unknown, DIGEST_REPAIR, 12L)
        assertSame(WorkflowIncidentOperationCode.NOT_ELIGIBLE, rejected.code)
        assertNull(unknownPort.lastResolution)
    }

    @Test
    fun `authorization digest changes when opaque result bytes change`() {
        val firstAuthorization = RecordingAuthorization(false)
        WorkflowIncidentCoordinator(firstAuthorization, RecordingPersistence(openIncident()))
            .resolveEffectIncident(
                context(), INCIDENT_ID, EFFECT_VERSION,
                storedResult("first".toByteArray()), DIGEST_REPAIR, 12L,
            )
        val secondAuthorization = RecordingAuthorization(false)
        WorkflowIncidentCoordinator(secondAuthorization, RecordingPersistence(openIncident()))
            .resolveEffectIncident(
                context(), INCIDENT_ID, EFFECT_VERSION,
                storedResult("second".toByteArray()), DIGEST_REPAIR, 12L,
            )

        assertNotEquals(
            assertNotNull(firstAuthorization.lastRequest).requestDigest,
            assertNotNull(secondAuthorization.lastRequest).requestDigest,
        )
    }

    private class RecordingAuthorization(private val allowed: Boolean) : WorkflowRuntimeAuthorizationPort {
        var lastRequest: WorkflowRuntimeAuthorizationRequest? = null

        override fun authorize(request: WorkflowRuntimeAuthorizationRequest): WorkflowRuntimeAuthorizationDecision {
            lastRequest = request
            return WorkflowRuntimeAuthorizationDecision.of(
                "authorization-1",
                request.callContext.tenantId,
                request.callContext.actor,
                request.action,
                request.instanceId,
                request.requestDigest,
                if (allowed) WorkflowRuntimeAuthorizationStatus.AUTHORIZED else WorkflowRuntimeAuthorizationStatus.DENIED,
                "authority-r1",
                DIGEST_AUTHORITY,
                request.evaluatedAt,
                request.evaluatedAt + 100L,
            )
        }

        override fun issueHumanDecisionReceipt(
            request: WorkflowRuntimeHumanDecisionReceiptRequest,
        ): WorkflowHumanDecisionAuthorizationReceipt = throw UnsupportedOperationException()
    }

    private class RecordingPersistence(
        private var snapshot: WorkflowEffectIncidentSnapshot?,
    ) : WorkflowIncidentPersistencePort {
        var lastResolution: WorkflowEffectIncidentResolution? = null

        override fun loadEffectIncident(
            tenantId: String,
            incidentId: String,
            readAt: Long,
        ): WorkflowEffectIncidentSnapshot? = snapshot?.takeIf {
            it.tenantId == tenantId && it.incidentId == incidentId
        }

        override fun resolveEffectIncident(
            request: WorkflowEffectIncidentResolution,
        ): WorkflowIncidentOperationResult {
            lastResolution = request
            val current = requireNotNull(snapshot)
            val resolvedEffect = WorkflowEffectRecord.restore(
                current.effect.intent,
                WorkflowEffectDeliveryStatus.SUCCEEDED,
                current.effect.version + 1L,
                current.effect.attempt,
                null,
                null,
                null,
                current.effect.checkpointSequence,
                current.effect.checkpointDigest,
                request.result.resultDigest,
                request.resolvedAt,
            )
            val resolved = WorkflowEffectIncidentSnapshot.restore(
                current.incidentId,
                current.tenantId,
                current.instanceId,
                current.incidentCode,
                WorkflowIncidentStatus.RESOLVED,
                current.evidenceDigest,
                request.repairDigest,
                current.occurredAt,
                request.resolvedAt,
                resolvedEffect,
            )
            snapshot = resolved
            return WorkflowIncidentOperationResult.resolved(resolved)
        }
    }

    private companion object {
        const val TENANT = "tenant-a"
        const val INSTANCE = "instance-a"
        const val EFFECT_ID = "effect-a"
        const val INCIDENT_ID = "incident-a"
        const val EFFECT_VERSION = 4L
        const val DIGEST_DEFINITION = "1111111111111111111111111111111111111111111111111111111111111111"
        const val DIGEST_SUBJECT = "2222222222222222222222222222222222222222222222222222222222222222"
        const val DIGEST_PAYLOAD = "3333333333333333333333333333333333333333333333333333333333333333"
        const val DIGEST_UNKNOWN = "4444444444444444444444444444444444444444444444444444444444444444"
        const val DIGEST_EVIDENCE = "5555555555555555555555555555555555555555555555555555555555555555"
        const val DIGEST_REPAIR = "6666666666666666666666666666666666666666666666666666666666666666"
        const val DIGEST_RESULT = "7777777777777777777777777777777777777777777777777777777777777777"
        const val DIGEST_AUTHORITY = "8888888888888888888888888888888888888888888888888888888888888888"

        fun context(): WorkflowTrustedCallContext = WorkflowTrustedCallContext.of(
            TENANT,
            WorkflowPrincipalRef.of("user", "operator-a"),
            "authentication-a",
            DIGEST_AUTHORITY,
        )

        fun storedResult(payload: ByteArray = byteArrayOf(7)): WorkflowEffectJobStoredResult =
            WorkflowEffectJobStoredResult.of(
                WorkflowEffectObservedOutcome.SUCCEEDED,
                "service-result",
                DIGEST_RESULT,
                payload,
                null,
                9L,
            )

        fun openIncident(): WorkflowEffectIncidentSnapshot {
            val intent = WorkflowEffectIntent.of(
                EFFECT_ID,
                WorkflowEffectCode.SERVICE_TASK,
                TENANT,
                INSTANCE,
                "definition-a",
                WorkflowDefinitionRef.of("service-flow", "v1", DIGEST_DEFINITION),
                WorkflowSubjectSnapshot.of(
                    WorkflowSubjectRef.of("document", "document-a"),
                    "subject-r1",
                    DIGEST_SUBJECT,
                ),
                "token-a",
                "execution-a",
                null,
                "service-a",
                null,
                DIGEST_PAYLOAD,
                1L,
            )
            val effect = WorkflowEffectRecord.restore(
                intent,
                WorkflowEffectDeliveryStatus.RECONCILIATION_INCIDENT,
                EFFECT_VERSION,
                1,
                null,
                null,
                null,
                0L,
                null,
                DIGEST_UNKNOWN,
                8L,
            )
            return WorkflowEffectIncidentSnapshot.restore(
                INCIDENT_ID,
                TENANT,
                INSTANCE,
                "effect-outcome-unknown",
                WorkflowIncidentStatus.OPEN,
                DIGEST_EVIDENCE,
                null,
                8L,
                null,
                effect,
            )
        }
    }
}
