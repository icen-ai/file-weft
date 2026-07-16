package ai.icen.fw.workflow.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkflowFormAndCommentContractTest {
    @Test
    fun `form versions pin JSON Schema 2020-12 and omitted ACL paths deny`() {
        val schema = WorkflowJsonSchemaRef.of(
            "host-registry",
            "expense-form",
            "3",
            WorkflowJsonSchemaDialect.JSON_SCHEMA_2020_12,
            digest('a'),
        )
        val form = WorkflowFormVersionRef.of("expense", "7", schema, "2", digest('b'), digest('c'))
        val amount = WorkflowFormFieldPath.of("/amount")
        val secret = WorkflowFormFieldPath.of("/bankAccount")
        val report = WorkflowFormFieldAccessReport.of(
            listOf(
                WorkflowFormFieldAccessDecision.of(
                    amount,
                    WorkflowFormFieldAccessMode.ALLOW,
                    WorkflowFormFieldAccessMode.ALLOW,
                ),
            ),
            digest('d'),
        )

        assertEquals("json-schema-2020-12", form.dataSchema.dialect.code)
        assertTrue(report.mayWrite(amount))
        assertFalse(report.mayWrite(secret))
        assertEquals(WorkflowFormFieldAccessMode.DENY, report.readMode(secret))
        val reversed = WorkflowFormFieldAccessReport.of(
            listOf(
                WorkflowFormFieldAccessDecision.denied(secret),
                WorkflowFormFieldAccessDecision.of(
                    amount,
                    WorkflowFormFieldAccessMode.ALLOW,
                    WorkflowFormFieldAccessMode.ALLOW,
                ),
            ),
            digest('d'),
        )
        val canonical = WorkflowFormFieldAccessReport.of(
            listOf(
                WorkflowFormFieldAccessDecision.of(
                    amount,
                    WorkflowFormFieldAccessMode.ALLOW,
                    WorkflowFormFieldAccessMode.ALLOW,
                ),
                WorkflowFormFieldAccessDecision.denied(secret),
            ),
            digest('d'),
        )
        assertEquals(canonical.reportDigest, reversed.reportDigest)
        assertFailsWith<IllegalArgumentException> {
            WorkflowFormVersionRef.of(
                "expense",
                "8",
                WorkflowJsonSchemaRef.of(
                    "host-registry",
                    "expense-form",
                    "4",
                    WorkflowJsonSchemaDialect.of("future-dialect"),
                    digest('e'),
                ),
                null,
                null,
                digest('f'),
            )
        }
        assertFailsWith<IllegalArgumentException> { WorkflowFormFieldPath.of("/bad~2escape") }
    }

    @Test
    fun `comment AST keeps markup inert and mention identity separate from display snapshot`() {
        val mentioned = WorkflowPrincipalRef.of("user", "directory-id-7")
        val document = WorkflowCommentDocument.of(
            listOf(
                WorkflowCommentToken.text("请 <img src=x onerror=alert(1)> 联系 "),
                WorkflowCommentToken.mention(mentioned, "同名用户"),
            ),
        )
        val snapshot = WorkflowCommentSnapshot.of(
            "comment-1",
            0L,
            WorkflowInstanceRef.of("instance-1", 2L),
            WorkflowWorkItemRef.of("task-1", 8L),
            WorkflowPrincipalRef.of("user", "author-1"),
            document,
            digest('a'),
            digest('b'),
            1_000L,
        )

        assertEquals(WorkflowCommentTokenKind.TEXT, document.tokens[0].kind)
        assertEquals("directory-id-7", document.tokens[1].principal!!.id)
        assertEquals("同名用户", document.tokens[1].displayNameSnapshot)
        assertEquals(listOf(mentioned), document.mentionedPrincipals)
        assertFalse(snapshot.toString().contains("onerror"))
        assertFailsWith<IllegalArgumentException> {
            WorkflowCommentSnapshot.of(
                "comment-2",
                0L,
                WorkflowInstanceRef.of("instance-1", 2L),
                null,
                WorkflowPrincipalRef.of("user", "author-1"),
                document,
                digest('a'),
                null,
                1_000L,
            )
        }
    }

    @Test
    fun `comment aggregate content is bounded across otherwise valid tokens`() {
        val chunks = List(9) { WorkflowCommentToken.text("x".repeat(8_192)) }

        assertFailsWith<IllegalArgumentException> { WorkflowCommentDocument.of(chunks) }
    }

    private fun digest(character: Char): String = character.toString().repeat(64)
}
