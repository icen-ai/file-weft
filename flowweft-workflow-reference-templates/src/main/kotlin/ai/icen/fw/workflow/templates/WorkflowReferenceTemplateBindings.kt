package ai.icen.fw.workflow.templates

import ai.icen.fw.workflow.api.WorkflowHumanTaskEvidenceBinding
import ai.icen.fw.workflow.api.WorkflowParticipantSelector
import ai.icen.fw.workflow.api.WorkflowPredicateRef

/** Exact provider-node material selected by an administrator before a template is linted. */
class WorkflowTemplateProviderMaterial private constructor(
    descriptorDigest: String,
    payloadDigest: String,
) {
    val descriptorDigest: String = ReferenceTemplateContractSupport.sha256(
        descriptorDigest,
        "Workflow provider descriptor digest is invalid.",
    )
    val payloadDigest: String = ReferenceTemplateContractSupport.sha256(
        payloadDigest,
        "Workflow provider payload digest is invalid.",
    )

    override fun toString(): String = "WorkflowTemplateProviderMaterial(<redacted>)"

    companion object {
        @JvmStatic
        fun of(descriptorDigest: String, payloadDigest: String): WorkflowTemplateProviderMaterial =
            WorkflowTemplateProviderMaterial(descriptorDigest, payloadDigest)
    }
}

/**
 * Host binding input for all fixed reference factories.
 *
 * Map keys are documented template slots. Values are copied immediately. No value authorizes a
 * user, proves a provider exists, or permits publication; lint and deployment checks remain
 * mandatory.
 */
class WorkflowReferenceTemplateBindings private constructor(
    val subject: WorkflowTemplateSubjectBinding,
    selectors: Map<String, WorkflowParticipantSelector>,
    evidenceBindings: Map<String, WorkflowHumanTaskEvidenceBinding>,
    predicates: Map<String, WorkflowPredicateRef>,
    providerMaterials: Map<String, WorkflowTemplateProviderMaterial>,
    profiles: Map<String, WorkflowTemplateProfileRef>,
) {
    val selectors: Map<String, WorkflowParticipantSelector> = checkedMap(selectors, "selector")
    val evidenceBindings: Map<String, WorkflowHumanTaskEvidenceBinding> = checkedMap(
        evidenceBindings,
        "form/rule evidence",
    )
    val predicates: Map<String, WorkflowPredicateRef> = checkedMap(predicates, "predicate")
    val providerMaterials: Map<String, WorkflowTemplateProviderMaterial> = checkedMap(
        providerMaterials,
        "provider material",
    )
    val profiles: Map<String, WorkflowTemplateProfileRef> = checkedMap(profiles, "profile")

    fun selector(slotId: String): WorkflowParticipantSelector = selectors[slotId]
        ?: throw IllegalArgumentException("Required workflow template selector binding is absent: $slotId")

    fun evidence(slotId: String): WorkflowHumanTaskEvidenceBinding = evidenceBindings[slotId]
        ?: throw IllegalArgumentException("Required workflow template evidence binding is absent: $slotId")

    fun predicate(slotId: String): WorkflowPredicateRef = predicates[slotId]
        ?: throw IllegalArgumentException("Required workflow template predicate binding is absent: $slotId")

    fun providerMaterial(slotId: String): WorkflowTemplateProviderMaterial = providerMaterials[slotId]
        ?: throw IllegalArgumentException("Required workflow template provider binding is absent: $slotId")

    fun profile(slotId: String, expectedKind: WorkflowTemplateProfileKind): WorkflowTemplateProfileRef {
        val profile = profiles[slotId]
            ?: throw IllegalArgumentException("Required workflow template profile binding is absent: $slotId")
        require(profile.kind == expectedKind) {
            "Workflow template profile binding has the wrong kind: $slotId"
        }
        return profile
    }

    override fun toString(): String = "WorkflowReferenceTemplateBindings(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            subject: WorkflowTemplateSubjectBinding,
            selectors: Map<String, WorkflowParticipantSelector>,
            evidenceBindings: Map<String, WorkflowHumanTaskEvidenceBinding>,
            predicates: Map<String, WorkflowPredicateRef>,
            providerMaterials: Map<String, WorkflowTemplateProviderMaterial>,
            profiles: Map<String, WorkflowTemplateProfileRef>,
        ): WorkflowReferenceTemplateBindings = WorkflowReferenceTemplateBindings(
            subject,
            selectors,
            evidenceBindings,
            predicates,
            providerMaterials,
            profiles,
        )

        @JvmStatic
        fun of(
            subject: WorkflowTemplateSubjectBinding,
            selectors: Map<String, WorkflowParticipantSelector>,
            evidenceBindings: Map<String, WorkflowHumanTaskEvidenceBinding>,
            predicates: Map<String, WorkflowPredicateRef>,
            profiles: Map<String, WorkflowTemplateProfileRef>,
        ): WorkflowReferenceTemplateBindings = WorkflowReferenceTemplateBindings(
            subject,
            selectors,
            evidenceBindings,
            predicates,
            emptyMap(),
            profiles,
        )

        private fun <T> checkedMap(values: Map<String, T>, label: String): Map<String, T> {
            values.keys.forEach { slot ->
                ReferenceTemplateContractSupport.code(slot, "Workflow template $label slot is invalid.")
            }
            return ReferenceTemplateContractSupport.immutableMap(
                values,
                ReferenceTemplateContractSupport.MAX_BINDINGS,
                "Workflow template $label bindings are invalid or exceed the limit.",
            )
        }
    }
}
