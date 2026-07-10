package com.fileweft.spi.identity

import com.fileweft.core.id.Identifier

data class UserIdentity(
    val id: Identifier,
    val displayName: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)
