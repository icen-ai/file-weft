package ai.icen.fw.web.runtime.v1.audit

import ai.icen.fw.application.audit.DocumentAuditLogPageRequest
import ai.icen.fw.application.audit.DocumentAuditLogQueryService
import ai.icen.fw.application.audit.DocumentAuditLogView
import ai.icen.fw.web.api.ApiPage
import ai.icen.fw.web.api.v1.audit.DocumentAuditLogDto
import ai.icen.fw.web.api.v1.audit.DocumentAuditLogPageQuery
import ai.icen.fw.web.runtime.v1.V1FeatureUnavailableException
import ai.icen.fw.web.runtime.v1.document.DocumentApiInputs

class DocumentAuditLogApiFacade private constructor(
    private val auditLogs: DocumentAuditLogQueryService?,
    @Suppress("UNUSED_PARAMETER") resolved: Boolean,
) {
    constructor(auditLogs: DocumentAuditLogQueryService) : this(auditLogs, true)

    constructor(candidates: List<DocumentAuditLogQueryService>) : this(
        candidates.also {
            require(it.size <= 1) { "Formal document audit-log API has multiple query-service candidates." }
        }.singleOrNull(),
        true,
    )

    private val cursorCodec = DocumentAuditLogCursorCodec()

    fun page(documentId: String, query: DocumentAuditLogPageQuery): ApiPage<DocumentAuditLogDto> {
        val requestedDocumentId = DocumentApiInputs.documentId(documentId)
        val service = auditLogs ?: throw V1FeatureUnavailableException()
        val result = service.page(
            requestedDocumentId,
            DocumentAuditLogPageRequest(query.cursor?.let(cursorCodec::decode), query.limit),
        )
        check(result.documentId == requestedDocumentId) {
            "Document audit-log service returned a page outside the requested document."
        }
        return ApiPage(
            items = result.items.map(::toDto),
            nextCursor = result.nextCursor?.let(cursorCodec::encode),
        )
    }

    private fun toDto(log: DocumentAuditLogView): DocumentAuditLogDto = DocumentAuditLogDto(
        id = log.id.value,
        action = log.action,
        createdTime = log.createdTime,
        operatorId = log.operatorId?.value,
        operatorName = log.operatorName,
        traceId = log.traceId?.value,
    )
}
