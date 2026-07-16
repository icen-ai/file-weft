package ai.icen.fw.application.retention

import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DeletionVisibilityGuardTest {
    @Test
    fun `ordinary reads remain hidden while strictly newer revisioned work can proceed`() {
        val query = RecordingQuery(fence())
        val guard = DeletionVisibilityGuard.create(query)

        assertFailsWith<DeletedResourceNotVisibleException> {
            guard.requireResourceVisible(TENANT, RESOURCE_TYPE, RESOURCE)
        }
        assertFailsWith<DeletedResourceNotVisibleException> {
            guard.requireRevisionVisible(TENANT, RESOURCE_TYPE, RESOURCE, 7)
        }
        assertFailsWith<DeletedResourceNotVisibleException> {
            guard.requireRevisionVisible(TENANT, RESOURCE_TYPE, RESOURCE, 6)
        }
        guard.requireRevisionVisible(TENANT, RESOURCE_TYPE, RESOURCE, 8)

        assertEquals(TENANT, query.lastTenant)
        assertEquals(RESOURCE_TYPE, query.lastType)
        assertEquals(RESOURCE, query.lastResource)
    }

    @Test
    fun `batch removes only exact tenant bound tombstones`() {
        val blocked = Identifier("document-blocked")
        val visible = Identifier("document-visible")
        val guard = DeletionVisibilityGuard.create(
            object : DeletionVisibilityQuery {
                override fun findFence(
                    tenantId: Identifier,
                    resourceType: String,
                    resourceId: Identifier,
                ): DeletionVisibilityFence? = if (resourceId == blocked) fence(resourceId) else null
            },
        )

        assertEquals(setOf(visible), guard.visibleResourceIds(TENANT, RESOURCE_TYPE, listOf(blocked, visible)))
    }

    @Test
    fun `mismatched malformed and missing capabilities fail closed`() {
        val foreign = DeletionVisibilityGuard.create(
            RecordingQuery(fence(tenantId = Identifier("tenant-foreign"))),
        )
        assertFailsWith<DeletionVisibilityUnavailableException> {
            foreign.requireResourceVisible(TENANT, RESOURCE_TYPE, RESOURCE)
        }

        val failed = DeletionVisibilityGuard.create(
            object : DeletionVisibilityQuery {
                override fun findFence(
                    tenantId: Identifier,
                    resourceType: String,
                    resourceId: Identifier,
                ): DeletionVisibilityFence? = throw IllegalStateException("missing table")
            },
        )
        val failure = assertFailsWith<DeletionVisibilityUnavailableException> {
            failed.requireResourceVisible(TENANT, RESOURCE_TYPE, RESOURCE)
        }
        assertEquals(DeletionVisibilityUnavailableException.DEFAULT_MESSAGE, failure.message)

        val missing = DeletionVisibilityGuard.requireFrom(Any())
        assertFailsWith<DeletionVisibilityUnavailableException> {
            missing.requireResourceVisible(TENANT, RESOURCE_TYPE, RESOURCE)
        }
        assertFailsWith<DeletionVisibilityUnavailableException> {
            missing.visibleResourceIds(TENANT, RESOURCE_TYPE, emptyList())
        }
    }

    @Test
    fun `absent exact fence remains visible`() {
        val guard = DeletionVisibilityGuard.create(RecordingQuery(null))

        guard.requireResourceVisible(TENANT, RESOURCE_TYPE, RESOURCE)
        assertNull(guard.fence(TENANT, RESOURCE_TYPE, RESOURCE))
    }

    private class RecordingQuery(private val result: DeletionVisibilityFence?) : DeletionVisibilityQuery {
        var lastTenant: Identifier? = null
        var lastType: String? = null
        var lastResource: Identifier? = null

        override fun findFence(
            tenantId: Identifier,
            resourceType: String,
            resourceId: Identifier,
        ): DeletionVisibilityFence? {
            lastTenant = tenantId
            lastType = resourceType
            lastResource = resourceId
            return result
        }
    }

    private companion object {
        val TENANT = Identifier("tenant-a")
        val RESOURCE = Identifier("document-a")
        const val RESOURCE_TYPE = "DOCUMENT"

        fun fence(
            resourceId: Identifier = RESOURCE,
            tenantId: Identifier = TENANT,
        ) = DeletionVisibilityFence(
            tombstoneId = Identifier("tombstone-a"),
            planId = Identifier("plan-a"),
            tenantId = tenantId,
            resourceType = RESOURCE_TYPE,
            resourceId = resourceId,
            resourceRevision = 7,
            blockedAt = 100,
        )
    }
}
