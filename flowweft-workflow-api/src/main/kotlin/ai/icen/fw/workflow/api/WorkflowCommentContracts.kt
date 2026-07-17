package ai.icen.fw.workflow.api

import java.nio.charset.StandardCharsets

/** Closed token kinds keep arbitrary HTML, URLs, attributes and executable nodes out of comments. */
class WorkflowCommentTokenKind private constructor(code: String) {
    val code: String = WorkflowContractSupport.requireMachineCode(code, "Workflow comment token kind is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowCommentTokenKind && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowCommentTokenKind(<redacted>)"

    companion object {
        @JvmField val TEXT = WorkflowCommentTokenKind("text")
        @JvmField val MENTION = WorkflowCommentTokenKind("mention")
    }
}

/**
 * One inert comment AST token. Text must be rendered as a text node. Mention identity is always
 * [principal]; [displayNameSnapshot] is an untrusted historical label and never an identifier.
 */
class WorkflowCommentToken private constructor(
    val kind: WorkflowCommentTokenKind,
    text: String?,
    val principal: WorkflowPrincipalRef?,
    displayNameSnapshot: String?,
) {
    val text: String? = text?.let { validatePlainText(it) }
    val displayNameSnapshot: String? = displayNameSnapshot?.let {
        WorkflowContractSupport.requireText(
            it,
            WorkflowContractSupport.MAX_TITLE_UTF8_BYTES,
            "Workflow mention display-name snapshot is invalid.",
        )
    }
    val tokenDigest: String

    init {
        require(kind == WorkflowCommentTokenKind.TEXT || kind == WorkflowCommentTokenKind.MENTION) {
            "Unknown workflow comment token kinds fail closed."
        }
        require(
            (kind == WorkflowCommentTokenKind.TEXT && this.text != null && principal == null &&
                this.displayNameSnapshot == null) ||
                (kind == WorkflowCommentTokenKind.MENTION && this.text == null && principal != null &&
                    this.displayNameSnapshot != null),
        ) { "Workflow comment token content does not match its kind." }
        tokenDigest = WorkflowContractSupport.digest("flowweft-workflow-comment-token-v1")
            .text(kind.code)
            .optionalText(this.text)
            .optionalText(principal?.type)
            .optionalText(principal?.id)
            .optionalText(this.displayNameSnapshot)
            .finish()
    }

    override fun toString(): String = "WorkflowCommentToken(<redacted>)"

    companion object {
        @JvmStatic
        fun text(value: String): WorkflowCommentToken =
            WorkflowCommentToken(WorkflowCommentTokenKind.TEXT, value, null, null)

        @JvmStatic
        fun mention(
            principal: WorkflowPrincipalRef,
            displayNameSnapshot: String,
        ): WorkflowCommentToken = WorkflowCommentToken(
            WorkflowCommentTokenKind.MENTION,
            null,
            principal,
            displayNameSnapshot,
        )

        private fun validatePlainText(value: String): String {
            require(value.isNotEmpty()) { "Workflow comment text is invalid." }
            // Sentinels let individual AST text nodes preserve significant boundary whitespace
            // while reusing the repository's fixed, JDK-stable Unicode safety profile.
            WorkflowContractSupport.requireMultilineText(
                "x${value}x",
                WorkflowContractSupport.MAX_DESCRIPTION_UTF8_BYTES + 2,
                "Workflow comment text is invalid.",
            )
            return value
        }
    }
}

/** Versioned, immutable structured comment document. It has no raw-HTML representation. */
class WorkflowCommentDocument private constructor(
    val schemaVersion: Int,
    tokens: Collection<WorkflowCommentToken>,
) {
    val tokens: List<WorkflowCommentToken> = WorkflowContractSupport.immutableList(
        tokens,
        MAX_TOKENS,
        "Workflow comment tokens exceed the limit.",
    )
    val mentionedPrincipals: List<WorkflowPrincipalRef>
    val contentSizeBytes: Int
    val documentDigest: String

    init {
        require(schemaVersion == VERSION_1) { "Unknown workflow comment schema versions fail closed." }
        require(this.tokens.isNotEmpty()) { "Workflow comments require at least one token." }
        val mentions = ArrayList<WorkflowPrincipalRef>()
        var aggregateBytes = 0
        this.tokens.forEach { token ->
            aggregateBytes += token.text?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0
            aggregateBytes += token.principal?.type?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0
            aggregateBytes += token.principal?.id?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0
            aggregateBytes += token.displayNameSnapshot?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0
            require(aggregateBytes <= MAX_CONTENT_UTF8_BYTES) {
                "Workflow comment content exceeds the aggregate UTF-8 byte limit."
            }
            token.principal?.let { if (!mentions.contains(it)) mentions.add(it) }
        }
        contentSizeBytes = aggregateBytes
        mentionedPrincipals = java.util.Collections.unmodifiableList(mentions)
        documentDigest = WorkflowContractSupport.digest("flowweft-workflow-comment-document-v1")
            .integer(schemaVersion)
            .integer(this.tokens.size)
            .also { writer -> this.tokens.forEach { writer.text(it.tokenDigest) } }
            .finish()
    }

    override fun toString(): String = "WorkflowCommentDocument(<redacted>)"

    companion object {
        const val VERSION_1: Int = 1
        const val MAX_TOKENS: Int = 256
        const val MAX_CONTENT_UTF8_BYTES: Int = 64 * 1024
        const val MEDIA_TYPE: String = "application/vnd.flowweft.comment-tokens+json"

        @JvmStatic
        fun of(tokens: Collection<WorkflowCommentToken>): WorkflowCommentDocument =
            WorkflowCommentDocument(VERSION_1, tokens)

        @JvmStatic
        fun restore(schemaVersion: Int, tokens: Collection<WorkflowCommentToken>): WorkflowCommentDocument =
            WorkflowCommentDocument(schemaVersion, tokens)
    }
}

/** Immutable comment version. Mentioning a principal grants no workflow or comment access. */
class WorkflowCommentSnapshot private constructor(
    commentId: String,
    val version: Long,
    val instance: WorkflowInstanceRef,
    val workItem: WorkflowWorkItemRef?,
    val author: WorkflowPrincipalRef,
    val document: WorkflowCommentDocument,
    authorAuthorizationReceiptDigest: String,
    mentionAttestationReceiptDigest: String?,
    val createdAtEpochMilli: Long,
) {
    val commentId: String = WorkflowContractSupport.requireText(
        commentId,
        WorkflowContractSupport.MAX_REFERENCE_ID_UTF8_BYTES,
        "Workflow comment identifier is invalid.",
    )
    val authorAuthorizationReceiptDigest: String = WorkflowContractSupport.requireCanonicalSha256(
        authorAuthorizationReceiptDigest,
        "Workflow comment authorization receipt digest is invalid.",
    )
    val mentionAttestationReceiptDigest: String? = mentionAttestationReceiptDigest?.let {
        WorkflowContractSupport.requireCanonicalSha256(
            it,
            "Workflow mention attestation receipt digest is invalid.",
        )
    }
    val snapshotDigest: String

    init {
        require(version >= 0L) { "Workflow comment version is invalid." }
        require(document.mentionedPrincipals.isEmpty() || this.mentionAttestationReceiptDigest != null) {
            "Workflow comment mentions require visibility attestation."
        }
        require(createdAtEpochMilli >= 0L) { "Workflow comment creation time is invalid." }
        snapshotDigest = WorkflowContractSupport.digest("flowweft-workflow-comment-snapshot-v1")
            .text(this.commentId)
            .longValue(version)
            .text(instance.id)
            .longValue(instance.expectedVersion)
            .optionalText(workItem?.id)
            .longValue(workItem?.expectedVersion ?: -1L)
            .text(author.type)
            .text(author.id)
            .text(document.documentDigest)
            .text(this.authorAuthorizationReceiptDigest)
            .optionalText(this.mentionAttestationReceiptDigest)
            .longValue(createdAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "WorkflowCommentSnapshot(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            commentId: String,
            version: Long,
            instance: WorkflowInstanceRef,
            workItem: WorkflowWorkItemRef?,
            author: WorkflowPrincipalRef,
            document: WorkflowCommentDocument,
            authorAuthorizationReceiptDigest: String,
            mentionAttestationReceiptDigest: String?,
            createdAtEpochMilli: Long,
        ): WorkflowCommentSnapshot = WorkflowCommentSnapshot(
            commentId,
            version,
            instance,
            workItem,
            author,
            document,
            authorAuthorizationReceiptDigest,
            mentionAttestationReceiptDigest,
            createdAtEpochMilli,
        )
    }
}
