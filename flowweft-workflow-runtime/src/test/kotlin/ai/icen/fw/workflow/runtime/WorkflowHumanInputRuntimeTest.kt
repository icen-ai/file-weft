package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowCommentDocument
import ai.icen.fw.workflow.api.WorkflowCommentToken
import ai.icen.fw.workflow.api.WorkflowFormFieldAccessDecision
import ai.icen.fw.workflow.api.WorkflowFormFieldAccessMode
import ai.icen.fw.workflow.api.WorkflowFormFieldAccessReport
import ai.icen.fw.workflow.api.WorkflowFormFieldPath
import ai.icen.fw.workflow.api.WorkflowFormVersionRef
import ai.icen.fw.workflow.api.WorkflowInstanceRef
import ai.icen.fw.workflow.api.WorkflowJsonSchemaDialect
import ai.icen.fw.workflow.api.WorkflowJsonSchemaRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.domain.WorkflowHumanDecisionAuthorizationReceipt
import ai.icen.fw.workflow.spi.WorkflowFormValidationOperation
import ai.icen.fw.workflow.spi.WorkflowMentionNotificationProvider
import ai.icen.fw.workflow.spi.WorkflowMentionResolver
import ai.icen.fw.workflow.spi.WorkflowMentionSearchPage
import ai.icen.fw.workflow.spi.WorkflowMentionSearchResult
import ai.icen.fw.workflow.spi.WorkflowMentionVisibilityDecision
import ai.icen.fw.workflow.spi.WorkflowMentionVisibilityResult
import ai.icen.fw.workflow.spi.WorkflowPayloadValidationReceipt
import ai.icen.fw.workflow.spi.WorkflowSchemaRef
import ai.icen.fw.workflow.spi.WorkflowSecureFormValidationReport
import ai.icen.fw.workflow.spi.WorkflowSecureFormValidationResult
import ai.icen.fw.workflow.spi.WorkflowSecureFormValidator
import ai.icen.fw.workflow.spi.WorkflowStructuredPayload
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkflowHumanInputRuntimeTest {
    @Test
    fun `submit fails closed when any requested field is not writable`() {
        val field = WorkflowFormFieldPath.of("/amount")
        val schema = WorkflowSchemaRef.of("registry", "expense", "1", digest('b'))
        val raw = WorkflowStructuredPayload.of(schema, "{}".toByteArray())
        val form = WorkflowFormVersionRef.of(
            "expense",
            "1",
            WorkflowJsonSchemaRef.of(
                "registry",
                "expense",
                "1",
                WorkflowJsonSchemaDialect.JSON_SCHEMA_2020_12,
                digest('b'),
            ),
            null,
            null,
            digest('c'),
        )
        val validator = WorkflowSecureFormValidator { request ->
            val receipt = WorkflowPayloadValidationReceipt.of(
                "validator",
                "r1",
                request.submission.schema,
                request.submission.canonicalPayloadDigest,
                1,
                digest('f'),
            )
            val normalized = WorkflowStructuredPayload.validated(request.submission, receipt)
            val access = WorkflowFormFieldAccessReport.of(
                listOf(WorkflowFormFieldAccessDecision.denied(field)),
                request.authorizationReceiptDigest,
            )
            CompletableFuture.completedFuture(
                WorkflowSecureFormValidationResult.success(
                    request,
                    WorkflowSecureFormValidationReport.valid(normalized, access),
                    NOW,
                    NOW + 100L,
                ),
            )
        }
        val runtime = runtime(validator, visible = true)
        val result = runtime.validateForm(
            WorkflowRuntimeFormCommand.of(
                trusted("author"),
                "instance-1",
                subject(),
                form,
                WorkflowFormValidationOperation.SUBMIT,
                listOf(field),
                raw,
                "submission-1",
                0L,
                "idem-form-1",
            ),
        )

        assertEquals(WorkflowHumanInputResultCode.AUTHORIZATION_DENIED, result.code)
        assertEquals("field-write-denied", result.diagnostic!!.code)
        assertNull(result.value)

        val incompleteCoverage = runtime(
            WorkflowSecureFormValidator { request ->
                val receipt = WorkflowPayloadValidationReceipt.of(
                    "validator",
                    "r1",
                    request.submission.schema,
                    request.submission.canonicalPayloadDigest,
                    2,
                    digest('f'),
                )
                val normalized = WorkflowStructuredPayload.validated(request.submission, receipt)
                val access = WorkflowFormFieldAccessReport.of(
                    listOf(
                        WorkflowFormFieldAccessDecision.of(
                            field,
                            WorkflowFormFieldAccessMode.ALLOW,
                            WorkflowFormFieldAccessMode.ALLOW,
                        ),
                    ),
                    request.authorizationReceiptDigest,
                )
                CompletableFuture.completedFuture(
                    WorkflowSecureFormValidationResult.success(
                        request,
                        WorkflowSecureFormValidationReport.valid(normalized, access),
                        NOW,
                        NOW + 100L,
                    ),
                )
            },
            visible = true,
        ).validateForm(
            WorkflowRuntimeFormCommand.of(
                trusted("author"),
                "instance-1",
                subject(),
                form,
                WorkflowFormValidationOperation.SUBMIT,
                listOf(field),
                raw,
                "submission-1",
                0L,
                "idem-form-2",
            ),
        )

        assertEquals(WorkflowHumanInputResultCode.RECEIPT_INVALID, incompleteCoverage.code)
        assertEquals("field-coverage-incomplete", incompleteCoverage.diagnostic!!.code)
    }

    @Test
    fun `hidden and missing mention share one value-free comment result`() {
        val recipient = principal("hidden-or-missing")
        val runtime = runtime(WorkflowSecureFormValidator { CompletableFuture.completedFuture(null) }, visible = false)
        val result = runtime.createComment(
            WorkflowRuntimeCommentCommand.of(
                trusted("author"),
                "comment-1",
                0L,
                WorkflowInstanceRef.of("instance-1", 0L),
                null,
                WorkflowCommentDocument.of(
                    listOf(WorkflowCommentToken.text("hello "), WorkflowCommentToken.mention(recipient, "Same Name")),
                ),
                "idem-comment-1",
            ),
        )

        assertEquals(WorkflowHumanInputResultCode.MENTION_NOT_VISIBLE, result.code)
        assertEquals("mention-not-visible", result.diagnostic!!.code)
        assertNull(result.comment)
    }

    private fun runtime(
        validator: WorkflowSecureFormValidator,
        visible: Boolean,
    ): WorkflowHumanInputRuntime {
        val resolver = object : WorkflowMentionResolver {
            override fun search(request: ai.icen.fw.workflow.spi.WorkflowMentionSearchRequest) =
                CompletableFuture.completedFuture(
                    WorkflowMentionSearchResult.success(request, WorkflowMentionSearchPage.empty(), NOW, NOW + 100L),
                )

            override fun verifyVisibility(request: ai.icen.fw.workflow.spi.WorkflowMentionVisibilityRequest) =
                CompletableFuture.completedFuture(
                    WorkflowMentionVisibilityResult.success(
                        request,
                        if (visible) error("The visible branch is not used by these focused tests.")
                        else WorkflowMentionVisibilityDecision.notVisible("directory-r1"),
                        NOW,
                        NOW + 100L,
                    ),
                )
        }
        val profile = WorkflowHumanInputProviderProfile.of("provider-a", "r1", 100L, 4_096, 4_096, 16)
        return WorkflowHumanInputRuntime(
            authorization(),
            idempotency(),
            validator,
            resolver,
            WorkflowMentionNotificationProvider { CompletableFuture.completedFuture(null) },
            WorkflowWorkerClock { NOW },
            profile,
            profile,
            profile,
            1_000L,
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
        ): WorkflowHumanDecisionAuthorizationReceipt = error("Not used by human-input orchestration tests.")
    }

    private fun idempotency(): WorkflowHumanInputIdempotencyPort = object : WorkflowHumanInputIdempotencyPort {
        override fun reserve(
            request: WorkflowHumanInputReservationRequest,
        ): WorkflowHumanInputReservationResult = WorkflowHumanInputReservationResult.reserved(
            WorkflowHumanInputReservation.of(
                request.tenantId,
                request.idempotencyKey,
                request.operation,
                request.requestDigest,
                "lease-1",
                1L,
                request.leaseUntilEpochMilli,
            ),
        )

        override fun complete(
            reservation: WorkflowHumanInputReservation,
            record: WorkflowHumanInputIdempotencyRecord,
        ): WorkflowHumanInputIdempotencyWriteResult = WorkflowHumanInputIdempotencyWriteResult.stored(record)
    }

    private fun trusted(id: String): WorkflowTrustedCallContext = WorkflowTrustedCallContext.of(
        "tenant-a",
        principal(id),
        "auth-$id",
        digest('9'),
    )

    private fun subject(): WorkflowSubjectSnapshot = WorkflowSubjectSnapshot.of(
        WorkflowSubjectRef.of("expense", "expense-1"),
        "r1",
        digest('e'),
    )

    private fun principal(id: String): WorkflowPrincipalRef = WorkflowPrincipalRef.of("user", id)
    private fun digest(character: Char): String = character.toString().repeat(64)

    companion object {
        private const val NOW = 1_000L
    }
}
