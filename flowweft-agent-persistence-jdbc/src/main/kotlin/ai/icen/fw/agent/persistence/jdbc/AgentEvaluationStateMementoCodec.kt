package ai.icen.fw.agent.persistence.jdbc

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentEvaluationCase
import ai.icen.fw.agent.api.AgentEvaluationCitationExpectation
import ai.icen.fw.agent.api.AgentEvaluationDiagnostic
import ai.icen.fw.agent.api.AgentEvaluationDiagnosticReason
import ai.icen.fw.agent.api.AgentEvaluationDiagnosticStatus
import ai.icen.fw.agent.api.AgentEvaluationExpectedOutcome
import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot
import ai.icen.fw.agent.api.AgentEvaluationRefusalExpectation
import ai.icen.fw.agent.api.AgentEvaluationRetrievalExpectation
import ai.icen.fw.agent.api.AgentEvaluationSuite
import ai.icen.fw.agent.api.AgentEvaluationToolDecision
import ai.icen.fw.agent.api.AgentEvaluationToolExpectation
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.api.ToolId
import ai.icen.fw.agent.runtime.AgentEvaluationCaseEvidence
import ai.icen.fw.agent.runtime.AgentEvaluationIdempotencyScope
import ai.icen.fw.agent.runtime.AgentEvaluationLease
import ai.icen.fw.agent.runtime.AgentEvaluationRunState
import ai.icen.fw.agent.runtime.AgentEvaluationRunStatus
import ai.icen.fw.core.id.Identifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Immutable, digest-bound JDBC memento. [payload] returns a defensive copy. */
class AgentEvaluationStateMemento internal constructor(
    val formatVersion: Int,
    digest: String,
    payload: ByteArray,
) {
    val digest: String = requireMementoDigest(digest, "Agent evaluation memento digest is invalid.")
    private val bytes = payload.copyOf()

    fun payload(): ByteArray = bytes.copyOf()

    override fun toString(): String =
        "AgentEvaluationStateMemento(formatVersion=$formatVersion, payload=<redacted>)"
}

/**
 * Explicit, bounded binary codec. It never uses Java serialization, reflection, class names,
 * Jackson polymorphism, fixture bytes or evaluator output. Unknown schema, version, enum, digest,
 * invalid UTF-8, truncation and trailing bytes all fail closed.
 */
class AgentEvaluationStateMementoCodec {
    fun encode(state: AgentEvaluationRunState): AgentEvaluationStateMemento {
        val writer = Writer()
        writer.text(state.evaluationId.value)
        writer.text(state.requestId.value)
        writer.idempotencyScope(state.idempotencyScope)
        writer.text(state.requestBindingDigest)
        writer.suite(state.suite)
        writer.providerSnapshot(state.providerSnapshot)
        writer.text(state.status.name)
        writer.long(state.stateVersion)
        writer.integer(state.attempt)
        writer.list(state.evidence, writer::caseEvidence)
        writer.nullable(state.lease, writer::lease)
        writer.nullable(state.diagnostic, writer::diagnostic)
        writer.nullableText(state.cancellationReason)
        writer.long(state.createdAt)
        writer.long(state.updatedAt)
        writer.long(state.deadlineAt)
        writer.integer(state.maximumAttempts)
        return writer.finish()
    }

    fun decode(memento: AgentEvaluationStateMemento): AgentEvaluationRunState {
        require(memento.formatVersion == FORMAT_VERSION) {
            "Unsupported Agent evaluation memento wrapper version '${memento.formatVersion}'."
        }
        return decode(memento.payload(), memento.digest)
    }

    fun decode(payload: ByteArray, expectedDigest: String): AgentEvaluationRunState {
        val reader = Reader(payload, expectedDigest)
        try {
            val evaluationId = Identifier(reader.text())
            val requestId = Identifier(reader.text())
            val idempotencyScope = reader.idempotencyScope()
            val requestBindingDigest = reader.text()
            val suite = reader.suite()
            val providerSnapshot = reader.providerSnapshot()
            val status = runStatus(reader.text())
            val stateVersion = reader.long()
            val attempt = reader.integer()
            val evidence = reader.list(reader::caseEvidence)
            val lease = reader.nullable(reader::lease)
            val diagnostic = reader.nullable(reader::diagnostic)
            val cancellationReason = reader.nullableText()
            val createdAt = reader.long()
            val updatedAt = reader.long()
            val deadlineAt = reader.long()
            val maximumAttempts = reader.integer()
            reader.requireFinished()
            val restoredRequestDigest = StableMementoDigest("flowweft.agent.evaluation.request.v1")
                .add(idempotencyScope.scopeDigest)
                .add(providerSnapshot.providerId.value)
                .add(createdAt)
                .add(deadlineAt)
                .add(maximumAttempts)
                .finish()
            require(requestBindingDigest == restoredRequestDigest) {
                "Agent evaluation memento request binding digest does not match its fields."
            }
            return AgentEvaluationRunState.restore(
                evaluationId,
                requestId,
                idempotencyScope,
                requestBindingDigest,
                suite,
                providerSnapshot,
                status,
                stateVersion,
                attempt,
                evidence,
                lease,
                diagnostic,
                cancellationReason,
                createdAt,
                updatedAt,
                deadlineAt,
                maximumAttempts,
            )
        } finally {
            reader.close()
        }
    }

    companion object {
        const val SCHEMA: String = "agent-evaluation-run"
        const val FORMAT_VERSION: Int = 1
        const val MAX_PAYLOAD_BYTES: Int = 4 * 1024 * 1024
        private const val MAGIC: Int = 0x46574556
        private const val CHECKSUM_BYTES: Int = 32
        private const val MAX_TEXT_BYTES: Int = 64 * 1024
        private const val MAX_COLLECTION_ITEMS: Int = 1_024
    }

    private class Writer {
        private val bytes = ByteArrayOutputStream()
        private val output = DataOutputStream(bytes)

        init {
            output.writeInt(MAGIC)
            output.writeInt(FORMAT_VERSION)
            text(SCHEMA)
        }

        fun finish(): AgentEvaluationStateMemento {
            output.flush()
            val content = bytes.toByteArray()
            require(content.size <= MAX_PAYLOAD_BYTES - CHECKSUM_BYTES) {
                "Agent evaluation memento exceeds the payload limit."
            }
            val payload = content + sha256(content)
            return AgentEvaluationStateMemento(FORMAT_VERSION, sha256Hex(payload), payload)
        }

        fun idempotencyScope(scope: AgentEvaluationIdempotencyScope) {
            text(scope.tenantId.value)
            text(scope.principalId.value)
            text(scope.principalType)
            text(scope.authorizationRevision)
            text(scope.suiteId.value)
            text(scope.suiteDigest)
            text(scope.providerSnapshotDigest)
            text(scope.idempotencyKeyDigest)
        }

        fun suite(suite: AgentEvaluationSuite) {
            text(suite.suiteId.value)
            text(suite.name)
            text(suite.version)
            long(suite.createdAt)
            list(suite.cases, ::evaluationCase)
        }

        fun evaluationCase(case: AgentEvaluationCase) {
            text(case.caseId.value)
            text(case.fixtureId.value)
            text(case.capabilityId.value)
            text(case.inputDigest)
            expectedOutcome(case.expected)
            list(case.tags.sorted(), ::text)
        }

        fun expectedOutcome(expected: AgentEvaluationExpectedOutcome) {
            nullable(expected.retrieval) { retrieval ->
                list(retrieval.requiredEvidenceIds.map { it.value }.sorted(), ::text)
                integer(retrieval.minimumRelevantEvidence)
                integer(retrieval.maximumMissingRequiredEvidence)
                bool(retrieval.requireSecurityFilterReceipt)
            }
            nullable(expected.citations) { citations ->
                list(citations.requiredEvidenceIds.map { it.value }.sorted(), ::text)
                integer(citations.minimumValidCitations)
                integer(citations.maximumUnsupportedClaims)
            }
            nullable(expected.tool) { tool ->
                text(tool.decision.name)
                nullableText(tool.providerId?.value)
                nullableText(tool.toolId?.value)
                nullableText(tool.argumentsDigest)
            }
            text(expected.refusal.name)
            nullableLong(expected.maximumCostMicros)
            nullableLong(expected.maximumLatencyMillis)
        }

        fun providerSnapshot(snapshot: AgentEvaluationProviderSnapshot) {
            text(snapshot.providerId.value)
            text(snapshot.implementationVersion)
            list(snapshot.capabilities.map { it.value }.sorted(), ::text)
            text(snapshot.descriptorDigest)
            long(snapshot.capturedAt)
            long(snapshot.expiresAt)
        }

        fun caseEvidence(evidence: AgentEvaluationCaseEvidence) {
            text(evidence.caseId.value)
            text(evidence.caseDigest)
            bool(evidence.passed)
            list(evidence.observationDigests.sorted(), ::text)
            diagnostic(evidence.diagnostic)
            long(evidence.completedAt)
        }

        fun lease(lease: AgentEvaluationLease) {
            text(lease.leaseId.value)
            text(lease.ownerId.value)
            long(lease.fencingToken)
            long(lease.acquiredAt)
            long(lease.expiresAt)
        }

        fun diagnostic(diagnostic: AgentEvaluationDiagnostic) {
            text(diagnostic.status.name)
            nullableText(diagnostic.reason?.value)
            nullableText(diagnostic.providerId?.value)
            nullableText(diagnostic.capabilityId?.value)
            nullableText(diagnostic.snapshotDigest)
            long(diagnostic.observedAt)
        }

        fun text(value: String) {
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            require(encoded.isNotEmpty() && encoded.size <= MAX_TEXT_BYTES) {
                "Agent evaluation memento text length is invalid."
            }
            requireRoom(4 + encoded.size)
            output.writeInt(encoded.size)
            output.write(encoded)
        }

        fun nullableText(value: String?) = nullable(value, ::text)

        fun integer(value: Int) {
            requireRoom(4)
            output.writeInt(value)
        }

        fun long(value: Long) {
            requireRoom(8)
            output.writeLong(value)
        }

        fun bool(value: Boolean) {
            requireRoom(1)
            output.writeByte(if (value) 1 else 0)
        }

        fun nullableLong(value: Long?) = nullable(value, ::long)

        fun <T> nullable(value: T?, write: (T) -> Unit) {
            bool(value != null)
            if (value != null) write(value)
        }

        fun <T> list(values: Collection<T>, write: (T) -> Unit) {
            require(values.size <= MAX_COLLECTION_ITEMS) {
                "Agent evaluation memento collection exceeds the limit."
            }
            integer(values.size)
            values.forEach(write)
        }

        private fun requireRoom(additionalBytes: Int) {
            require(bytes.size() <= MAX_PAYLOAD_BYTES - CHECKSUM_BYTES - additionalBytes) {
                "Agent evaluation memento exceeds the payload limit."
            }
        }
    }

    private class Reader(payload: ByteArray, expectedDigest: String) : Closeable {
        private val input: DataInputStream

        init {
            val digest = requireMementoDigest(expectedDigest, "Agent evaluation persisted digest is invalid.")
            require(payload.size in (CHECKSUM_BYTES + 1)..MAX_PAYLOAD_BYTES) {
                "Agent evaluation memento payload length is invalid."
            }
            require(sha256Hex(payload) == digest) { "Agent evaluation memento database digest does not match." }
            val content = payload.copyOfRange(0, payload.size - CHECKSUM_BYTES)
            val checksum = payload.copyOfRange(payload.size - CHECKSUM_BYTES, payload.size)
            require(MessageDigest.isEqual(sha256(content), checksum)) {
                "Agent evaluation memento internal checksum does not match."
            }
            input = DataInputStream(ByteArrayInputStream(content))
            require(input.readInt() == MAGIC) { "Agent evaluation memento schema magic is unsupported." }
            val version = input.readInt()
            require(version == FORMAT_VERSION) {
                "Unsupported Agent evaluation memento format version '$version'."
            }
            require(text() == SCHEMA) { "Agent evaluation memento schema is unsupported." }
        }

        fun idempotencyScope(): AgentEvaluationIdempotencyScope = AgentEvaluationIdempotencyScope.restore(
            Identifier(text()),
            Identifier(text()),
            text(),
            text(),
            Identifier(text()),
            text(),
            text(),
            text(),
        )

        fun suite(): AgentEvaluationSuite {
            val suiteId = Identifier(text())
            val name = text()
            val version = text()
            val createdAt = long()
            val cases = list(::evaluationCase)
            return AgentEvaluationSuite(suiteId, name, version, cases, createdAt)
        }

        private fun evaluationCase(): AgentEvaluationCase = AgentEvaluationCase(
            Identifier(text()),
            Identifier(text()),
            AgentCapabilityId(text()),
            text(),
            expectedOutcome(),
            list(::text),
        )

        private fun expectedOutcome(): AgentEvaluationExpectedOutcome {
            val retrieval = nullable {
                AgentEvaluationRetrievalExpectation(
                    list(::text).map(::Identifier),
                    integer(),
                    integer(),
                    bool(),
                )
            }
            val citations = nullable {
                AgentEvaluationCitationExpectation(
                    list(::text).map(::Identifier),
                    integer(),
                    integer(),
                )
            }
            val tool = nullable {
                AgentEvaluationToolExpectation(
                    toolDecision(text()),
                    nullableText()?.let(::ProviderId),
                    nullableText()?.let(::ToolId),
                    nullableText(),
                )
            }
            return AgentEvaluationExpectedOutcome(
                retrieval,
                citations,
                tool,
                refusalExpectation(text()),
                nullableLong(),
                nullableLong(),
            )
        }

        fun providerSnapshot(): AgentEvaluationProviderSnapshot = AgentEvaluationProviderSnapshot(
            ProviderId(text()),
            text(),
            list(::text).map(::AgentCapabilityId),
            text(),
            long(),
            long(),
        )

        fun caseEvidence(): AgentEvaluationCaseEvidence = AgentEvaluationCaseEvidence(
            Identifier(text()),
            text(),
            bool(),
            list(::text),
            diagnostic(),
            long(),
        )

        fun lease(): AgentEvaluationLease = AgentEvaluationLease(
            Identifier(text()),
            ProviderId(text()),
            long(),
            long(),
            long(),
        )

        fun diagnostic(): AgentEvaluationDiagnostic = AgentEvaluationDiagnostic(
            diagnosticStatus(text()),
            nullableText()?.let(::AgentEvaluationDiagnosticReason),
            nullableText()?.let(::ProviderId),
            nullableText()?.let(::AgentCapabilityId),
            nullableText(),
            long(),
        )

        fun text(): String {
            val length = input.readInt()
            require(length in 1..MAX_TEXT_BYTES && length <= input.available()) {
                "Agent evaluation memento text length is invalid."
            }
            val bytes = ByteArray(length)
            input.readFully(bytes)
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(ByteBuffer.wrap(bytes)).toString()
        }

        fun nullableText(): String? = nullable(::text)
        fun integer(): Int = input.readInt()
        fun long(): Long = input.readLong()

        fun bool(): Boolean = when (val value = input.readUnsignedByte()) {
            0 -> false
            1 -> true
            else -> throw IllegalArgumentException("Agent evaluation memento boolean '$value' is invalid.")
        }

        fun nullableLong(): Long? = nullable(::long)

        fun <T> nullable(read: () -> T): T? = if (bool()) read() else null

        fun <T> list(read: () -> T): List<T> {
            val count = integer()
            require(count in 0..MAX_COLLECTION_ITEMS) {
                "Agent evaluation memento collection length is invalid."
            }
            return (0 until count).map { read() }
        }

        fun requireFinished() {
            require(input.available() == 0) { "Agent evaluation memento contains trailing bytes." }
        }

        override fun close() = input.close()
    }
}

private fun runStatus(code: String): AgentEvaluationRunStatus = when (code) {
    "QUEUED" -> AgentEvaluationRunStatus.QUEUED
    "RUNNING" -> AgentEvaluationRunStatus.RUNNING
    "COMPLETED" -> AgentEvaluationRunStatus.COMPLETED
    "FAILED" -> AgentEvaluationRunStatus.FAILED
    "CANCELLED" -> AgentEvaluationRunStatus.CANCELLED
    "EXPIRED" -> AgentEvaluationRunStatus.EXPIRED
    else -> throw IllegalStateException("Persisted Agent evaluation status '$code' is unsupported.")
}

private fun diagnosticStatus(code: String): AgentEvaluationDiagnosticStatus = when (code) {
    "READY" -> AgentEvaluationDiagnosticStatus.READY
    "UNAVAILABLE" -> AgentEvaluationDiagnosticStatus.UNAVAILABLE
    "DEGRADED" -> AgentEvaluationDiagnosticStatus.DEGRADED
    "DRIFTED" -> AgentEvaluationDiagnosticStatus.DRIFTED
    "EXPIRED" -> AgentEvaluationDiagnosticStatus.EXPIRED
    "BUDGET_EXCEEDED" -> AgentEvaluationDiagnosticStatus.BUDGET_EXCEEDED
    "FAILED" -> AgentEvaluationDiagnosticStatus.FAILED
    else -> throw IllegalStateException("Persisted Agent evaluation diagnostic '$code' is unsupported.")
}

private fun toolDecision(code: String): AgentEvaluationToolDecision = when (code) {
    "INVOKE" -> AgentEvaluationToolDecision.INVOKE
    "SKIP" -> AgentEvaluationToolDecision.SKIP
    "REQUIRE_APPROVAL" -> AgentEvaluationToolDecision.REQUIRE_APPROVAL
    else -> throw IllegalStateException("Persisted Agent evaluation tool decision '$code' is unsupported.")
}

private fun refusalExpectation(code: String): AgentEvaluationRefusalExpectation = when (code) {
    "MUST_ANSWER" -> AgentEvaluationRefusalExpectation.MUST_ANSWER
    "MUST_REFUSE" -> AgentEvaluationRefusalExpectation.MUST_REFUSE
    "NOT_APPLICABLE" -> AgentEvaluationRefusalExpectation.NOT_APPLICABLE
    else -> throw IllegalStateException("Persisted Agent evaluation refusal expectation '$code' is unsupported.")
}

private fun requireMementoDigest(value: String, message: String): String {
    require(value.length == 64 && value.all { character -> character in '0'..'9' || character in 'a'..'f' }) {
        message
    }
    return value
}

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

private fun sha256Hex(bytes: ByteArray): String = sha256(bytes).joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}

private class StableMementoDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(domain)
    }

    fun add(value: String): StableMementoDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
        return this
    }

    fun add(value: Long): StableMementoDigest = add(value.toString())
    fun add(value: Int): StableMementoDigest = add(value.toString())

    fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
