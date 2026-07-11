package com.fileweft.spi.workflow

import com.fileweft.core.id.Identifier
import java.util.Collections

/**
 * Resolves a review route outside FileWeft's database transaction. Implementations may consult a
 * host policy service, but must provide their own timeout, retry and diagnostics when doing so.
 */
interface DocumentReviewRouteProvider {
    /** Stable route key selected by the host or by FileWeft's configured default. */
    fun id(): String

    fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute
}

/** Immutable routing input with no domain aggregate or persistence type leakage. */
class DocumentReviewRouteRequest @JvmOverloads constructor(
    val tenantId: Identifier,
    val documentId: Identifier,
    val documentNumber: String,
    val documentTitle: String,
    val submittedBy: Identifier? = null,
    val requestedReviewerId: Identifier? = null,
) {
    init {
        require(documentNumber.isNotBlank()) { "Review route document number must not be blank." }
        require(documentTitle.isNotBlank()) { "Review route document title must not be blank." }
    }
}

/** A parallel approval route. FileWeft publishes only after every task is approved. */
class DocumentReviewRoute(
    val workflowType: String,
    tasks: List<DocumentReviewRouteTask>,
) {
    val tasks: List<DocumentReviewRouteTask> = Collections.unmodifiableList(ArrayList(tasks))

    init {
        require(workflowType.isNotBlank()) { "Review route workflow type must not be blank." }
        require(this.tasks.isNotEmpty()) { "Review route must create at least one task." }
        require(this.tasks.distinct().size == this.tasks.size) { "Review route tasks must not be duplicated." }
    }
}

/** One assignee in a route; null retains FileWeft's existing unassigned-review behavior. */
class DocumentReviewRouteTask @JvmOverloads constructor(
    val assigneeId: Identifier? = null,
) {
    override fun equals(other: Any?): Boolean = other is DocumentReviewRouteTask && assigneeId == other.assigneeId

    override fun hashCode(): Int = assigneeId?.hashCode() ?: 0
}
