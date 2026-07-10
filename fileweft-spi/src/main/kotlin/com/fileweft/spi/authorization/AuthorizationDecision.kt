package com.fileweft.spi.authorization

data class AuthorizationDecision(
    val allowed: Boolean,
    val reason: String? = null,
)
