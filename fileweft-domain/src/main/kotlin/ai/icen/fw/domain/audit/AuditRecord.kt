package ai.icen.fw.domain.audit

import ai.icen.fw.core.id.Identifier
import java.util.Collections
import java.util.LinkedHashMap

/** Immutable, append-only evidence of a business or system operation. */
class AuditRecord(
    val id: Identifier,
    val tenantId: Identifier,
    val resourceType: String,
    val resourceId: Identifier,
    val action: String,
    val operatorId: Identifier? = null,
    details: Map<String, String> = emptyMap(),
    val createdAt: Long,
    operatorName: String? = null,
) {
    val details: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(details))
    /** Immutable display-name snapshot supplied by the owning user realm. */
    val operatorName: String? = operatorName?.takeIf { it.isNotBlank() }

    init {
        require(resourceType.isNotBlank()) { "Audit resource type must not be blank." }
        require(action.isNotBlank()) { "Audit action must not be blank." }
        require(createdAt >= 0) { "Audit record creation time must not be negative." }
    }
}
