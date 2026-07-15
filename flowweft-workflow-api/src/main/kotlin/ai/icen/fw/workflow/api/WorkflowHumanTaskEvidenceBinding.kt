package ai.icen.fw.workflow.api

/** Exact immutable form and rule revisions used by one human-task definition. */
class WorkflowHumanTaskEvidenceBinding private constructor(
    formKey: String,
    formVersion: String,
    formDigest: String,
    ruleKey: String,
    ruleVersion: String,
    ruleDigest: String,
) {
    val formKey: String = text(formKey, "form key")
    val formVersion: String = text(formVersion, "form version")
    val formDigest: String = sha(formDigest, "form")
    val ruleKey: String = text(ruleKey, "rule key")
    val ruleVersion: String = text(ruleVersion, "rule version")
    val ruleDigest: String = sha(ruleDigest, "rule")
    val isBuiltinNone: Boolean = this.formKey == "builtin-none" && this.formVersion == "1" &&
        this.formDigest == NONE_DIGEST && this.ruleKey == "builtin-none" &&
        this.ruleVersion == "1" && this.ruleDigest == NONE_DIGEST
    val contentDigest: String = WorkflowContractSupport.digest(
        "flowweft-workflow-human-task-evidence-binding-v1",
    )
        .text(this.formKey)
        .text(this.formVersion)
        .text(this.formDigest)
        .text(this.ruleKey)
        .text(this.ruleVersion)
        .text(this.ruleDigest)
        .finish()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowHumanTaskEvidenceBinding &&
            formKey == other.formKey &&
            formVersion == other.formVersion &&
            formDigest == other.formDigest &&
            ruleKey == other.ruleKey &&
            ruleVersion == other.ruleVersion &&
            ruleDigest == other.ruleDigest

    override fun hashCode(): Int {
        var result = formKey.hashCode()
        result = 31 * result + formVersion.hashCode()
        result = 31 * result + formDigest.hashCode()
        result = 31 * result + ruleKey.hashCode()
        result = 31 * result + ruleVersion.hashCode()
        result = 31 * result + ruleDigest.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowHumanTaskEvidenceBinding(<redacted>)"

    companion object {
        private val NONE_DIGEST: String = WorkflowContractSupport.digest(
            "flowweft-workflow-human-task-no-external-evidence-v1",
        ).finish()

        /** Explicit built-in binding for tasks that have no external form or rule provider. */
        @JvmStatic
        fun none(): WorkflowHumanTaskEvidenceBinding = WorkflowHumanTaskEvidenceBinding(
            "builtin-none",
            "1",
            NONE_DIGEST,
            "builtin-none",
            "1",
            NONE_DIGEST,
        )

        @JvmStatic
        fun of(
            formKey: String,
            formVersion: String,
            formDigest: String,
            ruleKey: String,
            ruleVersion: String,
            ruleDigest: String,
        ): WorkflowHumanTaskEvidenceBinding = WorkflowHumanTaskEvidenceBinding(
            formKey,
            formVersion,
            formDigest,
            ruleKey,
            ruleVersion,
            ruleDigest,
        )

        private fun text(value: String, label: String): String = WorkflowContractSupport.requireText(
            value,
            WorkflowContractSupport.MAX_REFERENCE_ID_UTF8_BYTES,
            "Workflow human-task $label is invalid.",
        )

        private fun sha(value: String, label: String): String = WorkflowContractSupport.requireCanonicalSha256(
            value,
            "Workflow human-task $label digest is invalid.",
        )
    }
}
