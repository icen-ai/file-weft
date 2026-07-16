package ai.icen.fw.application.doctor

import ai.icen.fw.application.retention.DeletionVisibilityGuard
import ai.icen.fw.application.retention.DeletionVisibilityQuery
import ai.icen.fw.application.retention.DeletionVisibilityUnavailableException
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.doctor.DoctorChecker

/** Verifies that the mandatory durable tombstone projection is queryable. */
class DeletionVisibilityDoctorChecker(
    query: DeletionVisibilityQuery,
    private val transaction: ApplicationTransaction,
) : DoctorChecker {
    private val guard = DeletionVisibilityGuard.create(query)

    override fun name(): String = NAME

    override fun check(context: DoctorCheckContext): DoctorCheckResult = try {
        val resourceId = context.documentId ?: PROBE_RESOURCE_ID
        val fence = transaction.execute {
            guard.fence(context.tenantId, DOCUMENT_RESOURCE_TYPE, resourceId)
        }
        if (fence == null) {
            DoctorCheckResult(
                NAME,
                DoctorStatus.HEALTHY,
                "Deletion visibility enforcement is available.",
            )
        } else {
            DoctorCheckResult(
                NAME,
                DoctorStatus.HEALTHY,
                "The resource is protected by a durable deletion visibility fence.",
                evidence = mapOf(
                    "resourceRevision" to fence.resourceRevision.toString(),
                    "blockedAt" to fence.blockedAt.toString(),
                ),
                repairSuggestion = "Keep normal reads fenced until every secure-deletion provider reports verified absence.",
            )
        }
    } catch (_: DeletionVisibilityUnavailableException) {
        unavailable()
    } catch (_: Exception) {
        unavailable()
    }

    private fun unavailable(): DoctorCheckResult = DoctorCheckResult(
        NAME,
        DoctorStatus.ERROR,
        "Deletion visibility enforcement could not query its durable tombstone projection.",
        repairSuggestion = "Run the current FlowWeft migrations and configure a tenant-scoped deletion visibility query before enabling document reads.",
    )

    companion object {
        const val NAME: String = "deletion-visibility"
        private const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        private val PROBE_RESOURCE_ID = Identifier("flowweft-deletion-visibility-probe")
    }
}
