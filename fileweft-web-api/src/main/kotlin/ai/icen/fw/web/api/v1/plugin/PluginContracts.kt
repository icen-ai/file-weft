package ai.icen.fw.web.api.v1.plugin

import ai.icen.fw.web.api.immutableList
import ai.icen.fw.web.api.optionalText
import ai.icen.fw.web.api.requiredText

class PluginCapabilityDto(
    type: String,
    val count: Int,
) {
    val type: String = requiredText(type, "Plugin capability type", 64)

    init {
        require(count > 0) { "Plugin capability count must be positive." }
    }
}

/** Safe process-wide plugin projection; implementation and configuration details are deliberately absent. */
class PluginDto(
    id: String,
    capabilities: List<PluginCapabilityDto>,
) {
    val id: String = requiredText(id, "Plugin id", 128).also(::requireSafePluginId)
    val capabilities: List<PluginCapabilityDto> = immutableList(capabilities)

    init {
        require(this.capabilities.map { capability -> capability.type }.distinct().size == this.capabilities.size) {
            "Plugin capability types must be unique."
        }
    }
}

private fun requireSafePluginId(value: String) {
    require(!isPluginIdBoundaryWhitespace(value.first()) && !isPluginIdBoundaryWhitespace(value.last())) {
        "Plugin id must not have surrounding whitespace."
    }
    require(value.none(::isUnsafePluginIdCharacter)) { "Plugin id contains an unsafe character." }
}

private fun isUnsafePluginIdCharacter(character: Char): Boolean =
    Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()

private fun isPluginIdBoundaryWhitespace(character: Char): Boolean =
    Character.isWhitespace(character) || Character.isSpaceChar(character)

class PluginPageQuery @JvmOverloads constructor(
    cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    val cursor: String? = optionalText(cursor, "Plugin inventory cursor", 512)

    init {
        require(limit in 1..MAX_LIMIT) { "Plugin inventory limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 100
    }
}
