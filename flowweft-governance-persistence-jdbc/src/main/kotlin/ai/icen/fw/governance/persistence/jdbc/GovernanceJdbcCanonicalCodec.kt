package ai.icen.fw.governance.persistence.jdbc

import ai.icen.fw.governance.api.GovernanceAuthorizationSnapshot
import ai.icen.fw.governance.api.GovernanceCallContext
import ai.icen.fw.governance.api.GovernanceDeletionExecutionRequest
import ai.icen.fw.governance.api.GovernanceDeletionPlan
import ai.icen.fw.governance.api.GovernanceDeletionStage
import ai.icen.fw.governance.api.GovernanceDeletionStep
import ai.icen.fw.governance.api.GovernanceDeletionStepReceipt
import ai.icen.fw.governance.api.GovernanceDeletionStepStatus
import ai.icen.fw.governance.api.GovernanceEffectiveClock
import ai.icen.fw.governance.api.GovernanceFailure
import ai.icen.fw.governance.api.GovernanceFailureClass
import ai.icen.fw.governance.api.GovernanceLegalHoldReleaseEvidence
import ai.icen.fw.governance.api.GovernanceLegalHoldResolution
import ai.icen.fw.governance.api.GovernanceLegalHoldResolutionStatus
import ai.icen.fw.governance.api.GovernanceLegalHoldScope
import ai.icen.fw.governance.api.GovernanceLegalHoldScopeType
import ai.icen.fw.governance.api.GovernanceLegalHoldSnapshot
import ai.icen.fw.governance.api.GovernanceLegalHoldStatus
import ai.icen.fw.governance.api.GovernancePrincipalRef
import ai.icen.fw.governance.api.GovernancePurpose
import ai.icen.fw.governance.api.GovernanceResourceRef
import ai.icen.fw.governance.api.GovernanceRetentionAssessment
import ai.icen.fw.governance.api.GovernanceRetentionOutcome
import ai.icen.fw.governance.api.GovernanceRetentionPolicyMode
import ai.icen.fw.governance.api.GovernanceRetentionPolicySnapshot
import ai.icen.fw.governance.api.GovernanceRetentionReason
import ai.icen.fw.governance.api.GovernanceVersionFence
import ai.icen.fw.governance.runtime.GovernanceDeletionDispatch
import ai.icen.fw.governance.runtime.GovernanceDeletionDispatchPhase
import ai.icen.fw.governance.runtime.GovernanceDeletionRun
import ai.icen.fw.governance.runtime.GovernanceDeletionRunStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Bounded, versioned canonical restart format. It deliberately avoids Java serialization so
 * database rows never depend on Kotlin/JVM implementation metadata or executable gadget graphs.
 */
internal object GovernanceJdbcCanonicalCodec {
    private const val MAGIC = 0x46574750 // FWGP
    const val VERSION = 1
    private const val TYPE_RUN = 1
    internal const val MAX_MEMENTO_BYTES = 4 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 16 * 1024
    private const val MAX_ITEMS = 512

    fun encodeRun(value: GovernanceDeletionRun): ByteArray = encode(TYPE_RUN) { run(value) }

    fun decodeRun(bytes: ByteArray, expectedStateDigest: String): GovernanceDeletionRun =
        decode(bytes, TYPE_RUN) { run(expectedStateDigest) }

    private fun <T> encode(type: Int, block: Writer.() -> T): ByteArray {
        val bytes = BoundedByteArrayOutputStream(MAX_MEMENTO_BYTES)
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeInt(type)
            Writer(output).block()
        }
        return bytes.toByteArray().also {
            require(it.isNotEmpty() && it.size <= MAX_MEMENTO_BYTES) { "Governance JDBC memento is too large." }
        }
    }

    private class BoundedByteArrayOutputStream(private val maximumBytes: Int) : ByteArrayOutputStream() {
        override fun write(value: Int) {
            require(count < maximumBytes) { "Governance JDBC memento is too large." }
            super.write(value)
        }

        override fun write(value: ByteArray, offset: Int, length: Int) {
            require(length >= 0 && length <= maximumBytes - count) { "Governance JDBC memento is too large." }
            super.write(value, offset, length)
        }
    }

    private fun <T> decode(bytes: ByteArray, type: Int, block: Reader.() -> T): T {
        require(bytes.isNotEmpty() && bytes.size <= MAX_MEMENTO_BYTES) {
            "Governance JDBC memento size is invalid."
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        try {
            require(input.readInt() == MAGIC && input.readInt() == VERSION && input.readInt() == type) {
                "Governance JDBC memento header is invalid."
            }
            val value = Reader(input).block()
            require(input.read() == -1) { "Governance JDBC memento has trailing data." }
            return value
        } catch (failure: EOFException) {
            throw IllegalArgumentException("Governance JDBC memento is truncated.", failure)
        } finally {
            input.close()
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
                "Governance JDBC memento text is invalid."
            }
            output.writeInt(bytes.size)
            output.write(bytes)
        }

        fun long(value: Long) = output.writeLong(value)
        fun integer(value: Int) = output.writeInt(value)
        fun bool(value: Boolean) = output.writeBoolean(value)
        fun nullableText(value: String?) { bool(value != null); if (value != null) text(value) }
        fun nullableLong(value: Long?) { bool(value != null); if (value != null) long(value) }

        fun <T> collection(values: Collection<T>, write: Writer.(T) -> Unit) {
            require(values.size <= MAX_ITEMS) { "Governance JDBC memento collection is too large." }
            integer(values.size)
            values.forEach { write(it) }
        }

        fun principal(value: GovernancePrincipalRef) {
            text(value.type)
            text(value.id)
        }

        fun resource(value: GovernanceResourceRef) {
            text(value.type)
            text(value.id)
            text(value.revision)
            text(value.digest)
            text(value.referenceDigest)
        }

        fun authorization(value: GovernanceAuthorizationSnapshot) {
            text(value.authorizationId)
            text(value.tenantId)
            principal(value.principal)
            text(value.purpose.code)
            resource(value.resource)
            text(value.authorityId)
            text(value.authorityRevision)
            text(value.authorizationRevision)
            text(value.decisionDigest)
            long(value.issuedAtEpochMilli)
            long(value.expiresAtEpochMilli)
            text(value.snapshotDigest)
        }

        fun context(value: GovernanceCallContext) {
            text(value.requestId)
            text(value.tenantId)
            principal(value.principal)
            text(value.purpose.code)
            authorization(value.authorization)
            text(value.idempotencyKey)
            long(value.requestedAtEpochMilli)
            long(value.deadlineEpochMilli)
            text(value.contextDigest)
        }

        fun fence(value: GovernanceVersionFence) {
            resource(value.resource)
            long(value.expectedGovernanceVersion)
            text(value.fenceDigest)
        }

        fun clock(value: GovernanceEffectiveClock) {
            text(value.clockId)
            text(value.authorityId)
            text(value.authorityRevision)
            long(value.observedAtEpochMilli)
            long(value.effectiveAtEpochMilli)
            long(value.expiresAtEpochMilli)
            text(value.clockDigest)
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

        fun holdScope(value: GovernanceLegalHoldScope) {
            text(value.tenantId)
            text(value.type.code)
            text(value.scopeRef)
            text(value.revision)
            text(value.digest)
            text(value.scopeDigest)
        }

        fun release(value: GovernanceLegalHoldReleaseEvidence) {
            text(value.releaseId)
            text(value.holdId)
            principal(value.releasedBy)
            text(value.authorizationRevision)
            text(value.decisionDigest)
            text(value.reasonCode)
            long(value.releasedAtEpochMilli)
            text(value.evidenceDigest)
        }

        fun hold(value: GovernanceLegalHoldSnapshot) {
            text(value.holdId)
            text(value.tenantId)
            holdScope(value.scope)
            integer(value.priority)
            text(value.revision)
            text(value.digest)
            text(value.status.code)
            long(value.appliedAtEpochMilli)
            bool(value.releaseEvidence != null)
            value.releaseEvidence?.let { release(it) }
            text(value.snapshotDigest)
        }

        fun holdResolution(value: GovernanceLegalHoldResolution) {
            resource(value.resource)
            text(value.tenantId)
            text(value.authorityId)
            text(value.authorityRevision)
            clock(value.clock)
            collection(value.holds) { hold(it) }
            bool(value.complete)
            text(value.status.code)
            nullableFailure(value.failure)
            long(value.expiresAtEpochMilli)
            text(value.resolutionDigest)
        }

        fun policy(value: GovernanceRetentionPolicySnapshot) {
            text(value.tenantId)
            resource(value.resource)
            text(value.policyId)
            text(value.version)
            text(value.policyDigest)
            text(value.mode.code)
            long(value.effectiveFromEpochMilli)
            long(value.capturedAtEpochMilli)
            long(value.expiresAtEpochMilli)
            nullableLong(value.retainUntilEpochMilli)
            text(value.snapshotDigest)
        }

        fun assessment(value: GovernanceRetentionAssessment) {
            text(value.tenantId)
            resource(value.resource)
            fence(value.fence)
            policy(value.policy)
            holdResolution(value.legalHolds)
            clock(value.clock)
            text(value.requestDigest)
            text(value.outcome.code)
            text(value.reason.code)
            text(value.assessmentDigest)
        }

        fun step(value: GovernanceDeletionStep) {
            text(value.stepId)
            integer(value.sequence)
            text(value.stage.name)
            text(value.targetRef)
            text(value.targetRevision)
            text(value.targetDigest)
            text(value.idempotencyKey)
            text(value.stepDigest)
        }

        fun plan(value: GovernanceDeletionPlan) {
            text(value.planId)
            context(value.context)
            fence(value.fence)
            assessment(value.assessment)
            collection(value.steps) { step(it) }
            bool(value.dryRun)
            long(value.createdAtEpochMilli)
            long(value.expiresAtEpochMilli)
            text(value.planDigest)
        }

        fun receipt(value: GovernanceDeletionStepReceipt) {
            text(value.planDigest)
            text(value.stepDigest)
            text(value.executionRequestDigest)
            integer(value.attempt)
            text(value.providerId)
            text(value.providerRevision)
            text(value.status.code)
            nullableText(value.receiptReference)
            text(value.resultDigest)
            nullableFailure(value.failure)
            long(value.observedAtEpochMilli)
            nullableText(value.reconciliationRequestDigest)
            text(value.receiptDigest)
        }

        fun nullableReceipt(value: GovernanceDeletionStepReceipt?) {
            bool(value != null)
            if (value != null) receipt(value)
        }

        fun executionRequest(value: GovernanceDeletionExecutionRequest) {
            context(value.context)
            text(value.step.stepDigest)
            assessment(value.currentAssessment)
            integer(value.attempt)
            nullableReceipt(value.previousAttempt)
            collection(value.previousReceipts) { receipt(it) }
            long(value.expectedPlanVersion)
            text(value.requestDigest)
        }

        fun dispatch(value: GovernanceDeletionDispatch) {
            executionRequest(value.request)
            text(value.providerId)
            text(value.providerRevision)
            text(value.operationReference)
            text(value.phase.code)
            long(value.preparedAtEpochMilli)
            nullableLong(value.startedAtEpochMilli)
            text(value.dispatchDigest)
        }

        fun nullableDispatch(value: GovernanceDeletionDispatch?) {
            bool(value != null)
            if (value != null) dispatch(value)
        }

        fun run(value: GovernanceDeletionRun) {
            plan(value.plan)
            text(value.commandDigest)
            text(value.idempotencyKey)
            text(value.status.code)
            collection(value.successfulReceipts) { receipt(it) }
            nullableReceipt(value.pendingReceipt)
            nullableDispatch(value.dispatch)
            nullableFailure(value.failure)
            nullableLong(value.nextActionAtEpochMilli)
            long(value.version)
            long(value.updatedAtEpochMilli)
            text(value.stateDigest)
        }
    }

    private class Reader(private val input: DataInputStream) {
        fun text(): String {
            val size = input.readInt()
            require(size in 1..MAX_TEXT_BYTES) { "Governance JDBC memento text length is invalid." }
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }

        fun long(): Long = input.readLong()
        fun integer(): Int = input.readInt()
        fun bool(): Boolean = when (val encoded = input.readUnsignedByte()) {
            0 -> false
            1 -> true
            else -> throw IllegalArgumentException(
                "Governance JDBC memento boolean value $encoded is non-canonical.",
            )
        }
        fun nullableText(): String? = if (bool()) text() else null
        fun nullableLong(): Long? = if (bool()) long() else null

        fun <T> collection(read: Reader.() -> T): List<T> {
            val size = integer()
            require(size in 0..MAX_ITEMS) { "Governance JDBC memento collection length is invalid." }
            return (0 until size).map { read() }
        }

        fun principal(): GovernancePrincipalRef = GovernancePrincipalRef.of(text(), text())

        fun resource(): GovernanceResourceRef {
            val value = GovernanceResourceRef.of(text(), text(), text(), text())
            require(value.referenceDigest == text()) { "Governance JDBC resource digest is invalid." }
            return value
        }

        fun authorization(): GovernanceAuthorizationSnapshot {
            val value = GovernanceAuthorizationSnapshot.of(
                text(),
                text(),
                principal(),
                GovernancePurpose.of(text()),
                resource(),
                text(),
                text(),
                text(),
                text(),
                long(),
                long(),
            )
            require(value.snapshotDigest == text()) { "Governance JDBC authorization digest is invalid." }
            return value
        }

        fun context(): GovernanceCallContext {
            val value = GovernanceCallContext.of(
                text(),
                text(),
                principal(),
                GovernancePurpose.of(text()),
                authorization(),
                text(),
                long(),
                long(),
            )
            require(value.contextDigest == text()) { "Governance JDBC context digest is invalid." }
            return value
        }

        fun fence(): GovernanceVersionFence {
            val value = GovernanceVersionFence.of(resource(), long())
            require(value.fenceDigest == text()) { "Governance JDBC fence digest is invalid." }
            return value
        }

        fun clock(): GovernanceEffectiveClock {
            val value = GovernanceEffectiveClock.of(text(), text(), text(), long(), long(), long())
            require(value.clockDigest == text()) { "Governance JDBC clock digest is invalid." }
            return value
        }

        fun failure(): GovernanceFailure {
            val value = GovernanceFailure.of(GovernanceFailureClass.of(text()), text(), bool(), bool())
            require(value.failureDigest == text()) { "Governance JDBC failure digest is invalid." }
            return value
        }

        fun nullableFailure(): GovernanceFailure? = if (bool()) failure() else null

        fun holdScope(): GovernanceLegalHoldScope {
            val value = GovernanceLegalHoldScope.of(
                text(), GovernanceLegalHoldScopeType.of(text()), text(), text(), text(),
            )
            require(value.scopeDigest == text()) { "Governance JDBC legal-hold scope digest is invalid." }
            return value
        }

        fun release(): GovernanceLegalHoldReleaseEvidence {
            val value = GovernanceLegalHoldReleaseEvidence.of(
                text(), text(), principal(), text(), text(), text(), long(),
            )
            require(value.evidenceDigest == text()) { "Governance JDBC legal-hold release digest is invalid." }
            return value
        }

        fun hold(): GovernanceLegalHoldSnapshot {
            val holdId = text()
            val tenantId = text()
            val scope = holdScope()
            val priority = integer()
            val revision = text()
            val digest = text()
            val status = text()
            val appliedAt = long()
            val released = if (bool()) release() else null
            val value = when (status) {
                GovernanceLegalHoldStatus.ACTIVE.code -> {
                    require(released == null) { "Active governance legal hold cannot carry release evidence." }
                    GovernanceLegalHoldSnapshot.active(
                        holdId, tenantId, scope, priority, revision, digest, appliedAt,
                    )
                }
                GovernanceLegalHoldStatus.RELEASED.code -> GovernanceLegalHoldSnapshot.released(
                    holdId, tenantId, scope, priority, revision, digest, appliedAt, requireNotNull(released),
                )
                else -> throw IllegalArgumentException("Governance JDBC legal-hold status is unsupported.")
            }
            require(value.snapshotDigest == text()) { "Governance JDBC legal-hold digest is invalid." }
            return value
        }

        fun holdResolution(): GovernanceLegalHoldResolution {
            val resource = resource()
            val tenantId = text()
            val authorityId = text()
            val authorityRevision = text()
            val clock = clock()
            val holds = collection { hold() }
            val canonicalHolds = holds.sortedWith(
                compareByDescending<GovernanceLegalHoldSnapshot> { it.priority }.thenBy { it.holdId },
            )
            require(holds.map { it.snapshotDigest } == canonicalHolds.map { it.snapshotDigest }) {
                "Governance JDBC legal-hold resolution order is not canonical."
            }
            val complete = bool()
            val status = text()
            val failure = nullableFailure()
            val expiresAt = long()
            val value = when (status) {
                GovernanceLegalHoldResolutionStatus.HELD.code -> GovernanceLegalHoldResolution.held(
                    resource, tenantId, authorityId, authorityRevision, clock, holds, complete, expiresAt,
                )
                GovernanceLegalHoldResolutionStatus.CLEAR.code -> GovernanceLegalHoldResolution.clear(
                    resource, tenantId, authorityId, authorityRevision, clock, holds, expiresAt,
                ).also { require(complete) }
                GovernanceLegalHoldResolutionStatus.UNKNOWN.code -> GovernanceLegalHoldResolution.unknown(
                    resource, tenantId, authorityId, authorityRevision, clock, requireNotNull(failure), expiresAt,
                ).also { require(!complete && holds.isEmpty()) }
                else -> throw IllegalArgumentException("Governance JDBC legal-hold resolution is unsupported.")
            }
            require(value.failure?.failureDigest == failure?.failureDigest && value.resolutionDigest == text()) {
                "Governance JDBC legal-hold resolution digest is invalid."
            }
            return value
        }

        fun policy(): GovernanceRetentionPolicySnapshot {
            val value = GovernanceRetentionPolicySnapshot.of(
                text(),
                resource(),
                text(),
                text(),
                text(),
                GovernanceRetentionPolicyMode.of(text()),
                long(),
                long(),
                long(),
                nullableLong(),
            )
            require(value.snapshotDigest == text()) { "Governance JDBC retention policy digest is invalid." }
            return value
        }

        fun assessment(): GovernanceRetentionAssessment {
            val tenantId = text()
            val resource = resource()
            val fence = fence()
            val policy = policy()
            val holds = holdResolution()
            val clock = clock()
            val requestDigest = text()
            val outcome = retentionOutcome(text())
            val reason = retentionReason(text())
            val expected = text()
            return GovernanceRetentionAssessment.rehydrate(
                tenantId,
                resource,
                fence,
                policy,
                holds,
                clock,
                requestDigest,
                outcome,
                reason,
                expected,
            )
        }

        fun step(): GovernanceDeletionStep {
            val value = GovernanceDeletionStep.of(
                text(), integer(), deletionStage(text()), text(), text(), text(), text(),
            )
            require(value.stepDigest == text()) { "Governance JDBC deletion step digest is invalid." }
            return value
        }

        fun plan(): GovernanceDeletionPlan {
            val value = GovernanceDeletionPlan.of(
                text(), context(), fence(), assessment(), collection { step() }, bool(), long(), long(),
            )
            require(value.planDigest == text()) { "Governance JDBC deletion plan digest is invalid." }
            return value
        }

        fun receipt(): GovernanceDeletionStepReceipt = GovernanceDeletionStepReceipt.rehydrate(
            text(),
            text(),
            text(),
            integer(),
            text(),
            text(),
            GovernanceDeletionStepStatus.of(text()),
            nullableText(),
            text(),
            nullableFailure(),
            long(),
            nullableText(),
            text(),
        )

        fun nullableReceipt(): GovernanceDeletionStepReceipt? = if (bool()) receipt() else null

        fun executionRequest(plan: GovernanceDeletionPlan): GovernanceDeletionExecutionRequest {
            val context = context()
            val stepDigest = text()
            val step = plan.steps.firstOrNull { it.stepDigest == stepDigest }
                ?: throw IllegalArgumentException("Governance JDBC dispatch step is not in its plan.")
            val assessment = assessment()
            val attempt = integer()
            val previousAttempt = nullableReceipt()
            val previousReceipts = collection { receipt() }
            val expectedVersion = long()
            val request = GovernanceDeletionExecutionRequest.of(
                context,
                plan,
                step,
                assessment,
                attempt,
                previousAttempt,
                previousReceipts,
                expectedVersion,
            )
            require(request.requestDigest == text()) { "Governance JDBC execution request digest is invalid." }
            return request
        }

        fun dispatch(plan: GovernanceDeletionPlan): GovernanceDeletionDispatch {
            val request = executionRequest(plan)
            val providerId = text()
            val providerRevision = text()
            val operationReference = text()
            val phase = text()
            val preparedAt = long()
            val startedAt = nullableLong()
            val prepared = GovernanceDeletionDispatch.prepared(
                request, providerId, providerRevision, operationReference, preparedAt,
            )
            val value = when (phase) {
                GovernanceDeletionDispatchPhase.PREPARED.code -> prepared.also { require(startedAt == null) }
                GovernanceDeletionDispatchPhase.PROVIDER_CALL_STARTED.code ->
                    GovernanceDeletionDispatch.started(prepared, requireNotNull(startedAt))
                else -> throw IllegalArgumentException("Governance JDBC dispatch phase is unsupported.")
            }
            require(value.dispatchDigest == text()) { "Governance JDBC dispatch digest is invalid." }
            return value
        }

        fun nullableDispatch(plan: GovernanceDeletionPlan): GovernanceDeletionDispatch? =
            if (bool()) dispatch(plan) else null

        fun run(expectedRowStateDigest: String): GovernanceDeletionRun {
            val plan = plan()
            val commandDigest = text()
            val idempotencyKey = text()
            val status = runStatus(text())
            val successful = collection { receipt() }
            val pending = nullableReceipt()
            val dispatch = nullableDispatch(plan)
            val failure = nullableFailure()
            val nextAction = nullableLong()
            val version = long()
            val updatedAt = long()
            val encodedStateDigest = text()
            require(encodedStateDigest == expectedRowStateDigest) {
                "Governance JDBC row and memento state digests differ."
            }
            return GovernanceDeletionRun.rehydrate(
                plan,
                commandDigest,
                idempotencyKey,
                status,
                successful,
                pending,
                dispatch,
                failure,
                nextAction,
                version,
                updatedAt,
                encodedStateDigest,
            )
        }

        private fun deletionStage(value: String): GovernanceDeletionStage =
            GovernanceDeletionStage.values().firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("Governance JDBC deletion stage is unsupported.")

        private fun runStatus(value: String): GovernanceDeletionRunStatus = when (value) {
            GovernanceDeletionRunStatus.READY.code -> GovernanceDeletionRunStatus.READY
            GovernanceDeletionRunStatus.DISPATCH_PREPARED.code -> GovernanceDeletionRunStatus.DISPATCH_PREPARED
            GovernanceDeletionRunStatus.DISPATCH_STARTED.code -> GovernanceDeletionRunStatus.DISPATCH_STARTED
            GovernanceDeletionRunStatus.RETRY_WAIT.code -> GovernanceDeletionRunStatus.RETRY_WAIT
            GovernanceDeletionRunStatus.RECONCILIATION_REQUIRED.code ->
                GovernanceDeletionRunStatus.RECONCILIATION_REQUIRED
            GovernanceDeletionRunStatus.BLOCKED.code -> GovernanceDeletionRunStatus.BLOCKED
            GovernanceDeletionRunStatus.COMPLETED.code -> GovernanceDeletionRunStatus.COMPLETED
            GovernanceDeletionRunStatus.FAILED.code -> GovernanceDeletionRunStatus.FAILED
            else -> throw IllegalArgumentException("Governance JDBC deletion run status is unsupported.")
        }

        private fun retentionOutcome(value: String): GovernanceRetentionOutcome = when (value) {
            GovernanceRetentionOutcome.ELIGIBLE_FOR_DELETION.code ->
                GovernanceRetentionOutcome.ELIGIBLE_FOR_DELETION
            GovernanceRetentionOutcome.RETAIN.code -> GovernanceRetentionOutcome.RETAIN
            GovernanceRetentionOutcome.BLOCKED_BY_LEGAL_HOLD.code ->
                GovernanceRetentionOutcome.BLOCKED_BY_LEGAL_HOLD
            GovernanceRetentionOutcome.INCOMPLETE.code -> GovernanceRetentionOutcome.INCOMPLETE
            else -> throw IllegalArgumentException("Governance JDBC retention outcome is unsupported.")
        }

        private fun retentionReason(value: String): GovernanceRetentionReason = when (value) {
            GovernanceRetentionReason.RETENTION_EXPIRED.code -> GovernanceRetentionReason.RETENTION_EXPIRED
            GovernanceRetentionReason.RETENTION_PERIOD_ACTIVE.code ->
                GovernanceRetentionReason.RETENTION_PERIOD_ACTIVE
            GovernanceRetentionReason.RETAIN_INDEFINITELY.code -> GovernanceRetentionReason.RETAIN_INDEFINITELY
            GovernanceRetentionReason.ACTIVE_LEGAL_HOLD.code -> GovernanceRetentionReason.ACTIVE_LEGAL_HOLD
            GovernanceRetentionReason.INCOMPLETE_LEGAL_HOLD.code ->
                GovernanceRetentionReason.INCOMPLETE_LEGAL_HOLD
            GovernanceRetentionReason.POLICY_UNKNOWN.code -> GovernanceRetentionReason.POLICY_UNKNOWN
            GovernanceRetentionReason.POLICY_NOT_EFFECTIVE.code -> GovernanceRetentionReason.POLICY_NOT_EFFECTIVE
            GovernanceRetentionReason.STALE_EVIDENCE.code -> GovernanceRetentionReason.STALE_EVIDENCE
            else -> throw IllegalArgumentException("Governance JDBC retention reason is unsupported.")
        }
    }
}
