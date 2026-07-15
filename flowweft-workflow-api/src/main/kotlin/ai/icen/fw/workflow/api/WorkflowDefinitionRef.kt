package ai.icen.fw.workflow.api

/**
 * Immutable reference to one exact workflow definition revision.
 *
 * [key] and [version] are case-sensitive, preserved exactly, and bounded to 256 and 128 UTF-8
 * bytes respectively. [digest] is validated but never recomputed or rewritten by this contract.
 * The caller-constructible value is not proof that the definition exists or is authorized; the
 * runtime must resolve and compare it inside the authenticated tenant.
 */
class WorkflowDefinitionRef private constructor(key: String, version: String, digest: String) {
    val key: String = WorkflowContractSupport.requireText(
        key,
        WorkflowContractSupport.MAX_DEFINITION_KEY_UTF8_BYTES,
        "Workflow definition key is invalid.",
    )
    val version: String = WorkflowContractSupport.requireText(
        version,
        WorkflowContractSupport.MAX_DEFINITION_VERSION_UTF8_BYTES,
        "Workflow definition version is invalid.",
    )
    val digest: String = WorkflowContractSupport.requireCanonicalSha256(
        digest,
        "Workflow definition digest must be a canonical lower-case SHA-256 digest.",
    )

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowDefinitionRef &&
            key == other.key &&
            version == other.version &&
            digest == other.digest

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + digest.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowDefinitionRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(key: String, version: String, digest: String): WorkflowDefinitionRef =
            WorkflowDefinitionRef(key, version, digest)
    }
}
