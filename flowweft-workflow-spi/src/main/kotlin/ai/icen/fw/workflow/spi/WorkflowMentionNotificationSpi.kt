package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowCommentSnapshot
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import java.util.concurrent.CompletionStage

/**
 * Invocation-bound evidence that one recipient was still visible immediately before an outbox
 * worker requested delivery. It is consistency evidence, never a grant to read the comment.
 */
class WorkflowMentionVisibilityAttestation private constructor(
    val recipient: WorkflowPrincipalRef,
    authorityRevision: String,
    visibilityReceiptDigest: String,
    val checkedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val authorityRevision: String = WorkflowSpiContractSupport.requireText(
        authorityRevision,
        WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES,
        "Workflow mention notification authority revision is invalid.",
    )
    val visibilityReceiptDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        visibilityReceiptDigest,
        "Workflow mention notification visibility receipt digest is invalid.",
    )
    val attestationDigest: String

    init {
        require(checkedAtEpochMilli >= 0L && expiresAtEpochMilli > checkedAtEpochMilli) {
            "Workflow mention notification visibility window is invalid."
        }
        require(expiresAtEpochMilli - checkedAtEpochMilli <= WorkflowSpiContractSupport.MAX_CALL_WINDOW_MILLIS) {
            "Workflow mention notification visibility window exceeds the limit."
        }
        attestationDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-mention-notify-attestation-v1")
            .text(recipient.type)
            .text(recipient.id)
            .text(this.authorityRevision)
            .text(this.visibilityReceiptDigest)
            .longValue(checkedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "WorkflowMentionVisibilityAttestation(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            recipient: WorkflowPrincipalRef,
            authorityRevision: String,
            visibilityReceiptDigest: String,
            checkedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowMentionVisibilityAttestation = WorkflowMentionVisibilityAttestation(
            recipient,
            authorityRevision,
            visibilityReceiptDigest,
            checkedAtEpochMilli,
            expiresAtEpochMilli,
        )
    }
}

/**
 * Safe mention-notification intent: it carries no comment text or subject summary. The recipient
 * still needs normal authorization when following the notification.
 */
class WorkflowMentionNotificationIntent private constructor(
    intentId: String,
    idempotencyKey: String,
    val comment: WorkflowCommentSnapshot,
    val recipient: WorkflowPrincipalRef,
    val visibility: WorkflowMentionVisibilityAttestation,
    val createdAtEpochMilli: Long,
) {
    val intentId: String = WorkflowSpiContractSupport.requireText(
        intentId,
        WorkflowSpiContractSupport.MAX_ID_UTF8_BYTES,
        "Workflow mention notification intent identifier is invalid.",
    )
    val idempotencyKey: String = WorkflowSpiContractSupport.requireText(
        idempotencyKey,
        WorkflowSpiContractSupport.MAX_ID_UTF8_BYTES,
        "Workflow mention notification idempotency key is invalid.",
    )
    val intentDigest: String

    init {
        require(recipient == visibility.recipient && comment.document.mentionedPrincipals.contains(recipient)) {
            "Workflow mention notification recipient is not attested by the comment."
        }
        require(createdAtEpochMilli in visibility.checkedAtEpochMilli..visibility.expiresAtEpochMilli) {
            "Workflow mention notification was not created under current visibility evidence."
        }
        intentDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-mention-notification-intent-v1")
            .text(this.intentId)
            .text(this.idempotencyKey)
            .text(comment.commentId)
            .longValue(comment.version)
            .text(comment.snapshotDigest)
            .text(recipient.type)
            .text(recipient.id)
            .text(visibility.attestationDigest)
            .longValue(createdAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "WorkflowMentionNotificationIntent(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            intentId: String,
            idempotencyKey: String,
            comment: WorkflowCommentSnapshot,
            recipient: WorkflowPrincipalRef,
            visibility: WorkflowMentionVisibilityAttestation,
            createdAtEpochMilli: Long,
        ): WorkflowMentionNotificationIntent = WorkflowMentionNotificationIntent(
            intentId,
            idempotencyKey,
            comment,
            recipient,
            visibility,
            createdAtEpochMilli,
        )
    }
}

class WorkflowMentionNotificationRequest private constructor(
    val context: WorkflowProviderCallContext,
    val intent: WorkflowMentionNotificationIntent,
) {
    val requestDigest: String

    init {
        require(context.requestedAtEpochMilli in
            intent.visibility.checkedAtEpochMilli..intent.visibility.expiresAtEpochMilli
        ) { "Workflow mention notification visibility evidence expired before delivery." }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-mention-notification-request-v1")
            .text(context.contextDigest)
            .text(intent.intentDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowMentionNotificationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowProviderCallContext,
            intent: WorkflowMentionNotificationIntent,
        ): WorkflowMentionNotificationRequest = WorkflowMentionNotificationRequest(context, intent)
    }
}

class WorkflowMentionNotificationResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val delivery: WorkflowNotificationDelivery?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (delivery != null)) {
            "Workflow mention notification result content does not match its outcome."
        }
    }

    override fun toString(): String = "WorkflowMentionNotificationResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowMentionNotificationRequest,
            delivery: WorkflowNotificationDelivery,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowMentionNotificationResult = WorkflowMentionNotificationResult(
            WorkflowProviderReceipt.success(
                request.context,
                request.requestDigest,
                delivery.deliveryDigest,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            delivery,
        )

        @JvmStatic
        fun failure(
            request: WorkflowMentionNotificationRequest,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowMentionNotificationResult = WorkflowMentionNotificationResult(
            WorkflowProviderReceipt.failure(
                request.context,
                request.requestDigest,
                outcome,
                WorkflowSpiContractSupport.failureDigest(
                    "flowweft-workflow-spi-mention-notification-failure-v1",
                    failure,
                ),
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            null,
        )
    }
}

fun interface WorkflowMentionNotificationProvider {
    fun send(request: WorkflowMentionNotificationRequest): CompletionStage<WorkflowMentionNotificationResult>
}
