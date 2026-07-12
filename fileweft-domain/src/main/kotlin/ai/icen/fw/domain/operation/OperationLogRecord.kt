package ai.icen.fw.domain.operation

import ai.icen.fw.core.id.Identifier
import java.util.Collections
import java.util.LinkedHashMap

/** Immutable operational evidence correlated with a business audit event. */
class OperationLogRecord(
    val id: Identifier,
    val tenantId: Identifier,
    val resourceType: String,
    val resourceId: Identifier,
    val action: String,
    val operatorId: Identifier? = null,
    val operatorName: String? = null,
    val traceId: Identifier? = null,
    details: Map<String, String> = emptyMap(),
    val createdAt: Long,
) {
    val details: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(details))

    init {
        require(resourceType.isNotBlank()) { "Operation resource type must not be blank." }
        require(action.isNotBlank()) { "Operation action must not be blank." }
        require(operatorName == null || operatorName.isNotBlank()) { "Operation operator name must not be blank when provided." }
        require(createdAt >= 0) { "Operation creation time must not be negative." }
    }
}
