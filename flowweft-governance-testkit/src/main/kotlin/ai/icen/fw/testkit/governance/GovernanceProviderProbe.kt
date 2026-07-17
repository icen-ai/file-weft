package ai.icen.fw.testkit.governance

import ai.icen.fw.governance.api.GovernanceDeletionExecutionRequest
import ai.icen.fw.governance.api.GovernanceDeletionReconciliationRequest
import ai.icen.fw.governance.api.GovernanceDeletionStage
import ai.icen.fw.governance.api.GovernanceDeletionStepReceipt
import ai.icen.fw.governance.api.GovernanceDeletionStepStatus
import ai.icen.fw.governance.api.GovernanceDeletionSurface
import ai.icen.fw.governance.runtime.GovernanceDeletionProviderDescriptor
import ai.icen.fw.governance.runtime.GovernanceDeletionProviderRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Observable provider boundary required by the deletion recovery contract suite.
 * Implementations must count real external mutations, not Java method calls.
 */
interface GovernanceObservableProviderProbe {
    fun registry(): GovernanceDeletionProviderRegistry

    fun crashAfterNextMutation()

    fun executionCount(): Long

    fun mutationCount(): Long

    fun reconciliationCount(): Long

    fun mutationCount(operationReference: String): Long

    fun lastOriginalOperationReference(): String?

    fun lastReconciledOperationReference(): String?
}

/**
 * Deterministic deletion provider. Mutation is idempotent by the exact operation reference and
 * reconciliation is read-only against the exact original execution request.
 */
class MutationCountingGovernanceProviderProbe private constructor() : GovernanceObservableProviderProbe {
    private val lock = Any()
    private val outcomes = linkedMapOf<String, ProviderOutcome>()
    private val executions = AtomicLong()
    private val mutations = AtomicLong()
    private val reconciliations = AtomicLong()
    private val failAcknowledgement = AtomicBoolean()
    @Volatile private var lastOriginal: String? = null
    @Volatile private var lastReconciled: String? = null

    private val descriptors: Map<GovernanceDeletionStage, GovernanceDeletionProviderDescriptor> =
        GovernanceDeletionStage.values().associateWith { stage -> descriptor(stage) }
    private val providerRegistry = GovernanceDeletionProviderRegistry { stage -> descriptors[stage] }

    override fun registry(): GovernanceDeletionProviderRegistry = providerRegistry

    override fun crashAfterNextMutation() {
        failAcknowledgement.set(true)
    }

    override fun executionCount(): Long = executions.get()

    override fun mutationCount(): Long = mutations.get()

    override fun reconciliationCount(): Long = reconciliations.get()

    override fun mutationCount(operationReference: String): Long = synchronized(lock) {
        if (outcomes.containsKey(operationReference)) 1L else 0L
    }

    override fun lastOriginalOperationReference(): String? = lastOriginal

    override fun lastReconciledOperationReference(): String? = lastReconciled

    private fun descriptor(stage: GovernanceDeletionStage): GovernanceDeletionProviderDescriptor {
        val providerId = "contract-${stage.name.lowercase().replace('_', '-')}"
        return GovernanceDeletionProviderDescriptor.of(
            providerId,
            "1",
            { request -> execute(providerId, request) },
            { request -> reconcile(request) },
        )
    }

    private fun execute(
        providerId: String,
        request: GovernanceDeletionExecutionRequest,
    ): CompletionStage<GovernanceDeletionStepReceipt> {
        executions.incrementAndGet()
        val operationReference = request.context.idempotencyKey
        val outcome = synchronized(lock) {
            val existing = outcomes[operationReference]
            if (existing != null) {
                check(existing.executionRequestDigest == request.requestDigest &&
                    existing.stage == request.step.stage && existing.providerId == providerId
                ) { "Governance provider operation reference was reused for changed arguments." }
                existing
            } else {
                ProviderOutcome(
                    operationReference,
                    request.requestDigest,
                    request.step.stage,
                    providerId,
                    successStatus(request.step.stage.surface),
                    "provider-receipt-${request.step.sequence}-${request.attempt}",
                    GovernanceContractAssertions.sha256("result:${request.requestDigest}"),
                ).also { created ->
                    outcomes[operationReference] = created
                    mutations.incrementAndGet()
                    lastOriginal = operationReference
                }
            }
        }
        if (failAcknowledgement.compareAndSet(true, false)) {
            return failedStage(IllegalStateException("synthetic provider acknowledgement loss"))
        }
        return CompletableFuture.completedFuture(outcome.successReceipt(request))
    }

    private fun reconcile(
        request: GovernanceDeletionReconciliationRequest,
    ): CompletionStage<GovernanceDeletionStepReceipt> {
        val operationReference = requireNotNull(request.previousReceipt.receiptReference) {
            "Governance reconciliation requires the exact original operation reference."
        }
        val outcome = synchronized(lock) {
            requireNotNull(outcomes[operationReference]) {
                "Governance reconciliation cannot find the exact original operation."
            }.also { stored ->
                check(stored.executionRequestDigest == request.previousReceipt.executionRequestDigest &&
                    stored.stage == request.step.stage &&
                    stored.providerId == request.previousReceipt.providerId
                ) { "Governance reconciliation evidence does not match the original operation." }
                reconciliations.incrementAndGet()
                lastReconciled = operationReference
            }
        }
        return CompletableFuture.completedFuture(
            GovernanceDeletionStepReceipt.reconciled(
                request,
                outcome.status,
                "reconciled-${request.step.sequence}-${request.previousReceipt.attempt}",
                outcome.resultDigest,
                null,
                minOf(request.context.deadlineEpochMilli, request.context.requestedAtEpochMilli + 1L),
            ),
        )
    }

    private fun successStatus(surface: GovernanceDeletionSurface): GovernanceDeletionStepStatus = when (surface) {
        GovernanceDeletionSurface.INDEX,
        GovernanceDeletionSurface.OBJECT,
        -> GovernanceDeletionStepStatus.VERIFIED_ABSENT
        GovernanceDeletionSurface.METADATA,
        GovernanceDeletionSurface.OUTBOX,
        -> GovernanceDeletionStepStatus.COMPLETED
    }

    private fun <T> failedStage(failure: Throwable): CompletionStage<T> = CompletableFuture<T>().also { future ->
        future.completeExceptionally(failure)
    }

    private class ProviderOutcome(
        val operationReference: String,
        val executionRequestDigest: String,
        val stage: GovernanceDeletionStage,
        val providerId: String,
        val status: GovernanceDeletionStepStatus,
        val receiptReference: String,
        val resultDigest: String,
    ) {
        fun successReceipt(request: GovernanceDeletionExecutionRequest): GovernanceDeletionStepReceipt =
            GovernanceDeletionStepReceipt.success(
                request,
                providerId,
                "1",
                status,
                receiptReference,
                resultDigest,
                minOf(request.context.deadlineEpochMilli, request.context.requestedAtEpochMilli + 1L),
            )
    }

    companion object {
        @JvmStatic
        fun create(): MutationCountingGovernanceProviderProbe = MutationCountingGovernanceProviderProbe()
    }
}
