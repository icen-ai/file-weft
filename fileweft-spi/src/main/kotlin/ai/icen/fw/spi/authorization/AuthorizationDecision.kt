package ai.icen.fw.spi.authorization

data class AuthorizationDecision(
    val allowed: Boolean,
    val reason: String? = null,
)
