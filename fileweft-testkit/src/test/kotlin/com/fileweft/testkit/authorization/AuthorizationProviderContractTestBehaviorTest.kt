package com.fileweft.testkit.authorization

import com.fileweft.core.id.Identifier
import com.fileweft.spi.authorization.AuthorizationAction
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.authorization.AuthorizationResource
import com.fileweft.spi.authorization.AuthorizationSubject

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
