package ai.icen.fw.observability

import ai.icen.fw.core.id.Identifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductionSystemDoctorTest {
    private val clock = FixedClock(1_000L)

    @Test
    fun `tenant diagnosis is authorized bound and optional absence remains explicit`() {
        val request = tenantRequest()
        var observedTenant: Identifier? = null
        val doctor = doctor(
            request,
            registry = SystemDoctorProbeRegistry { capability, _ ->
                if (capability == SystemDoctorCapability.DATABASE) {
                    SystemDoctorProbe { probeRequest ->
                        observedTenant = probeRequest.tenantId
                        healthy(probeRequest, SystemDoctorCode.DATABASE_AVAILABLE)
                    }
                } else {
                    null
                }
            },
        )

        val report = doctor.inspectTenant(request)

        assertEquals(Identifier("tenant-a"), observedTenant)
        assertEquals(SystemDoctorReadiness.READY, report.readiness)
        assertEquals(1, report.healthyRequiredProbeCount)
        assertEquals(SystemDoctorSeverity.UNSUPPORTED, report.status)
        assertEquals(
            SystemDoctorCapability.values().size - 1,
            report.findings.count { finding -> finding.code == SystemDoctorCode.PROBE_MISSING },
        )
        assertTrue(report.findings.filter { it.code == SystemDoctorCode.PROBE_MISSING }.all { !it.required })
        assertFalse(report.toString().contains("tenant-a"))
    }

    @Test
    fun `system entry carries no tenant and uses a separately bound authorization`() {
        val request = systemRequest()
        var observedTenant: Identifier? = Identifier("unexpected")
        val doctor = doctor(
            request,
            registry = SystemDoctorProbeRegistry { capability, _ ->
                if (capability == SystemDoctorCapability.DATABASE) {
                    SystemDoctorProbe { probeRequest ->
                        observedTenant = probeRequest.tenantId
                        healthy(probeRequest, SystemDoctorCode.DATABASE_AVAILABLE)
                    }
                } else {
                    null
                }
            },
        )

        val report = doctor.inspectSystem(request)

        assertEquals(null, observedTenant)
        assertEquals(SystemDoctorScope.SYSTEM, report.scope)
        assertEquals(SystemDoctorReadiness.READY, report.readiness)
    }

    @Test
    fun `required missing probe is unsupported and blocks readiness`() {
        val request = tenantRequest()
        val doctor = doctor(request, SystemDoctorProbeRegistry { _, _ -> null })

        val report = doctor.inspectTenant(request)

        assertEquals(SystemDoctorReadiness.NOT_READY, report.readiness)
        assertTrue(
            report.findings.any { finding ->
                finding.capability == SystemDoctorCapability.DATABASE && finding.required &&
                    finding.severity == SystemDoctorSeverity.UNSUPPORTED &&
                    finding.code == SystemDoctorCode.PROBE_MISSING
            },
        )
    }

    @Test
    fun `denied authorization invokes no probes and fails closed`() {
        val request = tenantRequest()
        var registryInvoked = false
        val doctor = ProductionSystemDoctor(
            SystemDoctorAuthorizationPort { deniedAuthorization(request) },
            topology(),
            SystemDoctorProbeRegistry { _, _ ->
                registryInvoked = true
                null
            },
            SystemDoctorProbeExecutionPort.DIRECT,
            clock = clock,
        )

        val report = doctor.inspectTenant(request)

        assertFalse(registryInvoked)
        assertEquals(SystemDoctorReadiness.NOT_READY, report.readiness)
        assertEquals(listOf(SystemDoctorCode.AUTHORIZATION_DENIED), report.findings.map { it.code })
    }

    @Test
    fun `probe exceptions are reduced to stable evidence without secret text`() {
        val request = tenantRequest()
        val secret = "jdbc:postgresql://internal/db?password=do-not-leak"
        val doctor = doctor(
            request,
            SystemDoctorProbeRegistry { capability, _ ->
                if (capability == SystemDoctorCapability.DATABASE) {
                    SystemDoctorProbe { throw IllegalStateException(secret) }
                } else {
                    null
                }
            },
        )

        val report = doctor.inspectTenant(request)
        val rendered = report.toString() + report.findings.joinToString()

        assertEquals(SystemDoctorReadiness.NOT_READY, report.readiness)
        assertTrue(report.findings.any { it.code == SystemDoctorCode.PROBE_FAILED })
        assertFalse(rendered.contains(secret))
        assertFalse(rendered.contains("password"))
    }

    @Test
    fun `hard timeout result blocks a required capability without executing result parsing`() {
        val request = tenantRequest()
        val doctor = ProductionSystemDoctor(
            SystemDoctorAuthorizationPort { allowedAuthorization(request) },
            topology(),
            SystemDoctorProbeRegistry { capability, _ ->
                if (capability == SystemDoctorCapability.DATABASE) {
                    SystemDoctorProbe { probeRequest -> healthy(probeRequest, SystemDoctorCode.DATABASE_AVAILABLE) }
                } else {
                    null
                }
            },
            SystemDoctorProbeExecutionPort { _, _, _ -> SystemDoctorProbeExecution.timedOut() },
            clock = clock,
        )

        val report = doctor.inspectTenant(request)

        assertEquals(SystemDoctorReadiness.NOT_READY, report.readiness)
        assertTrue(report.findings.any { it.code == SystemDoctorCode.PROBE_TIMED_OUT })
    }

    @Test
    fun `version and configuration drift fail closed before health evidence is trusted`() {
        val request = tenantRequest()
        val doctor = doctor(
            request,
            SystemDoctorProbeRegistry { capability, _ ->
                if (capability == SystemDoctorCapability.DATABASE) {
                    SystemDoctorProbe { probeRequest ->
                        SystemDoctorProbeResult(
                            probeRequest.probeBindingDigest,
                            probeRequest.capability,
                            SystemDoctorProbeState.HEALTHY,
                            "v2",
                            probeRequest.configurationDigest,
                            clock.currentTimeMillis(),
                        )
                    }
                } else {
                    null
                }
            },
        )

        val report = doctor.inspectTenant(request)

        assertEquals(SystemDoctorReadiness.NOT_READY, report.readiness)
        assertTrue(report.findings.any { it.code == SystemDoctorCode.PROBE_VERSION_DRIFT })
        assertFalse(report.findings.any { it.code == SystemDoctorCode.PROBE_HEALTHY })
    }

    @Test
    fun `optional degraded capabilities do not manufacture required readiness failures`() {
        val request = tenantRequest()
        val doctor = doctor(
            request,
            SystemDoctorProbeRegistry { capability, _ ->
                when (capability) {
                    SystemDoctorCapability.DATABASE -> SystemDoctorProbe { probeRequest ->
                        healthy(probeRequest, SystemDoctorCode.DATABASE_AVAILABLE)
                    }
                    SystemDoctorCapability.AGENT_QUEUE -> SystemDoctorProbe { probeRequest ->
                        SystemDoctorProbeResult(
                            probeRequest.probeBindingDigest,
                            probeRequest.capability,
                            SystemDoctorProbeState.DEGRADED,
                            probeRequest.contractVersion,
                            probeRequest.configurationDigest,
                            clock.currentTimeMillis(),
                            listOf(
                                SystemDoctorProbeSignal(
                                    SystemDoctorSeverity.ERROR,
                                    SystemDoctorCode.QUEUE_OUTCOME_UNKNOWN,
                                    2L,
                                    SystemDoctorBucket.UNKNOWN,
                                    SystemDoctorRepairAction.RECONCILE_UNKNOWN_OUTCOMES,
                                ),
                            ),
                        )
                    }
                    else -> null
                }
            },
        )

        val report = doctor.inspectTenant(request)

        assertEquals(SystemDoctorReadiness.READY, report.readiness)
        assertTrue(
            report.findings.any { finding ->
                finding.capability == SystemDoctorCapability.AGENT_QUEUE &&
                    finding.code == SystemDoctorCode.QUEUE_OUTCOME_UNKNOWN &&
                    finding.severity == SystemDoctorSeverity.WARNING
            },
        )
    }

    private fun doctor(
        request: SystemDoctorRequest,
        registry: SystemDoctorProbeRegistry,
    ): ProductionSystemDoctor = ProductionSystemDoctor(
        SystemDoctorAuthorizationPort { allowedAuthorization(request) },
        topology(),
        registry,
        SystemDoctorProbeExecutionPort.DIRECT,
        clock = clock,
    )

    private fun topology(): SystemDoctorTopology = SystemDoctorTopology(
        SystemDoctorCapability.values().map { capability ->
            SystemDoctorProbeRequirement(
                capability,
                "${capability.name.lowercase().replace('_', '-')}-probe",
                capability == SystemDoctorCapability.DATABASE,
                "v1",
                CONFIGURATION_DIGEST,
                250L,
                5_000L,
            )
        },
    )

    private fun healthy(
        request: SystemDoctorProbeRequest,
        code: SystemDoctorCode,
    ): SystemDoctorProbeResult = SystemDoctorProbeResult(
        request.probeBindingDigest,
        request.capability,
        SystemDoctorProbeState.HEALTHY,
        request.contractVersion,
        request.configurationDigest,
        clock.currentTimeMillis(),
        listOf(
            SystemDoctorProbeSignal(
                SystemDoctorSeverity.HEALTHY,
                code,
                1L,
                SystemDoctorBucket.AVAILABLE,
                SystemDoctorRepairAction.NONE,
            ),
        ),
    )

    private fun tenantRequest(): SystemDoctorRequest = SystemDoctorRequest(
        Identifier("request-a"),
        SystemDoctorScope.TENANT,
        Identifier("tenant-a"),
        Identifier("operator-a"),
        "human",
        "auth-revision-a",
        900L,
        5_000L,
    )

    private fun systemRequest(): SystemDoctorRequest = SystemDoctorRequest(
        Identifier("request-system"),
        SystemDoctorScope.SYSTEM,
        null,
        Identifier("operator-system"),
        "service",
        "auth-revision-system",
        900L,
        5_000L,
    )

    private fun allowedAuthorization(request: SystemDoctorRequest): SystemDoctorAuthorization =
        SystemDoctorAuthorization(
            true,
            request.bindingDigest,
            request.scope,
            request.tenantId,
            request.principalId,
            request.principalType,
            request.authorizationRevision,
            900L,
            5_000L,
        )

    private fun deniedAuthorization(request: SystemDoctorRequest): SystemDoctorAuthorization =
        SystemDoctorAuthorization(
            false,
            request.bindingDigest,
            request.scope,
            request.tenantId,
            request.principalId,
            request.principalType,
            request.authorizationRevision,
            900L,
            5_000L,
        )

    private class FixedClock(private val now: Long) : SystemDoctorClock {
        override fun currentTimeMillis(): Long = now
    }

    private companion object {
        const val CONFIGURATION_DIGEST: String =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
