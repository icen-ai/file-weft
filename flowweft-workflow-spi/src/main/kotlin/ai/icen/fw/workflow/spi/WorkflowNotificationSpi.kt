package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import java.util.concurrent.CompletionStage

class WorkflowNotificationTemplateRef private constructor(
    providerId: String,
    templateId: String,
    version: String,
    digest: String,
) {
    val providerId: String = WorkflowSpiContractSupport.requireMachineCode(providerId, "Workflow notification provider is invalid.")
    val templateId: String = WorkflowSpiContractSupport.requireMachineCode(templateId, "Workflow notification template is invalid.")
    val version: String = WorkflowSpiContractSupport.requireText(
        version, WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES, "Workflow notification template version is invalid.",
    )
    val digest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        digest, "Workflow notification template digest is invalid.",
    )

    override fun equals(other: Any?): Boolean = this === other ||
        other is WorkflowNotificationTemplateRef && providerId == other.providerId &&
        templateId == other.templateId && version == other.version && digest == other.digest

    override fun hashCode(): Int {
        var result = providerId.hashCode()
        result = 31 * result + templateId.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + digest.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowNotificationTemplateRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(providerId: String, templateId: String, version: String, digest: String): WorkflowNotificationTemplateRef =
            WorkflowNotificationTemplateRef(providerId, templateId, version, digest)
    }
}

class WorkflowNotificationChannel private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(code, "Workflow notification channel is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowNotificationChannel && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowNotificationChannel(<redacted>)"

    companion object {
        @JvmField val IN_APP = WorkflowNotificationChannel("in-app")
        @JvmField val EMAIL = WorkflowNotificationChannel("email")
        @JvmField val INSTANT_MESSAGE = WorkflowNotificationChannel("instant-message")

        @JvmStatic
        fun of(code: String): WorkflowNotificationChannel = when (code) {
            IN_APP.code -> IN_APP
            EMAIL.code -> EMAIL
            INSTANT_MESSAGE.code -> INSTANT_MESSAGE
            else -> WorkflowNotificationChannel(code)
        }
    }
}

/** Outbox-safe intent. Recipients are principals; network endpoints never enter workflow data. */
class WorkflowNotificationIntent private constructor(
    intentId: String,
    idempotencyKey: String,
    val template: WorkflowNotificationTemplateRef,
    val channel: WorkflowNotificationChannel,
    recipients: Collection<WorkflowPrincipalRef>,
    val subject: WorkflowSubjectSnapshot?,
    val safeFields: WorkflowStructuredPayload,
    val createdAtEpochMilli: Long,
) {
    val intentId: String = WorkflowSpiContractSupport.requireText(
        intentId, WorkflowSpiContractSupport.MAX_ID_UTF8_BYTES, "Workflow notification intent identifier is invalid.",
    )
    val idempotencyKey: String = WorkflowSpiContractSupport.requireText(
        idempotencyKey, WorkflowSpiContractSupport.MAX_ID_UTF8_BYTES, "Workflow notification idempotency key is invalid.",
    )
    val recipients: List<WorkflowPrincipalRef> = WorkflowSpiContractSupport.immutableList(
        recipients,
        WorkflowSpiContractSupport.MAX_ITEMS,
        "Workflow notification recipients exceed the limit.",
    )
    val intentDigest: String

    init {
        require(this.recipients.isNotEmpty()) { "Workflow notifications require recipients." }
        require(this.recipients.toSet().size == this.recipients.size) { "Workflow notification recipients must be unique." }
        require(channel == WorkflowNotificationChannel.IN_APP || channel == WorkflowNotificationChannel.EMAIL ||
            channel == WorkflowNotificationChannel.INSTANT_MESSAGE
        ) { "Unknown workflow notification channels require future typed support." }
        require(safeFields.validated) { "Workflow notification fields require trusted schema-validation evidence." }
        require(createdAtEpochMilli >= 0L) { "Workflow notification creation time is invalid." }
        intentDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-notification-intent-v1")
            .text(this.intentId)
            .text(this.idempotencyKey)
            .text(template.providerId)
            .text(template.templateId)
            .text(template.version)
            .text(template.digest)
            .text(channel.code)
            .integer(this.recipients.size)
            .also { writer -> this.recipients.forEach { principal -> writer.text(principal.type).text(principal.id) } }
            .optionalText(subject?.ref?.type)
            .optionalText(subject?.ref?.id)
            .optionalText(subject?.revision)
            .optionalText(subject?.digest)
            .text(safeFields.contentDigest)
            .longValue(createdAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "WorkflowNotificationIntent(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            intentId: String,
            idempotencyKey: String,
            template: WorkflowNotificationTemplateRef,
            channel: WorkflowNotificationChannel,
            recipients: Collection<WorkflowPrincipalRef>,
            subject: WorkflowSubjectSnapshot?,
            safeFields: WorkflowStructuredPayload,
            createdAtEpochMilli: Long,
        ): WorkflowNotificationIntent = WorkflowNotificationIntent(
            intentId,
            idempotencyKey,
            template,
            channel,
            recipients,
            subject,
            safeFields,
            createdAtEpochMilli,
        )
    }
}

class WorkflowNotificationRequest private constructor(
    val context: WorkflowProviderCallContext,
    val intent: WorkflowNotificationIntent,
) {
    val requestDigest: String

    init {
        require(context.providerId == intent.template.providerId) {
            "Workflow notification template provider does not match the call context."
        }
        require(intent.recipients.size <= context.maximumItems) { "Workflow notification recipient limit is exceeded." }
        require(intent.safeFields.size <= context.maximumInputBytes) { "Workflow notification payload exceeds the call limit." }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-notification-request-v1")
            .text(context.contextDigest)
            .text(intent.intentDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowNotificationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(context: WorkflowProviderCallContext, intent: WorkflowNotificationIntent): WorkflowNotificationRequest =
            WorkflowNotificationRequest(context, intent)
    }
}

class WorkflowNotificationDeliveryStatus private constructor(code: String) {
    val code: String = WorkflowSpiContractSupport.requireMachineCode(code, "Workflow notification status is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowNotificationDeliveryStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowNotificationDeliveryStatus(<redacted>)"

    companion object {
        @JvmField val ACCEPTED = WorkflowNotificationDeliveryStatus("accepted")
        @JvmField val SUPPRESSED = WorkflowNotificationDeliveryStatus("suppressed")

        @JvmStatic
        fun of(code: String): WorkflowNotificationDeliveryStatus = when (code) {
            ACCEPTED.code -> ACCEPTED
            SUPPRESSED.code -> SUPPRESSED
            else -> WorkflowNotificationDeliveryStatus(code)
        }
    }
}

class WorkflowNotificationDelivery private constructor(
    val status: WorkflowNotificationDeliveryStatus,
    providerMessageRef: String?,
    evidenceDigest: String,
) {
    val providerMessageRef: String? = providerMessageRef?.let {
        WorkflowSpiContractSupport.requireOpaqueReference(
            it, "Workflow notification provider message reference is invalid.",
        )
    }
    val evidenceDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        evidenceDigest, "Workflow notification evidence digest is invalid.",
    )
    val deliveryDigest: String

    init {
        require(status == WorkflowNotificationDeliveryStatus.ACCEPTED ||
            status == WorkflowNotificationDeliveryStatus.SUPPRESSED
        ) { "Unknown workflow notification statuses require future typed support." }
        require(status != WorkflowNotificationDeliveryStatus.SUPPRESSED || this.providerMessageRef == null) {
            "Suppressed workflow notifications cannot have a provider message reference."
        }
        deliveryDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-notification-delivery-v1")
            .text(status.code)
            .optionalText(this.providerMessageRef)
            .text(this.evidenceDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowNotificationDelivery(<redacted>)"

    companion object {
        @JvmStatic
        fun accepted(providerMessageRef: String, evidenceDigest: String): WorkflowNotificationDelivery =
            WorkflowNotificationDelivery(WorkflowNotificationDeliveryStatus.ACCEPTED, providerMessageRef, evidenceDigest)

        @JvmStatic
        fun suppressed(evidenceDigest: String): WorkflowNotificationDelivery =
            WorkflowNotificationDelivery(WorkflowNotificationDeliveryStatus.SUPPRESSED, null, evidenceDigest)
    }
}

class WorkflowNotificationResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val delivery: WorkflowNotificationDelivery?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (delivery != null)) {
            "Workflow notification result content does not match its outcome."
        }
    }

    override fun toString(): String = "WorkflowNotificationResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowNotificationRequest,
            delivery: WorkflowNotificationDelivery,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowNotificationResult = WorkflowNotificationResult(
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
            request: WorkflowNotificationRequest,
            outcome: WorkflowProviderOutcome,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowNotificationResult = WorkflowNotificationResult(
            WorkflowProviderReceipt.failure(
                request.context,
                request.requestDigest,
                outcome,
                WorkflowSpiContractSupport.failureDigest("flowweft-workflow-spi-notification-failure-v1", failure),
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            null,
        )
    }
}

fun interface WorkflowNotificationProvider {
    fun send(request: WorkflowNotificationRequest): CompletionStage<WorkflowNotificationResult>
}
