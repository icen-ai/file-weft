package ai.icen.fw.workflow.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class WorkflowReferenceContractTest {
    @Test
    fun `factories preserve exact UTF-8 values and bind observed versions`() {
        val principal = WorkflowPrincipalRef.of("用户", "审批人-A😀")
        val subject = WorkflowSubjectRef.of("知识文件", "法律/合同-甲")
        val snapshot = WorkflowSubjectSnapshot.of(subject, "修订-七", DIGEST_A)
        val definition = WorkflowDefinitionRef.of("合同审批", "2026.07-A", DIGEST_B)
        val instance = WorkflowInstanceRef.of("流程实例-甲", 0L)
        val workItem = WorkflowWorkItemRef.of("任务-乙", Long.MAX_VALUE)

        assertEquals("用户", principal.type)
        assertEquals("审批人-A😀", principal.id)
        assertEquals(subject, snapshot.ref)
        assertEquals("修订-七", snapshot.revision)
        assertEquals(DIGEST_A, snapshot.digest)
        assertEquals("合同审批", definition.key)
        assertEquals("2026.07-A", definition.version)
        assertEquals(DIGEST_B, definition.digest)
        assertEquals(0L, instance.expectedVersion)
        assertEquals(Long.MAX_VALUE, workItem.expectedVersion)
    }

    @Test
    fun `value equality and hashes remain case-sensitive and normalization-free`() {
        val principal = WorkflowPrincipalRef.of("USER", "Alice")
        val samePrincipal = WorkflowPrincipalRef.of("USER", "Alice")
        assertEquals(principal, samePrincipal)
        assertEquals(principal.hashCode(), samePrincipal.hashCode())
        assertNotEquals(principal, WorkflowPrincipalRef.of("user", "Alice"))
        assertNotEquals(principal, WorkflowPrincipalRef.of("USER", "alice"))

        val composed = WorkflowSubjectRef.of("DOCUMENT", "caf\u00e9")
        val decomposed = WorkflowSubjectRef.of("DOCUMENT", "cafe\u0301")
        assertNotEquals(composed, decomposed)

        assertEqualValue(
            WorkflowSubjectSnapshot.of(composed, "R1", DIGEST_A),
            WorkflowSubjectSnapshot.of(composed, "R1", DIGEST_A),
        )
        assertEqualValue(
            WorkflowDefinitionRef.of("leave", "V1", DIGEST_B),
            WorkflowDefinitionRef.of("leave", "V1", DIGEST_B),
        )
        assertEqualValue(WorkflowInstanceRef.of("instance", 7L), WorkflowInstanceRef.of("instance", 7L))
        assertEqualValue(WorkflowWorkItemRef.of("item", 8L), WorkflowWorkItemRef.of("item", 8L))
        assertNotEquals(WorkflowInstanceRef.of("instance", 7L), WorkflowInstanceRef.of("instance", 8L))
        assertNotEquals(WorkflowWorkItemRef.of("item", 8L), WorkflowWorkItemRef.of("Item", 8L))
    }

    @Test
    fun `rejects malformed Unicode controls ambiguous boundaries and noncharacters`() {
        val unpairedHighSurrogate = String(charArrayOf('\uD800'))
        val unpairedLowSurrogate = String(charArrayOf('\uDC00'))

        listOf(
            "",
            " USER",
            "USER ",
            "US\u0000ER",
            "US\u202eER",
            "US\u2028ER",
            "US\u2029ER",
            "US\ufdd0ER",
            "US${String(Character.toChars(0x13430))}ER",
            unpairedHighSurrogate,
            unpairedLowSurrogate,
        ).forEach { invalidType ->
            assertFailsWith<IllegalArgumentException> {
                WorkflowPrincipalRef.of(invalidType, "principal")
            }
        }

        assertFailsWith<IllegalArgumentException> {
            WorkflowSubjectRef.of("DOCUMENT", " subject")
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowSubjectSnapshot.of(WorkflowSubjectRef.of("DOCUMENT", "subject"), "R\n1", DIGEST_A)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowDefinitionRef.of("leave", "V1\t", DIGEST_A)
        }
    }

    @Test
    fun `uses a fixed Unicode profile and exact four-byte UTF-8 limits`() {
        val recentlyAssignedCodePoint = String(Character.toChars(0x1FAE0))
        assertEquals(
            "user-$recentlyAssignedCodePoint",
            WorkflowPrincipalRef.of("USER", "user-$recentlyAssignedCodePoint").id,
        )

        val exactType = "😀".repeat(16)
        val exactId = "😀".repeat(128)
        val exactRevision = "😀".repeat(64)
        val exactDefinitionVersion = "😀".repeat(32)
        assertEquals(exactType, WorkflowSubjectRef.of(exactType, "subject").type)
        assertEquals(exactId, WorkflowInstanceRef.of(exactId, 1L).id)
        assertEquals(exactId, WorkflowWorkItemRef.of(exactId, 1L).id)
        assertEquals(
            exactRevision,
            WorkflowSubjectSnapshot.of(WorkflowSubjectRef.of("DOCUMENT", "subject"), exactRevision, DIGEST_A).revision,
        )
        assertEquals(
            exactDefinitionVersion,
            WorkflowDefinitionRef.of("😀".repeat(64), exactDefinitionVersion, DIGEST_A).version,
        )

        assertFailsWith<IllegalArgumentException> { WorkflowSubjectRef.of("😀".repeat(17), "subject") }
        assertFailsWith<IllegalArgumentException> { WorkflowPrincipalRef.of("USER", "😀".repeat(129)) }
        assertFailsWith<IllegalArgumentException> { WorkflowInstanceRef.of("😀".repeat(129), 1L) }
        assertFailsWith<IllegalArgumentException> { WorkflowWorkItemRef.of("😀".repeat(129), 1L) }
        assertFailsWith<IllegalArgumentException> {
            WorkflowSubjectSnapshot.of(
                WorkflowSubjectRef.of("DOCUMENT", "subject"),
                "😀".repeat(65),
                DIGEST_A,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowDefinitionRef.of("😀".repeat(65), "V1", DIGEST_A)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowDefinitionRef.of("definition", "😀".repeat(33), DIGEST_A)
        }
    }

    @Test
    fun `enforces exact UTF-8 byte boundaries without excluding Chinese identifiers`() {
        val exactType = "类".repeat(21) + "a"
        val exactId = "文".repeat(170) + "aa"
        val exactRevision = "修".repeat(85) + "a"
        val exactDefinitionKey = "流".repeat(85) + "a"
        val exactDefinitionVersion = "版".repeat(42) + "aa"

        assertEquals(exactType, WorkflowPrincipalRef.of(exactType, exactId).type)
        assertEquals(exactId, WorkflowSubjectRef.of("DOCUMENT", exactId).id)
        assertEquals(
            exactRevision,
            WorkflowSubjectSnapshot.of(WorkflowSubjectRef.of("DOCUMENT", "subject"), exactRevision, DIGEST_A).revision,
        )
        assertEquals(
            exactDefinitionVersion,
            WorkflowDefinitionRef.of(exactDefinitionKey, exactDefinitionVersion, DIGEST_A).version,
        )

        assertFailsWith<IllegalArgumentException> {
            WorkflowPrincipalRef.of("类".repeat(21) + "aa", "principal")
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowSubjectRef.of("DOCUMENT", "文".repeat(170) + "aaa")
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowSubjectSnapshot.of(
                WorkflowSubjectRef.of("DOCUMENT", "subject"),
                "修".repeat(85) + "aa",
                DIGEST_A,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowDefinitionRef.of("流".repeat(85) + "aa", "V1", DIGEST_A)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowDefinitionRef.of("definition", "版".repeat(42) + "aaa", DIGEST_A)
        }
    }

    @Test
    fun `accepts only canonical lower-case SHA-256 and never substitutes it`() {
        val subject = WorkflowSubjectRef.of("DOCUMENT", "subject")
        assertEquals(DIGEST_A, WorkflowSubjectSnapshot.of(subject, "R1", DIGEST_A).digest)
        assertEquals(DIGEST_B, WorkflowDefinitionRef.of("leave", "V1", DIGEST_B).digest)

        listOf(
            "a".repeat(63),
            "a".repeat(65),
            "A".repeat(64),
            "g".repeat(64),
        ).forEach { invalidDigest ->
            assertFailsWith<IllegalArgumentException> {
                WorkflowSubjectSnapshot.of(subject, "R1", invalidDigest)
            }
            assertFailsWith<IllegalArgumentException> {
                WorkflowDefinitionRef.of("leave", "V1", invalidDigest)
            }
        }
    }

    @Test
    fun `structured workflow hashes use typed framing explicit domains and exact UTF-8 bytes`() {
        val canonicalText = "审批|v1|文档"
        assertEquals(
            "7a75367644a51c8bd2ef9f715468261acd226c815ff95edf122d485dc54e6639",
            WorkflowContractSupport.digest("flowweft-workflow-contract-test-v1").text(canonicalText).finish(),
        )
        assertNotEquals(
            WorkflowContractSupport.digest("flowweft-workflow-contract-test-v1").text("caf\u00e9").finish(),
            WorkflowContractSupport.digest("flowweft-workflow-contract-test-v1").text("cafe\u0301").finish(),
        )
        assertFailsWith<IllegalArgumentException> {
            WorkflowContractSupport.digest("fileweft-workflow-contract-test-v1")
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowContractSupport.digest("flowweft-workflow-contract-test-v1")
                .text(String(charArrayOf('\uD800')))
        }
    }

    @Test
    fun `optimistic references reject negative versions and redact log output`() {
        assertFailsWith<IllegalArgumentException> { WorkflowInstanceRef.of("instance", -1L) }
        assertFailsWith<IllegalArgumentException> { WorkflowWorkItemRef.of("work-item", -1L) }

        val values = listOf(
            WorkflowPrincipalRef.of("USER", "secret-principal") to listOf("secret-principal"),
            WorkflowSubjectRef.of("DOCUMENT", "secret-subject") to listOf("secret-subject"),
            WorkflowSubjectSnapshot.of(
                WorkflowSubjectRef.of("DOCUMENT", "secret-subject"),
                "secret-revision",
                DIGEST_A,
            ) to listOf("secret-subject", "secret-revision", DIGEST_A),
            WorkflowDefinitionRef.of("secret-definition", "secret-version", DIGEST_B) to
                listOf("secret-definition", "secret-version", DIGEST_B),
            WorkflowInstanceRef.of("secret-instance", 1L) to listOf("secret-instance"),
            WorkflowWorkItemRef.of("secret-work-item", 1L) to listOf("secret-work-item"),
        )

        values.forEach { (value, secrets) ->
            assertEquals("${value.javaClass.simpleName}(<redacted>)", value.toString())
            secrets.forEach { secret -> assertFalse(value.toString().contains(secret)) }
        }
    }

    private fun assertEqualValue(first: Any, second: Any) {
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    private companion object {
        const val DIGEST_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val DIGEST_B = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    }
}
