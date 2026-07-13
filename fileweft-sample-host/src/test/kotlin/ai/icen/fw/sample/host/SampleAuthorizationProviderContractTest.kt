package ai.icen.fw.sample.host

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationAction
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.authorization.AuthorizationResource
import ai.icen.fw.spi.authorization.AuthorizationSubject
import ai.icen.fw.testkit.authorization.AuthorizationProviderContractTest

class SampleAuthorizationProviderContractTest : AuthorizationProviderContractTest() {

    override val authorizationProvider = SampleAuthorizationProvider(
        allowedActions = setOf("document:read"),
    )

    override fun allowedRequest(): AuthorizationRequest = requestForAction("document:read")

    override fun deniedRequest(): AuthorizationRequest = requestForAction("document:delete")

    private fun requestForAction(action: String): AuthorizationRequest {
        val tenantId = Identifier("sample-tenant")
        return AuthorizationRequest(
            subject = AuthorizationSubject(id = Identifier("sample-user"), type = "user"),
            resource = AuthorizationResource(
                id = Identifier("sample-doc"),
                type = "document",
                tenantId = tenantId,
            ),
            action = AuthorizationAction(name = action),
        )
    }
}
