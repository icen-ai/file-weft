package ai.icen.fw.core.id

/**
 * Opaque, non-blank identifier shared by FlowWeft boundary contracts.
 *
 * Identifier values are intentionally not normalized: callers must preserve the
 * identifier issued by their owning system.
 */
class Identifier(value: String) {
    val value: String = value.also {
        require(it.isNotBlank()) { "Identifier value must not be blank." }
    }

    override fun equals(other: Any?): Boolean =
        other is Identifier && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}
