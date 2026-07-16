package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class WorkflowOrganizationSpiContractTest {
    @Test
    fun `role position and permission checks are exact snapshot-bound relationships`() {
        val context = WorkflowProviderCallContext.of(
            "organization-request",
            "tenant-a",
            "corp.directory",
            "provider-r7",
            "participant-membership",
            1_000L,
            1_100L,
            4_096,
            4_096,
            16,
        )
        val snapshot = WorkflowOrganizationSnapshot.of("corp.directory", "directory-r19", 1_000L, 1_100L)
        val principal = WorkflowPrincipalRef.of("USER", "reviewer-a")
        val permission = WorkflowOrganizationRef.of("permission", "legal.document.approve")
        val request = WorkflowOrganizationRelationshipRequest.of(
            context,
            snapshot,
            principal,
            permission,
            WorkflowOrganizationRelationshipKind.HAS_PERMISSION,
            1_010L,
        )

        assertSame(WorkflowOrganizationRelationshipKind.HAS_PERMISSION, request.relationship)
        assertEquals("directory-r19", request.snapshot.revision)
        assertNotEquals(
            request.requestDigest,
            WorkflowOrganizationRelationshipRequest.of(
                context,
                snapshot,
                principal,
                permission,
                WorkflowOrganizationRelationshipKind.ASSIGNED_ROLE,
                1_010L,
            ).requestDigest,
        )
        assertSame(
            WorkflowOrganizationRelationshipKind.HOLDS_POSITION,
            WorkflowOrganizationRelationshipKind.of("holds-position"),
        )
        assertFailsWith<IllegalArgumentException> {
            WorkflowOrganizationRelationshipRequest.of(
                context,
                WorkflowOrganizationSnapshot.of("other.directory", "directory-r19", 1_000L, 1_100L),
                principal,
                permission,
                WorkflowOrganizationRelationshipKind.HAS_PERMISSION,
                1_010L,
            )
        }
    }
}
