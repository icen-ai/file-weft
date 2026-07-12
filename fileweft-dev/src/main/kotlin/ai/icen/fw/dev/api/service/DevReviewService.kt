package ai.icen.fw.dev.api.service

import ai.icen.fw.application.catalog.DocumentCatalogLifecycleService
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.dev.api.config.DevRole
import ai.icen.fw.dev.api.security.DevUserDirectory
import ai.icen.fw.domain.workflow.WorkflowInstance
import ai.icen.fw.spi.tenant.TenantProvider

class DevReviewService(
    private val access: DevAccessService,
    private val users: DevUserDirectory,
    private val tenants: TenantProvider,
    private val workflows: DocumentCatalogLifecycleService,
) {
    fun submit(documentId: Identifier, reviewerId: String?, reviewRouteId: String?): WorkflowInstance {
        access.requireDocumentAction(documentId, SUBMIT_ACTION)
        val reviewer = reviewerId?.takeIf { it.isNotBlank() }?.let(::Identifier)?.let { users.findById(it) }
        if (reviewer != null) {
            require(reviewer.tenantId == tenants.currentTenant().tenantId) { "Reviewer must belong to the current tenant." }
            require(reviewer.role == DevRole.REVIEWER || reviewer.role == DevRole.ADMIN) {
                "Assigned reviewer must have REVIEWER or ADMIN role."
            }
        } else {
            require(reviewerId.isNullOrBlank()) { "Reviewer was not found." }
        }
        return workflows.submitForReview(documentId, reviewer?.id, reviewRouteId)
    }

    private companion object {
        const val SUBMIT_ACTION = "document:submit"
    }
}
