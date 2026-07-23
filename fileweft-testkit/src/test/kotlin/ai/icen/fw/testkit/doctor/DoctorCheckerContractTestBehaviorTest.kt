package ai.icen.fw.testkit.doctor

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.doctor.DoctorChecker
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException

class DoctorCheckerContractTestBehaviorTest : DoctorCheckerContractTest() {
    override val doctorChecker: DoctorChecker = object : DoctorChecker {
        override fun name(): String = "behavior-doctor"

        override fun check(context: DoctorCheckContext): DoctorCheckResult = DoctorCheckResult(
            checkerName = name(),
            status = DoctorStatus.HEALTHY,
            reason = "Behavior checker is healthy.",
            evidence = mapOf("state" to "healthy"),
            repairSuggestion = "No repair is required.",
        )
    }

    override fun checkContext(): DoctorCheckContext = DoctorCheckContext(Identifier("tenant-contract"))

    @Test
    fun `contract rejects a checker that throws`() {
        assertContractFails(
            object : DoctorChecker {
                override fun name(): String = "throwing-doctor"

                override fun check(context: DoctorCheckContext): DoctorCheckResult =
                    throw IllegalStateException("controlled failure")
            },
        )
    }

    @Test
    fun `contract rejects a checker with an unsafe name`() {
        assertContractFails(
            object : DoctorChecker {
                override fun name(): String = "doctor\u0000name"

                override fun check(context: DoctorCheckContext): DoctorCheckResult =
                    DoctorCheckResult(name(), DoctorStatus.HEALTHY, "Controlled healthy result.")
            },
        )
    }

    @Test
    fun `contract rejects a checker with a mismatched result name`() {
        assertContractFails(
            object : DoctorChecker {
                override fun name(): String = "renamed-doctor"

                override fun check(context: DoctorCheckContext): DoctorCheckResult =
                    DoctorCheckResult("other-doctor", DoctorStatus.HEALTHY, "Controlled healthy result.")
            },
        )
    }

    @Test
    fun `contract rejects a checker with a nondeterministic status`() {
        assertContractFails(
            object : DoctorChecker {
                private var calls = 0

                override fun name(): String = "flaky-doctor"

                override fun check(context: DoctorCheckContext): DoctorCheckResult {
                    calls += 1
                    val status = if (calls % 2 == 0) DoctorStatus.ERROR else DoctorStatus.HEALTHY
                    return DoctorCheckResult(name(), status, "Controlled alternating result.")
                }
            },
        )
    }

    @Test
    fun `contract rejects sensitive evidence keys`() {
        listOf("dbPassword", "api.secret", "AccessKeyId", "x-private-key").forEach { key ->
            assertContractFails(
                object : DoctorChecker {
                    override fun name(): String = "sensitive-doctor"

                    override fun check(context: DoctorCheckContext): DoctorCheckResult = DoctorCheckResult(
                        checkerName = name(),
                        status = DoctorStatus.WARNING,
                        reason = "Controlled warning result.",
                        evidence = mapOf(key to "redacted"),
                    )
                },
            )
        }
    }

    @Test
    fun `contract rejects output beyond the normalized doctor bounds`() {
        assertContractFails(
            object : DoctorChecker {
                override fun name(): String = "verbose-doctor"

                override fun check(context: DoctorCheckContext): DoctorCheckResult = DoctorCheckResult(
                    checkerName = name(),
                    status = DoctorStatus.HEALTHY,
                    reason = "r".repeat(2_049),
                )
            },
        )
        assertContractFails(
            object : DoctorChecker {
                override fun name(): String = "chatty-doctor"

                override fun check(context: DoctorCheckContext): DoctorCheckResult = DoctorCheckResult(
                    checkerName = name(),
                    status = DoctorStatus.HEALTHY,
                    reason = "Controlled healthy result.",
                    evidence = (1..33).associate { index -> "key-$index" to "value" },
                )
            },
        )
    }

    private fun assertContractFails(checker: DoctorChecker) {
        val failing = object : DoctorCheckerContractTest() {
            override val doctorChecker: DoctorChecker = checker

            override fun checkContext(): DoctorCheckContext = this@DoctorCheckerContractTestBehaviorTest.checkContext()
        }
        val violations = DoctorCheckerContractTest::class.java.methods
            .filter { method -> method.isAnnotationPresent(Test::class.java) }
            .mapNotNull { method ->
                try {
                    method.invoke(failing)
                    null
                } catch (failure: InvocationTargetException) {
                    failure.cause as? AssertionError
                }
            }

        assertTrue(
            violations.isNotEmpty(),
            "Expected the contract to reject the violating checker.",
        )
    }
}
