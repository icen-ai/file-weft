package com.fileweft.application.doctor

import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorCheckResult
import com.fileweft.core.result.DoctorStatus
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.connector.ConnectorHealth
import com.fileweft.spi.connector.ConnectorHealthStatus
import com.fileweft.spi.doctor.DoctorChecker
import com.fileweft.spi.observability.FileWeftMetric
import com.fileweft.spi.observability.FileWeftMetrics
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
            override fun exists(location: com.fileweft.spi.storage.StorageObjectLocation): Boolean = true
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
                override fun check(context: com.fileweft.core.context.DoctorCheckContext): DoctorCheckResult {
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
                    override fun check(context: com.fileweft.core.context.DoctorCheckContext): DoctorCheckResult =
                        throw IllegalStateException("dependency unavailable")
                },
                object : DoctorChecker {
                    override fun name(): String = "mismatched"
                    override fun check(context: com.fileweft.core.context.DoctorCheckContext): DoctorCheckResult =
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
                    override fun check(context: com.fileweft.core.context.DoctorCheckContext): DoctorCheckResult =
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
