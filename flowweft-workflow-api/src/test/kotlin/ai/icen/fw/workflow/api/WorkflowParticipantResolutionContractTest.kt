package ai.icen.fw.workflow.api

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkflowParticipantResolutionContractTest {
    @Test
    fun `membership strategy preserves legacy digests and requires every current-membership boundary`() {
        val selector = WorkflowParticipantSelector.group("legal-reviewers")
        val legacy = WorkflowHumanTaskParticipantRule.of(selector, WorkflowApprovalPolicy.one())
        val explicitLegacy = WorkflowHumanTaskParticipantRule.of(
            selector,
            WorkflowApprovalPolicy.one(),
            WorkflowParticipantMembershipStrategy.ACTIVATION_SNAPSHOT,
        )
        val current = WorkflowHumanTaskParticipantRule.of(
            selector,
            WorkflowApprovalPolicy.one(),
            WorkflowParticipantMembershipStrategy.CURRENT_MEMBERSHIP,
        )

        assertEquals(legacy.contentDigest, explicitLegacy.contentDigest)
        assertNotEquals(legacy.contentDigest, current.contentDigest)
        assertTrue(WorkflowParticipantMembershipStrategy.CURRENT_MEMBERSHIP.requiresFreshResolution(
            WorkflowParticipantResolutionStage.QUERY,
        ))
        assertFailsWith<IllegalArgumentException> {
            WorkflowHumanTaskPolicy.of(
                listOf(current),
                WorkflowHumanTaskCapabilities.of(false, false, false, true),
                WorkflowSeparationOfDutiesPolicy.of(false, false),
                listOf(WorkflowParticipantResolutionStage.ACTIVATION, WorkflowParticipantResolutionStage.DECISION),
            )
        }
        val policy = WorkflowHumanTaskPolicy.of(
            listOf(current),
            WorkflowHumanTaskCapabilities.of(false, false, false, true),
            WorkflowSeparationOfDutiesPolicy.of(false, false),
            listOf(
                WorkflowParticipantResolutionStage.ACTIVATION,
                WorkflowParticipantResolutionStage.QUERY,
                WorkflowParticipantResolutionStage.CLAIM,
                WorkflowParticipantResolutionStage.DECISION,
            ),
        )
        assertSame(WorkflowParticipantMembershipStrategy.CURRENT_MEMBERSHIP,
            policy.participantRules.single().membershipStrategy)
    }

    @Test
    fun `tiers snapshot non-empty ordered unique principals and bind origin mapping`() {
        val selector = WorkflowParticipantSelector.group("matrix-group")
        val source = mutableListOf(principal("user-a"), principal("user-b"))
        val tier = WorkflowParticipantTier.direct(selector, 0, source, DIGEST_A)
        source.clear()

        assertEquals(listOf(principal("user-a"), principal("user-b")), tier.principals)
        assertEquals(selector.digest, tier.selectorDigest)
        assertEquals(DIGEST_A, tier.originAndDelegationDigest)
        assertEquals(64, tier.digest.length)
        assertFailsWith<UnsupportedOperationException> {
            (tier.principals as MutableList<WorkflowPrincipalRef>).clear()
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantTier.direct(selector, 0, emptyList(), DIGEST_A)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantTier.direct(selector, 0, listOf(principal("same"), principal("same")), DIGEST_A)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantTier.direct(selector, 128, listOf(principal("user")), DIGEST_A)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantTier.manager(selector, 0, 1, listOf(principal("user")), DIGEST_A)
        }

        val managerSelector = WorkflowParticipantSelector.initiatorManagerChain(2, 4)
        val managerTier = WorkflowParticipantTier.manager(
            managerSelector,
            1,
            2,
            listOf(principal("manager")),
            DIGEST_B,
        )
        assertEquals(2, managerTier.managerLevel)
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantTier.direct(managerSelector, 1, listOf(principal("manager")), DIGEST_B)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantTier.manager(managerSelector, 1, 1, listOf(principal("manager")), DIGEST_B)
        }
        assertNotEquals(tier.digest, WorkflowParticipantTier.direct(selector, 0, tier.principals, DIGEST_B).digest)
    }

    @Test
    fun `resolved output preserves selector blocks tiers and derived flat order`() {
        val group = WorkflowParticipantSelector.group("matrix-group")
        val manager = WorkflowParticipantSelector.initiatorManagerChain(1, 3)
        val request = request(listOf(group, manager), maximumPrincipals = 5)
        val source = mutableListOf(
            WorkflowParticipantTier.direct(group, 0, listOf(principal("group-a")), DIGEST_A),
            WorkflowParticipantTier.direct(group, 1, listOf(principal("group-b")), DIGEST_B),
            WorkflowParticipantTier.manager(manager, 2, 1, listOf(principal("manager-1")), DIGEST_A),
            WorkflowParticipantTier.manager(
                manager,
                3,
                2,
                listOf(principal("manager-2"), principal("manager-2b")),
                DIGEST_B,
            ),
        )
        val resolution = WorkflowParticipantResolution.resolved(request, source, 10_010L, 10_090L)
        source.clear()

        assertSame(WorkflowParticipantResolutionStatus.RESOLVED, resolution.status)
        assertNull(resolution.reason)
        assertFalse(resolution.retryable)
        assertEquals(listOf(0, 1, 2, 3), resolution.tiers.map { tier -> tier.tierIndex })
        assertEquals(
            listOf("group-a", "group-b", "manager-1", "manager-2", "manager-2b"),
            resolution.principals.map { principal -> principal.id },
        )
        assertEquals(request.requestId, resolution.requestId)
        assertEquals(request.requestDigest, resolution.requestDigest)
        assertEquals(request.tenantId, resolution.tenantId)
        assertEquals(request.organizationAuthority, resolution.authority)
        assertEquals(request.organizationSnapshotRevision, resolution.authorityRevision)
        assertEquals(64, resolution.resolutionDigest.length)
        assertFailsWith<UnsupportedOperationException> {
            (resolution.tiers as MutableList<WorkflowParticipantTier>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            (resolution.principals as MutableList<WorkflowPrincipalRef>).clear()
        }
        assertEquals("WorkflowParticipantResolution(<redacted>)", resolution.toString())
    }

    @Test
    fun `resolved tiers fail closed on gaps reorder omissions and occurrence limits`() {
        val group = WorkflowParticipantSelector.group("group")
        val manager = WorkflowParticipantSelector.initiatorManagerChain(1, 3)
        val request = request(listOf(group, manager), maximumPrincipals = 4)

        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantResolution.resolved(request, emptyList(), 10_010L, 10_090L)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantResolution.resolved(
                request,
                listOf(
                    WorkflowParticipantTier.direct(group, 1, listOf(principal("group")), DIGEST_A),
                    WorkflowParticipantTier.manager(manager, 2, 1, listOf(principal("manager")), DIGEST_B),
                ),
                10_010L,
                10_090L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantResolution.resolved(
                request,
                listOf(
                    WorkflowParticipantTier.manager(manager, 0, 1, listOf(principal("manager")), DIGEST_A),
                    WorkflowParticipantTier.direct(group, 1, listOf(principal("group")), DIGEST_B),
                ),
                10_010L,
                10_090L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantResolution.resolved(
                request,
                listOf(
                    WorkflowParticipantTier.direct(group, 0, listOf(principal("group")), DIGEST_A),
                    WorkflowParticipantTier.manager(manager, 1, 2, listOf(principal("manager")), DIGEST_B),
                ),
                10_010L,
                10_090L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantResolution.resolved(
                request,
                listOf(WorkflowParticipantTier.direct(group, 0, listOf(principal("group")), DIGEST_A)),
                10_010L,
                10_090L,
            )
        }
        val smallRequest = request(listOf(group, manager), maximumPrincipals = 1)
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantResolution.resolved(
                smallRequest,
                listOf(
                    WorkflowParticipantTier.direct(group, 0, listOf(principal("group")), DIGEST_A),
                    WorkflowParticipantTier.manager(manager, 1, 1, listOf(principal("manager")), DIGEST_B),
                ),
                10_010L,
                10_090L,
            )
        }
    }

    @Test
    fun `resolved output preserves repeated principal occurrences across tiers and selectors`() {
        val group = WorkflowParticipantSelector.group("matrix-group")
        val manager = WorkflowParticipantSelector.initiatorManagerChain(1, 1)
        val shared = principal("shared-responsibility")
        val request = request(listOf(group, manager), maximumPrincipals = 3)

        val resolution = WorkflowParticipantResolution.resolved(
            request,
            listOf(
                WorkflowParticipantTier.direct(group, 0, listOf(shared), DIGEST_A),
                WorkflowParticipantTier.direct(group, 1, listOf(shared), DIGEST_B),
                WorkflowParticipantTier.manager(manager, 2, 1, listOf(shared), DIGEST_A),
            ),
            10_010L,
            10_090L,
        )

        assertEquals(listOf(shared, shared, shared), resolution.principals)
        assertEquals(listOf(0, 1, 2), resolution.tiers.map { tier -> tier.tierIndex })
        assertEquals(
            listOf(group.digest, group.digest, manager.digest),
            resolution.tiers.map { tier -> tier.selectorDigest },
        )
    }

    @Test
    fun `non-resolved outcomes are explicit empty bounded and extensible`() {
        val request = request(listOf(WorkflowParticipantSelector.group("group")))
        val empty = WorkflowParticipantResolution.empty(
            request,
            WorkflowParticipantResolutionReason.NO_MATCH,
            10_010L,
            10_020L,
        )
        val denied = WorkflowParticipantResolution.denied(
            request,
            WorkflowParticipantResolutionReason.DIRECTORY_DENIED,
            10_010L,
            10_020L,
        )
        val error = WorkflowParticipantResolution.error(
            request,
            WorkflowParticipantResolutionReason.PROVIDER_ERROR,
            true,
            10_010L,
            10_020L,
        )
        val extension = WorkflowParticipantResolution.unresolved(
            request,
            WorkflowParticipantResolutionStatus.of("corp.snapshot-rebuilding"),
            WorkflowParticipantResolutionReason.of("corp.rebuild-running"),
            true,
            10_010L,
            10_020L,
        )

        assertSame(WorkflowParticipantResolutionStatus.EMPTY, empty.status)
        assertSame(WorkflowParticipantResolutionStatus.DENIED, denied.status)
        assertSame(WorkflowParticipantResolutionStatus.ERROR, error.status)
        assertTrue(error.retryable)
        assertEquals("corp.snapshot-rebuilding", extension.status.code)
        listOf(empty, denied, error, extension).forEach { result ->
            assertTrue(result.tiers.isEmpty())
            assertTrue(result.principals.isEmpty())
            assertEquals(request.organizationAuthority, result.authority)
            assertEquals(request.organizationSnapshotRevision, result.authorityRevision)
        }
        assertNotEquals(empty.resolutionDigest, denied.resolutionDigest)
        assertNotEquals(denied.resolutionDigest, error.resolutionDigest)
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantResolution.unresolved(
                request,
                WorkflowParticipantResolutionStatus.RESOLVED,
                WorkflowParticipantResolutionReason.NO_MATCH,
                false,
                10_010L,
                10_020L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantResolution.unresolved(
                request,
                WorkflowParticipantResolutionStatus.EMPTY,
                WorkflowParticipantResolutionReason.NO_MATCH,
                true,
                10_010L,
                10_020L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantResolution.empty(
                request,
                WorkflowParticipantResolutionReason.NO_MATCH,
                9_999L,
                10_020L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantResolution.empty(
                request,
                WorkflowParticipantResolutionReason.NO_MATCH,
                10_010L,
                10_101L,
            )
        }
    }

    @Test
    fun `exact user remains exact when delegation is disabled`() {
        val expected = principal("expected")
        val selector = WorkflowParticipantSelector.exactUser(expected)
        val request = WorkflowParticipantResolutionRequest.of(
            "request-exact",
            "tenant",
            WorkflowDefinitionRef.of("definition", "V1", DIGEST_A),
            WorkflowInstanceRef.of("instance", 1L),
            WorkflowWorkItemRef.of("work-item", 2L),
            WorkflowParticipantResolutionStage.DECISION,
            WorkflowSubjectSnapshot.of(WorkflowSubjectRef.of("DOCUMENT", "subject"), "R1", DIGEST_B),
            principal("initiator"),
            principal("actor"),
            "corp.hr",
            "org-r1",
            listOf(selector),
            WorkflowDelegationPolicy.disabled(),
            2,
            10_000L,
            10_100L,
        )
        val valid = WorkflowParticipantResolution.resolved(
            request,
            listOf(WorkflowParticipantTier.direct(selector, 0, listOf(expected), DIGEST_A)),
            10_010L,
            10_020L,
        )
        assertEquals(listOf(expected), valid.principals)

        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantResolution.resolved(
                request,
                listOf(WorkflowParticipantTier.direct(selector, 0, listOf(principal("other")), DIGEST_A)),
                10_010L,
                10_020L,
            )
        }
    }

    @Test
    fun `authorized request binds principal definition and authorization revision without becoming a permit`() {
        val selector = WorkflowParticipantSelector.permission("legal.document.approve")
        val request = WorkflowParticipantResolutionRequest.authorized(
            "request-authorized",
            "tenant",
            WorkflowDefinitionRef.of("definition", "V7", DIGEST_A),
            WorkflowInstanceRef.of("instance", 9L),
            WorkflowWorkItemRef.of("work-item", 10L),
            WorkflowParticipantResolutionStage.CLAIM,
            WorkflowSubjectSnapshot.of(WorkflowSubjectRef.of("LEGAL_DOCUMENT", "subject"), "R4", DIGEST_B),
            principal("initiator"),
            principal("current-actor"),
            "corp.hr",
            "org-r9",
            listOf(selector),
            WorkflowDelegationPolicy.disabled(),
            "authorization-r17",
            DIGEST_A,
            16,
            10_000L,
            10_100L,
        )
        val resolution = WorkflowParticipantResolution.resolved(
            request,
            listOf(WorkflowParticipantTier.direct(selector, 0, listOf(principal("reviewer")), DIGEST_B)),
            10_010L,
            10_020L,
        )

        assertTrue(request.hasAuthorizationEvidence)
        assertEquals("authorization-r17", request.authorizationAuthorityRevision)
        assertEquals(DIGEST_A, request.authorizationEvidenceDigest)
        assertTrue(resolution.hasAuthorizationEvidence)
        assertEquals(request.authorizationAuthorityRevision, resolution.authorizationAuthorityRevision)
        assertEquals(request.authorizationEvidenceDigest, resolution.authorizationEvidenceDigest)
        assertNotEquals(
            request.requestDigest,
            WorkflowParticipantResolutionRequest.authorized(
                "request-authorized",
                "tenant",
                request.definition,
                request.instance,
                request.workItem,
                request.stage,
                request.subject,
                request.initiator,
                request.currentActor,
                request.organizationAuthority,
                request.organizationSnapshotRevision,
                request.selectors,
                request.delegationPolicy,
                "authorization-r18",
                DIGEST_A,
                request.maximumPrincipals,
                request.requestedAtEpochMilli,
                request.deadlineEpochMilli,
            ).requestDigest,
        )
    }

    @Test
    fun `resolver is a Java-compatible asynchronous boundary and result is not a permit`() {
        val group = WorkflowParticipantSelector.group("group")
        val request = request(listOf(group))
        val resolution = WorkflowParticipantResolution.resolved(
            request,
            listOf(WorkflowParticipantTier.direct(group, 0, listOf(principal("user")), DIGEST_A)),
            10_010L,
            10_020L,
        )
        val resolver = WorkflowParticipantResolver { actual ->
            assertSame(request, actual)
            CompletableFuture.completedFuture(resolution)
        }

        assertSame(resolution, resolver.resolve(request).toCompletableFuture().join())
        val publicNames = WorkflowParticipantResolution::class.java.methods.map { method -> method.name.lowercase() }
        assertFalse(publicNames.any { name -> "authorize" in name || "permit" in name || "allowed" in name })
    }

    private fun request(
        selectors: List<WorkflowParticipantSelector>,
        maximumPrincipals: Int = 16,
    ): WorkflowParticipantResolutionRequest = WorkflowParticipantResolutionRequest.of(
        "request",
        "tenant",
        WorkflowDefinitionRef.of("definition", "V1", DIGEST_A),
        WorkflowInstanceRef.of("instance", 1L),
        WorkflowWorkItemRef.of("work-item", 2L),
        WorkflowParticipantResolutionStage.ACTIVATION,
        WorkflowSubjectSnapshot.of(WorkflowSubjectRef.of("DOCUMENT", "subject"), "R1", DIGEST_B),
        principal("initiator"),
        principal("actor"),
        "corp.hr",
        "org-r1",
        selectors,
        WorkflowDelegationPolicy.includeActiveDelegates(2),
        maximumPrincipals,
        10_000L,
        10_100L,
    )

    private fun principal(id: String): WorkflowPrincipalRef = WorkflowPrincipalRef.of("USER", id)

    private companion object {
        const val DIGEST_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val DIGEST_B = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    }
}
