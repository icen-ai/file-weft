package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletionStage

enum class AgentProtocolExecutionStatus {
    SAFE_COMPLETION,
    BLOCKED,
    FAILED_CLOSED,
    VIOLATION,
}

enum class AgentProtocolCancellationOutcome {
    NOT_APPLICABLE,
    REJECTED_BEFORE_DISPATCH,
    CONFIRMED,
    REMOTE_REJECTED,
    OUTCOME_UNKNOWN,
    LOCALLY_ASSUMED_CANCELLED,
}

/** Safe protocol evidence; wire payloads, tokens, messages and tool arguments are never returned. */
class AgentProtocolConformanceResult(
    scenarioId: Identifier,
    scenarioBindingDigest: String,
    val status: AgentProtocolExecutionStatus,
    val effectivePeerId: ProviderId?,
    negotiatedVersion: String?,
    negotiatedProfileDigest: String?,
    negotiatedCapabilityDigest: String?,
    credentialReferenceUsed: Identifier?,
    val tlsVerified: Boolean,
    val outboundProtocolDispatchCount: Int,
    val insecureTransportCount: Int,
    val privateAddressContactCount: Int,
    val unapprovedRedirectFollowCount: Int,
    val credentialCrossPeerDeliveryCount: Int,
    val credentialExposureCount: Int,
    val descriptorDriftAcceptedCount: Int,
    val unknownVersionAcceptedCount: Int,
    val unknownCapabilityAcceptedCount: Int,
    val messageOrToolIdentityMismatchAcceptedCount: Int,
    val cancellationOutcome: AgentProtocolCancellationOutcome,
    evidenceDigest: String,
    safeFailureCode: String?,
    val completedAt: Long,
) {
    val scenarioId: Identifier = conformanceIdentifier(scenarioId, "Protocol result scenario is invalid.")
    val scenarioBindingDigest: String = conformanceDigest(
        scenarioBindingDigest,
        "Protocol result scenario digest is invalid.",
    )
    val negotiatedVersion: String? = negotiatedVersion?.let { value ->
        conformanceToken(value, "Protocol negotiated version is invalid.")
    }
    val negotiatedProfileDigest: String? = negotiatedProfileDigest?.let { value ->
        conformanceDigest(value, "Protocol negotiated profile digest is invalid.")
    }
    val negotiatedCapabilityDigest: String? = negotiatedCapabilityDigest?.let { value ->
        conformanceDigest(value, "Protocol negotiated capability digest is invalid.")
    }
    val credentialReferenceUsed: Identifier? = credentialReferenceUsed?.let { value ->
        conformanceIdentifier(value, "Protocol credential reference evidence is invalid.")
    }
    val evidenceDigest: String = conformanceDigest(evidenceDigest, "Protocol evidence digest is invalid.")
    val safeFailureCode: String? = safeFailureCode?.let { value ->
        conformanceCode(value, "Protocol safe failure code is invalid.")
    }
    val resultDigest: String

    init {
        require(
            outboundProtocolDispatchCount >= 0 && insecureTransportCount >= 0 &&
                privateAddressContactCount >= 0 && unapprovedRedirectFollowCount >= 0 &&
                credentialCrossPeerDeliveryCount >= 0 && credentialExposureCount >= 0 &&
                descriptorDriftAcceptedCount >= 0 && unknownVersionAcceptedCount >= 0 &&
                unknownCapabilityAcceptedCount >= 0 && messageOrToolIdentityMismatchAcceptedCount >= 0,
        ) { "Protocol conformance result counts must not be negative." }
        require(completedAt >= 0L) { "Protocol conformance completion time must not be negative." }
        require((status == AgentProtocolExecutionStatus.SAFE_COMPLETION) == (this.safeFailureCode == null)) {
            "Only a safe protocol completion may omit a safe failure code."
        }
        resultDigest = ConformanceDigest("flowweft.testkit.agent.protocol.result.v1")
            .add(this.scenarioId.value)
            .add(this.scenarioBindingDigest)
            .add(status.name)
            .add(effectivePeerId?.value ?: "-")
            .add(this.negotiatedVersion ?: "-")
            .add(this.negotiatedProfileDigest ?: "-")
            .add(this.negotiatedCapabilityDigest ?: "-")
            .add(this.credentialReferenceUsed?.value ?: "-")
            .add(tlsVerified)
            .add(outboundProtocolDispatchCount)
            .add(insecureTransportCount)
            .add(privateAddressContactCount)
            .add(unapprovedRedirectFollowCount)
            .add(credentialCrossPeerDeliveryCount)
            .add(credentialExposureCount)
            .add(descriptorDriftAcceptedCount)
            .add(unknownVersionAcceptedCount)
            .add(unknownCapabilityAcceptedCount)
            .add(messageOrToolIdentityMismatchAcceptedCount)
            .add(cancellationOutcome.name)
            .add(this.evidenceDigest)
            .add(this.safeFailureCode ?: "-")
            .add(completedAt)
            .finish()
    }

    override fun toString(): String =
        "AgentProtocolConformanceResult(status=$status, evidence=<redacted>, credentials=<redacted>)"
}

/** External host adapter. It may wrap MCP or A2A clients, but this TestKit never depends on either SDK. */
fun interface AgentProtocolAdapterSecurityHarness {
    fun execute(scenario: AgentProtocolConformanceScenario): CompletionStage<AgentProtocolConformanceResult>
}

object AgentProtocolConformanceAssertions {
    @JvmStatic
    fun assertConformant(
        scenario: AgentProtocolConformanceScenario,
        result: AgentProtocolConformanceResult,
    ) {
        assertEquals(scenario.scenarioId, result.scenarioId, "Protocol result belongs to another fixture.")
        assertEquals(
            scenario.bindingDigest,
            result.scenarioBindingDigest,
            "Protocol result is not bound to the exact fixture.",
        )
        assertTrue(
            result.completedAt in 0L..Long.MAX_VALUE,
            "Protocol result completion time is invalid.",
        )
        assertNotEquals(
            AgentProtocolExecutionStatus.VIOLATION,
            result.status,
            "Protocol harness observed a security/conformance violation.",
        )
        assertEquals(0, result.insecureTransportCount, "Adapter sent protocol data over an insecure transport.")
        assertEquals(0, result.privateAddressContactCount, "Adapter contacted a private, loopback or link-local host.")
        assertEquals(0, result.unapprovedRedirectFollowCount, "Adapter followed an unvalidated redirect.")
        assertEquals(0, result.credentialCrossPeerDeliveryCount, "Adapter delivered a credential to another peer.")
        assertEquals(0, result.credentialExposureCount, "Adapter exposed a credential in payload/evidence/log output.")
        assertEquals(0, result.descriptorDriftAcceptedCount, "Adapter accepted unreviewed capability/descriptor drift.")
        assertEquals(0, result.unknownVersionAcceptedCount, "Adapter silently accepted an unknown protocol version.")
        assertEquals(0, result.unknownCapabilityAcceptedCount, "Adapter silently accepted an unknown capability.")
        assertEquals(
            0,
            result.messageOrToolIdentityMismatchAcceptedCount,
            "Adapter accepted a changed message, task, tool, schema or argument binding.",
        )

        when (scenario.expectedDisposition) {
            AgentProtocolExpectedDisposition.BLOCK_BEFORE_PROTOCOL_DISPATCH -> {
                assertTrue(
                    result.status == AgentProtocolExecutionStatus.BLOCKED ||
                        result.status == AgentProtocolExecutionStatus.FAILED_CLOSED,
                    "Unsafe protocol fixture must block or fail closed.",
                )
                assertEquals(0, result.outboundProtocolDispatchCount, "Blocked fixture reached protocol dispatch.")
                assertNull(result.credentialReferenceUsed, "Blocked fixture consumed a credential.")
                assertTrue(
                    result.cancellationOutcome == AgentProtocolCancellationOutcome.NOT_APPLICABLE ||
                        result.cancellationOutcome == AgentProtocolCancellationOutcome.REJECTED_BEFORE_DISPATCH,
                    "Blocked cancellation fixture reported a remote cancellation outcome.",
                )
            }
            AgentProtocolExpectedDisposition.ALLOW_BOUND_OPERATION -> {
                val unknownCancellation = scenario.approvedOperation.operation == AgentProtocolOperationKind.CANCEL_TASK &&
                    result.status == AgentProtocolExecutionStatus.FAILED_CLOSED &&
                    result.cancellationOutcome == AgentProtocolCancellationOutcome.OUTCOME_UNKNOWN
                assertTrue(
                    result.status == AgentProtocolExecutionStatus.SAFE_COMPLETION || unknownCancellation,
                    "Valid bound operation did not complete safely or enter explicit cancellation reconciliation.",
                )
                assertEquals(1, result.outboundProtocolDispatchCount, "Valid fixture must dispatch exactly once.")
                assertTrue(result.tlsVerified, "Valid production protocol operation requires verified TLS.")
                assertEquals(scenario.approvedProfile.peerId, result.effectivePeerId)
                assertEquals(scenario.approvedProfile.version, result.negotiatedVersion)
                assertEquals(scenario.approvedProfile.profileDigest, result.negotiatedProfileDigest)
                assertEquals(scenario.approvedProfile.capabilityDigest, result.negotiatedCapabilityDigest)
                assertEquals(scenario.credential?.credentialReference, result.credentialReferenceUsed)
                if (scenario.approvedOperation.operation == AgentProtocolOperationKind.CANCEL_TASK) {
                    assertTrue(
                        result.cancellationOutcome == AgentProtocolCancellationOutcome.CONFIRMED ||
                            result.cancellationOutcome == AgentProtocolCancellationOutcome.REMOTE_REJECTED ||
                            result.cancellationOutcome == AgentProtocolCancellationOutcome.OUTCOME_UNKNOWN,
                        "Cancellation must preserve the remote/unknown outcome instead of assuming success.",
                    )
                } else {
                    assertEquals(AgentProtocolCancellationOutcome.NOT_APPLICABLE, result.cancellationOutcome)
                }
            }
        }
        assertNotEquals(
            AgentProtocolCancellationOutcome.LOCALLY_ASSUMED_CANCELLED,
            result.cancellationOutcome,
            "A cancellation request is not proof that the remote task stopped.",
        )
        assertEquals(64, result.evidenceDigest.length, "Protocol result must carry bounded safe evidence.")
        assertEquals(64, result.resultDigest.length, "Protocol result must bind every security-relevant field.")
        assertFalse(result.toString().contains(result.evidenceDigest), "Protocol toString leaked evidence identifiers.")
    }
}

/** Reusable MCP 2025-11-25 and A2A 1.0 adapter security/conformance contract. */
abstract class AgentProtocolAdapterSecurityContractTest {
    protected abstract val protocolHarness: AgentProtocolAdapterSecurityHarness

    protected open fun protocolFixtures(): AgentProtocolFixtureCatalog = AgentProtocolConformanceFixtures.standard()

    protected open fun asynchronousTimeout(): Duration = Duration.ofSeconds(60)

    @Test fun `MCP stable profile negotiates exact version capability and OAuth peer`() =
        verify(AgentProtocolSecurityAttack.MCP_BASELINE)

    @Test fun `A2A 1_0 profile negotiates exact Agent Card and message identity`() =
        verify(AgentProtocolSecurityAttack.A2A_BASELINE)

    @Test fun `production adapters reject TLS downgrade`() = verify(AgentProtocolSecurityAttack.TLS_DOWNGRADE)

    @Test fun `production adapters verify the exact TLS peer identity`() =
        verify(AgentProtocolSecurityAttack.TLS_IDENTITY_MISMATCH)

    @Test fun `OAuth discovery cannot contact private or metadata addresses`() =
        verify(AgentProtocolSecurityAttack.PRIVATE_ADDRESS_SSRF)

    @Test fun `redirect destinations receive the same private network validation`() =
        verify(AgentProtocolSecurityAttack.REDIRECT_TO_PRIVATE_ADDRESS)

    @Test fun `OAuth tokens require the exact protected resource audience`() =
        verify(AgentProtocolSecurityAttack.OAUTH_AUDIENCE_MISMATCH)

    @Test fun `credentials are isolated per reviewed peer profile`() =
        verify(AgentProtocolSecurityAttack.CREDENTIAL_CROSS_PEER_REUSE)

    @Test fun `descriptor and capability digest drift fails closed`() =
        verify(AgentProtocolSecurityAttack.CAPABILITY_DIGEST_DRIFT)

    @Test fun `unknown and experimental capabilities require explicit negotiation`() =
        verify(AgentProtocolSecurityAttack.UNKNOWN_REQUIRED_CAPABILITY)

    @Test fun `unknown MCP version never replaces the stable profile`() =
        verify(AgentProtocolSecurityAttack.MCP_VERSION_MISMATCH)

    @Test fun `A2A does not silently fall back from 1_0 to 0_3`() =
        verify(AgentProtocolSecurityAttack.A2A_VERSION_MISMATCH)

    @Test fun `MCP tool identity remains exactly bound`() =
        verify(AgentProtocolSecurityAttack.MCP_TOOL_IDENTITY_MISMATCH)

    @Test fun `MCP tool descriptor remains exactly bound`() =
        verify(AgentProtocolSecurityAttack.MCP_TOOL_DESCRIPTOR_MISMATCH)

    @Test fun `MCP canonical tool arguments remain exactly bound`() =
        verify(AgentProtocolSecurityAttack.MCP_TOOL_ARGUMENT_MISMATCH)

    @Test fun `A2A peer message identity remains exactly bound`() =
        verify(AgentProtocolSecurityAttack.A2A_MESSAGE_IDENTITY_MISMATCH)

    @Test fun `A2A canonical message content remains exactly bound`() =
        verify(AgentProtocolSecurityAttack.A2A_MESSAGE_DIGEST_MISMATCH)

    @Test fun `A2A cancellation preserves bound remote outcome`() =
        verify(AgentProtocolSecurityAttack.A2A_BOUND_CANCELLATION)

    @Test fun `A2A task cancellation cannot cross principal boundaries`() =
        verify(AgentProtocolSecurityAttack.A2A_CANCELLATION_CROSS_SUBJECT)

    private fun verify(attack: AgentProtocolSecurityAttack) {
        val scenario = protocolFixtures().scenario(attack)
        val result = AgentContractAssertions.awaitStage(
            protocolHarness.execute(scenario),
            asynchronousTimeout(),
            "Agent protocol $attack",
        )
        AgentProtocolConformanceAssertions.assertConformant(scenario, result)
    }
}

private const val MAX_CONFORMANCE_TOKEN_CODE_POINTS = 512
private val conformanceCodePattern = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")

private fun conformanceIdentifier(value: Identifier, message: String): Identifier {
    conformanceToken(value.value, message)
    return value
}

private fun conformanceCode(value: String, message: String): String {
    conformanceToken(value, message)
    require(conformanceCodePattern.matches(value)) { message }
    return value
}

private fun conformanceToken(value: String, message: String): String {
    require(value.isNotBlank() && value == value.trim() &&
        value.codePointCount(0, value.length) <= MAX_CONFORMANCE_TOKEN_CODE_POINTS
    ) { message }
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        require(!Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT.toInt()) {
            message
        }
        offset += Character.charCount(codePoint)
    }
    return value
}

private fun conformanceDigest(value: String, message: String): String {
    require(value.length == 64 && value.all { character -> character in '0'..'9' || character in 'a'..'f' }) { message }
    return value
}

private class ConformanceDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(conformanceCode(domain, "Protocol result digest domain is invalid."))
    }

    fun add(value: String): ConformanceDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
        return this
    }

    fun add(value: Long): ConformanceDigest = add(value.toString())
    fun add(value: Int): ConformanceDigest = add(value.toString())
    fun add(value: Boolean): ConformanceDigest = add(if (value) "1" else "0")

    fun finish(): String = digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }
}
