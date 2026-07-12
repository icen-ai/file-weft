package ai.icen.fw.spi.authorization

interface AuthorizationProvider {
    fun authorize(request: AuthorizationRequest): AuthorizationDecision
}
