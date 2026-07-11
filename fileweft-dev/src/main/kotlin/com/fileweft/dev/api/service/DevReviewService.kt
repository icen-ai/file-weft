package com.fileweft.dev.api.service

import com.fileweft.core.id.Identifier
import com.fileweft.dev.api.config.DevRole
import com.fileweft.dev.api.security.DevUserDirectory
import com.fileweft.domain.workflow.WorkflowInstance
import com.fileweft.spi.tenant.TenantProvider
import com.fileweft.application.workflow.DocumentReviewWorkflowService

class DevReviewService(
    private val users: DevUserDirectory,
    private val tenants: TenantProvider,
    private val workflows: DocumentReviewWorkflowService,
) {
    fun submit(documentId: Identifier, reviewerId: String?, reviewRouteId: String?): WorkflowInstance {
        val reviewer = reviewerId?.takeIf { it.isNotBlank() }?.let(::Identifier)?.let { users.findById(it) }
        if (reviewer != null) {
            require(reviewer.tenantId == tenants.currentTenant().tenantId) { "Reviewer must belong to the current tenant." }
            require(reviewer.role == DevRole.REVIEWER || reviewer.role == DevRole.ADMIN) {
                "Assigned reviewer must have REVIEWER or ADMIN role."
            }
        } else {
            require(reviewerId.isNullOrBlank()) { "Reviewer was not found." }
        }
        return workflows.submit(documentId, reviewer?.id, reviewRouteId)
    }
}
