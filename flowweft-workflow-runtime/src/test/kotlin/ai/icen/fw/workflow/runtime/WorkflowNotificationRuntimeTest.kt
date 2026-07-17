package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.domain.WorkflowHumanDecisionAuthorizationReceipt
import ai.icen.fw.workflow.spi.WorkflowNotificationChannel
import ai.icen.fw.workflow.spi.WorkflowNotificationDelivery
import ai.icen.fw.workflow.spi.WorkflowNotificationIntent
import ai.icen.fw.workflow.spi.WorkflowNotificationProvider
import ai.icen.fw.workflow.spi.WorkflowNotificationResult
import ai.icen.fw.workflow.spi.WorkflowNotificationTemplateRef
import ai.icen.fw.workflow.spi.WorkflowPayloadValidationReceipt
import ai.icen.fw.workflow.spi.WorkflowSchemaRef
import ai.icen.fw.workflow.spi.WorkflowStructuredPayload
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkflowNotificationRuntimeTest {
    @Test
    fun `fan out is stable per recipient and never shares a provider request`() {
        val intent = intent(listOf(principal("a"), principal("b")))
        val first = WorkflowNotificationEnvelope.fanOut(intent, principal("issuer"), 900L)
        val replay = WorkflowNotificationEnvelope.fanOut(intent, principal("issuer"), 900L)

        assertEquals(2, first.size)
        assertEquals(first.map { it.envelopeDigest }, replay.map { it.envelopeDigest })
        assertNotEquals(first[0].deduplicationKey, first[1].deduplicationKey)
        assertEquals(listOf(principal("a")), first[0].intent.recipients)
        assertEquals(listOf(principal("b")), first[1].intent.recipients)
        val restored = WorkflowNotificationEnvelope.restore(
            first[0].envelopeId,
            first[0].deduplicationKey,
            first[0].originIntentDigest,
            first[0].intent,
            first[0].recipient,
            first[0].issuer,
            first[0].enqueuedAt,
        )
        assertEquals(first[0].envelopeDigest, restored.envelopeDigest)
    }

    @Test
    fun `revoked audience is durably suppressed before provider invocation`() {
        val store = MemoryNotificationStore()
        val clock = MutableClock(1_000L)
        var providerCalls = 0
        val runtime = runtime(
            store,
            clock,
            WorkflowNotificationAudienceStatus.REVOKED,
            WorkflowNotificationProvider {
                providerCalls += 1
                throw AssertionError("revoked recipients must never reach the provider")
            },
        )
        val enqueue = runtime.enqueue(context(), intent(listOf(principal("recipient"))), 900L)
        val queued = requireNotNull(enqueue.record)

        val result = runtime.dispatch(
            context(),
            queued.envelope.envelopeId,
            queued.version,
            "worker-1",
            "lease-1",
            1L,
            1_000L,
            2_000L,
        )

        assertEquals(WorkflowNotificationRuntimeCode.COMMITTED, result.code)
        assertEquals(WorkflowNotificationQueueStatus.SUPPRESSED, result.record!!.status)
        assertEquals(0, providerCalls)
    }

    @Test
    fun `exception after provider checkpoint becomes outcome unknown and blocks blind retry`() {
        val store = MemoryNotificationStore()
        val clock = MutableClock(1_000L)
        val runtime = runtime(
            store,
            clock,
            WorkflowNotificationAudienceStatus.VISIBLE,
            WorkflowNotificationProvider {
                clock.now = 1_020L
                CompletableFuture<WorkflowNotificationResult>().also {
                    it.completeExceptionally(IllegalStateException("vendor secret must not escape"))
                }
            },
        )
        val queued = requireNotNull(runtime.enqueue(context(), intent(listOf(principal("recipient"))), 900L).record)

        val first = runtime.dispatch(
            context(),
            queued.envelope.envelopeId,
            queued.version,
            "worker-1",
            "lease-1",
            1L,
            1_000L,
            2_000L,
        )
        assertEquals(WorkflowNotificationQueueStatus.OUTCOME_UNKNOWN, first.record!!.status)
        assertTrue(first.toString().contains("<redacted>"))

        val retry = runtime.dispatch(
            context(),
            queued.envelope.envelopeId,
            first.record!!.version,
            "worker-2",
            "lease-2",
            2L,
            1_030L,
            2_000L,
        )
        assertEquals(WorkflowNotificationRuntimeCode.RECONCILIATION_REQUIRED, retry.code)
    }

    @Test
    fun `accepted provider result binds receipt and later bounce report`() {
        val store = MemoryNotificationStore()
        val clock = MutableClock(1_000L)
        val runtime = runtime(
            store,
            clock,
            WorkflowNotificationAudienceStatus.VISIBLE,
            WorkflowNotificationProvider { request ->
                clock.now = 1_020L
                CompletableFuture.completedFuture(
                    WorkflowNotificationResult.success(
                        request,
                        WorkflowNotificationDelivery.accepted("provider-message-1", digest('9')),
                        1_020L,
                        1_400L,
                    ),
                )
            },
        )
        val queued = requireNotNull(runtime.enqueue(context(), intent(listOf(principal("recipient"))), 900L).record)
        val accepted = runtime.dispatch(
            context(),
            queued.envelope.envelopeId,
            queued.version,
            "worker-1",
            "lease-1",
            1L,
            1_000L,
            2_000L,
        )
        assertEquals(WorkflowNotificationQueueStatus.ACCEPTED, accepted.record!!.status)

        clock.now = 1_100L
        val bounced = runtime.recordDeliveryReport(
            context(),
            WorkflowNotificationDeliveryReport.of(
                "report-1",
                "tenant-a",
                queued.envelope.envelopeId,
                "provider-a",
                "r1",
                "provider-message-1",
                WorkflowNotificationQueueStatus.PERMANENT_BOUNCE,
                digest('8'),
                1_090L,
            ),
            accepted.record!!.version,
            1_100L,
        )
        assertEquals(WorkflowNotificationQueueStatus.PERMANENT_BOUNCE, bounced.record!!.status)
    }

    private fun runtime(
        store: MemoryNotificationStore,
        clock: MutableClock,
        audienceStatus: WorkflowNotificationAudienceStatus,
        provider: WorkflowNotificationProvider,
    ): WorkflowNotificationRuntime = WorkflowNotificationRuntime(
        AllowAuthorizationPort(),
        WorkflowNotificationAudiencePort { request ->
            WorkflowNotificationAudienceDecision.of(
                request.tenantId,
                request.recipient,
                request.requestDigest,
                audienceStatus,
                "authority-r1",
                digest('7'),
                request.evaluatedAt,
                1_800L,
            )
        },
        provider,
        store,
        WorkflowNotificationProviderProfile.of("provider-a", "r1", 500L, 4_096, 4_096, 16, 3, 100L),
        clock,
    )

    private fun intent(recipients: List<WorkflowPrincipalRef>): WorkflowNotificationIntent {
        val schema = WorkflowSchemaRef.of("schema-provider", "notification", "1", digest('1'))
        val raw = WorkflowStructuredPayload.of(schema, "{}".toByteArray())
        val payload = WorkflowStructuredPayload.validated(
            raw,
            WorkflowPayloadValidationReceipt.of(
                "validator",
                "r1",
                schema,
                raw.canonicalPayloadDigest,
                0,
                digest('2'),
            ),
        )
        return WorkflowNotificationIntent.of(
            "intent-1",
            "idempotency-1",
            WorkflowNotificationTemplateRef.of("provider-a", "task-created", "1", digest('3')),
            WorkflowNotificationChannel.IN_APP,
            recipients,
            WorkflowSubjectSnapshot.of(WorkflowSubjectRef.of("document", "document-1"), "r1", digest('4')),
            payload,
            800L,
        )
    }

    private fun context(): WorkflowTrustedCallContext = WorkflowTrustedCallContext.of(
        "tenant-a",
        principal("worker"),
        "authentication-1",
        digest('5'),
    )

    private fun principal(id: String): WorkflowPrincipalRef = WorkflowPrincipalRef.of("user", id)
    private fun digest(character: Char): String = character.toString().repeat(64)

    private class MutableClock(var now: Long) : WorkflowWorkerClock {
        override fun currentTimeMillis(): Long = now
    }

    private class AllowAuthorizationPort : WorkflowRuntimeAuthorizationPort {
        override fun authorize(request: WorkflowRuntimeAuthorizationRequest): WorkflowRuntimeAuthorizationDecision =
            WorkflowRuntimeAuthorizationDecision.of(
                "authorization-${request.action.code}",
                request.callContext.tenantId,
                request.callContext.actor,
                request.action,
                request.instanceId,
                request.requestDigest,
                WorkflowRuntimeAuthorizationStatus.AUTHORIZED,
                "authority-r1",
                digest('6'),
                request.evaluatedAt,
                3_000L,
            )

        override fun issueHumanDecisionReceipt(
            request: WorkflowRuntimeHumanDecisionReceiptRequest,
        ): WorkflowHumanDecisionAuthorizationReceipt = error("not used")

        private fun digest(character: Char): String = character.toString().repeat(64)
    }

    private class MemoryNotificationStore : WorkflowNotificationStore {
        private val records = linkedMapOf<Pair<String, String>, WorkflowNotificationRecord>()
        private val batches = linkedMapOf<Pair<String, String>, String>()
        private val reports = linkedMapOf<Pair<String, String>, String>()

        override fun enqueue(batch: WorkflowNotificationEnqueueBatch): WorkflowNotificationStoreResult {
            val batchKey = batch.tenantId to batch.originIdempotencyKey
            val existingDigest = batches[batchKey]
            if (existingDigest != null) {
                if (existingDigest != batch.originIntentDigest) {
                    return WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.CONFLICT)
                }
                return WorkflowNotificationStoreResult.replayed(
                    requireNotNull(records[batch.tenantId to batch.envelopes.first().envelopeId]),
                )
            }
            batch.envelopes.forEach { envelope ->
                val key = batch.tenantId to envelope.envelopeId
                if (records.containsKey(key)) return WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.CONFLICT)
            }
            batches[batchKey] = batch.originIntentDigest
            batch.envelopes.forEach { envelope ->
                records[batch.tenantId to envelope.envelopeId] = WorkflowNotificationRecord.queued(batch.tenantId, envelope)
            }
            return WorkflowNotificationStoreResult.applied(
                requireNotNull(records[batch.tenantId to batch.envelopes.first().envelopeId]),
            )
        }

        override fun load(tenantId: String, envelopeId: String, readAt: Long): WorkflowNotificationRecord? =
            records[tenantId to envelopeId]

        override fun claim(request: WorkflowNotificationClaim): WorkflowNotificationStoreResult = update(
            request.tenantId,
            request.envelopeId,
            request.expectedVersion,
        ) { current ->
            WorkflowNotificationRecord.restore(
                current.tenantId,
                current.envelope,
                WorkflowNotificationQueueStatus.LEASED,
                current.version + 1L,
                current.attempt + 1,
                request.lease,
                null,
                null,
                null,
                null,
                null,
                request.lease.acquiredAt,
            )
        }

        override fun checkpointProviderCall(
            request: WorkflowNotificationProviderCheckpoint,
        ): WorkflowNotificationStoreResult = update(request.tenantId, request.envelopeId, request.expectedVersion) { current ->
            require(current.lease?.leaseId == request.leaseId && current.lease?.fencingToken == request.fencingToken)
            WorkflowNotificationRecord.restore(
                current.tenantId,
                current.envelope,
                WorkflowNotificationQueueStatus.PROVIDER_CALL_STARTED,
                current.version + 1L,
                current.attempt,
                current.lease,
                null,
                request.providerRequestDigest,
                null,
                null,
                null,
                request.checkpointedAt,
            )
        }

        override fun complete(request: WorkflowNotificationCompletion): WorkflowNotificationStoreResult = update(
            request.tenantId,
            request.envelopeId,
            request.expectedVersion,
        ) { current ->
            require(current.lease?.leaseId == request.leaseId && current.lease?.fencingToken == request.fencingToken)
            WorkflowNotificationRecord.restore(
                current.tenantId,
                current.envelope,
                request.targetStatus,
                current.version + 1L,
                current.attempt,
                null,
                request.nextAttemptAt,
                current.providerRequestDigest,
                request.providerReceipt,
                request.delivery,
                request.outcomeEvidenceDigest,
                request.completedAt,
            )
        }

        override fun recordDeliveryReport(
            request: WorkflowNotificationReportMutation,
        ): WorkflowNotificationStoreResult {
            val reportKey = request.report.tenantId to request.report.reportId
            reports[reportKey]?.let { existing ->
                return if (existing == request.report.reportDigest) {
                    WorkflowNotificationStoreResult.replayed(
                        requireNotNull(records[request.report.tenantId to request.report.envelopeId]),
                    )
                } else WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.CONFLICT)
            }
            val result = update(request.report.tenantId, request.report.envelopeId, request.expectedVersion) { current ->
                WorkflowNotificationRecord.restore(
                    current.tenantId,
                    current.envelope,
                    request.report.status,
                    current.version + 1L,
                    current.attempt,
                    null,
                    null,
                    current.providerRequestDigest,
                    current.providerReceipt,
                    current.delivery,
                    request.report.evidenceDigest,
                    request.report.observedAt,
                )
            }
            if (result.code == WorkflowNotificationStoreCode.APPLIED) reports[reportKey] = request.report.reportDigest
            return result
        }

        override fun reconcile(request: WorkflowNotificationReconciliation): WorkflowNotificationStoreResult = update(
            request.tenantId,
            request.envelopeId,
            request.expectedVersion,
        ) { current ->
            val status = when (request.resolution) {
                WorkflowNotificationReconciliationResolution.ACCEPTED -> WorkflowNotificationQueueStatus.ACCEPTED
                WorkflowNotificationReconciliationResolution.NOT_SENT -> WorkflowNotificationQueueStatus.RETRY_WAIT
                else -> WorkflowNotificationQueueStatus.TERMINAL_FAILURE
            }
            WorkflowNotificationRecord.restore(
                current.tenantId,
                current.envelope,
                status,
                current.version + 1L,
                current.attempt,
                null,
                request.nextAttemptAt,
                current.providerRequestDigest,
                request.providerReceipt,
                request.delivery,
                request.evidenceDigest,
                request.reconciledAt,
            )
        }

        private fun update(
            tenantId: String,
            envelopeId: String,
            expectedVersion: Long,
            mutation: (WorkflowNotificationRecord) -> WorkflowNotificationRecord,
        ): WorkflowNotificationStoreResult {
            val key = tenantId to envelopeId
            val current = records[key]
                ?: return WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
            if (current.version != expectedVersion) {
                return WorkflowNotificationStoreResult.failed(WorkflowNotificationStoreCode.NOT_ELIGIBLE)
            }
            val updated = mutation(current)
            records[key] = updated
            return WorkflowNotificationStoreResult.applied(updated)
        }
    }
}
