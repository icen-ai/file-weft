package ai.icen.fw.web.runtime.v1.plugin

import ai.icen.fw.application.plugin.PluginInventoryDescriptor
import ai.icen.fw.application.plugin.PluginInventoryPageRequest
import ai.icen.fw.application.plugin.PluginInventoryQueryService
import ai.icen.fw.web.api.ApiPage
import ai.icen.fw.web.api.v1.plugin.PluginCapabilityDto
import ai.icen.fw.web.api.v1.plugin.PluginDto
import ai.icen.fw.web.api.v1.plugin.PluginPageQuery
import ai.icen.fw.web.runtime.v1.V1FeatureUnavailableException

class PluginInventoryApiFacade private constructor(
    private val plugins: PluginInventoryQueryService?,
    @Suppress("UNUSED_PARAMETER") resolved: Boolean,
) {
    constructor(plugins: PluginInventoryQueryService) : this(plugins, true)

    constructor(candidates: List<PluginInventoryQueryService>) : this(
        candidates.also {
            require(it.size <= 1) { "Formal plugin inventory API has multiple query-service candidates." }
        }.singleOrNull(),
        true,
    )

    private val cursorCodec = PluginInventoryCursorCodec()

    fun page(query: PluginPageQuery): ApiPage<PluginDto> {
        val service = plugins ?: throw V1FeatureUnavailableException()
        val result = service.page(
            PluginInventoryPageRequest(
                cursor = query.cursor?.let(cursorCodec::decode),
                limit = query.limit,
            ),
        )
        return ApiPage(
            items = result.items.map(::toDto),
            nextCursor = result.nextCursor?.let(cursorCodec::encode),
        )
    }

    private fun toDto(plugin: PluginInventoryDescriptor): PluginDto = PluginDto(
        id = plugin.id,
        capabilities = plugin.capabilities.map { capability ->
            PluginCapabilityDto(capability.type.name, capability.count)
        },
    )
}
