package ai.icen.fw.workflow.api

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class WorkflowParticipantSelectorContractTest {
    @Test
    fun `models built-in organization selectors without executable expressions`() {
        val user = WorkflowPrincipalRef.of("HUMAN", "用户-甲")
        val exact = WorkflowParticipantSelector.exactUser(user)
        val group = WorkflowParticipantSelector.group("组-一")
        val role = WorkflowParticipantSelector.role("法务审批人")
        val position = WorkflowParticipantSelector.position("天津区经理")
        val leaders = WorkflowParticipantSelector.departmentLeaders("水务部门")
        val initiatorManagers = WorkflowParticipantSelector.initiatorManagerChain(1, 3)
        val actorManager = WorkflowParticipantSelector.currentActorManagerChain(2, 2)

        assertSame(WorkflowParticipantSelectorKind.EXACT_USER, exact.kind)
        assertEquals(user, exact.exactPrincipal)
        assertNull(exact.organizationId)
        assertEquals("组-一", group.organizationId)
        assertEquals("法务审批人", role.organizationId)
        assertEquals("天津区经理", position.organizationId)
        assertEquals("水务部门", leaders.organizationId)
        assertEquals(1, initiatorManagers.minimumManagerLevel)
        assertEquals(3, initiatorManagers.maximumManagerLevel)
        assertEquals(2, actorManager.minimumManagerLevel)
        assertEquals(2, actorManager.maximumManagerLevel)
        assertEquals(64, exact.digest.length)
        assertEquals(7, setOf(exact, group, role, position, leaders, initiatorManagers, actorManager).size)

        val publicMethodNames = WorkflowParticipantSelector::class.java.methods.map { method -> method.name.lowercase() }
        assertFalse(publicMethodNames.any { name -> "expression" in name || "script" in name })
        assertFalse(publicMethodNames.any { name -> "authorize" in name || "permit" in name || "allowed" in name })
    }

    @Test
    fun `supports namespaced extension codes with one bounded opaque target`() {
        val customKind = WorkflowParticipantSelectorKind.of("corp.hr.project-owner")
        val selector = WorkflowParticipantSelector.extensionTarget(customKind, "项目-北区")

        assertEquals(customKind, selector.kind)
        assertEquals("项目-北区", selector.organizationId)
        assertEquals(customKind, WorkflowParticipantSelectorKind.of("corp.hr.project-owner"))
        assertNotEquals(customKind, WorkflowParticipantSelectorKind.of("corp.hr.Project-owner"))
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantSelector.extensionTarget(WorkflowParticipantSelectorKind.GROUP, "group")
        }
        assertFailsWith<IllegalArgumentException> { WorkflowParticipantSelectorKind.of("脚本") }
        assertFailsWith<IllegalArgumentException> { WorkflowParticipantSelectorKind.of("corp.hr\nowner") }
    }

    @Test
    fun `bounds manager and delegation traversal without hidden defaults`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantSelector.initiatorManagerChain(0, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantSelector.currentActorManagerChain(3, 2)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowParticipantSelector.currentActorManagerChain(1, 17)
        }

        val disabled = WorkflowDelegationPolicy.disabled()
        val include = WorkflowDelegationPolicy.includeActiveDelegates(2)
        val replace = WorkflowDelegationPolicy.activeDelegateOrOriginal(4)
        val extension = WorkflowDelegationPolicy.of(WorkflowDelegationMode.of("corp.proxy"), 1)
        assertEquals(0, disabled.maximumHops)
        assertSame(WorkflowDelegationMode.DISABLED, disabled.mode)
        assertEquals(2, include.maximumHops)
        assertEquals(4, replace.maximumHops)
        assertEquals("corp.proxy", extension.mode.code)
        assertFailsWith<IllegalArgumentException> {
            WorkflowDelegationPolicy.of(WorkflowDelegationMode.DISABLED, 1)
        }
        assertFailsWith<IllegalArgumentException> { WorkflowDelegationPolicy.includeActiveDelegates(0) }
        assertFailsWith<IllegalArgumentException> { WorkflowDelegationPolicy.includeActiveDelegates(5) }
    }

    @Test
    fun `request defensively snapshots selectors and binds every security context`() {
        val group = WorkflowParticipantSelector.group("group-a")
        val manager = WorkflowParticipantSelector.initiatorManagerChain(1, 2)
        val source = mutableListOf(group, manager)
        val baseline = request(selectors = source)
        source.clear()

        assertEquals(listOf(group, manager), baseline.selectors)
        assertSame(WorkflowParticipantResolutionStage.ACTIVATION, baseline.stage)
        assertFailsWith<UnsupportedOperationException> {
            (baseline.selectors as MutableList<WorkflowParticipantSelector>).clear()
        }
        assertNotEquals(baseline.requestDigest, request(requestId = "request-2").requestDigest)
        assertNotEquals(baseline.requestDigest, request(tenantId = "tenant-2").requestDigest)
        assertNotEquals(
            baseline.requestDigest,
            request(definition = WorkflowDefinitionRef.of("definition", "V2", DIGEST_B)).requestDigest,
        )
        assertNotEquals(
            baseline.requestDigest,
            request(instance = WorkflowInstanceRef.of("instance", 8L)).requestDigest,
        )
        assertNotEquals(
            baseline.requestDigest,
            request(workItem = WorkflowWorkItemRef.of("work-item", 9L)).requestDigest,
        )
        assertNotEquals(
            baseline.requestDigest,
            request(stage = WorkflowParticipantResolutionStage.CLAIM).requestDigest,
        )
        assertNotEquals(
            baseline.requestDigest,
            request(subject = WorkflowSubjectSnapshot.of(WorkflowSubjectRef.of("DOCUMENT", "subject-2"), "R1", DIGEST_A))
                .requestDigest,
        )
        assertNotEquals(
            baseline.requestDigest,
            request(currentActor = WorkflowPrincipalRef.of("USER", "actor-2")).requestDigest,
        )
        assertNotEquals(baseline.requestDigest, request(organizationRevision = "org-r2").requestDigest)
        assertNotEquals(baseline.requestDigest, request(selectors = listOf(manager, group)).requestDigest)
        assertNotEquals(
            baseline.requestDigest,
            request(delegationPolicy = WorkflowDelegationPolicy.includeActiveDelegates(1)).requestDigest,
        )
        assertNotEquals(baseline.requestDigest, request(deadline = 10_200L).requestDigest)
        assertEquals("WorkflowParticipantResolutionRequest(<redacted>)", baseline.toString())
    }

    @Test
    fun `request rejects duplicate unbounded and stale resolution inputs`() {
        val selector = WorkflowParticipantSelector.group("group")
        assertFailsWith<IllegalArgumentException> { request(selectors = listOf(selector, selector)) }
        assertFailsWith<IllegalArgumentException> { request(selectors = emptyList()) }
        assertFailsWith<IllegalArgumentException> { request(maximumPrincipals = 0) }
        assertFailsWith<IllegalArgumentException> { request(maximumPrincipals = 257) }
        assertFailsWith<IllegalArgumentException> { request(requestedAt = -1L) }
        assertFailsWith<IllegalArgumentException> { request(requestedAt = 10_000L, deadline = 10_000L) }
        assertFailsWith<IllegalArgumentException> { request(requestedAt = 10_000L, deadline = 310_001L) }

        val tooMany = (0..32).map { index ->
            WorkflowParticipantSelector.extensionTarget(
                WorkflowParticipantSelectorKind.of("corp.selector-$index"),
                "target-$index",
            )
        }
        assertFailsWith<IllegalArgumentException> { request(selectors = tooMany) }
    }

    @Test
    fun `new public values keep exact redacted logging`() {
        val values = listOf(
            WorkflowParticipantSelectorKind.of("corp.secret-kind"),
            WorkflowDelegationMode.of("corp.secret-mode"),
            WorkflowDelegationPolicy.includeActiveDelegates(2),
            WorkflowParticipantResolutionStatus.of("corp.secret-status"),
            WorkflowParticipantResolutionReason.of("corp.secret-reason"),
            WorkflowParticipantSelector.group("secret-group"),
        )
        values.forEach { value ->
            assertEquals("${value.javaClass.simpleName}(<redacted>)", value.toString())
        }
    }

    private fun request(
        requestId: String = "request-1",
        tenantId: String = "tenant-1",
        definition: WorkflowDefinitionRef = WorkflowDefinitionRef.of("definition", "V1", DIGEST_A),
        instance: WorkflowInstanceRef = WorkflowInstanceRef.of("instance", 7L),
        workItem: WorkflowWorkItemRef = WorkflowWorkItemRef.of("work-item", 8L),
        stage: WorkflowParticipantResolutionStage = WorkflowParticipantResolutionStage.ACTIVATION,
        subject: WorkflowSubjectSnapshot = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("DOCUMENT", "subject"),
            "R1",
            DIGEST_A,
        ),
        initiator: WorkflowPrincipalRef = WorkflowPrincipalRef.of("USER", "initiator"),
        currentActor: WorkflowPrincipalRef = WorkflowPrincipalRef.of("USER", "actor"),
        organizationAuthority: String = "corp.hr",
        organizationRevision: String = "org-r1",
        selectors: List<WorkflowParticipantSelector> = listOf(
            WorkflowParticipantSelector.group("group-a"),
            WorkflowParticipantSelector.initiatorManagerChain(1, 2),
        ),
        delegationPolicy: WorkflowDelegationPolicy = WorkflowDelegationPolicy.disabled(),
        maximumPrincipals: Int = 16,
        requestedAt: Long = 10_000L,
        deadline: Long = 10_100L,
    ): WorkflowParticipantResolutionRequest = WorkflowParticipantResolutionRequest.of(
        requestId,
        tenantId,
        definition,
        instance,
        workItem,
        stage,
        subject,
        initiator,
        currentActor,
        organizationAuthority,
        organizationRevision,
        selectors,
        delegationPolicy,
        maximumPrincipals,
        requestedAt,
        deadline,
    )

    private companion object {
        const val DIGEST_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val DIGEST_B = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
    }
}
