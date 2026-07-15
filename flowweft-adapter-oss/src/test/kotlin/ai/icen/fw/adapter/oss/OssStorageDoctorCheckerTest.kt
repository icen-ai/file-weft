package ai.icen.fw.adapter.oss

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

class OssStorageDoctorCheckerTest {
    @Test
    fun `returns bounded side-effect-free healthy evidence`() {
        val fixture = fixture()

        val result = fixture.doctor.check(DoctorCheckContext(Identifier("tenant-secret")))

        assertEquals(DoctorStatus.HEALTHY, result.status)
        assertEquals("v4", result.evidence["signatureVersion"])
        assertEquals("aliyun-oss-java-v1", result.evidence["sdkFamily"])
        assertEvidenceIsSafe(result.evidence)
    }

    @Test
    fun `turns provider denial into actionable sanitized evidence`() {
        val fixture = fixture()
        fixture.client.bucketFailure = serviceFailure(
            "AccessDenied",
            "bucket=flowweft-oss-test Authorization=secret request-id=private",
        )

        val result = fixture.doctor.check(DoctorCheckContext(Identifier("tenant-secret")))

        assertEquals(DoctorStatus.ERROR, result.status)
        assertEquals("AUTHORIZATION", result.evidence["failureCategory"])
        assertEquals("CHECK_BUCKET", result.evidence["operation"])
        assertTrue(result.repairSuggestion.orEmpty().contains("least-privilege"))
        assertEvidenceIsSafe(result.evidence)
        assertFalse(result.reason.contains("private"))
    }

    private fun assertEvidenceIsSafe(evidence: Map<String, String>) {
        val rendered = evidence.entries.joinToString("|") { "${it.key}=${it.value}" }
        assertFalse(rendered.contains("oss-cn-hangzhou.aliyuncs.com"))
        assertFalse(rendered.contains("flowweft-oss-test"))
        assertFalse(rendered.contains("access-secret"))
        assertFalse(rendered.contains("tenant-secret"))
        assertFalse(rendered.contains("request-id"))
        assertFalse(rendered.contains("Authorization"))
    }

    private fun fixture(): Fixture {
        val client = FakeOssClientFacade()
        val configuration = OssStorageConfiguration(
            URI.create("https://oss-cn-hangzhou.aliyuncs.com"),
            "cn-hangzhou",
            "flowweft-oss-test",
            StaticOssCredentialsProvider("access-key", "access-secret"),
        )
        val adapter = OssStorageAdapter.testInstance(configuration, OssStorageClientPolicy(), client)
        return Fixture(client, OssStorageDoctorChecker(adapter))
    }

    private data class Fixture(
        val client: FakeOssClientFacade,
        val doctor: OssStorageDoctorChecker,
    )
}
