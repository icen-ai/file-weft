package ai.icen.fw.governance.runtime

import ai.icen.fw.governance.api.GovernanceAuthorizationSnapshot
import ai.icen.fw.governance.api.GovernanceDeletionPlan
import ai.icen.fw.governance.api.GovernanceDeletionStage
import ai.icen.fw.governance.api.GovernanceDeletionStepReceipt
import ai.icen.fw.governance.api.GovernanceDeletionStepStatus
import ai.icen.fw.governance.api.GovernanceEffectiveClock
import ai.icen.fw.governance.api.GovernanceLegalHoldResolution
import ai.icen.fw.governance.api.GovernanceLegalHoldScope
import ai.icen.fw.governance.api.GovernanceLegalHoldScopeType
import ai.icen.fw.governance.api.GovernanceLegalHoldSnapshot
import ai.icen.fw.governance.api.GovernancePrincipalRef
import ai.icen.fw.governance.api.GovernancePurpose
import ai.icen.fw.governance.api.GovernanceResourceRef
import ai.icen.fw.governance.api.GovernanceRetentionAssessment
import ai.icen.fw.governance.api.GovernanceRetentionPolicyMode
import ai.icen.fw.governance.api.GovernanceRetentionPolicySnapshot
import ai.icen.fw.governance.api.GovernanceVersionFence
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.test.assertFalse

internal class GovernanceRuntimeTestFixture {
    val tenant = "tenant-a"
    val principal = GovernancePrincipalRef.of("user", "user-7")
    val resource = GovernanceResourceRef.of("document", "document-9", "revision-12", digest('a'))
    var now = 1_000L
    var activeHold = false
    var policyCalls = 0
    var targetCalls = 0
    var executorCalls = 0
    var reconcilerCalls = 0
    var failNextExecutor = false
    var missingStage: GovernanceDeletionStage? = null
    var authorizationRevision = "authorization-r1"
    val deniedAuthorizationPurposes = mutableSetOf<GovernancePurpose>()
    val authorizationPurposes = mutableListOf<GovernancePurpose>()
    val executorAuthorizationRevisions = mutableListOf<String>()
    val executorContextDigests = mutableListOf<String>()
    val metrics = mutableListOf<GovernanceMetric>()
    val signals = mutableListOf<GovernanceOutboxRecord>()
    val repository = InMemoryGovernanceRepository()

    private var idSequence = 0
    val identifiers = GovernanceRuntimeIdPort { request ->
        idSequence += 1
        "${request.kind.code}-${request.ordinal}-$idSequence"
    }
    val clock = object : GovernanceRuntimeClockPort {
        override fun nowEpochMilli(): Long = now

        override fun observe(request: GovernanceClockObservationRequest): GovernanceEffectiveClock =
            GovernanceEffectiveClock.of(
                "clock-${request.observedAtEpochMilli}",
                "test-clock",
                "clock-r1",
                request.observedAtEpochMilli,
                request.observedAtEpochMilli + 10_000L,
                request.requiredUntilEpochMilli + 100_000L,
            )
    }
    val authorization = GovernanceRuntimeAuthorizationPort { request ->
        authorizationPurposes += request.purpose
        check(request.purpose !in deniedAuthorizationPurposes) { "authorization denied" }
        GovernanceAuthorizationSnapshot.of(
            "authorization-${authorizationPurposes.size}",
            request.invocation.tenantId,
            request.invocation.principal,
            request.purpose,
            request.invocation.resource,
            "host-authorization",
            "authority-r1",
            authorizationRevision,
            digest('b'),
            request.invocation.requestedAtEpochMilli - 10L,
            request.invocation.deadlineEpochMilli + 1_000L,
        )
    }
    val calls = GovernanceAuthorizedCallFactory(authorization, identifiers)
    val holdResolver = ai.icen.fw.governance.api.GovernanceLegalHoldResolver { request ->
        val resolution = if (activeHold) {
            val scope = GovernanceLegalHoldScope.of(
                tenant, GovernanceLegalHoldScopeType.RESOURCE, "document-9", "scope-r1", digest('c'),
            )
            val hold = GovernanceLegalHoldSnapshot.active(
                "hold-1", tenant, scope, 1_000, "hold-r1", digest('d'), now - 1L,
            )
            GovernanceLegalHoldResolution.held(
                resource,
                tenant,
                "hold-registry",
                "registry-r1",
                request.clock,
                listOf(hold),
                true,
                now + 40_000L,
            )
        } else {
            GovernanceLegalHoldResolution.clear(
                resource,
                tenant,
                "hold-registry",
                "registry-r1",
                request.clock,
                emptyList(),
                now + 40_000L,
            )
        }
        CompletableFuture.completedFuture(resolution)
    }
    val policies = GovernanceRetentionPolicyPort { request ->
        policyCalls += 1
        CompletableFuture.completedFuture(
            GovernanceRetentionPolicySnapshot.of(
                tenant,
                request.resource,
                "records-policy",
                "policy-r1",
                digest('e'),
                GovernanceRetentionPolicyMode.RETAIN_UNTIL,
                0L,
                now - 10L,
                now + 40_000L,
                1L,
            ),
        )
    }
    val evidence = GovernanceDeletionEvidenceResolver(
        calls,
        clock,
        holdResolver,
        policies,
        ai.icen.fw.governance.api.GovernanceRetentionEvaluator { request ->
            GovernanceRetentionAssessment.evaluate(request)
        },
    )
    val targets = GovernanceDeletionTargetPort {
        targetCalls += 1
        CompletableFuture.completedFuture(
            GovernanceDeletionPlan.REQUIRED_STAGE_ORDER.mapIndexed { index, stage ->
                GovernanceDeletionTarget.of(
                    stage,
                    "target-${index + 1}",
                    "target-r1",
                    ((index % 6) + 1).toString().repeat(64),
                )
            },
        )
    }
    val providerRegistry = GovernanceDeletionProviderRegistry { stage ->
        if (stage == missingStage) null else descriptor(stage)
    }
    val metricPort = GovernanceMetricsPort { metric -> metrics += metric }
    val signalPort = GovernanceWorkerSignalPort { record ->
        signals += record
        CompletableFuture.completedFuture(null)
    }
    val planning = ProviderNeutralGovernancePlanningRuntime(
        evidence,
        calls,
        clock,
        identifiers,
        targets,
        repository,
        signalPort,
        metricPort,
        100_000L,
    )
    val worker = ProviderNeutralGovernanceDeletionWorker(
        evidence,
        calls,
        clock,
        identifiers,
        repository,
        providerRegistry,
        metricPort,
        1_000L,
    )

    fun invocation(
        purpose: GovernancePurpose,
        idempotencyKey: String,
    ): GovernanceTrustedInvocation = GovernanceTrustedInvocation.of(
        tenant,
        principal,
        purpose,
        resource,
        idempotencyKey,
        now - 10L,
        now + 100L,
    )

    fun planCommand(idempotencyKey: String = "delete-document-9"): GovernanceDeletionPlanCommand =
        GovernanceDeletionPlanCommand.of(
            invocation(GovernancePurpose.PLAN_SECURE_DELETION, idempotencyKey),
            GovernanceVersionFence.of(resource, 7L),
            false,
        )

    fun createRun(): GovernanceDeletionRun {
        val result = planning.plan(planCommand()).toCompletableFuture().get()
        return requireNotNull(result.run)
    }

    private fun descriptor(stage: GovernanceDeletionStage): GovernanceDeletionProviderDescriptor {
        val providerId = "provider-${stage.name.lowercase().replace('_', '-')}"
        val executor = ai.icen.fw.governance.api.GovernanceDeletionStepExecutor { request ->
            executorCalls += 1
            executorAuthorizationRevisions += request.context.authorization.authorizationRevision
            executorContextDigests += request.context.contextDigest
            assertFalse(repository.transactionActive, "External executor was called inside a repository transaction")
            if (failNextExecutor) {
                failNextExecutor = false
                failedStage(IllegalStateException("simulated provider acknowledgement loss"))
            } else {
                val status = when (request.step.stage.surface) {
                    ai.icen.fw.governance.api.GovernanceDeletionSurface.INDEX,
                    ai.icen.fw.governance.api.GovernanceDeletionSurface.OBJECT ->
                        GovernanceDeletionStepStatus.VERIFIED_ABSENT
                    else -> GovernanceDeletionStepStatus.COMPLETED
                }
                CompletableFuture.completedFuture(
                    GovernanceDeletionStepReceipt.success(
                        request,
                        providerId,
                        "provider-r1",
                        status,
                        "receipt-${request.step.sequence}-${request.attempt}",
                        digest('f'),
                        request.context.requestedAtEpochMilli + 1L,
                    ),
                )
            }
        }
        val reconciler = ai.icen.fw.governance.api.GovernanceDeletionReconciler { request ->
            reconcilerCalls += 1
            assertFalse(repository.transactionActive, "Reconciler was called inside a repository transaction")
            val status = when (request.step.stage.surface) {
                ai.icen.fw.governance.api.GovernanceDeletionSurface.INDEX,
                ai.icen.fw.governance.api.GovernanceDeletionSurface.OBJECT ->
                    GovernanceDeletionStepStatus.VERIFIED_ABSENT
                else -> GovernanceDeletionStepStatus.COMPLETED
            }
            CompletableFuture.completedFuture(
                GovernanceDeletionStepReceipt.reconciled(
                    request,
                    status,
                    "reconciled-${request.step.sequence}",
                    digest('9'),
                    null,
                    request.context.requestedAtEpochMilli + 1L,
                ),
            )
        }
        return GovernanceDeletionProviderDescriptor.of(providerId, "provider-r1", executor, reconciler)
    }

    private fun <T> failedStage(error: Throwable): CompletionStage<T> = CompletableFuture<T>().also { future ->
        future.completeExceptionally(error)
    }

    fun digest(character: Char): String = character.toString().repeat(64)
}

internal class InMemoryGovernanceRepository : GovernanceDeletionRepository {
    private val byPlan = linkedMapOf<String, GovernanceDeletionRun>()
    private val byIdempotency = linkedMapOf<String, GovernanceDeletionRun>()
    val outbox = mutableListOf<GovernanceOutboxRecord>()
    var transactionActive = false
    var acknowledgeUnknownAfterNextCommit = false
    var failNextLoadAfterUnknown = false
    private var rejectNextLoad = false

    override fun findByIdempotency(tenantId: String, idempotencyKey: String): GovernanceDeletionRun? = tx {
        byIdempotency["$tenantId:$idempotencyKey"]
    }

    override fun load(tenantId: String, planId: String): GovernanceDeletionRun? = tx {
        if (rejectNextLoad) {
            rejectNextLoad = false
            error("simulated store acknowledgement and reread loss")
        }
        byPlan["$tenantId:$planId"]
    }

    override fun compareAndSet(
        tenantId: String,
        planId: String,
        expectedVersion: Long?,
        candidate: GovernanceDeletionRun,
        outbox: GovernanceOutboxRecord,
    ): GovernanceStoreResult = tx {
        val key = "$tenantId:$planId"
        val current = byPlan[key]
        val matches = if (expectedVersion == null) current == null else current?.version == expectedVersion
        if (!matches || candidate.tenantId != tenantId || candidate.planId != planId ||
            outbox.stateDigest != candidate.stateDigest) {
            GovernanceStoreResult.failed(GovernanceStoreCode.CONFLICT)
        } else {
            byPlan[key] = candidate
            byIdempotency["$tenantId:${candidate.idempotencyKey}"] = candidate
            this.outbox += outbox
            if (acknowledgeUnknownAfterNextCommit) {
                acknowledgeUnknownAfterNextCommit = false
                rejectNextLoad = failNextLoadAfterUnknown
                failNextLoadAfterUnknown = false
                GovernanceStoreResult.failed(GovernanceStoreCode.OUTCOME_UNKNOWN)
            } else {
                GovernanceStoreResult.stored(candidate)
            }
        }
    }

    fun size(): Int = byPlan.size

    private fun <T> tx(block: () -> T): T {
        check(!transactionActive)
        transactionActive = true
        return try {
            block()
        } finally {
            transactionActive = false
        }
    }
}
