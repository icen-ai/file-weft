package ai.icen.fw.workflow.api

/**
 * Caller-supplied, version-bound subject claim.
 *
 * [revision] is preserved exactly and bounded to 256 UTF-8 bytes. [digest] is accepted only in
 * canonical lower-case SHA-256 form. The API does not derive, normalize, or replace the host's
 * digest. This value is not authoritative evidence or a permission: a trusted runtime must resolve
 * the subject in the authenticated tenant and compare every field against host-owned state.
 */
class WorkflowSubjectSnapshot private constructor(
    val ref: WorkflowSubjectRef,
    revision: String,
    digest: String,
) {
    val revision: String = WorkflowContractSupport.requireText(
        revision,
        WorkflowContractSupport.MAX_SUBJECT_REVISION_UTF8_BYTES,
        "Workflow subject revision is invalid.",
    )
    val digest: String = WorkflowContractSupport.requireCanonicalSha256(
        digest,
        "Workflow subject digest must be a canonical lower-case SHA-256 digest.",
    )

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowSubjectSnapshot &&
            ref == other.ref &&
            revision == other.revision &&
            digest == other.digest

    override fun hashCode(): Int {
        var result = ref.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + digest.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowSubjectSnapshot(<redacted>)"

    companion object {
        @JvmStatic
        fun of(ref: WorkflowSubjectRef, revision: String, digest: String): WorkflowSubjectSnapshot =
            WorkflowSubjectSnapshot(ref, revision, digest)
    }
}
