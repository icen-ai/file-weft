package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowDelegationPolicy
import ai.icen.fw.workflow.api.WorkflowInstanceRef
import ai.icen.fw.workflow.api.WorkflowParticipantResolution
import ai.icen.fw.workflow.api.WorkflowParticipantResolutionRequest
import ai.icen.fw.workflow.api.WorkflowParticipantResolutionStage
import ai.icen.fw.workflow.api.WorkflowParticipantResolutionStatus
import ai.icen.fw.workflow.api.WorkflowParticipantResolver
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.api.WorkflowWorkItemRef
import ai.icen.fw.workflow.domain.WorkflowEffectCode
import ai.icen.fw.workflow.domain.WorkflowEffectIntent
import ai.icen.fw.workflow.domain.WorkflowExecutionIds
import ai.icen.fw.workflow.domain.WorkflowParticipantActivationReceipt
import ai.icen.fw.workflow.spi.WorkflowOrganizationAuthority
import ai.icen.fw.workflow.spi.WorkflowOrganizationSnapshotRequest
import ai.icen.fw.workflow.spi.WorkflowProviderCallContext
import ai.icen.fw.workflow.spi.WorkflowProviderOutcome
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Production participant-resolution handler. It reuses the public resolver and organization
 * authority SPI, pins the exact directory snapshot, and emits only v3 organization-bound domain
 * receipts. No repository or database dependency is present in this class.
 */
class WorkflowParticipantResolutionEffectHandler @JvmOverloads constructor(
    private val participantResolver: WorkflowParticipantResolver,
    private val organizationAuthority: WorkflowOrganizationAuthority,
    private val durableRuntime: WorkflowDurableRuntime,
    private val clock: WorkflowWorkerClock,
    providerId: String,
    providerRevision: String,
    callWindowMillis: Long = 30_000L,
    maximumPrincipals: Int = 256,
    retryDelayMillis: Long = 5_000L,
    iterationBudget: Int = 256,
    private val participantAuthorizationPort: WorkflowRuntimeAuthorizationPort? = null,
) : WorkflowEffectHandler {
    private val providerId: String = WorkflowRuntimeSupport.code(
        providerId,
        "Workflow participant organization provider id is invalid.",
    )
    private val providerRevision: String = WorkflowRuntimeSupport.text(
        providerRevision,
        256,
        "Workflow participant organization provider revision is invalid.",
    )
    private val callWindowMillis: Long = callWindowMillis.also { value ->
        require(value in 1L..300_000L) { "Workflow participant call window is invalid." }
    }
    private val maximumPrincipals: Int = maximumPrincipals.also { value ->
        require(value in 1..256) { "Workflow participant maximum principal count is invalid." }
    }
    private val retryDelayMillis: Long = retryDelayMillis.also { value ->
        require(value in 1L..86_400_000L) { "Workflow participant retry delay is invalid." }
    }
    private val iterationBudget: Int = WorkflowRuntimeSupport.positive(
        iterationBudget,
        1024,
        "Workflow participant apply iteration budget is invalid.",
    )

    override fun effectCode(): WorkflowEffectCode = WorkflowEffectCode.PARTICIPANT_RESOLUTION

    override fun execute(request: WorkflowEffectHandlerRequest): WorkflowEffectJobStoredResult {
        if (request.effect.intent.code != effectCode()) {
            return failure("unsupported-effect", false, request.now)
        }
        val intent = request.effect.intent
        val workItem = request.state.humanWorkItems.firstOrNull { item ->
            item.workItemId == intent.workItemId && item.nodeExecutionId == intent.nodeExecutionId &&
                item.tokenId == intent.tokenId && item.nodeId == intent.nodeId &&
                item.activeRuleIndex == intent.ruleIndex
        } ?: return failure("work-item-binding-invalid", false, request.now)
        val node = try {
            request.definition.index.node(workItem.nodeId)
        } catch (_: RuntimeException) {
            return failure("definition-node-missing", false, request.now)
        }
        val rule = node.humanTaskPolicy?.participantRules?.getOrNull(workItem.activeRuleIndex)
            ?: return failure("participant-rule-missing", false, request.now)
        if (intent.payloadDigest != participantPayloadDigest(workItem, rule)) {
            return failure("participant-effect-binding-invalid", false, request.now)
        }

        // Participant discovery can disclose organization membership. Authorize the exact tenant,
        // principal, definition version, subject, work item and provider before any directory call.
        // The resulting authority revision/evidence digest is also embedded into the resolver
        // request, so even a structurally valid response from another authorization revision is
        // unusable here.
        val authorizationStart = currentTimeWithin(request)
            ?: return failure("participant-authorization-expired", false, request.now)
        val authorizationRequestDigest = participantAuthorizationRequestDigest(request, workItem, rule)
        val authorizationRequest = WorkflowRuntimeAuthorizationRequest.of(
            request.callContext,
            WorkflowRuntimeAction.RESOLVE_PARTICIPANTS,
            request.state.instanceId,
            request.state.definitionId,
            request.state.definitionRef,
            request.state.subject,
            authorizationRequestDigest,
            authorizationStart,
        )
        val authorization = try {
            participantAuthorizationPort?.authorize(authorizationRequest)
        } catch (_: RuntimeException) {
            null
        }
        if (authorization == null ||
            authorization.status != WorkflowRuntimeAuthorizationStatus.AUTHORIZED ||
            !authorization.matches(authorizationRequest, authorizationStart)
        ) {
            return failure("participant-authorization-denied", false, authorizationStart)
        }
        val authorizationEvidenceDigest = participantAuthorizationEvidenceDigest(
            authorizationRequest,
            authorization,
        )

        val snapshotStart = currentTimeWithin(request) ?: return failure("participant-deadline-expired", true, request.now)
        val snapshotDeadline = minOf(request.deadline, authorization.validUntil, snapshotStart + callWindowMillis)
        if (snapshotDeadline <= snapshotStart) {
            return failure("participant-authorization-expired", false, snapshotStart)
        }
        val providerContext = WorkflowProviderCallContext.of(
            stableId("organization-call", intent.effectId, request.claim.lease.fencingToken.toString()),
            request.callContext.tenantId,
            providerId,
            providerRevision,
            "participant-resolution",
            snapshotStart,
            snapshotDeadline,
            65_536,
            65_536,
            maximumPrincipals,
        )
        val snapshotRequest = WorkflowOrganizationSnapshotRequest.of(providerContext)
        val snapshotResult = try {
            await(organizationAuthority.snapshot(snapshotRequest), snapshotDeadline)
        } catch (error: Exception) {
            restoreInterrupt(error)
            return failure("organization-snapshot-unavailable", true, safeNow(snapshotStart))
        }
        val snapshot = snapshotResult.snapshot
        val snapshotReceipt = snapshotResult.receipt
        val snapshotObservedAt = clock.currentTimeMillis()
        if (snapshot == null || snapshotReceipt.outcome != WorkflowProviderOutcome.SUCCESS ||
            snapshotReceipt.contextDigest != providerContext.contextDigest ||
            snapshotReceipt.requestDigest != snapshotRequest.requestDigest ||
            snapshotReceipt.resultDigest != snapshot.snapshotDigest ||
            snapshotReceipt.tenantId != request.callContext.tenantId ||
            snapshotReceipt.providerId != providerId || snapshotReceipt.providerRevision != providerRevision ||
            snapshot.authorityId != providerId || snapshot.expiresAtEpochMilli < snapshotReceipt.completedAtEpochMilli ||
            snapshotReceipt.completedAtEpochMilli > snapshotObservedAt
        ) {
            val retryable = snapshotReceipt.failure?.retryable == true
            return failure("organization-snapshot-invalid", retryable, safeNow(snapshotStart))
        }

        val resolutionStart = safeNow(snapshotReceipt.completedAtEpochMilli)
        val resolutionDeadline = minOf(
            request.deadline,
            authorization.validUntil,
            snapshot.expiresAtEpochMilli,
            resolutionStart + callWindowMillis,
        )
        if (resolutionDeadline <= resolutionStart) {
            return failure("organization-snapshot-expired", true, resolutionStart)
        }
        val resolutionRequest = WorkflowParticipantResolutionRequest.authorized(
            stableId("participant-request", intent.effectId, request.claim.lease.fencingToken.toString()),
            request.callContext.tenantId,
            intent.definitionRef,
            WorkflowInstanceRef.of(intent.instanceId, request.state.version),
            WorkflowWorkItemRef.of(workItem.workItemId, workItem.revision),
            WorkflowParticipantResolutionStage.ACTIVATION,
            rule.membershipStrategy,
            intent.subject,
            request.state.initiator,
            request.state.initiator,
            snapshot.authorityId,
            snapshot.revision,
            listOf(rule.selector),
            WorkflowDelegationPolicy.disabled(),
            authorization.authorityRevision,
            authorizationEvidenceDigest,
            maximumPrincipals,
            resolutionStart,
            resolutionDeadline,
        )
        val resolution = try {
            await(participantResolver.resolve(resolutionRequest), resolutionDeadline)
        } catch (error: Exception) {
            restoreInterrupt(error)
            return failure("participant-resolver-unavailable", true, safeNow(resolutionStart))
        }
        val resolutionObservedAt = clock.currentTimeMillis()
        if (!resolutionMatches(resolutionRequest, resolution, resolutionObservedAt)) {
            return failure("participant-resolution-evidence-invalid", false, safeNow(resolutionStart))
        }

        // A resolver result is usable only while the host organization authority still reports
        // the exact revision it resolved against. This second read closes the revision-drift
        // window around the external resolver call; a later attempt may resolve against the new
        // revision, but this attempt never applies mixed-revision evidence.
        val confirmationStart = safeNow(resolution.resolvedAtEpochMilli)
        val confirmationDeadline = minOf(
            request.deadline,
            authorization.validUntil,
            snapshot.expiresAtEpochMilli,
            confirmationStart + callWindowMillis,
        )
        if (confirmationDeadline <= confirmationStart) {
            return failure("organization-confirmation-expired", true, confirmationStart)
        }
        val confirmationContext = WorkflowProviderCallContext.of(
            stableId("organization-confirm", intent.effectId, request.claim.lease.fencingToken.toString()),
            request.callContext.tenantId,
            providerId,
            providerRevision,
            "participant-resolution-confirm",
            confirmationStart,
            confirmationDeadline,
            65_536,
            65_536,
            maximumPrincipals,
        )
        val confirmationRequest = WorkflowOrganizationSnapshotRequest.of(confirmationContext)
        val confirmationResult = try {
            await(organizationAuthority.snapshot(confirmationRequest), confirmationDeadline)
        } catch (error: Exception) {
            restoreInterrupt(error)
            return failure("organization-confirmation-unavailable", true, safeNow(confirmationStart))
        }
        val confirmedSnapshot = confirmationResult.snapshot
        val confirmationReceipt = confirmationResult.receipt
        val confirmationObservedAt = clock.currentTimeMillis()
        if (confirmedSnapshot == null || confirmationReceipt.outcome != WorkflowProviderOutcome.SUCCESS ||
            confirmationReceipt.contextDigest != confirmationContext.contextDigest ||
            confirmationReceipt.requestDigest != confirmationRequest.requestDigest ||
            confirmationReceipt.resultDigest != confirmedSnapshot.snapshotDigest ||
            confirmationReceipt.tenantId != request.callContext.tenantId ||
            confirmationReceipt.providerId != providerId || confirmationReceipt.providerRevision != providerRevision ||
            confirmedSnapshot.authorityId != providerId ||
            confirmedSnapshot.expiresAtEpochMilli < confirmationReceipt.completedAtEpochMilli ||
            confirmationReceipt.completedAtEpochMilli > confirmationObservedAt
        ) {
            val retryable = confirmationReceipt.failure?.retryable != false
            return failure("organization-confirmation-invalid", retryable, safeNow(confirmationStart))
        }
        if (confirmedSnapshot.authorityId != snapshot.authorityId || confirmedSnapshot.revision != snapshot.revision) {
            return failure(
                "organization-revision-drift",
                true,
                confirmationReceipt.completedAtEpochMilli,
            )
        }
        if (resolution.status != WorkflowParticipantResolutionStatus.RESOLVED) {
            val reason = resolution.reason?.code ?: "participant-resolution-unresolved"
            return failure(reason, resolution.retryable, resolution.resolvedAtEpochMilli)
        }
        if (resolution.principals.toSet().size != resolution.principals.size) {
            return failure("participant-resolution-duplicate", false, resolution.resolvedAtEpochMilli)
        }

        val receipt = try {
            WorkflowParticipantActivationReceipt.organizationDoubleChecked(
                stableId("participant-receipt", intent.effectId, resolution.resolutionDigest),
                intent.effectId,
                intent.tenantId,
                intent.instanceId,
                intent.definitionId,
                intent.definitionRef,
                intent.subject,
                requireNotNull(intent.tokenId),
                requireNotNull(intent.nodeExecutionId),
                requireNotNull(intent.workItemId),
                requireNotNull(intent.nodeId),
                requireNotNull(intent.ruleIndex),
                rule.contentDigest,
                intent.requestDigest,
                resolution.principals,
                resolution.resolutionDigest,
                resolution.authority,
                resolution.authorityRevision,
                resolution.requestDigest,
                rule.selector.digest,
                providerRevision,
                snapshot.snapshotDigest,
                snapshotReceipt.receiptDigest,
                confirmedSnapshot.revision,
                confirmedSnapshot.snapshotDigest,
                confirmationRequest.requestDigest,
                confirmationReceipt.receiptDigest,
                resolution.resolvedAtEpochMilli,
                minOf(
                    resolution.expiresAtEpochMilli,
                    snapshot.expiresAtEpochMilli,
                    confirmedSnapshot.expiresAtEpochMilli,
                    confirmationReceipt.expiresAtEpochMilli,
                    authorization.validUntil,
                ),
            )
        } catch (_: IllegalArgumentException) {
            return failure("participant-receipt-invalid", false, resolution.resolvedAtEpochMilli)
        }
        return WorkflowEffectJobStoredResult.of(
            WorkflowEffectObservedOutcome.SUCCEEDED,
            RESULT_TYPE,
            receipt.receiptDigest,
            WorkflowParticipantActivationReceiptCodec.encode(receipt),
            null,
            confirmationReceipt.completedAtEpochMilli,
        )
    }

    override fun apply(request: WorkflowEffectApplyRequest): WorkflowRuntimeResult {
        if (request.result.resultType != RESULT_TYPE) {
            return applyFailure(request.state, "participant-result-type-invalid")
        }
        val receipt = try {
            WorkflowParticipantActivationReceiptCodec.decode(request.result.bytes())
        } catch (_: RuntimeException) {
            return applyFailure(request.state, "participant-result-payload-invalid")
        }
        val intent = request.effect.intent
        val workItem = request.state.humanWorkItems.firstOrNull { item -> item.workItemId == receipt.workItemId }
        val token = request.state.tokens.firstOrNull { item -> item.tokenId == receipt.tokenId }
        val execution = request.state.nodeExecutions.firstOrNull { item ->
            item.executionId == receipt.nodeExecutionId
        }
        val node = workItem?.let { item ->
            try {
                request.definition.index.node(item.nodeId)
            } catch (_: RuntimeException) {
                null
            }
        }
        val rule = workItem?.let { item ->
            node?.humanTaskPolicy?.participantRules?.getOrNull(item.activeRuleIndex)
        }
        if (receipt.evidenceVersion != 3 || receipt.receiptDigest != request.result.resultDigest ||
            request.result.completedAt < receipt.resolvedAt || request.result.completedAt > receipt.validUntil ||
            intent.code != effectCode() ||
            receipt.effectId != intent.effectId || receipt.tenantId != request.claim.tenantId ||
            receipt.instanceId != request.claim.instanceId || receipt.tenantId != request.state.tenantId ||
            receipt.instanceId != request.state.instanceId || receipt.definitionId != request.state.definitionId ||
            receipt.definitionRef != request.state.definitionRef || receipt.subject != request.state.subject ||
            receipt.definitionId != intent.definitionId || receipt.definitionRef != intent.definitionRef ||
            receipt.subject != intent.subject || receipt.effectRequestDigest != intent.requestDigest ||
            receipt.tokenId != intent.tokenId || receipt.nodeExecutionId != intent.nodeExecutionId ||
            receipt.workItemId != intent.workItemId || receipt.nodeId != intent.nodeId ||
            receipt.ruleIndex != intent.ruleIndex || workItem == null ||
            workItem.nodeExecutionId != receipt.nodeExecutionId || workItem.tokenId != receipt.tokenId ||
            workItem.nodeId != receipt.nodeId || workItem.activeRuleIndex != receipt.ruleIndex || rule == null ||
            receipt.ruleDigest != rule.contentDigest || receipt.selectorDigest != rule.selector.digest ||
            token == null || token.nodeId != receipt.nodeId ||
            token.waitingExecutionId != receipt.nodeExecutionId ||
            token.status != ai.icen.fw.workflow.domain.WorkflowTokenStatus.WAITING_HUMAN ||
            execution == null || execution.tokenId != receipt.tokenId || execution.nodeId != receipt.nodeId ||
            execution.status != ai.icen.fw.workflow.domain.WorkflowNodeExecutionStatus.WAITING ||
            execution.pendingEffectId != receipt.effectId || execution.pendingEffectCode != effectCode() ||
            execution.effectRequestDigest != receipt.effectRequestDigest ||
            receipt.organizationAuthority != providerId || receipt.organizationProviderRevision != providerRevision ||
            receipt.organizationSnapshotRevision != receipt.organizationConfirmationRevision ||
            receipt.organizationSnapshotDigest.isNullOrBlank() ||
            receipt.organizationSnapshotReceiptDigest.isNullOrBlank() ||
            receipt.organizationConfirmationSnapshotDigest.isNullOrBlank() ||
            receipt.organizationConfirmationRequestDigest.isNullOrBlank() ||
            receipt.organizationConfirmationReceiptDigest.isNullOrBlank() ||
            receipt.resolutionRequestDigest.isNullOrBlank() || request.now > receipt.validUntil
        ) return applyFailure(request.state, "participant-result-binding-invalid")

        val commandSeed = stableId("participant-apply", receipt.effectId, receipt.effectRequestDigest)
        val options = WorkflowRuntimeCommandOptions.of(
            "participant-apply-$commandSeed",
            "participant-apply-$commandSeed",
            request.state.version,
            request.now,
            iterationBudget,
            executionIds(commandSeed),
        )
        return durableRuntime.activateHumanRule(
            WorkflowRuntimeActivateHumanRuleRequest.fenced(
                request.callContext,
                options,
                receipt.instanceId,
                receipt.workItemId,
                receipt,
                request.claim.lease,
            ),
        )
    }

    private fun resolutionMatches(
        request: WorkflowParticipantResolutionRequest,
        result: WorkflowParticipantResolution,
        observedAt: Long,
    ): Boolean = result.requestId == request.requestId && result.requestDigest == request.requestDigest &&
        result.tenantId == request.tenantId && result.authority == request.organizationAuthority &&
        result.authorityRevision == request.organizationSnapshotRevision &&
        result.membershipStrategy == request.membershipStrategy &&
        request.hasAuthorizationEvidence && result.hasAuthorizationEvidence &&
        result.authorizationAuthorityRevision == request.authorizationAuthorityRevision &&
        result.authorizationEvidenceDigest == request.authorizationEvidenceDigest &&
        result.resolvedAtEpochMilli >= request.requestedAtEpochMilli &&
        result.resolvedAtEpochMilli <= observedAt &&
        result.resolvedAtEpochMilli < request.deadlineEpochMilli &&
        result.expiresAtEpochMilli <= request.deadlineEpochMilli

    private fun participantPayloadDigest(
        workItem: ai.icen.fw.workflow.domain.WorkflowHumanWorkItemState,
        rule: ai.icen.fw.workflow.api.WorkflowHumanTaskParticipantRule,
    ): String = WorkflowEffectIntent.participantResolutionPayloadDigest(
        workItem.contentDigest,
        workItem.policyDigest,
        workItem.activeRuleIndex,
        rule.contentDigest,
        rule.selector.digest,
        rule.approvalPolicy.contentDigest,
    )

    private fun participantAuthorizationRequestDigest(
        request: WorkflowEffectHandlerRequest,
        workItem: ai.icen.fw.workflow.domain.WorkflowHumanWorkItemState,
        rule: ai.icen.fw.workflow.api.WorkflowHumanTaskParticipantRule,
    ): String = WorkflowRuntimeSupport.digest(
        "flowweft-workflow-runtime-participant-authorization-request-v1",
    )
        .text(request.callContext.contextDigest)
        .text(request.effect.intent.requestDigest)
        .text(request.state.tenantId)
        .text(request.state.instanceId)
        .longValue(request.state.version)
        .text(request.state.definitionId)
        .text(request.state.definitionRef.key)
        .text(request.state.definitionRef.version)
        .text(request.state.definitionRef.digest)
        .text(request.state.subject.ref.type)
        .text(request.state.subject.ref.id)
        .text(request.state.subject.revision)
        .text(request.state.subject.digest)
        .text(request.callContext.actor.type)
        .text(request.callContext.actor.id)
        .text(workItem.workItemId)
        .longValue(workItem.revision)
        .text(rule.contentDigest)
        .text(rule.selector.digest)
        .text(providerId)
        .text(providerRevision)
        .finish()

    private fun participantAuthorizationEvidenceDigest(
        request: WorkflowRuntimeAuthorizationRequest,
        decision: WorkflowRuntimeAuthorizationDecision,
    ): String = WorkflowRuntimeSupport.digest(
        "flowweft-workflow-runtime-participant-authorization-evidence-v1",
    )
        .text(request.callContext.contextDigest)
        .text(request.requestDigest)
        .text(decision.authorizationId)
        .text(decision.tenantId)
        .text(decision.actor.type)
        .text(decision.actor.id)
        .text(decision.action.code)
        .text(decision.instanceId)
        .text(decision.status.code)
        .text(decision.authorityRevision)
        .text(decision.authorityDigest)
        .longValue(decision.evaluatedAt)
        .longValue(decision.validUntil)
        .finish()

    private fun failure(code: String, retryable: Boolean, completedAt: Long): WorkflowEffectJobStoredResult {
        val safeCode = try {
            WorkflowRuntimeSupport.code(code, "Workflow participant failure code is invalid.")
        } catch (_: IllegalArgumentException) {
            "participant-provider-failed"
        }
        val payload = "participant-resolution-failure-v1:$safeCode".toByteArray(StandardCharsets.UTF_8)
        val digest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-participant-failure-v1")
            .text(safeCode)
            .bool(retryable)
            .longValue(completedAt)
            .finish()
        return WorkflowEffectJobStoredResult.of(
            if (retryable) {
                WorkflowEffectObservedOutcome.RETRYABLE_FAILURE
            } else {
                WorkflowEffectObservedOutcome.TERMINAL_FAILURE
            },
            FAILURE_RESULT_TYPE,
            digest,
            payload,
            if (retryable) completedAt + retryDelayMillis else null,
            completedAt,
        )
    }

    private fun currentTimeWithin(request: WorkflowEffectHandlerRequest): Long? =
        clock.currentTimeMillis().takeIf { value -> value >= request.now && value < request.deadline }

    private fun safeNow(minimum: Long): Long =
        maxOf(minimum, clock.currentTimeMillis())

    private fun restoreInterrupt(error: Exception) {
        if (error is InterruptedException) Thread.currentThread().interrupt()
    }

    private fun <T> await(stage: java.util.concurrent.CompletionStage<T>, deadline: Long): T {
        val remaining = deadline - clock.currentTimeMillis()
        require(remaining > 0L) { "Workflow provider deadline expired." }
        return stage.toCompletableFuture().get(remaining, TimeUnit.MILLISECONDS)
    }

    private fun applyFailure(state: ai.icen.fw.workflow.domain.WorkflowInstanceState, code: String): WorkflowRuntimeResult =
        WorkflowRuntimeResult.failed(
            WorkflowRuntimeResultCode.DOMAIN_REJECTED,
            state,
            null,
            code,
        )

    private fun executionIds(seed: String): WorkflowExecutionIds = WorkflowExecutionIds.of(
        (0 until 256).map { index -> "wf-worker-$seed-token-$index" },
        (0 until 256).map { index -> "wf-worker-$seed-execution-$index" },
        (0 until 128).map { index -> "wf-worker-$seed-work-$index" },
        (0 until 256).map { index -> "wf-worker-$seed-effect-$index" },
        (0 until 1024).map { index -> "wf-worker-$seed-event-$index" },
        (0 until 128).map { index -> "wf-worker-$seed-scope-$index" },
    )

    private companion object {
        const val RESULT_TYPE = "participant-activation-receipt-v3"
        const val FAILURE_RESULT_TYPE = "participant-resolution-failure-v1"
    }
}

internal object WorkflowParticipantActivationReceiptCodec {
    private const val MAGIC = "FW-WORKFLOW-PARTICIPANT-RECEIPT"
    private const val VERSION = 3
    private const val MAX_BYTES = 1024 * 1024

    fun encode(receipt: WorkflowParticipantActivationReceipt): ByteArray {
        require(receipt.evidenceVersion == VERSION) {
            "Only double-checked organization receipts are durable worker results."
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            writeText(output, MAGIC)
            output.writeInt(VERSION)
            writeText(output, receipt.receiptId)
            writeText(output, receipt.effectId)
            writeText(output, receipt.tenantId)
            writeText(output, receipt.instanceId)
            writeText(output, receipt.definitionId)
            writeText(output, receipt.definitionRef.key)
            writeText(output, receipt.definitionRef.version)
            writeText(output, receipt.definitionRef.digest)
            writeText(output, receipt.subject.ref.type)
            writeText(output, receipt.subject.ref.id)
            writeText(output, receipt.subject.revision)
            writeText(output, receipt.subject.digest)
            writeText(output, receipt.tokenId)
            writeText(output, receipt.nodeExecutionId)
            writeText(output, receipt.workItemId)
            writeText(output, receipt.nodeId)
            output.writeInt(receipt.ruleIndex)
            writeText(output, receipt.ruleDigest)
            writeText(output, receipt.effectRequestDigest)
            output.writeInt(receipt.candidates.size)
            receipt.candidates.forEach { candidate ->
                writeText(output, candidate.type)
                writeText(output, candidate.id)
            }
            writeText(output, receipt.resolutionDigest)
            writeText(output, requireNotNull(receipt.organizationAuthority))
            writeText(output, requireNotNull(receipt.organizationSnapshotRevision))
            writeText(output, requireNotNull(receipt.resolutionRequestDigest))
            writeText(output, requireNotNull(receipt.selectorDigest))
            writeText(output, requireNotNull(receipt.organizationProviderRevision))
            writeText(output, requireNotNull(receipt.organizationSnapshotDigest))
            writeText(output, requireNotNull(receipt.organizationSnapshotReceiptDigest))
            writeText(output, requireNotNull(receipt.organizationConfirmationRevision))
            writeText(output, requireNotNull(receipt.organizationConfirmationSnapshotDigest))
            writeText(output, requireNotNull(receipt.organizationConfirmationRequestDigest))
            writeText(output, requireNotNull(receipt.organizationConfirmationReceiptDigest))
            output.writeLong(receipt.resolvedAt)
            output.writeLong(receipt.validUntil)
        }
        return bytes.toByteArray().also { result ->
            require(result.size <= MAX_BYTES) { "Workflow participant receipt payload exceeds the limit." }
        }
    }

    fun decode(payload: ByteArray): WorkflowParticipantActivationReceipt {
        require(payload.isNotEmpty() && payload.size <= MAX_BYTES) {
            "Workflow participant receipt payload is invalid."
        }
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(readText(input) == MAGIC && input.readInt() == VERSION) {
                "Workflow participant receipt payload version is unsupported."
            }
            val receiptId = readText(input)
            val effectId = readText(input)
            val tenantId = readText(input)
            val instanceId = readText(input)
            val definitionId = readText(input)
            val definitionRef = WorkflowDefinitionRef.of(readText(input), readText(input), readText(input))
            val subject = WorkflowSubjectSnapshot.of(
                WorkflowSubjectRef.of(readText(input), readText(input)),
                readText(input),
                readText(input),
            )
            val tokenId = readText(input)
            val nodeExecutionId = readText(input)
            val workItemId = readText(input)
            val nodeId = readText(input)
            val ruleIndex = input.readInt()
            val ruleDigest = readText(input)
            val effectRequestDigest = readText(input)
            val candidateCount = input.readInt()
            require(candidateCount in 0..256) { "Workflow participant receipt candidate count is invalid." }
            val candidates = (0 until candidateCount).map {
                WorkflowPrincipalRef.of(readText(input), readText(input))
            }
            val receipt = WorkflowParticipantActivationReceipt.organizationDoubleChecked(
                receiptId,
                effectId,
                tenantId,
                instanceId,
                definitionId,
                definitionRef,
                subject,
                tokenId,
                nodeExecutionId,
                workItemId,
                nodeId,
                ruleIndex,
                ruleDigest,
                effectRequestDigest,
                candidates,
                readText(input),
                readText(input),
                readText(input),
                readText(input),
                readText(input),
                readText(input),
                readText(input),
                readText(input),
                readText(input),
                readText(input),
                readText(input),
                readText(input),
                input.readLong(),
                input.readLong(),
            )
            require(input.available() == 0) { "Workflow participant receipt payload has trailing data." }
            return receipt
        }
    }

    private fun writeText(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= 4096) { "Workflow participant receipt text exceeds the limit." }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..4096 && size <= input.available()) {
            "Workflow participant receipt text length is invalid."
        }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}

private fun stableId(vararg values: String): String {
    val writer = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-participant-id-v1")
        .integer(values.size)
    values.forEach(writer::text)
    return writer.finish()
}
