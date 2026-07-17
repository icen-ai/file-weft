package ai.icen.fw.workflow.web.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowWebContractTest {

    @Test
    fun `publishes a complete versioned route catalog with strict mutation headers`() {
        val routes = WorkflowWebRoute.all()

        assertTrue(routes.isNotEmpty())
        assertTrue(routes.all { it.pathTemplate.startsWith("/flowweft/v1/") })
        assertTrue(routes.filter { it.method != "GET" }.all {
            it.idempotencyRequired && it.ifMatchRequired
        })
        assertTrue(routes.filter { it.method == "GET" }.none {
            it.idempotencyRequired || it.ifMatchRequired
        })
        assertEquals(routes.size, routes.map { it.operationId }.toSet().size)
        assertEquals(routes.size, routes.map { it.method + " " + it.pathTemplate }.toSet().size)

        val operations = routes.map { it.operationId }.toSet()
        assertTrue(
            setOf(
                "putWorkflowDefinitionDraft",
                "publishWorkflowDefinition",
                "retireWorkflowDefinition",
                "startWorkflowInstance",
                "suspendWorkflowInstance",
                "claimWorkflowTask",
                "decideWorkflowTask",
                "delegateWorkflowTask",
                "addWorkflowTaskSigner",
                "returnWorkflowTask",
                "createWorkflowComment",
                "submitWorkflowTaskForm",
                "listWorkflowHistory",
                "repairWorkflowIncident",
                "dryRunWorkflowMigration",
                "executeWorkflowMigration",
                "getWorkflowDoctor",
            ).all(operations::contains),
        )
    }

    @Test
    fun `requires one strong version tag and one bounded idempotency key`() {
        val preconditions = WorkflowWebWritePreconditions.parse("command-1", "\"fw-42\"")

        assertEquals(42L, preconditions.versionTag.expectedVersion)
        assertEquals("\"fw-42\"", preconditions.versionTag.toHeaderValue())
        assertEquals("WorkflowWebWritePreconditions(<redacted>)", preconditions.toString())
        assertThrows<IllegalArgumentException> {
            WorkflowWebWritePreconditions.parse(" command", "\"fw-1\"")
        }
        assertThrows<IllegalArgumentException> {
            WorkflowWebWritePreconditions.parse("command", "W/\"fw-1\"")
        }
        assertThrows<IllegalArgumentException> {
            WorkflowWebWritePreconditions.parse("command", "\"fw-1\", \"fw-2\"")
        }
    }

    @Test
    fun `keeps tenant and authenticated actor out of untrusted command DTOs`() {
        val requestTypes = listOf(
            WorkflowDefinitionDraftCommand::class.java,
            WorkflowDefinitionLifecycleCommand::class.java,
            WorkflowInstanceStartCommand::class.java,
            WorkflowInstanceControlCommand::class.java,
            WorkflowTaskClaimCommand::class.java,
            WorkflowTaskDecisionCommand::class.java,
            WorkflowTaskDelegateCommand::class.java,
            WorkflowTaskAddSignCommand::class.java,
            WorkflowTaskReturnCommand::class.java,
            WorkflowFormSubmissionCommand::class.java,
            WorkflowCommentDocumentCommand::class.java,
            WorkflowIncidentActionCommand::class.java,
            WorkflowMigrationCommand::class.java,
        )
        val forbidden = setOf(
            "tenant",
            "tenantId",
            "actor",
            "actorId",
            "userId",
            "operatorId",
            "authorizationRevision",
            "authenticationId",
        )

        assertTrue(requestTypes.flatMap { it.declaredFields.asList() }.none { it.name in forbidden })

        val context = WorkflowWebTrustedContext.authenticated(
            "tenant-1",
            "USER",
            "user-1",
            "authentication-1",
            digest('a'),
        )
        assertEquals("tenant-1", context.tenantId)
        assertEquals("WorkflowWebTrustedContext(<redacted>)", context.toString())
    }

    @Test
    fun `distinguishes hidden and unsupported resources from empty success`() {
        val hidden = WorkflowWebApplicationResult.hidden<String>()
        val unsupported = WorkflowWebApplicationResult.unsupported<String>()
        val success = WorkflowWebApplicationResult.success("value", replayed = true)

        assertEquals(WorkflowWebErrorCodes.NOT_FOUND, hidden.code)
        assertNull(hidden.value)
        assertEquals(404, WorkflowWebHttpStatusPolicy.statusFor(hidden.code))
        assertEquals(WorkflowWebErrorCodes.CAPABILITY_UNSUPPORTED, unsupported.code)
        assertEquals(503, WorkflowWebHttpStatusPolicy.statusFor(unsupported.code))
        assertEquals("value", success.value)
        assertTrue(success.replayed)
        assertThrows<IllegalArgumentException> {
            WorkflowWebApplicationResult.failure<String>(WorkflowWebErrorCodes.OK)
        }
    }

    @Test
    fun `bounds definition form comment migration and page payloads`() {
        val draft = WorkflowDefinitionDraftCommand(
            "leave",
            "1.0",
            "请假流程",
            "flowweft-neutral",
            "1",
            "{\"nodes\":[]}",
            digest('b'),
        )
        val pageSource = mutableListOf("one")
        val page = WorkflowWebPage(pageSource, "opaque")
        pageSource.clear()

        assertEquals("请假流程", draft.title)
        assertEquals(listOf("one"), page.items)
        assertThrows<UnsupportedOperationException> { (page.items as MutableList<String>).clear() }
        assertThrows<IllegalArgumentException> { WorkflowWebPageQuery(limit = 201) }
        assertThrows<IllegalArgumentException> {
            WorkflowDefinitionDraftCommand(
                "leave",
                "1",
                "Leave",
                "neutral",
                "1",
                "x".repeat(WorkflowDefinitionDraftCommand.MAX_DEFINITION_SOURCE_BYTES + 1),
                digest('b'),
            )
        }
        assertThrows<IllegalArgumentException> {
            WorkflowCommentDocumentCommand(emptyList())
        }
        assertThrows<IllegalArgumentException> {
            WorkflowTaskAddSignCommand(emptyList(), "AFTER", "REVIEW")
        }
        assertThrows<IllegalArgumentException> {
            WorkflowMigrationCommand(
                "source",
                "1",
                "target",
                "2",
                emptyList(),
                emptyList(),
            )
        }
    }

    @Test
    fun `uses structured comments and safe doctor projections`() {
        val comment = WorkflowCommentDocumentCommand(
            listOf(
                WorkflowCommentTokenCommand.text("请复核 <script> 作为普通文本"),
                WorkflowCommentTokenCommand.mention(WorkflowPrincipalTargetCommand("USER", "reviewer-1")),
            ),
        )
        val mention = WorkflowCommentTokenDto.mention("USER", "reviewer-1", "审核人")
        val doctor = WorkflowDoctorReportDto(
            "WARNING",
            listOf(WorkflowDoctorCheckDto("JOB_QUEUE", "WARNING", "WORKFLOW_JOB_LAG", 3, "LAG_1M_5M", "RETRY_WORKER")),
            100,
        )

        assertEquals(2, comment.tokens.size)
        assertEquals("审核人", mention.displayNameSnapshot)
        assertEquals(3L, doctor.checks.single().affectedCount)
        val doctorFields = WorkflowDoctorCheckDto::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(setOf("evidence", "exception", "stackTrace", "endpoint", "payload", "leaseToken")
            .intersect(doctorFields).isEmpty())
        val errorFields = WorkflowWebError::class.java.declaredFields.map { it.name }.toSet()
        assertTrue(setOf("attributes", "cause", "stackTrace").intersect(errorFields).isEmpty())
    }

    @Test
    fun `keeps unified response failures internally consistent`() {
        val success = WorkflowWebResponse.success("ok", "trace-1")
        val failure = WorkflowWebResponse.failure<String>(
            WorkflowWebError(WorkflowWebErrorCodes.NOT_FOUND, "Resource was not found."),
            "trace-2",
        )

        assertTrue(success.isSuccess())
        assertFalse(success.isFailure())
        assertTrue(failure.isFailure())
        assertNull(failure.data)
        assertEquals("trace-2", failure.traceId)
    }

    private fun digest(character: Char): String = character.toString().repeat(64)
}
