package ai.icen.fw.application.retention

import ai.icen.fw.application.outbox.LeasedOutboxEventHandler
import ai.icen.fw.application.outbox.OutboxEventLease
import ai.icen.fw.application.outbox.OutboxEventMutationRepository
import ai.icen.fw.application.outbox.OutboxEventStatus
import ai.icen.fw.application.outbox.OutboxLeaseLostException
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.retention.SecureDeletionStage
import ai.icen.fw.spi.event.OutboxHandlingResult
import ai.icen.fw.spi.event.OutboxHandlingStatus
import ai.icen.fw.spi.retention.SecureDeletionProvider
import ai.icen.fw.spi.retention.SecureDeletionProviderReceipt
import ai.icen.fw.spi.retention.SecureDeletionProviderRequest
import ai.icen.fw.spi.retention.SecureDeletionProviderStatus
import ai.icen.fw.spi.retention.SecureDeletionTarget
import java.time.Clock
import java.time.Duration
import java.util.EnumMap

/**
 * Lease-fenced, retryable secure-deletion worker implemented on the existing
 * Outbox worker contract. Provider calls occur only between short local
 * transactions; every result is re-fenced before it can advance the plan.
 */
class SecureDeletionOutboxEventHandler @JvmOverloads constructor(
    private val deletions: SecureDeletionRepository,
    private val outboxMutations: OutboxEventMutationRepository,
    private val transaction: ApplicationTransaction,
    providers: List<SecureDeletionProvider>,
    private val clock: Clock,
    private val providerTimeout: Duration = Duration.ofSeconds(30),
) : LeasedOutboxEventHandler {
    private val providersByTarget: Map<SecureDeletionTarget, ProviderBinding>

    init {
        val bindings = providers.map { provider ->
            ProviderBinding(
                providerId = provider.providerId().also {
                    require(it.isNotBlank()) { "Secure-deletion provider id must not be blank." }
                },
                target = provider.target(),
                provider = provider,
            )
        }
        require(bindings.map { it.providerId }.distinct().size == bindings.size) {
            "Secure-deletion provider ids must be unique."
        }
        require(bindings.map { it.target }.toSet() == SecureDeletionTarget.values().toSet()) {
            "Exactly one index and one object-storage deletion provider are required."
        }
        require(bindings.map { it.target }.distinct().size == bindings.size) {
            "Only one secure-deletion provider may own each external target."
        }
        require(!providerTimeout.isNegative && !providerTimeout.isZero) {
            "Secure-deletion provider timeout must be positive."
        }
        providersByTarget = EnumMap<SecureDeletionTarget, ProviderBinding>(SecureDeletionTarget::class.java).apply {
            bindings.forEach { binding -> put(binding.target, binding) }
        }
    }

    override fun supports(event: OutboxEvent): Boolean =
        event.type == SecureDeletionApplicationService.SECURE_DELETION_REQUESTED_EVENT_TYPE

    /** Secure deletion never accepts the legacy tokenless worker path. */
    override fun handle(event: OutboxEvent): OutboxHandlingResult = OutboxHandlingResult(
        OutboxHandlingStatus.PERMANENT_FAILURE,
        "Secure deletion requires the current persisted Outbox lease.",
    )

    override fun handle(lease: OutboxEventLease): OutboxHandlingResult {
        val eventIdentity = parseEvent(lease.event)
            ?: return permanent("Secure-deletion event payload is incomplete or invalid.")
        repeat(EXTERNAL_STAGE_COUNT) {
            when (val preparation = transaction.execute { prepare(lease, eventIdentity) }) {
                is Preparation.Completed -> return succeeded("Secure deletion is already complete.")
                is Preparation.Failed -> return permanent(preparation.message)
                is Preparation.Ready -> {
                    val binding = providersByTarget.getValue(preparation.request.target)
                    val previousReceiptFailure = validatePreviousReceipt(binding, preparation)
                    if (previousReceiptFailure != null) {
                        transaction.execute { recordPermanentFailure(lease, preparation, previousReceiptFailure) }
                        return permanent(previousReceiptFailure)
                    }
                    val providerReceipt = try {
                        invokeProvider(binding, preparation)
                    } catch (failure: Exception) {
                        val message = "Secure-deletion provider invocation failed: ${failure.javaClass.name}"
                        transaction.execute { recordRetryableInvocationFailure(lease, preparation, message) }
                        return retryable(message)
                    }
                    val contractFailure = validateProviderReceipt(binding, preparation, providerReceipt)
                    if (contractFailure != null) {
                        transaction.execute { recordPermanentFailure(lease, preparation, contractFailure) }
                        return permanent(contractFailure)
                    }
                    if (
                        preparation.reconciling &&
                        providerReceipt.status == SecureDeletionProviderStatus.RETRYABLE_FAILURE
                    ) {
                        val message = providerReceipt.message ?: "Secure-deletion receipt reconciliation should be retried."
                        transaction.execute { recordRetryableInvocationFailure(lease, preparation, message) }
                        return retryable(message)
                    }
                    when (transaction.execute { completeAttempt(lease, preparation, providerReceipt) }) {
                        Completion.CONTINUE -> Unit
                        Completion.SUCCEEDED -> return succeeded("Secure deletion completed with verified provider receipts.")
                        Completion.RETRY -> return retryable(
                            providerReceipt.message ?: "Secure-deletion provider result requires reconciliation.",
                        )
                        Completion.FAILED -> return permanent(
                            providerReceipt.message ?: "Secure-deletion provider reported a permanent failure.",
                        )
                    }
                }
            }
        }
        return permanent("Secure-deletion plan did not reach a terminal state after its fixed external stages.")
    }

    /** Called only after the Outbox row itself is durably FAILED; performs no provider call. */
    override fun onExhausted(event: OutboxEvent, message: String) {
        val identity = parseEvent(event) ?: return
        transaction.execute {
            val execution = deletions.findForMutation(event.tenantId, identity.planId) ?: return@execute
            if (
                execution.tenantId != event.tenantId ||
                execution.planId != identity.planId ||
                !execution.matchesDispatch(event.id, identity.resourceRevision) ||
                execution.tombstoneId != identity.tombstoneId ||
                execution.resourceType != identity.resourceType ||
                execution.resourceId != identity.resourceId
            ) {
                return@execute
            }
            val outboxState = outboxMutations.findForMutation(event.tenantId, event.id) ?: return@execute
            if (
                outboxState.status != OutboxEventStatus.FAILED ||
                outboxState.eventType != SecureDeletionApplicationService.SECURE_DELETION_REQUESTED_EVENT_TYPE
            ) {
                return@execute
            }
            if (execution.status != SecureDeletionExecutionStatus.SUCCEEDED) {
                val now = now()
                if (execution.status != SecureDeletionExecutionStatus.FAILED) {
                    execution.markExhausted(message, now)
                    deletions.save(execution)
                }
                deletions.appendFailureEvidenceIfAbsent(execution.failureEvidence(now))
            }
        }
    }

    private fun prepare(lease: OutboxEventLease, identity: EventIdentity): Preparation {
        val event = lease.event
        val execution = deletions.findForMutation(event.tenantId, identity.planId)
            ?: return Preparation.Failed("Secure-deletion execution was not found in the event tenant.")
        if (
            execution.tenantId != event.tenantId ||
            execution.planId != identity.planId ||
            !execution.matchesDispatch(event.id, identity.resourceRevision) ||
            execution.tombstoneId != identity.tombstoneId ||
            execution.resourceType != identity.resourceType ||
            execution.resourceId != identity.resourceId
        ) {
            return Preparation.Failed("Secure-deletion event conflicts with its durable revision fence.")
        }
        if (execution.status == SecureDeletionExecutionStatus.SUCCEEDED) return Preparation.Completed
        if (execution.status == SecureDeletionExecutionStatus.FAILED) {
            return Preparation.Failed(execution.lastError ?: "Secure-deletion execution requires operator intervention.")
        }
        if (execution.currentStage == SecureDeletionStage.FINALIZE_DATABASE) {
            requireCurrentLease(lease)
            val now = now()
            execution.finalizeDatabase(now)
            deletions.save(execution)
            deletions.appendCompletionEvidenceIfAbsent(execution.completionEvidence(now))
            return Preparation.Completed
        }
        if (
            execution.currentStage != SecureDeletionStage.PURGE_INDEX_PROJECTIONS &&
            execution.currentStage != SecureDeletionStage.PURGE_OBJECT_STORAGE
        ) {
            return Preparation.Failed("Secure-deletion execution has an invalid external stage.")
        }
        requireCurrentLease(lease)
        val target = StoredSecureDeletionReceipt.targetFor(execution.currentStage)
        return Preparation.Ready(
            planId = execution.planId,
            tenantId = execution.tenantId,
            stage = execution.currentStage,
            request = SecureDeletionProviderRequest(
                tenantId = execution.tenantId,
                planId = execution.planId,
                tombstoneId = execution.tombstoneId,
                resourceType = execution.resourceType,
                resourceId = execution.resourceId,
                resourceRevision = execution.resourceRevision,
                target = target,
                idempotencyKey = execution.idempotencyKeyFor(execution.currentStage),
                timeout = providerTimeout,
            ),
            previousReceipt = execution.latestReceipt(execution.currentStage),
            reconciling = execution.latestReceipt(execution.currentStage)
                ?.providerReceipt?.status == SecureDeletionProviderStatus.ACCEPTED_UNVERIFIED,
        )
    }

    private fun invokeProvider(
        binding: ProviderBinding,
        preparation: Preparation.Ready,
    ): SecureDeletionProviderReceipt {
        val previous = preparation.previousReceipt?.providerReceipt
        return if (preparation.reconciling) {
            binding.provider.reconcileDeletion(preparation.request, requireNotNull(previous))
        } else {
            binding.provider.requestDeletion(preparation.request)
        }
    }

    private fun completeAttempt(
        lease: OutboxEventLease,
        preparation: Preparation.Ready,
        receipt: SecureDeletionProviderReceipt,
    ): Completion {
        val execution = currentExecution(lease, preparation)
        val now = now()
        execution.recordProviderReceipt(
            StoredSecureDeletionReceipt(
                stage = preparation.stage,
                idempotencyKey = preparation.request.idempotencyKey,
                providerReceipt = receipt,
                recordedAt = now,
            ),
            now,
        )
        return when (receipt.status) {
            SecureDeletionProviderStatus.VERIFIED_ABSENT -> {
                execution.advanceVerifiedStage(now)
                if (execution.currentStage == SecureDeletionStage.FINALIZE_DATABASE) {
                    execution.finalizeDatabase(now)
                }
                deletions.save(execution)
                if (execution.status == SecureDeletionExecutionStatus.SUCCEEDED) {
                    deletions.appendCompletionEvidenceIfAbsent(execution.completionEvidence(now))
                    Completion.SUCCEEDED
                } else {
                    Completion.CONTINUE
                }
            }

            SecureDeletionProviderStatus.ACCEPTED_UNVERIFIED,
            SecureDeletionProviderStatus.RETRYABLE_FAILURE,
            -> {
                deletions.save(execution)
                Completion.RETRY
            }

            SecureDeletionProviderStatus.PERMANENT_FAILURE -> {
                deletions.save(execution)
                deletions.appendFailureEvidenceIfAbsent(execution.failureEvidence(now))
                Completion.FAILED
            }
        }
    }

    private fun recordRetryableInvocationFailure(
        lease: OutboxEventLease,
        preparation: Preparation.Ready,
        message: String,
    ) {
        val execution = currentExecution(lease, preparation)
        execution.recordRetryableFailure(preparation.stage, message, now())
        deletions.save(execution)
    }

    private fun recordPermanentFailure(
        lease: OutboxEventLease,
        preparation: Preparation.Ready,
        message: String,
    ) {
        val execution = currentExecution(lease, preparation)
        val now = now()
        execution.markExhausted(message, now)
        deletions.save(execution)
        deletions.appendFailureEvidenceIfAbsent(execution.failureEvidence(now))
    }

    /** Locks execution before the Outbox row; every mutation path uses this order. */
    private fun currentExecution(
        lease: OutboxEventLease,
        preparation: Preparation.Ready,
    ): SecureDeletionExecution {
        val execution = deletions.findForMutation(preparation.tenantId, preparation.planId)
            ?: throw OutboxLeaseLostException("Secure-deletion execution no longer exists in the event tenant.")
        if (
            execution.tenantId != preparation.tenantId ||
            execution.planId != preparation.planId ||
            execution.currentStage != preparation.stage ||
            execution.resourceRevision != preparation.request.resourceRevision ||
            execution.idempotencyKeyFor(preparation.stage) != preparation.request.idempotencyKey ||
            execution.status == SecureDeletionExecutionStatus.SUCCEEDED ||
            execution.status == SecureDeletionExecutionStatus.FAILED
        ) {
            throw OutboxLeaseLostException("Secure-deletion execution advanced beyond this provider attempt.")
        }
        requireCurrentLease(lease)
        return execution
    }

    private fun requireCurrentLease(lease: OutboxEventLease) {
        val state = outboxMutations.findForMutation(lease.event.tenantId, lease.event.id)
            ?: throw OutboxLeaseLostException("Secure-deletion Outbox event no longer exists in the current tenant.")
        state.requireCurrentLease(lease)
    }

    private fun validateProviderReceipt(
        binding: ProviderBinding,
        preparation: Preparation.Ready,
        receipt: SecureDeletionProviderReceipt,
    ): String? {
        if (receipt.providerId != binding.providerId || receipt.target != binding.target) {
            return "Secure-deletion provider receipt identity does not match the configured provider."
        }
        if (receipt.target != preparation.request.target) {
            return "Secure-deletion provider receipt target does not match the current plan stage."
        }
        if (receipt.requestBindingDigest != preparation.request.bindingDigest) {
            return "Secure-deletion provider receipt does not bind the exact tenant-scoped request."
        }
        if ((receipt.receiptReference?.length ?: 0) > MAX_RECEIPT_REFERENCE_LENGTH) {
            return "Secure-deletion provider receipt reference exceeds the persistence limit."
        }
        if ((receipt.message?.length ?: 0) > MAX_DIAGNOSTIC_LENGTH) {
            return "Secure-deletion provider diagnostic exceeds the persistence limit."
        }
        if (
            receipt.evidence.size > MAX_EVIDENCE_ENTRIES ||
            receipt.evidence.any { (key, value) ->
                key.length > MAX_EVIDENCE_KEY_LENGTH || value.length > MAX_EVIDENCE_VALUE_LENGTH
            }
        ) {
            return "Secure-deletion provider evidence exceeds the persistence limit."
        }
        return null
    }

    private fun validatePreviousReceipt(
        binding: ProviderBinding,
        preparation: Preparation.Ready,
    ): String? {
        val previous = preparation.previousReceipt ?: return null
        if (
            previous.providerReceipt.providerId != binding.providerId ||
            previous.providerReceipt.target != binding.target ||
            previous.providerReceipt.requestBindingDigest != preparation.request.bindingDigest ||
            previous.idempotencyKey != preparation.request.idempotencyKey
        ) {
            return "Secure-deletion provider configuration changed after a receipt was persisted."
        }
        return null
    }

    private fun parseEvent(event: OutboxEvent): EventIdentity? {
        if (!supports(event)) return null
        return try {
            val revision = event.payload[SecureDeletionApplicationService.RESOURCE_REVISION_PAYLOAD_KEY]
                ?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
            EventIdentity(
                planId = Identifier(event.payload[SecureDeletionApplicationService.PLAN_ID_PAYLOAD_KEY] ?: return null),
                tombstoneId = Identifier(event.payload[SecureDeletionApplicationService.TOMBSTONE_ID_PAYLOAD_KEY] ?: return null),
                resourceType = event.payload[SecureDeletionApplicationService.RESOURCE_TYPE_PAYLOAD_KEY]
                    ?.takeIf { it.isNotBlank() } ?: return null,
                resourceId = Identifier(event.payload[SecureDeletionApplicationService.RESOURCE_ID_PAYLOAD_KEY] ?: return null),
                resourceRevision = revision,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun now(): Long = clock.millis().also {
        require(it >= 0) { "Secure-deletion worker clock must not return a negative time." }
    }

    private fun succeeded(message: String) = OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED, message)

    private fun retryable(message: String) = OutboxHandlingResult(
        OutboxHandlingStatus.RETRYABLE_FAILURE,
        message.take(MAX_DIAGNOSTIC_LENGTH),
    )

    private fun permanent(message: String) = OutboxHandlingResult(
        OutboxHandlingStatus.PERMANENT_FAILURE,
        message.take(MAX_DIAGNOSTIC_LENGTH),
    )

    private class ProviderBinding(
        val providerId: String,
        val target: SecureDeletionTarget,
        val provider: SecureDeletionProvider,
    )

    private class EventIdentity(
        val planId: Identifier,
        val tombstoneId: Identifier,
        val resourceType: String,
        val resourceId: Identifier,
        val resourceRevision: Long,
    )

    private sealed class Preparation {
        object Completed : Preparation()
        class Failed(val message: String) : Preparation()
        class Ready(
            val planId: Identifier,
            val tenantId: Identifier,
            val stage: SecureDeletionStage,
            val request: SecureDeletionProviderRequest,
            val previousReceipt: StoredSecureDeletionReceipt?,
            val reconciling: Boolean,
        ) : Preparation()
    }

    private enum class Completion { CONTINUE, SUCCEEDED, RETRY, FAILED }

    private companion object {
        const val EXTERNAL_STAGE_COUNT = 2
        const val MAX_DIAGNOSTIC_LENGTH = 1024
        const val MAX_RECEIPT_REFERENCE_LENGTH = 2048
        const val MAX_EVIDENCE_ENTRIES = 64
        const val MAX_EVIDENCE_KEY_LENGTH = 128
        const val MAX_EVIDENCE_VALUE_LENGTH = 512
    }
}
