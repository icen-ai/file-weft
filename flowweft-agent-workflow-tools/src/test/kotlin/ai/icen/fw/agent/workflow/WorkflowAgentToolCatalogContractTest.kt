package ai.icen.fw.agent.workflow

import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkflowAgentToolCatalogContractTest {
    @Test
    fun `catalog is deterministic complete and descriptor bound`() {
        val first = WorkflowAgentToolCatalog()
        val second = WorkflowAgentToolCatalog()
        val context = AgentRunContext(
            Identifier("tenant-1"),
            Identifier("principal-1"),
            "USER",
            Identifier("request-1"),
            1L,
        )
        val catalog = first.descriptors(context)

        assertEquals(17, catalog.descriptors.size)
        assertEquals(17, catalog.descriptors.map { it.toolId }.toSet().size)
        assertEquals(first.catalogDigest, second.catalogDigest)
        assertEquals(WorkflowAgentToolCatalog.CATALOG_DIGEST_V1, first.catalogDigest)
        assertTrue(catalog.descriptors.all { descriptor ->
            descriptor.providerId == WorkflowAgentToolCatalog.PROVIDER_ID &&
                WorkflowAgentToolCatalog.CAPABILITY_ID in descriptor.capabilities &&
                descriptor.schemaDigest == workflowAgentSha256(descriptor.inputSchema)
        })
        assertTrue(first.operation(WorkflowAgentOperation.TERMINATE_INSTANCE.toolId)!!.confirmationRequired)
        assertTrue(first.operation(WorkflowAgentOperation.ADD_SIGN_HUMAN_TASK.toolId)!!.confirmationRequired)
        assertTrue(first.operation(WorkflowAgentOperation.REPAIR_INCIDENT.toolId)!!.confirmationRequired)
    }

    @Test
    fun `canonical command fixes action resource versions purpose and exact payload`() {
        val arguments = definitionArguments()
        val target = WorkflowAgentAuthorizationTarget.decode(
            WorkflowAgentOperation.PUBLISH_DEFINITION.toolId,
            arguments,
        )

        assertEquals("workflow.definition.publish", target.action)
        assertEquals("workflow-definition", target.resourceType)
        assertEquals("definition-1", target.resourceId)
        assertEquals("publish-approved-draft", target.purpose)
        assertEquals("idem-publish-1", target.idempotencyKey)
        assertTrue(target.confirmationRequired)
        assertEquals(64, target.resourceRevision.length)
        assertEquals(7L, target.command.expectedDefinitionStateVersion)
        assertEquals("{\"changeTicket\":\"ticket-1\"}", target.command.payload.toString(StandardCharsets.UTF_8))

        val changedVersion = definitionArguments(expectedDefinitionStateVersion = 8L)
        val changedTarget = WorkflowAgentAuthorizationTarget.decode(
            WorkflowAgentOperation.PUBLISH_DEFINITION.toolId,
            changedVersion,
        )
        assertNotEquals(target.resourceRevision, changedTarget.resourceRevision)
        assertNotEquals(target.command.argumentsDigest, changedTarget.command.argumentsDigest)
    }

    @Test
    fun `non canonical unknown and cross resource commands fail closed`() {
        val canonical = definitionArguments().toString(StandardCharsets.UTF_8)
        assertFailsWith<IllegalArgumentException> {
            WorkflowAgentCommand.decode(
                WorkflowAgentOperation.PUBLISH_DEFINITION.toolId,
                canonical.replaceFirst("{", "{ ").toByteArray(StandardCharsets.UTF_8),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowAgentCommand.decode(
                WorkflowAgentOperation.RETIRE_DEFINITION.toolId,
                definitionArguments(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowAgentCommand.decode(
                WorkflowAgentOperation.PUBLISH_DEFINITION.toolId,
                definitionArguments(resourceId = "definition-2"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowAgentCommand.decode(
                WorkflowAgentOperation.PUBLISH_DEFINITION.toolId,
                canonical.dropLast(1).plus(",\"tenantId\":\"tenant-2\"}")
                    .toByteArray(StandardCharsets.UTF_8),
            )
        }
    }

    private fun definitionArguments(
        resourceId: String = "definition-1",
        expectedDefinitionStateVersion: Long = 7L,
    ): ByteArray = (
        "{\"definitionDigest\":\"${"a".repeat(64)}\"," +
            "\"definitionId\":\"definition-1\",\"definitionVersion\":\"2026.07.1\"," +
            "\"executionNonce\":\"nonce-publish-1\"," +
            "\"expectedDefinitionStateVersion\":$expectedDefinitionStateVersion," +
            "\"expectedIncidentVersion\":0,\"expectedInstanceVersion\":0," +
            "\"expectedWorkItemVersion\":0,\"idempotencyKey\":\"idem-publish-1\"," +
            "\"incidentId\":\"-\",\"instanceId\":\"-\"," +
            "\"operation\":\"workflow.definition.publish\"," +
            "\"payload\":{\"changeTicket\":\"ticket-1\"}," +
            "\"purpose\":\"publish-approved-draft\",\"resourceId\":\"$resourceId\"," +
            "\"resourceType\":\"workflow-definition\",\"workItemId\":\"-\"}"
        ).toByteArray(StandardCharsets.UTF_8)
}
