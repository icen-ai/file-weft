package ai.icen.fw.agent.api

/** Stable provider identifier. Providers add new values without changing FlowWeft enums. */
class ProviderId(value: String) {
    val value: String = requireStableAgentId(value, "Agent provider identifier is invalid.")

    override fun equals(other: Any?): Boolean = other is ProviderId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** Stable model identifier owned by a configured provider. */
class ModelId(value: String) {
    val value: String = requireStableAgentId(value, "Agent model identifier is invalid.")

    override fun equals(other: Any?): Boolean = other is ModelId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** Stable tool identifier. Tool schema changes are tracked separately by schema digest. */
class ToolId(value: String) {
    val value: String = requireStableAgentId(value, "Agent tool identifier is invalid.")

    override fun equals(other: Any?): Boolean = other is ToolId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** Extensible capability identifier used for discovery and negotiation. */
class AgentCapabilityId(value: String) {
    val value: String = requireStableAgentId(value, "Agent capability identifier is invalid.")

    override fun equals(other: Any?): Boolean = other is AgentCapabilityId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}
