package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import java.util.concurrent.CompletionStage

/** A visible directory suggestion. The display snapshot is never identity or authorization. */
class WorkflowMentionCandidate private constructor(
    val principal: WorkflowPrincipalRef,
    displayNameSnapshot: String,
    authorityRevision: String,
    visibilityReceiptDigest: String,
) {
    val displayNameSnapshot: String = WorkflowSpiContractSupport.requireText(
        displayNameSnapshot,
        WorkflowSpiContractSupport.MAX_TEXT_UTF8_BYTES,
        "Workflow mention display-name snapshot is invalid.",
    )
    val authorityRevision: String = WorkflowSpiContractSupport.requireText(
        authorityRevision,
        WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES,
        "Workflow mention authority revision is invalid.",
    )
    val visibilityReceiptDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        visibilityReceiptDigest,
        "Workflow mention visibility receipt digest is invalid.",
    )
    val candidateDigest: String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-mention-candidate-v1")
        .text(principal.type)
        .text(principal.id)
        .text(this.displayNameSnapshot)
        .text(this.authorityRevision)
        .text(this.visibilityReceiptDigest)
        .finish()

    override fun toString(): String = "WorkflowMentionCandidate(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            principal: WorkflowPrincipalRef,
            displayNameSnapshot: String,
            authorityRevision: String,
            visibilityReceiptDigest: String,
        ): WorkflowMentionCandidate = WorkflowMentionCandidate(
            principal,
            displayNameSnapshot,
            authorityRevision,
            visibilityReceiptDigest,
        )
    }
}

class WorkflowMentionSearchRequest private constructor(
    val context: WorkflowProviderCallContext,
    val requester: WorkflowPrincipalRef,
    authorizationReceiptDigest: String,
    query: String,
    cursor: String?,
    val pageSize: Int,
) {
    val authorizationReceiptDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        authorizationReceiptDigest,
        "Workflow mention search authorization receipt digest is invalid.",
    )
    val query: String = WorkflowSpiContractSupport.requireText(
        query,
        WorkflowSpiContractSupport.MAX_TEXT_UTF8_BYTES,
        "Workflow mention search query is invalid.",
    )
    val cursor: String? = cursor?.let {
        WorkflowSpiContractSupport.requireOpaqueReference(it, "Workflow mention search cursor is invalid.")
    }
    val requestDigest: String

    init {
        require(this.query.codePointCount(0, this.query.length) >= MINIMUM_QUERY_CODE_POINTS) {
            "Workflow mention search query is too short."
        }
        require(pageSize in 1..minOf(MAXIMUM_PAGE_SIZE, context.maximumItems)) {
            "Workflow mention search page size is invalid."
        }
        requestDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-mention-search-request-v1")
            .text(context.contextDigest)
            .text(requester.type)
            .text(requester.id)
            .text(this.authorizationReceiptDigest)
            .text(this.query)
            .optionalText(this.cursor)
            .integer(pageSize)
            .finish()
    }

    override fun toString(): String = "WorkflowMentionSearchRequest(<redacted>)"

    companion object {
        const val MINIMUM_QUERY_CODE_POINTS: Int = 2
        const val MAXIMUM_PAGE_SIZE: Int = 50

        @JvmStatic
        fun of(
            context: WorkflowProviderCallContext,
            requester: WorkflowPrincipalRef,
            authorizationReceiptDigest: String,
            query: String,
            cursor: String?,
            pageSize: Int,
        ): WorkflowMentionSearchRequest = WorkflowMentionSearchRequest(
            context,
            requester,
            authorizationReceiptDigest,
            query,
            cursor,
            pageSize,
        )
    }
}

/** Contains visible principals only. Hidden and missing principals both contribute nothing. */
class WorkflowMentionSearchPage private constructor(
    candidates: Collection<WorkflowMentionCandidate>,
    nextCursor: String?,
) {
    val candidates: List<WorkflowMentionCandidate> = WorkflowSpiContractSupport.immutableList(
        candidates,
        WorkflowMentionSearchRequest.MAXIMUM_PAGE_SIZE,
        "Workflow mention search results exceed the limit.",
    )
    val nextCursor: String? = nextCursor?.let {
        WorkflowSpiContractSupport.requireOpaqueReference(it, "Workflow mention search cursor is invalid.")
    }
    val pageDigest: String

    init {
        require(this.candidates.map { it.principal }.toSet().size == this.candidates.size) {
            "Workflow mention suggestions must contain unique principals."
        }
        pageDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-mention-search-page-v1")
            .integer(this.candidates.size)
            .also { writer -> this.candidates.forEach { writer.text(it.candidateDigest) } }
            .optionalText(this.nextCursor)
            .finish()
    }

    override fun toString(): String = "WorkflowMentionSearchPage(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            candidates: Collection<WorkflowMentionCandidate>,
            nextCursor: String?,
        ): WorkflowMentionSearchPage = WorkflowMentionSearchPage(candidates, nextCursor)

        /** Uniform successful answer for no visible matches, including hidden-only matches. */
        @JvmStatic
        fun empty(): WorkflowMentionSearchPage = WorkflowMentionSearchPage(emptyList(), null)
    }
}

class WorkflowMentionSearchResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val page: WorkflowMentionSearchPage?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (page != null)) {
            "Workflow mention search result content does not match its outcome."
        }
        require(receipt.outcome == WorkflowProviderOutcome.SUCCESS ||
            receipt.outcome == WorkflowProviderOutcome.UNAVAILABLE || receipt.outcome == WorkflowProviderOutcome.FAILED
        ) { "Mention search must not distinguish denied, hidden and missing principals." }
    }

    override fun toString(): String = "WorkflowMentionSearchResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowMentionSearchRequest,
            page: WorkflowMentionSearchPage,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowMentionSearchResult {
            require(page.candidates.size <= request.pageSize && page.candidates.size <= request.context.maximumItems) {
                "Workflow mention search page exceeds the request limit."
            }
            return WorkflowMentionSearchResult(
                WorkflowProviderReceipt.success(
                    request.context,
                    request.requestDigest,
                    page.pageDigest,
                    completedAtEpochMilli,
                    expiresAtEpochMilli,
                ),
                page,
            )
        }

        @JvmStatic
        fun unavailable(
            request: WorkflowMentionSearchRequest,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowMentionSearchResult = WorkflowMentionSearchResult(
            WorkflowProviderReceipt.failure(
                request.context,
                request.requestDigest,
                WorkflowProviderOutcome.UNAVAILABLE,
                WorkflowSpiContractSupport.failureDigest("flowweft-workflow-spi-mention-search-failure-v1", failure),
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            null,
        )
    }
}

class WorkflowMentionVisibilityRequest private constructor(
    val context: WorkflowProviderCallContext,
    val requester: WorkflowPrincipalRef,
    val mentionedPrincipal: WorkflowPrincipalRef,
    authorizationReceiptDigest: String,
) {
    val authorizationReceiptDigest: String = WorkflowSpiContractSupport.requireCanonicalSha256(
        authorizationReceiptDigest,
        "Workflow mention visibility authorization receipt digest is invalid.",
    )
    val requestDigest: String = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-mention-visibility-request-v1")
        .text(context.contextDigest)
        .text(requester.type)
        .text(requester.id)
        .text(mentionedPrincipal.type)
        .text(mentionedPrincipal.id)
        .text(this.authorizationReceiptDigest)
        .finish()

    override fun toString(): String = "WorkflowMentionVisibilityRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: WorkflowProviderCallContext,
            requester: WorkflowPrincipalRef,
            mentionedPrincipal: WorkflowPrincipalRef,
            authorizationReceiptDigest: String,
        ): WorkflowMentionVisibilityRequest = WorkflowMentionVisibilityRequest(
            context,
            requester,
            mentionedPrincipal,
            authorizationReceiptDigest,
        )
    }
}

/** Hidden and missing principals both produce [notVisible] with no candidate or reason. */
class WorkflowMentionVisibilityDecision private constructor(
    val visible: Boolean,
    val candidate: WorkflowMentionCandidate?,
    authorityRevision: String,
) {
    val authorityRevision: String = WorkflowSpiContractSupport.requireText(
        authorityRevision,
        WorkflowSpiContractSupport.MAX_REVISION_UTF8_BYTES,
        "Workflow mention authority revision is invalid.",
    )
    val decisionDigest: String

    init {
        require(visible == (candidate != null)) { "Workflow mention visibility content is inconsistent." }
        require(candidate == null || candidate.authorityRevision == this.authorityRevision) {
            "Workflow mention visibility authority revision is inconsistent."
        }
        decisionDigest = WorkflowSpiContractSupport.digest("flowweft-workflow-spi-mention-visibility-decision-v1")
            .booleanValue(visible)
            .optionalText(candidate?.candidateDigest)
            .text(this.authorityRevision)
            .finish()
    }

    override fun toString(): String = "WorkflowMentionVisibilityDecision(<redacted>)"

    companion object {
        @JvmStatic
        fun visible(candidate: WorkflowMentionCandidate): WorkflowMentionVisibilityDecision =
            WorkflowMentionVisibilityDecision(true, candidate, candidate.authorityRevision)

        @JvmStatic
        fun notVisible(authorityRevision: String): WorkflowMentionVisibilityDecision =
            WorkflowMentionVisibilityDecision(false, null, authorityRevision)
    }
}

class WorkflowMentionVisibilityResult private constructor(
    val receipt: WorkflowProviderReceipt,
    val decision: WorkflowMentionVisibilityDecision?,
) {
    init {
        require((receipt.outcome == WorkflowProviderOutcome.SUCCESS) == (decision != null)) {
            "Workflow mention visibility result content does not match its outcome."
        }
        require(receipt.outcome == WorkflowProviderOutcome.SUCCESS ||
            receipt.outcome == WorkflowProviderOutcome.UNAVAILABLE || receipt.outcome == WorkflowProviderOutcome.FAILED
        ) { "Mention visibility must not distinguish denied, hidden and missing principals." }
    }

    override fun toString(): String = "WorkflowMentionVisibilityResult(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowMentionVisibilityRequest,
            decision: WorkflowMentionVisibilityDecision,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowMentionVisibilityResult {
            require(decision.candidate == null || decision.candidate.principal == request.mentionedPrincipal) {
                "Workflow mention visibility decision does not match the requested principal."
            }
            return WorkflowMentionVisibilityResult(
                WorkflowProviderReceipt.success(
                    request.context,
                    request.requestDigest,
                    decision.decisionDigest,
                    completedAtEpochMilli,
                    expiresAtEpochMilli,
                ),
                decision,
            )
        }

        @JvmStatic
        fun unavailable(
            request: WorkflowMentionVisibilityRequest,
            failure: WorkflowProviderFailure,
            completedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): WorkflowMentionVisibilityResult = WorkflowMentionVisibilityResult(
            WorkflowProviderReceipt.failure(
                request.context,
                request.requestDigest,
                WorkflowProviderOutcome.UNAVAILABLE,
                WorkflowSpiContractSupport.failureDigest("flowweft-workflow-spi-mention-visibility-failure-v1", failure),
                failure,
                completedAtEpochMilli,
                expiresAtEpochMilli,
            ),
            null,
        )
    }
}

interface WorkflowMentionResolver {
    fun search(request: WorkflowMentionSearchRequest): CompletionStage<WorkflowMentionSearchResult>
    fun verifyVisibility(request: WorkflowMentionVisibilityRequest): CompletionStage<WorkflowMentionVisibilityResult>
}
