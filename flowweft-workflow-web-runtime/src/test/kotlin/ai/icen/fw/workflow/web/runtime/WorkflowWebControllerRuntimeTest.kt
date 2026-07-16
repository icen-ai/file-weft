package ai.icen.fw.workflow.web.runtime

import ai.icen.fw.workflow.web.api.WorkflowWebApplicationResult
import ai.icen.fw.workflow.web.api.WorkflowWebErrorCodes
import ai.icen.fw.workflow.web.api.WorkflowWebRoute
import ai.icen.fw.workflow.web.api.WorkflowWebTrustedContext
import ai.icen.fw.workflow.web.api.WorkflowWebTrustedContextProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowWebControllerRuntimeTest {
    @Test
    fun `write binds trusted context and exact mutation headers`() {
        val runtime = WorkflowWebControllerRuntime(WorkflowWebTrustedContextProvider { context() })
        var called = false
        val response = runtime.executeWrite(
            route("startWorkflowInstance"),
            WorkflowWebRequestMetadata.of(
                "POST",
                "application/json; charset=UTF-8",
                "application/json",
                24,
                listOf("idem-1"),
                listOf("\"fw-7\""),
            ),
            WorkflowWebWriteInvocation { trusted, preconditions ->
                called = true
                assertEquals("tenant-a", trusted.tenantId)
                assertEquals(7L, preconditions.versionTag.expectedVersion)
                WorkflowWebApplicationResult.success("receipt")
            },
        )

        assertTrue(called)
        assertEquals(200, response.status)
        assertEquals("receipt", response.body.data)
        assertEquals("private, no-store", response.headers["Cache-Control"])
    }

    @Test
    fun `duplicate mutation headers fail before authentication and application`() {
        var authenticationCalls = 0
        var applicationCalls = 0
        val runtime = WorkflowWebControllerRuntime(WorkflowWebTrustedContextProvider {
            authenticationCalls += 1
            context()
        })
        val response = runtime.executeWrite(
            route("startWorkflowInstance"),
            WorkflowWebRequestMetadata.of(
                "POST",
                "application/json",
                null,
                0,
                listOf("idem-1", "idem-2"),
                listOf("\"fw-0\""),
            ),
            WorkflowWebWriteInvocation<String> { _, _ ->
                applicationCalls += 1
                WorkflowWebApplicationResult.success("impossible")
            },
        )

        assertEquals(400, response.status)
        assertEquals(0, authenticationCalls)
        assertEquals(0, applicationCalls)
    }

    @Test
    fun `hidden and unsupported results keep stable public projections`() {
        val runtime = WorkflowWebControllerRuntime(WorkflowWebTrustedContextProvider { context() })
        val metadata = WorkflowWebRequestMetadata.of("GET", accept = "*/*")
        val hidden = runtime.executeRead(
            route("getWorkflowInstance"),
            metadata,
            WorkflowWebReadInvocation<String> { WorkflowWebApplicationResult.hidden() },
        )
        val unsupported = runtime.executeRead(
            route("getWorkflowInstance"),
            metadata,
            WorkflowWebReadInvocation<String> { WorkflowWebApplicationResult.unsupported() },
        )

        assertEquals(404, hidden.status)
        assertEquals(WorkflowWebErrorCodes.NOT_FOUND, hidden.body.code)
        assertEquals(503, unsupported.status)
        assertEquals(WorkflowWebErrorCodes.CAPABILITY_UNSUPPORTED, unsupported.body.code)
    }

    @Test
    fun `authentication and application exceptions never escape`() {
        val authFailure = WorkflowWebControllerRuntime(
            WorkflowWebTrustedContextProvider { error("password=secret") },
        ).executeRead(
            route("getWorkflowInstance"),
            WorkflowWebRequestMetadata.of("GET"),
            WorkflowWebReadInvocation<String> { WorkflowWebApplicationResult.success("impossible") },
        )
        val applicationFailure = WorkflowWebControllerRuntime(
            WorkflowWebTrustedContextProvider { context() },
        ).executeRead(
            route("getWorkflowInstance"),
            WorkflowWebRequestMetadata.of("GET"),
            WorkflowWebReadInvocation<String> { error("jdbc password=secret") },
        )

        assertEquals(401, authFailure.status)
        assertEquals(500, applicationFailure.status)
        assertFalse(authFailure.toString().contains("secret"))
        assertFalse(applicationFailure.body.message.contains("jdbc"))
    }

    private fun route(operationId: String): WorkflowWebRoute =
        WorkflowWebRoute.all().single { it.operationId == operationId }

    private fun context(): WorkflowWebTrustedContext = WorkflowWebTrustedContext.authenticated(
        "tenant-a",
        "user",
        "user-1",
        "auth-1",
        "a".repeat(64),
        "trace-1",
    )
}
