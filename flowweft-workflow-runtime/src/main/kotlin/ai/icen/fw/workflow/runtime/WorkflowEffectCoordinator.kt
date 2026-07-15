package ai.icen.fw.workflow.runtime

/**
 * Durable effect control plane. It never invokes a provider: a worker checkpoints
 * PROVIDER_CALL_STARTED, performs the external call outside persistence, then records its outcome.
 */
class WorkflowEffectCoordinator(
    private val authorizationPort: WorkflowRuntimeAuthorizationPort,
    private val persistencePort: WorkflowRuntimePersistencePort,
) {
    fun claim(
        context: WorkflowTrustedCallContext,
        effectId: String,
        workerId: String,
        leaseId: String,
        expectedRecordVersion: Long,
        fencingToken: Long,
        now: Long,
        leaseExpiresAt: Long,
    ): WorkflowEffectOperationResult {
        val loaded = load(context, effectId, now)
        val record = loaded.record ?: return missingOrUnknown(loaded.storeFailed)
        val digest = operationDigest(
            WorkflowRuntimeAction.CLAIM_EFFECT,
            context,
            effectId,
            expectedRecordVersion,
            workerId,
            leaseId,
            fencingToken.toString(),
            leaseExpiresAt.toString(),
        )
        val authorization = authorize(record, context, WorkflowRuntimeAction.CLAIM_EFFECT, digest, now)
            ?: return denied()
        if (record.status == WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN ||
            record.status == WorkflowEffectDeliveryStatus.RECONCILIATION_INCIDENT
        ) return reconciliationRequired()
        if (record.status == WorkflowEffectDeliveryStatus.LEASED &&
            record.lease != null && record.lease.expiresAt <= now &&
            record.phase == WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED
        ) return reconciliationRequired()
        val eligible = record.status == WorkflowEffectDeliveryStatus.PENDING ||
            record.status == WorkflowEffectDeliveryStatus.RETRY_WAIT && record.nextAttemptAt!! <= now ||
            record.status == WorkflowEffectDeliveryStatus.LEASED && record.lease!!.expiresAt <= now &&
                record.phase == WorkflowEffectExecutionPhase.PREPARED
        if (!eligible) return failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
        val operation = try {
            WorkflowEffectClaim.of(
                context.tenantId,
                effectId,
                expectedRecordVersion,
                digest,
                authorization,
                WorkflowEffectLease.of(leaseId, workerId, fencingToken, now, leaseExpiresAt),
            )
        } catch (_: IllegalArgumentException) {
            return failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
        }
        return mutate { persistencePort.claimEffect(operation) }
    }

    fun checkpoint(
        context: WorkflowTrustedCallContext,
        effectId: String,
        expectedRecordVersion: Long,
        leaseId: String,
        fencingToken: Long,
        sequence: Long,
        phase: WorkflowEffectExecutionPhase,
        checkpointDigest: String,
        now: Long,
    ): WorkflowEffectOperationResult {
        val loaded = load(context, effectId, now)
        val record = loaded.record ?: return missingOrUnknown(loaded.storeFailed)
        val digest = operationDigest(
            WorkflowRuntimeAction.CHECKPOINT_EFFECT,
            context,
            effectId,
            expectedRecordVersion,
            leaseId,
            fencingToken.toString(),
            sequence.toString(),
            phase.code,
            checkpointDigest,
        )
        val authorization = authorize(record, context, WorkflowRuntimeAction.CHECKPOINT_EFFECT, digest, now)
            ?: return denied()
        val operation = try {
            WorkflowEffectCheckpoint.of(
                context.tenantId,
                effectId,
                expectedRecordVersion,
                digest,
                authorization,
                leaseId,
                fencingToken,
                sequence,
                phase,
                checkpointDigest,
                now,
            )
        } catch (_: IllegalArgumentException) {
            return failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
        }
        return mutate { persistencePort.checkpointEffect(operation) }
    }

    fun recordOutcome(
        context: WorkflowTrustedCallContext,
        effectId: String,
        expectedRecordVersion: Long,
        leaseId: String,
        fencingToken: Long,
        outcome: WorkflowEffectObservedOutcome,
        outcomeDigest: String,
        now: Long,
    ): WorkflowEffectOperationResult {
        val loaded = load(context, effectId, now)
        val record = loaded.record ?: return missingOrUnknown(loaded.storeFailed)
        val digest = operationDigest(
            WorkflowRuntimeAction.RECORD_EFFECT_OUTCOME,
            context,
            effectId,
            expectedRecordVersion,
            leaseId,
            fencingToken.toString(),
            outcome.code,
            outcomeDigest,
        )
        val authorization = authorize(record, context, WorkflowRuntimeAction.RECORD_EFFECT_OUTCOME, digest, now)
            ?: return denied()
        val operation = try {
            WorkflowEffectOutcome.of(
                context.tenantId,
                effectId,
                expectedRecordVersion,
                digest,
                authorization,
                leaseId,
                fencingToken,
                outcome,
                outcomeDigest,
                now,
            )
        } catch (_: IllegalArgumentException) {
            return failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
        }
        return mutate { persistencePort.recordEffectOutcome(operation) }
    }

    fun retry(
        context: WorkflowTrustedCallContext,
        effectId: String,
        expectedRecordVersion: Long,
        nextAttemptAt: Long,
        retryReasonDigest: String,
        now: Long,
    ): WorkflowEffectOperationResult {
        val loaded = load(context, effectId, now)
        val record = loaded.record ?: return missingOrUnknown(loaded.storeFailed)
        val digest = operationDigest(
            WorkflowRuntimeAction.RETRY_EFFECT,
            context,
            effectId,
            expectedRecordVersion,
            nextAttemptAt.toString(),
            retryReasonDigest,
        )
        val authorization = authorize(record, context, WorkflowRuntimeAction.RETRY_EFFECT, digest, now)
            ?: return denied()
        if (record.status == WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN ||
            record.status == WorkflowEffectDeliveryStatus.RECONCILIATION_INCIDENT
        ) return reconciliationRequired()
        if (record.status != WorkflowEffectDeliveryStatus.RETRYABLE_FAILURE) {
            return failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
        }
        val operation = try {
            WorkflowEffectRetry.of(
                context.tenantId,
                effectId,
                expectedRecordVersion,
                digest,
                authorization,
                nextAttemptAt,
                retryReasonDigest,
                now,
            )
        } catch (_: IllegalArgumentException) {
            return failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
        }
        return mutate { persistencePort.scheduleEffectRetry(operation) }
    }

    /** Worker form that fences retry scheduling to the still-current queue lease. */
    fun retryFenced(
        context: WorkflowTrustedCallContext,
        effectId: String,
        expectedRecordVersion: Long,
        leaseId: String,
        fencingToken: Long,
        nextAttemptAt: Long,
        retryReasonDigest: String,
        now: Long,
    ): WorkflowEffectOperationResult {
        val loaded = load(context, effectId, now)
        val record = loaded.record ?: return missingOrUnknown(loaded.storeFailed)
        val digest = operationDigest(
            WorkflowRuntimeAction.RETRY_EFFECT,
            context,
            effectId,
            expectedRecordVersion,
            leaseId,
            fencingToken.toString(),
            nextAttemptAt.toString(),
            retryReasonDigest,
        )
        val authorization = authorize(record, context, WorkflowRuntimeAction.RETRY_EFFECT, digest, now)
            ?: return denied()
        if (record.status != WorkflowEffectDeliveryStatus.RETRYABLE_FAILURE) {
            return if (record.status == WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN ||
                record.status == WorkflowEffectDeliveryStatus.RECONCILIATION_INCIDENT
            ) reconciliationRequired() else failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
        }
        val operation = try {
            WorkflowEffectRetry.fenced(
                context.tenantId,
                effectId,
                expectedRecordVersion,
                digest,
                authorization,
                leaseId,
                fencingToken,
                nextAttemptAt,
                retryReasonDigest,
                now,
            )
        } catch (_: IllegalArgumentException) {
            return failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
        }
        return mutate { persistencePort.scheduleEffectRetry(operation) }
    }

    fun raiseReconciliationIncident(
        context: WorkflowTrustedCallContext,
        effectId: String,
        expectedRecordVersion: Long,
        incidentId: String,
        evidenceDigest: String,
        now: Long,
    ): WorkflowEffectOperationResult {
        val loaded = load(context, effectId, now)
        val record = loaded.record ?: return missingOrUnknown(loaded.storeFailed)
        val digest = operationDigest(
            WorkflowRuntimeAction.RECONCILE_EFFECT,
            context,
            effectId,
            expectedRecordVersion,
            incidentId,
            evidenceDigest,
        )
        val authorization = authorize(record, context, WorkflowRuntimeAction.RECONCILE_EFFECT, digest, now)
            ?: return denied()
        if (record.status != WorkflowEffectDeliveryStatus.OUTCOME_UNKNOWN) {
            return failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
        }
        val operation = try {
            WorkflowEffectReconciliationIncident.of(
                context.tenantId,
                effectId,
                expectedRecordVersion,
                digest,
                authorization,
                incidentId,
                evidenceDigest,
                now,
            )
        } catch (_: IllegalArgumentException) {
            return failed(WorkflowEffectOperationCode.NOT_ELIGIBLE)
        }
        return mutate { persistencePort.raiseEffectReconciliationIncident(operation) }
    }

    private fun load(
        context: WorkflowTrustedCallContext,
        effectId: String,
        now: Long,
    ): EffectLoad = try {
        EffectLoad(persistencePort.loadEffect(context.tenantId, effectId, now), false)
    } catch (_: RuntimeException) {
        EffectLoad(null, true)
    }

    private fun missingOrUnknown(storeFailed: Boolean): WorkflowEffectOperationResult =
        if (storeFailed) {
            failed(WorkflowEffectOperationCode.STORE_OUTCOME_UNKNOWN)
        } else {
            // Effect identifiers are opaque capabilities. A missing effect and an effect that the
            // caller cannot operate must have the same public result; otherwise workers can probe
            // another tenant's or workflow's durable side effects by identifier.
            failed(WorkflowEffectOperationCode.AUTHORIZATION_DENIED)
        }

    private fun authorize(
        record: WorkflowEffectRecord,
        context: WorkflowTrustedCallContext,
        action: WorkflowRuntimeAction,
        requestDigest: String,
        now: Long,
    ): WorkflowRuntimeAuthorizationDecision? {
        val request = WorkflowRuntimeAuthorizationRequest.of(
            context,
            action,
            record.intent.instanceId,
            record.intent.definitionId,
            record.intent.definitionRef,
            record.intent.subject,
            requestDigest,
            now,
        )
        return try {
            authorizationPort.authorize(request).takeIf { decision ->
                decision.status == WorkflowRuntimeAuthorizationStatus.AUTHORIZED && decision.matches(request, now)
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun mutate(operation: () -> WorkflowEffectOperationResult): WorkflowEffectOperationResult = try {
        operation()
    } catch (_: RuntimeException) {
        failed(WorkflowEffectOperationCode.STORE_OUTCOME_UNKNOWN)
    }

    private fun operationDigest(
        action: WorkflowRuntimeAction,
        context: WorkflowTrustedCallContext,
        effectId: String,
        expectedVersion: Long,
        vararg values: String,
    ): String {
        val writer = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-effect-operation-v1")
            .text(action.code)
            .text(context.tenantId)
            .text(context.actor.type)
            .text(context.actor.id)
            .text(effectId)
            .longValue(expectedVersion)
            .integer(values.size)
        values.forEach(writer::text)
        return writer.finish()
    }

    private fun denied(): WorkflowEffectOperationResult =
        failed(WorkflowEffectOperationCode.AUTHORIZATION_DENIED)

    private fun reconciliationRequired(): WorkflowEffectOperationResult =
        failed(WorkflowEffectOperationCode.RECONCILIATION_REQUIRED)

    private fun failed(code: WorkflowEffectOperationCode): WorkflowEffectOperationResult =
        WorkflowEffectOperationResult.failed(code)

    private class EffectLoad(
        val record: WorkflowEffectRecord?,
        val storeFailed: Boolean,
    )
}
