package ai.icen.fw.governance.persistence.jdbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GovernanceJdbcCanonicalCodecTest {
    @Test
    fun `round trips ready and prepared runs with canonical nested evidence`() {
        listOf(
            GovernanceJdbcTestFixture.readyRun(),
            GovernanceJdbcTestFixture.preparedSecondStepRun(),
        ).forEach { run ->
            val restored = GovernanceJdbcCanonicalCodec.decodeRun(
                GovernanceJdbcCanonicalCodec.encodeRun(run),
                run.stateDigest,
            )
            assertEquals(run.stateDigest, restored.stateDigest)
            assertEquals(run.plan.planDigest, restored.plan.planDigest)
            assertEquals(run.successfulReceipts.map { it.receiptDigest },
                restored.successfulReceipts.map { it.receiptDigest })
            assertEquals(run.dispatch?.dispatchDigest, restored.dispatch?.dispatchDigest)
        }
    }

    @Test
    fun `rejects truncation and an independently changed row digest`() {
        val run = GovernanceJdbcTestFixture.readyRun()
        val bytes = GovernanceJdbcCanonicalCodec.encodeRun(run)
        assertFailsWith<IllegalArgumentException> {
            GovernanceJdbcCanonicalCodec.decodeRun(bytes.copyOf(bytes.size - 1), run.stateDigest)
        }
        assertFailsWith<IllegalArgumentException> {
            GovernanceJdbcCanonicalCodec.decodeRun(bytes, GovernanceJdbcTestFixture.digest('f'))
        }
    }
}
