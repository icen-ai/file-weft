package ai.icen.fw.web.api.v1.audit

import ai.icen.fw.web.api.optionalText
import ai.icen.fw.web.api.requiredText

/** One redacted audit entry. Raw details, tenant ids, storage keys, and downstream state are absent. */
class DocumentAuditLogDto @JvmOverloads constructor(
    id: String,
    action: String,
    val createdTime: Long,
    operatorId: String? = null,
    operatorName: String? = null,
    traceId: String? = null,
) {
    val id: String = requiredText(id, "Document audit-log id", 64)
    val action: String = requiredText(action, "Document audit-log action", 128)
    val operatorId: String? = optionalText(operatorId, "Document audit-log operator id", 64)
    val operatorName: String? = optionalText(operatorName, "Document audit-log operator name", 256)
    val traceId: String? = optionalText(traceId, "Document audit-log trace id", 128)

    init {
        require(createdTime >= 0) { "Document audit-log creation time must not be negative." }
    }
}

class DocumentAuditLogPageQuery @JvmOverloads constructor(
    cursor: String? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    val cursor: String? = optionalText(cursor, "Document audit-log cursor", 512)

    init {
        require(limit in 1..MAX_LIMIT) { "Document audit-log limit must be between 1 and $MAX_LIMIT." }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 100
    }
}
