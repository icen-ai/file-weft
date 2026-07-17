package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowCommentDocument
import ai.icen.fw.workflow.api.WorkflowCommentSnapshot
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowSecureFormAndMentionSpiTest {
    @Test
    fun `compound form result fails closed when an ACL field is omitted`() {
        val field = WorkflowFormFieldPath.of("/amount")
        val request = secureFormRequest(listOf(field))
        val normalized = validated(request.submission)
        val missingAcl = WorkflowFormFieldAccessReport.of(emptyList(), digest('d'))

        assertFailsWith<IllegalArgumentException> {
            WorkflowSecureFormValidationResult.success(
                request,
                WorkflowSecureFormValidationReport.valid(normalized, missingAcl),
                1_050L,
                1_100L,
            )
        }
        val completeAcl = WorkflowFormFieldAccessReport.of(
            listOf(
                WorkflowFormFieldAccessDecision.of(
                    field,
                    WorkflowFormFieldAccessMode.ALLOW,
                    WorkflowFormFieldAccessMode.ALLOW,
                ),
            ),
            digest('d'),
        )
        assertTrue(
            WorkflowSecureFormValidationResult.success(
                request,
                WorkflowSecureFormValidationReport.valid(normalized, completeAcl),
                1_050L,
                1_100L,
            ).report!!.schemaValid,
        )
    }

    @Test
    fun `mention search and exact visibility do not distinguish hidden from missing`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowMentionSearchRequest.of(context(), principal("requester"), digest('a'), "x", null, 10)
        }
        val search = WorkflowMentionSearchRequest.of(
            context(),
            principal("requester"),
            digest('a'),
            "us",
            null,
            10,
        )
        val empty = WorkflowMentionSearchResult.success(search, WorkflowMentionSearchPage.empty(), 1_050L, 1_100L)
        assertTrue(empty.page!!.candidates.isEmpty())

        val visibility = WorkflowMentionVisibilityRequest.of(
            context(),
            principal("requester"),
            principal("hidden-or-missing"),
            digest('a'),
        )
        val result = WorkflowMentionVisibilityResult.success(
            visibility,
            WorkflowMentionVisibilityDecision.notVisible("directory-r7"),
            1_050L,
            1_100L,
        )
        assertFalse(result.decision!!.visible)
        assertEquals(null, result.decision!!.candidate)
    }

    @Test
    fun `mention notification carries no comment text and requires fresh visibility`() {
        val recipient = principal("recipient")
        val comment = WorkflowCommentSnapshot.of(
            "comment-1",
            0L,
            WorkflowInstanceRef.of("instance-1", 0L),
            null,
            principal("author"),
            WorkflowCommentDocument.of(
                listOf(WorkflowCommentToken.text("sensitive summary "), WorkflowCommentToken.mention(recipient, "R")),
            ),
            digest('a'),
            digest('b'),
            900L,
        )
        val visibility = WorkflowMentionVisibilityAttestation.of(recipient, "directory-r7", digest('c'), 990L, 1_100L)
        val intent = WorkflowMentionNotificationIntent.of(
            "intent-1",
            "idem-1",
            comment,
            recipient,
            visibility,
            995L,
        )
        val request = WorkflowMentionNotificationRequest.of(context(), intent)

        assertFalse(request.toString().contains("sensitive"))
        assertFailsWith<IllegalArgumentException> {
            WorkflowMentionNotificationRequest.of(
                context(requestedAt = 1_101L, deadline = 1_200L),
                intent,
            )
        }
    }

    private fun secureFormRequest(fields: Collection<WorkflowFormFieldPath>): WorkflowSecureFormValidationRequest {
        val apiSchema = WorkflowJsonSchemaRef.of(
            "schema-provider",
            "expense",
            "1",
            WorkflowJsonSchemaDialect.JSON_SCHEMA_2020_12,
            digest('b'),
        )
        val form = WorkflowFormVersionRef.of("expense", "1", apiSchema, null, null, digest('c'))
        val spiSchema = WorkflowSchemaRef.of("schema-provider", "expense", "1", digest('b'))
        return WorkflowSecureFormValidationRequest.of(
            context(),
            form,
            WorkflowSubjectSnapshot.of(WorkflowSubjectRef.of("expense", "expense-1"), "r1", digest('e')),
            principal("author"),
            WorkflowFormValidationOperation.SUBMIT,
            fields,
            WorkflowStructuredPayload.of(spiSchema, "{}".toByteArray()),
            "auth-r1",
            digest('d'),
        )
    }

    private fun validated(raw: WorkflowStructuredPayload): WorkflowStructuredPayload = WorkflowStructuredPayload.validated(
        raw,
        WorkflowPayloadValidationReceipt.of(
            "schema-validator",
            "r1",
            raw.schema,
            raw.canonicalPayloadDigest,
            1,
            digest('f'),
        ),
    )

    private fun principal(id: String): WorkflowPrincipalRef = WorkflowPrincipalRef.of("user", id)

    private fun context(requestedAt: Long = 1_000L, deadline: Long = 1_200L): WorkflowProviderCallContext =
        WorkflowProviderCallContext.of(
            "request-1",
            "tenant-a",
            "provider-a",
            "r1",
            "contract-test",
            requestedAt,
            deadline,
            4_096,
            4_096,
            16,
        )

    private fun digest(character: Char): String = character.toString().repeat(64)
}
