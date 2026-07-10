package com.fileweft.spi.authorization

interface AuthorizationProvider {
    fun authorize(request: AuthorizationRequest): AuthorizationDecision
}
