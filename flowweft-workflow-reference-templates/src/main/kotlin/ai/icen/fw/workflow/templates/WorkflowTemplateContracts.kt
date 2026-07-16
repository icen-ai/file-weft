package ai.icen.fw.workflow.templates

import ai.icen.fw.workflow.api.WorkflowDefinition
import ai.icen.fw.workflow.api.WorkflowHumanTaskEvidenceBinding
import ai.icen.fw.workflow.api.WorkflowNodeKind
import java.util.Collections

/** Stable category for an exact host/provider profile used by a reference template. */
class WorkflowTemplateProfileKind private constructor(code: String) {
    val code: String = ReferenceTemplateContractSupport.code(code, "Workflow template profile kind is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowTemplateProfileKind && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowTemplateProfileKind(<redacted>)"

    companion object {
        @JvmField val SUBJECT = WorkflowTemplateProfileKind("subject")
        @JvmField val ORGANIZATION = WorkflowTemplateProfileKind("organization")
        @JvmField val FORM = WorkflowTemplateProfileKind("form")
        @JvmField val PREDICATE = WorkflowTemplateProfileKind("predicate")
        @JvmField val BUSINESS_CALENDAR = WorkflowTemplateProfileKind("business-calendar")
        @JvmField val SERVICE_NODE = WorkflowTemplateProfileKind("service-node")

        @JvmStatic
        fun of(code: String): WorkflowTemplateProfileKind = when (code) {
            SUBJECT.code -> SUBJECT
            ORGANIZATION.code -> ORGANIZATION
            FORM.code -> FORM
            PREDICATE.code -> PREDICATE
            BUSINESS_CALENDAR.code -> BUSINESS_CALENDAR
            SERVICE_NODE.code -> SERVICE_NODE
            else -> WorkflowTemplateProfileKind(code)
        }
    }
}

/** Exact immutable profile identity. A profile id is a registry key, never a URL or credential. */
class WorkflowTemplateProfileRef private constructor(
    val kind: WorkflowTemplateProfileKind,
    profileId: String,
    version: String,
    digest: String,
) {
    val profileId: String = ReferenceTemplateContractSupport.code(
        profileId,
        "Workflow template profile identifier is invalid.",
    )
    val version: String = ReferenceTemplateContractSupport.text(
        version,
        ReferenceTemplateContractSupport.MAX_VERSION_BYTES,
        "Workflow template profile version is invalid.",
    )
    val digest: String = ReferenceTemplateContractSupport.sha256(
        digest,
        "Workflow template profile digest is invalid.",
    )
    val bindingDigest: String = ReferenceTemplateContractSupport.digest(
        "flowweft-workflow-template-profile-v1",
        kind.code,
        this.profileId,
        this.version,
        this.digest,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowTemplateProfileRef && bindingDigest == other.bindingDigest

    override fun hashCode(): Int = bindingDigest.hashCode()
    override fun toString(): String = "WorkflowTemplateProfileRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            kind: WorkflowTemplateProfileKind,
            profileId: String,
            version: String,
            digest: String,
        ): WorkflowTemplateProfileRef = WorkflowTemplateProfileRef(kind, profileId, version, digest)
    }
}

/** Subject types remain host-owned; the template only pins the resolver profile that validates them. */
class WorkflowTemplateSubjectBinding private constructor(
    subjectType: String,
    val profile: WorkflowTemplateProfileRef,
) {
    val subjectType: String = ReferenceTemplateContractSupport.code(
        subjectType,
        "Workflow template subject type is invalid.",
    )
    val contentDigest: String

    init {
        require(profile.kind == WorkflowTemplateProfileKind.SUBJECT) {
            "Workflow template subjects require a subject profile."
        }
        contentDigest = ReferenceTemplateContractSupport.digest(
            "flowweft-workflow-template-subject-v1",
            this.subjectType,
            profile.bindingDigest,
        )
    }

    override fun toString(): String = "WorkflowTemplateSubjectBinding(<redacted>)"

    companion object {
        @JvmStatic
        fun of(subjectType: String, profile: WorkflowTemplateProfileRef): WorkflowTemplateSubjectBinding =
            WorkflowTemplateSubjectBinding(subjectType, profile)
    }
}

class WorkflowTemplateSelectorBinding private constructor(
    nodeId: String,
    val ruleIndex: Int,
    slotId: String,
    selectorDigest: String,
    val profile: WorkflowTemplateProfileRef,
) {
    val nodeId: String = ReferenceTemplateContractSupport.code(nodeId, "Workflow selector node is invalid.")
    val slotId: String = ReferenceTemplateContractSupport.code(slotId, "Workflow selector slot is invalid.")
    val selectorDigest: String = ReferenceTemplateContractSupport.sha256(
        selectorDigest,
        "Workflow selector binding digest is invalid.",
    )
    val contentDigest: String

    init {
        require(ruleIndex >= 0) { "Workflow selector rule index is invalid." }
        require(profile.kind == WorkflowTemplateProfileKind.ORGANIZATION) {
            "Workflow selectors require an organization profile."
        }
        contentDigest = ReferenceTemplateContractSupport.digest(
            "flowweft-workflow-template-selector-binding-v1",
            this.nodeId,
            ruleIndex.toString(),
            this.slotId,
            this.selectorDigest,
            profile.bindingDigest,
        )
    }

    override fun toString(): String = "WorkflowTemplateSelectorBinding(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            nodeId: String,
            ruleIndex: Int,
            slotId: String,
            selectorDigest: String,
            profile: WorkflowTemplateProfileRef,
        ): WorkflowTemplateSelectorBinding = WorkflowTemplateSelectorBinding(
            nodeId,
            ruleIndex,
            slotId,
            selectorDigest,
            profile,
        )
    }
}

class WorkflowTemplateFormBinding private constructor(
    nodeId: String,
    val evidence: WorkflowHumanTaskEvidenceBinding,
    val profile: WorkflowTemplateProfileRef,
) {
    val nodeId: String = ReferenceTemplateContractSupport.code(nodeId, "Workflow form node is invalid.")
    val contentDigest: String

    init {
        require(!evidence.isBuiltinNone) { "Reference workflow templates require an exact form/rule binding." }
        require(profile.kind == WorkflowTemplateProfileKind.FORM) {
            "Workflow form bindings require a form profile."
        }
        contentDigest = ReferenceTemplateContractSupport.digest(
            "flowweft-workflow-template-form-binding-v1",
            this.nodeId,
            evidence.contentDigest,
            profile.bindingDigest,
        )
    }

    override fun toString(): String = "WorkflowTemplateFormBinding(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            nodeId: String,
            evidence: WorkflowHumanTaskEvidenceBinding,
            profile: WorkflowTemplateProfileRef,
        ): WorkflowTemplateFormBinding = WorkflowTemplateFormBinding(nodeId, evidence, profile)
    }
}

class WorkflowTemplatePredicateBinding private constructor(
    transitionId: String,
    slotId: String,
    predicateBindingDigest: String,
    val profile: WorkflowTemplateProfileRef,
) {
    val transitionId: String = ReferenceTemplateContractSupport.code(
        transitionId,
        "Workflow predicate transition is invalid.",
    )
    val slotId: String = ReferenceTemplateContractSupport.code(slotId, "Workflow predicate slot is invalid.")
    val predicateBindingDigest: String = ReferenceTemplateContractSupport.sha256(
        predicateBindingDigest,
        "Workflow predicate binding digest is invalid.",
    )
    val contentDigest: String

    init {
        require(profile.kind == WorkflowTemplateProfileKind.PREDICATE) {
            "Workflow predicates require a predicate profile."
        }
        contentDigest = ReferenceTemplateContractSupport.digest(
            "flowweft-workflow-template-predicate-binding-v1",
            this.transitionId,
            this.slotId,
            this.predicateBindingDigest,
            profile.bindingDigest,
        )
    }

    override fun toString(): String = "WorkflowTemplatePredicateBinding(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            transitionId: String,
            slotId: String,
            predicateBindingDigest: String,
            profile: WorkflowTemplateProfileRef,
        ): WorkflowTemplatePredicateBinding = WorkflowTemplatePredicateBinding(
            transitionId,
            slotId,
            predicateBindingDigest,
            profile,
        )
    }
}

/** Exact binding for a provider-backed node; descriptor bytes and secrets stay outside the template. */
class WorkflowTemplateProviderNodeBinding private constructor(
    nodeId: String,
    slotId: String,
    val nodeKind: WorkflowNodeKind,
    descriptorDigest: String,
    payloadDigest: String,
    val profile: WorkflowTemplateProfileRef,
) {
    val nodeId: String = ReferenceTemplateContractSupport.code(nodeId, "Workflow provider node is invalid.")
    val slotId: String = ReferenceTemplateContractSupport.code(slotId, "Workflow provider node slot is invalid.")
    val descriptorDigest: String = ReferenceTemplateContractSupport.sha256(
        descriptorDigest,
        "Workflow provider descriptor digest is invalid.",
    )
    val payloadDigest: String = ReferenceTemplateContractSupport.sha256(
        payloadDigest,
        "Workflow provider payload digest is invalid.",
    )
    val contentDigest: String

    init {
        require(profile.kind == WorkflowTemplateProfileKind.SERVICE_NODE) {
            "Workflow provider nodes require a service-node profile."
        }
        require(nodeKind == WorkflowNodeKind.SERVICE_TASK || nodeKind == WorkflowNodeKind.DECISION ||
            nodeKind == WorkflowNodeKind.TIMER_WAIT || nodeKind == WorkflowNodeKind.SUBPROCESS ||
            nodeKind == WorkflowNodeKind.EXTENSION || !isBuiltin(nodeKind)
        ) { "Workflow provider node kind is invalid." }
        contentDigest = ReferenceTemplateContractSupport.digest(
            "flowweft-workflow-template-provider-node-v1",
            this.nodeId,
            this.slotId,
            nodeKind.code,
            this.descriptorDigest,
            this.payloadDigest,
            profile.bindingDigest,
        )
    }

    override fun toString(): String = "WorkflowTemplateProviderNodeBinding(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            nodeId: String,
            slotId: String,
            nodeKind: WorkflowNodeKind,
            descriptorDigest: String,
            payloadDigest: String,
            profile: WorkflowTemplateProfileRef,
        ): WorkflowTemplateProviderNodeBinding = WorkflowTemplateProviderNodeBinding(
            nodeId,
            slotId,
            nodeKind,
            descriptorDigest,
            payloadDigest,
            profile,
        )

        private fun isBuiltin(kind: WorkflowNodeKind): Boolean =
            kind == WorkflowNodeKind.START || kind == WorkflowNodeKind.END ||
                kind == WorkflowNodeKind.HUMAN_TASK || kind == WorkflowNodeKind.SERVICE_TASK ||
                kind == WorkflowNodeKind.DECISION || kind == WorkflowNodeKind.EXCLUSIVE_GATEWAY ||
                kind == WorkflowNodeKind.PARALLEL_SPLIT || kind == WorkflowNodeKind.PARALLEL_JOIN ||
                kind == WorkflowNodeKind.TIMER_WAIT || kind == WorkflowNodeKind.SUBPROCESS ||
                kind == WorkflowNodeKind.EXTENSION
    }
}

/** Metadata-only SLA policy. Runtime support is an explicit simulation/deployment requirement. */
class WorkflowTemplateSlaMetadata private constructor(
    nodeId: String,
    val reminderAfterSeconds: Long,
    val dueAfterSeconds: Long,
    val escalationAfterSeconds: Long,
    val calendarProfile: WorkflowTemplateProfileRef,
) {
    val nodeId: String = ReferenceTemplateContractSupport.code(nodeId, "Workflow SLA node is invalid.")
    val contentDigest: String

    init {
        require(reminderAfterSeconds > 0L && reminderAfterSeconds < dueAfterSeconds &&
            dueAfterSeconds <= escalationAfterSeconds
        ) { "Workflow SLA intervals must be positive and ordered." }
        require(calendarProfile.kind == WorkflowTemplateProfileKind.BUSINESS_CALENDAR) {
            "Workflow SLA metadata requires a business-calendar profile."
        }
        contentDigest = ReferenceTemplateContractSupport.digest(
            "flowweft-workflow-template-sla-v1",
            this.nodeId,
            reminderAfterSeconds.toString(),
            dueAfterSeconds.toString(),
            escalationAfterSeconds.toString(),
            calendarProfile.bindingDigest,
        )
    }

    override fun toString(): String = "WorkflowTemplateSlaMetadata(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            nodeId: String,
            reminderAfterSeconds: Long,
            dueAfterSeconds: Long,
            escalationAfterSeconds: Long,
            calendarProfile: WorkflowTemplateProfileRef,
        ): WorkflowTemplateSlaMetadata = WorkflowTemplateSlaMetadata(
            nodeId,
            reminderAfterSeconds,
            dueAfterSeconds,
            escalationAfterSeconds,
            calendarProfile,
        )
    }
}

/** Required durable bound for one graph cycle. It is metadata until a runtime declares support. */
class WorkflowTemplateCycleBudget private constructor(
    cycleId: String,
    entryNodeId: String,
    val maximumIterations: Int,
) {
    val cycleId: String = ReferenceTemplateContractSupport.code(cycleId, "Workflow cycle id is invalid.")
    val entryNodeId: String = ReferenceTemplateContractSupport.code(
        entryNodeId,
        "Workflow cycle entry node is invalid.",
    )
    val contentDigest: String

    init {
        require(maximumIterations in 1..ReferenceTemplateContractSupport.MAX_CYCLE_ITERATIONS) {
            "Workflow cycle iteration budget is invalid."
        }
        contentDigest = ReferenceTemplateContractSupport.digest(
            "flowweft-workflow-template-cycle-budget-v1",
            this.cycleId,
            this.entryNodeId,
            maximumIterations.toString(),
        )
    }

    override fun toString(): String = "WorkflowTemplateCycleBudget(<redacted>)"

    companion object {
        @JvmStatic
        fun of(cycleId: String, entryNodeId: String, maximumIterations: Int): WorkflowTemplateCycleBudget =
            WorkflowTemplateCycleBudget(cycleId, entryNodeId, maximumIterations)
    }
}

/** A bound, draft-only reference template. It is never a publication or provider-availability receipt. */
class WorkflowReferenceTemplate private constructor(
    templateId: String,
    templateVersion: String,
    val definition: WorkflowDefinition,
    val subject: WorkflowTemplateSubjectBinding,
    selectorBindings: Collection<WorkflowTemplateSelectorBinding>,
    formBindings: Collection<WorkflowTemplateFormBinding>,
    predicateBindings: Collection<WorkflowTemplatePredicateBinding>,
    providerNodeBindings: Collection<WorkflowTemplateProviderNodeBinding>,
    slaMetadata: Collection<WorkflowTemplateSlaMetadata>,
    cycleBudgets: Collection<WorkflowTemplateCycleBudget>,
    simulationCases: Collection<WorkflowTemplateSimulationCase>,
) {
    val templateId: String = ReferenceTemplateContractSupport.code(templateId, "Workflow template id is invalid.")
    val templateVersion: String = ReferenceTemplateContractSupport.text(
        templateVersion,
        ReferenceTemplateContractSupport.MAX_VERSION_BYTES,
        "Workflow template version is invalid.",
    )
    val selectorBindings: List<WorkflowTemplateSelectorBinding> = immutable(selectorBindings, "selector")
    val formBindings: List<WorkflowTemplateFormBinding> = immutable(formBindings, "form")
    val predicateBindings: List<WorkflowTemplatePredicateBinding> = immutable(predicateBindings, "predicate")
    val providerNodeBindings: List<WorkflowTemplateProviderNodeBinding> = immutable(
        providerNodeBindings,
        "provider node",
    )
    val slaMetadata: List<WorkflowTemplateSlaMetadata> = immutable(slaMetadata, "SLA")
    val cycleBudgets: List<WorkflowTemplateCycleBudget> = immutable(cycleBudgets, "cycle budget")
    val simulationCases: List<WorkflowTemplateSimulationCase> = immutable(simulationCases, "simulation case")
    val templateDigest: String

    init {
        require(this.simulationCases.isNotEmpty()) { "Workflow reference templates require simulation cases." }
        val digests = ArrayList<String>()
        digests.add(this.templateId)
        digests.add(this.templateVersion)
        digests.add(definition.contentDigest)
        digests.add(subject.contentDigest)
        digests.add("selectors:${this.selectorBindings.size}")
        this.selectorBindings.forEach { digests.add(it.contentDigest) }
        digests.add("forms:${this.formBindings.size}")
        this.formBindings.forEach { digests.add(it.contentDigest) }
        digests.add("predicates:${this.predicateBindings.size}")
        this.predicateBindings.forEach { digests.add(it.contentDigest) }
        digests.add("provider-nodes:${this.providerNodeBindings.size}")
        this.providerNodeBindings.forEach { digests.add(it.contentDigest) }
        digests.add("sla:${this.slaMetadata.size}")
        this.slaMetadata.forEach { digests.add(it.contentDigest) }
        digests.add("cycle-budgets:${this.cycleBudgets.size}")
        this.cycleBudgets.forEach { digests.add(it.contentDigest) }
        digests.add("simulation-cases:${this.simulationCases.size}")
        this.simulationCases.forEach { digests.add(it.contentDigest) }
        templateDigest = ReferenceTemplateContractSupport.digest(
            "flowweft-workflow-reference-template-v1",
            digests,
        )
    }

    fun simulationCase(caseId: String): WorkflowTemplateSimulationCase = simulationCases.firstOrNull {
        it.caseId == caseId
    } ?: throw IllegalArgumentException("Workflow template simulation case is absent.")

    override fun toString(): String = "WorkflowReferenceTemplate(<redacted>)"

    private fun <T> immutable(values: Collection<T>, label: String): List<T> = Collections.unmodifiableList(
        ArrayList(
            ReferenceTemplateContractSupport.immutableList(
                values,
                ReferenceTemplateContractSupport.MAX_BINDINGS,
                "Workflow template $label bindings are invalid or exceed the limit.",
            ),
        ),
    )

    companion object {
        @JvmSynthetic
        internal fun create(
            templateId: String,
            templateVersion: String,
            definition: WorkflowDefinition,
            subject: WorkflowTemplateSubjectBinding,
            selectorBindings: Collection<WorkflowTemplateSelectorBinding>,
            formBindings: Collection<WorkflowTemplateFormBinding>,
            predicateBindings: Collection<WorkflowTemplatePredicateBinding>,
            providerNodeBindings: Collection<WorkflowTemplateProviderNodeBinding>,
            slaMetadata: Collection<WorkflowTemplateSlaMetadata>,
            cycleBudgets: Collection<WorkflowTemplateCycleBudget>,
            simulationCases: Collection<WorkflowTemplateSimulationCase>,
        ): WorkflowReferenceTemplate = WorkflowReferenceTemplate(
            templateId,
            templateVersion,
            definition,
            subject,
            selectorBindings,
            formBindings,
            predicateBindings,
            providerNodeBindings,
            slaMetadata,
            cycleBudgets,
            simulationCases,
        )
    }
}
