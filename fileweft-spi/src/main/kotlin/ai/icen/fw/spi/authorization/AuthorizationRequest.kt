package ai.icen.fw.spi.authorization

import ai.icen.fw.core.id.Identifier

data class AuthorizationSubject(
    val id: Identifier,
    val type: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(type.isNotBlank()) { "Subject type must not be blank." }
    }
}

data class AuthorizationResource(
    val id: Identifier,
    val type: String,
    val tenantId: Identifier,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(type.isNotBlank()) { "Resource type must not be blank." }
    }
}

data class AuthorizationAction(
    val name: String,
) {
    init {
        require(name.isNotBlank()) { "Action name must not be blank." }
    }
}

data class AuthorizationEnvironment(
    val attributes: Map<String, String> = emptyMap(),
)

data class AuthorizationRequest(
    val subject: AuthorizationSubject,
    val resource: AuthorizationResource,
    val action: AuthorizationAction,
    val environment: AuthorizationEnvironment = AuthorizationEnvironment(),
)
