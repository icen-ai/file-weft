package ai.icen.fw.reliability.runtime

import ai.icen.fw.reliability.api.ReliabilityDoctorFinding
import ai.icen.fw.reliability.api.ReliabilityDoctorFindingCode
import ai.icen.fw.reliability.api.ReliabilityDoctorMode
import ai.icen.fw.reliability.api.ReliabilityDoctorReport
import ai.icen.fw.reliability.api.ReliabilityDoctorRequest
import ai.icen.fw.reliability.api.ReliabilityDoctorSeverity
import ai.icen.fw.reliability.api.ReliabilityDoctorStatus
import ai.icen.fw.reliability.api.ReliabilityMetricComponentClass
import ai.icen.fw.reliability.api.ReliabilityProviderResultStatus
import ai.icen.fw.reliability.api.ReliabilityPurpose
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class ReliabilityRuntimeDoctor(
    private val calls: ReliabilityAuthorizedCallFactory,
    private val clock: ReliabilityRuntimeClock,
    private val providers: ReliabilityProviderRegistry,
    diagnosticSources: Collection<ReliabilityRuntimeDiagnosticSource> = emptyList(),
) {
    private val diagnosticSources: List<ReliabilityRuntimeDiagnosticSource> =
        ReliabilityRuntimeSupport.immutable(
            diagnosticSources, 64, "Reliability runtime diagnostic sources are invalid.",
        )

    fun inspect(
        invocation: ReliabilityTrustedInvocation,
        providerId: String,
        mode: ReliabilityDoctorMode,
    ): CompletionStage<ReliabilityDoctorReport> {
        require(invocation.purpose == ReliabilityPurpose.INSPECT_DOCTOR &&
            invocation.action == ai.icen.fw.reliability.api.ReliabilityAction.INSPECT_DOCTOR
        ) { "Reliability runtime Doctor invocation is not authorized for diagnostics." }
        val now = clock.nowEpochMilli().coerceAtLeast(invocation.requestedAtEpochMilli)
        val argument = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-doctor-arguments-v1")
            .text(providerId)
            .text(mode.name)
            .finish()
        val context = calls.create(invocation, "inspect-doctor", argument)
        context.requireFresh(now)
        val request = ReliabilityDoctorRequest.of(context, mode)
        val provider = providers.find(providerId)
        if (provider == null || !provider.descriptor.isCurrent(now)) {
            return completedDoctor(unavailable(request, providerId, "unavailable", now))
        }
        val handled = try {
            provider.spi.doctor(request).handle { result, throwable -> result to throwable }
        } catch (_: RuntimeException) {
            return completedDoctor(unavailable(request, providerId, provider.descriptor.providerRevision, now))
        }
        return handled.thenApply { handledResult ->
            val result = handledResult.first
            val throwable = handledResult.second
            val report = result?.value
            if (throwable != null || result == null || result.status != ReliabilityProviderResultStatus.SUCCESS ||
                report == null || report.requestDigest != request.requestDigest ||
                report.observedAtEpochMilli > now || report.expiresAtEpochMilli <= now
            ) {
                unavailable(request, providerId, provider.descriptor.providerRevision, now)
            } else {
                val local = safeFindings(mode, now)
                if (local.isEmpty()) report else merge(request, report, local, now)
            }
        }
    }

    private fun merge(
        request: ReliabilityDoctorRequest,
        providerReport: ReliabilityDoctorReport,
        local: List<ReliabilityDoctorFinding>,
        now: Long,
    ): ReliabilityDoctorReport {
        val findings = (providerReport.findings + local).take(ReliabilityDoctorReport.MAX_FINDINGS)
        val status = status(findings, providerReport.status)
        return ReliabilityDoctorReport.of(
            request,
            providerReport.providerId,
            providerReport.providerRevision,
            status,
            findings,
            now,
            minOf(providerReport.expiresAtEpochMilli, safeAdd(now, MAX_REPORT_TTL_MILLIS)),
        )
    }

    private fun unavailable(
        request: ReliabilityDoctorRequest,
        providerId: String,
        providerRevision: String,
        now: Long,
    ): ReliabilityDoctorReport = ReliabilityDoctorReport.of(
        request,
        ReliabilityRuntimeSupport.code(providerId, "Reliability Doctor provider id is invalid."),
        ReliabilityRuntimeSupport.text(
            providerRevision,
            ReliabilityRuntimeSupport.MAX_REVISION_BYTES,
            "Reliability Doctor provider revision is invalid.",
        ),
        ReliabilityDoctorStatus.NOT_READY,
        (listOf(
            ReliabilityDoctorFinding.of(
                ReliabilityDoctorFindingCode.PROVIDER_UNREACHABLE,
                ReliabilityDoctorSeverity.ERROR,
                ReliabilityMetricComponentClass.OTHER,
            ),
        ) + safeFindings(request.mode, now)).take(ReliabilityDoctorReport.MAX_FINDINGS),
        now,
        safeAdd(now, MAX_REPORT_TTL_MILLIS),
    )

    private fun safeFindings(mode: ReliabilityDoctorMode, now: Long): List<ReliabilityDoctorFinding> {
        val findings = ArrayList<ReliabilityDoctorFinding>()
        diagnosticSources.forEach { source ->
            val values = try {
                source.findings(mode, now)
            } catch (_: RuntimeException) {
                listOf(
                    ReliabilityDoctorFinding.of(
                        ReliabilityDoctorFindingCode.PROVIDER_UNREACHABLE,
                        ReliabilityDoctorSeverity.ERROR,
                        ReliabilityMetricComponentClass.OTHER,
                    ),
                )
            }
            findings.addAll(values.take(ReliabilityDoctorReport.MAX_FINDINGS - findings.size))
        }
        return findings
    }

    private fun status(
        findings: List<ReliabilityDoctorFinding>,
        providerStatus: ReliabilityDoctorStatus,
    ): ReliabilityDoctorStatus = when {
        findings.any { it.severity == ReliabilityDoctorSeverity.ERROR } -> ReliabilityDoctorStatus.NOT_READY
        findings.any { it.severity == ReliabilityDoctorSeverity.WARNING } -> ReliabilityDoctorStatus.DEGRADED
        providerStatus == ReliabilityDoctorStatus.UNSUPPORTED -> ReliabilityDoctorStatus.UNSUPPORTED
        else -> ReliabilityDoctorStatus.READY
    }

    private fun safeAdd(value: Long, delta: Long): Long =
        if (Long.MAX_VALUE - value < delta) Long.MAX_VALUE else value + delta

    companion object {
        const val MAX_REPORT_TTL_MILLIS: Long = 60_000L
    }
}

private fun <T> completedDoctor(value: T): CompletionStage<T> = CompletableFuture.completedFuture(value)
