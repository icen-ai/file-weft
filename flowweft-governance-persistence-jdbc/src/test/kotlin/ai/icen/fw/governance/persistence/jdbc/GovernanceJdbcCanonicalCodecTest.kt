package ai.icen.fw.governance.persistence.jdbc

import ai.icen.fw.governance.runtime.GovernanceDeletionTargetItemOperation
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

    @Test
    fun `round trips target manifest and item operation with exact canonical digests`() {
        val scenario = GovernanceJdbcTestFixture.targetLedgerScenario()
        val manifestBytes = GovernanceTargetJdbcCanonicalCodec.encodeManifest(scenario.manifest)
        val restoredManifest = GovernanceTargetJdbcCanonicalCodec.decodeManifest(
            manifestBytes, scenario.manifest.manifestDigest,
        )
        assertEquals(scenario.manifest.manifestDigest, restoredManifest.manifestDigest)
        assertEquals(
            scenario.manifest.items.map { it.itemBindingDigest },
            restoredManifest.items.map { it.itemBindingDigest },
        )
        assertEquals(
            scenario.manifest.items.map { it.itemIdentityDigest },
            restoredManifest.items.map { it.itemIdentityDigest },
        )

        val prepared = GovernanceDeletionTargetItemOperation.prepared(
            scenario.request,
            scenario.manifest,
            scenario.item,
            "canonical-operation-1",
            2_001L,
        )
        val started = GovernanceDeletionTargetItemOperation.markStarted(prepared, 2_002L)
        val operationBytes = GovernanceTargetJdbcCanonicalCodec.encodeOperation(started)
        val restoredOperation = GovernanceTargetJdbcCanonicalCodec.decodeOperation(
            operationBytes, started.stateDigest,
        )
        assertEquals(started.stateDigest, restoredOperation.stateDigest)
        assertEquals(started.binding.bindingDigest, restoredOperation.binding.bindingDigest)

        assertFailsWith<IllegalArgumentException> {
            GovernanceTargetJdbcCanonicalCodec.decodeManifest(
                manifestBytes.copyOf(manifestBytes.size - 1), scenario.manifest.manifestDigest,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GovernanceTargetJdbcCanonicalCodec.decodeOperation(
                operationBytes, GovernanceJdbcTestFixture.digest('f'),
            )
        }
    }

    @Test
    fun `target codec rejects invalid utf8 trailing bytes oversized input and malformed row digest`() {
        val scenario = GovernanceJdbcTestFixture.targetLedgerScenario()
        val bytes = GovernanceTargetJdbcCanonicalCodec.encodeManifest(scenario.manifest)

        // Header is three ints, followed by the first length-prefixed UTF-8 tenant value.
        val invalidUtf8 = bytes.copyOf().also { it[16] = 0x80.toByte() }
        assertFailsWith<IllegalArgumentException> {
            GovernanceTargetJdbcCanonicalCodec.decodeManifest(
                invalidUtf8, scenario.manifest.manifestDigest,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GovernanceTargetJdbcCanonicalCodec.decodeManifest(
                bytes + byteArrayOf(0), scenario.manifest.manifestDigest,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GovernanceTargetJdbcCanonicalCodec.decodeManifest(
                ByteArray(GovernanceTargetJdbcCanonicalCodec.MAX_MEMENTO_BYTES + 1),
                scenario.manifest.manifestDigest,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GovernanceTargetJdbcCanonicalCodec.decodeManifest(bytes, "A".repeat(64))
        }
    }
}
