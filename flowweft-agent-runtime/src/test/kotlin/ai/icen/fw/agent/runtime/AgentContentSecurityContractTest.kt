package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentCitation
import ai.icen.fw.agent.api.AgentCitationContentBlock
import ai.icen.fw.agent.api.AgentContentBlock
import ai.icen.fw.agent.api.AgentContentOrigin
import ai.icen.fw.agent.api.AgentMessage
import ai.icen.fw.agent.api.AgentMessageRole
import ai.icen.fw.agent.api.AgentTextContentBlock
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class AgentContentSecurityContractTest {

    @Test
    fun `decision binds exact provider operation acl state and content without logging payload`() {
        val first = request("secret-token-never-log", hash("provider-v1"), 1)
        val drifted = request("secret-token-never-log", hash("provider-v2"), 1)
        val decision = AgentContentSecurityDecision.allow(
            Identifier("decision-1"),
            ProviderId("content-policy.local"),
            first,
            "policy-v1",
            10,
            100,
        )

        decision.requireAllowedFor(first, 10)
        assertNotEquals(first.bindingDigest, drifted.bindingDigest)
        assertFailsWith<IllegalArgumentException> { decision.requireAllowedFor(drifted, 10) }
        assertFalse(first.toString().contains("secret-token-never-log"))
        assertFalse(decision.toString().contains("secret-token-never-log"))
    }

    @Test
    fun `mutable extension cannot change after content policy approval`() {
        class MutableBlock(var value: String) : AgentContentBlock {
            override fun kind(): String = "extension.mutable"
            override fun origin(): AgentContentOrigin = AgentContentOrigin.USER
            override fun bindingDigest(): String = hash(value)
        }
        val block = MutableBlock("approved")
        val message = AgentMessage(
            Identifier("message-mutable"),
            AgentMessageRole.USER,
            listOf(block),
            1,
        )
        val request = request(message, hash("provider-v1"), 1)
        val decision = AgentContentSecurityDecision.allow(
            Identifier("decision-mutable"),
            ProviderId("content-policy.local"),
            request,
            "policy-v1",
            10,
            100,
        )
        block.value = "mutated-secret"

        assertFailsWith<IllegalArgumentException> { decision.requireAllowedFor(request, 10) }
    }

    @Test
    fun `citation content fails closed until policy affirms every exact lineage digest`() {
        val citation = AgentCitationContentBlock(
            AgentCitation(
                Identifier("citation-1"),
                Identifier("tenant-1"),
                Identifier("document-1"),
                Identifier("version-1"),
                Identifier("evidence-1"),
                hash("content-1"),
            ),
        )
        val message = AgentMessage(
            Identifier("message-citation"),
            AgentMessageRole.CONTEXT,
            listOf(citation),
            1,
        )
        val request = request(message, hash("provider-v1"), 1)
        val unverified = AgentContentSecurityDecision.allow(
            Identifier("decision-unverified"),
            ProviderId("content-policy.local"),
            request,
            "policy-v1",
            10,
            100,
        )
        val verified = AgentContentSecurityDecision.allow(
            Identifier("decision-verified"),
            ProviderId("content-policy.local"),
            request,
            "policy-v1",
            10,
            100,
            request.citationDigests,
        )

        assertFailsWith<IllegalArgumentException> { unverified.requireAllowedFor(request, 10) }
        verified.requireAllowedFor(request, 10)
    }

    private fun request(content: String, providerBinding: String, version: Long): AgentContentSecurityRequest = request(
        AgentMessage(
            Identifier("message-$version-${content.length}"),
            AgentMessageRole.USER,
            listOf(AgentTextContentBlock(AgentContentOrigin.USER, content)),
            1,
        ),
        providerBinding,
        version,
    )

    private fun request(
        message: AgentMessage,
        providerBinding: String,
        version: Long,
    ): AgentContentSecurityRequest = AgentContentSecurityRequest(
        Identifier("content-request-$version-${providerBinding.substring(0, 8)}"),
        Identifier("tenant-1"),
        Identifier("principal-1"),
        "USER",
        Identifier("run-1"),
        AgentCapabilityId("agent.answer"),
        version,
        AgentContentSecurityBoundary.MODEL_INPUT,
        ProviderId("model.local"),
        providerBinding,
        hash("operation-$version"),
        hash("state-$version"),
        "authorization-v1",
        listOf(message),
        emptyList(),
        emptyList(),
        10,
        100,
    )

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
