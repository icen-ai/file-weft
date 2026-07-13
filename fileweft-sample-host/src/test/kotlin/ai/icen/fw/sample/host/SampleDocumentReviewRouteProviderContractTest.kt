package ai.icen.fw.sample.host

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.workflow.DocumentReviewRouteRequest
import ai.icen.fw.testkit.workflow.DocumentReviewRouteProviderContractTest

class SampleDocumentReviewRouteProviderContractTest : DocumentReviewRouteProviderContractTest() {

    override val routeProvider = SampleDocumentReviewRouteProvider()

    override fun routeRequest(): DocumentReviewRouteRequest {
        return DocumentReviewRouteRequest(
            tenantId = Identifier("sample-tenant"),
            documentId = Identifier("sample-doc"),
            documentNumber = "DOC-001",
            documentTitle = "Sample Document",
            requestedReviewerId = Identifier("sample-reviewer"),
        )
    }
}
