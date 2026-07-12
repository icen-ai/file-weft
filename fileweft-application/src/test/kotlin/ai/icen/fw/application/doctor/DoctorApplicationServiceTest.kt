package ai.icen.fw.application.doctor

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.doctor.DoctorChecker
import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.FileWeftMetrics
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DoctorApplicationServiceTest {
    @Test
    fun `aggregates authorized lifecycle storage and connector diagnosis`() {
        val storage = object : StorageAdapterStub() {
            override fun exists(location: ai.icen.fw.spi.storage.StorageObjectLocation): Boolean = true
        }
        val report = DoctorApplicationService(
            tenantProvider = FixedTenantProvider(),
            permissionChecker = permissionChecker(AuthorizationDecision(true)),
            checkers = listOf(
                LifecycleDoctorChecker(InMemoryDocumentRepository(documentWithActiveVersion())),
                StorageDoctorChecker(InMemoryDocumentRepository(documentWithActiveVersion()), InMemoryFileObjectRepository(fileObject()), storage, DirectTransaction),
                ConnectorDoctorChecker(listOf(FixedConnector(ConnectorHealth(ConnectorHealthStatus.HEALTHY)))),
            ),
            clock = fixedClock(),
        ).inspectDocument(Identifier("document-1"))

        assertEquals(DoctorStatus.HEALTHY, report.status)
        assertEquals(listOf("permission", "lifecycle", "storage", "connector"), report.checks.map { it.checkerName })
        assertEquals(10, report.inspectedAt)
    }

    @Test
    fun `returns only permission diagnosis without running dependent checks for denied access`() {
        var invoked = false
        val report = DoctorApplicationService(
            FixedTenantProvider(),
            permissionChecker(AuthorizationDecision(false, "doctor permission is missing")),
            listOf(object : DoctorChecker {
                override fun name(): String = "unexpected"
                override fun check(context: ai.icen.fw.core.context.DoctorCheckContext): DoctorCheckResult {
                    invoked = true
                    return DoctorCheckResult(name(), DoctorStatus.HEALTHY, "Unexpected checker ran.")
                }
            }),
            fixedClock(),
        ).inspectDocument(Identifier("document-1"))

        assertEquals(DoctorStatus.ERROR, report.status)
        assertEquals(listOf("permission"), report.checks.map { it.checkerName })
        assertFalse(invoked)
    }

    @Test
    fun `contains checker failures and reports contract name mismatches`() {
        val report = DoctorApplicationService(
            FixedTenantProvider(),
            permissionChecker(AuthorizationDecision(true)),
            listOf(
                object : DoctorChecker {
                    override fun name(): String = "failing"
                    override fun check(context: ai.icen.fw.core.context.DoctorCheckContext): DoctorCheckResult =
                        throw IllegalStateException("dependency unavailable")
                },
                object : DoctorChecker {
                    override fun name(): String = "mismatched"
                    override fun check(context: ai.icen.fw.core.context.DoctorCheckContext): DoctorCheckResult =
                        DoctorCheckResult("other", DoctorStatus.HEALTHY, "Incorrect contract result.")
                },
            ),
            fixedClock(),
        ).inspectDocument(Identifier("document-1"))

        assertEquals(DoctorStatus.ERROR, report.status)
        assertEquals(DoctorStatus.ERROR, report.checks.single { it.checkerName == "failing" }.status)
        assertEquals(DoctorStatus.ERROR, report.checks.single { it.checkerName == "mismatched" }.status)
        assertTrue(report.checks.single { it.checkerName == "failing" }.evidence.containsKey("exceptionType"))
    }

    @Test
    fun `rejects duplicate checker names before diagnosis runs`() {
        assertFailsWith<IllegalArgumentException> {
            DoctorApplicationService(
                FixedTenantProvider(),
                permissionChecker(AuthorizationDecision(true)),
                listOf(object : DoctorChecker {
                    override fun name(): String = PermissionDoctorChecker.NAME
                    override fun check(context: ai.icen.fw.core.context.DoctorCheckContext): DoctorCheckResult =
                        DoctorCheckResult(name(), DoctorStatus.HEALTHY, "Duplicate checker.")
                }),
                fixedClock(),
            )
        }
    }

    @Test
    fun `records doctor failure without allowing metrics backend failures to change report`() {
        val metrics = RecordingMetrics()
        val report = DoctorApplicationService(
            FixedTenantProvider(),
            permissionChecker(AuthorizationDecision(false, "missing permission")),
            emptyList(),
            fixedClock(),
            metrics,
        ).inspectDocument(Identifier("document-1"))

        assertEquals(DoctorStatus.ERROR, report.status)
        assertEquals(listOf(FileWeftMetric.DOCTOR_FAILURE), metrics.metrics)

        val unaffected = DoctorApplicationService(
            FixedTenantProvider(), permissionChecker(AuthorizationDecision(false, "missing permission")),
            emptyList(), fixedClock(), ThrowingMetrics,
        ).inspectDocument(Identifier("document-1"))
        assertEquals(DoctorStatus.ERROR, unaffected.status)
    }

    @Test
    fun `bounds checker registration before any diagnosis runs`() {
        val checkers = (1..65).map { index ->
            object : DoctorChecker {
                override fun name(): String = "checker-$index"
                override fun check(context: ai.icen.fw.core.context.DoctorCheckContext): DoctorCheckResult =
                    DoctorCheckResult(name(), DoctorStatus.HEALTHY, "Healthy.")
            }
        }

        assertFailsWith<IllegalArgumentException> {
            DoctorApplicationService(
                FixedTenantProvider(),
                permissionChecker(AuthorizationDecision(true)),
                checkers,
                fixedClock(),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            DoctorApplicationService(
                FixedTenantProvider(),
                permissionChecker(AuthorizationDecision(true)),
                listOf(object : DoctorChecker {
                    override fun name(): String = "unsafe\u0000checker"
                    override fun check(context: ai.icen.fw.core.context.DoctorCheckContext): DoctorCheckResult =
                        DoctorCheckResult("unused", DoctorStatus.HEALTHY, "Healthy.")
                }),
                fixedClock(),
            )
        }
    }

    @Test
    fun `normalizes bounded diagnostics and removes sensitive evidence keys`() {
        val evidence = linkedMapOf("accessToken" to "must-not-survive")
        (1..40).forEach { index -> evidence["safe-$index"] = "v".repeat(2_000) }
        val report = DoctorApplicationService(
            FixedTenantProvider(),
            permissionChecker(AuthorizationDecision(true)),
            listOf(object : DoctorChecker {
                override fun name(): String = "bounded"
                override fun check(context: ai.icen.fw.core.context.DoctorCheckContext): DoctorCheckResult =
                    DoctorCheckResult(
                        name(),
                        DoctorStatus.WARNING,
                        "reason\u0000" + "x".repeat(3_000),
                        evidence,
                        "repair" + "y".repeat(5_000),
                    )
            }),
            fixedClock(),
        ).inspectDocument(Identifier("document-1"))

        val bounded = report.checks.single { it.checkerName == "bounded" }
        assertEquals(2_048, bounded.reason.length)
        assertFalse(bounded.reason.contains('\u0000'))
        assertEquals(32, bounded.evidence.size)
        assertFalse(bounded.evidence.containsKey("accessToken"))
        assertTrue(bounded.evidence.values.all { it.length == 1_024 })
        assertEquals(4_096, bounded.repairSuggestion?.length)
    }

    @Test
    fun `freezes checker contract names at registration`() {
        var nameCalls = 0
        val checker = object : DoctorChecker {
            override fun name(): String {
                nameCalls++
                return if (nameCalls == 1) "stable" else "changed"
            }

            override fun check(context: ai.icen.fw.core.context.DoctorCheckContext): DoctorCheckResult =
                DoctorCheckResult("stable", DoctorStatus.HEALTHY, "Stable result.")
        }
        val service = DoctorApplicationService(
            FixedTenantProvider(),
            permissionChecker(AuthorizationDecision(true)),
            listOf(checker),
            fixedClock(),
        )

        val report = service.inspectDocument(Identifier("document-1"))

        assertEquals(1, nameCalls)
        assertEquals(DoctorStatus.HEALTHY, report.checks.single { it.checkerName == "stable" }.status)
    }

    private fun permissionChecker(decision: AuthorizationDecision) = PermissionDoctorChecker(
        FixedUserRealmProvider(),
        FixedAuthorizationProvider(decision),
    )

    private fun fixedClock(): Clock = Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)

    private class RecordingMetrics : FileWeftMetrics {
        val metrics = mutableListOf<FileWeftMetric>()
        override fun increment(metric: FileWeftMetric, tags: Map<String, String>) { metrics += metric }
    }

    private object ThrowingMetrics : FileWeftMetrics {
        override fun increment(metric: FileWeftMetric, tags: Map<String, String>) = throw IllegalStateException("metrics offline")
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }
}
