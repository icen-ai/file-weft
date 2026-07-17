package ai.icen.fw.governance.persistence.jdbc

import ai.icen.fw.governance.api.GovernanceDeletionStage
import ai.icen.fw.governance.api.GovernanceFailure
import ai.icen.fw.governance.api.GovernanceFailureClass
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetExecutionBinding
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItem
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemKind
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemOperation
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemOperationStatus
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemOutcome
import ai.icen.fw.governance.runtime.GovernanceDeletionTargetManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Bounded canonical restart format for target manifests and per-item operation checkpoints. */
internal object GovernanceTargetJdbcCanonicalCodec {
    private const val MAGIC = 0x46574754 // FWGT
    const val VERSION = 1
    private const val TYPE_MANIFEST = 1
    private const val TYPE_OPERATION = 2
    const val MAX_MEMENTO_BYTES = 4 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 16 * 1024
    private const val MAX_ITEMS = 256

    fun encodeManifest(value: GovernanceDeletionTargetManifest): ByteArray =
        encode(TYPE_MANIFEST) { manifest(value) }

    fun decodeManifest(bytes: ByteArray, expectedManifestDigest: String): GovernanceDeletionTargetManifest {
        requireSha256(expectedManifestDigest, "Governance target JDBC manifest digest is invalid.")
        return decode(bytes, TYPE_MANIFEST) { manifest(expectedManifestDigest) }
    }

    fun encodeOperation(value: GovernanceDeletionTargetItemOperation): ByteArray =
        encode(TYPE_OPERATION) { operation(value) }

    fun decodeOperation(bytes: ByteArray, expectedStateDigest: String): GovernanceDeletionTargetItemOperation {
        requireSha256(expectedStateDigest, "Governance target JDBC operation state digest is invalid.")
        return decode(bytes, TYPE_OPERATION) { operation(expectedStateDigest) }
    }

    private fun <T> encode(type: Int, block: Writer.() -> T): ByteArray {
        val bytes = BoundedByteArrayOutputStream(MAX_MEMENTO_BYTES)
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(type)
            Writer(output).block()
        }
        return bytes.toByteArray().also { encoded ->
            require(encoded.isNotEmpty() && encoded.size <= MAX_MEMENTO_BYTES) {
                "Governance target JDBC memento is too large."
            }
        }
    }

    private fun <T> decode(bytes: ByteArray, type: Int, block: Reader.() -> T): T {
        require(bytes.isNotEmpty() && bytes.size <= MAX_MEMENTO_BYTES) {
            "Governance target JDBC memento size is invalid."
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        try {
            require(input.readInt() == MAGIC && input.readInt() == VERSION && input.readInt() == type) {
                "Governance target JDBC memento header is invalid."
            }
            val value = Reader(input).block()
            require(input.read() == -1) { "Governance target JDBC memento has trailing data." }
            return value
        } catch (failure: EOFException) {
            throw IllegalArgumentException("Governance target JDBC memento is truncated.", failure)
        } catch (failure: IOException) {
            throw IllegalArgumentException("Governance target JDBC memento encoding is invalid.", failure)
        } finally {
            input.close()
        }
    }

    private fun requireSha256(value: String, message: String) {
        require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) { message }
    }

    private class BoundedByteArrayOutputStream(private val maximumBytes: Int) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            require(count < maximumBytes) { "Governance target JDBC memento is too large." }
            super.write(value)
        }

        override fun write(value: ByteArray, offset: Int, length: Int) {
            require(length >= 0 && length <= maximumBytes - count) {
                "Governance target JDBC memento is too large."
            }
            super.write(value, offset, length)
        }
    }

    private class Writer(private val output: DataOutputStream) {
        fun text(value: String) {
            val encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val buffer = encoder.encode(CharBuffer.wrap(value))
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            require(bytes.isNotEmpty() && bytes.size <= MAX_TEXT_BYTES) {
                "Governance target JDBC memento text is invalid."
            }
            output.writeInt(bytes.size)
            output.write(bytes)
        }

        fun integer(value: Int) = output.writeInt(value)
        fun long(value: Long) = output.writeLong(value)
        fun bool(value: Boolean) = output.writeBoolean(value)
        fun nullableText(value: String?) { bool(value != null); if (value != null) text(value) }
        fun nullableLong(value: Long?) { bool(value != null); if (value != null) long(value) }

        fun item(value: GovernanceDeletionTargetItem) {
            integer(value.ordinal)
            text(value.kind.code)
            text(value.itemRef)
            text(value.itemRevision)
            text(value.itemDigest)
            text(value.providerId)
            text(value.providerRevision)
            text(value.itemIdentityDigest)
            text(value.itemBindingDigest)
        }

        fun manifest(value: GovernanceDeletionTargetManifest) {
            text(value.tenantId)
            text(value.planningRequestDigest)
            text(value.planningIdentityDigest)
            text(value.resourceReferenceDigest)
            text(value.assessmentDigest)
            text(value.stage.name)
            text(value.targetRef)
            text(value.targetRevision)
            text(value.targetDigest)
            require(value.items.size <= MAX_ITEMS) { "Governance target JDBC manifest has too many items." }
            integer(value.items.size)
            value.items.forEach(::item)
            long(value.createdAtEpochMilli)
            text(value.preparationDigest)
            text(value.targetBindingDigest)
            text(value.manifestDigest)
        }

        fun failure(value: GovernanceFailure) {
            text(value.classification.code)
            text(value.reasonCode)
            bool(value.retryable)
            bool(value.reconciliationRequired)
            text(value.failureDigest)
        }

        fun nullableFailure(value: GovernanceFailure?) {
            bool(value != null)
            if (value != null) failure(value)
        }

        fun outcome(value: GovernanceDeletionTargetItemOutcome) {
            text(value.operationKeyDigest)
            text(value.sourceOperationStateDigest)
            text(value.status.code)
            text(value.receiptReference)
            text(value.resultDigest)
            nullableFailure(value.failure)
            long(value.observedAtEpochMilli)
            text(value.outcomeDigest)
        }

        fun binding(value: GovernanceDeletionTargetExecutionBinding) {
            text(value.tenantId)
            text(value.planDigest)
            text(value.stepDigest)
            text(value.planningRequestDigest)
            text(value.planningIdentityDigest)
            text(value.stage.name)
            text(value.targetRef)
            text(value.targetRevision)
            text(value.targetDigest)
            text(value.manifestDigest)
            text(value.preparationDigest)
            text(value.bindingDigest)
        }

        fun operation(value: GovernanceDeletionTargetItemOperation) {
            binding(value.binding)
            text(value.itemBindingDigest)
            text(value.providerId)
            text(value.providerRevision)
            text(value.operationReference)
            text(value.executionRequestDigest)
            long(value.providerCallDeadlineEpochMilli)
            text(value.status.code)
            long(value.version)
            long(value.preparedAtEpochMilli)
            nullableLong(value.startedAtEpochMilli)
            bool(value.outcome != null)
            value.outcome?.let(::outcome)
            nullableText(value.reconciliationRequestDigest)
            nullableLong(value.reconciliationRequestedAtEpochMilli)
            nullableLong(value.reconciliationStartedAtEpochMilli)
            nullableLong(value.reconciliationDeadlineEpochMilli)
            long(value.updatedAtEpochMilli)
            text(value.operationKeyDigest)
            text(value.stateDigest)
        }
    }

    private class Reader(private val input: DataInputStream) {
        fun text(): String {
            val size = input.readInt()
            require(size in 1..MAX_TEXT_BYTES) { "Governance target JDBC memento text length is invalid." }
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }

        fun integer(): Int = input.readInt()
        fun long(): Long = input.readLong()
        fun bool(): Boolean = when (val encoded = input.readUnsignedByte()) {
            0 -> false
            1 -> true
            else -> throw IllegalArgumentException(
                "Governance target JDBC memento boolean value $encoded is non-canonical.",
            )
        }
        fun nullableText(): String? = if (bool()) text() else null
        fun nullableLong(): Long? = if (bool()) long() else null

        fun item(): GovernanceDeletionTargetItem {
            val value = GovernanceDeletionTargetItem.of(
                integer(),
                GovernanceDeletionTargetItemKind.of(text()),
                text(),
                text(),
                text(),
                text(),
                text(),
            )
            require(value.itemIdentityDigest == text() && value.itemBindingDigest == text()) {
                "Governance target JDBC item binding digest is invalid."
            }
            return value
        }

        fun manifest(expectedManifestDigest: String): GovernanceDeletionTargetManifest {
            val tenantId = text()
            val planningRequestDigest = text()
            val planningIdentityDigest = text()
            val resourceReferenceDigest = text()
            val assessmentDigest = text()
            val stage = deletionStage(text())
            val targetRef = text()
            val targetRevision = text()
            val targetDigest = text()
            val itemCount = integer()
            require(itemCount in 1..MAX_ITEMS) { "Governance target JDBC manifest item count is invalid." }
            val items = (0 until itemCount).map { item() }
            val createdAtEpochMilli = long()
            val preparationDigest = text()
            val targetBindingDigest = text()
            val encodedManifestDigest = text()
            require(encodedManifestDigest == expectedManifestDigest) {
                "Governance target JDBC row and manifest digest differ."
            }
            return GovernanceDeletionTargetManifest.rehydrate(
                tenantId,
                planningRequestDigest,
                planningIdentityDigest,
                resourceReferenceDigest,
                assessmentDigest,
                stage,
                targetRef,
                targetRevision,
                targetDigest,
                items,
                createdAtEpochMilli,
                preparationDigest,
                targetBindingDigest,
                encodedManifestDigest,
            )
        }

        fun failure(): GovernanceFailure {
            val value = GovernanceFailure.of(GovernanceFailureClass.of(text()), text(), bool(), bool())
            require(value.failureDigest == text()) { "Governance target JDBC failure digest is invalid." }
            return value
        }

        fun outcome(): GovernanceDeletionTargetItemOutcome = GovernanceDeletionTargetItemOutcome.rehydrate(
            text(),
            text(),
            GovernanceDeletionTargetItemOperationStatus.of(text()),
            text(),
            text(),
            if (bool()) failure() else null,
            long(),
            text(),
        )

        fun binding(): GovernanceDeletionTargetExecutionBinding =
            GovernanceDeletionTargetExecutionBinding.rehydrate(
                text(),
                text(),
                text(),
                text(),
                text(),
                deletionStage(text()),
                text(),
                text(),
                text(),
                text(),
                text(),
                text(),
            )

        fun operation(expectedStateDigest: String): GovernanceDeletionTargetItemOperation {
            val binding = binding()
            val itemBindingDigest = text()
            val providerId = text()
            val providerRevision = text()
            val operationReference = text()
            val executionRequestDigest = text()
            val providerCallDeadlineEpochMilli = long()
            val status = GovernanceDeletionTargetItemOperationStatus.of(text())
            val version = long()
            val preparedAtEpochMilli = long()
            val startedAtEpochMilli = nullableLong()
            val outcome = if (bool()) outcome() else null
            val reconciliationRequestDigest = nullableText()
            val reconciliationRequestedAtEpochMilli = nullableLong()
            val reconciliationStartedAtEpochMilli = nullableLong()
            val reconciliationDeadlineEpochMilli = nullableLong()
            val updatedAtEpochMilli = long()
            val operationKeyDigest = text()
            val encodedStateDigest = text()
            require(encodedStateDigest == expectedStateDigest) {
                "Governance target JDBC row and operation state digest differ."
            }
            return GovernanceDeletionTargetItemOperation.rehydrate(
                binding,
                itemBindingDigest,
                providerId,
                providerRevision,
                operationReference,
                executionRequestDigest,
                providerCallDeadlineEpochMilli,
                status,
                version,
                preparedAtEpochMilli,
                startedAtEpochMilli,
                outcome,
                reconciliationRequestDigest,
                reconciliationRequestedAtEpochMilli,
                reconciliationStartedAtEpochMilli,
                reconciliationDeadlineEpochMilli,
                updatedAtEpochMilli,
                operationKeyDigest,
                encodedStateDigest,
            )
        }

        private fun deletionStage(value: String): GovernanceDeletionStage =
            GovernanceDeletionStage.values().firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("Governance target JDBC deletion stage is unsupported.")
    }
}
