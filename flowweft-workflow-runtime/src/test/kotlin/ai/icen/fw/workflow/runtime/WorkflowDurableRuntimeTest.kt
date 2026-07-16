package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowApprovalPolicy
import ai.icen.fw.workflow.api.WorkflowDefinition
import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowDefinitionStatus
import ai.icen.fw.workflow.api.WorkflowHumanTaskCapabilities
import ai.icen.fw.workflow.api.WorkflowHumanCollaborationAction
import ai.icen.fw.workflow.api.WorkflowHumanTaskEvidenceBinding
import ai.icen.fw.workflow.api.WorkflowHumanTaskParticipantRule
import ai.icen.fw.workflow.api.WorkflowHumanTaskPolicy
import ai.icen.fw.workflow.api.WorkflowNodeDefinition
import ai.icen.fw.workflow.api.WorkflowNodeKind
import ai.icen.fw.workflow.api.WorkflowParticipantResolutionStage
import ai.icen.fw.workflow.api.WorkflowParticipantResolution
import ai.icen.fw.workflow.api.WorkflowParticipantResolutionRequest
import ai.icen.fw.workflow.api.WorkflowParticipantTier
import ai.icen.fw.workflow.api.WorkflowParticipantSelector
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSeparationOfDutiesPolicy
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.api.WorkflowTransitionDefinition
import ai.icen.fw.workflow.api.WorkflowTransitionTrigger
import ai.icen.fw.workflow.domain.WorkflowAuthorizationStatus
import ai.icen.fw.workflow.domain.WorkflowContinuationReceipt
import ai.icen.fw.workflow.domain.WorkflowDefinitionExecutionReceipt
import ai.icen.fw.workflow.domain.WorkflowDefinitionIndex
import ai.icen.fw.workflow.domain.WorkflowEffectCode
import ai.icen.fw.workflow.domain.WorkflowEffectCompletionReceipt
import ai.icen.fw.workflow.domain.WorkflowExecutionIds
import ai.icen.fw.workflow.domain.WorkflowHumanDecisionAuthorizationReceipt
import ai.icen.fw.workflow.domain.WorkflowHumanCollaborationAuthorizationReceipt
import ai.icen.fw.workflow.domain.WorkflowHumanDecisionCode
import ai.icen.fw.workflow.domain.WorkflowHumanWorkItemStatus
import ai.icen.fw.workflow.domain.WorkflowInstanceStatus
import ai.icen.fw.workflow.domain.WorkflowParticipantActivationReceipt
import ai.icen.fw.workflow.spi.WorkflowOrganizationAuthority
import ai.icen.fw.workflow.spi.WorkflowOrganizationRelationshipRequest
import ai.icen.fw.workflow.spi.WorkflowOrganizationRelationshipResult
import ai.icen.fw.workflow.spi.WorkflowOrganizationSnapshot
import ai.icen.fw.workflow.spi.WorkflowOrganizationSnapshotRequest
import ai.icen.fw.workflow.spi.WorkflowOrganizationSnapshotResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WorkflowDurableRuntimeTest {
    @Test
    fun `effect worker checkpoints before provider call and stores result after transaction`() {
        val fixture = Fixture()
        val definition = fixture.install(humanDefinition())
        val started = fixture.runtime.start(
            startRequest(definition, "instance-worker", "worker-start-key", "worker-start", 10L),
        )
        val pending = fixture.store.effects.values.single()
        val order = mutableListOf<String>()
        class Queue : WorkflowReadyEffectJobPort {
            var inside: Boolean = false
            lateinit var claim: WorkflowClaimedEffectJob
            var stored: WorkflowEffectJobStoredResult? = null

            override fun claimReady(request: WorkflowReadyEffectJobClaimRequest): List<WorkflowClaimedEffectJob> {
                inside = true
                try {
                    order += "claim"
                    claim = WorkflowClaimedEffectJob.of(
                        "job-worker",
                        request.tenantId,
                        started.state!!.instanceId,
                        pending.intent.effectId,
                        pending.intent.code,
                        WorkflowEffectJobExecutionMode.EXECUTE_PROVIDER,
                        1L,
                        pending.version,
                        request.requestDigest,
                        WorkflowEffectLease.of(
                            "lease-worker",
                            request.workerId,
                            1L,
                            request.now,
                            request.leaseExpiresAt,
                        ),
                        null,
                        request.now,
                    )
                    return listOf(claim)
                } finally {
                    inside = false
                }
            }

            override fun storeResult(
                checkpoint: WorkflowEffectJobResultCheckpoint,
            ): WorkflowEffectJobStoreResult {
                inside = true
                try {
                    order += "store-result"
                    stored = checkpoint.result
                    return WorkflowEffectJobStoreResult.stored(checkpoint.result)
                } finally {
                    inside = false
                }
            }

            override fun loadClaims(
                request: WorkflowReadyEffectJobClaimRequest,
                readAt: Long,
            ): List<WorkflowClaimedEffectJob> = emptyList()

            override fun loadClaim(tenantId: String, jobId: String, readAt: Long): WorkflowClaimedEffectJob? = null
        }
        val queue = Queue()
        val handler = object : WorkflowEffectHandler {
            override fun effectCode(): WorkflowEffectCode = WorkflowEffectCode.PARTICIPANT_RESOLUTION

            override fun execute(request: WorkflowEffectHandlerRequest): WorkflowEffectJobStoredResult {
                assertFalse(queue.inside)
                assertFalse(fixture.store.insideCommit)
                assertSame(WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED, request.effect.phase)
                order += "provider"
                return WorkflowEffectJobStoredResult.of(
                    WorkflowEffectObservedOutcome.SUCCEEDED,
                    "test-worker-result-v1",
                    DIGEST_OUTPUT,
                    byteArrayOf(1),
                    null,
                    20L,
                )
            }

            override fun apply(request: WorkflowEffectApplyRequest): WorkflowRuntimeResult {
                assertFalse(queue.inside)
                assertFalse(fixture.store.insideCommit)
                order += "apply"
                return WorkflowRuntimeResult.durable(
                    WorkflowRuntimeResultCode.COMMITTED,
                    request.state,
                    null,
                    null,
                )
            }
        }
        val worker = WorkflowEffectWorker(
            queue,
            fixture.store,
            WorkflowEffectCoordinator(fixture.authorization, fixture.store),
            handler,
            WorkflowWorkerClock { 20L },
        )

        val result = worker.poll(context(), "worker-a", "claim-worker", 20L, 100L, 1)

        assertEquals(listOf("claim", "provider", "store-result", "apply"), order)
        assertSame(WorkflowEffectWorkerItemCode.APPLIED, result.items.single().code)
        assertFalse(result.claimOutcomeUnknown)
    }

    @Test
    fun `production participant handler binds authority revision request and selector evidence`() {
        val fixture = Fixture()
        val definition = fixture.install(humanDefinition())
        val started = fixture.runtime.start(
            startRequest(definition, "instance-handler", "handler-start-key", "handler-start", 10L),
        )
        val state = started.state!!
        val effect = fixture.store.effects.values.single()
        val claim = WorkflowClaimedEffectJob.of(
            "job-handler",
            TENANT,
            state.instanceId,
            effect.intent.effectId,
            WorkflowEffectCode.PARTICIPANT_RESOLUTION,
            WorkflowEffectJobExecutionMode.EXECUTE_PROVIDER,
            1L,
            effect.version,
            DIGEST_A,
            WorkflowEffectLease.of("lease-handler", "worker-handler", 1L, 20L, 100L),
            null,
            20L,
        )
        var handlerNow = 20L
        var snapshotCalls = 0
        val authority = object : WorkflowOrganizationAuthority {
            override fun snapshot(request: WorkflowOrganizationSnapshotRequest) =
                CompletableFuture.completedFuture(
                    WorkflowOrganizationSnapshotResult.success(
                        request,
                        WorkflowOrganizationSnapshot.of(
                            "test-directory",
                            "organization-revision-42",
                            handlerNow,
                            90L,
                        ),
                        handlerNow,
                    ),
                ).also { snapshotCalls += 1 }

            override fun verifyRelationship(
                request: WorkflowOrganizationRelationshipRequest,
            ): java.util.concurrent.CompletionStage<WorkflowOrganizationRelationshipResult> =
                throw UnsupportedOperationException()
        }
        var capturedResolutionRequest: WorkflowParticipantResolutionRequest? = null
        val handler = WorkflowParticipantResolutionEffectHandler(
            participantResolver = { request ->
                capturedResolutionRequest = request
                handlerNow = 21L
                CompletableFuture.completedFuture(
                    WorkflowParticipantResolution.resolved(
                        request,
                        listOf(
                            WorkflowParticipantTier.direct(
                                request.selectors.single(),
                                0,
                                listOf(BOB),
                                DIGEST_RESOLUTION,
                            ),
                        ),
                        21L,
                        80L,
                    ),
                )
            },
            organizationAuthority = authority,
            durableRuntime = fixture.runtime,
            clock = WorkflowWorkerClock { handlerNow },
            providerId = "test-directory",
            providerRevision = "provider-revision-7",
            participantAuthorizationPort = fixture.authorization,
        )

        val result = handler.execute(
            WorkflowEffectHandlerRequest.of(
                context(),
                claim,
                effect,
                state,
                definition,
                20L,
                100L,
            ),
        )

        assertSame(WorkflowEffectObservedOutcome.SUCCEEDED, result.outcome)
        val receipt = WorkflowParticipantActivationReceiptCodec.decode(result.bytes())
        val rule = definition.index.node("review").humanTaskPolicy!!.participantRules.single()
        assertTrue(receipt.hasOrganizationEvidence)
        assertEquals(3, receipt.evidenceVersion)
        assertEquals("test-directory", receipt.organizationAuthority)
        assertEquals("organization-revision-42", receipt.organizationSnapshotRevision)
        assertEquals("organization-revision-42", receipt.organizationConfirmationRevision)
        assertEquals("provider-revision-7", receipt.organizationProviderRevision)
        assertNotNull(receipt.organizationSnapshotDigest)
        assertNotNull(receipt.organizationSnapshotReceiptDigest)
        assertNotNull(receipt.organizationConfirmationSnapshotDigest)
        assertNotNull(receipt.organizationConfirmationRequestDigest)
        assertNotNull(receipt.organizationConfirmationReceiptDigest)
        assertEquals(rule.selector.digest, receipt.selectorDigest)
        assertEquals(result.resultDigest, receipt.receiptDigest)
        assertEquals(2, snapshotCalls)
        assertSame(WorkflowRuntimeAction.RESOLVE_PARTICIPANTS, fixture.authorization.requests.last().action)
        assertTrue(requireNotNull(capturedResolutionRequest).hasAuthorizationEvidence)
        assertEquals("authority-v1", capturedResolutionRequest!!.authorizationAuthorityRevision)
        assertEquals(context().actor, capturedResolutionRequest!!.currentActor)

        val callsBeforeDenial = snapshotCalls
        fixture.authorization.allowed = false
        val denied = handler.execute(
            WorkflowEffectHandlerRequest.of(context(), claim, effect, state, definition, 20L, 100L),
        )
        assertSame(WorkflowEffectObservedOutcome.TERMINAL_FAILURE, denied.outcome)
        assertEquals(callsBeforeDenial, snapshotCalls)
    }

    @Test
    fun `participant handler fails closed when organization revision drifts during resolver call`() {
        val fixture = Fixture()
        val definition = fixture.install(humanDefinition())
        val started = fixture.runtime.start(
            startRequest(definition, "instance-handler-drift", "handler-drift-key", "handler-drift", 10L),
        )
        val state = started.state!!
        val effect = fixture.store.effects.values.single()
        val claim = WorkflowClaimedEffectJob.of(
            "job-handler-drift",
            TENANT,
            state.instanceId,
            effect.intent.effectId,
            WorkflowEffectCode.PARTICIPANT_RESOLUTION,
            WorkflowEffectJobExecutionMode.EXECUTE_PROVIDER,
            1L,
            effect.version,
            DIGEST_A,
            WorkflowEffectLease.of("lease-handler-drift", "worker-handler", 1L, 20L, 100L),
            null,
            20L,
        )
        var now = 20L
        var snapshotCalls = 0
        val authority = object : WorkflowOrganizationAuthority {
            override fun snapshot(request: WorkflowOrganizationSnapshotRequest) =
                CompletableFuture.completedFuture(
                    WorkflowOrganizationSnapshotResult.success(
                        request,
                        WorkflowOrganizationSnapshot.of(
                            "test-directory",
                            if (++snapshotCalls == 1) "organization-revision-42" else "organization-revision-43",
                            now,
                            90L,
                        ),
                        now,
                    ),
                )

            override fun verifyRelationship(
                request: WorkflowOrganizationRelationshipRequest,
            ): java.util.concurrent.CompletionStage<WorkflowOrganizationRelationshipResult> =
                throw UnsupportedOperationException()
        }
        val handler = WorkflowParticipantResolutionEffectHandler(
            participantResolver = { request ->
                now = 21L
                CompletableFuture.completedFuture(
                    WorkflowParticipantResolution.resolved(
                        request,
                        listOf(
                            WorkflowParticipantTier.direct(
                                request.selectors.single(),
                                0,
                                listOf(BOB),
                                DIGEST_RESOLUTION,
                            ),
                        ),
                        21L,
                        80L,
                    ),
                )
            },
            organizationAuthority = authority,
            durableRuntime = fixture.runtime,
            clock = WorkflowWorkerClock { now },
            providerId = "test-directory",
            providerRevision = "provider-revision-7",
            participantAuthorizationPort = fixture.authorization,
        )

        val result = handler.execute(
            WorkflowEffectHandlerRequest.of(context(), claim, effect, state, definition, 20L, 100L),
        )

        assertSame(WorkflowEffectObservedOutcome.RETRYABLE_FAILURE, result.outcome)
        assertEquals("participant-resolution-failure-v1", result.resultType)
        assertEquals(2, snapshotCalls)
    }

    @Test
    fun `effect apply request rejects stale record versions and expired queue fences`() {
        val fixture = Fixture()
        val definition = fixture.install(humanDefinition())
        val started = fixture.runtime.start(
            startRequest(definition, "instance-apply-binding", "apply-binding-key", "apply-binding", 10L),
        )
        val state = requireNotNull(started.state)
        val pending = fixture.store.effects.values.single()
        val result = WorkflowEffectJobStoredResult.of(
            WorkflowEffectObservedOutcome.SUCCEEDED,
            "test-apply-result-v1",
            DIGEST_OUTPUT,
            byteArrayOf(0, 0xff.toByte()),
            null,
            20L,
        )
        fixture.store.markSucceeded(pending.intent.effectId, result.resultDigest, 20L)
        val succeeded = fixture.store.effect(pending.intent.effectId)
        val liveClaim = WorkflowClaimedEffectJob.of(
            "job-apply-binding",
            TENANT,
            state.instanceId,
            succeeded.intent.effectId,
            succeeded.intent.code,
            WorkflowEffectJobExecutionMode.APPLY_SUCCEEDED_RESULT,
            1L,
            succeeded.version,
            DIGEST_A,
            WorkflowEffectLease.of("lease-apply-binding", "worker-apply", 7L, 20L, 100L),
            result,
            20L,
        )
        val valid = WorkflowEffectApplyRequest.of(
            context(),
            liveClaim,
            succeeded,
            state,
            definition,
            result,
            21L,
        )
        assertEquals(succeeded.version, valid.effect.version)

        val staleClaim = WorkflowClaimedEffectJob.of(
            "job-apply-binding",
            TENANT,
            state.instanceId,
            succeeded.intent.effectId,
            succeeded.intent.code,
            WorkflowEffectJobExecutionMode.APPLY_SUCCEEDED_RESULT,
            2L,
            succeeded.version - 1L,
            DIGEST_A,
            WorkflowEffectLease.of("lease-stale-binding", "worker-stale", 8L, 20L, 100L),
            result,
            20L,
        )
        assertFailsWith<IllegalArgumentException> {
            WorkflowEffectApplyRequest.of(context(), staleClaim, succeeded, state, definition, result, 21L)
        }

        val expiredClaim = WorkflowClaimedEffectJob.of(
            "job-apply-binding",
            TENANT,
            state.instanceId,
            succeeded.intent.effectId,
            succeeded.intent.code,
            WorkflowEffectJobExecutionMode.APPLY_SUCCEEDED_RESULT,
            3L,
            succeeded.version,
            DIGEST_A,
            WorkflowEffectLease.of("lease-expired-binding", "worker-expired", 9L, 20L, 21L),
            result,
            20L,
        )
        assertFailsWith<IllegalArgumentException> {
            WorkflowEffectApplyRequest.of(context(), expiredClaim, succeeded, state, definition, result, 21L)
        }
    }

    @Test
    fun `trusted identifiers use deterministic UTF-8 validation including supplementary Chinese`() {
        val context = WorkflowTrustedCallContext.of(
            "租户-\uD840\uDC00",
            ALICE,
            "认证-\uD840\uDC00",
            DIGEST_AUTHORITY,
        )
        assertEquals("租户-\uD840\uDC00", context.tenantId)
        assertFailsWith<IllegalArgumentException> {
            WorkflowTrustedCallContext.of("租户\u3000", ALICE, "认证", DIGEST_AUTHORITY)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowTrustedCallContext.of("租户", ALICE, "认证-\uD840", DIGEST_AUTHORITY)
        }
    }

    @Test
    fun `state events effects and idempotency commit before dispatch and replay reauthorizes`() {
        val fixture = Fixture()
        val definition = fixture.install(serviceDefinition())
        fixture.dispatch.failNext = true

        val firstRequest = startRequest(definition, "instance-replay", "replay-key", "first", 10L)
        val first = fixture.runtime.start(firstRequest)

        assertSame(WorkflowRuntimeResultCode.COMMITTED_DISPATCH_DEFERRED, first.code)
        assertTrue(first.committed)
        assertEquals(1, fixture.store.commitCalls)
        assertEquals(1, fixture.store.states.size)
        assertTrue(fixture.store.events.isNotEmpty())
        assertEquals(1, fixture.store.effects.size)
        assertEquals(1, fixture.store.idempotency.size)
        assertEquals(1, fixture.dispatch.attempts)

        val replay = fixture.runtime.start(
            startRequest(definition, "instance-replay", "replay-key", "replay", 20L),
        )
        assertSame(WorkflowRuntimeResultCode.REPLAYED, replay.code)
        assertEquals(1, fixture.store.commitCalls)
        assertEquals(2, fixture.dispatch.attempts)
        assertEquals(2, fixture.authorization.requests.size)

        val conflicting = fixture.runtime.start(
            startRequest(definition, "instance-replay", "replay-key", "conflict", 30L, budget = 1),
        )
        assertSame(WorkflowRuntimeResultCode.IDEMPOTENCY_CONFLICT, conflicting.code)
        assertEquals(3, fixture.authorization.requests.size)
        assertEquals(2, fixture.dispatch.attempts)

        fixture.authorization.allowed = false
        val revoked = fixture.runtime.start(
            startRequest(definition, "instance-replay", "replay-key", "revoked", 40L),
        )
        assertSame(WorkflowRuntimeResultCode.AUTHORIZATION_DENIED, revoked.code)
        assertEquals(4, fixture.authorization.requests.size)
        assertEquals(2, fixture.dispatch.attempts)
        assertFalse(fixture.store.authorizationOrDispatchObservedInsideCommit)
    }

    @Test
    fun `authorization precedes start existence checks and missing instances remain opaque`() {
        val fixture = Fixture()
        val absentDefinition = fixture.record(serviceDefinition())
        fixture.authorization.allowed = false

        val deniedStart = fixture.runtime.start(
            startRequest(absentDefinition, "instance-opaque-start", "opaque-start", "opaque-start", 10L),
        )
        assertSame(WorkflowRuntimeResultCode.AUTHORIZATION_DENIED, deniedStart.code)
        assertEquals(1, fixture.authorization.requests.size)

        fixture.authorization.allowed = true
        val authorizedMissing = fixture.runtime.start(
            startRequest(absentDefinition, "instance-opaque-start", "opaque-start-2", "opaque-start-2", 20L),
        )
        assertSame(WorkflowRuntimeResultCode.NOT_FOUND, authorizedMissing.code)
        assertEquals(2, fixture.authorization.requests.size)

        val missingInstance = fixture.runtime.continueExecution(
            WorkflowRuntimeContinueRequest.of(
                context(),
                options("opaque-instance", "opaque-instance-key", 1L, 30L),
                "instance-does-not-exist",
                WorkflowContinuationReceipt.of(
                    "opaque-receipt",
                    "opaque-effect",
                    TENANT,
                    "instance-does-not-exist",
                    DEFINITION,
                    absentDefinition.index.definition.ref,
                    SUBJECT,
                    DIGEST_OUTPUT,
                    30L,
                ),
            ),
        )
        assertSame(WorkflowRuntimeResultCode.AUTHORIZATION_DENIED, missingInstance.code)
        assertEquals(2, fixture.authorization.requests.size)
    }

    @Test
    fun `unknown commit outcome is recovered by an exact authorized replay`() {
        val fixture = Fixture()
        val definition = fixture.install(serviceDefinition())
        fixture.store.throwAfterNextCommit = true

        val first = fixture.runtime.start(
            startRequest(definition, "instance-unknown", "unknown-key", "unknown", 10L),
        )
        assertSame(WorkflowRuntimeResultCode.COMMIT_OUTCOME_UNKNOWN, first.code)
        assertFalse(first.committed)
        assertEquals(1, fixture.store.states.size)
        assertEquals(0, fixture.dispatch.attempts)

        val replay = fixture.runtime.start(
            startRequest(definition, "instance-unknown", "unknown-key", "recovered", 20L),
        )
        assertSame(WorkflowRuntimeResultCode.REPLAYED, replay.code)
        assertEquals(1, fixture.store.commitCalls)
        assertEquals(1, fixture.dispatch.attempts)
        assertEquals(2, fixture.authorization.requests.size)
    }

    @Test
    fun `CAS conflict never dispatches or partially persists`() {
        val fixture = Fixture()
        val definition = fixture.install(serviceDefinition())
        fixture.store.forceNextVersionConflict = true

        val result = fixture.runtime.start(
            startRequest(definition, "instance-cas", "cas-key", "cas", 10L),
        )

        assertSame(WorkflowRuntimeResultCode.VERSION_CONFLICT, result.code)
        assertTrue(fixture.store.states.isEmpty())
        assertTrue(fixture.store.events.isEmpty())
        assertTrue(fixture.store.effects.isEmpty())
        assertTrue(fixture.store.idempotency.isEmpty())
        assertEquals(0, fixture.dispatch.attempts)
    }

    @Test
    fun `human approval and rejection follow distinct explicit triggers`() {
        val fixture = Fixture()
        val definition = fixture.install(humanDefinition())

        val approved = executeHuman(fixture, definition, "instance-approved", "approved", WorkflowHumanDecisionCode.APPROVE)
        assertSame(WorkflowInstanceStatus.COMPLETED, approved.state!!.status)
        assertSame(WorkflowHumanWorkItemStatus.APPROVED, approved.state!!.humanWorkItems.single().status)
        assertEquals("approved", approved.state!!.nodeExecutions.last().nodeId)
        assertTrue(approved.state!!.nodeExecutions.none { execution -> execution.nodeId == "rejected" })

        val rejected = executeHuman(fixture, definition, "instance-rejected", "rejected", WorkflowHumanDecisionCode.REJECT)
        assertSame(WorkflowInstanceStatus.COMPLETED, rejected.state!!.status)
        assertSame(WorkflowHumanWorkItemStatus.REJECTED, rejected.state!!.humanWorkItems.single().status)
        assertEquals("rejected", rejected.state!!.nodeExecutions.last().nodeId)
        assertTrue(rejected.state!!.nodeExecutions.none { execution -> execution.nodeId == "approved" })
        assertEquals(2, fixture.authorization.humanReceiptRequests)
    }

    @Test
    fun `collaboration authorization is pre-load opaque and claim is CAS idempotent nonce-bound`() {
        val fixture = Fixture()
        val definition = fixture.install(collaborationDefinition(false))
        val policy = definition.index.node("review").humanTaskPolicy!!
        fixture.authorization.allowed = false
        val readsBefore = fixture.store.snapshotReads
        val deniedMissing = fixture.runtime.collaborateHumanTask(
            WorkflowRuntimeHumanCollaborationRequest.of(
                context(BOB),
                options("missing-claim", "missing-claim-key", 1L, 10L),
                "missing-instance",
                DEFINITION,
                definition.index.definition.ref,
                SUBJECT,
                "missing-task",
                "review",
                DIGEST_A,
                policy.evidenceBinding,
                0,
                DIGEST_A,
                DIGEST_B,
                WorkflowHumanCollaborationAction.CLAIM,
                null,
                0L,
                "missing-nonce",
            ),
        )
        assertSame(WorkflowRuntimeResultCode.AUTHORIZATION_DENIED, deniedMissing.code)
        assertEquals(readsBefore, fixture.store.snapshotReads)

        fixture.authorization.allowed = true
        val crossTenant = fixture.runtime.collaborateHumanTask(
            WorkflowRuntimeHumanCollaborationRequest.of(
                WorkflowTrustedCallContext.of("tenant-other", BOB, "other-authentication", DIGEST_AUTHORITY),
                options("cross-tenant-claim", "cross-tenant-claim-key", 1L, 11L),
                "missing-instance",
                DEFINITION,
                definition.index.definition.ref,
                SUBJECT,
                "missing-task",
                "review",
                DIGEST_A,
                policy.evidenceBinding,
                0,
                DIGEST_A,
                DIGEST_B,
                WorkflowHumanCollaborationAction.CLAIM,
                null,
                0L,
                "cross-tenant-nonce",
            ),
        )
        assertSame(WorkflowRuntimeResultCode.AUTHORIZATION_DENIED, crossTenant.code)

        val active = activateHuman(
            fixture,
            definition,
            "instance-claim",
            "claim",
            listOf(BOB, CAROL),
        ).state!!
        val firstRequest = collaborationRequest(
            active,
            BOB,
            WorkflowHumanCollaborationAction.CLAIM,
            null,
            "claim-first",
            "claim-key",
            "claim-nonce",
            30L,
        )
        val staleCompetingRequest = collaborationRequest(
            active,
            CAROL,
            WorkflowHumanCollaborationAction.CLAIM,
            null,
            "claim-competing",
            "claim-competing-key",
            "claim-competing-nonce",
            30L,
        )
        val claimed = fixture.runtime.collaborateHumanTask(firstRequest)
        assertSame(WorkflowRuntimeResultCode.COMMITTED, claimed.code)
        assertEquals(BOB, claimed.state!!.humanWorkItems.single().collaboration.claimOwner)
        assertSame(
            WorkflowRuntimeResultCode.VERSION_CONFLICT,
            fixture.runtime.collaborateHumanTask(staleCompetingRequest).code,
        )

        fixture.authorization.allowed = false
        val revokedReplay = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                active,
                BOB,
                WorkflowHumanCollaborationAction.CLAIM,
                null,
                "claim-revoked-replay",
                "claim-key",
                "claim-nonce",
                35L,
            ),
        )
        assertSame(WorkflowRuntimeResultCode.AUTHORIZATION_DENIED, revokedReplay.code)
        fixture.authorization.allowed = true
        val replay = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                active,
                BOB,
                WorkflowHumanCollaborationAction.CLAIM,
                null,
                "claim-replay",
                "claim-key",
                "claim-nonce",
                40L,
            ),
        )
        assertSame(WorkflowRuntimeResultCode.REPLAYED, replay.code)

        val current = claimed.state!!
        val repeatedNonce = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                current,
                BOB,
                WorkflowHumanCollaborationAction.UNCLAIM,
                null,
                "claim-nonce-reuse",
                "claim-nonce-reuse-key",
                "claim-nonce",
                50L,
            ),
        )
        assertSame(WorkflowRuntimeResultCode.DOMAIN_REJECTED, repeatedNonce.code)
        assertEquals("authorization-denied", repeatedNonce.failureCode)
    }

    @Test
    fun `unclaim is owner or privileged and delegate transfer never enlarge candidates`() {
        val fixture = Fixture()
        val definition = fixture.install(collaborationDefinition(false))
        var state = activateHuman(
            fixture,
            definition,
            "instance-collaboration",
            "collaboration",
            listOf(BOB, CAROL, DAVE),
        ).state!!
        state = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                state, BOB, WorkflowHumanCollaborationAction.CLAIM, null,
                "owner-claim", "owner-claim-key", "owner-claim-nonce", 30L,
            ),
        ).state!!

        val outsiderUnclaim = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                state, CAROL, WorkflowHumanCollaborationAction.UNCLAIM, null,
                "outsider-unclaim", "outsider-unclaim-key", "outsider-unclaim-nonce", 40L,
            ),
        )
        assertSame(WorkflowRuntimeResultCode.DOMAIN_REJECTED, outsiderUnclaim.code)
        assertEquals("actor-not-claim-owner", outsiderUnclaim.failureCode)

        fixture.authorization.privilegedUnclaim = true
        val privileged = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                state, CAROL, WorkflowHumanCollaborationAction.UNCLAIM, null,
                "privileged-unclaim", "privileged-unclaim-key", "privileged-unclaim-nonce", 41L,
            ),
        )
        assertSame(WorkflowRuntimeResultCode.COMMITTED, privileged.code)
        assertEquals(null, privileged.state!!.humanWorkItems.single().collaboration.claimOwner)
        fixture.authorization.privilegedUnclaim = false

        state = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                privileged.state!!, BOB, WorkflowHumanCollaborationAction.CLAIM, null,
                "delegate-claim", "delegate-claim-key", "delegate-claim-nonce", 50L,
            ),
        ).state!!
        val outsiderDelegate = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                state, BOB, WorkflowHumanCollaborationAction.DELEGATE, EVE,
                "outsider-delegate", "outsider-delegate-key", "outsider-delegate-nonce", 60L,
            ),
        )
        assertSame(WorkflowRuntimeResultCode.AUTHORIZATION_DENIED, outsiderDelegate.code)

        state = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                state, BOB, WorkflowHumanCollaborationAction.DELEGATE, CAROL,
                "valid-delegate", "valid-delegate-key", "valid-delegate-nonce", 61L,
            ),
        ).state!!
        assertEquals(BOB, state.humanWorkItems.single().collaboration.claimOwner)
        assertEquals(CAROL, state.humanWorkItems.single().collaboration.activeDelegate)
        state = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                state, CAROL, WorkflowHumanCollaborationAction.TRANSFER, DAVE,
                "valid-transfer", "valid-transfer-key", "valid-transfer-nonce", 70L,
            ),
        ).state!!
        assertEquals(DAVE, state.humanWorkItems.single().collaboration.claimOwner)
        assertEquals(null, state.humanWorkItems.single().collaboration.activeDelegate)

        val item = state.humanWorkItems.single()
        val oldOwnerDecision = fixture.runtime.decideHumanTask(
            WorkflowRuntimeHumanDecisionRequest.of(
                context(BOB),
                options("old-owner-decision", "old-owner-decision-key", state.version, 80L),
                state.instanceId,
                item.workItemId,
                WorkflowHumanDecisionCode.APPROVE,
                item.revision,
            ),
        )
        assertSame(WorkflowRuntimeResultCode.DOMAIN_REJECTED, oldOwnerDecision.code)
        assertEquals("actor-not-claim-owner", oldOwnerDecision.failureCode)
    }

    @Test
    fun `collaboration rechecks separation of duties against initiator`() {
        val fixture = Fixture()
        val definition = fixture.install(collaborationDefinition(true))
        val state = activateHuman(
            fixture,
            definition,
            "instance-sod",
            "sod",
            listOf(ALICE, BOB),
        ).state!!
        val result = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                state, ALICE, WorkflowHumanCollaborationAction.CLAIM, null,
                "sod-claim", "sod-claim-key", "sod-claim-nonce", 30L,
            ),
        )
        assertSame(WorkflowRuntimeResultCode.DOMAIN_REJECTED, result.code)
        assertEquals("actor-not-candidate", result.failureCode)

        val claimed = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                state, BOB, WorkflowHumanCollaborationAction.CLAIM, null,
                "sod-owner-claim", "sod-owner-claim-key", "sod-owner-claim-nonce", 31L,
            ),
        ).state!!
        val forbiddenSigner = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                claimed, BOB, WorkflowHumanCollaborationAction.ADD_SIGN, ALICE,
                "sod-add-sign", "sod-add-sign-key", "sod-add-sign-nonce", 32L,
            ),
        )
        assertSame(WorkflowRuntimeResultCode.DOMAIN_REJECTED, forbiddenSigner.code)
        assertEquals("actor-not-candidate", forbiddenSigner.failureCode)
    }

    @Test
    fun `nested before-sign requires approval and returns exact LIFO actors`() {
        val fixture = Fixture()
        val definition = fixture.install(collaborationDefinition(false))
        var state = activateHuman(
            fixture,
            definition,
            "instance-add-sign",
            "add-sign",
            listOf(BOB, CAROL, DAVE),
        ).state!!
        state = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                state, BOB, WorkflowHumanCollaborationAction.CLAIM, null,
                "add-sign-claim", "add-sign-claim-key", "add-sign-claim-nonce", 30L,
            ),
        ).state!!

        val beforeFirstAddSign = state
        state = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                state, BOB, WorkflowHumanCollaborationAction.ADD_SIGN, CAROL,
                "add-sign-first", "add-sign-first-key", "add-sign-first-nonce", 40L,
            ),
        ).state!!
        assertEquals(BOB, state.humanWorkItems.single().collaboration.claimOwner)
        assertEquals(CAROL, state.humanWorkItems.single().collaboration.effectiveActor)
        assertEquals(1, state.humanWorkItems.single().collaboration.addSignFrames.size)
        assertSame(
            WorkflowRuntimeResultCode.REPLAYED,
            fixture.runtime.collaborateHumanTask(
                collaborationRequest(
                    beforeFirstAddSign, BOB, WorkflowHumanCollaborationAction.ADD_SIGN, CAROL,
                    "add-sign-first-replay", "add-sign-first-key", "add-sign-first-nonce", 41L,
                ),
            ).code,
        )

        val cycle = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                state, CAROL, WorkflowHumanCollaborationAction.ADD_SIGN, BOB,
                "add-sign-cycle", "add-sign-cycle-key", "add-sign-cycle-nonce", 42L,
            ),
        )
        assertSame(WorkflowRuntimeResultCode.DOMAIN_REJECTED, cycle.code)
        assertEquals("human-collaboration-conflict", cycle.failureCode)

        state = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                state, CAROL, WorkflowHumanCollaborationAction.ADD_SIGN, DAVE,
                "add-sign-second", "add-sign-second-key", "add-sign-second-nonce", 50L,
            ),
        ).state!!
        val prematureReturn = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                state, DAVE, WorkflowHumanCollaborationAction.RETURN, CAROL,
                "return-premature", "return-premature-key", "return-premature-nonce", 51L,
            ),
        )
        assertSame(WorkflowRuntimeResultCode.DOMAIN_REJECTED, prematureReturn.code)
        assertEquals("add-sign-decision-required", prematureReturn.failureCode)

        state = decideActiveHuman(fixture, state, DAVE, "second-signer-approve", 60L).state!!
        assertSame(WorkflowHumanWorkItemStatus.ACTIVE, state.humanWorkItems.single().status)
        assertEquals(DAVE, state.humanWorkItems.single().collaboration.effectiveActor)
        val wrongReturn = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                state, DAVE, WorkflowHumanCollaborationAction.RETURN, BOB,
                "return-wrong", "return-wrong-key", "return-wrong-nonce", 61L,
            ),
        )
        assertSame(WorkflowRuntimeResultCode.DOMAIN_REJECTED, wrongReturn.code)
        assertEquals("actor-not-claim-owner", wrongReturn.failureCode)
        val beforeSecondReturn = state
        state = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                beforeSecondReturn, DAVE, WorkflowHumanCollaborationAction.RETURN, CAROL,
                "return-second", "return-second-key", "return-second-nonce", 70L,
            ),
        ).state!!
        assertEquals(CAROL, state.humanWorkItems.single().collaboration.effectiveActor)
        assertSame(
            WorkflowRuntimeResultCode.REPLAYED,
            fixture.runtime.collaborateHumanTask(
                collaborationRequest(
                    beforeSecondReturn, DAVE, WorkflowHumanCollaborationAction.RETURN, CAROL,
                    "return-second-replay", "return-second-key", "return-second-nonce", 71L,
                ),
            ).code,
        )

        state = decideActiveHuman(fixture, state, CAROL, "first-signer-approve", 80L).state!!
        state = fixture.runtime.collaborateHumanTask(
            collaborationRequest(
                state, CAROL, WorkflowHumanCollaborationAction.RETURN, BOB,
                "return-first", "return-first-key", "return-first-nonce", 90L,
            ),
        ).state!!
        assertEquals(BOB, state.humanWorkItems.single().collaboration.effectiveActor)
        assertEquals(emptyList(), state.humanWorkItems.single().collaboration.addSignFrames)

        val completed = decideActiveHuman(fixture, state, BOB, "owner-approve", 100L)
        assertSame(WorkflowRuntimeResultCode.COMMITTED, completed.code)
        assertSame(WorkflowInstanceStatus.COMPLETED, completed.state!!.status)
        assertEquals(
            listOf("claim", "add-sign", "add-sign", "return", "return"),
            completed.state!!.humanWorkItems.single().collaboration.records.map { it.action.code },
        )
    }

    @Test
    fun `external completion and continuation consume outcome evidence atomically`() {
        val fixture = Fixture()
        val definition = fixture.install(serviceDefinition())

        val serviceStart = fixture.runtime.start(
            startRequest(definition, "instance-service", "service-start", "service-start", 10L),
        )
        val serviceState = serviceStart.state!!
        val serviceEffect = fixture.store.onlyEffect("instance-service")
        fixture.store.markSucceeded(serviceEffect.effectId, DIGEST_OUTPUT, 15L)
        val serviceReceipt = WorkflowEffectCompletionReceipt.success(
            "service-receipt",
            serviceEffect.effectId,
            serviceEffect.code,
            serviceState.tenantId,
            serviceState.instanceId,
            serviceState.definitionId,
            serviceState.definitionRef,
            serviceState.subject,
            serviceEffect.tokenId!!,
            serviceEffect.nodeExecutionId!!,
            serviceEffect.nodeId!!,
            serviceEffect.requestDigest,
            null,
            DIGEST_OUTPUT,
            20L,
        )
        val completed = fixture.runtime.completeEffect(
            WorkflowRuntimeCompleteEffectRequest.of(
                context(),
                options("service-complete", "service-complete-key", serviceState.version, 20L),
                serviceState.instanceId,
                serviceReceipt,
            ),
        )
        assertSame(WorkflowInstanceStatus.COMPLETED, completed.state!!.status)
        assertSame(
            WorkflowEffectDeliveryStatus.DOMAIN_APPLIED,
            fixture.store.effect(serviceEffect.effectId).status,
        )

        val budgetStart = fixture.runtime.start(
            startRequest(definition, "instance-budget", "budget-start", "budget-start", 30L, budget = 1),
        )
        val budgetState = budgetStart.state!!
        val continuation = fixture.store.onlyEffect("instance-budget")
        assertSame(WorkflowEffectCode.CONTINUE_EXECUTION, continuation.code)
        fixture.store.markSucceeded(continuation.effectId, DIGEST_OUTPUT, 35L)
        val continuationReceipt = WorkflowContinuationReceipt.of(
            "continuation-receipt",
            continuation.effectId,
            budgetState.tenantId,
            budgetState.instanceId,
            budgetState.definitionId,
            budgetState.definitionRef,
            budgetState.subject,
            continuation.requestDigest,
            40L,
        )
        val resumed = fixture.runtime.continueExecution(
            WorkflowRuntimeContinueRequest.of(
                context(),
                options("continue", "continue-key", budgetState.version, 40L),
                budgetState.instanceId,
                continuationReceipt,
            ),
        )
        assertSame(WorkflowRuntimeResultCode.COMMITTED, resumed.code)
        assertSame(WorkflowInstanceStatus.WAITING, resumed.state!!.status)
        assertSame(
            WorkflowEffectDeliveryStatus.DOMAIN_APPLIED,
            fixture.store.effect(continuation.effectId).status,
        )
        assertSame(WorkflowEffectCode.SERVICE_TASK, fixture.store.pendingEffect("instance-budget").code)
    }

    @Test
    fun `effect leases checkpoint before provider call and unknown outcomes require reconciliation`() {
        val fixture = Fixture()
        val definition = fixture.install(serviceDefinition())
        fixture.runtime.start(startRequest(definition, "instance-effect", "effect-start", "effect-start", 1L))
        val effect = fixture.store.onlyEffect("instance-effect")
        val coordinator = WorkflowEffectCoordinator(fixture.authorization, fixture.store)

        val claimed = coordinator.claim(context(), effect.effectId, "worker-a", "lease-a", 0L, 1L, 10L, 20L)
        assertSame(WorkflowEffectDeliveryStatus.LEASED, claimed.record!!.status)
        assertSame(WorkflowEffectExecutionPhase.PREPARED, claimed.record!!.phase)
        val checkpointed = coordinator.checkpoint(
            context(),
            effect.effectId,
            claimed.record!!.version,
            "lease-a",
            1L,
            1L,
            WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED,
            DIGEST_CHECKPOINT,
            11L,
        )
        assertSame(WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED, checkpointed.record!!.phase)
        val unknown = coordinator.recordOutcome(
            context(),
            effect.effectId,
            checkpointed.record!!.version,
            "lease-a",
            1L,
            WorkflowEffectObservedOutcome.OUTCOME_UNKNOWN,
            DIGEST_OUTPUT,
            12L,
        )
        assertSame(WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN, unknown.record!!.status)

        fixture.authorization.allowed = false
        val unauthorizedRetry = coordinator.retry(
            context(), effect.effectId, unknown.record!!.version, 30L, DIGEST_RETRY, 13L,
        )
        assertSame(WorkflowEffectOperationCode.AUTHORIZATION_DENIED, unauthorizedRetry.code)
        fixture.authorization.allowed = true
        val blockedRetry = coordinator.retry(
            context(), effect.effectId, unknown.record!!.version, 30L, DIGEST_RETRY, 13L,
        )
        assertSame(WorkflowEffectOperationCode.RECONCILIATION_REQUIRED, blockedRetry.code)
        val incident = coordinator.raiseReconciliationIncident(
            context(), effect.effectId, unknown.record!!.version, "incident-1", DIGEST_INCIDENT, 14L,
        )
        assertSame(WorkflowEffectDeliveryStatus.RECONCILIATION_INCIDENT, incident.record!!.status)

        fixture.runtime.start(startRequest(definition, "instance-retry", "retry-start", "retry-start", 30L))
        val retryEffect = fixture.store.onlyEffect("instance-retry")
        val firstLease = coordinator.claim(
            context(), retryEffect.effectId, "worker-b", "lease-b", 0L, 2L, 31L, 40L,
        ).record!!
        val callStarted = coordinator.checkpoint(
            context(), retryEffect.effectId, firstLease.version, "lease-b", 2L, 1L,
            WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED, DIGEST_CHECKPOINT, 32L,
        ).record!!
        val retryable = coordinator.recordOutcome(
            context(), retryEffect.effectId, callStarted.version, "lease-b", 2L,
            WorkflowEffectObservedOutcome.RETRYABLE_FAILURE, DIGEST_OUTPUT, 33L,
        ).record!!
        val scheduled = coordinator.retry(
            context(), retryEffect.effectId, retryable.version, 50L, DIGEST_RETRY, 34L,
        ).record!!
        assertSame(WorkflowEffectDeliveryStatus.RETRY_WAIT, scheduled.status)
        assertSame(
            WorkflowEffectOperationCode.NOT_ELIGIBLE,
            coordinator.claim(
                context(), retryEffect.effectId, "worker-c", "lease-c", scheduled.version, 3L, 49L, 60L,
            ).code,
        )
        val reclaimed = coordinator.claim(
            context(), retryEffect.effectId, "worker-c", "lease-c", scheduled.version, 3L, 50L, 60L,
        )
        assertSame(WorkflowEffectDeliveryStatus.LEASED, reclaimed.record!!.status)
        assertEquals(2, reclaimed.record!!.attempt)
    }

    @Test
    fun `missing effects remain opaque to lifecycle callers`() {
        val fixture = Fixture()
        val coordinator = WorkflowEffectCoordinator(fixture.authorization, fixture.store)

        val missing = coordinator.claim(
            context(),
            "effect-does-not-exist",
            "worker-opaque",
            "lease-opaque",
            0L,
            1L,
            10L,
            20L,
        )

        assertSame(WorkflowEffectOperationCode.AUTHORIZATION_DENIED, missing.code)
        assertTrue(fixture.authorization.requests.isEmpty())
    }

    private fun activateHuman(
        fixture: Fixture,
        definition: WorkflowRuntimeDefinitionRecord,
        instanceId: String,
        prefix: String,
        candidates: List<WorkflowPrincipalRef>,
    ): WorkflowRuntimeResult {
        val started = fixture.runtime.start(
            startRequest(definition, instanceId, "$prefix-start-key", "$prefix-start", 10L),
        )
        val state = started.state!!
        val item = state.humanWorkItems.single()
        val effect = fixture.store.onlyEffect(instanceId)
        fixture.store.markSucceeded(effect.effectId, DIGEST_OUTPUT, 15L)
        val execution = state.nodeExecutions.single { it.executionId == item.nodeExecutionId }
        val rule = definition.index.node(item.nodeId).humanTaskPolicy!!.participantRules[item.activeRuleIndex]
        val receipt = WorkflowParticipantActivationReceipt.of(
            "$prefix-activation-receipt",
            effect.effectId,
            state.tenantId,
            state.instanceId,
            state.definitionId,
            state.definitionRef,
            state.subject,
            item.tokenId,
            item.nodeExecutionId,
            item.workItemId,
            item.nodeId,
            item.activeRuleIndex,
            rule.contentDigest,
            execution.effectRequestDigest!!,
            candidates,
            DIGEST_RESOLUTION,
            20L,
            1_000L,
        )
        return fixture.runtime.activateHumanRule(
            WorkflowRuntimeActivateHumanRuleRequest.of(
                context(),
                options("$prefix-activate", "$prefix-activate-key", state.version, 20L),
                instanceId,
                item.workItemId,
                receipt,
            ),
        )
    }

    private fun collaborationRequest(
        state: ai.icen.fw.workflow.domain.WorkflowInstanceState,
        actor: WorkflowPrincipalRef,
        action: WorkflowHumanCollaborationAction,
        target: WorkflowPrincipalRef?,
        prefix: String,
        idempotencyKey: String,
        nonce: String,
        now: Long,
    ): WorkflowRuntimeHumanCollaborationRequest {
        val item = state.humanWorkItems.single()
        val snapshot = item.ruleSnapshots[item.activeRuleIndex]
        return WorkflowRuntimeHumanCollaborationRequest.of(
            context(actor),
            options(prefix, idempotencyKey, state.version, now),
            state.instanceId,
            state.definitionId,
            state.definitionRef,
            state.subject,
            item.workItemId,
            item.nodeId,
            item.policyDigest,
            WorkflowHumanTaskEvidenceBinding.none(),
            item.activeRuleIndex,
            snapshot.ruleDigest,
            snapshot.activationDigest,
            action,
            target,
            item.revision,
            nonce,
        )
    }

    private fun decideActiveHuman(
        fixture: Fixture,
        state: ai.icen.fw.workflow.domain.WorkflowInstanceState,
        actor: WorkflowPrincipalRef,
        prefix: String,
        now: Long,
    ): WorkflowRuntimeResult {
        val item = state.humanWorkItems.single()
        return fixture.runtime.decideHumanTask(
            WorkflowRuntimeHumanDecisionRequest.of(
                context(actor),
                options(prefix, "$prefix-key", state.version, now),
                state.instanceId,
                item.workItemId,
                WorkflowHumanDecisionCode.APPROVE,
                item.revision,
            ),
        )
    }

    private fun executeHuman(
        fixture: Fixture,
        definition: WorkflowRuntimeDefinitionRecord,
        instanceId: String,
        prefix: String,
        decision: WorkflowHumanDecisionCode,
    ): WorkflowRuntimeResult {
        val started = fixture.runtime.start(
            startRequest(definition, instanceId, "$prefix-start-key", "$prefix-start", 10L),
        )
        val state = started.state!!
        val item = state.humanWorkItems.single()
        val effect = fixture.store.onlyEffect(instanceId)
        fixture.store.markSucceeded(effect.effectId, DIGEST_OUTPUT, 15L)
        val execution = state.nodeExecutions.single { value -> value.executionId == item.nodeExecutionId }
        val rule = definition.index.node(item.nodeId).humanTaskPolicy!!.participantRules[item.activeRuleIndex]
        val activationReceipt = WorkflowParticipantActivationReceipt.of(
            "$prefix-activation-receipt",
            effect.effectId,
            state.tenantId,
            state.instanceId,
            state.definitionId,
            state.definitionRef,
            state.subject,
            item.tokenId,
            item.nodeExecutionId,
            item.workItemId,
            item.nodeId,
            item.activeRuleIndex,
            rule.contentDigest,
            execution.effectRequestDigest!!,
            listOf(ALICE),
            DIGEST_RESOLUTION,
            20L,
            100L,
        )
        val activated = fixture.runtime.activateHumanRule(
            WorkflowRuntimeActivateHumanRuleRequest.of(
                context(),
                options("$prefix-activate", "$prefix-activate-key", state.version, 20L),
                instanceId,
                item.workItemId,
                activationReceipt,
            ),
        )
        val activeItem = activated.state!!.humanWorkItems.single()
        return fixture.runtime.decideHumanTask(
            WorkflowRuntimeHumanDecisionRequest.of(
                context(),
                options("$prefix-decide", "$prefix-decide-key", activated.state!!.version, 30L),
                instanceId,
                activeItem.workItemId,
                decision,
                activeItem.revision,
            ),
        )
    }

    private class Fixture {
        val store = InMemoryPersistence()
        val authorization = TestAuthorization(store)
        val dispatch = TestDispatch(store)
        val runtime = WorkflowDurableRuntime(authorization, store, dispatch, authorization)

        fun install(definition: WorkflowDefinition): WorkflowRuntimeDefinitionRecord {
            val record = record(definition)
            store.install(record)
            return record
        }

        fun record(definition: WorkflowDefinition): WorkflowRuntimeDefinitionRecord =
            WorkflowRuntimeDefinitionRecord.of(
                WorkflowDefinitionIndex.compile(definition),
                WorkflowDefinitionExecutionReceipt.of(
                    "${definition.definitionId}-deployment",
                    definition.tenantId,
                    definition.definitionId,
                    definition.ref,
                    definition.schemaVersion,
                    DIGEST_CAPABILITY,
                    0L,
                    100_000L,
                ),
            )
    }

    private class TestAuthorization(private val store: InMemoryPersistence) :
        WorkflowRuntimeAuthorizationPort,
        WorkflowRuntimeHumanCollaborationAuthorizationPort {
        var allowed: Boolean = true
        val requests = mutableListOf<WorkflowRuntimeAuthorizationRequest>()
        var humanReceiptRequests: Int = 0
        var collaborationReceiptRequests: Int = 0
        var privilegedUnclaim: Boolean = false

        override fun authorize(request: WorkflowRuntimeAuthorizationRequest): WorkflowRuntimeAuthorizationDecision {
            if (store.insideCommit) store.authorizationOrDispatchObservedInsideCommit = true
            requests += request
            return WorkflowRuntimeAuthorizationDecision.of(
                "authorization-${requests.size}",
                request.callContext.tenantId,
                request.callContext.actor,
                request.action,
                request.instanceId,
                request.requestDigest,
                if (allowed) WorkflowRuntimeAuthorizationStatus.AUTHORIZED else WorkflowRuntimeAuthorizationStatus.DENIED,
                "authority-v1",
                DIGEST_AUTHORITY,
                request.evaluatedAt,
                request.evaluatedAt + 1_000L,
            )
        }

        override fun issueHumanDecisionReceipt(
            request: WorkflowRuntimeHumanDecisionReceiptRequest,
        ): WorkflowHumanDecisionAuthorizationReceipt {
            if (store.insideCommit) store.authorizationOrDispatchObservedInsideCommit = true
            humanReceiptRequests += 1
            val activation = request.workItem.ruleSnapshots[request.workItem.activeRuleIndex]
            return WorkflowHumanDecisionAuthorizationReceipt.of(
                "human-authorization-$humanReceiptRequests",
                request.state.tenantId,
                request.state.instanceId,
                request.state.definitionId,
                request.state.definitionRef,
                request.state.subject,
                request.workItem.workItemId,
                request.workItem.activeRuleIndex,
                request.actor,
                request.decision,
                activation.activationDigest,
                request.authorizationRequestDigest,
                WorkflowAuthorizationStatus.AUTHORIZED,
                "authority-v1",
                DIGEST_AUTHORITY,
                request.evaluatedAt,
                request.evaluatedAt + 1_000L,
            )
        }

        override fun issueHumanCollaborationReceipt(
            request: WorkflowRuntimeHumanCollaborationReceiptRequest,
        ): WorkflowHumanCollaborationAuthorizationReceipt {
            if (store.insideCommit) store.authorizationOrDispatchObservedInsideCommit = true
            collaborationReceiptRequests += 1
            val command = request.request
            val snapshot = request.workItem.ruleSnapshots[request.workItem.activeRuleIndex]
            return WorkflowHumanCollaborationAuthorizationReceipt.of(
                "collaboration-authorization-$collaborationReceiptRequests",
                request.state.tenantId,
                request.state.instanceId,
                request.state.definitionId,
                request.state.definitionRef,
                request.state.subject,
                request.workItem.workItemId,
                request.workItem.nodeId,
                request.workItem.policyDigest,
                command.evidenceBinding,
                request.workItem.activeRuleIndex,
                snapshot.ruleDigest,
                snapshot.activationDigest,
                command.collaborationAction,
                request.callContext.actor,
                command.target,
                request.workItem.collaboration.claimOwner,
                request.workItem.collaboration.activeDelegate,
                request.workItem.collaboration.contentDigest,
                command.expectedWorkItemVersion,
                command.executionNonce,
                request.authorizationRequestDigest,
                WorkflowAuthorizationStatus.AUTHORIZED,
                snapshot.candidates.contains(request.callContext.actor),
                command.target?.let(snapshot.candidates::contains) ?: false,
                privilegedUnclaim,
                true,
                "directory-v1",
                DIGEST_RESOLUTION,
                "authority-v1",
                DIGEST_AUTHORITY,
                request.evaluatedAt,
                request.evaluatedAt + 1_000L,
            )
        }
    }

    private class TestDispatch(private val store: InMemoryPersistence) : WorkflowEffectDispatchPort {
        var failNext: Boolean = false
        var attempts: Int = 0
        val signals = mutableListOf<WorkflowEffectDispatchSignal>()

        override fun signal(signal: WorkflowEffectDispatchSignal) {
            if (store.insideCommit) store.authorizationOrDispatchObservedInsideCommit = true
            attempts += 1
            if (failNext) {
                failNext = false
                throw IllegalStateException("simulated dispatcher crash")
            }
            signals += signal
        }
    }

    private class InMemoryPersistence : WorkflowRuntimePersistencePort {
        private val definitions = ConcurrentHashMap<String, WorkflowRuntimeDefinitionRecord>()
        val states = linkedMapOf<String, ai.icen.fw.workflow.domain.WorkflowInstanceState>()
        val events = mutableListOf<ai.icen.fw.workflow.domain.WorkflowDomainEvent>()
        val effects = linkedMapOf<String, WorkflowEffectRecord>()
        val idempotency = linkedMapOf<String, WorkflowRuntimeIdempotencyRecord>()
        val commits = mutableListOf<WorkflowRuntimeAtomicCommit>()
        var commitCalls: Int = 0
        var snapshotReads: Int = 0
        var forceNextVersionConflict: Boolean = false
        var throwAfterNextCommit: Boolean = false
        @Volatile var insideCommit: Boolean = false
        @Volatile var authorizationOrDispatchObservedInsideCommit: Boolean = false

        fun install(record: WorkflowRuntimeDefinitionRecord) {
            val definition = record.index.definition
            definitions[definitionKey(definition.tenantId, definition.definitionId, definition.ref)] = record
        }

        override fun loadDefinition(
            tenantId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
        ): WorkflowRuntimeDefinitionRecord? = definitions[definitionKey(tenantId, definitionId, definitionRef)]

        @Synchronized
        override fun loadCommandSnapshot(
            tenantId: String,
            instanceId: String,
            idempotencyKey: String,
            readAt: Long,
        ): WorkflowRuntimeCommandSnapshot {
            snapshotReads += 1
            return WorkflowRuntimeCommandSnapshot.of(
                tenantId,
                instanceId,
                idempotencyKey,
                states[instanceKey(tenantId, instanceId)],
                idempotency[idempotencyKey(tenantId, instanceId, idempotencyKey)],
                readAt,
            )
        }

        @Synchronized
        override fun commit(request: WorkflowRuntimeAtomicCommit): WorkflowRuntimeCommitResult {
            check(!insideCommit)
            insideCommit = true
            try {
                commitCalls += 1
                if (forceNextVersionConflict) {
                    forceNextVersionConflict = false
                    return WorkflowRuntimeCommitResult.conflict(WorkflowRuntimeCommitCode.VERSION_CONFLICT)
                }
                val key = instanceKey(request.tenantId, request.instanceId)
                val idemKey = idempotencyKey(
                    request.tenantId,
                    request.instanceId,
                    request.idempotency.idempotencyKey,
                )
                if (idempotency.containsKey(idemKey)) {
                    return WorkflowRuntimeCommitResult.conflict(WorkflowRuntimeCommitCode.IDEMPOTENCY_CONFLICT)
                }
                val current = states[key]
                val casMatches = if (request.expectedInstanceVersion == 0L) {
                    current == null && request.expectedStateDigest == null
                } else {
                    current != null && current.version == request.expectedInstanceVersion &&
                        current.stateDigest == request.expectedStateDigest
                }
                if (!casMatches) {
                    return WorkflowRuntimeCommitResult.conflict(WorkflowRuntimeCommitCode.VERSION_CONFLICT)
                }
                val acknowledged = request.effectAcknowledgement?.let { acknowledgement ->
                    effects[acknowledgement.effectId]?.takeIf { record ->
                        record.intent.tenantId == request.tenantId &&
                            record.intent.instanceId == request.instanceId &&
                            record.intent.requestDigest == acknowledgement.requestDigest &&
                            (record.status == WorkflowEffectDeliveryStatus.SUCCEEDED ||
                                record.status == WorkflowEffectDeliveryStatus.TERMINAL_FAILURE)
                    } ?: return WorkflowRuntimeCommitResult.conflict(WorkflowRuntimeCommitCode.EFFECT_CONFLICT)
                }
                if (request.effects.any { effect -> effects.containsKey(effect.effectId) }) {
                    return WorkflowRuntimeCommitResult.conflict(WorkflowRuntimeCommitCode.EFFECT_CONFLICT)
                }

                states[key] = request.state
                events += request.events
                request.effects.forEach { effect -> effects[effect.effectId] = WorkflowEffectRecord.pending(effect) }
                idempotency[idemKey] = request.idempotency
                if (acknowledged != null) {
                    val acknowledgement = request.effectAcknowledgement!!
                    effects[acknowledgement.effectId] = WorkflowEffectRecord.restore(
                        acknowledged.intent,
                        WorkflowEffectDeliveryStatus.DOMAIN_APPLIED,
                        acknowledged.version + 1L,
                        acknowledged.attempt,
                        null,
                        null,
                        null,
                        acknowledged.checkpointSequence,
                        acknowledged.checkpointDigest,
                        acknowledgement.receiptDigest,
                        request.committedAt,
                    )
                }
                commits += request
                if (throwAfterNextCommit) {
                    throwAfterNextCommit = false
                    throw IllegalStateException("simulated lost commit acknowledgement")
                }
                return WorkflowRuntimeCommitResult.committed(request.state.version)
            } finally {
                insideCommit = false
            }
        }

        @Synchronized
        override fun loadEffect(tenantId: String, effectId: String, readAt: Long): WorkflowEffectRecord? =
            effects[effectId]?.takeIf { record -> record.intent.tenantId == tenantId }

        @Synchronized
        override fun claimEffect(request: WorkflowEffectClaim): WorkflowEffectOperationResult {
            val current = exactEffect(request.tenantId, request.effectId, request.expectedRecordVersion)
                ?: return versionOrMissing(request.effectId)
            val eligible = current.status == WorkflowEffectDeliveryStatus.PENDING ||
                current.status == WorkflowEffectDeliveryStatus.RETRY_WAIT && current.nextAttemptAt!! <= request.lease.acquiredAt ||
                current.status == WorkflowEffectDeliveryStatus.LEASED && current.lease!!.expiresAt <= request.lease.acquiredAt &&
                    current.phase == WorkflowEffectExecutionPhase.PREPARED
            if (!eligible) return WorkflowEffectOperationResult.failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
            val updated = WorkflowEffectRecord.restore(
                current.intent,
                WorkflowEffectDeliveryStatus.LEASED,
                current.version + 1L,
                current.attempt + 1,
                null,
                request.lease,
                WorkflowEffectExecutionPhase.PREPARED,
                current.checkpointSequence,
                current.checkpointDigest,
                null,
                request.lease.acquiredAt,
            )
            effects[request.effectId] = updated
            return WorkflowEffectOperationResult.applied(updated)
        }

        @Synchronized
        override fun checkpointEffect(request: WorkflowEffectCheckpoint): WorkflowEffectOperationResult {
            val current = exactEffect(request.tenantId, request.effectId, request.expectedRecordVersion)
                ?: return versionOrMissing(request.effectId)
            val lease = current.lease
            if (current.status != WorkflowEffectDeliveryStatus.LEASED || lease == null ||
                lease.leaseId != request.leaseId || lease.fencingToken != request.fencingToken
            ) return WorkflowEffectOperationResult.failed(WorkflowEffectOperationCode.LEASE_MISMATCH)
            if (request.sequence <= current.checkpointSequence ||
                current.phase == WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED &&
                request.phase != WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED
            ) return WorkflowEffectOperationResult.failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
            val updated = WorkflowEffectRecord.restore(
                current.intent,
                WorkflowEffectDeliveryStatus.LEASED,
                current.version + 1L,
                current.attempt,
                null,
                lease,
                request.phase,
                request.sequence,
                request.checkpointDigest,
                null,
                request.checkpointedAt,
            )
            effects[request.effectId] = updated
            return WorkflowEffectOperationResult.applied(updated)
        }

        @Synchronized
        override fun recordEffectOutcome(request: WorkflowEffectOutcome): WorkflowEffectOperationResult {
            val current = exactEffect(request.tenantId, request.effectId, request.expectedRecordVersion)
                ?: return versionOrMissing(request.effectId)
            val lease = current.lease
            if (current.status != WorkflowEffectDeliveryStatus.LEASED || lease == null ||
                lease.leaseId != request.leaseId || lease.fencingToken != request.fencingToken ||
                current.phase != WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED
            ) return WorkflowEffectOperationResult.failed(WorkflowEffectOperationCode.LEASE_MISMATCH)
            val status = when (request.outcome) {
                WorkflowEffectObservedOutcome.SUCCEEDED -> WorkflowEffectDeliveryStatus.SUCCEEDED
                WorkflowEffectObservedOutcome.RETRYABLE_FAILURE -> WorkflowEffectDeliveryStatus.RETRYABLE_FAILURE
                WorkflowEffectObservedOutcome.TERMINAL_FAILURE -> WorkflowEffectDeliveryStatus.TERMINAL_FAILURE
                WorkflowEffectObservedOutcome.OUTCOME_UNKNOWN -> WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN
                else -> return WorkflowEffectOperationResult.failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
            }
            val updated = WorkflowEffectRecord.restore(
                current.intent,
                status,
                current.version + 1L,
                current.attempt,
                null,
                null,
                null,
                current.checkpointSequence,
                current.checkpointDigest,
                request.outcomeDigest,
                request.completedAt,
            )
            effects[request.effectId] = updated
            return WorkflowEffectOperationResult.applied(updated)
        }

        @Synchronized
        override fun scheduleEffectRetry(request: WorkflowEffectRetry): WorkflowEffectOperationResult {
            val current = exactEffect(request.tenantId, request.effectId, request.expectedRecordVersion)
                ?: return versionOrMissing(request.effectId)
            if (current.status != WorkflowEffectDeliveryStatus.RETRYABLE_FAILURE) {
                return WorkflowEffectOperationResult.failed(WorkflowEffectOperationCode.RECONCILIATION_REQUIRED)
            }
            val updated = WorkflowEffectRecord.restore(
                current.intent,
                WorkflowEffectDeliveryStatus.RETRY_WAIT,
                current.version + 1L,
                current.attempt,
                request.nextAttemptAt,
                null,
                null,
                current.checkpointSequence,
                current.checkpointDigest,
                current.outcomeDigest,
                request.scheduledAt,
            )
            effects[request.effectId] = updated
            return WorkflowEffectOperationResult.applied(updated)
        }

        @Synchronized
        override fun raiseEffectReconciliationIncident(
            request: WorkflowEffectReconciliationIncident,
        ): WorkflowEffectOperationResult {
            val current = exactEffect(request.tenantId, request.effectId, request.expectedRecordVersion)
                ?: return versionOrMissing(request.effectId)
            if (current.status != WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN) {
                return WorkflowEffectOperationResult.failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
            }
            val updated = WorkflowEffectRecord.restore(
                current.intent,
                WorkflowEffectDeliveryStatus.RECONCILIATION_INCIDENT,
                current.version + 1L,
                current.attempt,
                null,
                null,
                null,
                current.checkpointSequence,
                current.checkpointDigest,
                request.evidenceDigest,
                request.raisedAt,
            )
            effects[request.effectId] = updated
            return WorkflowEffectOperationResult.applied(updated)
        }

        @Synchronized
        fun markSucceeded(effectId: String, outcomeDigest: String, now: Long) {
            val current = requireNotNull(effects[effectId])
            effects[effectId] = WorkflowEffectRecord.restore(
                current.intent,
                WorkflowEffectDeliveryStatus.SUCCEEDED,
                current.version + 1L,
                maxOf(1, current.attempt),
                null,
                null,
                null,
                current.checkpointSequence,
                current.checkpointDigest,
                outcomeDigest,
                now,
            )
        }

        fun onlyEffect(instanceId: String) = effects.values.single { record ->
            record.intent.instanceId == instanceId && record.status != WorkflowEffectDeliveryStatus.DOMAIN_APPLIED
        }.intent

        fun pendingEffect(instanceId: String) = effects.values.single { record ->
            record.intent.instanceId == instanceId && record.status == WorkflowEffectDeliveryStatus.PENDING
        }.intent

        fun effect(effectId: String): WorkflowEffectRecord = requireNotNull(effects[effectId])

        private fun exactEffect(tenantId: String, effectId: String, version: Long): WorkflowEffectRecord? =
            effects[effectId]?.takeIf { record ->
                record.intent.tenantId == tenantId && record.version == version
            }

        private fun versionOrMissing(effectId: String): WorkflowEffectOperationResult =
            WorkflowEffectOperationResult.failed(
                if (effects.containsKey(effectId)) {
                    WorkflowEffectOperationCode.VERSION_CONFLICT
                } else {
                    WorkflowEffectOperationCode.NOT_FOUND
                },
            )

        private fun definitionKey(tenantId: String, definitionId: String, ref: WorkflowDefinitionRef): String =
            "$tenantId|$definitionId|${ref.key}|${ref.version}|${ref.digest}"

        private fun instanceKey(tenantId: String, instanceId: String): String = "$tenantId|$instanceId"

        private fun idempotencyKey(tenantId: String, instanceId: String, key: String): String =
            "$tenantId|$instanceId|$key"
    }

    private companion object {
        const val TENANT = "tenant-tianjin"
        const val DEFINITION = "definition-runtime"
        const val DIGEST_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val DIGEST_B = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        const val DIGEST_CAPABILITY = "1111111111111111111111111111111111111111111111111111111111111111"
        const val DIGEST_AUTHORITY = "2222222222222222222222222222222222222222222222222222222222222222"
        const val DIGEST_RESOLUTION = "3333333333333333333333333333333333333333333333333333333333333333"
        const val DIGEST_OUTPUT = "4444444444444444444444444444444444444444444444444444444444444444"
        const val DIGEST_CHECKPOINT = "5555555555555555555555555555555555555555555555555555555555555555"
        const val DIGEST_RETRY = "6666666666666666666666666666666666666666666666666666666666666666"
        const val DIGEST_INCIDENT = "7777777777777777777777777777777777777777777777777777777777777777"
        val ALICE: WorkflowPrincipalRef = WorkflowPrincipalRef.of("user", "alice")
        val BOB: WorkflowPrincipalRef = WorkflowPrincipalRef.of("user", "bob")
        val CAROL: WorkflowPrincipalRef = WorkflowPrincipalRef.of("user", "carol")
        val DAVE: WorkflowPrincipalRef = WorkflowPrincipalRef.of("user", "dave")
        val EVE: WorkflowPrincipalRef = WorkflowPrincipalRef.of("user", "eve")
        val SUBJECT: WorkflowSubjectSnapshot = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("business-record", "record-001"),
            "revision-7",
            DIGEST_A,
        )

        fun context(): WorkflowTrustedCallContext = context(ALICE)

        fun context(actor: WorkflowPrincipalRef): WorkflowTrustedCallContext = WorkflowTrustedCallContext.of(
            TENANT,
            actor,
            "authentication-${actor.id}",
            DIGEST_AUTHORITY,
        )

        fun startRequest(
            definition: WorkflowRuntimeDefinitionRecord,
            instanceId: String,
            key: String,
            prefix: String,
            now: Long,
            budget: Int = 64,
        ): WorkflowRuntimeStartRequest = WorkflowRuntimeStartRequest.of(
            context(),
            options(prefix, key, 0L, now, budget),
            instanceId,
            definition.index.definition.definitionId,
            definition.index.definition.ref,
            SUBJECT,
        )

        fun options(
            prefix: String,
            key: String,
            expectedVersion: Long,
            now: Long,
            budget: Int = 64,
        ): WorkflowRuntimeCommandOptions = WorkflowRuntimeCommandOptions.of(
            "$prefix-command",
            key,
            expectedVersion,
            now,
            budget,
            executionIds(prefix),
        )

        fun executionIds(prefix: String): WorkflowExecutionIds = WorkflowExecutionIds.of(
            (0 until 128).map { index -> "$prefix-token-$index" },
            (0 until 128).map { index -> "$prefix-execution-$index" },
            (0 until 32).map { index -> "$prefix-work-item-$index" },
            (0 until 128).map { index -> "$prefix-effect-$index" },
            (0 until 512).map { index -> "$prefix-event-$index" },
            (0 until 32).map { index -> "$prefix-scope-$index" },
        )

        fun serviceDefinition(): WorkflowDefinition = definition(
            listOf(
                WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "开始", null),
                WorkflowNodeDefinition.serviceTask("service", "外部服务", null, DIGEST_A, DIGEST_B),
                WorkflowNodeDefinition.of("end", WorkflowNodeKind.END, "结束", null),
            ),
            listOf(
                WorkflowTransitionDefinition.unconditional("start-service", "start", "service"),
                WorkflowTransitionDefinition.unconditional("service-end", "service", "end"),
            ),
        )

        fun humanDefinition(): WorkflowDefinition {
            val policy = WorkflowHumanTaskPolicy.of(
                listOf(
                    WorkflowHumanTaskParticipantRule.of(
                        WorkflowParticipantSelector.group("reviewers"),
                        WorkflowApprovalPolicy.one(),
                    ),
                ),
                WorkflowHumanTaskCapabilities.of(false, false, false, false),
                WorkflowSeparationOfDutiesPolicy.of(true, true),
                listOf(WorkflowParticipantResolutionStage.ACTIVATION),
            )
            return definition(
                listOf(
                    WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "开始", null),
                    WorkflowNodeDefinition.humanTask("review", "审批", null, policy),
                    WorkflowNodeDefinition.of("approved", WorkflowNodeKind.END, "通过", null),
                    WorkflowNodeDefinition.of("rejected", WorkflowNodeKind.END, "驳回", null),
                ),
                listOf(
                    WorkflowTransitionDefinition.unconditional("start-review", "start", "review"),
                    WorkflowTransitionDefinition.unconditional(
                        "review-approved", "review", "approved", WorkflowTransitionTrigger.APPROVED,
                    ),
                    WorkflowTransitionDefinition.unconditional(
                        "review-rejected", "review", "rejected", WorkflowTransitionTrigger.REJECTED,
                    ),
                ),
            )
        }

        fun collaborationDefinition(excludeInitiator: Boolean): WorkflowDefinition {
            val policy = WorkflowHumanTaskPolicy.of(
                listOf(
                    WorkflowHumanTaskParticipantRule.of(
                        WorkflowParticipantSelector.group("collaboration-reviewers"),
                        WorkflowApprovalPolicy.one(),
                    ),
                ),
                WorkflowHumanTaskCapabilities.of(true, true, true, true),
                WorkflowSeparationOfDutiesPolicy.of(excludeInitiator, false),
                listOf(WorkflowParticipantResolutionStage.ACTIVATION),
            )
            return definition(
                listOf(
                    WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "开始", null),
                    WorkflowNodeDefinition.humanTask("review", "协作审批", null, policy),
                    WorkflowNodeDefinition.of("approved", WorkflowNodeKind.END, "通过", null),
                    WorkflowNodeDefinition.of("rejected", WorkflowNodeKind.END, "驳回", null),
                ),
                listOf(
                    WorkflowTransitionDefinition.unconditional("start-review", "start", "review"),
                    WorkflowTransitionDefinition.unconditional(
                        "review-approved", "review", "approved", WorkflowTransitionTrigger.APPROVED,
                    ),
                    WorkflowTransitionDefinition.unconditional(
                        "review-rejected", "review", "rejected", WorkflowTransitionTrigger.REJECTED,
                    ),
                ),
            )
        }

        fun definition(
            nodes: List<WorkflowNodeDefinition>,
            transitions: List<WorkflowTransitionDefinition>,
        ): WorkflowDefinition = WorkflowDefinition.of(
            TENANT,
            DEFINITION,
            "runtime-contract",
            "v1",
            1,
            WorkflowDefinitionStatus.DRAFT,
            "运行时契约流程",
            null,
            nodes,
            transitions,
        )
    }
}
