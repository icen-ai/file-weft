package ai.icen.fw.sample.host

import ai.icen.fw.spi.workflow.DocumentReviewRoute
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider
import ai.icen.fw.spi.workflow.DocumentReviewRouteRequest
import ai.icen.fw.spi.workflow.DocumentReviewRouteTask

/**
 * Sample host review route provider that returns a single-assignee parallel
 * route for every document.
 */
class SampleDocumentReviewRouteProvider : DocumentReviewRouteProvider {

    override fun id(): String = "sample-review-route"

    override fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute {
        return DocumentReviewRoute(
            workflowType = "sample-parallel-review",
            tasks = listOf(
                DocumentReviewRouteTask(assigneeId = request.requestedReviewerId),
            ),
        )
    }
}
