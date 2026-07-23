package ai.icen.fw.application.doctor

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.doctor.DoctorChecker
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TimeoutDoctorCheckerTest {
    private val context = DoctorCheckContext(Identifier("tenant-1"), Identifier("document-1"))

    @Test
    fun `returns the delegate result when the check completes within the budget`() {
        val result = TimeoutDoctorChecker(
            object : DoctorChecker {
                override fun name(): String = "healthy"
                override fun check(context: DoctorCheckContext): DoctorCheckResult =
                    DoctorCheckResult(name(), DoctorStatus.WARNING, "Degraded.", mapOf("queue" to "3"), "Drain the queue.")
            },
            timeoutMillis = 5_000,
        ).check(context)

        assertEquals("healthy", result.checkerName)
        assertEquals(DoctorStatus.WARNING, result.status)
        assertEquals("Degraded.", result.reason)
        assertEquals(mapOf("queue" to "3"), result.evidence)
        assertEquals("Drain the queue.", result.repairSuggestion)
    }

    @Test
    fun `bounds a stalled check to a fixed error result within the time budget`() {
        val startedAt = System.nanoTime()
        val result = TimeoutDoctorChecker(
            object : DoctorChecker {
                override fun name(): String = "stalled"
                override fun check(context: DoctorCheckContext): DoctorCheckResult {
                    Thread.sleep(60_000)
                    return DoctorCheckResult(name(), DoctorStatus.HEALTHY, "Never returned.")
                }
            },
            timeoutMillis = 50,
        ).check(context)
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals("stalled", result.checkerName)
        assertEquals(DoctorStatus.ERROR, result.status)
        assertEquals(TimeoutDoctorChecker.TIMEOUT_REASON, result.reason)
        assertNotNull(result.repairSuggestion)
        assertTrue(result.evidence.isEmpty(), "Timeout results must not leak thread details.")
        assertTrue(elapsedMillis < 10_000, "The check must return within a bounded time, took ${elapsedMillis}ms.")
    }

    @Test
    fun `bounds a check that ignores interruption without blocking the caller`() {
        val startedAt = System.nanoTime()
        val result = TimeoutDoctorChecker(
            object : DoctorChecker {
                override fun name(): String = "uninterruptible"
                override fun check(context: DoctorCheckContext): DoctorCheckResult {
                    // Simulates a checker stuck in uninterruptible I/O: it swallows
                    // interrupts and only returns under its own slow deadline.
                    val deadline = System.nanoTime() + 2_000_000_000L
                    while (System.nanoTime() < deadline) {
                        try {
                            Thread.sleep(10)
                        } catch (ignored: InterruptedException) {
                            // Deliberately uninterruptible for this test.
                        }
                    }
                    return DoctorCheckResult(name(), DoctorStatus.HEALTHY, "Late result.")
                }
            },
            timeoutMillis = 50,
        ).check(context)
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(DoctorStatus.ERROR, result.status)
        assertEquals(TimeoutDoctorChecker.TIMEOUT_REASON, result.reason)
        assertTrue(elapsedMillis < 1_500, "An uninterruptible checker must not delay the caller, took ${elapsedMillis}ms.")
    }

    @Test
    fun `propagates delegate failures so the pipeline can classify them`() {
        val checker = TimeoutDoctorChecker(
            object : DoctorChecker {
                override fun name(): String = "failing"
                override fun check(context: DoctorCheckContext): DoctorCheckResult =
                    throw IllegalStateException("dependency unavailable")
            },
            timeoutMillis = 5_000,
        )

        val failure = assertFailsWith<IllegalStateException> { checker.check(context) }
        assertEquals("dependency unavailable", failure.message)
    }

    @Test
    fun `fails bounded when the executor rejects work instead of queueing without bound`() {
        val saturated = Executors.newSingleThreadExecutor()
        saturated.shutdown()
        val result = TimeoutDoctorChecker(
            object : DoctorChecker {
                override fun name(): String = "rejected"
                override fun check(context: DoctorCheckContext): DoctorCheckResult =
                    DoctorCheckResult(name(), DoctorStatus.HEALTHY, "Never submitted.")
            },
            timeoutMillis = 5_000,
            executor = saturated,
        ).check(context)

        assertEquals("rejected", result.checkerName)
        assertEquals(DoctorStatus.ERROR, result.status)
        assertEquals(TimeoutDoctorChecker.TIMEOUT_REASON, result.reason)
    }

    @Test
    fun `normalizes a timed out plugin style checker through the doctor pipeline`() {
        val report = DoctorApplicationService(
            FixedTenantProvider(),
            PermissionDoctorChecker(FixedUserRealmProvider(), FixedAuthorizationProvider(AuthorizationDecision(true))),
            listOf(
                TimeoutDoctorChecker(
                    object : DoctorChecker {
                        override fun name(): String = "plugin-stalled"
                        override fun check(context: DoctorCheckContext): DoctorCheckResult {
                            Thread.sleep(60_000)
                            return DoctorCheckResult(name(), DoctorStatus.HEALTHY, "Never returned.")
                        }
                    },
                    timeoutMillis = 50,
                ),
            ),
            fixedClock(),
        ).inspectDocument(Identifier("document-1"))

        val stalled = report.checks.single { it.checkerName == "plugin-stalled" }
        assertEquals(DoctorStatus.ERROR, report.status)
        assertEquals(DoctorStatus.ERROR, stalled.status)
        assertEquals(TimeoutDoctorChecker.TIMEOUT_REASON, stalled.reason)
        assertNotNull(stalled.repairSuggestion)
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)
}
