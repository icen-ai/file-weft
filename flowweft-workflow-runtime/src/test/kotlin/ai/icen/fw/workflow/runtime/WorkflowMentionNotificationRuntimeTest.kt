package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowCommentDocument
import ai.icen.fw.workflow.api.WorkflowCommentSnapshot
import ai.icen.fw.workflow.api.WorkflowCommentToken
import ai.icen.fw.workflow.api.WorkflowInstanceRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.domain.WorkflowHumanDecisionAuthorizationReceipt
import ai.icen.fw.workflow.spi.WorkflowMentionCandidate
import ai.icen.fw.workflow.spi.WorkflowMentionNotificationProvider
import ai.icen.fw.workflow.spi.WorkflowMentionNotificationResult
import ai.icen.fw.workflow.spi.WorkflowMentionResolver
import ai.icen.fw.workflow.spi.WorkflowMentionSearchPage
import ai.icen.fw.workflow.spi.WorkflowMentionSearchResult
import ai.icen.fw.workflow.spi.WorkflowMentionVisibilityDecision
import ai.icen.fw.workflow.spi.WorkflowMentionVisibilityResult
import ai.icen.fw.workflow.spi.WorkflowNotificationDelivery
import ai.icen.fw.workflow.spi.WorkflowProviderFailure
import ai.icen.fw.workflow.spi.WorkflowProviderOutcome
import ai.icen.fw.workflow.spi.WorkflowSecureFormValidator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowMentionNotificationRuntimeTest {
    @Test
    fun `provider call is checkpointed before one accepted delivery`() {
        val port = CheckpointingPort()
        val calls = AtomicInteger()
        val runtime = runtime(port, WorkflowMentionNotificationProvider { request ->
            calls.incrementAndGet()
            CompletableFuture.completedFuture(WorkflowMentionNotificationResult.success(
                request,
                WorkflowNotificationDelivery.accepted("provider-message-1", digest('e')),
                NOW,
                NOW + 100L,
            ))
        })

        val result = runtime.notifyMention(command())

        assertEquals(WorkflowHumanInputResultCode.SUCCEEDED, result.code)
        assertEquals(1, calls.get())
        assertEquals(WorkflowMentionNotificationCheckpointStatus.ACCEPTED, port.checkpoint!!.status)
        assertEquals(result.delivery!!.deliveryDigest, port.completed!!.delivery!!.deliveryDigest)
    }

    @Test
    fun `provider exception becomes durable outcome unknown and is never blindly resent`() {
        val port = CheckpointingPort()
        val calls = AtomicInteger()
        val runtime = runtime(port, WorkflowMentionNotificationProvider {
            calls.incrementAndGet()
            CompletableFuture<WorkflowMentionNotificationResult>().also { future ->
                future.completeExceptionally(IllegalStateException("redacted provider failure"))
            }
        })

        val first = runtime.notifyMention(command())
        val second = runtime.notifyMention(command())

        assertEquals(WorkflowHumanInputResultCode.OUTCOME_UNKNOWN, first.code)
        assertEquals(WorkflowHumanInputResultCode.OUTCOME_UNKNOWN, second.code)
        assertEquals(1, calls.get())
        assertEquals(WorkflowMentionNotificationCheckpointStatus.OUTCOME_UNKNOWN, port.checkpoint!!.status)
    }

    @Test
    fun `retryable provider failure receipt remains outcome unknown and is never blindly resent`() {
        val port = CheckpointingPort()
        val calls = AtomicInteger()
        val runtime = runtime(port, WorkflowMentionNotificationProvider { request ->
            calls.incrementAndGet()
            CompletableFuture.completedFuture(WorkflowMentionNotificationResult.failure(
                request,
                WorkflowProviderOutcome.UNAVAILABLE,
                WorkflowProviderFailure.of("temporary-provider-failure", true),
                NOW,
                NOW + 100L,
            ))
        })

        val first = runtime.notifyMention(command())
        val second = runtime.notifyMention(command())

        assertEquals(WorkflowHumanInputResultCode.OUTCOME_UNKNOWN, first.code)
        assertEquals(WorkflowHumanInputResultCode.OUTCOME_UNKNOWN, second.code)
        assertEquals(1, calls.get())
        assertEquals(WorkflowMentionNotificationCheckpointStatus.OUTCOME_UNKNOWN, port.checkpoint!!.status)
    }

    private fun runtime(
        port: CheckpointingPort,
        provider: WorkflowMentionNotificationProvider,
    ): WorkflowHumanInputRuntime {
        val resolver = object : WorkflowMentionResolver {
            override fun search(request: ai.icen.fw.workflow.spi.WorkflowMentionSearchRequest) =
                CompletableFuture.completedFuture(
                    WorkflowMentionSearchResult.success(request, WorkflowMentionSearchPage.empty(), NOW, NOW + 100L),
                )

            override fun verifyVisibility(request: ai.icen.fw.workflow.spi.WorkflowMentionVisibilityRequest) =
                CompletableFuture.completedFuture(WorkflowMentionVisibilityResult.success(
                    request,
                    WorkflowMentionVisibilityDecision.visible(WorkflowMentionCandidate.of(
                        request.mentionedPrincipal,
                        "Mentioned User",
                        "directory-r1",
                        digest('b'),
                    )),
                    NOW,
                    NOW + 100L,
                ))
        }
        val profile = WorkflowHumanInputProviderProfile.of("provider-a", "r1", 100L, 4_096, 4_096, 16)
        return WorkflowHumanInputRuntime(
            authorization(),
            port,
            WorkflowSecureFormValidator { CompletableFuture.completedFuture(null) },
            resolver,
            provider,
            WorkflowWorkerClock { NOW },
            profile,
            profile,
            profile,
            1_000L,
        )
    }

    private fun command(): WorkflowRuntimeMentionNotificationCommand {
        val recipient = WorkflowPrincipalRef.of("user", "recipient-1")
        val comment = WorkflowCommentSnapshot.of(
            "comment-1",
            1L,
            WorkflowInstanceRef.of("instance-1", 2L),
            null,
            WorkflowPrincipalRef.of("user", "author-1"),
            WorkflowCommentDocument.of(listOf(
                WorkflowCommentToken.text("hello "),
                WorkflowCommentToken.mention(recipient, "Mentioned User"),
            )),
            digest('a'),
            digest('b'),
            NOW,
        )
        return WorkflowRuntimeMentionNotificationCommand.of(
            WorkflowTrustedCallContext.of(
                "tenant-a",
                WorkflowPrincipalRef.of("user", "author-1"),
                "auth-1",
                digest('9'),
            ),
            comment,
            recipient,
            "mention-idem-1",
        )
    }

    private fun authorization(): WorkflowRuntimeAuthorizationPort = object : WorkflowRuntimeAuthorizationPort {
        override fun authorize(request: WorkflowRuntimeAuthorizationRequest): WorkflowRuntimeAuthorizationDecision =
            WorkflowRuntimeAuthorizationDecision.of(
                "authorization-1",
                request.callContext.tenantId,
                request.callContext.actor,
                request.action,
                request.instanceId,
                request.requestDigest,
                WorkflowRuntimeAuthorizationStatus.AUTHORIZED,
                "authority-r1",
                digest('a'),
                NOW,
                NOW + 1_000L,
            )

        override fun issueHumanDecisionReceipt(
            request: WorkflowRuntimeHumanDecisionReceiptRequest,
        ): WorkflowHumanDecisionAuthorizationReceipt = error("Not used by mention notification tests.")
    }

    private class CheckpointingPort :
        WorkflowHumanInputIdempotencyPort,
        WorkflowMentionNotificationCheckpointPort {
        var reservation: WorkflowHumanInputReservation? = null
        var checkpoint: WorkflowMentionNotificationCheckpointRecord? = null
        var completed: WorkflowHumanInputIdempotencyRecord? = null

        override fun reserve(request: WorkflowHumanInputReservationRequest): WorkflowHumanInputReservationResult {
            completed?.let { return WorkflowHumanInputReservationResult.replayed(it) }
            val current = checkpoint
            if (current != null && current.status != WorkflowMentionNotificationCheckpointStatus.NOT_SENT) {
                return WorkflowHumanInputReservationResult.failed(WorkflowHumanInputReservationCode.OUTCOME_UNKNOWN)
            }
            val value = reservation ?: WorkflowHumanInputReservation.of(
                request.tenantId,
                request.idempotencyKey,
                request.operation,
                request.requestDigest,
                "lease-1",
                1L,
                request.leaseUntilEpochMilli,
            ).also { reservation = it }
            return WorkflowHumanInputReservationResult.reserved(value)
        }

        override fun complete(
            reservation: WorkflowHumanInputReservation,
            record: WorkflowHumanInputIdempotencyRecord,
        ): WorkflowHumanInputIdempotencyWriteResult = WorkflowHumanInputIdempotencyWriteResult.stored(record)

        override fun loadProviderCheckpoint(
            tenantId: String,
            idempotencyKey: String,
            readAtEpochMilli: Long,
        ): WorkflowMentionNotificationCheckpointRecord? = checkpoint?.takeIf {
            it.tenantId == tenantId && it.idempotencyKey == idempotencyKey
        }

        override fun checkpointProviderCall(
            request: WorkflowMentionNotificationProviderCheckpoint,
        ): WorkflowMentionNotificationCheckpointResult {
            checkpoint?.let { return WorkflowMentionNotificationCheckpointResult.replayed(it) }
            val value = WorkflowMentionNotificationCheckpointRecord.of(
                request.reservation.tenantId,
                request.reservation.idempotencyKey,
                request.reservation.requestDigest,
                request.reservation.leaseId,
                request.reservation.fencingToken,
                request.providerRequestDigest,
                WorkflowMentionNotificationCheckpointStatus.PROVIDER_CALL_STARTED,
                null,
                1L,
                request.checkpointedAtEpochMilli,
                request.checkpointedAtEpochMilli,
            )
            checkpoint = value
            return WorkflowMentionNotificationCheckpointResult.applied(value)
        }

        override fun markProviderOutcomeUnknown(
            request: WorkflowMentionNotificationOutcomeUnknown,
        ): WorkflowMentionNotificationCheckpointResult {
            val value = WorkflowMentionNotificationCheckpointRecord.of(
                request.checkpoint.tenantId,
                request.checkpoint.idempotencyKey,
                request.checkpoint.operationRequestDigest,
                request.checkpoint.leaseId,
                request.checkpoint.fencingToken,
                request.checkpoint.providerRequestDigest,
                WorkflowMentionNotificationCheckpointStatus.OUTCOME_UNKNOWN,
                request.evidenceDigest,
                request.checkpoint.recordVersion + 1L,
                request.checkpoint.checkpointedAtEpochMilli,
                request.observedAtEpochMilli,
            )
            checkpoint = value
            return WorkflowMentionNotificationCheckpointResult.applied(value)
        }

        override fun reconcileProviderCall(
            request: WorkflowMentionNotificationReconciliation,
        ): WorkflowMentionNotificationReconciliationResult {
            val status = when (request.resolution) {
                WorkflowMentionNotificationReconciliationResolution.ACCEPTED ->
                    WorkflowMentionNotificationCheckpointStatus.ACCEPTED
                WorkflowMentionNotificationReconciliationResolution.NOT_SENT ->
                    WorkflowMentionNotificationCheckpointStatus.NOT_SENT
                else -> WorkflowMentionNotificationCheckpointStatus.TERMINAL_FAILURE
            }
            val value = WorkflowMentionNotificationCheckpointRecord.of(
                request.checkpoint.tenantId,
                request.checkpoint.idempotencyKey,
                request.checkpoint.operationRequestDigest,
                request.checkpoint.leaseId,
                request.checkpoint.fencingToken,
                request.checkpoint.providerRequestDigest,
                status,
                request.evidenceDigest,
                request.checkpoint.recordVersion + 1L,
                request.checkpoint.checkpointedAtEpochMilli,
                request.reconciledAtEpochMilli,
            )
            checkpoint = value
            completed = request.record
            return WorkflowMentionNotificationReconciliationResult.applied(value, request.record)
        }
    }

    private fun digest(character: Char): String = character.toString().repeat(64)

    private companion object {
        const val NOW = 1_000L
    }
}
