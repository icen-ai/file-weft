package ai.icen.fw.agent.interoperability.spi

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentFailureCategory
import ai.icen.fw.agent.api.AgentRunFailure
import ai.icen.fw.core.id.Identifier
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AgentInteroperabilityBindingAndDoctorTest {
    @Test
    fun `capability provider result is anchored to exact successful remote dispatch evidence`() {
        val fixture = InteroperabilityContractFixture.create()
        val evidence = AgentInteroperabilityDispatchEvidence.of(fixture.dispatch, fixture.dispatchResult)
        val request = AgentInteroperabilityCapabilityRequest.of(
            Identifier("capability-request-1"),
            fixture.providerId,
            evidence,
            fixture.snapshot.capabilityDigest,
            fixture.catalog.catalogDigest,
            60L,
            200L,
        )
        val result = AgentInteroperabilityCapabilityResult.available(
            request,
            fixture.snapshot,
            InteroperabilityContractFixture.digest("capability-evidence"),
            70L,
        )
        val provider = object : AgentInteroperabilityCapabilityProvider {
            override fun providerId() = fixture.providerId

            override fun capabilities(request: AgentInteroperabilityCapabilityRequest) =
                CompletableFuture.completedFuture(result)
        }

        assertSame(fixture.snapshot, provider.capabilities(request).toCompletableFuture().join().snapshot)
        assertEquals(fixture.dispatch.bindingDigest, evidence.result.dispatchBindingDigest)
        assertEquals(AgentInteroperabilityCapabilityStatus.AVAILABLE, result.status)
    }

    @Test
    fun `extension snapshot binds later existing dispatch and fails closed on missing capability`() {
        val fixture = InteroperabilityContractFixture.create()
        val binding = AgentInteroperabilityDispatchBinding.of(
            fixture.laterDispatch,
            fixture.snapshot,
            listOf(AgentInteroperabilityCapabilities.MCP_RESOURCES_READ),
            70L,
        )

        assertEquals(fixture.laterDispatch.bindingDigest, binding.dispatch.bindingDigest)
        assertFailsWith<IllegalArgumentException> {
            AgentInteroperabilityDispatchBinding.of(
                fixture.laterDispatch,
                fixture.snapshot,
                listOf(AgentCapabilityId("remote.mcp.unreviewed")),
                70L,
            )
        }
    }

    @Test
    fun `capability digest drift cannot be reported as available`() {
        val fixture = InteroperabilityContractFixture.create()
        val evidence = AgentInteroperabilityDispatchEvidence.of(fixture.dispatch, fixture.dispatchResult)
        val request = AgentInteroperabilityCapabilityRequest.of(
            Identifier("capability-request-drift"),
            fixture.providerId,
            evidence,
            InteroperabilityContractFixture.digest("old-capability"),
            fixture.catalog.catalogDigest,
            60L,
            200L,
        )

        assertFailsWith<IllegalArgumentException> {
            AgentInteroperabilityCapabilityResult.available(
                request,
                fixture.snapshot,
                InteroperabilityContractFixture.digest("capability-evidence"),
                70L,
            )
        }
        val drifted = AgentInteroperabilityCapabilityResult.failure(
            request,
            AgentInteroperabilityCapabilityStatus.DRIFTED,
            fixture.snapshot,
            AgentRunFailure(AgentFailureCategory.PROTOCOL, "interop.capability.drifted"),
            InteroperabilityContractFixture.digest("drift-evidence"),
            70L,
        )
        assertEquals(AgentInteroperabilityCapabilityStatus.DRIFTED, drifted.status)
    }

    @Test
    fun `Doctor reuses trusted initialization request and returns value-free findings`() {
        val fixture = InteroperabilityContractFixture.create()
        val request = AgentInteroperabilityDoctorRequest.of(
            Identifier("doctor-request-1"),
            fixture.providerId,
            fixture.invocation,
            fixture.profile,
            AgentInteroperabilityDoctorMode.CATALOG,
            fixture.snapshot.capabilityDigest,
            fixture.catalog.catalogDigest,
            60L,
            120L,
        )
        val finding = AgentInteroperabilityDoctorFinding.of(
            AgentInteroperabilityDoctorCode.CATALOG_MATCHED,
            AgentInteroperabilityDoctorSeverity.INFO,
            1L,
            InteroperabilityContractFixture.digest("doctor-evidence"),
        )
        val result = AgentInteroperabilityDoctorResult.of(
            request,
            fixture.providerId,
            AgentInteroperabilityDoctorStatus.READY,
            listOf(finding),
            70L,
            130L,
        )
        val doctor = object : AgentInteroperabilityDoctor {
            override fun providerId() = fixture.providerId

            override fun inspect(request: AgentInteroperabilityDoctorRequest) =
                CompletableFuture.completedFuture(result)
        }

        assertEquals(AgentInteroperabilityDoctorStatus.READY, doctor.inspect(request).toCompletableFuture().join().status)
        assertFailsWith<IllegalArgumentException> {
            AgentInteroperabilityDoctorResult.of(
                request,
                fixture.providerId,
                AgentInteroperabilityDoctorStatus.READY,
                listOf(
                    AgentInteroperabilityDoctorFinding.of(
                        AgentInteroperabilityDoctorCode.CATALOG_DRIFTED,
                        AgentInteroperabilityDoctorSeverity.WARNING,
                        1L,
                        InteroperabilityContractFixture.digest("warning-evidence"),
                    ),
                ),
                70L,
                130L,
            )
        }
    }
}
