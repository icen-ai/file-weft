package ai.icen.fw.agent.workflow

import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentToolRisk
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.workflow.web.api.WorkflowWebApplicationResult
import ai.icen.fw.workflow.web.api.WorkflowWebCapabilitiesDto
import ai.icen.fw.workflow.web.api.WorkflowWebCapabilityApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowWebRoute
import ai.icen.fw.workflow.web.api.WorkflowWebTrustedContext
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkflowAgentPublicToolDirectoryContractTest {
    @Test
    fun `directory deterministically projects every public workflow use case`() {
        val first = WorkflowAgentPublicToolDirectory()
        val second = WorkflowAgentPublicToolDirectory()
        val context = AgentRunContext(
            Identifier("tenant-1"),
            Identifier("principal-1"),
            "USER",
            Identifier("request-1"),
            1L,
        )

        assertEquals(33, first.entries.size)
        assertEquals(WorkflowWebRoute.all().map { route -> route.operationId },
            first.entries.map { entry -> entry.operationId })
        assertEquals(WorkflowAgentUseCaseCategory.values().toSet(),
            first.entries.map { entry -> entry.category }.toSet())
        assertEquals(first.directoryDigest, second.directoryDigest)
        assertEquals(33, first.descriptors(context).descriptors.size)
        assertTrue(first.entries.all { entry ->
            entry.toolDescriptor.providerId == WorkflowAgentPublicToolDirectory.PROVIDER_ID &&
                entry.toolDescriptor.schemaDigest == workflowAgentSha256(entry.toolDescriptor.inputSchema) &&
                WorkflowAgentPublicToolDirectory.CAPABILITY_ID in entry.toolDescriptor.capabilities
        })
        assertTrue(first.entry("publishWorkflowDefinition")!!.confirmationRequired)
        assertTrue(first.entry("decideWorkflowTask")!!.confirmationRequired)
        assertTrue(first.entry("delegateWorkflowTask")!!.confirmationRequired)
        assertTrue(first.entry("addWorkflowTaskSigner")!!.confirmationRequired)
        assertTrue(first.entry("terminateWorkflowInstance")!!.confirmationRequired)
        assertTrue(first.entry("executeWorkflowMigration")!!.confirmationRequired)
        assertEquals(AgentToolRisk.READ_ONLY, first.entry("getWorkflowDoctor")!!.risk)
    }

    @Test
    fun `registry offers only installed public application ports and diagnoses the rest`() {
        val directory = WorkflowAgentPublicToolDirectory()
        val empty = WorkflowAgentPublicApplicationPortRegistry(directory)
        val capabilityPort = object : WorkflowWebCapabilityApplicationPort {
            override fun listCapabilities(
                context: WorkflowWebTrustedContext,
            ): WorkflowWebApplicationResult<WorkflowWebCapabilitiesDto> =
                WorkflowWebApplicationResult.unsupported()
        }
        val partial = WorkflowAgentPublicApplicationPortRegistry(
            directory = directory,
            capabilities = capabilityPort,
        )
        val context = AgentRunContext(
            Identifier("tenant-1"),
            Identifier("principal-1"),
            "USER",
            Identifier("request-1"),
            1L,
        )

        assertEquals(0, empty.descriptors(context).descriptors.size)
        assertEquals(0, empty.snapshot().availableCount)
        assertEquals(33, empty.snapshot().registrations.size)
        assertEquals(1, partial.descriptors(context).descriptors.size)
        assertEquals("listWorkflowCapabilities",
            partial.descriptors(context).descriptors.single().displayName)
        assertEquals(1, partial.snapshot().availableCount)
        assertNotEquals(empty.registryDigest, partial.registryDigest)
        assertFalse(empty.isRegistered(directory.entry("listWorkflowCapabilities")!!.toolId))
        assertTrue(partial.isRegistered(directory.entry("listWorkflowCapabilities")!!.toolId))
    }

    @Test
    fun `canonical public command binds exact payload version purpose and rejects actor injection`() {
        val directory = WorkflowAgentPublicToolDirectory()
        val entry = directory.entry("publishWorkflowDefinition")!!
        val first = WorkflowAgentPublicAuthorizationTarget.decode(directory, entry.toolId, publishArguments())
        val changedPayload = WorkflowAgentPublicAuthorizationTarget.decode(
            directory,
            entry.toolId,
            publishArguments(payload = "{\"changeTicket\":\"ticket-2\"}"),
        )
        val changedPurpose = WorkflowAgentPublicAuthorizationTarget.decode(
            directory,
            entry.toolId,
            publishArguments(purpose = "publish-emergency-draft"),
        )

        assertEquals("workflow.definition.publish", first.action)
        assertEquals("workflow-definition", first.resourceType)
        assertEquals("definition-1", first.resourceId)
        assertEquals(7L, first.command.expectedResourceVersion)
        assertEquals("{\"changeTicket\":\"ticket-1\"}",
            first.command.payload.toString(StandardCharsets.UTF_8))
        assertNotEquals(first.resourceRevision, changedPayload.resourceRevision)
        assertEquals(first.resourceRevision, changedPurpose.resourceRevision)
        assertNotEquals(first.command.commandDigest, changedPurpose.command.commandDigest)

        val canonical = publishArguments().toString(StandardCharsets.UTF_8)
        assertFailsWith<IllegalArgumentException> {
            WorkflowAgentPublicCommand.decode(
                directory,
                entry.toolId,
                canonical.replaceFirst("{", "{ ").toByteArray(StandardCharsets.UTF_8),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowAgentPublicCommand.decode(
                directory,
                entry.toolId,
                canonical.dropLast(1).plus(",\"tenantId\":\"tenant-2\"}")
                    .toByteArray(StandardCharsets.UTF_8),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowAgentPublicCommand.decode(
                directory,
                directory.entry("retireWorkflowDefinition")!!.toolId,
                publishArguments(),
            )
        }
    }

    private fun publishArguments(
        payload: String = "{\"changeTicket\":\"ticket-1\"}",
        purpose: String = "publish-approved-draft",
    ): ByteArray = (
        "{\"applicationContractVersion\":\"flowweft.workflow.web.application.v1\"," +
            "\"executionNonce\":\"nonce-publish-1\",\"expectedResourceVersion\":7," +
            "\"idempotencyKey\":\"idem-publish-1\"," +
            "\"operationId\":\"publishWorkflowDefinition\",\"payload\":$payload," +
            "\"purpose\":\"$purpose\",\"resourceId\":\"definition-1\"," +
            "\"resourceType\":\"workflow-definition\"}"
        ).toByteArray(StandardCharsets.UTF_8)
}
