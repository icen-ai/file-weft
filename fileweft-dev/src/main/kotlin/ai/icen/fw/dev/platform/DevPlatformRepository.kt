package ai.icen.fw.dev.platform

import org.springframework.jdbc.core.JdbcTemplate

data class DevPlatformDocument(
    val id: String,
    val targetId: String,
    val tenantId: String,
    val documentId: String,
    val externalId: String,
    val fileName: String,
    val contentType: String?,
    val contentHash: String?,
    val downloadUri: String,
    val downloadedBytes: Long,
    val lastIdempotencyKey: String,
    val createdTime: Long,
    val updatedTime: Long,
)

class DevPlatformRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun save(document: DevPlatformDocument) {
        jdbcTemplate.update(
            """
            INSERT INTO fw_dev_platform_document(
                id, target_id, tenant_id, document_id, external_id, file_name, content_type, content_hash, download_uri,
                downloaded_bytes, last_idempotency_key, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, document_id, target_id) DO UPDATE SET
                external_id = EXCLUDED.external_id,
                file_name = EXCLUDED.file_name,
                content_type = EXCLUDED.content_type,
                content_hash = EXCLUDED.content_hash,
                download_uri = EXCLUDED.download_uri,
                downloaded_bytes = EXCLUDED.downloaded_bytes,
                last_idempotency_key = EXCLUDED.last_idempotency_key,
                updated_time = EXCLUDED.updated_time
            """.trimIndent(),
            document.id, document.targetId, document.tenantId, document.documentId, document.externalId, document.fileName,
            document.contentType, document.contentHash, document.downloadUri, document.downloadedBytes,
            document.lastIdempotencyKey, document.createdTime, document.updatedTime,
        )
    }

    fun delete(targetId: String, tenantId: String, documentId: String): Boolean = jdbcTemplate.update(
        "DELETE FROM fw_dev_platform_document WHERE target_id = ? AND tenant_id = ? AND document_id = ?",
        targetId,
        tenantId,
        documentId,
    ) > 0

    fun find(targetId: String, tenantId: String, documentId: String): DevPlatformDocument? = jdbcTemplate.query(
        """
        SELECT id, target_id, tenant_id, document_id, external_id, file_name, content_type, content_hash, download_uri,
               downloaded_bytes, last_idempotency_key, created_time, updated_time
        FROM fw_dev_platform_document WHERE target_id = ? AND tenant_id = ? AND document_id = ?
        """.trimIndent(),
        { result, _ ->
            DevPlatformDocument(
                result.getString("id"), result.getString("target_id"), result.getString("tenant_id"), result.getString("document_id"),
                result.getString("external_id"), result.getString("file_name"), result.getString("content_type"),
                result.getString("content_hash"), result.getString("download_uri"), result.getLong("downloaded_bytes"),
                result.getString("last_idempotency_key"), result.getLong("created_time"), result.getLong("updated_time"),
            )
        },
        targetId, tenantId, documentId,
    ).firstOrNull()

    fun findAll(targetId: String?, tenantId: String?): List<DevPlatformDocument> {
        val sql = buildString {
            append("SELECT id, target_id, tenant_id, document_id, external_id, file_name, content_type, content_hash, download_uri, downloaded_bytes, last_idempotency_key, created_time, updated_time FROM fw_dev_platform_document")
            if (targetId != null || tenantId != null) append(" WHERE ")
            if (targetId != null) append("target_id = ?")
            if (targetId != null && tenantId != null) append(" AND ")
            if (tenantId != null) append("tenant_id = ?")
            append(" ORDER BY updated_time DESC")
        }
        val arguments = listOfNotNull(targetId, tenantId).toTypedArray()
        return jdbcTemplate.query(sql, MAPPER, *arguments)
    }

    private companion object {
        val MAPPER = org.springframework.jdbc.core.RowMapper<DevPlatformDocument> { result, _ ->
            DevPlatformDocument(
                result.getString("id"), result.getString("target_id"), result.getString("tenant_id"), result.getString("document_id"),
                result.getString("external_id"), result.getString("file_name"), result.getString("content_type"),
                result.getString("content_hash"), result.getString("download_uri"), result.getLong("downloaded_bytes"),
                result.getString("last_idempotency_key"), result.getLong("created_time"), result.getLong("updated_time"),
            )
        }
    }
}
