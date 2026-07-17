package ai.icen.fw.adapter.dify

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DifyKnowledgeBaseDoctorCheckerTest {
    @Test
    fun `reports explicit supported and unsupported evidence without secrets`() {
        val fixture = doctorFixture()

        val result = fixture.checker.check(DoctorCheckContext(Identifier("tenant-a")))

        assertEquals(DoctorStatus.HEALTHY, result.status)
        assertEquals("1.14.x", result.evidence["apiCompatibility"])
        assertEquals("true", result.evidence["createByFile"])
        assertEquals("true", result.evidence["canonicalFileUpdate"])
        assertEquals("true", result.evidence["statusReadback"])
        assertEquals("durable-required", result.evidence["projectionStore"])
        assertEquals("fenced-unknown", result.evidence["ambiguousWrite"])
        assertEquals("disabled", result.evidence["apiRedirects"])
        assertEquals("disabled", result.evidence["transportRetries"])
        assertEquals("blocked", result.evidence["privateApiAddresses"])
        assertEquals("false", result.evidence["verifiablePurge"])
        assertEquals("false", result.evidence["safeRetrieval"])
        val rendered = result.reason + result.evidence.entries.joinToString() + result.repairSuggestion.orEmpty()
        assertFalse(rendered.contains("dify.example.test"))
        assertFalse(rendered.contains(TEST_DATASET_ID))
        assertFalse(rendered.contains("test-api-key"))
    }

    @Test
    fun `maps degraded dependency evidence to warning with an operator action`() {
        val fixture = doctorFixture()
        fixture.remote.healthResult = DifyHealthReadResult(DifyReadDisposition.RETRYABLE_FAILURE)

        val result = fixture.checker.check(DoctorCheckContext(Identifier("tenant-a")))

        assertEquals(DoctorStatus.WARNING, result.status)
        assertTrue(!result.repairSuggestion.isNullOrBlank())
    }

    @Test
    fun `rejects a doctor request for a tenant not bound to the dedicated dataset`() {
        val fixture = doctorFixture()

        val result = fixture.checker.check(DoctorCheckContext(Identifier("tenant-b")))

        assertEquals(DoctorStatus.ERROR, result.status)
        assertEquals(0, fixture.remote.healthCalls)
    }

    private fun doctorFixture(): DoctorFixture {
        val profile = testProfile()
        val remote = TestDifyRemoteApi()
        val connector = DifyKnowledgeBaseConnector(
            profile,
            InMemoryDifyProjectionStore(),
            TestDifySourceDownloader(),
            remote,
        )
        return DoctorFixture(remote, DifyKnowledgeBaseDoctorChecker(connector, profile))
    }

    private class DoctorFixture(
        val remote: TestDifyRemoteApi,
        val checker: DifyKnowledgeBaseDoctorChecker,
    )
}
