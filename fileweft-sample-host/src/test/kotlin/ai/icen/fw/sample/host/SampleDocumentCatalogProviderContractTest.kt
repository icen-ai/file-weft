package ai.icen.fw.sample.host

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.testkit.catalog.DocumentCatalogProviderContractTest

class SampleDocumentCatalogProviderContractTest : DocumentCatalogProviderContractTest() {

    override val catalogProvider = SampleDocumentCatalogProvider()

    override fun tenantId(): Identifier = Identifier("sample-tenant")

    override fun userId(): Identifier = Identifier("sample-user")
}
