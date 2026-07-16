package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowCommentSnapshot
import ai.icen.fw.workflow.api.WorkflowFormFieldAccessMode
import ai.icen.fw.workflow.api.WorkflowFormSubmissionRef
import ai.icen.fw.workflow.spi.WorkflowFormValidationOperation
import ai.icen.fw.workflow.spi.WorkflowMentionNotificationIntent
import ai.icen.fw.workflow.spi.WorkflowMentionNotificationProvider
import ai.icen.fw.workflow.spi.WorkflowMentionNotificationRequest
import ai.icen.fw.workflow.spi.WorkflowMentionResolver
import ai.icen.fw.workflow.spi.WorkflowMentionSearchRequest
import ai.icen.fw.workflow.spi.WorkflowMentionVisibilityAttestation
import ai.icen.fw.workflow.spi.WorkflowMentionVisibilityRequest
import ai.icen.fw.workflow.spi.WorkflowProviderCallContext
import ai.icen.fw.workflow.spi.WorkflowProviderOutcome
import ai.icen.fw.workflow.spi.WorkflowProviderReceipt
import ai.icen.fw.workflow.spi.WorkflowSecureFormValidationRequest
import ai.icen.fw.workflow.spi.WorkflowSecureFormValidator
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * Provider-neutral orchestration for forms, structured comments and safe mentions.
 *
 * Provider calls happen only after a short idempotency reservation transaction and never inside
 * persistence callbacks. The runtime never parses or renders comment text as HTML.
 */
class WorkflowHumanInputRuntime @JvmOverloads constructor(
    private val authorizationPort: WorkflowRuntimeAuthorizationPort,
    private val idempotencyPort: WorkflowHumanInputIdempotencyPort,
    private val formValidator: WorkflowSecureFormValidator,
    private val mentionResolver: WorkflowMentionResolver,
    private val notificationProvider: WorkflowMentionNotificationProvider,
    private val clock: WorkflowWorkerClock,
    private val formProfile: WorkflowHumanInputProviderProfile,
    private val mentionProfile: WorkflowHumanInputProviderProfile,
    private val notificationProfile: WorkflowHumanInputProviderProfile,
    private val operationWindowMillis: Long = 300_000L,
) {
    init {
        require(operationWindowMillis in 1L..WorkflowHumanInputReservationRequest.MAXIMUM_LEASE_MILLIS) {
            "Workflow human-input operation window is invalid."
        }
    }

    fun validateForm(command: WorkflowRuntimeFormCommand): WorkflowRuntimeFormResult {
        val operation = WorkflowHumanInputOperation.FORM_VALIDATE
        val startedAt = currentTime(operation) ?: return formClockFailure(operation)
        val overallDeadline = deadline(startedAt, operationWindowMillis)
            ?: return formFailure(operation, WorkflowHumanInputResultCode.INVALID, "clock-overflow", false)
        val authorization = authorize(
            command.callContext,
            ACTION_VALIDATE_FORM,
            command.instanceId,
            command.subject,
            command.requestDigest,
            startedAt,
            operation,
        )
        authorization.failure?.let { return WorkflowRuntimeFormResult.failed(it.code, it.diagnostic) }
        val authRequest = authorization.request!!
        val authDecision = authorization.decision!!
        val reservation = reserve(
            command.callContext.tenantId,
            command.idempotencyKey,
            operation,
            command.requestDigest,
            startedAt,
            overallDeadline,
        )
        reservation.failure?.let { return WorkflowRuntimeFormResult.failed(it.code, it.diagnostic) }
        reservation.replay?.let { record ->
            val value = record.validatedForm
                ?: return formFailure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "replay-type-invalid", false)
            return WorkflowRuntimeFormResult.success(WorkflowHumanInputResultCode.REPLAYED, value)
        }

        val providerStart = currentTime(operation)
            ?: return formClockFailure(operation)
        val providerDeadline = providerDeadline(providerStart, overallDeadline, formProfile)
            ?: return formFailure(operation, WorkflowHumanInputResultCode.PROVIDER_UNAVAILABLE, "provider-deadline-expired", true)
        val providerContext = providerContext(
            command.callContext,
            formProfile,
            "form-${command.requestDigest.take(24)}",
            "secure-form-validation",
            providerStart,
            providerDeadline,
        )
        val providerRequest = try {
            WorkflowSecureFormValidationRequest.of(
                providerContext,
                command.form,
                command.subject,
                command.callContext.actor,
                command.operation,
                command.requestedFields,
                command.submission,
                authDecision.authorityRevision,
                authDecision.authorityDigest,
            )
        } catch (_: RuntimeException) {
            return formFailure(operation, WorkflowHumanInputResultCode.INVALID, "provider-request-invalid", false)
        }
        val providerResult = try {
            await(formValidator.validate(providerRequest), providerDeadline)
        } catch (error: Exception) {
            restoreInterrupt(error)
            return formFailure(operation, WorkflowHumanInputResultCode.PROVIDER_UNAVAILABLE, "form-provider-unavailable", true)
        }
        val completedAt = currentTime(operation) ?: return formClockFailure(operation)
        if (!authDecision.matches(authRequest, completedAt)) {
            return formFailure(operation, WorkflowHumanInputResultCode.AUTHORIZATION_DENIED, "authorization-expired", false)
        }
        if (!receiptMatches(providerResult.receipt, providerContext, providerRequest.requestDigest, completedAt)) {
            return formFailure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "form-receipt-invalid", false)
        }
        if (providerResult.receipt.outcome != WorkflowProviderOutcome.SUCCESS) {
            return formProviderFailure(operation, providerResult.receipt)
        }
        val report = providerResult.report
            ?: return formFailure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "form-report-missing", false)
        if (!report.schemaValid) {
            return formFailure(operation, WorkflowHumanInputResultCode.INVALID, "form-schema-invalid", false)
        }
        val normalized = report.normalizedSubmission
            ?: return formFailure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "normalized-form-missing", false)
        val normalizedFieldCount = normalized.fieldCount
        if (normalizedFieldCount == null || normalizedFieldCount != command.requestedFields.size) {
            return formFailure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "field-coverage-incomplete", false)
        }
        val decided = report.fieldAccess.decisions.map { it.path }
        if (decided.size != command.requestedFields.size || decided.toSet() != command.requestedFields.toSet() ||
            report.fieldAccess.authorityReceiptDigest != authDecision.authorityDigest
        ) {
            return formFailure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "field-authorization-incomplete", false)
        }
        if (command.operation == WorkflowFormValidationOperation.SUBMIT &&
            command.requestedFields.any { !report.fieldAccess.mayWrite(it) }
        ) {
            return formFailure(operation, WorkflowHumanInputResultCode.AUTHORIZATION_DENIED, "field-write-denied", false)
        }
        if (command.operation == WorkflowFormValidationOperation.READ &&
            command.requestedFields.any { report.fieldAccess.readMode(it) != WorkflowFormFieldAccessMode.ALLOW }
        ) {
            // Without a provider-neutral JSON projection contract the runtime cannot prove that
            // denied/redacted values were removed, so it returns no payload.
            return formFailure(operation, WorkflowHumanInputResultCode.PROVIDER_REJECTED, "field-redaction-unproven", false)
        }
        val validationReceipt = normalized.validationReceipt
            ?: return formFailure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "schema-attestation-missing", false)
        val submission = if (command.operation == WorkflowFormValidationOperation.SUBMIT) {
            try {
                WorkflowFormSubmissionRef.of(
                    command.submissionId!!,
                    command.submissionVersion!!,
                    command.form,
                    command.callContext.actor,
                    normalized.canonicalPayloadDigest,
                    normalized.size,
                    validationReceipt.receiptDigest,
                    report.fieldAccess.authorityReceiptDigest,
                    completedAt,
                )
            } catch (_: RuntimeException) {
                return formFailure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "submission-evidence-invalid", false)
            }
        } else {
            null
        }
        val value = try {
            WorkflowRuntimeValidatedForm.of(
                command.form,
                command.operation,
                normalized,
                report.fieldAccess,
                providerResult.receipt,
                submission,
            )
        } catch (_: RuntimeException) {
            return formFailure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "validated-form-invalid", false)
        }
        val record = WorkflowHumanInputIdempotencyRecord.form(
            command.callContext.tenantId,
            command.idempotencyKey,
            command.requestDigest,
            value,
            completedAt,
        )
        val completion = complete(reservation.reservation!!, record, operation)
        completion.failure?.let { return WorkflowRuntimeFormResult.failed(it.code, it.diagnostic) }
        val completed = completion.record?.validatedForm
            ?: return formFailure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "completion-type-invalid", false)
        return WorkflowRuntimeFormResult.success(completion.resultCode!!, completed)
    }

    fun createComment(command: WorkflowRuntimeCommentCommand): WorkflowRuntimeCommentResult {
        val operation = WorkflowHumanInputOperation.COMMENT_CREATE
        val startedAt = currentTime(operation) ?: return commentClockFailure(operation)
        val overallDeadline = deadline(startedAt, operationWindowMillis)
            ?: return commentFailure(operation, WorkflowHumanInputResultCode.INVALID, "clock-overflow", false)
        val authorization = authorize(
            command.callContext,
            ACTION_CREATE_COMMENT,
            command.instance.id,
            null,
            command.requestDigest,
            startedAt,
            operation,
        )
        authorization.failure?.let { return WorkflowRuntimeCommentResult.failed(it.code, it.diagnostic) }
        val authRequest = authorization.request!!
        val authDecision = authorization.decision!!
        val reservation = reserve(
            command.callContext.tenantId,
            command.idempotencyKey,
            operation,
            command.requestDigest,
            startedAt,
            overallDeadline,
        )
        reservation.failure?.let { return WorkflowRuntimeCommentResult.failed(it.code, it.diagnostic) }
        reservation.replay?.let { record ->
            val value = record.comment
                ?: return commentFailure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "replay-type-invalid", false)
            return WorkflowRuntimeCommentResult.success(WorkflowHumanInputResultCode.REPLAYED, value)
        }

        if (command.document.mentionedPrincipals.size > mentionProfile.maximumItems) {
            return commentFailure(operation, WorkflowHumanInputResultCode.INVALID, "mention-limit-exceeded", false)
        }

        val attestationWriter = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-comment-mention-attestation-v1")
            .text(command.document.documentDigest)
            .text(authDecision.authorityDigest)
            .integer(command.document.mentionedPrincipals.size)
        var mentionAttestationExpiresAt = Long.MAX_VALUE
        command.document.mentionedPrincipals.forEachIndexed { index, principal ->
            val checkedAt = currentTime(operation) ?: return commentClockFailure(operation)
            val callDeadline = providerDeadline(checkedAt, overallDeadline, mentionProfile)
                ?: return commentFailure(
                    operation,
                    WorkflowHumanInputResultCode.PROVIDER_UNAVAILABLE,
                    "mention-deadline-expired",
                    true,
                )
            val context = providerContext(
                command.callContext,
                mentionProfile,
                "comment-mention-${command.requestDigest.take(16)}-$index",
                "comment-mention-visibility",
                checkedAt,
                callDeadline,
            )
            val request = WorkflowMentionVisibilityRequest.of(
                context,
                command.callContext.actor,
                principal,
                authDecision.authorityDigest,
            )
            val result = try {
                await(mentionResolver.verifyVisibility(request), callDeadline)
            } catch (error: Exception) {
                restoreInterrupt(error)
                return commentFailure(
                    operation,
                    WorkflowHumanInputResultCode.PROVIDER_UNAVAILABLE,
                    "mention-provider-unavailable",
                    true,
                )
            }
            val observedAt = currentTime(operation) ?: return commentClockFailure(operation)
            if (!authDecision.matches(authRequest, observedAt)) {
                return commentFailure(
                    operation,
                    WorkflowHumanInputResultCode.AUTHORIZATION_DENIED,
                    "authorization-expired",
                    false,
                )
            }
            if (!receiptMatches(result.receipt, context, request.requestDigest, observedAt)) {
                return commentFailure(
                    operation,
                    WorkflowHumanInputResultCode.RECEIPT_INVALID,
                    "mention-receipt-invalid",
                    false,
                )
            }
            if (result.receipt.outcome != WorkflowProviderOutcome.SUCCESS) {
                return commentProviderFailure(operation, result.receipt)
            }
            val decision = result.decision
                ?: return commentFailure(
                    operation,
                    WorkflowHumanInputResultCode.RECEIPT_INVALID,
                    "mention-decision-missing",
                    false,
                )
            if (!decision.visible) {
                // Do not identify the token or distinguish a hidden principal from a missing one.
                return commentFailure(
                    operation,
                    WorkflowHumanInputResultCode.MENTION_NOT_VISIBLE,
                    "mention-not-visible",
                    false,
                )
            }
            val candidate = decision.candidate
                ?: return commentFailure(
                    operation,
                    WorkflowHumanInputResultCode.RECEIPT_INVALID,
                    "mention-candidate-missing",
                    false,
                )
            if (candidate.principal != principal) {
                return commentFailure(
                    operation,
                    WorkflowHumanInputResultCode.RECEIPT_INVALID,
                    "mention-principal-mismatch",
                    false,
                )
            }
            mentionAttestationExpiresAt = minOf(
                mentionAttestationExpiresAt,
                result.receipt.expiresAtEpochMilli,
            )
            attestationWriter.text(result.receipt.receiptDigest)
                .text(candidate.authorityRevision)
                .text(candidate.visibilityReceiptDigest)
        }
        val completedAt = currentTime(operation) ?: return commentClockFailure(operation)
        if (!authDecision.matches(authRequest, completedAt)) {
            return commentFailure(
                operation,
                WorkflowHumanInputResultCode.AUTHORIZATION_DENIED,
                "authorization-expired",
                false,
            )
        }
        if (command.document.mentionedPrincipals.isNotEmpty() && completedAt > mentionAttestationExpiresAt) {
            return commentFailure(
                operation,
                WorkflowHumanInputResultCode.AUTHORIZATION_DENIED,
                "mention-attestation-expired",
                false,
            )
        }
        val mentionAttestation = if (command.document.mentionedPrincipals.isEmpty()) null else attestationWriter.finish()
        val comment = try {
            WorkflowCommentSnapshot.of(
                command.commentId,
                command.commentVersion,
                command.instance,
                command.workItem,
                command.callContext.actor,
                command.document,
                authDecision.authorityDigest,
                mentionAttestation,
                completedAt,
            )
        } catch (_: RuntimeException) {
            return commentFailure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "comment-evidence-invalid", false)
        }
        val record = WorkflowHumanInputIdempotencyRecord.comment(
            command.callContext.tenantId,
            command.idempotencyKey,
            command.requestDigest,
            comment,
            completedAt,
        )
        val completion = complete(reservation.reservation!!, record, operation)
        completion.failure?.let { return WorkflowRuntimeCommentResult.failed(it.code, it.diagnostic) }
        val completed = completion.record?.comment
            ?: return commentFailure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "completion-type-invalid", false)
        return WorkflowRuntimeCommentResult.success(completion.resultCode!!, completed)
    }

    fun searchMentions(command: WorkflowRuntimeMentionSearchCommand): WorkflowRuntimeMentionSearchResult {
        val operation = WorkflowHumanInputOperation.MENTION_SEARCH
        val startedAt = currentTime(operation)
            ?: return mentionSearchFailure(operation, WorkflowHumanInputResultCode.INVALID, "clock-invalid", false)
        val overallDeadline = deadline(startedAt, operationWindowMillis)
            ?: return mentionSearchFailure(operation, WorkflowHumanInputResultCode.INVALID, "clock-overflow", false)
        val authorization = authorize(
            command.callContext,
            ACTION_SEARCH_MENTIONS,
            command.instanceId,
            command.subject,
            command.requestDigest,
            startedAt,
            operation,
        )
        authorization.failure?.let { return WorkflowRuntimeMentionSearchResult.failed(it.code, it.diagnostic) }
        val authRequest = authorization.request!!
        val authDecision = authorization.decision!!
        val callDeadline = providerDeadline(startedAt, overallDeadline, mentionProfile)
            ?: return mentionSearchFailure(
                operation,
                WorkflowHumanInputResultCode.PROVIDER_UNAVAILABLE,
                "mention-deadline-expired",
                true,
            )
        val context = providerContext(
            command.callContext,
            mentionProfile,
            "mention-search-${command.requestDigest.take(24)}",
            "mention-search",
            startedAt,
            callDeadline,
        )
        val request = try {
            WorkflowMentionSearchRequest.of(
                context,
                command.callContext.actor,
                authDecision.authorityDigest,
                command.query,
                command.cursor,
                command.pageSize,
            )
        } catch (_: RuntimeException) {
            return mentionSearchFailure(operation, WorkflowHumanInputResultCode.INVALID, "mention-query-invalid", false)
        }
        val result = try {
            await(mentionResolver.search(request), callDeadline)
        } catch (error: Exception) {
            restoreInterrupt(error)
            return mentionSearchFailure(
                operation,
                WorkflowHumanInputResultCode.PROVIDER_UNAVAILABLE,
                "mention-provider-unavailable",
                true,
            )
        }
        val completedAt = currentTime(operation)
            ?: return mentionSearchFailure(operation, WorkflowHumanInputResultCode.INVALID, "clock-invalid", false)
        if (!authDecision.matches(authRequest, completedAt)) {
            return mentionSearchFailure(
                operation,
                WorkflowHumanInputResultCode.AUTHORIZATION_DENIED,
                "authorization-expired",
                false,
            )
        }
        if (!receiptMatches(result.receipt, context, request.requestDigest, completedAt)) {
            return mentionSearchFailure(
                operation,
                WorkflowHumanInputResultCode.RECEIPT_INVALID,
                "mention-receipt-invalid",
                false,
            )
        }
        if (result.receipt.outcome != WorkflowProviderOutcome.SUCCESS) {
            return mentionSearchProviderFailure(operation, result.receipt)
        }
        val page = result.page
            ?: return mentionSearchFailure(
                operation,
                WorkflowHumanInputResultCode.RECEIPT_INVALID,
                "mention-page-missing",
                false,
            )
        return WorkflowRuntimeMentionSearchResult.success(page)
    }

    fun notifyMention(command: WorkflowRuntimeMentionNotificationCommand): WorkflowRuntimeMentionNotificationResult {
        val operation = WorkflowHumanInputOperation.MENTION_NOTIFY
        val startedAt = currentTime(operation)
            ?: return notificationFailure(operation, WorkflowHumanInputResultCode.INVALID, "clock-invalid", false)
        val overallDeadline = deadline(startedAt, operationWindowMillis)
            ?: return notificationFailure(operation, WorkflowHumanInputResultCode.INVALID, "clock-overflow", false)
        val authorization = authorize(
            command.callContext,
            ACTION_NOTIFY_MENTION,
            command.comment.instance.id,
            null,
            command.requestDigest,
            startedAt,
            operation,
        )
        authorization.failure?.let { return WorkflowRuntimeMentionNotificationResult.failed(it.code, it.diagnostic) }
        val authRequest = authorization.request!!
        val authDecision = authorization.decision!!
        val reservation = reserve(
            command.callContext.tenantId,
            command.idempotencyKey,
            operation,
            command.requestDigest,
            startedAt,
            overallDeadline,
        )
        reservation.failure?.let { return WorkflowRuntimeMentionNotificationResult.failed(it.code, it.diagnostic) }
        reservation.replay?.let { record ->
            val delivery = record.delivery
                ?: return notificationFailure(
                    operation,
                    WorkflowHumanInputResultCode.RECEIPT_INVALID,
                    "replay-type-invalid",
                    false,
                )
            return WorkflowRuntimeMentionNotificationResult.success(WorkflowHumanInputResultCode.REPLAYED, delivery)
        }

        val visibilityStart = currentTime(operation)
            ?: return notificationFailure(operation, WorkflowHumanInputResultCode.INVALID, "clock-invalid", false)
        val visibilityDeadline = providerDeadline(visibilityStart, overallDeadline, mentionProfile)
            ?: return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.PROVIDER_UNAVAILABLE,
                "mention-deadline-expired",
                true,
            )
        val visibilityContext = providerContext(
            command.callContext,
            mentionProfile,
            "notify-visible-${command.requestDigest.take(24)}",
            "mention-notification-visibility",
            visibilityStart,
            visibilityDeadline,
        )
        val visibilityRequest = WorkflowMentionVisibilityRequest.of(
            visibilityContext,
            command.callContext.actor,
            command.recipient,
            authDecision.authorityDigest,
        )
        val visibilityResult = try {
            await(mentionResolver.verifyVisibility(visibilityRequest), visibilityDeadline)
        } catch (error: Exception) {
            restoreInterrupt(error)
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.PROVIDER_UNAVAILABLE,
                "mention-provider-unavailable",
                true,
            )
        }
        val visibilityCompletedAt = currentTime(operation)
            ?: return notificationFailure(operation, WorkflowHumanInputResultCode.INVALID, "clock-invalid", false)
        if (!authDecision.matches(authRequest, visibilityCompletedAt)) {
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.AUTHORIZATION_DENIED,
                "authorization-expired",
                false,
            )
        }
        if (!receiptMatches(
                visibilityResult.receipt,
                visibilityContext,
                visibilityRequest.requestDigest,
                visibilityCompletedAt,
            )
        ) {
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.RECEIPT_INVALID,
                "mention-receipt-invalid",
                false,
            )
        }
        if (visibilityResult.receipt.outcome != WorkflowProviderOutcome.SUCCESS) {
            return notificationProviderFailure(operation, visibilityResult.receipt)
        }
        val visibilityDecision = visibilityResult.decision
            ?: return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.RECEIPT_INVALID,
                "mention-decision-missing",
                false,
            )
        if (!visibilityDecision.visible) {
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.MENTION_NOT_VISIBLE,
                "mention-not-visible",
                false,
            )
        }
        val candidate = visibilityDecision.candidate
            ?: return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.RECEIPT_INVALID,
                "mention-candidate-missing",
                false,
            )
        if (candidate.principal != command.recipient) {
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.RECEIPT_INVALID,
                "mention-principal-mismatch",
                false,
            )
        }
        val visibilityEvidence = WorkflowRuntimeSupport.digest(
            "flowweft-workflow-runtime-mention-notification-visibility-v1",
        )
            .text(visibilityResult.receipt.receiptDigest)
            .text(candidate.visibilityReceiptDigest)
            .finish()
        val attestation = try {
            WorkflowMentionVisibilityAttestation.of(
                command.recipient,
                candidate.authorityRevision,
                visibilityEvidence,
                visibilityCompletedAt,
                visibilityResult.receipt.expiresAtEpochMilli,
            )
        } catch (_: RuntimeException) {
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.RECEIPT_INVALID,
                "visibility-attestation-invalid",
                false,
            )
        }
        val intent = try {
            WorkflowMentionNotificationIntent.of(
                "mention-${command.requestDigest}",
                command.idempotencyKey,
                command.comment,
                command.recipient,
                attestation,
                visibilityCompletedAt,
            )
        } catch (_: RuntimeException) {
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.RECEIPT_INVALID,
                "notification-intent-invalid",
                false,
            )
        }
        val notificationStart = currentTime(operation)
            ?: return notificationFailure(operation, WorkflowHumanInputResultCode.INVALID, "clock-invalid", false)
        val notificationDeadline = providerDeadline(notificationStart, overallDeadline, notificationProfile)
            ?: return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.PROVIDER_UNAVAILABLE,
                "notification-deadline-expired",
                true,
            )
        val notificationContext = providerContext(
            command.callContext,
            notificationProfile,
            "mention-notify-${command.requestDigest.take(24)}",
            "mention-notification",
            notificationStart,
            notificationDeadline,
        )
        val notificationRequest = try {
            WorkflowMentionNotificationRequest.of(notificationContext, intent)
        } catch (_: RuntimeException) {
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.RECEIPT_INVALID,
                "notification-attestation-expired",
                false,
            )
        }
        val checkpointPort = idempotencyPort as? WorkflowMentionNotificationCheckpointPort
            ?: return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.PROVIDER_UNAVAILABLE,
                "notification-checkpoint-unsupported",
                false,
            )
        val checkpointRequest = try {
            WorkflowMentionNotificationProviderCheckpoint.of(
                reservation.reservation!!,
                notificationRequest.requestDigest,
                notificationStart,
            )
        } catch (_: RuntimeException) {
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.RECEIPT_INVALID,
                "notification-checkpoint-request-invalid",
                false,
            )
        }
        val checkpointResult = try {
            checkpointPort.checkpointProviderCall(checkpointRequest)
        } catch (_: Exception) {
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.OUTCOME_UNKNOWN,
                "notification-checkpoint-unknown",
                false,
            )
        }
        val checkpoint = checkpointResult.checkpoint
        if (checkpointResult.code != WorkflowMentionNotificationCheckpointCode.APPLIED || checkpoint == null ||
            checkpoint.status != WorkflowMentionNotificationCheckpointStatus.PROVIDER_CALL_STARTED ||
            !checkpoint.matches(reservation.reservation!!, notificationRequest.requestDigest)
        ) {
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.OUTCOME_UNKNOWN,
                "notification-checkpoint-not-new",
                false,
            )
        }
        val notificationResult = try {
            await(notificationProvider.send(notificationRequest), notificationDeadline)
        } catch (error: Exception) {
            restoreInterrupt(error)
            markNotificationOutcomeUnknown(
                checkpointPort,
                checkpoint,
                "provider-call-failed",
                currentTime(operation) ?: notificationStart,
            )
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.OUTCOME_UNKNOWN,
                "notification-provider-outcome-unknown",
                false,
            )
        }
        val completedAt = currentTime(operation)
            ?: run {
                markNotificationOutcomeUnknown(checkpointPort, checkpoint, "clock-invalid", notificationStart)
                return notificationFailure(
                    operation,
                    WorkflowHumanInputResultCode.OUTCOME_UNKNOWN,
                    "notification-provider-outcome-unknown",
                    false,
                )
            }
        if (!authDecision.matches(authRequest, completedAt)) {
            markNotificationOutcomeUnknown(checkpointPort, checkpoint, "authorization-expired", completedAt)
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.OUTCOME_UNKNOWN,
                "notification-provider-outcome-unknown",
                false,
            )
        }
        if (!receiptMatches(notificationResult.receipt, notificationContext, notificationRequest.requestDigest, completedAt)) {
            markNotificationOutcomeUnknown(checkpointPort, checkpoint, "receipt-invalid", completedAt)
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.OUTCOME_UNKNOWN,
                "notification-provider-outcome-unknown",
                false,
            )
        }
        if (notificationResult.receipt.outcome != WorkflowProviderOutcome.SUCCESS) {
            markNotificationOutcomeUnknown(
                checkpointPort,
                checkpoint,
                "provider-failure-receipt",
                completedAt,
                notificationResult.receipt.receiptDigest,
            )
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.OUTCOME_UNKNOWN,
                "notification-provider-outcome-unknown",
                false,
            )
        }
        val delivery = notificationResult.delivery
            ?: run {
                markNotificationOutcomeUnknown(checkpointPort, checkpoint, "delivery-missing", completedAt)
                return notificationFailure(
                    operation,
                    WorkflowHumanInputResultCode.OUTCOME_UNKNOWN,
                    "notification-provider-outcome-unknown",
                    false,
                )
            }
        val record = WorkflowHumanInputIdempotencyRecord.notification(
            command.callContext.tenantId,
            command.idempotencyKey,
            command.requestDigest,
            delivery,
            notificationResult.receipt,
            completedAt,
        )
        val reconciliation = try {
            checkpointPort.reconcileProviderCall(WorkflowMentionNotificationReconciliation.of(
                checkpoint,
                WorkflowMentionNotificationReconciliationResolution.ACCEPTED,
                record,
                notificationResult.receipt.receiptDigest,
                completedAt,
            ))
        } catch (_: Exception) {
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.OUTCOME_UNKNOWN,
                "notification-reconciliation-unknown",
                false,
            )
        }
        if (reconciliation.code != WorkflowMentionNotificationCheckpointCode.APPLIED &&
            reconciliation.code != WorkflowMentionNotificationCheckpointCode.REPLAYED
        ) {
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.OUTCOME_UNKNOWN,
                "notification-reconciliation-unknown",
                false,
            )
        }
        val completedRecord = reconciliation.record
            ?: return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.RECEIPT_INVALID,
                "reconciliation-record-invalid",
                false,
            )
        val completed = completedRecord.delivery
            ?: return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.RECEIPT_INVALID,
                "reconciliation-record-invalid",
                false,
            )
        if (completedRecord.tenantId != command.callContext.tenantId ||
            !completedRecord.matches(operation, command.requestDigest) ||
            completedRecord.resultDigest != record.resultDigest
        ) {
            return notificationFailure(
                operation,
                WorkflowHumanInputResultCode.RECEIPT_INVALID,
                "reconciliation-record-invalid",
                false,
            )
        }
        val resultCode = if (reconciliation.code == WorkflowMentionNotificationCheckpointCode.APPLIED) {
            WorkflowHumanInputResultCode.SUCCEEDED
        } else {
            WorkflowHumanInputResultCode.REPLAYED
        }
        return WorkflowRuntimeMentionNotificationResult.success(resultCode, completed)
    }

    private fun authorize(
        context: WorkflowTrustedCallContext,
        actionCode: String,
        instanceId: String,
        subject: ai.icen.fw.workflow.api.WorkflowSubjectSnapshot?,
        requestDigest: String,
        now: Long,
        operation: WorkflowHumanInputOperation,
    ): AuthorizationOutcome {
        val request = WorkflowRuntimeAuthorizationRequest.of(
            context,
            WorkflowRuntimeAction.of(actionCode),
            instanceId,
            null,
            null,
            subject,
            requestDigest,
            now,
        )
        val decision = try {
            authorizationPort.authorize(request)
        } catch (_: RuntimeException) {
            return AuthorizationOutcome.failure(
                failure(operation, WorkflowHumanInputResultCode.PROVIDER_UNAVAILABLE, "authorization-unavailable", true),
            )
        }
        if (!decision.matches(request, now)) {
            return AuthorizationOutcome.failure(
                failure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "authorization-receipt-invalid", false),
            )
        }
        if (decision.status != WorkflowRuntimeAuthorizationStatus.AUTHORIZED) {
            return AuthorizationOutcome.failure(
                failure(operation, WorkflowHumanInputResultCode.AUTHORIZATION_DENIED, "authorization-denied", false),
            )
        }
        return AuthorizationOutcome.success(request, decision)
    }

    private fun reserve(
        tenantId: String,
        idempotencyKey: String,
        operation: WorkflowHumanInputOperation,
        requestDigest: String,
        now: Long,
        leaseUntil: Long,
    ): ReservationOutcome {
        val request = WorkflowHumanInputReservationRequest.of(
            tenantId,
            idempotencyKey,
            operation,
            requestDigest,
            now,
            leaseUntil,
        )
        val result = try {
            idempotencyPort.reserve(request)
        } catch (_: RuntimeException) {
            return ReservationOutcome.failure(
                failure(operation, WorkflowHumanInputResultCode.OUTCOME_UNKNOWN, "idempotency-reserve-unknown", true),
            )
        }
        return when (result.code) {
            WorkflowHumanInputReservationCode.RESERVED -> {
                val value = result.reservation
                if (value == null || value.tenantId != tenantId || value.idempotencyKey != idempotencyKey ||
                    value.operation != operation || value.requestDigest != requestDigest ||
                    value.expiresAtEpochMilli > leaseUntil || value.expiresAtEpochMilli <= now
                ) {
                    ReservationOutcome.failure(
                        failure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "reservation-invalid", false),
                    )
                } else {
                    ReservationOutcome.reserved(value)
                }
            }
            WorkflowHumanInputReservationCode.REPLAYED -> {
                val record = result.record
                if (record == null || record.tenantId != tenantId || record.idempotencyKey != idempotencyKey ||
                    !record.matches(operation, requestDigest)
                ) {
                    ReservationOutcome.failure(
                        failure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "replay-record-invalid", false),
                    )
                } else {
                    ReservationOutcome.replayed(record)
                }
            }
            WorkflowHumanInputReservationCode.CONFLICT -> ReservationOutcome.failure(
                failure(operation, WorkflowHumanInputResultCode.IDEMPOTENCY_CONFLICT, "idempotency-conflict", false),
            )
            WorkflowHumanInputReservationCode.IN_PROGRESS -> ReservationOutcome.failure(
                failure(operation, WorkflowHumanInputResultCode.OUTCOME_UNKNOWN, "idempotency-in-progress", true),
            )
            else -> ReservationOutcome.failure(
                failure(operation, WorkflowHumanInputResultCode.OUTCOME_UNKNOWN, "idempotency-reserve-unknown", true),
            )
        }
    }

    private fun complete(
        reservation: WorkflowHumanInputReservation,
        record: WorkflowHumanInputIdempotencyRecord,
        operation: WorkflowHumanInputOperation,
    ): CompletionOutcome {
        if (reservation.tenantId != record.tenantId || reservation.idempotencyKey != record.idempotencyKey ||
            reservation.operation != record.operation || reservation.requestDigest != record.requestDigest
        ) {
            return CompletionOutcome.failure(
                failure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "completion-binding-invalid", false),
            )
        }
        if (record.completedAtEpochMilli > reservation.expiresAtEpochMilli) {
            return CompletionOutcome.failure(
                failure(operation, WorkflowHumanInputResultCode.OUTCOME_UNKNOWN, "reservation-expired", true),
            )
        }
        val result = try {
            idempotencyPort.complete(reservation, record)
        } catch (_: RuntimeException) {
            return CompletionOutcome.failure(
                failure(operation, WorkflowHumanInputResultCode.OUTCOME_UNKNOWN, "idempotency-completion-unknown", true),
            )
        }
        return when (result.code) {
            WorkflowHumanInputIdempotencyWriteCode.STORED -> validateCompletion(
                result.record,
                record,
                WorkflowHumanInputResultCode.SUCCEEDED,
                operation,
            )
            WorkflowHumanInputIdempotencyWriteCode.REPLAYED -> validateCompletion(
                result.record,
                record,
                WorkflowHumanInputResultCode.REPLAYED,
                operation,
            )
            WorkflowHumanInputIdempotencyWriteCode.CONFLICT -> CompletionOutcome.failure(
                failure(operation, WorkflowHumanInputResultCode.IDEMPOTENCY_CONFLICT, "idempotency-conflict", false),
            )
            else -> CompletionOutcome.failure(
                failure(operation, WorkflowHumanInputResultCode.OUTCOME_UNKNOWN, "idempotency-completion-unknown", true),
            )
        }
    }

    private fun validateCompletion(
        returned: WorkflowHumanInputIdempotencyRecord?,
        expected: WorkflowHumanInputIdempotencyRecord,
        resultCode: WorkflowHumanInputResultCode,
        operation: WorkflowHumanInputOperation,
    ): CompletionOutcome {
        if (returned == null || returned.tenantId != expected.tenantId ||
            returned.idempotencyKey != expected.idempotencyKey ||
            !returned.matches(expected.operation, expected.requestDigest) ||
            returned.resultDigest != expected.resultDigest
        ) {
            return CompletionOutcome.failure(
                failure(operation, WorkflowHumanInputResultCode.RECEIPT_INVALID, "completion-record-invalid", false),
            )
        }
        return CompletionOutcome.success(resultCode, returned)
    }

    private fun markNotificationOutcomeUnknown(
        port: WorkflowMentionNotificationCheckpointPort,
        checkpoint: WorkflowMentionNotificationCheckpointRecord,
        reason: String,
        observedAt: Long,
        sourceEvidenceDigest: String? = null,
    ): Boolean {
        val safeObservedAt = maxOf(observedAt, checkpoint.updatedAtEpochMilli)
        val evidence = WorkflowRuntimeSupport.digest(
            "flowweft-workflow-runtime-mention-provider-outcome-unknown-v1",
        )
            .text(checkpoint.checkpointDigest)
            .text(reason)
            .bool(sourceEvidenceDigest != null)
            .also { writer -> sourceEvidenceDigest?.let { writer.text(it) } }
            .longValue(safeObservedAt)
            .finish()
        val result = try {
            port.markProviderOutcomeUnknown(WorkflowMentionNotificationOutcomeUnknown.of(
                checkpoint,
                evidence,
                safeObservedAt,
            ))
        } catch (_: Exception) {
            return false
        }
        return result.code == WorkflowMentionNotificationCheckpointCode.APPLIED ||
            result.code == WorkflowMentionNotificationCheckpointCode.REPLAYED
    }

    private fun providerContext(
        trusted: WorkflowTrustedCallContext,
        profile: WorkflowHumanInputProviderProfile,
        requestId: String,
        purpose: String,
        requestedAt: Long,
        deadline: Long,
    ): WorkflowProviderCallContext = WorkflowProviderCallContext.of(
        requestId,
        trusted.tenantId,
        profile.providerId,
        profile.providerRevision,
        purpose,
        requestedAt,
        deadline,
        profile.maximumInputBytes,
        profile.maximumOutputBytes,
        profile.maximumItems,
    )

    private fun receiptMatches(
        receipt: WorkflowProviderReceipt,
        context: WorkflowProviderCallContext,
        requestDigest: String,
        observedAt: Long,
    ): Boolean = receipt.requestId == context.requestId &&
        receipt.tenantId == context.tenantId &&
        receipt.providerId == context.providerId &&
        receipt.providerRevision == context.providerRevision &&
        receipt.contextDigest == context.contextDigest &&
        receipt.requestDigest == requestDigest &&
        receipt.completedAtEpochMilli <= observedAt &&
        observedAt <= receipt.expiresAtEpochMilli

    private fun providerDeadline(
        now: Long,
        overallDeadline: Long,
        profile: WorkflowHumanInputProviderProfile,
    ): Long? {
        val own = deadline(now, profile.callWindowMillis) ?: return null
        val result = minOf(own, overallDeadline)
        return if (result > now) result else null
    }

    private fun currentTime(operation: WorkflowHumanInputOperation): Long? = try {
        clock.currentTimeMillis().takeIf { it >= 0L }
    } catch (_: RuntimeException) {
        null
    }

    private fun deadline(now: Long, window: Long): Long? =
        if (now > Long.MAX_VALUE - window) null else now + window

    private fun <T> await(stage: CompletionStage<T>, deadline: Long): T {
        val remaining = deadline - clock.currentTimeMillis()
        require(remaining > 0L) { "Workflow provider deadline expired." }
        return stage.toCompletableFuture().get(remaining, TimeUnit.MILLISECONDS)
    }

    private fun restoreInterrupt(error: Exception) {
        var current: Throwable? = error
        while (current != null) {
            if (current is InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            current = current.cause
        }
    }

    private fun formProviderFailure(
        operation: WorkflowHumanInputOperation,
        receipt: WorkflowProviderReceipt,
    ): WorkflowRuntimeFormResult = if (providerUnavailable(receipt)) {
        formFailure(operation, WorkflowHumanInputResultCode.PROVIDER_UNAVAILABLE, "form-provider-unavailable", true)
    } else {
        formFailure(operation, WorkflowHumanInputResultCode.PROVIDER_REJECTED, "form-provider-rejected", false)
    }

    private fun commentProviderFailure(
        operation: WorkflowHumanInputOperation,
        receipt: WorkflowProviderReceipt,
    ): WorkflowRuntimeCommentResult = if (providerUnavailable(receipt)) {
        commentFailure(operation, WorkflowHumanInputResultCode.PROVIDER_UNAVAILABLE, "mention-provider-unavailable", true)
    } else {
        commentFailure(operation, WorkflowHumanInputResultCode.PROVIDER_REJECTED, "mention-provider-rejected", false)
    }

    private fun mentionSearchProviderFailure(
        operation: WorkflowHumanInputOperation,
        receipt: WorkflowProviderReceipt,
    ): WorkflowRuntimeMentionSearchResult = if (providerUnavailable(receipt)) {
        mentionSearchFailure(
            operation,
            WorkflowHumanInputResultCode.PROVIDER_UNAVAILABLE,
            "mention-provider-unavailable",
            true,
        )
    } else {
        mentionSearchFailure(
            operation,
            WorkflowHumanInputResultCode.PROVIDER_REJECTED,
            "mention-provider-rejected",
            false,
        )
    }

    private fun notificationProviderFailure(
        operation: WorkflowHumanInputOperation,
        receipt: WorkflowProviderReceipt,
    ): WorkflowRuntimeMentionNotificationResult = if (providerUnavailable(receipt)) {
        notificationFailure(
            operation,
            WorkflowHumanInputResultCode.PROVIDER_UNAVAILABLE,
            "notification-provider-unavailable",
            true,
        )
    } else {
        notificationFailure(
            operation,
            WorkflowHumanInputResultCode.PROVIDER_REJECTED,
            "notification-provider-rejected",
            false,
        )
    }

    private fun providerUnavailable(receipt: WorkflowProviderReceipt): Boolean =
        receipt.outcome == WorkflowProviderOutcome.UNAVAILABLE || receipt.failure?.retryable == true

    private fun failure(
        operation: WorkflowHumanInputOperation,
        code: WorkflowHumanInputResultCode,
        diagnosticCode: String,
        retryable: Boolean,
    ): RuntimeFailure = RuntimeFailure(
        code,
        WorkflowHumanInputDiagnostic.of("human-input-runtime", operation, diagnosticCode, retryable),
    )

    private fun formClockFailure(operation: WorkflowHumanInputOperation): WorkflowRuntimeFormResult =
        formFailure(operation, WorkflowHumanInputResultCode.INVALID, "clock-invalid", false)

    private fun formFailure(
        operation: WorkflowHumanInputOperation,
        code: WorkflowHumanInputResultCode,
        diagnosticCode: String,
        retryable: Boolean,
    ): WorkflowRuntimeFormResult = WorkflowRuntimeFormResult.failed(
        code,
        WorkflowHumanInputDiagnostic.of("human-input-runtime", operation, diagnosticCode, retryable),
    )

    private fun commentClockFailure(operation: WorkflowHumanInputOperation): WorkflowRuntimeCommentResult =
        commentFailure(operation, WorkflowHumanInputResultCode.INVALID, "clock-invalid", false)

    private fun commentFailure(
        operation: WorkflowHumanInputOperation,
        code: WorkflowHumanInputResultCode,
        diagnosticCode: String,
        retryable: Boolean,
    ): WorkflowRuntimeCommentResult = WorkflowRuntimeCommentResult.failed(
        code,
        WorkflowHumanInputDiagnostic.of("human-input-runtime", operation, diagnosticCode, retryable),
    )

    private fun mentionSearchFailure(
        operation: WorkflowHumanInputOperation,
        code: WorkflowHumanInputResultCode,
        diagnosticCode: String,
        retryable: Boolean,
    ): WorkflowRuntimeMentionSearchResult = WorkflowRuntimeMentionSearchResult.failed(
        code,
        WorkflowHumanInputDiagnostic.of("human-input-runtime", operation, diagnosticCode, retryable),
    )

    private fun notificationFailure(
        operation: WorkflowHumanInputOperation,
        code: WorkflowHumanInputResultCode,
        diagnosticCode: String,
        retryable: Boolean,
    ): WorkflowRuntimeMentionNotificationResult = WorkflowRuntimeMentionNotificationResult.failed(
        code,
        WorkflowHumanInputDiagnostic.of("human-input-runtime", operation, diagnosticCode, retryable),
    )

    private class RuntimeFailure(
        val code: WorkflowHumanInputResultCode,
        val diagnostic: WorkflowHumanInputDiagnostic,
    )

    private class AuthorizationOutcome private constructor(
        val request: WorkflowRuntimeAuthorizationRequest?,
        val decision: WorkflowRuntimeAuthorizationDecision?,
        val failure: RuntimeFailure?,
    ) {
        companion object {
            fun success(
                request: WorkflowRuntimeAuthorizationRequest,
                decision: WorkflowRuntimeAuthorizationDecision,
            ) = AuthorizationOutcome(request, decision, null)

            fun failure(failure: RuntimeFailure) = AuthorizationOutcome(null, null, failure)
        }
    }

    private class ReservationOutcome private constructor(
        val reservation: WorkflowHumanInputReservation?,
        val replay: WorkflowHumanInputIdempotencyRecord?,
        val failure: RuntimeFailure?,
    ) {
        companion object {
            fun reserved(value: WorkflowHumanInputReservation) = ReservationOutcome(value, null, null)
            fun replayed(value: WorkflowHumanInputIdempotencyRecord) = ReservationOutcome(null, value, null)
            fun failure(value: RuntimeFailure) = ReservationOutcome(null, null, value)
        }
    }

    private class CompletionOutcome private constructor(
        val resultCode: WorkflowHumanInputResultCode?,
        val record: WorkflowHumanInputIdempotencyRecord?,
        val failure: RuntimeFailure?,
    ) {
        companion object {
            fun success(
                code: WorkflowHumanInputResultCode,
                record: WorkflowHumanInputIdempotencyRecord,
            ) = CompletionOutcome(code, record, null)

            fun failure(value: RuntimeFailure) = CompletionOutcome(null, null, value)
        }
    }

    companion object {
        private const val ACTION_VALIDATE_FORM = "validate-form"
        private const val ACTION_CREATE_COMMENT = "create-comment"
        private const val ACTION_SEARCH_MENTIONS = "search-mentions"
        private const val ACTION_NOTIFY_MENTION = "notify-mention"
    }
}
