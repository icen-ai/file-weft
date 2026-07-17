package ai.icen.fw.application.document

import ai.icen.fw.application.retention.DeletionVisibilityFence
import ai.icen.fw.application.retention.DeletionVisibilityGuard
import ai.icen.fw.application.retention.DeletionVisibilityQuery
import ai.icen.fw.core.id.Identifier

internal fun visibleDeletionGuard(): DeletionVisibilityGuard = DeletionVisibilityGuard.create(
    object : DeletionVisibilityQuery {
        override fun findFence(
            tenantId: Identifier,
            resourceType: String,
            resourceId: Identifier,
        ): DeletionVisibilityFence? = null
    },
)
