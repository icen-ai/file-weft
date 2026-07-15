package ai.icen.fw.adapter.s3

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.doctor.DoctorChecker
import java.util.LinkedHashMap

/**
 * Side-effect-free connectivity, credential and bucket-access diagnosis for
 * any S3-compatible service, including the maintained RustFS profile.
 */
class S3StorageDoctorChecker(
    private val adapter: S3StorageAdapter,
) : DoctorChecker {
    override fun name(): String = NAME

    override fun check(@Suppress("UNUSED_PARAMETER") context: DoctorCheckContext): DoctorCheckResult {
        // The probe is bucket-scoped. The trusted tenant context is deliberately
        // neither sent to the provider nor copied into bounded evidence.
        val startedAt = System.nanoTime()
        return try {
            adapter.checkBucketAccess()
            DoctorCheckResult(
                checkerName = NAME,
                status = DoctorStatus.HEALTHY,
                reason = "S3-compatible storage bucket is reachable with the configured credentials.",
                evidence = evidence(elapsedMillis(startedAt)),
            )
        } catch (failure: S3StorageOperationException) {
            val evidence = evidence(elapsedMillis(startedAt)).toMutableMap()
            evidence["operation"] = failure.operation.name
            evidence["failureCategory"] = failure.category.name
            evidence["retryable"] = failure.retryable.toString()
            failure.missingResource?.let { resource -> evidence["missingResource"] = resource.name }
            DoctorCheckResult(
                checkerName = NAME,
                status = DoctorStatus.ERROR,
                reason = "S3-compatible storage bucket check failed.",
                evidence = evidence,
                repairSuggestion = repairSuggestion(failure.category),
            )
        } catch (_: Exception) {
            val evidence = evidence(elapsedMillis(startedAt)).toMutableMap()
            evidence["operation"] = S3StorageOperation.CHECK_BUCKET.name
            evidence["failureCategory"] = S3StorageFailureCategory.UNKNOWN.name
            evidence["retryable"] = false.toString()
            DoctorCheckResult(
                checkerName = NAME,
                status = DoctorStatus.ERROR,
                reason = "S3-compatible storage bucket check failed without a classified provider response.",
                evidence = evidence,
                repairSuggestion = "Verify adapter lifecycle and configuration, then inspect trusted server-side logs.",
            )
        }
    }

    private fun evidence(elapsedMillis: Long): Map<String, String> = LinkedHashMap(adapter.diagnosticEvidence()).apply {
        put("elapsedMillis", elapsedMillis.toString())
    }

    private fun repairSuggestion(category: S3StorageFailureCategory): String = when (category) {
        S3StorageFailureCategory.AUTHENTICATION ->
            "Verify the configured access-key reference, secret-key reference, and signing clock."
        S3StorageFailureCategory.AUTHORIZATION ->
            "Grant the configured identity bucket and object permissions required by FlowWeft."
        S3StorageFailureCategory.NOT_FOUND ->
            "Create the configured bucket through an authorized bootstrap process or correct the bucket configuration."
        S3StorageFailureCategory.CONFIGURATION ->
            "Verify endpoint TLS, region, path-style mode, DNS and signing-clock configuration."
        S3StorageFailureCategory.TIMEOUT,
        S3StorageFailureCategory.THROTTLED,
        S3StorageFailureCategory.UNAVAILABLE,
        -> "Check service capacity and network reachability, then retry after the dependency recovers."
        S3StorageFailureCategory.CONFLICT,
        S3StorageFailureCategory.INVALID_REQUEST,
        S3StorageFailureCategory.INTEGRITY,
        S3StorageFailureCategory.UNKNOWN,
        -> "Verify S3-compatible service configuration and inspect trusted server-side logs."
    }

    private fun elapsedMillis(startedAt: Long): Long =
        Math.max(0L, (System.nanoTime() - startedAt) / NANOS_PER_MILLISECOND)

    companion object {
        const val NAME = "s3-storage"
        private const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
