package ai.icen.fw.application.plugin

import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import java.util.ArrayList
import java.util.Collections

/** Fixed contribution categories. Vendor types, bean names, and connector identifiers stay internal. */
enum class PluginCapabilityType {
    STORAGE_ADAPTER,
    CONNECTOR,
    DOCTOR_CHECKER,
    AGENT,
    AGENT_TASK_TRIGGER,
    OUTBOX_EVENT_HANDLER,
    TASK_HANDLER,
    REVIEW_ROUTE_PROVIDER,
}

class PluginCapabilityDescriptor(
    val type: PluginCapabilityType,
    val count: Int,
) {
    init {
        require(count > 0) { "Plugin capability count must be positive." }
    }
}

/** Immutable registry snapshot supplied by a runtime adapter. */
class PluginInventoryDescriptor(
    val id: String,
    capabilities: List<PluginCapabilityDescriptor>,
) {
    val capabilities: List<PluginCapabilityDescriptor> = immutableList(capabilities)

    init {
        require(this.capabilities.map { capability -> capability.type }.distinct().size == this.capabilities.size) {
            "Plugin capability types must be unique."
        }
    }
}

/** Additive runtime port; implementations must return a stable immutable snapshot. */
interface PluginInventoryProvider {
    fun inventory(): List<PluginInventoryDescriptor>
}

class PluginInventoryCursor(val pluginId: String)

class PluginInventoryPageRequest @JvmOverloads constructor(
    val cursor: PluginInventoryCursor? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "Plugin inventory limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 100
    }
}

class PluginInventoryPageResult @JvmOverloads constructor(
    items: List<PluginInventoryDescriptor>,
    val nextCursor: PluginInventoryCursor? = null,
) {
    val items: List<PluginInventoryDescriptor> = immutableList(items)

    init {
        require(this.items.size <= PluginInventoryPageRequest.MAX_LIMIT) {
            "Plugin inventory page exceeds the maximum size."
        }
    }
}

/**
 * Authorized, tenant-context-derived query boundary for the process-wide plugin inventory.
 *
 * The tenant is used only for the host authorization decision. It is never forwarded to
 * the process-wide registry or exposed in the result.
 */
class PluginInventoryQueryService(
    private val tenants: TenantProvider,
    users: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val provider: PluginInventoryProvider,
) {
    private val authorization = ApplicationAuthorization(users, authorizationProvider)

    fun page(request: PluginInventoryPageRequest): PluginInventoryPageResult {
        val tenant = tenants.currentTenant()
        authorization.requireAction(
            tenant.tenantId,
            SYSTEM_RESOURCE_ID,
            SYSTEM_RESOURCE_TYPE,
            PLUGIN_INVENTORY_ACTION,
        )

        val snapshot = provider.inventory().also(::validateSnapshot).sortedBy { descriptor -> descriptor.id }
        val remaining = request.cursor?.let { cursor ->
            validatePublicId(cursor.pluginId, "Plugin inventory cursor")
            snapshot.filter { descriptor -> descriptor.id > cursor.pluginId }
        } ?: snapshot
        val selected = remaining.take(request.limit + 1)
        val hasNext = selected.size > request.limit
        val items = if (hasNext) selected.subList(0, request.limit) else selected
        return PluginInventoryPageResult(
            items,
            if (hasNext) PluginInventoryCursor(items.last().id) else null,
        )
    }

    private fun validateSnapshot(snapshot: List<PluginInventoryDescriptor>) {
        check(snapshot.size <= MAX_PLUGIN_COUNT) { "Plugin inventory exceeds the supported process limit." }
        val ids = HashSet<String>()
        snapshot.forEach { descriptor ->
            validatePublicId(descriptor.id, "Plugin id")
            check(ids.add(descriptor.id)) { "Plugin inventory contains duplicate ids." }
            check(descriptor.capabilities.size <= PluginCapabilityType.values().size) {
                "Plugin inventory contains too many capability categories."
            }
        }
    }

    private fun validatePublicId(value: String, label: String) {
        check(value.isNotBlank()) { "$label must not be blank." }
        check(value.length <= MAX_PLUGIN_ID_LENGTH) { "$label exceeds the public length limit." }
        check(!isPluginIdBoundaryWhitespace(value.first()) && !isPluginIdBoundaryWhitespace(value.last())) {
            "$label must not have surrounding whitespace."
        }
        check(value.none(::isUnsafePluginIdCharacter)) {
            "$label contains an unsafe character."
        }
    }

    companion object {
        const val PLUGIN_INVENTORY_ACTION: String = "system:plugins:read"
        const val SYSTEM_RESOURCE_TYPE: String = "FILEWEFT_SYSTEM"
        val SYSTEM_RESOURCE_ID: Identifier = Identifier("fileweft-system")
        private const val MAX_PLUGIN_COUNT: Int = 10_000
        private const val MAX_PLUGIN_ID_LENGTH: Int = 128
    }
}

private fun isUnsafePluginIdCharacter(character: Char): Boolean =
    Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()

private fun isPluginIdBoundaryWhitespace(character: Char): Boolean =
    Character.isWhitespace(character) || Character.isSpaceChar(character)

private fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
