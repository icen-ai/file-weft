package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowHumanCollaborationAction
import ai.icen.fw.workflow.api.WorkflowPrincipalRef

/** Immutable audit record for one accepted work-item collaboration transition. */
class WorkflowHumanCollaborationRecord private constructor(
    recordId: String,
    val action: WorkflowHumanCollaborationAction,
    val actor: WorkflowPrincipalRef,
    val target: WorkflowPrincipalRef?,
    val ownerBefore: WorkflowPrincipalRef?,
    val ownerAfter: WorkflowPrincipalRef?,
    val delegateBefore: WorkflowPrincipalRef?,
    val delegateAfter: WorkflowPrincipalRef?,
    authorizationReceiptDigest: String,
    executionNonce: String,
    occurredAt: Long,
) {
    val recordId: String = text(recordId, "record")
    val authorizationReceiptDigest: String = sha(authorizationReceiptDigest, "authorization receipt")
    val executionNonce: String = text(executionNonce, "execution nonce")
    val occurredAt: Long = WorkflowDomainSupport.requireTime(
        occurredAt,
        "Workflow human collaboration time is invalid.",
    )
    val contentDigest: String = WorkflowDomainSupport.digest(
        "flowweft-workflow-domain-human-collaboration-record-v1",
    )
        .text(this.recordId)
        .text(action.code)
        .principal(actor)
        .optionalPrincipal(target)
        .optionalPrincipal(ownerBefore)
        .optionalPrincipal(ownerAfter)
        .optionalPrincipal(delegateBefore)
        .optionalPrincipal(delegateAfter)
        .text(this.authorizationReceiptDigest)
        .text(this.executionNonce)
        .longValue(this.occurredAt)
        .finish()

    override fun toString(): String = "WorkflowHumanCollaborationRecord(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            recordId: String,
            action: WorkflowHumanCollaborationAction,
            actor: WorkflowPrincipalRef,
            target: WorkflowPrincipalRef?,
            ownerBefore: WorkflowPrincipalRef?,
            ownerAfter: WorkflowPrincipalRef?,
            delegateBefore: WorkflowPrincipalRef?,
            delegateAfter: WorkflowPrincipalRef?,
            authorizationReceiptDigest: String,
            executionNonce: String,
            occurredAt: Long,
        ): WorkflowHumanCollaborationRecord = WorkflowHumanCollaborationRecord(
            recordId,
            action,
            actor,
            target,
            ownerBefore,
            ownerAfter,
            delegateBefore,
            delegateAfter,
            authorizationReceiptDigest,
            executionNonce,
            occurredAt,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow human collaboration $label is invalid.",
        )

        private fun sha(value: String, label: String): String = WorkflowDomainSupport.requireSha256(
            value,
            "Workflow human collaboration $label digest is invalid.",
        )

        private fun WorkflowDomainSupport.DigestWriter.principal(value: WorkflowPrincipalRef) =
            text(value.type).text(value.id)

        private fun WorkflowDomainSupport.DigestWriter.optionalPrincipal(value: WorkflowPrincipalRef?) =
            booleanValue(value != null).also { writer -> value?.let { writer.principal(it) } }
    }
}

/** One active before-sign frame. The signer must approve before returning to the exact inviter. */
class WorkflowHumanAddSignFrame private constructor(
    frameId: String,
    val inviter: WorkflowPrincipalRef,
    val signer: WorkflowPrincipalRef,
    val ownerBefore: WorkflowPrincipalRef,
    val delegateBefore: WorkflowPrincipalRef?,
    val priorAssignmentDepth: Int,
    addedAt: Long,
) {
    val frameId: String = WorkflowDomainSupport.requireText(
        frameId,
        WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
        "Workflow add-sign frame id is invalid.",
    )
    val addedAt: Long = WorkflowDomainSupport.requireTime(addedAt, "Workflow add-sign time is invalid.")
    val contentDigest: String

    init {
        require(priorAssignmentDepth in 1 until WorkflowHumanTaskCollaborationState.MAX_ASSIGNMENT_DEPTH) {
            "Workflow add-sign prior assignment depth is invalid."
        }
        require(inviter == (delegateBefore ?: ownerBefore) && signer != inviter) {
            "Workflow add-sign actor binding is invalid."
        }
        contentDigest = WorkflowDomainSupport.digest("flowweft-workflow-domain-human-add-sign-frame-v1")
            .text(this.frameId)
            .text(inviter.type)
            .text(inviter.id)
            .text(signer.type)
            .text(signer.id)
            .text(ownerBefore.type)
            .text(ownerBefore.id)
            .optionalPrincipal(delegateBefore)
            .integer(priorAssignmentDepth)
            .longValue(this.addedAt)
            .finish()
    }

    override fun toString(): String = "WorkflowHumanAddSignFrame(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            frameId: String,
            inviter: WorkflowPrincipalRef,
            signer: WorkflowPrincipalRef,
            ownerBefore: WorkflowPrincipalRef,
            delegateBefore: WorkflowPrincipalRef?,
            priorAssignmentDepth: Int,
            addedAt: Long,
        ): WorkflowHumanAddSignFrame = WorkflowHumanAddSignFrame(
            frameId,
            inviter,
            signer,
            ownerBefore,
            delegateBefore,
            priorAssignmentDepth,
            addedAt,
        )

        private fun WorkflowDomainSupport.DigestWriter.optionalPrincipal(value: WorkflowPrincipalRef?) =
            booleanValue(value != null).also { writer ->
                value?.let { writer.text(it.type).text(it.id) }
            }
    }
}

/**
 * Structured claim/delegation state. Delegation preserves [claimOwner]; transfer replaces it.
 * Before-sign frames are a bounded LIFO chain: each signer approves and then explicitly returns
 * to the exact inviter. [assignmentPath] rejects cycles and [records] never mutate.
 */
class WorkflowHumanTaskCollaborationState private constructor(
    val claimOwner: WorkflowPrincipalRef?,
    val activeDelegate: WorkflowPrincipalRef?,
    assignmentPath: Collection<WorkflowPrincipalRef>,
    addSignFrames: Collection<WorkflowHumanAddSignFrame>,
    records: Collection<WorkflowHumanCollaborationRecord>,
) {
    val assignmentPath: List<WorkflowPrincipalRef> = immutablePrincipals(assignmentPath)
    val addSignFrames: List<WorkflowHumanAddSignFrame> = WorkflowDomainSupport.immutableList(
        addSignFrames,
        MAX_ASSIGNMENT_DEPTH,
        "Workflow add-sign frames are invalid or exceed the limit.",
    )
    val records: List<WorkflowHumanCollaborationRecord> = WorkflowDomainSupport.immutableList(
        records,
        MAX_RECORDS,
        "Workflow human collaboration history is invalid or exceeds the limit.",
    )
    val isPristine: Boolean = claimOwner == null && activeDelegate == null &&
        this.assignmentPath.isEmpty() && this.addSignFrames.isEmpty() && this.records.isEmpty()
    val effectiveActor: WorkflowPrincipalRef? = activeDelegate ?: claimOwner
    val contentDigest: String

    init {
        require(this.assignmentPath.size <= MAX_ASSIGNMENT_DEPTH) {
            "Workflow human collaboration assignment depth exceeds the limit."
        }
        require(this.assignmentPath.toSet().size == this.assignmentPath.size) {
            "Workflow human collaboration assignment path contains a cycle."
        }
        require(this.records.map { it.recordId }.toSet().size == this.records.size &&
            this.records.map { it.executionNonce }.toSet().size == this.records.size
        ) { "Workflow human collaboration records and nonces must be unique." }
        if (claimOwner == null) {
            require(activeDelegate == null && this.assignmentPath.isEmpty() && this.addSignFrames.isEmpty()) {
                "Unclaimed workflow work items cannot retain an assignment path."
            }
        } else {
            require(this.assignmentPath.isNotEmpty() && this.assignmentPath.contains(claimOwner)) {
                "Claimed workflow work items require their owner in the assignment path."
            }
            require(activeDelegate == null || this.assignmentPath.last() == activeDelegate) {
                "Workflow active delegate must terminate the assignment path."
            }
        }
        require(this.addSignFrames.map { it.frameId }.toSet().size == this.addSignFrames.size) {
            "Workflow add-sign frames must be unique."
        }
        if (this.addSignFrames.isNotEmpty()) {
            require(claimOwner != null && activeDelegate == this.addSignFrames.last().signer) {
                "Workflow add-sign stack does not match the active assignment."
            }
            val baseDepth = this.assignmentPath.size - this.addSignFrames.size
            require(baseDepth >= 1) { "Workflow add-sign stack has no base assignment." }
            this.addSignFrames.forEachIndexed { index, frame ->
                val expectedDepth = baseDepth + index
                val sourceRecord = this.records.firstOrNull { it.recordId == frame.frameId }
                require(frame.priorAssignmentDepth == expectedDepth &&
                    this.assignmentPath[expectedDepth - 1] == frame.inviter &&
                    this.assignmentPath[expectedDepth] == frame.signer &&
                    sourceRecord != null && sourceRecord.action == WorkflowHumanCollaborationAction.ADD_SIGN &&
                    sourceRecord.actor == frame.inviter && sourceRecord.target == frame.signer &&
                    sourceRecord.ownerBefore == frame.ownerBefore &&
                    sourceRecord.delegateBefore == frame.delegateBefore &&
                    sourceRecord.occurredAt == frame.addedAt
                ) { "Workflow add-sign stack evidence is inconsistent." }
                val expectedOwner = this.addSignFrames.first().ownerBefore
                val expectedDelegate = if (index == 0) frame.delegateBefore else this.addSignFrames[index - 1].signer
                require(frame.ownerBefore == expectedOwner && frame.delegateBefore == expectedDelegate) {
                    "Workflow nested add-sign stack is inconsistent."
                }
            }
            require(claimOwner == this.addSignFrames.first().ownerBefore) {
                "Workflow add-sign stack changed the claim owner."
            }
        }
        val digestDomain = if (this.addSignFrames.isEmpty()) {
            "flowweft-workflow-domain-human-collaboration-state-v1"
        } else {
            "flowweft-workflow-domain-human-collaboration-state-v2"
        }
        val writer = WorkflowDomainSupport.digest(digestDomain)
            .optionalPrincipal(claimOwner)
            .optionalPrincipal(activeDelegate)
            .integer(this.assignmentPath.size)
        this.assignmentPath.forEach { writer.principal(it) }
        if (this.addSignFrames.isNotEmpty()) {
            writer.integer(this.addSignFrames.size)
            this.addSignFrames.forEach { writer.text(it.contentDigest) }
        }
        writer.integer(this.records.size)
        this.records.forEach { writer.text(it.contentDigest) }
        contentDigest = writer.finish()
    }

    fun hasConsumedNonce(nonce: String): Boolean = records.any { it.executionNonce == nonce }

    fun clearAssignment(): WorkflowHumanTaskCollaborationState =
        WorkflowHumanTaskCollaborationState(null, null, emptyList(), emptyList(), records)

    fun transition(
        recordId: String,
        action: WorkflowHumanCollaborationAction,
        actor: WorkflowPrincipalRef,
        target: WorkflowPrincipalRef?,
        authorizationReceiptDigest: String,
        executionNonce: String,
        occurredAt: Long,
    ): WorkflowHumanTaskCollaborationState {
        require(records.size < MAX_RECORDS) { "Workflow human collaboration history limit was reached." }
        require(!hasConsumedNonce(executionNonce)) { "Workflow human collaboration nonce was already consumed." }
        val beforeOwner = claimOwner
        val beforeDelegate = activeDelegate
        val afterOwner: WorkflowPrincipalRef?
        val afterDelegate: WorkflowPrincipalRef?
        val afterPath: List<WorkflowPrincipalRef>
        val afterAddSignFrames: List<WorkflowHumanAddSignFrame>
        when (action) {
            WorkflowHumanCollaborationAction.CLAIM -> {
                require(claimOwner == null && target == null) { "Workflow work item is already claimed." }
                afterOwner = actor
                afterDelegate = null
                afterPath = listOf(actor)
                afterAddSignFrames = emptyList()
            }
            WorkflowHumanCollaborationAction.UNCLAIM -> {
                require(claimOwner != null && target == null) { "Workflow work item is not claimed." }
                afterOwner = null
                afterDelegate = null
                afterPath = emptyList()
                afterAddSignFrames = emptyList()
            }
            WorkflowHumanCollaborationAction.DELEGATE -> {
                val delegate = requireNotNull(target) { "Workflow delegation target is required." }
                require(effectiveActor == actor && !assignmentPath.contains(delegate)) {
                    "Workflow delegation actor or target is invalid."
                }
                require(addSignFrames.isEmpty()) { "Workflow delegation cannot bypass an active add-sign frame." }
                require(assignmentPath.size < MAX_ASSIGNMENT_DEPTH) {
                    "Workflow delegation depth limit was reached."
                }
                afterOwner = claimOwner
                afterDelegate = delegate
                afterPath = assignmentPath + delegate
                afterAddSignFrames = emptyList()
            }
            WorkflowHumanCollaborationAction.TRANSFER -> {
                val newOwner = requireNotNull(target) { "Workflow transfer target is required." }
                require(effectiveActor == actor && !assignmentPath.contains(newOwner)) {
                    "Workflow transfer actor or target is invalid."
                }
                require(addSignFrames.isEmpty()) { "Workflow transfer cannot bypass an active add-sign frame." }
                require(assignmentPath.size < MAX_ASSIGNMENT_DEPTH) {
                    "Workflow transfer depth limit was reached."
                }
                afterOwner = newOwner
                afterDelegate = null
                afterPath = assignmentPath + newOwner
                afterAddSignFrames = emptyList()
            }
            WorkflowHumanCollaborationAction.ADD_SIGN -> {
                val signer = requireNotNull(target) { "Workflow add-sign target is required." }
                val owner = requireNotNull(claimOwner) { "Workflow add-sign requires a claimed work item." }
                require(effectiveActor == actor && !assignmentPath.contains(signer)) {
                    "Workflow add-sign actor or target is invalid."
                }
                require(assignmentPath.size < MAX_ASSIGNMENT_DEPTH) {
                    "Workflow add-sign depth limit was reached."
                }
                val frame = WorkflowHumanAddSignFrame.of(
                    recordId,
                    actor,
                    signer,
                    owner,
                    activeDelegate,
                    assignmentPath.size,
                    occurredAt,
                )
                afterOwner = owner
                afterDelegate = signer
                afterPath = assignmentPath + signer
                afterAddSignFrames = addSignFrames + frame
            }
            WorkflowHumanCollaborationAction.RETURN -> {
                val returnTarget = requireNotNull(target) { "Workflow return target is required." }
                val frame = addSignFrames.lastOrNull()
                    ?: throw IllegalArgumentException("Workflow return requires an active add-sign frame.")
                require(effectiveActor == actor && frame.signer == actor && frame.inviter == returnTarget &&
                    assignmentPath.size == frame.priorAssignmentDepth + 1 && assignmentPath.last() == actor
                ) { "Workflow return actor or target is invalid." }
                afterOwner = frame.ownerBefore
                afterDelegate = frame.delegateBefore
                afterPath = assignmentPath.dropLast(1)
                afterAddSignFrames = addSignFrames.dropLast(1)
            }
            else -> throw IllegalArgumentException("Unknown workflow human collaboration action is unsupported.")
        }
        val record = WorkflowHumanCollaborationRecord.of(
            recordId,
            action,
            actor,
            target,
            beforeOwner,
            afterOwner,
            beforeDelegate,
            afterDelegate,
            authorizationReceiptDigest,
            executionNonce,
            occurredAt,
        )
        return WorkflowHumanTaskCollaborationState(
            afterOwner,
            afterDelegate,
            afterPath,
            afterAddSignFrames,
            records + record,
        )
    }

    override fun toString(): String = "WorkflowHumanTaskCollaborationState(<redacted>)"

    companion object {
        const val MAX_ASSIGNMENT_DEPTH: Int = 16
        const val MAX_RECORDS: Int = 64

        @JvmStatic fun unclaimed(): WorkflowHumanTaskCollaborationState =
            WorkflowHumanTaskCollaborationState(null, null, emptyList(), emptyList(), emptyList())

        @JvmStatic fun restore(
            claimOwner: WorkflowPrincipalRef?,
            activeDelegate: WorkflowPrincipalRef?,
            assignmentPath: Collection<WorkflowPrincipalRef>,
            records: Collection<WorkflowHumanCollaborationRecord>,
        ): WorkflowHumanTaskCollaborationState = WorkflowHumanTaskCollaborationState(
            claimOwner,
            activeDelegate,
            assignmentPath,
            emptyList(),
            records,
        )

        @JvmStatic fun restore(
            claimOwner: WorkflowPrincipalRef?,
            activeDelegate: WorkflowPrincipalRef?,
            assignmentPath: Collection<WorkflowPrincipalRef>,
            addSignFrames: Collection<WorkflowHumanAddSignFrame>,
            records: Collection<WorkflowHumanCollaborationRecord>,
        ): WorkflowHumanTaskCollaborationState = WorkflowHumanTaskCollaborationState(
            claimOwner,
            activeDelegate,
            assignmentPath,
            addSignFrames,
            records,
        )

        private fun immutablePrincipals(values: Collection<WorkflowPrincipalRef>): List<WorkflowPrincipalRef> =
            WorkflowDomainSupport.immutableList(
                values,
                MAX_ASSIGNMENT_DEPTH,
                "Workflow human collaboration assignment path is invalid or exceeds the limit.",
            )

        private fun WorkflowDomainSupport.DigestWriter.principal(value: WorkflowPrincipalRef) =
            text(value.type).text(value.id)

        private fun WorkflowDomainSupport.DigestWriter.optionalPrincipal(value: WorkflowPrincipalRef?) =
            booleanValue(value != null).also { writer -> value?.let { writer.principal(it) } }
    }
}
