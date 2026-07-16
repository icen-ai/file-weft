package ai.icen.fw.workflow.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowCommentDocument
import ai.icen.fw.workflow.api.WorkflowCommentSnapshot
import ai.icen.fw.workflow.api.WorkflowCommentToken
import ai.icen.fw.workflow.api.WorkflowCommentTokenKind
import ai.icen.fw.workflow.api.WorkflowFormFieldAccessDecision
import ai.icen.fw.workflow.api.WorkflowFormFieldAccessMode
import ai.icen.fw.workflow.api.WorkflowFormFieldAccessReport
import ai.icen.fw.workflow.api.WorkflowFormFieldPath
import ai.icen.fw.workflow.api.WorkflowFormSubmissionRef
import ai.icen.fw.workflow.api.WorkflowFormVersionRef
import ai.icen.fw.workflow.api.WorkflowInstanceRef
import ai.icen.fw.workflow.api.WorkflowJsonSchemaDialect
import ai.icen.fw.workflow.api.WorkflowJsonSchemaRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowWorkItemRef
import ai.icen.fw.workflow.runtime.WorkflowHumanInputIdempotencyRecord
import ai.icen.fw.workflow.runtime.WorkflowHumanInputOperation
import ai.icen.fw.workflow.runtime.WorkflowRuntimeValidatedForm
import ai.icen.fw.workflow.spi.WorkflowFormValidationOperation
import ai.icen.fw.workflow.spi.WorkflowNotificationDelivery
import ai.icen.fw.workflow.spi.WorkflowNotificationDeliveryStatus
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

internal class WorkflowHumanInputJdbcEncodedResult(
    val kind: String,
    val payload: ByteArray,
    val providerReceiptDigest: String?,
    val receiptExpiresAt: Long?,
)

/** Deterministic, bounded codec for idempotent human-input result replay. */
internal object WorkflowHumanInputJdbcCodec {
    fun encode(record: WorkflowHumanInputIdempotencyRecord): WorkflowHumanInputJdbcEncodedResult {
        val writer = Writer()
        val receipt = when (record.operation) {
            WorkflowHumanInputOperation.FORM_VALIDATE -> {
                writer.text(KIND_FORM)
                val form = requireNotNull(record.validatedForm)
                writer.form(form)
                form.providerReceipt
            }
            WorkflowHumanInputOperation.COMMENT_CREATE -> {
                writer.text(KIND_COMMENT)
                writer.comment(requireNotNull(record.comment))
                null
            }
            WorkflowHumanInputOperation.MENTION_NOTIFY -> {
                writer.text(KIND_NOTIFICATION)
                val delivery = requireNotNull(record.delivery)
                val receipt = requireNotNull(record.notificationReceipt)
                writer.delivery(delivery)
                writer.receipt(receipt)
                receipt
            }
            else -> throw IllegalArgumentException("Unsupported workflow human-input result kind.")
        }
        writer.text(record.resultDigest)
        return WorkflowHumanInputJdbcEncodedResult(
            kind(record.operation),
            writer.finish(),
            receipt?.receiptDigest,
            receipt?.expiresAtEpochMilli,
        )
    }

    fun decode(
        tenantId: String,
        idempotencyKey: String,
        operation: WorkflowHumanInputOperation,
        requestDigest: String,
        expectedResultDigest: String,
        payload: ByteArray,
        completedAtEpochMilli: Long,
    ): WorkflowHumanInputIdempotencyRecord = Reader(payload).use { reader ->
        val storedKind = reader.text()
        val record = when (operation) {
            WorkflowHumanInputOperation.FORM_VALIDATE -> {
                require(storedKind == KIND_FORM) { "Persisted workflow form result kind is invalid." }
                WorkflowHumanInputIdempotencyRecord.form(
                    tenantId,
                    idempotencyKey,
                    requestDigest,
                    reader.form(),
                    completedAtEpochMilli,
                )
            }
            WorkflowHumanInputOperation.COMMENT_CREATE -> {
                require(storedKind == KIND_COMMENT) { "Persisted workflow comment result kind is invalid." }
                WorkflowHumanInputIdempotencyRecord.comment(
                    tenantId,
                    idempotencyKey,
                    requestDigest,
                    reader.comment(),
                    completedAtEpochMilli,
                )
            }
            WorkflowHumanInputOperation.MENTION_NOTIFY -> {
                require(storedKind == KIND_NOTIFICATION) { "Persisted workflow notification result kind is invalid." }
                val delivery = reader.delivery()
                WorkflowHumanInputIdempotencyRecord.notification(
                    tenantId,
                    idempotencyKey,
                    requestDigest,
                    delivery,
                    reader.receipt(),
                    completedAtEpochMilli,
                )
            }
            else -> throw IllegalArgumentException("Unsupported persisted workflow human-input operation.")
        }
        val encodedResultDigest = reader.text()
        reader.end()
        require(encodedResultDigest == expectedResultDigest && record.resultDigest == expectedResultDigest) {
            "Persisted workflow human-input result digest is inconsistent."
        }
        record
    }

    private fun kind(operation: WorkflowHumanInputOperation): String = when (operation) {
        WorkflowHumanInputOperation.FORM_VALIDATE -> KIND_FORM
        WorkflowHumanInputOperation.COMMENT_CREATE -> KIND_COMMENT
        WorkflowHumanInputOperation.MENTION_NOTIFY -> KIND_NOTIFICATION
        else -> throw IllegalArgumentException("Unsupported workflow human-input operation.")
    }

    private class Writer {
        private val bytes = ByteArrayOutputStream()
        private val output = DataOutputStream(bytes)

        init {
            text(MAGIC)
            integer(FORMAT_VERSION)
        }

        fun form(value: WorkflowRuntimeValidatedForm) {
            formRef(value.form)
            text(value.operation.code)
            structuredPayload(value.normalizedSubmission)
            access(value.fieldAccess)
            receipt(value.providerReceipt)
            nullable(value.submission) { submission -> submission(submission) }
            text(value.evidenceDigest)
        }

        fun comment(value: WorkflowCommentSnapshot) {
            text(value.commentId)
            long(value.version)
            text(value.instance.id)
            long(value.instance.expectedVersion)
            nullable(value.workItem) { workItem ->
                text(workItem.id)
                long(workItem.expectedVersion)
            }
            principal(value.author)
            integer(value.document.schemaVersion)
            list(value.document.tokens) { token ->
                text(token.kind.code)
                when (token.kind) {
                    WorkflowCommentTokenKind.TEXT -> text(requireNotNull(token.text))
                    WorkflowCommentTokenKind.MENTION -> {
                        principal(requireNotNull(token.principal))
                        text(requireNotNull(token.displayNameSnapshot))
                    }
                    else -> throw IllegalArgumentException("Unknown workflow comment token kind cannot be persisted.")
                }
                text(token.tokenDigest)
            }
            text(value.document.documentDigest)
            text(value.authorAuthorizationReceiptDigest)
            nullableText(value.mentionAttestationReceiptDigest)
            long(value.createdAtEpochMilli)
            text(value.snapshotDigest)
        }

        fun delivery(value: WorkflowNotificationDelivery) {
            text(value.status.code)
            nullableText(value.providerMessageRef)
            text(value.evidenceDigest)
            text(value.deliveryDigest)
        }

        private fun formRef(value: WorkflowFormVersionRef) {
            text(value.formKey)
            text(value.version)
            text(value.dataSchema.registryId)
            text(value.dataSchema.schemaId)
            text(value.dataSchema.version)
            text(value.dataSchema.dialect.code)
            text(value.dataSchema.digest)
            nullableText(value.uiSchemaVersion)
            nullableText(value.uiSchemaDigest)
            text(value.formDigest)
            text(value.bindingDigest)
        }

        private fun structuredPayload(value: WorkflowStructuredPayload) {
            schema(value.schema)
            bytes(value.bytes())
            val validation = requireNotNull(value.validationReceipt) {
                "Workflow JDBC form replay requires validation evidence."
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

        private fun access(value: WorkflowFormFieldAccessReport) {
            list(value.decisions) { decision ->
                text(decision.path.value)
                text(decision.readMode.code)
                text(decision.writeMode.code)
                text(decision.decisionDigest)
            }
            text(value.authorityReceiptDigest)
            text(value.reportDigest)
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

        private fun submission(value: WorkflowFormSubmissionRef) {
            text(value.submissionId)
            long(value.version)
            principal(value.submittedBy)
            text(value.canonicalPayloadDigest)
            integer(value.payloadSizeBytes)
            text(value.validationReceiptDigest)
            text(value.fieldAccessReceiptDigest)
            long(value.submittedAtEpochMilli)
            text(value.submissionDigest)
        }

        private fun schema(value: WorkflowSchemaRef) {
            text(value.providerId)
            text(value.schemaId)
            text(value.version)
            text(value.digest)
        }

        private fun principal(value: WorkflowPrincipalRef) {
            text(value.type)
            text(value.id)
        }

        fun text(value: String) {
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            require(encoded.size <= MAX_STRING_BYTES) { "Workflow human-input text exceeds the codec limit." }
            output.writeInt(encoded.size)
            output.write(encoded)
        }

        private fun bytes(value: ByteArray) {
            require(value.size <= MAX_PAYLOAD_BYTES) { "Workflow human-input payload exceeds the codec limit." }
            output.writeInt(value.size)
            output.write(value)
        }

        private fun nullableText(value: String?) = nullable(value, ::text)
        private fun integer(value: Int) = output.writeInt(value)
        private fun long(value: Long) = output.writeLong(value)
        private fun bool(value: Boolean) = output.writeBoolean(value)

        private fun <T> nullable(value: T?, writer: (T) -> Unit) {
            output.writeBoolean(value != null)
            if (value != null) writer(value)
        }

        private fun <T> list(values: Collection<T>, writer: (T) -> Unit) {
            require(values.size <= MAX_COLLECTION_ITEMS) {
                "Workflow human-input collection exceeds the codec limit."
            }
            integer(values.size)
            values.forEach(writer)
        }

        fun finish(): ByteArray {
            output.flush()
            val result = bytes.toByteArray()
            require(result.size <= MAX_ENCODED_BYTES) { "Workflow human-input record exceeds the codec limit." }
            return result
        }
    }

    private class Reader(payload: ByteArray) : AutoCloseable {
        private val input: DataInputStream

        init {
            require(payload.size <= MAX_ENCODED_BYTES) { "Persisted workflow human-input payload exceeds the limit." }
            input = DataInputStream(ByteArrayInputStream(payload))
            require(text() == MAGIC && integer() == FORMAT_VERSION) {
                "Persisted workflow human-input codec header is unsupported."
            }
        }

        fun form(): WorkflowRuntimeValidatedForm {
            val form = formRef()
            val operation = WorkflowFormValidationOperation.of(text())
            require(operation == WorkflowFormValidationOperation.READ ||
                operation == WorkflowFormValidationOperation.SUBMIT
            ) { "Persisted workflow form operation is unsupported." }
            val payload = structuredPayload()
            val access = access()
            val providerReceipt = receipt()
            val submission = nullable { submission(form) }
            val evidenceDigest = text()
            val value = WorkflowRuntimeValidatedForm.of(
                form,
                operation,
                payload,
                access,
                providerReceipt,
                submission,
            )
            require(value.evidenceDigest == evidenceDigest) {
                "Persisted workflow validated-form digest is inconsistent."
            }
            return value
        }

        fun comment(): WorkflowCommentSnapshot {
            val commentId = text()
            val version = long()
            val instance = WorkflowInstanceRef.of(text(), long())
            val workItem = nullable { WorkflowWorkItemRef.of(text(), long()) }
            val author = principal()
            val schemaVersion = integer()
            val tokens = list {
                val kind = text()
                val token = when (kind) {
                    WorkflowCommentTokenKind.TEXT.code -> WorkflowCommentToken.text(text())
                    WorkflowCommentTokenKind.MENTION.code -> WorkflowCommentToken.mention(principal(), text())
                    else -> throw IllegalArgumentException("Persisted workflow comment token kind is unsupported.")
                }
                require(token.tokenDigest == text()) { "Persisted workflow comment token digest is inconsistent." }
                token
            }
            val document = WorkflowCommentDocument.restore(schemaVersion, tokens)
            require(document.documentDigest == text()) { "Persisted workflow comment document digest is inconsistent." }
            val authorizationDigest = text()
            val mentionDigest = nullableText()
            val createdAt = long()
            val snapshotDigest = text()
            val value = WorkflowCommentSnapshot.of(
                commentId,
                version,
                instance,
                workItem,
                author,
                document,
                authorizationDigest,
                mentionDigest,
                createdAt,
            )
            require(value.snapshotDigest == snapshotDigest) {
                "Persisted workflow comment snapshot digest is inconsistent."
            }
            return value
        }

        fun delivery(): WorkflowNotificationDelivery {
            val status = WorkflowNotificationDeliveryStatus.of(text())
            val providerRef = nullableText()
            val evidence = text()
            val deliveryDigest = text()
            val value = when (status) {
                WorkflowNotificationDeliveryStatus.ACCEPTED ->
                    WorkflowNotificationDelivery.accepted(requireNotNull(providerRef), evidence)
                WorkflowNotificationDeliveryStatus.SUPPRESSED -> WorkflowNotificationDelivery.suppressed(evidence)
                else -> throw IllegalArgumentException("Persisted workflow notification status is unsupported.")
            }
            require(value.deliveryDigest == deliveryDigest) {
                "Persisted workflow notification delivery digest is inconsistent."
            }
            return value
        }

        private fun formRef(): WorkflowFormVersionRef {
            val formKey = text()
            val version = text()
            val schema = WorkflowJsonSchemaRef.of(
                text(),
                text(),
                text(),
                WorkflowJsonSchemaDialect.of(text()),
                text(),
            )
            val uiVersion = nullableText()
            val uiDigest = nullableText()
            val formDigest = text()
            val bindingDigest = text()
            val value = WorkflowFormVersionRef.of(formKey, version, schema, uiVersion, uiDigest, formDigest)
            require(value.bindingDigest == bindingDigest) { "Persisted workflow form binding digest is inconsistent." }
            return value
        }

        private fun structuredPayload(): WorkflowStructuredPayload {
            val schema = schema()
            val bytes = bytes()
            val validatorId = text()
            val validatorRevision = text()
            val validationSchema = schema()
            val canonicalDigest = text()
            val fieldCount = integer()
            val authorityDigest = text()
            val validationDigest = text()
            val expectedCanonicalDigest = text()
            val expectedContentDigest = text()
            val raw = WorkflowStructuredPayload.of(schema, bytes)
            val validation = WorkflowPayloadValidationReceipt.of(
                validatorId,
                validatorRevision,
                validationSchema,
                canonicalDigest,
                fieldCount,
                authorityDigest,
            )
            require(validation.receiptDigest == validationDigest) {
                "Persisted workflow form validation receipt digest is inconsistent."
            }
            val value = WorkflowStructuredPayload.validated(raw, validation)
            require(value.canonicalPayloadDigest == expectedCanonicalDigest &&
                value.contentDigest == expectedContentDigest
            ) { "Persisted workflow normalized form digest is inconsistent." }
            return value
        }

        private fun access(): WorkflowFormFieldAccessReport {
            val decisions = list {
                val value = WorkflowFormFieldAccessDecision.of(
                    WorkflowFormFieldPath.of(text()),
                    WorkflowFormFieldAccessMode.of(text()),
                    WorkflowFormFieldAccessMode.of(text()),
                )
                require(value.decisionDigest == text()) {
                    "Persisted workflow field decision digest is inconsistent."
                }
                value
            }
            val value = WorkflowFormFieldAccessReport.of(decisions, text())
            require(value.reportDigest == text()) { "Persisted workflow field report digest is inconsistent." }
            return value
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
                "Persisted workflow provider context digest is inconsistent."
            }
            val requestDigest = text()
            val outcome = WorkflowProviderOutcome.of(text())
            val resultDigest = text()
            val failure = nullable { WorkflowProviderFailure.of(text(), bool()) }
            val completedAt = long()
            val expiresAt = long()
            val expectedReceiptDigest = text()
            val value = if (outcome == WorkflowProviderOutcome.SUCCESS) {
                require(failure == null) { "Persisted successful provider receipt contains failure metadata." }
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
            require(value.contextDigest == expectedContextDigest && value.receiptDigest == expectedReceiptDigest) {
                "Persisted workflow provider receipt digest is inconsistent."
            }
            return value
        }

        private fun submission(form: WorkflowFormVersionRef): WorkflowFormSubmissionRef {
            val value = WorkflowFormSubmissionRef.of(
                text(),
                long(),
                form,
                principal(),
                text(),
                integer(),
                text(),
                text(),
                long(),
            )
            require(value.submissionDigest == text()) {
                "Persisted workflow form submission digest is inconsistent."
            }
            return value
        }

        private fun schema(): WorkflowSchemaRef = WorkflowSchemaRef.of(text(), text(), text(), text())
        private fun principal(): WorkflowPrincipalRef = WorkflowPrincipalRef.of(text(), text())

        fun text(): String {
            val size = input.readInt()
            require(size in 0..MAX_STRING_BYTES && size <= input.available()) {
                "Persisted workflow human-input text length is invalid."
            }
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }

        private fun bytes(): ByteArray {
            val size = input.readInt()
            require(size in 0..MAX_PAYLOAD_BYTES && size <= input.available()) {
                "Persisted workflow human-input binary length is invalid."
            }
            return ByteArray(size).also { input.readFully(it) }
        }

        private fun nullableText(): String? = nullable { text() }
        private fun integer(): Int = input.readInt()
        private fun long(): Long = input.readLong()
        private fun bool(): Boolean = input.readBoolean()
        private fun <T> nullable(reader: () -> T): T? = if (input.readBoolean()) reader() else null

        private fun <T> list(reader: Reader.() -> T): List<T> {
            val size = integer()
            require(size in 0..MAX_COLLECTION_ITEMS) {
                "Persisted workflow human-input collection length is invalid."
            }
            return ArrayList<T>(size).also { result -> repeat(size) { result += reader(this) } }
        }

        fun end() {
            require(input.available() == 0) { "Persisted workflow human-input payload contains trailing data." }
        }

        override fun close() = input.close()
    }

    private const val MAGIC = "flowweft-workflow-human-input-jdbc"
    private const val FORMAT_VERSION = 1
    private const val KIND_FORM = "form"
    private const val KIND_COMMENT = "comment"
    private const val KIND_NOTIFICATION = "notification"
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_COLLECTION_ITEMS = 4096
    private const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024
    private const val MAX_ENCODED_BYTES = 8 * 1024 * 1024
}
