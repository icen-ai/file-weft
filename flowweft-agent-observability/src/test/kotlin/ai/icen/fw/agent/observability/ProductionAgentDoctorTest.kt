package ai.icen.fw.agent.observability

import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductionAgentDoctorTest {
    @Test
    fun `authorization denial prevents every production probe`() {
        val request = request()
        val topologyCalls = AtomicInteger()
        val durableCalls = AtomicInteger()
        val doctor = ProductionAgentDoctor(
            authorization = AgentDoctorAuthorizationPort { denied(it) },
            providerTopology = AgentProviderTopologyPort {
                topologyCalls.incrementAndGet()
                error("must not run")
            },
            providerProbes = AgentProviderDiagnosticProbeRegistry { _, _ -> error("must not run") },
            durableProbes = AgentDurableDiagnosticProbeRegistry {
                durableCalls.incrementAndGet()
                error("must not run")
            },
            clock = AgentDoctorClock { 120L },
        )

        val report = doctor.diagnose(request)

        assertEquals(AgentDoctorStatus.ERROR, report.status)
        assertEquals(listOf(AgentDoctorCode.AUTHORIZATION_DENIED), report.findings.map { it.code })
        assertEquals(0, topologyCalls.get())
        assertEquals(0, durableCalls.get())
    }

    @Test
    fun `system request rejects a tenant-scoped authorization before probes`() {
        val request = AgentDoctorRequest(
            id("request-system"),
            AgentDoctorScope.SYSTEM,
            null,
            id("operator-1"),
            "SYSTEM",
            "authorization-v1",
            100L,
            1_000L,
        )
        val topologyCalls = AtomicInteger()
        val doctor = ProductionAgentDoctor(
            authorization = AgentDoctorAuthorizationPort {
                AgentDoctorAuthorization(
                    true,
                    request.bindingDigest,
                    AgentDoctorScope.TENANT,
                    id("tenant-1"),
                    request.principalId,
                    request.principalType,
                    request.authorizationRevision,
                    90L,
                    1_100L,
                )
            },
            providerTopology = AgentProviderTopologyPort {
                topologyCalls.incrementAndGet()
                error("must not run")
            },
            providerProbes = AgentProviderDiagnosticProbeRegistry { _, _ -> error("must not run") },
            durableProbes = AgentDurableDiagnosticProbeRegistry { error("must not run") },
            clock = AgentDoctorClock { 120L },
        )

        val report = doctor.diagnose(request)

        assertEquals(AgentDoctorStatus.ERROR, report.status)
        assertTrue(report.has(AgentDoctorCode.AUTHORIZATION_MISMATCH))
        assertEquals(0, topologyCalls.get())
    }

    @Test
    fun `missing critical provider probe is unsupported and never healthy`() {
        val request = request()
        val expected = expectation(AgentProviderKind.MODEL, "model")
        val doctor = doctor(
            request,
            listOf(expected),
            providerProbe = { _, _ -> null },
        )

        val report = doctor.diagnose(request)

        assertEquals(AgentDoctorStatus.UNSUPPORTED, report.status)
        assertTrue(report.has(AgentDoctorCode.PROVIDER_PROBE_MISSING, AgentProviderKind.MODEL))
        assertFalse(report.has(AgentDoctorCode.PROVIDER_AVAILABLE, AgentProviderKind.MODEL))
    }

    @Test
    fun `provider drift and durable hazards produce bounded content-free evidence`() {
        val request = request()
        val expectations = AgentProviderKind.values().map { kind -> expectation(kind, kind.name.lowercase()) }
        val secret = "https://secret.example?token=provider-secret prompt=private"
        val doctor = doctor(
            request,
            expectations,
            providerProbe = { kind, providerId ->
                if (kind == AgentProviderKind.RETRIEVAL) {
                    AgentProviderDiagnosticProbe { throw IllegalStateException(secret) }
                } else {
                    val expected = expectations.single { item -> item.providerId == providerId }
                    AgentProviderDiagnosticProbe { probeRequest ->
                        AgentProviderDiagnosticProbeResult(
                            probeRequest.requestBindingDigest,
                            providerId,
                            kind,
                            AgentProviderProbeState.AVAILABLE,
                            120L,
                            if (kind == AgentProviderKind.MODEL) digest("changed") else expected.descriptorDigest,
                            if (kind == AgentProviderKind.TOOL) digest("changed-capability") else expected.capabilityDigest,
                            if (kind == AgentProviderKind.REMOTE_PROTOCOL) {
                                digest("changed-configuration")
                            } else {
                                expected.configurationDigest
                            },
                        )
                    }
                }
            },
            durableSnapshot = { durableRequest, workload ->
                if (workload == AgentDurableWorkloadKind.AGENT_RUN) {
                    AgentDurableDiagnosticSnapshot(
                        durableRequest.requestBindingDigest,
                        workload,
                        AgentDoctorWindow.RECENT_1_HOUR,
                        120L,
                        queuedCount = 101L,
                        runningCount = 3L,
                        failedCount = 2L,
                        cancelledCount = 1L,
                        expiredCount = 1L,
                        expiredLeaseCount = 1L,
                        outcomeUnknownCount = 2L,
                        reconciliationPendingCount = 2L,
                        overCostLimitCount = 1L,
                        overLatencyLimitCount = 1L,
                    )
                } else {
                    AgentDurableDiagnosticSnapshot(
                        durableRequest.requestBindingDigest,
                        workload,
                        AgentDoctorWindow.RECENT_1_HOUR,
                        120L,
                        queuedCount = 1L,
                        unknownCostCount = 1L,
                        unknownLatencyCount = 1L,
                        evaluationCaseCount = 10L,
                        evaluationCaseFailedCount = 4L,
                        evaluationRetrievalFailedCount = 1L,
                        evaluationCitationFailedCount = 1L,
                        evaluationToolFailedCount = 1L,
                        evaluationRefusalFailedCount = 1L,
                        evaluationObservationUnknownCount = 1L,
                    )
                }
            },
        )

        val report = doctor.diagnose(request)

        assertEquals(AgentDoctorStatus.ERROR, report.status)
        assertTrue(report.has(AgentDoctorCode.PROVIDER_DESCRIPTOR_DRIFT, AgentProviderKind.MODEL))
        assertTrue(report.has(AgentDoctorCode.PROVIDER_CAPABILITY_DRIFT, AgentProviderKind.TOOL))
        assertTrue(report.has(AgentDoctorCode.PROVIDER_CONFIGURATION_DRIFT, AgentProviderKind.REMOTE_PROTOCOL))
        assertTrue(report.has(AgentDoctorCode.PROVIDER_PROBE_FAILED, AgentProviderKind.RETRIEVAL))
        assertTrue(report.has(AgentDoctorCode.DURABLE_BACKLOG_HIGH))
        assertTrue(report.has(AgentDoctorCode.DURABLE_EXPIRED_LEASE))
        assertTrue(report.has(AgentDoctorCode.DURABLE_OUTCOME_UNKNOWN))
        assertTrue(report.has(AgentDoctorCode.DURABLE_RECONCILIATION_PENDING))
        assertTrue(report.has(AgentDoctorCode.DURABLE_FAILED))
        assertTrue(report.has(AgentDoctorCode.DURABLE_CANCELLED))
        assertTrue(report.has(AgentDoctorCode.COST_LIMIT_EXCEEDED))
        assertTrue(report.has(AgentDoctorCode.LATENCY_LIMIT_EXCEEDED))
        assertTrue(report.has(AgentDoctorCode.COST_OBSERVATION_UNKNOWN))
        assertTrue(report.has(AgentDoctorCode.LATENCY_OBSERVATION_UNKNOWN))
        assertTrue(report.has(AgentDoctorCode.EVALUATION_REGRESSION_FAILED))
        assertTrue(report.has(AgentDoctorCode.EVALUATION_RETRIEVAL_FAILED))
        assertTrue(report.has(AgentDoctorCode.EVALUATION_CITATION_FAILED))
        assertTrue(report.has(AgentDoctorCode.EVALUATION_TOOL_FAILED))
        assertTrue(report.has(AgentDoctorCode.EVALUATION_REFUSAL_FAILED))
        assertTrue(report.has(AgentDoctorCode.EVALUATION_OBSERVATION_UNKNOWN))
        assertTrue(report.findings.size <= 256)
        val safeText = report.toString() + report.findings.joinToString()
        assertFalse(safeText.contains("secret"))
        assertFalse(safeText.contains("prompt"))
        assertFalse(safeText.contains("token"))
        assertFalse(safeText.contains("IllegalStateException"))
        assertFalse(safeText.contains("https://"))
    }

    @Test
    fun `healthy report requires complete current provider and durable evidence`() {
        val request = request()
        val expectations = AgentProviderKind.values().map { kind -> expectation(kind, kind.name.lowercase()) }
        val doctor = doctor(request, expectations)

        val report = doctor.diagnose(request)

        assertEquals(AgentDoctorStatus.HEALTHY, report.status)
        assertEquals(4L, report.findings
            .filter { finding -> finding.code == AgentDoctorCode.PROVIDER_AVAILABLE }
            .sumOf { finding -> finding.count })
        assertEquals(2, report.findings.count { finding ->
            finding.code == AgentDoctorCode.DURABLE_BACKLOG_WITHIN_LIMIT
        })
    }

    private fun doctor(
        request: AgentDoctorRequest,
        expectations: List<AgentProviderDiagnosticExpectation>,
        providerProbe: (AgentProviderKind, ProviderId) -> AgentProviderDiagnosticProbe? = { kind, providerId ->
            val expected = expectations.single { item -> item.providerKind == kind && item.providerId == providerId }
            AgentProviderDiagnosticProbe { probeRequest ->
                AgentProviderDiagnosticProbeResult(
                    probeRequest.requestBindingDigest,
                    providerId,
                    kind,
                    AgentProviderProbeState.AVAILABLE,
                    120L,
                    expected.descriptorDigest,
                    expected.capabilityDigest,
                    expected.configurationDigest,
                )
            }
        },
        durableSnapshot: (AgentDurableDiagnosticRequest, AgentDurableWorkloadKind) -> AgentDurableDiagnosticSnapshot =
            { durableRequest, workload ->
                AgentDurableDiagnosticSnapshot(
                    durableRequest.requestBindingDigest,
                    workload,
                    AgentDoctorWindow.RECENT_1_HOUR,
                    120L,
                )
            },
    ): ProductionAgentDoctor = ProductionAgentDoctor(
        authorization = AgentDoctorAuthorizationPort { approved(it) },
        providerTopology = AgentProviderTopologyPort { probeRequest ->
            AgentProviderTopologySnapshot(
                probeRequest.requestBindingDigest,
                expectations,
                AgentProviderKind.values().toList(),
            )
        },
        providerProbes = AgentProviderDiagnosticProbeRegistry { kind, providerId -> providerProbe(kind, providerId) },
        durableProbes = AgentDurableDiagnosticProbeRegistry { workload ->
            AgentDurableDiagnosticProbe { durableRequest -> durableSnapshot(durableRequest, workload) }
        },
        clock = AgentDoctorClock { 120L },
    )

    private fun request(): AgentDoctorRequest = AgentDoctorRequest(
        id("request-1"),
        AgentDoctorScope.TENANT,
        id("tenant-1"),
        id("principal-1"),
        "USER",
        "authorization-v1",
        100L,
        1_000L,
    )

    private fun approved(request: AgentDoctorRequest): AgentDoctorAuthorization = AgentDoctorAuthorization(
        true,
        request.bindingDigest,
        request.scope,
        request.tenantId,
        request.principalId,
        request.principalType,
        request.authorizationRevision,
        90L,
        1_100L,
    )

    private fun denied(request: AgentDoctorRequest): AgentDoctorAuthorization = AgentDoctorAuthorization(
        false,
        request.bindingDigest,
        request.scope,
        request.tenantId,
        request.principalId,
        request.principalType,
        request.authorizationRevision,
        90L,
        1_100L,
    )

    private fun expectation(kind: AgentProviderKind, seed: String): AgentProviderDiagnosticExpectation =
        AgentProviderDiagnosticExpectation(
            ProviderId("provider.$seed"),
            kind,
            digest("$seed-descriptor"),
            digest("$seed-capability"),
            digest("$seed-configuration"),
        )

    private fun AgentDoctorReport.has(code: AgentDoctorCode, providerKind: AgentProviderKind? = null): Boolean =
        findings.any { finding -> finding.code == code && (providerKind == null || finding.providerKind == providerKind) }

    private fun id(value: String): Identifier = Identifier(value)

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
