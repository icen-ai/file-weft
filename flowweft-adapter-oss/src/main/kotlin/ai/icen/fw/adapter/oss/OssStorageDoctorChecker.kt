package ai.icen.fw.adapter.oss

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.doctor.DoctorChecker
import java.util.LinkedHashMap

/** Side-effect-free OSS bucket, SigV4, credential and network diagnosis. */
class OssStorageDoctorChecker(
    private val adapter: OssStorageAdapter,
) : DoctorChecker {
    override fun name(): String = NAME

    override fun check(@Suppress("UNUSED_PARAMETER") context: DoctorCheckContext): DoctorCheckResult {
        val startedAt = System.nanoTime()
        return try {
            adapter.checkBucketAccess()
            DoctorCheckResult(
                NAME,
                DoctorStatus.HEALTHY,
                "Alibaba Cloud OSS bucket is reachable with the configured RAM or STS identity.",
                evidence(elapsedMillis(startedAt)),
            )
        } catch (failure: OssStorageOperationException) {
            val evidence = evidence(elapsedMillis(startedAt)).toMutableMap().apply {
                put("operation", failure.operation.name)
                put("failureCategory", failure.category.name)
                put("retryable", failure.retryable.toString())
            }
            DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "Alibaba Cloud OSS bucket check failed.",
                evidence,
                repairSuggestion(failure.category),
            )
        } catch (_: Exception) {
            DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "Alibaba Cloud OSS bucket check failed without a classified provider response.",
                evidence(elapsedMillis(startedAt)),
                "Verify adapter lifecycle and inspect trusted server-side logs.",
            )
        }
    }

    private fun evidence(elapsedMillis: Long): Map<String, String> =
        LinkedHashMap(adapter.diagnosticEvidence()).apply { put("elapsedMillis", elapsedMillis.toString()) }

    private fun repairSuggestion(category: OssStorageFailureCategory): String = when (category) {
        OssStorageFailureCategory.AUTHENTICATION ->
            "Verify the RAM Role or STS credential source, token expiry, signing clock, and access-key references."
        OssStorageFailureCategory.AUTHORIZATION ->
            "Grant the least-privilege OSS bucket, object, and multipart actions required by FlowWeft."
        OssStorageFailureCategory.NOT_FOUND ->
            "Create the configured bucket through an authorized bootstrap process or correct the bucket configuration."
        OssStorageFailureCategory.CONFIGURATION ->
            "Verify the HTTPS endpoint, region, SigV4, DNS, CNAME/path-style mode, and TLS trust configuration."
        OssStorageFailureCategory.TIMEOUT,
        OssStorageFailureCategory.THROTTLED,
        OssStorageFailureCategory.UNAVAILABLE,
        -> "Check OSS and network capacity, then retry after the dependency recovers."
        OssStorageFailureCategory.CONFLICT,
        OssStorageFailureCategory.INVALID_REQUEST,
        OssStorageFailureCategory.INTEGRITY,
        OssStorageFailureCategory.UNKNOWN,
        -> "Verify OSS configuration and inspect sanitized trusted server-side diagnostics."
    }

    private fun elapsedMillis(startedAt: Long): Long =
        Math.max(0L, (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND)

    companion object {
        const val NAME = "oss-storage"
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
