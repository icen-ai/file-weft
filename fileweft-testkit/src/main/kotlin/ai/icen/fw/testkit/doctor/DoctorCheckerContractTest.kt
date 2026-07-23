package ai.icen.fw.testkit.doctor

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.spi.doctor.DoctorChecker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

/**
 * Reusable contract for a [DoctorChecker]. The orchestrating service bounds,
 * normalizes and isolates checker output, but a well-behaved checker must
 * already satisfy these invariants itself: never throw, stay deterministic,
 * keep evidence free of credentials and keep every field bounded.
 */
abstract class DoctorCheckerContractTest {
    protected abstract val doctorChecker: DoctorChecker

    protected abstract fun checkContext(): DoctorCheckContext

    @Test
    fun `reports a non-blank checker name`() {
        assertTrue(doctorChecker.name().isNotBlank(), "Doctor checker name must not be blank.")
    }

    @Test
    fun `reports a bounded and transport safe checker name`() {
        val name = doctorChecker.name()

        assertTrue(name.length <= MAX_CHECKER_NAME_LENGTH, "Doctor checker name must stay within the registration bound.")
        assertTrue(
            name.none { character ->
                Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
            },
            "Doctor checker name must not contain control or format characters.",
        )
    }

    @Test
    fun `check result names the checker and provides a reason`() {
        val result = doctorChecker.check(checkContext())

        assertEquals(doctorChecker.name(), result.checkerName, "Result checker name must match the checker.")
        assertTrue(result.reason.isNotBlank(), "Doctor check result must include a reason.")
    }

    @Test
    fun `check completes without throwing`() {
        val result = assertDoesNotThrow<DoctorCheckResult> {
            doctorChecker.check(checkContext())
        }

        assertEquals(doctorChecker.name(), result.checkerName, "Result checker name must match the checker.")
    }

    @Test
    fun `repeated checks with the same input report a stable outcome`() {
        val first = doctorChecker.check(checkContext())
        val second = doctorChecker.check(checkContext())

        assertEquals(first.checkerName, second.checkerName, "Repeated checks must keep the checker name.")
        assertEquals(first.status, second.status, "Repeated checks with the same input must report the same status.")
    }

    @Test
    fun `evidence keys never carry sensitive markers`() {
        val result = doctorChecker.check(checkContext())

        result.evidence.keys.forEach { key ->
            val normalized = key.lowercase().filter(Char::isLetterOrDigit)
            SENSITIVE_EVIDENCE_MARKERS.forEach { marker ->
                assertFalse(
                    normalized.contains(marker),
                    "Doctor evidence key must not contain the sensitive marker '$marker': $key",
                )
            }
        }
    }

    @Test
    fun `result output stays within the normalized doctor bounds`() {
        val result = doctorChecker.check(checkContext())

        assertTrue(
            result.reason.length <= MAX_REASON_LENGTH,
            "Doctor check reason must stay within the normalization bound.",
        )
        assertTrue(
            (result.repairSuggestion?.length ?: 0) <= MAX_REPAIR_LENGTH,
            "Doctor repair suggestion must stay within the normalization bound.",
        )
        assertTrue(
            result.evidence.size <= MAX_EVIDENCE_ENTRIES,
            "Doctor evidence must stay within the normalization entry bound.",
        )
        result.evidence.forEach { (key, value) ->
            assertTrue(key.length <= MAX_EVIDENCE_KEY_LENGTH, "Doctor evidence keys must stay within the normalization bound.")
            assertTrue(
                value.length <= MAX_EVIDENCE_VALUE_LENGTH,
                "Doctor evidence values must stay within the normalization bound.",
            )
        }
    }

    private companion object {
        // Bounds and sensitive markers mirror the DoctorApplicationService
        // normalization contract; a checker violating them relies on the
        // orchestrator to truncate or drop its output instead of complying.
        const val MAX_CHECKER_NAME_LENGTH = 128
        const val MAX_REASON_LENGTH = 2_048
        const val MAX_REPAIR_LENGTH = 4_096
        const val MAX_EVIDENCE_ENTRIES = 32
        const val MAX_EVIDENCE_KEY_LENGTH = 128
        const val MAX_EVIDENCE_VALUE_LENGTH = 1_024
        val SENSITIVE_EVIDENCE_MARKERS = setOf(
            "password", "secret", "token", "credential", "authorization", "accesskey", "privatekey",
        )
    }
}
