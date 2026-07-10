package com.fileweft.domain.workflow

import com.fileweft.core.id.Identifier

interface WorkflowInstanceRepository {
    fun findById(tenantId: Identifier, workflowId: Identifier): WorkflowInstance?

    fun findActiveByDocument(tenantId: Identifier, documentId: Identifier): WorkflowInstance?

    fun save(workflow: WorkflowInstance)
}
