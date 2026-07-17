package ai.icen.fw.workflow.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.runtime.WorkflowNotificationEnvelope
import ai.icen.fw.workflow.spi.WorkflowNotificationChannel
import ai.icen.fw.workflow.spi.WorkflowNotificationDelivery
import ai.icen.fw.workflow.spi.WorkflowNotificationDeliveryStatus
import ai.icen.fw.workflow.spi.WorkflowNotificationIntent
import ai.icen.fw.workflow.spi.WorkflowNotificationTemplateRef
import ai.icen.fw.workflow.spi.WorkflowPayloadValidationReceipt
import ai.icen.fw.workflow.spi.WorkflowProviderCallContext
import ai.icen.fw.workflow.spi.WorkflowProviderFailure
import ai.icen.fw.workflow.spi.WorkflowProviderOutcome
import ai.icen.fw.workflow.spi.WorkflowProviderReceipt
import ai.icen.fw.workflow.spi.WorkflowSchemaRef
import ai.icen.fw.workflow.spi.WorkflowStructuredPayload
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

internal class WorkflowNotificationJdbcProviderEvidence(
    val receipt: WorkflowProviderReceipt,
    val delivery: WorkflowNotificationDelivery,
)

/** Bounded binary codec that reconstructs every immutable object and then rechecks its digest. */
internal object WorkflowNotificationJdbcCodec {
    fun encodeEnvelope(value: WorkflowNotificationEnvelope): ByteArray = Writer(ENVELOPE_MAGIC).run {
        text(value.envelopeId)
        text(value.deduplicationKey)
        text(value.originIntentDigest)
        intent(value.intent)
        principal(value.recipient)
        principal(value.issuer)
        long(value.enqueuedAt)
        text(value.envelopeDigest)
        finish()
    }

    fun decodeEnvelope(payload: ByteArray): WorkflowNotificationEnvelope = Reader(payload, ENVELOPE_MAGIC).use { reader ->
        val envelopeId = reader.text()
        val deduplicationKey = reader.text()
        val originIntentDigest = reader.text()
        val intent = reader.intent()
        val recipient = reader.principal()
        val issuer = reader.principal()
        val enqueuedAt = reader.long()
        val expectedDigest = reader.text()
        reader.end()
        WorkflowNotificationEnvelope.restore(
            envelopeId,
            deduplicationKey,
            originIntentDigest,
            intent,
            recipient,
            issuer,
            enqueuedAt,
        ).also { restored ->
            require(restored.envelopeDigest == expectedDigest) {
                "Persisted workflow notification envelope digest is inconsistent."
            }
        }
    }

    fun encodeProviderEvidence(
        receipt: WorkflowProviderReceipt,
        delivery: WorkflowNotificationDelivery,
    ): ByteArray = Writer(EVIDENCE_MAGIC).run {
        receipt(receipt)
        delivery(delivery)
        finish()
    }

    fun decodeProviderEvidence(payload: ByteArray): WorkflowNotificationJdbcProviderEvidence =
        Reader(payload, EVIDENCE_MAGIC).use { reader ->
            val receipt = reader.receipt()
            val delivery = reader.delivery()
            reader.end()
            require(receipt.resultDigest == delivery.deliveryDigest) {
                "Persisted workflow notification provider evidence is inconsistent."
            }
            WorkflowNotificationJdbcProviderEvidence(receipt, delivery)
        }

    private class Writer(magic: String) {
        private val bytes = ByteArrayOutputStream()
        private val output = DataOutputStream(bytes)

        init {
            text(magic)
            integer(FORMAT_VERSION)
        }

        fun intent(value: WorkflowNotificationIntent) {
            text(value.intentId)
            text(value.idempotencyKey)
            text(value.template.providerId)
            text(value.template.templateId)
            text(value.template.version)
            text(value.template.digest)
            text(value.channel.code)
            list(value.recipients, ::principal)
            nullable(value.subject, ::subject)
            structuredPayload(value.safeFields)
            long(value.createdAtEpochMilli)
            text(value.intentDigest)
        }

        fun receipt(value: WorkflowProviderReceipt) {
            val context = value.restoreContext()
            text(context.requestId)
            text(context.tenantId)
            text(context.providerId)
            text(context.providerRevision)
            text(context.purpose)
            long(context.requestedAtEpochMilli)
            long(context.deadlineEpochMilli)
            integer(context.maximumInputBytes)
            integer(context.maximumOutputBytes)
            integer(context.maximumItems)
            text(context.contextDigest)
            text(value.requestDigest)
            text(value.outcome.code)
            text(value.resultDigest)
            nullable(value.failure) { failure ->
                text(failure.code)
                bool(failure.retryable)
            }
            long(value.completedAtEpochMilli)
            long(value.expiresAtEpochMilli)
            text(value.receiptDigest)
        }

        fun delivery(value: WorkflowNotificationDelivery) {
            text(value.status.code)
            nullableText(value.providerMessageRef)
            text(value.evidenceDigest)
            text(value.deliveryDigest)
        }

        fun principal(value: WorkflowPrincipalRef) {
            text(value.type)
            text(value.id)
        }

        private fun subject(value: WorkflowSubjectSnapshot) {
            text(value.ref.type)
            text(value.ref.id)
            text(value.revision)
            text(value.digest)
        }

        private fun structuredPayload(value: WorkflowStructuredPayload) {
            schema(value.schema)
            bytes(value.bytes())
            val validation = requireNotNull(value.validationReceipt) {
                "Workflow notification persistence requires validated safe fields."
            }
            text(validation.validatorId)
            text(validation.validatorRevision)
            schema(validation.schema)
            text(validation.canonicalPayloadDigest)
            integer(validation.fieldCount)
            text(validation.authorityReceiptDigest)
            text(validation.receiptDigest)
            text(value.canonicalPayloadDigest)
            text(value.contentDigest)
        }

        private fun schema(value: WorkflowSchemaRef) {
            text(value.providerId)
            text(value.schemaId)
            text(value.version)
            text(value.digest)
        }

        fun text(value: String) {
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            require(encoded.size <= MAX_STRING_BYTES) { "Workflow notification text exceeds the codec limit." }
            output.writeInt(encoded.size)
            output.write(encoded)
        }

        private fun bytes(value: ByteArray) {
            require(value.size <= MAX_PAYLOAD_BYTES) { "Workflow notification payload exceeds the codec limit." }
            output.writeInt(value.size)
            output.write(value)
        }

        private fun nullableText(value: String?) = nullable(value, ::text)
        private fun integer(value: Int) = output.writeInt(value)
        fun long(value: Long) = output.writeLong(value)
        private fun bool(value: Boolean) = output.writeBoolean(value)

        private fun <T> nullable(value: T?, writer: (T) -> Unit) {
            output.writeBoolean(value != null)
            if (value != null) writer(value)
        }

        private fun <T> list(values: Collection<T>, writer: (T) -> Unit) {
            require(values.size <= MAX_COLLECTION_ITEMS) {
                "Workflow notification collection exceeds the codec limit."
            }
            integer(values.size)
            values.forEach(writer)
        }

        fun finish(): ByteArray {
            output.flush()
            return bytes.toByteArray().also { result ->
                require(result.size <= MAX_ENCODED_BYTES) {
                    "Workflow notification record exceeds the codec limit."
                }
            }
        }
    }

    private class Reader(payload: ByteArray, expectedMagic: String) : AutoCloseable {
        private val input: DataInputStream

        init {
            require(payload.size <= MAX_ENCODED_BYTES) {
                "Persisted workflow notification payload exceeds the codec limit."
            }
            input = DataInputStream(ByteArrayInputStream(payload))
            require(text() == expectedMagic && integer() == FORMAT_VERSION) {
                "Persisted workflow notification codec header is unsupported."
            }
        }

        fun intent(): WorkflowNotificationIntent {
            val intentId = text()
            val idempotencyKey = text()
            val template = WorkflowNotificationTemplateRef.of(text(), text(), text(), text())
            val channel = WorkflowNotificationChannel.of(text())
            val recipients = list { principal() }
            val subject = nullable { subject() }
            val safeFields = structuredPayload()
            val createdAt = long()
            val expectedDigest = text()
            return WorkflowNotificationIntent.of(
                intentId,
                idempotencyKey,
                template,
                channel,
                recipients,
                subject,
                safeFields,
                createdAt,
            ).also { restored ->
                require(restored.intentDigest == expectedDigest) {
                    "Persisted workflow notification intent digest is inconsistent."
                }
            }
        }

        fun receipt(): WorkflowProviderReceipt {
            val context = WorkflowProviderCallContext.of(
                text(),
                text(),
                text(),
                text(),
                text(),
                long(),
                long(),
                integer(),
                integer(),
                integer(),
            )
            val expectedContextDigest = text()
            require(context.contextDigest == expectedContextDigest) {
                "Persisted workflow notification provider context digest is inconsistent."
            }
            val requestDigest = text()
            val outcome = WorkflowProviderOutcome.of(text())
            val resultDigest = text()
            val failure = nullable { WorkflowProviderFailure.of(text(), bool()) }
            val completedAt = long()
            val expiresAt = long()
            val expectedReceiptDigest = text()
            val receipt = if (outcome == WorkflowProviderOutcome.SUCCESS) {
                require(failure == null) { "Persisted successful notification receipt has failure metadata." }
                WorkflowProviderReceipt.success(context, requestDigest, resultDigest, completedAt, expiresAt)
            } else {
                WorkflowProviderReceipt.failure(
                    context,
                    requestDigest,
                    outcome,
                    resultDigest,
                    requireNotNull(failure),
                    completedAt,
                    expiresAt,
                )
            }
            require(receipt.receiptDigest == expectedReceiptDigest) {
                "Persisted workflow notification provider receipt digest is inconsistent."
            }
            return receipt
        }

        fun delivery(): WorkflowNotificationDelivery {
            val status = WorkflowNotificationDeliveryStatus.of(text())
            val providerMessageRef = nullableText()
            val evidenceDigest = text()
            val expectedDigest = text()
            val delivery = when (status) {
                WorkflowNotificationDeliveryStatus.ACCEPTED ->
                    WorkflowNotificationDelivery.accepted(requireNotNull(providerMessageRef), evidenceDigest)
                WorkflowNotificationDeliveryStatus.SUPPRESSED -> {
                    require(providerMessageRef == null) {
                        "Persisted suppressed notification has a provider reference."
                    }
                    WorkflowNotificationDelivery.suppressed(evidenceDigest)
                }
                else -> throw IllegalArgumentException("Persisted workflow notification delivery status is unsupported.")
            }
            require(delivery.deliveryDigest == expectedDigest) {
                "Persisted workflow notification delivery digest is inconsistent."
            }
            return delivery
        }

        fun principal(): WorkflowPrincipalRef = WorkflowPrincipalRef.of(text(), text())

        private fun subject(): WorkflowSubjectSnapshot = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of(text(), text()),
            text(),
            text(),
        )

        private fun structuredPayload(): WorkflowStructuredPayload {
            val schema = schema()
            val content = bytes()
            val validatorId = text()
            val validatorRevision = text()
            val validationSchema = schema()
            val canonicalPayloadDigest = text()
            val fieldCount = integer()
            val authorityReceiptDigest = text()
            val expectedValidationDigest = text()
            val expectedCanonicalDigest = text()
            val expectedContentDigest = text()
            val raw = WorkflowStructuredPayload.of(schema, content)
            val validation = WorkflowPayloadValidationReceipt.of(
                validatorId,
                validatorRevision,
                validationSchema,
                canonicalPayloadDigest,
                fieldCount,
                authorityReceiptDigest,
            )
            require(validation.receiptDigest == expectedValidationDigest) {
                "Persisted notification payload validation digest is inconsistent."
            }
            return WorkflowStructuredPayload.validated(raw, validation).also { restored ->
                require(restored.canonicalPayloadDigest == expectedCanonicalDigest &&
                    restored.contentDigest == expectedContentDigest
                ) { "Persisted notification safe-field digest is inconsistent." }
            }
        }

        private fun schema(): WorkflowSchemaRef = WorkflowSchemaRef.of(text(), text(), text(), text())

        fun text(): String {
            val size = input.readInt()
            require(size in 0..MAX_STRING_BYTES && size <= input.available()) {
                "Persisted workflow notification text length is invalid."
            }
            val encoded = ByteArray(size).also { input.readFully(it) }
            return String(encoded, StandardCharsets.UTF_8)
        }

        private fun bytes(): ByteArray {
            val size = input.readInt()
            require(size in 0..MAX_PAYLOAD_BYTES && size <= input.available()) {
                "Persisted workflow notification binary length is invalid."
            }
            return ByteArray(size).also { input.readFully(it) }
        }

        private fun nullableText(): String? = nullable { text() }
        private fun integer(): Int = input.readInt()
        fun long(): Long = input.readLong()
        private fun bool(): Boolean = input.readBoolean()
        private fun <T> nullable(reader: () -> T): T? = if (input.readBoolean()) reader() else null

        private fun <T> list(reader: Reader.() -> T): List<T> {
            val size = integer()
            require(size in 0..MAX_COLLECTION_ITEMS) {
                "Persisted workflow notification collection length is invalid."
            }
            return ArrayList<T>(size).also { result -> repeat(size) { result += reader(this) } }
        }

        fun end() {
            require(input.available() == 0) { "Persisted workflow notification payload contains trailing data." }
        }

        override fun close() = input.close()
    }

    private const val ENVELOPE_MAGIC = "flowweft-workflow-notification-envelope-jdbc"
    private const val EVIDENCE_MAGIC = "flowweft-workflow-notification-evidence-jdbc"
    private const val FORMAT_VERSION = 1
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_COLLECTION_ITEMS = 256
    private const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024
    private const val MAX_ENCODED_BYTES = 8 * 1024 * 1024
}
