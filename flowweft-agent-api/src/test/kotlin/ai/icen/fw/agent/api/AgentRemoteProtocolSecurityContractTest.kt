package ai.icen.fw.agent.api

import ai.icen.fw.core.id.Identifier
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentRemoteProtocolSecurityContractTest {

    @Test
    fun `reviewed profile pins binding providers descriptor and MCP tool catalog`() {
        val profile = mcpProfile(toolDigest = digest("tool-v1"))
        val changedTool = mcpProfile(toolDigest = digest("tool-v2"))
        val changedProvider = mcpProfile(
            toolDigest = digest("tool-v1"),
            protocolProviderId = ProviderId("transport.other"),
        )

        assertNotEquals(profile.profileDigest, changedTool.profileDigest)
        assertNotEquals(profile.toolCatalogDigest, changedTool.toolCatalogDigest)
        assertNotEquals(profile.profileDigest, changedProvider.profileDigest)
        assertEquals(0, profile.maximumRedirects)
        assertThrows(IllegalArgumentException::class.java) {
            mcpProfile(toolDigest = digest("tool-v1"), maximumRedirects = 1)
        }

        val observation = AgentRemotePeerObservation(
            profile.peerId,
            profile.protocol,
            profile.protocolVersion,
            profile.bindingId,
            profile.descriptorVersion,
            profile.descriptorDigest,
            profile.capabilityDigest,
            profile.toolCatalogDigest,
            profile.securitySchemeDigest,
            100L,
        )
        observation.requireMatches(profile)
        assertThrows(IllegalArgumentException::class.java) {
            AgentRemotePeerObservation(
                profile.peerId,
                profile.protocol,
                profile.protocolVersion,
                AgentRemoteProtocolBindingId.A2A_GRPC,
                profile.descriptorVersion,
                profile.descriptorDigest,
                profile.capabilityDigest,
                profile.toolCatalogDigest,
                profile.securitySchemeDigest,
                100L,
            ).requireMatches(profile)
        }

        val rendered = profile.toString() + profile.credential.toString()
        assertFalse(rendered.contains(profile.resourceUri.host))
        assertFalse(rendered.contains(profile.credential.credentialReference.value))
        assertFalse(rendered.contains(profile.descriptorDigest))
    }

    @Test
    fun `capability cannot be confused across protocol operations`() {
        val profile = mcpInitializationProfile(AgentRemoteProtocolBaselines.MCP_2025_11_25)
        val payload = payload("{}")
        val operation = initializeOperation(profile, payload.digest)

        val request = AgentRemoteProtocolInvocationRequest(
            id("request-1"),
            operation,
            payload,
            AgentRemoteProtocolCapabilities.MCP_INITIALIZE,
            profile.profileDigest,
            AUTHORIZATION_PROVIDER,
            AgentBudget(100, 100, 1, 1, 1_000, 0),
            AgentUsage(),
            "initialize-1",
            20L,
            200L,
            1_000L,
            1_024,
            AgentCancellationToken.NONE,
            null,
        )

        assertEquals(AgentRemoteProtocolCapabilities.MCP_INITIALIZE, request.requiredCapability)
        val reconciliationAuthorization = AgentRemoteAuthorizationRequest(
            id("reconciliation-auth"),
            AUTHORIZATION_PROVIDER,
            AgentRemoteAuthorizationPhase.RECONCILIATION,
            id("original-final-auth"),
            request,
            context(),
            profile,
            profile.resourceUri,
            digest("fresh-network-resolution"),
            profile.credential.bindingDigest,
            0,
            250L,
            300L,
        )
        assertTrue(reconciliationAuthorization.requestedAt > request.deadlineAt)
        assertTrue(reconciliationAuthorization.expiresAt < request.reconciliationDeadlineAt)
        assertThrows(IllegalArgumentException::class.java) {
            AgentRemoteProtocolInvocationRequest(
                id("request-confused"),
                operation,
                payload,
                AgentRemoteProtocolCapabilities.MCP_TOOL_CALL,
                profile.profileDigest,
                AUTHORIZATION_PROVIDER,
                AgentBudget(100, 100, 1, 1, 1_000, 0),
                AgentUsage(),
                "initialize-confused",
                20L,
                200L,
                1_000L,
                1_024,
                AgentCancellationToken.NONE,
                null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRemoteProtocolInvocationRequest(
                id("request-window"),
                operation,
                payload,
                AgentRemoteProtocolCapabilities.MCP_INITIALIZE,
                profile.profileDigest,
                AUTHORIZATION_PROVIDER,
                AgentBudget(100, 100, 1, 1, 1_000, 0),
                AgentUsage(),
                "initialize-window",
                20L,
                200L,
                200L + 31L * 24L * 60L * 60L * 1_000L,
                1_024,
                AgentCancellationToken.NONE,
                null,
            )
        }
    }

    @Test
    fun `A2A cancellation binds parent invocation task and canonical payload`() {
        val payload = payload("{\"task\":\"remote-1\"}")
        val context = context()
        val operation = AgentRemoteOperationBinding(
            context,
            id("run-1"),
            id("step-cancel"),
            A2A_PEER,
            AgentRemoteProtocolKind.A2A,
            AgentRemoteOperationKind.A2A_CANCEL_TASK,
            payload.digest,
            "agent.remote.cancel",
            "remote-task",
            id("remote-resource"),
            "7",
            "cancel delegated task",
            id("cancel-message"),
            payload.digest,
            remoteTaskId = "remote-1",
            parentInvocationId = id("parent-invocation"),
            parentOperationDigest = digest("parent-operation"),
            executorProviderId = LOCAL_PROTOCOL_EXECUTOR,
            executorToolId = ToolId("remote.a2a.cancel"),
        )

        assertEquals(id("parent-invocation"), operation.parentInvocationId)
        assertEquals(payload.digest, operation.messageDigest)
        assertEquals(
            AgentRemoteProtocolCapabilities.A2A_CANCEL_TASK,
            AgentRemoteProtocolCapabilities.requiredFor(
                AgentRemoteProtocolKind.A2A,
                AgentRemoteOperationKind.A2A_CANCEL_TASK,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AgentRemoteOperationBinding(
                context,
                id("run-1"),
                id("step-cancel"),
                A2A_PEER,
                AgentRemoteProtocolKind.A2A,
                AgentRemoteOperationKind.A2A_CANCEL_TASK,
                payload.digest,
                "agent.remote.cancel",
                "remote-task",
                id("remote-resource"),
                "7",
                "cancel delegated task",
                id("cancel-message"),
                digest("changed-payload"),
                remoteTaskId = "remote-1",
                parentOperationDigest = digest("parent-operation"),
                executorProviderId = LOCAL_PROTOCOL_EXECUTOR,
                executorToolId = ToolId("remote.a2a.cancel"),
            )
        }
    }

    @Test
    fun `A2A task reads bind owner context capability visibility and one-time execution`() {
        val context = context()
        val getPayload = payload("{\"historyLength\":0,\"id\":\"remote-1\",\"tenant\":\"remote-tenant-a\"}")
        val get = AgentRemoteOperationBinding(
            context,
            id("run-1"),
            id("step-get"),
            A2A_PEER,
            AgentRemoteProtocolKind.A2A,
            AgentRemoteOperationKind.A2A_GET_TASK,
            getPayload.digest,
            "agent.remote.task.get",
            "remote-task",
            id("remote-resource"),
            "revision-7",
            "read delegated task",
            remoteTaskId = "remote-1",
            parentInvocationId = id("parent-invocation"),
            parentOperationDigest = digest("parent-operation"),
            executorProviderId = LOCAL_PROTOCOL_EXECUTOR,
            executorToolId = ToolId("remote.a2a.get-task"),
            a2aTenantRoutingId = "remote-tenant-a",
            a2aContextId = "context-owned-a",
        )
        val listPayload = payload(
            "{\"contextId\":\"context-owned-a\",\"historyLength\":0,\"includeArtifacts\":false," +
                "\"pageSize\":25,\"tenant\":\"remote-tenant-a\"}",
        )
        val list = AgentRemoteOperationBinding(
            context,
            id("run-1"),
            id("step-list"),
            A2A_PEER,
            AgentRemoteProtocolKind.A2A,
            AgentRemoteOperationKind.A2A_LIST_TASKS,
            listPayload.digest,
            "agent.remote.task.list",
            "remote-task-context",
            id("context-owned-a"),
            "revision-9",
            "list delegated tasks in owned context",
            parentInvocationId = id("parent-invocation"),
            parentOperationDigest = digest("parent-operation"),
            executorProviderId = LOCAL_PROTOCOL_EXECUTOR,
            executorToolId = ToolId("remote.a2a.list-tasks"),
            a2aTenantRoutingId = "remote-tenant-a",
            a2aContextId = "context-owned-a",
            a2aMaximumVisibleTasks = 100,
        )

        assertEquals(AgentRemoteProtocolCapabilities.A2A_GET_TASK, AgentRemoteProtocolCapabilities.requiredFor(
            AgentRemoteProtocolKind.A2A,
            AgentRemoteOperationKind.A2A_GET_TASK,
        ))
        assertEquals(AgentRemoteProtocolCapabilities.A2A_LIST_TASKS, AgentRemoteProtocolCapabilities.requiredFor(
            AgentRemoteProtocolKind.A2A,
            AgentRemoteOperationKind.A2A_LIST_TASKS,
        ))
        assertNotEquals(get.bindingDigest, list.bindingDigest)
        assertThrows(IllegalArgumentException::class.java) {
            AgentRemoteProtocolInvocationRequest(
                id("get-without-one-time-context"),
                get,
                getPayload,
                AgentRemoteProtocolCapabilities.A2A_GET_TASK,
                digest("approved-profile"),
                AUTHORIZATION_PROVIDER,
                AgentBudget(100, 100, 1, 1, 1_000, 0),
                AgentUsage(),
                "get-idempotency",
                20L,
                200L,
                1_000L,
                1_024,
                AgentCancellationToken.NONE,
                null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRemoteOperationBinding(
                context,
                id("run-1"),
                id("step-list-zero"),
                A2A_PEER,
                AgentRemoteProtocolKind.A2A,
                AgentRemoteOperationKind.A2A_LIST_TASKS,
                listPayload.digest,
                "agent.remote.task.list",
                "remote-task-context",
                id("context-owned-a"),
                "revision-9",
                "list delegated tasks in owned context",
                parentInvocationId = id("parent-invocation"),
                parentOperationDigest = digest("parent-operation"),
                executorProviderId = LOCAL_PROTOCOL_EXECUTOR,
                executorToolId = ToolId("remote.a2a.list-tasks"),
                a2aTenantRoutingId = "remote-tenant-a",
                a2aContextId = "context-owned-a",
                a2aMaximumVisibleTasks = 0,
            )
        }
    }

    @Test
    fun `MCP tool catalog rejects identity descriptor and argument confusion`() {
        val approvedDigest = digest("tool-v1")
        val profile = mcpProfile(approvedDigest)
        val payload = payload("{\"documentId\":\"doc-1\"}")
        val approved = mcpToolOperation(profile, payload.digest, ToolId("document.read"), approvedDigest)
        profile.requireOperation(approved)

        assertThrows(IllegalArgumentException::class.java) {
            AgentRemoteProtocolInvocationRequest(
                id("tool-request-without-context"),
                approved,
                payload,
                AgentRemoteProtocolCapabilities.MCP_TOOL_CALL,
                profile.profileDigest,
                AUTHORIZATION_PROVIDER,
                AgentBudget(100, 100, 1, 1, 1_000, 0),
                AgentUsage(),
                "tool-idempotency",
                20L,
                200L,
                1_000L,
                1_024,
                AgentCancellationToken.NONE,
                null,
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            profile.requireOperation(mcpToolOperation(
                profile,
                payload.digest,
                ToolId("document.delete"),
                approvedDigest,
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            profile.requireOperation(mcpToolOperation(
                profile,
                payload.digest,
                ToolId("document.read"),
                digest("rug-pulled-descriptor"),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRemoteOperationBinding(
                context(),
                id("run-1"),
                id("step-tool"),
                profile.peerId,
                profile.protocol,
                AgentRemoteOperationKind.MCP_TOOL_CALL,
                payload.digest,
                "document.read",
                "document",
                id("doc-1"),
                "1",
                "read document",
                toolProviderId = MCP_PEER,
                toolId = ToolId("document.read"),
                toolDescriptorDigest = approvedDigest,
                toolArgumentsDigest = digest("changed-arguments"),
                executorProviderId = LOCAL_PROTOCOL_EXECUTOR,
                executorToolId = ToolId("remote.mcp.call"),
            )
        }
    }

    @Test
    fun `MCP Tasks require profile and tool level opt in`() {
        assertThrows(IllegalArgumentException::class.java) {
            mcpProfile(
                digest("task-tool"),
                taskSupport = AgentRemoteTaskSupport.OPTIONAL,
                enableTasks = false,
            )
        }

        val profile = mcpProfile(
            digest("task-tool"),
            taskSupport = AgentRemoteTaskSupport.OPTIONAL,
            enableTasks = true,
        )
        assertTrue(AgentRemoteProtocolCapabilities.MCP_TASKS_EXPERIMENTAL in profile.capabilities)
        assertEquals(AgentRemoteTaskSupport.OPTIONAL, profile.toolBindings.single().taskSupport)
    }

    @Test
    fun `network contracts reject private reserved and ambiguous destinations`() {
        assertFalse(AgentRemoteResolvedAddress(byteArrayOf(10, 0, 0, 1)).isPubliclyRoutable())
        assertFalse(AgentRemoteResolvedAddress(byteArrayOf(127, 0, 0, 1)).isPubliclyRoutable())
        assertFalse(AgentRemoteResolvedAddress(byteArrayOf(100, 64, 0, 1)).isPubliclyRoutable())
        assertFalse(AgentRemoteResolvedAddress(ipv6("fc000000000000000000000000000001")).isPubliclyRoutable())
        assertFalse(AgentRemoteResolvedAddress(ipv6("20010db8000000000000000000000001")).isPubliclyRoutable())
        assertFalse(AgentRemoteResolvedAddress(ipv6("00000000000000000000ffff0a000001")).isPubliclyRoutable())
        assertTrue(AgentRemoteResolvedAddress(byteArrayOf(8, 8, 8, 8)).isPubliclyRoutable())
        assertTrue(AgentRemoteResolvedAddress(ipv6("20014860486000000000000000008888")).isPubliclyRoutable())

        assertThrows(IllegalArgumentException::class.java) {
            credential(URI("http://mcp.example/protocol"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            credential(URI("https://user@mcp.example/protocol"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            credential(URI("https://mcp.example/protocol?token=forbidden"))
        }
    }

    private fun mcpInitializationProfile(version: String): AgentRemotePeerProfile {
        val uri = URI("https://mcp.example/protocol")
        return AgentRemotePeerProfile(
            MCP_PEER,
            AgentRemoteProtocolKind.MCP,
            version,
            AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP,
            TRANSPORT_PROVIDER,
            RECONCILER_PROVIDER,
            uri,
            "server-v1",
            digest("server-descriptor"),
            listOf(AgentRemoteProtocolCapabilities.MCP_INITIALIZE),
            digest("security-schemes"),
            digest("tls-identity"),
            credential(uri),
            "profile-1",
        )
    }

    private fun mcpProfile(
        toolDigest: String,
        protocolProviderId: ProviderId = TRANSPORT_PROVIDER,
        maximumRedirects: Int = 0,
        taskSupport: AgentRemoteTaskSupport = AgentRemoteTaskSupport.NONE,
        enableTasks: Boolean = false,
    ): AgentRemotePeerProfile {
        val uri = URI("https://mcp.example/protocol")
        return AgentRemotePeerProfile(
            MCP_PEER,
            AgentRemoteProtocolKind.MCP,
            AgentRemoteProtocolBaselines.MCP_2025_11_25,
            AgentRemoteProtocolBindingId.MCP_STREAMABLE_HTTP,
            protocolProviderId,
            RECONCILER_PROVIDER,
            uri,
            "server-v1",
            digest("server-descriptor"),
            if (enableTasks) {
                listOf(
                    AgentRemoteProtocolCapabilities.MCP_TOOL_CALL,
                    AgentRemoteProtocolCapabilities.MCP_TASKS_EXPERIMENTAL,
                )
            } else {
                listOf(AgentRemoteProtocolCapabilities.MCP_TOOL_CALL)
            },
            digest("security-schemes"),
            digest("tls-identity"),
            credential(uri),
            "profile-1",
            maximumRedirects,
            listOf(AgentRemoteToolBinding(MCP_PEER, ToolId("document.read"), toolDigest, taskSupport)),
        )
    }

    private fun initializeOperation(profile: AgentRemotePeerProfile, payloadDigest: String) =
        AgentRemoteOperationBinding(
            context(),
            id("run-1"),
            id("step-initialize"),
            profile.peerId,
            profile.protocol,
            AgentRemoteOperationKind.INITIALIZE,
            payloadDigest,
            "agent.remote.initialize",
            "remote-peer",
            id("peer-resource"),
            "1",
            "initialize reviewed peer",
        )

    private fun mcpToolOperation(
        profile: AgentRemotePeerProfile,
        payloadDigest: String,
        toolId: ToolId,
        descriptorDigest: String,
    ) = AgentRemoteOperationBinding(
        context(),
        id("run-1"),
        id("step-tool"),
        profile.peerId,
        profile.protocol,
        AgentRemoteOperationKind.MCP_TOOL_CALL,
        payloadDigest,
        "document.read",
        "document",
        id("doc-1"),
        "1",
        "read document",
        toolProviderId = MCP_PEER,
        toolId = toolId,
        toolDescriptorDigest = descriptorDigest,
        toolArgumentsDigest = payloadDigest,
        executorProviderId = LOCAL_PROTOCOL_EXECUTOR,
        executorToolId = ToolId("remote.mcp.call"),
    )

    private fun context() = AgentRunContext(id("tenant-a"), id("principal-a"), "USER", id("caller-request"), 10L)

    private fun credential(uri: URI) = AgentRemoteCredentialBinding(
        id("credential-reference"),
        MCP_PEER,
        AgentRemoteAuthenticationScheme.OAUTH2_BEARER,
        uri,
        listOf("mcp.invoke"),
        "credential-revision-1",
    )

    private fun payload(value: String): AgentRemoteProtocolPayload {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        return AgentRemoteProtocolPayload("application/json", bytes, digest(bytes))
    }

    private fun digest(value: String): String = digest(value.toByteArray(StandardCharsets.UTF_8))

    private fun digest(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ipv6(hex: String): ByteArray = hex.chunked(2).map { byte -> byte.toInt(16).toByte() }.toByteArray()

    private fun id(value: String) = Identifier(value)

    private companion object {
        val MCP_PEER = ProviderId("peer.mcp")
        val A2A_PEER = ProviderId("peer.a2a")
        val TRANSPORT_PROVIDER = ProviderId("transport.mcp")
        val RECONCILER_PROVIDER = ProviderId("reconciler.mcp")
        val AUTHORIZATION_PROVIDER = ProviderId("authorization.remote")
        val LOCAL_PROTOCOL_EXECUTOR = ProviderId("executor.remote-protocol")
    }
}
