package ai.icen.fw.testkit.authorization

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationAction
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.authorization.AuthorizationResource
import ai.icen.fw.spi.authorization.AuthorizationSubject

/** Exercises the reusable authorization contract with both permit and deny decisions. */
class AuthorizationProviderContractTestBehaviorTest : AuthorizationProviderContractTest() {
    override val authorizationProvider: AuthorizationProvider = object : AuthorizationProvider {
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision =
            AuthorizationDecision(request.action.name == "document:read", "Action is not granted.")
    }

    override fun allowedRequest(): AuthorizationRequest = request("document:read")

    override fun deniedRequest(): AuthorizationRequest = request("document:delete")

    private fun request(action: String): AuthorizationRequest = AuthorizationRequest(
        subject = AuthorizationSubject(Identifier("user-contract"), "USER"),
        resource = AuthorizationResource(Identifier("document-contract"), "DOCUMENT", Identifier("tenant-contract")),
        action = AuthorizationAction(action),
    )
}
