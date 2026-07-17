package ai.icen.fw.workflow.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowApprovalPolicy
import ai.icen.fw.workflow.api.WorkflowDefinition
import ai.icen.fw.workflow.api.WorkflowDefinitionStatus
import ai.icen.fw.workflow.api.WorkflowHumanTaskCapabilities
import ai.icen.fw.workflow.api.WorkflowHumanCollaborationAction
import ai.icen.fw.workflow.api.WorkflowHumanTaskEvidenceBinding
import ai.icen.fw.workflow.api.WorkflowHumanTaskParticipantRule
import ai.icen.fw.workflow.api.WorkflowHumanTaskPolicy
import ai.icen.fw.workflow.api.WorkflowNodeDefinition
import ai.icen.fw.workflow.api.WorkflowNodeKind
import ai.icen.fw.workflow.api.WorkflowParticipantMembershipStrategy
import ai.icen.fw.workflow.api.WorkflowParticipantResolutionStage
import ai.icen.fw.workflow.api.WorkflowParticipantSelector
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSeparationOfDutiesPolicy
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.api.WorkflowTransitionDefinition
import ai.icen.fw.workflow.api.WorkflowTransitionTrigger
import ai.icen.fw.workflow.domain.WorkflowCommandContext
import ai.icen.fw.workflow.domain.WorkflowDefinitionExecutionReceipt
import ai.icen.fw.workflow.domain.WorkflowDefinitionIndex
import ai.icen.fw.workflow.domain.WorkflowDomainEngine
import ai.icen.fw.workflow.domain.WorkflowExecutionIds
import ai.icen.fw.workflow.domain.WorkflowHumanRuleSnapshot
import ai.icen.fw.workflow.domain.WorkflowHumanTaskCollaborationState
import ai.icen.fw.workflow.domain.WorkflowHumanWorkItemState
import ai.icen.fw.workflow.domain.WorkflowHumanWorkItemStatus
import ai.icen.fw.workflow.domain.WorkflowIdempotencyReceipt
import ai.icen.fw.workflow.domain.WorkflowInstanceState
import ai.icen.fw.workflow.domain.WorkflowNodeExecutionState
import ai.icen.fw.workflow.domain.WorkflowNodeExecutionStatus
import ai.icen.fw.workflow.domain.WorkflowStartCommand
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class WorkflowJdbcBinaryCodecTest {
    @Test
    fun `definition and aggregate snapshots round trip through public factories`() {
        val definition = definition()
        val decodedDefinition = WorkflowJdbcBinaryCodec.decodeDefinition(
            WorkflowJdbcBinaryCodec.encodeDefinition(definition),
        )
        assertEquals(definition, decodedDefinition)
        assertEquals(definition.contentDigest, decodedDefinition.contentDigest)

        val index = WorkflowDefinitionIndex.compile(definition)
        val receipt = WorkflowDefinitionExecutionReceipt.of(
            "deployment-1",
            TENANT,
            DEFINITION_ID,
            definition.ref,
            definition.schemaVersion,
            DIGEST_B,
            10L,
            1_000L,
        )
        val context = WorkflowCommandContext.of(
            "command-1",
            "idempotency-1",
            0L,
            10L,
            64,
            WorkflowExecutionIds.of(
                (0 until 16).map { "token-$it" },
                (0 until 16).map { "execution-$it" },
                (0 until 8).map { "work-$it" },
                (0 until 16).map { "effect-$it" },
                (0 until 64).map { "event-$it" },
                (0 until 8).map { "scope-$it" },
            ),
            WorkflowIdempotencyReceipt.fresh(TENANT, INSTANCE_ID, "idempotency-1", 10L),
        )
        val started = WorkflowDomainEngine.start(
            index,
            WorkflowStartCommand.of(
                context,
                TENANT,
                INSTANCE_ID,
                DEFINITION_ID,
                definition.ref,
                SUBJECT,
                WorkflowPrincipalRef.of("user", "initiator"),
                receipt,
            ),
        )
        val state = requireNotNull(started.state)
        val encoded = WorkflowJdbcBinaryCodec.encodeState(state)
        val decoded = WorkflowJdbcBinaryCodec.decodeState(encoded)
        assertEquals(state, decoded)
        assertEquals(state.stateDigest, decoded.stateDigest)
        assertEquals(state.tokens.map { it.contentDigest }, decoded.tokens.map { it.contentDigest })
        assertEquals(
            state.nodeExecutions.map { it.contentDigest },
            decoded.nodeExecutions.map { it.contentDigest },
        )
        assertEquals(
            state.humanWorkItems.map { it.contentDigest },
            decoded.humanWorkItems.map { it.contentDigest },
        )
        assertContentEquals(encoded, WorkflowJdbcBinaryCodec.encodeState(decoded))
        val legacyState = WorkflowJdbcBinaryCodec.decodeState(WorkflowJdbcBinaryCodec.encodeState(state, 1))
        assertEquals(state.stateDigest, legacyState.stateDigest)
        val v2State = WorkflowJdbcBinaryCodec.decodeState(WorkflowJdbcBinaryCodec.encodeState(state, 2))
        assertEquals(state.stateDigest, v2State.stateDigest)
        val suspendedState = WorkflowInstanceState.restore(
            state.tenantId,
            state.instanceId,
            state.definitionId,
            state.definitionRef,
            state.subject,
            state.initiator,
            ai.icen.fw.workflow.domain.WorkflowInstanceStatus.SUSPENDED,
            state.version + 1L,
            state.createdAt,
            state.updatedAt + 1L,
            state.tokens,
            state.nodeExecutions,
            state.humanWorkItems,
            state.pendingContinuationEffectId,
            state.pendingContinuationRequestDigest,
            state.status,
        )
        val decodedSuspended = WorkflowJdbcBinaryCodec.decodeState(
            WorkflowJdbcBinaryCodec.encodeState(suspendedState),
        )
        assertEquals(suspendedState.stateDigest, decodedSuspended.stateDigest)
        assertEquals(state.status, decodedSuspended.suspendedFromStatus)
        assertFailsWith<IllegalArgumentException> {
            WorkflowJdbcBinaryCodec.encodeState(suspendedState, 5)
        }

        val reviewer = WorkflowPrincipalRef.of("user", "reviewer-1")
        val addedSigner = WorkflowPrincipalRef.of("user", "reviewer-2")
        val workItem = state.humanWorkItems.single()
        val execution = state.nodeExecutions.single { it.executionId == workItem.nodeExecutionId }
        val rule = index.node(workItem.nodeId).humanTaskPolicy!!.participantRules.single()
        val snapshot = WorkflowHumanRuleSnapshot.organizationDoubleChecked(
            0,
            rule.contentDigest,
            rule.selector.digest,
            rule.approvalPolicy.mode,
            WorkflowParticipantMembershipStrategy.CURRENT_MEMBERSHIP,
            2,
            2,
            listOf(reviewer, addedSigner),
            DIGEST_A,
            DIGEST_B,
            "tianjin-directory",
            "revision-42",
            DIGEST_A,
            "provider-revision-7",
            DIGEST_A,
            DIGEST_B,
            "revision-42",
            DIGEST_B,
            DIGEST_A,
            DIGEST_B,
            20L,
        )
        val claimed = WorkflowHumanTaskCollaborationState.unclaimed().transition(
            "collaboration-record-1",
            WorkflowHumanCollaborationAction.CLAIM,
            reviewer,
            null,
            DIGEST_B,
            "one-use-nonce-1",
            20L,
        )
        val collaboration = claimed.transition(
            "collaboration-record-2",
            WorkflowHumanCollaborationAction.ADD_SIGN,
            reviewer,
            addedSigner,
            DIGEST_B,
            "one-use-nonce-2",
            21L,
        )
        val activeItem = WorkflowHumanWorkItemState.restore(
            workItem.workItemId,
            workItem.nodeExecutionId,
            workItem.tokenId,
            workItem.nodeId,
            workItem.policyDigest,
            WorkflowHumanWorkItemStatus.ACTIVE,
            0,
            listOf(snapshot),
            emptyList(),
            collaboration,
            workItem.revision + 1L,
            workItem.createdAt,
            21L,
        )
        val activeExecution = WorkflowNodeExecutionState.restore(
            execution.executionId,
            execution.tokenId,
            execution.nodeId,
            WorkflowNodeExecutionStatus.WAITING,
            execution.revision + 1L,
            execution.startedAt,
            null,
            null,
            null,
            null,
        )
        val activeState = WorkflowInstanceState.restore(
            state.tenantId,
            state.instanceId,
            state.definitionId,
            state.definitionRef,
            state.subject,
            state.initiator,
            state.status,
            state.version + 1L,
            state.createdAt,
            21L,
            state.tokens,
            state.nodeExecutions.map { if (it.executionId == activeExecution.executionId) activeExecution else it },
            listOf(activeItem),
            null,
            null,
        )
        val decodedCollaboration = WorkflowJdbcBinaryCodec.decodeState(
            WorkflowJdbcBinaryCodec.encodeState(activeState),
        )
        assertEquals(activeState.stateDigest, decodedCollaboration.stateDigest)
        assertEquals("tianjin-directory", decodedCollaboration.humanWorkItems.single()
            .ruleSnapshots.single().organizationAuthority)
        assertEquals("revision-42", decodedCollaboration.humanWorkItems.single()
            .ruleSnapshots.single().organizationSnapshotRevision)
        assertEquals(3, decodedCollaboration.humanWorkItems.single().ruleSnapshots.single().evidenceVersion)
        assertEquals(
            WorkflowParticipantMembershipStrategy.CURRENT_MEMBERSHIP,
            decodedCollaboration.humanWorkItems.single().ruleSnapshots.single().membershipStrategy,
        )
        assertEquals("provider-revision-7", decodedCollaboration.humanWorkItems.single()
            .ruleSnapshots.single().organizationProviderRevision)
        assertEquals(DIGEST_B, decodedCollaboration.humanWorkItems.single()
            .ruleSnapshots.single().organizationConfirmationReceiptDigest)
        assertFailsWith<IllegalArgumentException> { WorkflowJdbcBinaryCodec.encodeState(activeState, 3) }
        assertFailsWith<IllegalArgumentException> { WorkflowJdbcBinaryCodec.encodeState(activeState, 4) }
        assertFailsWith<IllegalArgumentException> { WorkflowJdbcBinaryCodec.encodeState(activeState, 2) }
        assertEquals(reviewer, decodedCollaboration.humanWorkItems.single().collaboration.claimOwner)
        assertEquals(addedSigner, decodedCollaboration.humanWorkItems.single().collaboration.activeDelegate)
        assertEquals(
            collaboration.addSignFrames.single().contentDigest,
            decodedCollaboration.humanWorkItems.single().collaboration.addSignFrames.single().contentDigest,
        )
        assertEquals(
            collaboration.records.map { it.contentDigest },
            decodedCollaboration.humanWorkItems.single().collaboration.records.map { it.contentDigest },
        )
    }

    @Test
    fun `snapshot corruption fails closed`() {
        val payload = WorkflowJdbcBinaryCodec.encodeDefinition(definition()) + byteArrayOf(1)
        assertFailsWith<IllegalArgumentException> { WorkflowJdbcBinaryCodec.decodeDefinition(payload) }
    }

    @Test
    fun `v1 definition payload remains readable while v2 carries exact evidence binding`() {
        val legacy = definition(false)
        val decodedLegacy = WorkflowJdbcBinaryCodec.decodeDefinition(
            WorkflowJdbcBinaryCodec.encodeDefinition(legacy, 1),
        )
        assertEquals(legacy.contentDigest, decodedLegacy.contentDigest)
        assertEquals(true, decodedLegacy.nodes.single { it.nodeId == "review" }
            .humanTaskPolicy!!.evidenceBinding.isBuiltinNone)
        val v2 = definition(true)
        val decodedV2 = WorkflowJdbcBinaryCodec.decodeDefinition(
            WorkflowJdbcBinaryCodec.encodeDefinition(v2, 2),
        )
        assertEquals(v2.contentDigest, decodedV2.contentDigest)
    }

    @Test
    fun `v5 definition preserves current membership while older formats fail closed`() {
        val current = definition(true, WorkflowParticipantMembershipStrategy.CURRENT_MEMBERSHIP)
        val decoded = WorkflowJdbcBinaryCodec.decodeDefinition(
            WorkflowJdbcBinaryCodec.encodeDefinition(current),
        )
        assertEquals(current.contentDigest, decoded.contentDigest)
        assertEquals(
            WorkflowParticipantMembershipStrategy.CURRENT_MEMBERSHIP,
            decoded.nodes.single { it.nodeId == "review" }
                .humanTaskPolicy!!.participantRules.single().membershipStrategy,
        )
        assertFailsWith<IllegalArgumentException> {
            WorkflowJdbcBinaryCodec.encodeDefinition(current, 4)
        }
    }

    private fun definition(
        withEvidence: Boolean = true,
        membershipStrategy: WorkflowParticipantMembershipStrategy =
            WorkflowParticipantMembershipStrategy.ACTIVATION_SNAPSHOT,
    ): WorkflowDefinition {
        val rules = listOf(
            WorkflowHumanTaskParticipantRule.of(
                WorkflowParticipantSelector.group("legal-reviewers"),
                WorkflowApprovalPolicy.all(),
                membershipStrategy,
            ),
        )
        val capabilities = WorkflowHumanTaskCapabilities.of(false, true, true, true)
        val separation = WorkflowSeparationOfDutiesPolicy.of(true, true)
        val stages = if (membershipStrategy == WorkflowParticipantMembershipStrategy.CURRENT_MEMBERSHIP) {
            listOf(
                WorkflowParticipantResolutionStage.ACTIVATION,
                WorkflowParticipantResolutionStage.QUERY,
                WorkflowParticipantResolutionStage.CLAIM,
                WorkflowParticipantResolutionStage.DECISION,
            )
        } else {
            listOf(WorkflowParticipantResolutionStage.ACTIVATION, WorkflowParticipantResolutionStage.DECISION)
        }
        val policy = if (withEvidence) {
            WorkflowHumanTaskPolicy.of(
                rules,
                capabilities,
                separation,
                stages,
                WorkflowHumanTaskEvidenceBinding.of(
                    "legal-form",
                    "v3",
                    DIGEST_A,
                    "legal-routing-rule",
                    "v7",
                    DIGEST_B,
                ),
            )
        } else {
            WorkflowHumanTaskPolicy.of(rules, capabilities, separation, stages)
        }
        return WorkflowDefinition.of(
            TENANT,
            DEFINITION_ID,
            "legal-review",
            "v1",
            1,
            WorkflowDefinitionStatus.PUBLISHED,
            "法律文件审批",
            "版本化通用工作流",
            listOf(
                WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "开始", null),
                WorkflowNodeDefinition.humanTask("review", "审批", null, policy),
                WorkflowNodeDefinition.of("approved", WorkflowNodeKind.END, "通过", null),
                WorkflowNodeDefinition.of("rejected", WorkflowNodeKind.END, "驳回", null),
            ),
            listOf(
                WorkflowTransitionDefinition.unconditional("start-review", "start", "review"),
                WorkflowTransitionDefinition.unconditional(
                    "review-approved", "review", "approved", WorkflowTransitionTrigger.APPROVED,
                ),
                WorkflowTransitionDefinition.unconditional(
                    "review-rejected", "review", "rejected", WorkflowTransitionTrigger.REJECTED,
                ),
            ),
        )
    }

    private companion object {
        const val TENANT = "tenant-天津"
        const val DEFINITION_ID = "definition-legal"
        const val INSTANCE_ID = "instance-001"
        const val DIGEST_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val DIGEST_B = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        val SUBJECT = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("legal-file", "file-001"),
            "revision-1",
            DIGEST_A,
        )
    }
}
