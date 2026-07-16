package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.spi.WorkflowNotificationDelivery
import ai.icen.fw.workflow.spi.WorkflowNotificationDeliveryStatus
import ai.icen.fw.workflow.spi.WorkflowNotificationIntent
import ai.icen.fw.workflow.spi.WorkflowNotificationProvider
import ai.icen.fw.workflow.spi.WorkflowNotificationRequest
import ai.icen.fw.workflow.spi.WorkflowNotificationResult
import ai.icen.fw.workflow.spi.WorkflowProviderCallContext
import ai.icen.fw.workflow.spi.WorkflowProviderOutcome
import ai.icen.fw.workflow.spi.WorkflowProviderReceipt
import java.util.concurrent.TimeUnit

/**
 * Durable notification worker orchestration. Every store call is a short local transaction;
 * authorization, audience and provider calls always execute between transactions.
 */
class WorkflowNotificationRuntime(
    private val authorizationPort: WorkflowRuntimeAuthorizationPort,
    private val audiencePort: WorkflowNotificationAudiencePort,
    private val provider: WorkflowNotificationProvider,
    private val store: WorkflowNotificationStore,
    private val profile: WorkflowNotificationProviderProfile,
    private val clock: WorkflowWorkerClock,
) {
    fun enqueue(
        context: WorkflowTrustedCallContext,
        intent: WorkflowNotificationIntent,
        now: Long,
    ): WorkflowNotificationRuntimeResult {
        if (now < intent.createdAtEpochMilli || intent.recipients.size > profile.maximumRecipients ||
            intent.template.providerId != profile.providerId
        ) return failed(WorkflowNotificationRuntimeCode.NOT_ELIGIBLE, "enqueue-binding-invalid")
        val requestDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-notification-enqueue-v1")
            .text(context.contextDigest)
            .text(intent.intentDigest)
            .longValue(now)
            .finish()
        val authorization = authorize(
            context,
            WorkflowRuntimeAction.ENQUEUE_NOTIFICATION,
            intent.intentId,
            intent.subject,
            requestDigest,
            now,
        ) ?: return denied()
        val envelopes = try {
            WorkflowNotificationEnvelope.fanOut(intent, context.actor, now)
        } catch (_: RuntimeException) {
            return failed(WorkflowNotificationRuntimeCode.NOT_ELIGIBLE, "fanout-invalid")
        }
        val batch = try {
            WorkflowNotificationEnqueueBatch.of(
                context.tenantId,
                intent.idempotencyKey,
                intent.intentDigest,
                envelopes,
                authorizationEvidence(authorization),
                now,
            )
        } catch (_: RuntimeException) {
            return failed(WorkflowNotificationRuntimeCode.NOT_ELIGIBLE, "enqueue-batch-invalid")
        }
        return mutate { store.enqueue(batch) }
    }

    fun dispatch(
        context: WorkflowTrustedCallContext,
        envelopeId: String,
        expectedVersion: Long,
        workerId: String,
        leaseId: String,
        fencingToken: Long,
        now: Long,
        leaseExpiresAt: Long,
    ): WorkflowNotificationRuntimeResult {
        val current = load(context.tenantId, envelopeId, now)
            ?: return failed(WorkflowNotificationRuntimeCode.AUTHORIZATION_DENIED, "notification-hidden")
        if (current.version != expectedVersion) {
            return failed(WorkflowNotificationRuntimeCode.NOT_ELIGIBLE, "record-version-conflict")
        }
        if (current.status == WorkflowNotificationQueueStatus.OUTCOME_UNKNOWN ||
            current.status == WorkflowNotificationQueueStatus.PROVIDER_CALL_STARTED &&
            current.lease?.expiresAt?.let { it <= now } == true
        ) return failed(WorkflowNotificationRuntimeCode.RECONCILIATION_REQUIRED, "provider-outcome-unknown")
        val eligible = current.status == WorkflowNotificationQueueStatus.QUEUED ||
            current.status == WorkflowNotificationQueueStatus.RETRY_WAIT && current.nextAttemptAt!! <= now ||
            current.status == WorkflowNotificationQueueStatus.LEASED && current.lease!!.expiresAt <= now
        if (!eligible || leaseExpiresAt <= now) {
            return failed(WorkflowNotificationRuntimeCode.NOT_ELIGIBLE, "notification-not-claimable")
        }
        val claimDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-notification-claim-v1")
            .text(context.contextDigest)
            .text(current.envelope.envelopeDigest)
            .longValue(expectedVersion)
            .text(workerId)
            .text(leaseId)
            .longValue(fencingToken)
            .longValue(now)
            .longValue(leaseExpiresAt)
            .finish()
        val claimAuthorization = authorize(
            context,
            WorkflowRuntimeAction.CLAIM_NOTIFICATION,
            current.envelope.envelopeId,
            current.envelope.intent.subject,
            claimDigest,
            now,
        ) ?: return denied()
        val lease = try {
            WorkflowNotificationLease.of(leaseId, workerId, fencingToken, now, leaseExpiresAt)
        } catch (_: RuntimeException) {
            return failed(WorkflowNotificationRuntimeCode.NOT_ELIGIBLE, "lease-invalid")
        }
        val claimed = mutateStore {
            store.claim(
                WorkflowNotificationClaim.of(
                    context.tenantId,
                    envelopeId,
                    expectedVersion,
                    lease,
                    authorizationEvidence(claimAuthorization),
                ),
            )
        } ?: return storeUnknown()
        if (claimed.code != WorkflowNotificationStoreCode.APPLIED || claimed.record == null) {
            return mapStoreFailure(claimed.code, "claim-rejected")
        }
        val leased = claimed.record
        if (!leaseMatches(leased, lease) || leased.status != WorkflowNotificationQueueStatus.LEASED) {
            return storeUnknown()
        }

        val audienceAt = currentTime() ?: return completeBeforeCall(
            leased,
            lease,
            WorkflowNotificationQueueStatus.RETRY_WAIT,
            "clock-invalid",
            now,
        )
        val audienceRequest = WorkflowNotificationAudienceRequest.of(
            context.tenantId,
            leased.envelope.recipient,
            leased.envelope.intent.subject,
            leased.envelope.envelopeDigest,
            audienceAt,
        )
        val audience = try {
            audiencePort.evaluate(audienceRequest)
        } catch (_: RuntimeException) {
            return completeBeforeCall(
                leased,
                lease,
                retryStatus(leased),
                "audience-unavailable",
                audienceAt,
            )
        }
        val afterAudience = currentTime() ?: return completeBeforeCall(
            leased,
            lease,
            retryStatus(leased),
            "clock-invalid",
            audienceAt,
        )
        if (!audience.matches(audienceRequest, afterAudience)) {
            return completeBeforeCall(
                leased,
                lease,
                retryStatus(leased),
                "audience-evidence-invalid",
                afterAudience,
            )
        }
        if (audience.status == WorkflowNotificationAudienceStatus.REVOKED) {
            return completeBeforeCall(
                leased,
                lease,
                WorkflowNotificationQueueStatus.SUPPRESSED,
                "recipient-access-revoked",
                afterAudience,
                audience.decisionDigest,
            )
        }

        val deadline = minOf(safeAdd(afterAudience, profile.callWindowMillis), lease.expiresAt)
        if (deadline <= afterAudience || audience.validUntil < deadline) {
            return completeBeforeCall(
                leased,
                lease,
                retryStatus(leased),
                "audience-window-expired",
                afterAudience,
            )
        }
        val deliverDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-notification-deliver-v1")
            .text(context.contextDigest)
            .text(leased.envelope.envelopeDigest)
            .text(audience.decisionDigest)
            .text(profile.providerId)
            .text(profile.providerRevision)
            .longValue(leased.version)
            .longValue(afterAudience)
            .longValue(deadline)
            .finish()
        val deliverAuthorization = authorize(
            context,
            WorkflowRuntimeAction.DELIVER_NOTIFICATION,
            leased.envelope.envelopeId,
            leased.envelope.intent.subject,
            deliverDigest,
            afterAudience,
        ) ?: return denied()
        if (deliverAuthorization.validUntil < deadline) {
            return completeBeforeCall(
                leased,
                lease,
                retryStatus(leased),
                "authorization-window-expired",
                afterAudience,
            )
        }
        val providerContext = try {
            WorkflowProviderCallContext.of(
                "notify-${leased.envelope.envelopeId.take(48)}-${leased.attempt}",
                context.tenantId,
                profile.providerId,
                profile.providerRevision,
                "workflow-notification",
                afterAudience,
                deadline,
                profile.maximumInputBytes,
                profile.maximumOutputBytes,
                1,
            )
        } catch (_: RuntimeException) {
            return completeBeforeCall(
                leased,
                lease,
                WorkflowNotificationQueueStatus.TERMINAL_FAILURE,
                "provider-context-invalid",
                afterAudience,
            )
        }
        val providerRequest = try {
            WorkflowNotificationRequest.of(providerContext, leased.envelope.intent)
        } catch (_: RuntimeException) {
            return completeBeforeCall(
                leased,
                lease,
                WorkflowNotificationQueueStatus.TERMINAL_FAILURE,
                "provider-request-invalid",
                afterAudience,
            )
        }
        val checkpointed = mutateStore {
            store.checkpointProviderCall(
                WorkflowNotificationProviderCheckpoint.of(
                    context.tenantId,
                    envelopeId,
                    leased.version,
                    lease.leaseId,
                    lease.fencingToken,
                    providerRequest.requestDigest,
                    authorizationEvidence(deliverAuthorization),
                    afterAudience,
                ),
            )
        } ?: return storeUnknown()
        if (checkpointed.code != WorkflowNotificationStoreCode.APPLIED || checkpointed.record == null ||
            checkpointed.record.status != WorkflowNotificationQueueStatus.PROVIDER_CALL_STARTED ||
            !leaseMatches(checkpointed.record, lease) ||
            checkpointed.record.providerRequestDigest != providerRequest.requestDigest
        ) return storeUnknown()
        val started = checkpointed.record
        val providerResult = try {
            await(provider.send(providerRequest), deadline)
        } catch (error: Exception) {
            restoreInterrupt(error)
            return completeAfterCallUnknown(started, lease, "provider-call-outcome-unknown")
        }
        val completedAt = currentTime()
            ?: return completeAfterCallUnknown(started, lease, "clock-invalid-after-provider")
        val recordDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-notification-record-outcome-v1")
            .text(context.contextDigest)
            .text(started.envelope.envelopeDigest)
            .text(providerResult.receipt.receiptDigest)
            .longValue(started.version)
            .longValue(completedAt)
            .finish()
        val recordAuthorization = authorize(
            context,
            WorkflowRuntimeAction.RECORD_NOTIFICATION_DELIVERY,
            started.envelope.envelopeId,
            started.envelope.intent.subject,
            recordDigest,
            completedAt,
        ) ?: return failed(WorkflowNotificationRuntimeCode.OUTCOME_UNKNOWN, "outcome-record-authorization-denied")
        if (!receiptMatches(providerResult.receipt, providerContext, providerRequest.requestDigest, completedAt)) {
            return completeAfterCallUnknown(started, lease, "provider-receipt-invalid")
        }
        return completeProviderResult(started, lease, providerResult, recordAuthorization, completedAt)
    }

    fun recordDeliveryReport(
        context: WorkflowTrustedCallContext,
        report: WorkflowNotificationDeliveryReport,
        expectedVersion: Long,
        now: Long,
    ): WorkflowNotificationRuntimeResult {
        if (report.tenantId != context.tenantId || report.envelopeId.isEmpty() || report.observedAt > now ||
            report.providerId != profile.providerId || report.providerRevision != profile.providerRevision
        ) return failed(WorkflowNotificationRuntimeCode.NOT_ELIGIBLE, "delivery-report-binding-invalid")
        val record = load(context.tenantId, report.envelopeId, now)
            ?: return failed(WorkflowNotificationRuntimeCode.AUTHORIZATION_DENIED, "notification-hidden")
        if (record.version != expectedVersion ||
            record.status != WorkflowNotificationQueueStatus.ACCEPTED &&
            record.status != WorkflowNotificationQueueStatus.DELIVERED &&
            record.status != WorkflowNotificationQueueStatus.TRANSIENT_BOUNCE &&
            record.status != WorkflowNotificationQueueStatus.PERMANENT_BOUNCE
        ) return failed(WorkflowNotificationRuntimeCode.NOT_ELIGIBLE, "delivery-report-state-invalid")
        val providerMessageRef = record.delivery?.providerMessageRef
            ?: return failed(WorkflowNotificationRuntimeCode.RECEIPT_INVALID, "provider-message-reference-missing")
        if (providerMessageRef != report.providerMessageRef || report.observedAt < record.updatedAt) {
            return failed(WorkflowNotificationRuntimeCode.RECEIPT_INVALID, "delivery-report-evidence-invalid")
        }
        val authorization = authorize(
            context,
            WorkflowRuntimeAction.RECORD_NOTIFICATION_DELIVERY,
            record.envelope.envelopeId,
            record.envelope.intent.subject,
            report.reportDigest,
            now,
        ) ?: return denied()
        return mutate {
            store.recordDeliveryReport(
                WorkflowNotificationReportMutation.of(
                    report,
                    expectedVersion,
                    authorizationEvidence(authorization),
                ),
            )
        }
    }

    fun reconcile(
        context: WorkflowTrustedCallContext,
        envelopeId: String,
        expectedVersion: Long,
        resolution: WorkflowNotificationReconciliationResolution,
        providerReceipt: WorkflowProviderReceipt?,
        delivery: WorkflowNotificationDelivery?,
        evidenceDigest: String,
        nextAttemptAt: Long?,
        now: Long,
    ): WorkflowNotificationRuntimeResult {
        val record = load(context.tenantId, envelopeId, now)
            ?: return failed(WorkflowNotificationRuntimeCode.AUTHORIZATION_DENIED, "notification-hidden")
        if (record.status != WorkflowNotificationQueueStatus.OUTCOME_UNKNOWN || record.version != expectedVersion) {
            return failed(WorkflowNotificationRuntimeCode.NOT_ELIGIBLE, "reconciliation-state-invalid")
        }
        if (resolution == WorkflowNotificationReconciliationResolution.ACCEPTED &&
            (providerReceipt == null || delivery == null || providerReceipt.tenantId != context.tenantId ||
                providerReceipt.providerId != profile.providerId || providerReceipt.providerRevision != profile.providerRevision ||
                providerReceipt.outcome != WorkflowProviderOutcome.SUCCESS ||
                delivery.status != WorkflowNotificationDeliveryStatus.ACCEPTED)
        ) return failed(WorkflowNotificationRuntimeCode.RECEIPT_INVALID, "reconciliation-provider-evidence-invalid")
        if (resolution == WorkflowNotificationReconciliationResolution.NOT_SENT &&
            (record.attempt >= profile.maximumAttempts || nextAttemptAt == null || nextAttemptAt <= now)
        ) return failed(WorkflowNotificationRuntimeCode.NOT_ELIGIBLE, "reconciliation-retry-invalid")
        val requestDigest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-notification-reconcile-v1")
            .text(context.contextDigest)
            .text(record.envelope.envelopeDigest)
            .longValue(expectedVersion)
            .text(resolution.code)
            .optional(providerReceipt?.receiptDigest)
            .optional(delivery?.deliveryDigest)
            .text(evidenceDigest)
            .optional(nextAttemptAt?.toString())
            .longValue(now)
            .finish()
        val authorization = authorize(
            context,
            WorkflowRuntimeAction.RECONCILE_NOTIFICATION,
            envelopeId,
            record.envelope.intent.subject,
            requestDigest,
            now,
        ) ?: return denied()
        val reconciliation = try {
            WorkflowNotificationReconciliation.of(
                context.tenantId,
                envelopeId,
                expectedVersion,
                resolution,
                providerReceipt,
                delivery,
                evidenceDigest,
                authorizationEvidence(authorization),
                nextAttemptAt,
                now,
            )
        } catch (_: RuntimeException) {
            return failed(WorkflowNotificationRuntimeCode.NOT_ELIGIBLE, "reconciliation-invalid")
        }
        return mutate { store.reconcile(reconciliation) }
    }

    private fun completeProviderResult(
        record: WorkflowNotificationRecord,
        lease: WorkflowNotificationLease,
        result: WorkflowNotificationResult,
        authorization: WorkflowRuntimeAuthorizationDecision,
        completedAt: Long,
    ): WorkflowNotificationRuntimeResult {
        val receipt = result.receipt
        val target: WorkflowNotificationQueueStatus
        val delivery: WorkflowNotificationDelivery?
        val storedReceipt: WorkflowProviderReceipt?
        val nextAttemptAt: Long?
        if (receipt.outcome == WorkflowProviderOutcome.SUCCESS) {
            delivery = result.delivery
                ?: return completeAfterCallUnknown(record, lease, "provider-delivery-missing")
            target = when (delivery.status) {
                WorkflowNotificationDeliveryStatus.ACCEPTED -> WorkflowNotificationQueueStatus.ACCEPTED
                WorkflowNotificationDeliveryStatus.SUPPRESSED -> WorkflowNotificationQueueStatus.SUPPRESSED
                else -> return completeAfterCallUnknown(record, lease, "provider-delivery-status-invalid")
            }
            storedReceipt = receipt
            nextAttemptAt = null
        } else {
            delivery = null
            storedReceipt = null
            val retryable = receipt.failure?.retryable == true && record.attempt < profile.maximumAttempts
            target = if (retryable) WorkflowNotificationQueueStatus.RETRY_WAIT
            else WorkflowNotificationQueueStatus.TERMINAL_FAILURE
            nextAttemptAt = if (retryable) safeAdd(completedAt, profile.retryDelayMillis) else null
        }
        val evidence = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-notification-provider-outcome-v1")
            .text(record.envelope.envelopeDigest)
            .text(receipt.receiptDigest)
            .text(authorizationEvidence(authorization))
            .text(target.code)
            .finish()
        return complete(
            record,
            lease,
            target,
            storedReceipt,
            delivery,
            evidence,
            nextAttemptAt,
            completedAt,
        )
    }

    private fun completeBeforeCall(
        record: WorkflowNotificationRecord,
        lease: WorkflowNotificationLease,
        target: WorkflowNotificationQueueStatus,
        diagnostic: String,
        completedAt: Long,
        additionalEvidenceDigest: String? = null,
    ): WorkflowNotificationRuntimeResult {
        val evidence = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-notification-local-outcome-v1")
            .text(record.envelope.envelopeDigest)
            .text(target.code)
            .text(diagnostic)
            .optional(additionalEvidenceDigest)
            .longValue(completedAt)
            .finish()
        val nextAttemptAt = if (target == WorkflowNotificationQueueStatus.RETRY_WAIT) {
            safeAdd(completedAt, profile.retryDelayMillis)
        } else null
        return complete(record, lease, target, null, null, evidence, nextAttemptAt, completedAt)
    }

    private fun completeAfterCallUnknown(
        record: WorkflowNotificationRecord,
        lease: WorkflowNotificationLease,
        diagnostic: String,
    ): WorkflowNotificationRuntimeResult {
        val completedAt = currentTime() ?: record.updatedAt
        val result = completeBeforeCall(
            record,
            lease,
            WorkflowNotificationQueueStatus.OUTCOME_UNKNOWN,
            diagnostic,
            completedAt,
        )
        return if (result.record != null) result
        else failed(WorkflowNotificationRuntimeCode.OUTCOME_UNKNOWN, diagnostic)
    }

    private fun complete(
        record: WorkflowNotificationRecord,
        lease: WorkflowNotificationLease,
        target: WorkflowNotificationQueueStatus,
        receipt: WorkflowProviderReceipt?,
        delivery: WorkflowNotificationDelivery?,
        evidence: String,
        nextAttemptAt: Long?,
        completedAt: Long,
    ): WorkflowNotificationRuntimeResult {
        val completion = try {
            WorkflowNotificationCompletion.of(
                record.tenantId,
                record.envelope.envelopeId,
                record.version,
                lease.leaseId,
                lease.fencingToken,
                target,
                receipt,
                delivery,
                evidence,
                nextAttemptAt,
                completedAt,
            )
        } catch (_: RuntimeException) {
            return failed(WorkflowNotificationRuntimeCode.OUTCOME_UNKNOWN, "completion-invalid")
        }
        return mutate { store.complete(completion) }
    }

    private fun retryStatus(record: WorkflowNotificationRecord): WorkflowNotificationQueueStatus =
        if (record.attempt < profile.maximumAttempts) WorkflowNotificationQueueStatus.RETRY_WAIT
        else WorkflowNotificationQueueStatus.TERMINAL_FAILURE

    private fun authorize(
        context: WorkflowTrustedCallContext,
        action: WorkflowRuntimeAction,
        resourceId: String,
        subject: WorkflowSubjectSnapshot?,
        requestDigest: String,
        now: Long,
    ): WorkflowRuntimeAuthorizationDecision? {
        val request = try {
            WorkflowRuntimeAuthorizationRequest.of(
                context,
                action,
                resourceId,
                null,
                null,
                subject,
                requestDigest,
                now,
            )
        } catch (_: RuntimeException) {
            return null
        }
        return try {
            authorizationPort.authorize(request).takeIf { decision ->
                decision.status == WorkflowRuntimeAuthorizationStatus.AUTHORIZED && decision.matches(request, now)
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun authorizationEvidence(decision: WorkflowRuntimeAuthorizationDecision): String =
        WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-notification-authorization-evidence-v1")
            .text(decision.authorizationId)
            .text(decision.tenantId)
            .text(decision.actor.type)
            .text(decision.actor.id)
            .text(decision.action.code)
            .text(decision.instanceId)
            .text(decision.requestDigest)
            .text(decision.authorityRevision)
            .text(decision.authorityDigest)
            .longValue(decision.evaluatedAt)
            .longValue(decision.validUntil)
            .finish()

    private fun load(tenantId: String, envelopeId: String, now: Long): WorkflowNotificationRecord? = try {
        store.load(tenantId, envelopeId, now)
    } catch (_: RuntimeException) {
        null
    }

    private fun receiptMatches(
        receipt: WorkflowProviderReceipt,
        context: WorkflowProviderCallContext,
        requestDigest: String,
        now: Long,
    ): Boolean = receipt.contextDigest == context.contextDigest && receipt.requestDigest == requestDigest &&
        receipt.tenantId == context.tenantId && receipt.providerId == profile.providerId &&
        receipt.providerRevision == profile.providerRevision && receipt.completedAtEpochMilli <= now &&
        now <= receipt.expiresAtEpochMilli && now <= context.deadlineEpochMilli

    private fun leaseMatches(record: WorkflowNotificationRecord, lease: WorkflowNotificationLease): Boolean =
        record.lease?.leaseId == lease.leaseId && record.lease?.workerId == lease.workerId &&
            record.lease?.fencingToken == lease.fencingToken && record.lease?.expiresAt == lease.expiresAt

    private fun mutate(operation: () -> WorkflowNotificationStoreResult): WorkflowNotificationRuntimeResult {
        val result = mutateStore(operation) ?: return storeUnknown()
        return when (result.code) {
            WorkflowNotificationStoreCode.APPLIED -> WorkflowNotificationRuntimeResult.success(
                WorkflowNotificationRuntimeCode.COMMITTED,
                requireNotNull(result.record),
            )
            WorkflowNotificationStoreCode.REPLAYED -> WorkflowNotificationRuntimeResult.success(
                WorkflowNotificationRuntimeCode.REPLAYED,
                requireNotNull(result.record),
            )
            WorkflowNotificationStoreCode.CONFLICT -> failed(
                WorkflowNotificationRuntimeCode.IDEMPOTENCY_CONFLICT,
                "notification-conflict",
            )
            WorkflowNotificationStoreCode.NOT_ELIGIBLE,
            WorkflowNotificationStoreCode.LEASE_MISMATCH -> failed(
                WorkflowNotificationRuntimeCode.NOT_ELIGIBLE,
                "notification-not-eligible",
            )
            else -> storeUnknown()
        }
    }

    private fun mutateStore(operation: () -> WorkflowNotificationStoreResult): WorkflowNotificationStoreResult? = try {
        operation()
    } catch (_: RuntimeException) {
        null
    }

    private fun mapStoreFailure(code: WorkflowNotificationStoreCode, diagnostic: String): WorkflowNotificationRuntimeResult =
        when (code) {
            WorkflowNotificationStoreCode.CONFLICT -> failed(
                WorkflowNotificationRuntimeCode.IDEMPOTENCY_CONFLICT,
                diagnostic,
            )
            WorkflowNotificationStoreCode.NOT_ELIGIBLE,
            WorkflowNotificationStoreCode.LEASE_MISMATCH -> failed(
                WorkflowNotificationRuntimeCode.NOT_ELIGIBLE,
                diagnostic,
            )
            else -> storeUnknown()
        }

    private fun currentTime(): Long? = try {
        clock.currentTimeMillis().takeIf { it >= 0L }
    } catch (_: RuntimeException) {
        null
    }

    private fun await(
        stage: java.util.concurrent.CompletionStage<WorkflowNotificationResult>,
        deadline: Long,
    ): WorkflowNotificationResult {
        val remaining = deadline - (currentTime() ?: deadline)
        require(remaining > 0L) { "Workflow notification provider deadline expired." }
        return stage.toCompletableFuture().get(remaining, TimeUnit.MILLISECONDS)
    }

    private fun safeAdd(value: Long, delta: Long): Long =
        if (Long.MAX_VALUE - value < delta) Long.MAX_VALUE else value + delta

    private fun restoreInterrupt(error: Exception) {
        if (error is InterruptedException || error.cause is InterruptedException) Thread.currentThread().interrupt()
    }

    private fun denied(): WorkflowNotificationRuntimeResult =
        failed(WorkflowNotificationRuntimeCode.AUTHORIZATION_DENIED, "authorization-denied")

    private fun storeUnknown(): WorkflowNotificationRuntimeResult =
        failed(WorkflowNotificationRuntimeCode.STORE_OUTCOME_UNKNOWN, "store-outcome-unknown")

    private fun failed(code: WorkflowNotificationRuntimeCode, diagnostic: String): WorkflowNotificationRuntimeResult =
        WorkflowNotificationRuntimeResult.failed(code, diagnostic)
}
