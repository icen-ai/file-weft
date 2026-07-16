package ai.icen.fw.workflow.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowApprovalMode
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
import ai.icen.fw.workflow.api.WorkflowParticipantSelectorKind
import ai.icen.fw.workflow.api.WorkflowPredicateInputMapping
import ai.icen.fw.workflow.api.WorkflowPredicateInputSourceKind
import ai.icen.fw.workflow.api.WorkflowPredicateRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSeparationOfDutiesPolicy
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.api.WorkflowTransitionDefinition
import ai.icen.fw.workflow.api.WorkflowTransitionTrigger
import ai.icen.fw.workflow.domain.WorkflowEffectCode
import ai.icen.fw.workflow.domain.WorkflowHumanDecision
import ai.icen.fw.workflow.domain.WorkflowHumanDecisionCode
import ai.icen.fw.workflow.domain.WorkflowHumanCollaborationRecord
import ai.icen.fw.workflow.domain.WorkflowHumanAddSignFrame
import ai.icen.fw.workflow.domain.WorkflowHumanRuleSnapshot
import ai.icen.fw.workflow.domain.WorkflowHumanTaskCollaborationState
import ai.icen.fw.workflow.domain.WorkflowHumanWorkItemState
import ai.icen.fw.workflow.domain.WorkflowHumanWorkItemStatus
import ai.icen.fw.workflow.domain.WorkflowInstanceState
import ai.icen.fw.workflow.domain.WorkflowInstanceStatus
import ai.icen.fw.workflow.domain.WorkflowNodeExecutionState
import ai.icen.fw.workflow.domain.WorkflowNodeExecutionStatus
import ai.icen.fw.workflow.domain.WorkflowParallelFrame
import ai.icen.fw.workflow.domain.WorkflowTokenState
import ai.icen.fw.workflow.domain.WorkflowTokenStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Versioned, bounded wire format for durable aggregate and definition snapshots.
 * It intentionally reconstructs through public factories, so corrupt or obsolete data fails closed.
 */
internal object WorkflowJdbcBinaryCodec {
    private const val FORMAT_VERSION = 6
    private const val STATE_MAGIC = "FW-WORKFLOW-STATE"
    private const val DEFINITION_MAGIC = "FW-WORKFLOW-DEFINITION"

    fun encodeState(state: WorkflowInstanceState): ByteArray = encodeState(state, FORMAT_VERSION)

    internal fun encodeState(state: WorkflowInstanceState, formatVersion: Int): ByteArray {
        require(formatVersion >= 2 || state.humanWorkItems.all { it.collaboration.isPristine }) {
            "Workflow JDBC v1 cannot encode human-task collaboration state."
        }
        require(formatVersion >= 3 || state.humanWorkItems.all { workItem ->
            workItem.ruleSnapshots.all { snapshot -> !snapshot.hasOrganizationEvidence }
        }) {
            "Workflow JDBC v1/v2 cannot encode participant organization evidence."
        }
        require(formatVersion >= 4 || state.humanWorkItems.all { workItem ->
            workItem.ruleSnapshots.all { snapshot -> snapshot.evidenceVersion < 3 }
        }) {
            "Workflow JDBC v1/v2/v3 cannot encode double-checked organization evidence."
        }
        require(formatVersion >= 5 || state.humanWorkItems.all { workItem ->
            workItem.ruleSnapshots.all { snapshot ->
                snapshot.membershipStrategy == WorkflowParticipantMembershipStrategy.ACTIVATION_SNAPSHOT
            }
        }) {
            "Workflow JDBC v1/v2/v3/v4 cannot encode current-membership semantics."
        }
        require(formatVersion >= 6 ||
            (state.suspendedFromStatus == null &&
                state.status != WorkflowInstanceStatus.SUSPENDED &&
                state.status != WorkflowInstanceStatus.CANCELLED &&
                state.status != WorkflowInstanceStatus.TERMINATED)
        ) {
            "Workflow JDBC v1/v2/v3/v4/v5 cannot encode instance lifecycle controls."
        }
        return Writer(STATE_MAGIC, formatVersion).apply {
        text(state.tenantId)
        text(state.instanceId)
        text(state.definitionId)
        definitionRef(state.definitionRef.key, state.definitionRef.version, state.definitionRef.digest)
        subject(state.subject)
        principal(state.initiator)
        text(state.status.code)
        long(state.version)
        long(state.createdAt)
        long(state.updatedAt)
        list(state.tokens) { token ->
            text(token.tokenId)
            text(token.nodeId)
            text(token.status.code)
            list(token.parallelFrames) { frame ->
                text(frame.scopeId)
                text(frame.splitNodeId)
                text(frame.joinNodeId)
                integer(frame.branchIndex)
                integer(frame.branchCount)
            }
            nullableText(token.waitingExecutionId)
            long(token.revision)
        }
        list(state.nodeExecutions) { execution ->
            text(execution.executionId)
            text(execution.tokenId)
            text(execution.nodeId)
            text(execution.status.code)
            long(execution.revision)
            long(execution.startedAt)
            nullableLong(execution.completedAt)
            nullableText(execution.pendingEffectId)
            nullableText(execution.pendingEffectCode?.code)
            nullableText(execution.effectRequestDigest)
        }
        list(state.humanWorkItems) { workItem ->
            text(workItem.workItemId)
            text(workItem.nodeExecutionId)
            text(workItem.tokenId)
            text(workItem.nodeId)
            text(workItem.policyDigest)
            text(workItem.status.code)
            integer(workItem.activeRuleIndex)
            list(workItem.ruleSnapshots) { snapshot ->
                integer(snapshot.ruleIndex)
                text(snapshot.ruleDigest)
                text(snapshot.selectorDigest)
                text(snapshot.approvalMode.code)
                if (formatVersion >= 5) text(snapshot.membershipStrategy.code)
                integer(snapshot.denominator)
                integer(snapshot.requiredApprovals)
                list(snapshot.candidates, ::principal)
                text(snapshot.resolutionDigest)
                text(snapshot.activationReceiptDigest)
                long(snapshot.activatedAt)
                if (formatVersion >= 3) {
                    integer(snapshot.evidenceVersion)
                    nullableText(snapshot.organizationAuthority)
                    nullableText(snapshot.organizationSnapshotRevision)
                    nullableText(snapshot.resolutionRequestDigest)
                    if (formatVersion >= 4) {
                        nullableText(snapshot.organizationProviderRevision)
                        nullableText(snapshot.organizationSnapshotDigest)
                        nullableText(snapshot.organizationSnapshotReceiptDigest)
                        nullableText(snapshot.organizationConfirmationRevision)
                        nullableText(snapshot.organizationConfirmationSnapshotDigest)
                        nullableText(snapshot.organizationConfirmationRequestDigest)
                        nullableText(snapshot.organizationConfirmationReceiptDigest)
                    }
                }
            }
            list(workItem.decisions) { decision ->
                text(decision.decisionId)
                integer(decision.ruleIndex)
                principal(decision.actor)
                text(decision.decision.code)
                text(decision.authorizationReceiptDigest)
                long(decision.decidedAt)
            }
            if (formatVersion >= 2) {
                nullable(workItem.collaboration.claimOwner, ::principal)
                nullable(workItem.collaboration.activeDelegate, ::principal)
                list(workItem.collaboration.assignmentPath, ::principal)
                list(workItem.collaboration.addSignFrames) { frame ->
                    text(frame.frameId)
                    principal(frame.inviter)
                    principal(frame.signer)
                    principal(frame.ownerBefore)
                    nullable(frame.delegateBefore, ::principal)
                    integer(frame.priorAssignmentDepth)
                    long(frame.addedAt)
                }
                list(workItem.collaboration.records) { record ->
                    text(record.recordId)
                    text(record.action.code)
                    principal(record.actor)
                    nullable(record.target, ::principal)
                    nullable(record.ownerBefore, ::principal)
                    nullable(record.ownerAfter, ::principal)
                    nullable(record.delegateBefore, ::principal)
                    nullable(record.delegateAfter, ::principal)
                    text(record.authorizationReceiptDigest)
                    text(record.executionNonce)
                    long(record.occurredAt)
                }
            }
            long(workItem.revision)
            long(workItem.createdAt)
            long(workItem.updatedAt)
        }
        nullableText(state.pendingContinuationEffectId)
        nullableText(state.pendingContinuationRequestDigest)
        if (formatVersion >= 6) nullableText(state.suspendedFromStatus?.code)
        }.finish()
    }

    fun decodeState(payload: ByteArray): WorkflowInstanceState = Reader(payload, STATE_MAGIC).use { reader ->
        val tenantId = reader.text()
        val instanceId = reader.text()
        val definitionId = reader.text()
        val definitionRef = reader.definitionRef()
        val subject = reader.subject()
        val initiator = reader.principal()
        val status = WorkflowInstanceStatus.of(reader.text())
        val version = reader.long()
        val createdAt = reader.long()
        val updatedAt = reader.long()
        val tokens = reader.list {
            val tokenId = text()
            val nodeId = text()
            val tokenStatus = WorkflowTokenStatus.of(text())
            val frames = list {
                WorkflowParallelFrame.of(text(), text(), text(), integer(), integer())
            }
            val waitingExecutionId = nullableText()
            val revision = long()
            WorkflowTokenState.restore(tokenId, nodeId, tokenStatus, frames, waitingExecutionId, revision)
        }
        val executions = reader.list {
            val executionId = text()
            val tokenId = text()
            val nodeId = text()
            val executionStatus = WorkflowNodeExecutionStatus.of(text())
            val revision = long()
            val startedAt = long()
            val completedAt = nullableLong()
            val pendingEffectId = nullableText()
            val effectCode = nullableText()?.let(WorkflowEffectCode::of)
            val requestDigest = nullableText()
            WorkflowNodeExecutionState.restore(
                executionId,
                tokenId,
                nodeId,
                executionStatus,
                revision,
                startedAt,
                completedAt,
                pendingEffectId,
                effectCode,
                requestDigest,
            )
        }
        val workItems = reader.list {
            val workItemId = text()
            val nodeExecutionId = text()
            val tokenId = text()
            val nodeId = text()
            val policyDigest = text()
            val workItemStatus = WorkflowHumanWorkItemStatus.of(text())
            val activeRuleIndex = integer()
            val snapshots = list {
                val ruleIndex = integer()
                val ruleDigest = text()
                val selectorDigest = text()
                val mode = WorkflowApprovalMode.of(text())
                val membershipStrategy = if (formatVersion >= 5) {
                    WorkflowParticipantMembershipStrategy.of(text())
                } else {
                    WorkflowParticipantMembershipStrategy.ACTIVATION_SNAPSHOT
                }
                val denominator = integer()
                val requiredApprovals = integer()
                val candidates = list { principal() }
                val resolutionDigest = text()
                val activationReceiptDigest = text()
                val activatedAt = long()
                if (formatVersion >= 3) {
                    val evidenceVersion = integer()
                    val organizationAuthority = nullableText()
                    val organizationSnapshotRevision = nullableText()
                    val resolutionRequestDigest = nullableText()
                    val organizationProviderRevision = if (formatVersion >= 4) nullableText() else null
                    val organizationSnapshotDigest = if (formatVersion >= 4) nullableText() else null
                    val organizationSnapshotReceiptDigest = if (formatVersion >= 4) nullableText() else null
                    val organizationConfirmationRevision = if (formatVersion >= 4) nullableText() else null
                    val organizationConfirmationSnapshotDigest = if (formatVersion >= 4) nullableText() else null
                    val organizationConfirmationRequestDigest = if (formatVersion >= 4) nullableText() else null
                    val organizationConfirmationReceiptDigest = if (formatVersion >= 4) nullableText() else null
                    when (evidenceVersion) {
                        1 -> {
                            require(
                                organizationAuthority == null && organizationSnapshotRevision == null &&
                                    resolutionRequestDigest == null && organizationProviderRevision == null &&
                                    organizationSnapshotDigest == null && organizationSnapshotReceiptDigest == null &&
                                    organizationConfirmationRevision == null &&
                                    organizationConfirmationSnapshotDigest == null &&
                                    organizationConfirmationRequestDigest == null &&
                                    organizationConfirmationReceiptDigest == null,
                            ) { "Workflow JDBC legacy participant evidence is inconsistent." }
                            WorkflowHumanRuleSnapshot.of(
                                ruleIndex,
                                ruleDigest,
                                selectorDigest,
                                mode,
                                membershipStrategy,
                                denominator,
                                requiredApprovals,
                                candidates,
                                resolutionDigest,
                                activationReceiptDigest,
                                activatedAt,
                            )
                        }
                        2 -> {
                            require(
                                organizationProviderRevision == null && organizationSnapshotDigest == null &&
                                    organizationSnapshotReceiptDigest == null &&
                                    organizationConfirmationRevision == null &&
                                    organizationConfirmationSnapshotDigest == null &&
                                    organizationConfirmationRequestDigest == null &&
                                    organizationConfirmationReceiptDigest == null,
                            ) { "Workflow JDBC v2 participant evidence contains v3 fields." }
                            WorkflowHumanRuleSnapshot.organizationBound(
                            ruleIndex,
                            ruleDigest,
                            selectorDigest,
                            mode,
                            membershipStrategy,
                            denominator,
                            requiredApprovals,
                            candidates,
                            resolutionDigest,
                            activationReceiptDigest,
                            requireNotNull(organizationAuthority) {
                                "Workflow JDBC organization authority is missing."
                            },
                            requireNotNull(organizationSnapshotRevision) {
                                "Workflow JDBC organization revision is missing."
                            },
                            requireNotNull(resolutionRequestDigest) {
                                "Workflow JDBC participant request digest is missing."
                            },
                            activatedAt,
                        )
                        }
                        3 -> WorkflowHumanRuleSnapshot.organizationDoubleChecked(
                            ruleIndex,
                            ruleDigest,
                            selectorDigest,
                            mode,
                            membershipStrategy,
                            denominator,
                            requiredApprovals,
                            candidates,
                            resolutionDigest,
                            activationReceiptDigest,
                            requireNotNull(organizationAuthority) {
                                "Workflow JDBC organization authority is missing."
                            },
                            requireNotNull(organizationSnapshotRevision) {
                                "Workflow JDBC organization revision is missing."
                            },
                            requireNotNull(resolutionRequestDigest) {
                                "Workflow JDBC participant request digest is missing."
                            },
                            requireNotNull(organizationProviderRevision) {
                                "Workflow JDBC organization provider revision is missing."
                            },
                            requireNotNull(organizationSnapshotDigest) {
                                "Workflow JDBC organization snapshot digest is missing."
                            },
                            requireNotNull(organizationSnapshotReceiptDigest) {
                                "Workflow JDBC organization snapshot receipt is missing."
                            },
                            requireNotNull(organizationConfirmationRevision) {
                                "Workflow JDBC organization confirmation revision is missing."
                            },
                            requireNotNull(organizationConfirmationSnapshotDigest) {
                                "Workflow JDBC organization confirmation snapshot is missing."
                            },
                            requireNotNull(organizationConfirmationRequestDigest) {
                                "Workflow JDBC organization confirmation request is missing."
                            },
                            requireNotNull(organizationConfirmationReceiptDigest) {
                                "Workflow JDBC organization confirmation receipt is missing."
                            },
                            activatedAt,
                        )
                        else -> throw IllegalArgumentException(
                            "Workflow JDBC participant evidence version is unsupported.",
                        )
                    }
                } else {
                    WorkflowHumanRuleSnapshot.of(
                        ruleIndex,
                        ruleDigest,
                        selectorDigest,
                        mode,
                        membershipStrategy,
                        denominator,
                        requiredApprovals,
                        candidates,
                        resolutionDigest,
                        activationReceiptDigest,
                        activatedAt,
                    )
                }
            }
            val decisions = list {
                WorkflowHumanDecision.of(
                    text(),
                    integer(),
                    principal(),
                    WorkflowHumanDecisionCode.of(text()),
                    text(),
                    long(),
                )
            }
            val collaboration = if (formatVersion >= 2) {
                val claimOwner = nullable { principal() }
                val activeDelegate = nullable { principal() }
                val assignmentPath = list { principal() }
                val addSignFrames = list {
                    WorkflowHumanAddSignFrame.of(
                        text(),
                        principal(),
                        principal(),
                        principal(),
                        nullable { principal() },
                        integer(),
                        long(),
                    )
                }
                val records = list {
                    WorkflowHumanCollaborationRecord.of(
                        text(),
                        WorkflowHumanCollaborationAction.of(text()),
                        principal(),
                        nullable { principal() },
                        nullable { principal() },
                        nullable { principal() },
                        nullable { principal() },
                        nullable { principal() },
                        text(),
                        text(),
                        long(),
                    )
                }
                WorkflowHumanTaskCollaborationState.restore(
                    claimOwner,
                    activeDelegate,
                    assignmentPath,
                    addSignFrames,
                    records,
                )
            } else {
                WorkflowHumanTaskCollaborationState.unclaimed()
            }
            val revision = long()
            val workItemCreatedAt = long()
            val workItemUpdatedAt = long()
            WorkflowHumanWorkItemState.restore(
                workItemId,
                nodeExecutionId,
                tokenId,
                nodeId,
                policyDigest,
                workItemStatus,
                activeRuleIndex,
                snapshots,
                decisions,
                collaboration,
                revision,
                workItemCreatedAt,
                workItemUpdatedAt,
            )
        }
        val continuationEffectId = reader.nullableText()
        val continuationRequestDigest = reader.nullableText()
        val suspendedFromStatus = if (reader.formatVersion >= 6) {
            reader.nullableText()?.let(WorkflowInstanceStatus::of)
        } else {
            null
        }
        reader.end()
        WorkflowInstanceState.restore(
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            initiator,
            status,
            version,
            createdAt,
            updatedAt,
            tokens,
            executions,
            workItems,
            continuationEffectId,
            continuationRequestDigest,
            suspendedFromStatus,
        )
    }

    fun encodeDefinition(definition: WorkflowDefinition): ByteArray = encodeDefinition(definition, FORMAT_VERSION)

    internal fun encodeDefinition(definition: WorkflowDefinition, formatVersion: Int): ByteArray =
        Writer(DEFINITION_MAGIC, formatVersion).apply {
        text(definition.tenantId)
        text(definition.definitionId)
        text(definition.key)
        text(definition.version)
        integer(definition.schemaVersion)
        text(definition.status.code)
        text(definition.title)
        nullableText(definition.description)
        list(definition.nodes) { node ->
            text(node.nodeId)
            text(node.kind.code)
            text(node.title)
            nullableText(node.description)
            nullable(node.humanTaskPolicy) { policy -> humanPolicy(policy) }
            nullableText(node.parallelPairNodeId)
            text(node.descriptorDigest)
            text(node.payloadDigest)
        }
        list(definition.transitions) { transition ->
            text(transition.transitionId)
            text(transition.fromNodeId)
            text(transition.toNodeId)
            text(transition.trigger.code)
            nullable(transition.predicate) { predicate ->
                text(predicate.providerId)
                text(predicate.predicateId)
                text(predicate.version)
                text(predicate.digest)
                list(predicate.inputMappings) { mapping ->
                    text(mapping.inputName)
                    text(mapping.sourceKind.code)
                    text(mapping.sourceReference)
                }
            }
        }
        }.finish()

    fun decodeDefinition(payload: ByteArray): WorkflowDefinition = Reader(payload, DEFINITION_MAGIC).use { reader ->
        val tenantId = reader.text()
        val definitionId = reader.text()
        val key = reader.text()
        val version = reader.text()
        val schemaVersion = reader.integer()
        val status = WorkflowDefinitionStatus.of(reader.text())
        val title = reader.text()
        val description = reader.nullableText()
        val nodes = reader.list {
            val nodeId = text()
            val kind = WorkflowNodeKind.of(text())
            val nodeTitle = text()
            val nodeDescription = nullableText()
            val policy = nullable { humanPolicy() }
            val pair = nullableText()
            val descriptorDigest = text()
            val payloadDigest = text()
            when (kind) {
                WorkflowNodeKind.START,
                WorkflowNodeKind.END,
                WorkflowNodeKind.EXCLUSIVE_GATEWAY -> WorkflowNodeDefinition.of(
                    nodeId,
                    kind,
                    nodeTitle,
                    nodeDescription,
                )
                WorkflowNodeKind.HUMAN_TASK -> WorkflowNodeDefinition.humanTask(
                    nodeId,
                    nodeTitle,
                    nodeDescription,
                    requireNotNull(policy) { "Persisted human task policy is missing." },
                )
                WorkflowNodeKind.PARALLEL_SPLIT -> WorkflowNodeDefinition.parallelSplit(
                    nodeId,
                    requireNotNull(pair) { "Persisted parallel pair is missing." },
                    nodeTitle,
                    nodeDescription,
                )
                WorkflowNodeKind.PARALLEL_JOIN -> WorkflowNodeDefinition.parallelJoin(
                    nodeId,
                    requireNotNull(pair) { "Persisted parallel pair is missing." },
                    nodeTitle,
                    nodeDescription,
                )
                WorkflowNodeKind.SERVICE_TASK -> WorkflowNodeDefinition.serviceTask(
                    nodeId,
                    nodeTitle,
                    nodeDescription,
                    descriptorDigest,
                    payloadDigest,
                )
                WorkflowNodeKind.DECISION -> WorkflowNodeDefinition.decision(
                    nodeId,
                    nodeTitle,
                    nodeDescription,
                    descriptorDigest,
                    payloadDigest,
                )
                WorkflowNodeKind.TIMER_WAIT -> WorkflowNodeDefinition.timerWait(
                    nodeId,
                    nodeTitle,
                    nodeDescription,
                    descriptorDigest,
                    payloadDigest,
                )
                WorkflowNodeKind.SUBPROCESS -> WorkflowNodeDefinition.subprocess(
                    nodeId,
                    nodeTitle,
                    nodeDescription,
                    descriptorDigest,
                    payloadDigest,
                )
                else -> WorkflowNodeDefinition.extension(
                    nodeId,
                    kind,
                    nodeTitle,
                    nodeDescription,
                    descriptorDigest,
                    payloadDigest,
                )
            }
        }
        val transitions = reader.list {
            val transitionId = text()
            val from = text()
            val to = text()
            val trigger = WorkflowTransitionTrigger.of(text())
            val predicate = nullable {
                val providerId = text()
                val predicateId = text()
                val predicateVersion = text()
                val digest = text()
                val mappings = list {
                    WorkflowPredicateInputMapping.of(
                        text(),
                        WorkflowPredicateInputSourceKind.of(text()),
                        text(),
                    )
                }
                WorkflowPredicateRef.of(providerId, predicateId, predicateVersion, digest, mappings)
            }
            if (predicate == null) {
                WorkflowTransitionDefinition.unconditional(transitionId, from, to, trigger)
            } else {
                WorkflowTransitionDefinition.conditional(transitionId, from, to, trigger, predicate)
            }
        }
        reader.end()
        WorkflowDefinition.of(
            tenantId,
            definitionId,
            key,
            version,
            schemaVersion,
            status,
            title,
            description,
            nodes,
            transitions,
        )
    }

    private class Writer(magic: String, val formatVersion: Int) {
        private val bytes = ByteArrayOutputStream()
        private val output = DataOutputStream(bytes)

        init {
            require(formatVersion in 1..FORMAT_VERSION) { "Workflow JDBC snapshot format is unsupported." }
            text(magic)
            integer(formatVersion)
        }

        fun text(value: String) {
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            require(encoded.size <= MAX_STRING_BYTES) { "Workflow JDBC snapshot text exceeds the format limit." }
            output.writeInt(encoded.size)
            output.write(encoded)
        }

        fun nullableText(value: String?) = nullable(value, ::text)

        fun integer(value: Int) = output.writeInt(value)

        fun long(value: Long) = output.writeLong(value)

        fun nullableLong(value: Long?) = nullable(value, ::long)

        fun <T> nullable(value: T?, writer: (T) -> Unit) {
            output.writeBoolean(value != null)
            if (value != null) writer(value)
        }

        fun <T> list(values: Collection<T>, writer: (T) -> Unit) {
            require(values.size <= MAX_COLLECTION_ITEMS) { "Workflow JDBC snapshot collection exceeds the format limit." }
            integer(values.size)
            values.forEach(writer)
        }

        fun principal(value: WorkflowPrincipalRef) {
            text(value.type)
            text(value.id)
        }

        fun subject(value: WorkflowSubjectSnapshot) {
            text(value.ref.type)
            text(value.ref.id)
            text(value.revision)
            text(value.digest)
        }

        fun definitionRef(key: String, version: String, digest: String) {
            text(key)
            text(version)
            text(digest)
        }

        fun humanPolicy(policy: WorkflowHumanTaskPolicy) {
            require(formatVersion >= 5 || policy.participantRules.all { rule ->
                rule.membershipStrategy == WorkflowParticipantMembershipStrategy.ACTIVATION_SNAPSHOT
            }) {
                "Workflow JDBC v1/v2/v3/v4 cannot encode current-membership definitions."
            }
            list(policy.participantRules) { rule ->
                selector(rule.selector)
                text(rule.approvalPolicy.mode.code)
                nullable(rule.approvalPolicy.requiredApprovals, ::integer)
                if (formatVersion >= 5) text(rule.membershipStrategy.code)
            }
            output.writeBoolean(policy.capabilities.addSignEnabled)
            output.writeBoolean(policy.capabilities.delegationEnabled)
            output.writeBoolean(policy.capabilities.transferEnabled)
            output.writeBoolean(policy.capabilities.claimEnabled)
            output.writeBoolean(policy.separationOfDuties.initiatorExcluded)
            output.writeBoolean(policy.separationOfDuties.priorApproversExcluded)
            list(policy.resolutionStages) { stage -> text(stage.code) }
            if (formatVersion >= 2) {
                text(policy.evidenceBinding.formKey)
                text(policy.evidenceBinding.formVersion)
                text(policy.evidenceBinding.formDigest)
                text(policy.evidenceBinding.ruleKey)
                text(policy.evidenceBinding.ruleVersion)
                text(policy.evidenceBinding.ruleDigest)
            } else {
                require(policy.evidenceBinding.isBuiltinNone) {
                    "Workflow JDBC v1 cannot encode external human-task evidence bindings."
                }
            }
        }

        private fun selector(selector: WorkflowParticipantSelector) {
            text(selector.kind.code)
            nullable(selector.exactPrincipal, ::principal)
            nullableText(selector.organizationId)
            nullable(selector.minimumManagerLevel, ::integer)
            nullable(selector.maximumManagerLevel, ::integer)
        }

        fun finish(): ByteArray {
            output.flush()
            val result = bytes.toByteArray()
            require(result.size <= MAX_PAYLOAD_BYTES) { "Workflow JDBC snapshot exceeds the format limit." }
            return result
        }
    }

    private class Reader(payload: ByteArray, expectedMagic: String) : AutoCloseable {
        private val input: DataInputStream
        val formatVersion: Int

        init {
            require(payload.size <= MAX_PAYLOAD_BYTES) { "Workflow JDBC snapshot exceeds the format limit." }
            input = DataInputStream(ByteArrayInputStream(payload))
            val magic = text()
            formatVersion = integer()
            require(magic == expectedMagic && formatVersion in 1..FORMAT_VERSION) {
                "Workflow JDBC snapshot format is unsupported."
            }
        }

        fun text(): String {
            val size = input.readInt()
            require(size in 0..MAX_STRING_BYTES && size <= input.available()) {
                "Workflow JDBC snapshot text length is invalid."
            }
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }

        fun nullableText(): String? = nullable { text() }

        fun integer(): Int = input.readInt()

        fun long(): Long = input.readLong()

        fun nullableLong(): Long? = nullable { long() }

        fun bool(): Boolean = input.readBoolean()

        fun <T> nullable(reader: () -> T): T? = if (input.readBoolean()) reader() else null

        fun <T> list(reader: Reader.() -> T): List<T> {
            val size = integer()
            require(size in 0..MAX_COLLECTION_ITEMS) { "Workflow JDBC snapshot collection length is invalid." }
            return ArrayList<T>(size).also { result -> repeat(size) { result += reader(this) } }
        }

        fun principal(): WorkflowPrincipalRef = WorkflowPrincipalRef.of(text(), text())

        fun subject(): WorkflowSubjectSnapshot = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of(text(), text()),
            text(),
            text(),
        )

        fun definitionRef() = ai.icen.fw.workflow.api.WorkflowDefinitionRef.of(text(), text(), text())

        fun humanPolicy(): WorkflowHumanTaskPolicy {
            val rules = list {
                val selector = selector()
                val mode = WorkflowApprovalMode.of(text())
                val required = nullable { integer() }
                val membershipStrategy = if (formatVersion >= 5) {
                    WorkflowParticipantMembershipStrategy.of(text())
                } else {
                    WorkflowParticipantMembershipStrategy.ACTIVATION_SNAPSHOT
                }
                val approval = when (mode) {
                    WorkflowApprovalMode.ONE -> WorkflowApprovalPolicy.one()
                    WorkflowApprovalMode.ALL -> WorkflowApprovalPolicy.all()
                    WorkflowApprovalMode.QUORUM -> WorkflowApprovalPolicy.quorum(
                        requireNotNull(required) { "Persisted workflow quorum is missing." },
                    )
                    else -> throw IllegalArgumentException("Persisted workflow approval mode is unsupported.")
                }
                WorkflowHumanTaskParticipantRule.of(selector, approval, membershipStrategy)
            }
            val capabilities = WorkflowHumanTaskCapabilities.of(bool(), bool(), bool(), bool())
            val separation = WorkflowSeparationOfDutiesPolicy.of(bool(), bool())
            val stages = list { WorkflowParticipantResolutionStage.of(text()) }
            val evidence = if (formatVersion >= 2) {
                WorkflowHumanTaskEvidenceBinding.of(text(), text(), text(), text(), text(), text())
            } else {
                WorkflowHumanTaskEvidenceBinding.none()
            }
            return WorkflowHumanTaskPolicy.of(rules, capabilities, separation, stages, evidence)
        }

        private fun selector(): WorkflowParticipantSelector {
            val kind = WorkflowParticipantSelectorKind.of(text())
            val principal = nullable { principal() }
            val organizationId = nullableText()
            val minimum = nullable { integer() }
            val maximum = nullable { integer() }
            return when (kind) {
                WorkflowParticipantSelectorKind.EXACT_USER -> WorkflowParticipantSelector.exactUser(
                    requireNotNull(principal) { "Persisted exact workflow principal is missing." },
                )
                WorkflowParticipantSelectorKind.GROUP -> WorkflowParticipantSelector.group(requireNotNull(organizationId))
                WorkflowParticipantSelectorKind.ROLE -> WorkflowParticipantSelector.role(requireNotNull(organizationId))
                WorkflowParticipantSelectorKind.POSITION -> WorkflowParticipantSelector.position(requireNotNull(organizationId))
                WorkflowParticipantSelectorKind.DEPARTMENT_LEADERS ->
                    WorkflowParticipantSelector.departmentLeaders(requireNotNull(organizationId))
                WorkflowParticipantSelectorKind.INITIATOR_MANAGER_CHAIN ->
                    WorkflowParticipantSelector.initiatorManagerChain(requireNotNull(minimum), requireNotNull(maximum))
                WorkflowParticipantSelectorKind.CURRENT_ACTOR_MANAGER_CHAIN ->
                    WorkflowParticipantSelector.currentActorManagerChain(requireNotNull(minimum), requireNotNull(maximum))
                else -> WorkflowParticipantSelector.extensionTarget(kind, requireNotNull(organizationId))
            }
        }

        fun end() {
            require(input.available() == 0) { "Workflow JDBC snapshot contains trailing data." }
        }

        override fun close() = input.close()
    }

    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_COLLECTION_ITEMS = 65_536
    private const val MAX_PAYLOAD_BYTES = 64 * 1024 * 1024
}
