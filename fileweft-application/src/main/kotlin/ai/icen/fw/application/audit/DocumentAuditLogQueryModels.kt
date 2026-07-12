package ai.icen.fw.application.audit

import ai.icen.fw.core.id.Identifier
import java.util.ArrayList
import java.util.Collections

/** Stable database ordering position; HTTP adapters own opaque encoding. */
class DocumentAuditLogPageCursor(
    val createdTime: Long,
    val id: Identifier,
) {
    init {
        require(createdTime >= 0) { "Document audit-log cursor creation time must not be negative." }
        requireSafeIdentifier(id, "Document audit-log cursor id", MAX_RECORD_ID_LENGTH)
    }
}

/** Tenant and user identity are deliberately absent and come from trusted context. */
class DocumentAuditLogPageRequest @JvmOverloads constructor(
    val cursor: DocumentAuditLogPageCursor? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) {
            "Document audit-log page limit must be between 1 and $MAX_LIMIT."
        }
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 100
    }
}

/**
 * Redacted audit projection enriched only with the trace id from its mirrored
 * operation record. Raw detail JSON and tenant identity never enter this view.
 */
class DocumentAuditLogView @JvmOverloads constructor(
    val id: Identifier,
    action: String,
    val createdTime: Long,
    val operatorId: Identifier? = null,
    operatorName: String? = null,
    val traceId: Identifier? = null,
) {
    val action: String = safeRequiredText(action, "Document audit-log action", MAX_ACTION_LENGTH)
    val operatorName: String? = operatorName?.let {
        safeRequiredText(it, "Document audit-log operator name", MAX_OPERATOR_NAME_LENGTH)
    }

    init {
        requireSafeIdentifier(id, "Document audit-log id", MAX_RECORD_ID_LENGTH)
        operatorId?.let { requireSafeIdentifier(it, "Document audit-log operator id", MAX_OPERATOR_ID_LENGTH) }
        traceId?.let { requireSafeIdentifier(it, "Document audit-log trace id", MAX_TRACE_ID_LENGTH) }
        require(createdTime >= 0) { "Document audit-log creation time must not be negative." }
    }
}

/** A visible document and one bounded, deterministic page of its audit history. */
class DocumentAuditLogPageResult @JvmOverloads constructor(
    val documentId: Identifier,
    items: List<DocumentAuditLogView>,
    val nextCursor: DocumentAuditLogPageCursor? = null,
) {
    val items: List<DocumentAuditLogView> = Collections.unmodifiableList(ArrayList(items))

    init {
        requireSafeIdentifier(documentId, "Document audit-log document id", MAX_DOCUMENT_ID_LENGTH)
        require(this.items.size <= DocumentAuditLogPageRequest.MAX_LIMIT) {
            "Document audit-log page must not contain more than ${DocumentAuditLogPageRequest.MAX_LIMIT} items."
        }
        require(this.items.map { item -> item.id }.distinct().size == this.items.size) {
            "Document audit-log page entries must have unique identifiers."
        }
        nextCursor?.let { cursor ->
            val last = this.items.lastOrNull()
                ?: throw IllegalArgumentException("A document audit-log next cursor requires at least one item.")
            require(cursor.createdTime == last.createdTime && cursor.id == last.id) {
                "Document audit-log next cursor must identify the last returned item."
            }
        }
    }
}

private fun safeRequiredText(value: String, label: String, maximumLength: Int): String {
    require(value.isNotBlank()) { "$label must not be blank." }
    require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters." }
    require(value.none(::isUnsafeCharacter)) { "$label must not contain unsafe characters." }
    return value
}

private fun requireSafeIdentifier(identifier: Identifier, label: String, maximumLength: Int) {
    val value = identifier.value
    require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters." }
    require(value.none(::isUnsafeCharacter)) { "$label must not contain unsafe characters." }
}

private fun isUnsafeCharacter(character: Char): Boolean =
    Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()

private const val MAX_RECORD_ID_LENGTH = 64
private const val MAX_DOCUMENT_ID_LENGTH = 128
private const val MAX_ACTION_LENGTH = 128
private const val MAX_OPERATOR_ID_LENGTH = 64
private const val MAX_OPERATOR_NAME_LENGTH = 256
private const val MAX_TRACE_ID_LENGTH = 128
