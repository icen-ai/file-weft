package ai.icen.fw.application.workflow

import ai.icen.fw.spi.workflow.DocumentReviewRoute
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider
import ai.icen.fw.spi.workflow.DocumentReviewRouteRequest
import ai.icen.fw.spi.workflow.DocumentReviewRouteTask
import java.util.Collections
import java.util.LinkedHashMap

/** Default route retains FlowWeft's original one-reviewer-or-unassigned behavior. */
object DefaultDocumentReviewRouteProvider : DocumentReviewRouteProvider {
    const val ID = "default"
    const val WORKFLOW_TYPE = "DOCUMENT_REVIEW"

    override fun id(): String = ID

    override fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute = DocumentReviewRoute(
        workflowType = WORKFLOW_TYPE,
        tasks = listOf(DocumentReviewRouteTask(request.requestedReviewerId)),
    )
}

/** Selects one deterministic route provider and keeps plugin/Spring identifier conflicts visible at startup. */
class DocumentReviewRouteResolver @JvmOverloads constructor(
    providers: List<DocumentReviewRouteProvider> = listOf(DefaultDocumentReviewRouteProvider),
    private val defaultRouteId: String = DefaultDocumentReviewRouteProvider.ID,
) {
    private val providersById: Map<String, DocumentReviewRouteProvider>

    init {
        require(defaultRouteId.isNotBlank()) { "Default review route id must not be blank." }
        val resolved = LinkedHashMap<String, DocumentReviewRouteProvider>()
        providers.forEach { provider ->
            val id = provider.id()
            require(id.isNotBlank()) { "Document review route provider id must not be blank." }
            require(resolved.putIfAbsent(id, provider) == null) {
                "Document review route provider id $id is contributed more than once."
            }
        }
        require(resolved.containsKey(defaultRouteId)) {
            "Configured default review route $defaultRouteId is not available."
        }
        providersById = Collections.unmodifiableMap(resolved)
    }

    fun routeIds(): Set<String> = Collections.unmodifiableSet(LinkedHashSet(providersById.keys))

    fun resolve(requestedRouteId: String?, request: DocumentReviewRouteRequest): ResolvedDocumentReviewRoute {
        val routeId = requestedRouteId?.takeIf { it.isNotBlank() } ?: defaultRouteId
        val provider = providersById[routeId]
            ?: throw NoSuchElementException("Document review route $routeId is not available.")
        return ResolvedDocumentReviewRoute(routeId, provider.resolve(request))
    }
}

class ResolvedDocumentReviewRoute(
    val routeId: String,
    val route: DocumentReviewRoute,
) {
    init {
        require(routeId.isNotBlank()) { "Resolved review route id must not be blank." }
    }
}
