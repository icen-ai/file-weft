package ai.icen.fw.testkit.workflow

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider
import ai.icen.fw.spi.workflow.DocumentReviewRouteRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

abstract class DocumentReviewRouteProviderContractTest {
    protected abstract val routeProvider: DocumentReviewRouteProvider

    protected abstract fun routeRequest(): DocumentReviewRouteRequest

    @Test
    fun `reports a non-blank stable id`() {
        assertTrue(routeProvider.id().isNotBlank(), "Review route provider id must not be blank.")
    }

    @Test
    fun `resolves a route with at least one task`() {
        val route = routeProvider.resolve(routeRequest())

        assertTrue(route.workflowType.isNotBlank(), "Resolved workflow type must not be blank.")
        assertTrue(route.tasks.isNotEmpty(), "Resolved route must contain at least one task.")
        assertEquals(
            route.tasks.size,
            route.tasks.distinct().size,
            "Resolved route tasks must not be duplicated.",
        )
    }
}
