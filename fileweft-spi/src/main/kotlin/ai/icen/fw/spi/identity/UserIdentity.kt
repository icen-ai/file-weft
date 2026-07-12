package ai.icen.fw.spi.identity

import ai.icen.fw.core.id.Identifier

data class UserIdentity(
    val id: Identifier,
    val displayName: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)
