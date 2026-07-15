package ai.icen.fw.workflow.api

/**
 * Immutable reference to a typed predicate provider contract and ordered input bindings.
 *
 * [digest] binds the provider-owned predicate descriptor for the exact id and version;
 * [bindingDigest] additionally binds this reference's ordered input mappings. The model accepts no
 * embedded script or expression. Both digests are caller-constructible claims: a trusted runtime
 * must resolve the exact provider/version/digest and reject unsupported or changed capabilities.
 */
class WorkflowPredicateRef private constructor(
    providerId: String,
    predicateId: String,
    version: String,
    digest: String,
    inputMappings: Collection<WorkflowPredicateInputMapping>,
) {
    val providerId: String = WorkflowContractSupport.requireMachineCode(
        providerId,
        "Workflow predicate provider identifier is invalid.",
    )
    val predicateId: String = WorkflowContractSupport.requireMachineCode(
        predicateId,
        "Workflow predicate identifier is invalid.",
    )
    val version: String = WorkflowContractSupport.requireText(
        version,
        WorkflowContractSupport.MAX_DEFINITION_VERSION_UTF8_BYTES,
        "Workflow predicate version is invalid.",
    )
    val digest: String = WorkflowContractSupport.requireCanonicalSha256(
        digest,
        "Workflow predicate digest must be a canonical lower-case SHA-256 digest.",
    )
    val inputMappings: List<WorkflowPredicateInputMapping> = WorkflowContractSupport.immutableList(
        inputMappings,
        WorkflowContractSupport.MAX_PREDICATE_INPUTS,
        "Workflow predicate input mappings are invalid or exceed the limit.",
    )
    val bindingDigest: String

    init {
        require(this.inputMappings.map { mapping -> mapping.inputName }.toSet().size == this.inputMappings.size) {
            "Workflow predicate input names must be unique."
        }

        val writer = WorkflowContractSupport.digest(WorkflowContractSupport.PREDICATE_REF_DIGEST_DOMAIN)
            .text(this.providerId)
            .text(this.predicateId)
            .text(this.version)
            .text(this.digest)
            .integer(this.inputMappings.size)
        this.inputMappings.forEach { mapping -> writer.text(mapping.contentDigest) }
        bindingDigest = writer.finish()
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowPredicateRef &&
            providerId == other.providerId &&
            predicateId == other.predicateId &&
            version == other.version &&
            digest == other.digest &&
            inputMappings == other.inputMappings

    override fun hashCode(): Int {
        var result = providerId.hashCode()
        result = 31 * result + predicateId.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + digest.hashCode()
        result = 31 * result + inputMappings.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowPredicateRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            providerId: String,
            predicateId: String,
            version: String,
            digest: String,
            inputMappings: Collection<WorkflowPredicateInputMapping>,
        ): WorkflowPredicateRef = WorkflowPredicateRef(
            providerId,
            predicateId,
            version,
            digest,
            inputMappings,
        )
    }
}
