package com.fileweft.dev.platform

import org.springframework.jdbc.core.JdbcTemplate

data class DevPlatformDocument(
    val id: String,
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
                id, tenant_id, document_id, external_id, file_name, content_type, content_hash, download_uri,
                downloaded_bytes, last_idempotency_key, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, document_id) DO UPDATE SET
                external_id = EXCLUDED.external_id,
                file_name = EXCLUDED.file_name,
                content_type = EXCLUDED.content_type,
                content_hash = EXCLUDED.content_hash,
                download_uri = EXCLUDED.download_uri,
                downloaded_bytes = EXCLUDED.downloaded_bytes,
                last_idempotency_key = EXCLUDED.last_idempotency_key,
                updated_time = EXCLUDED.updated_time
            """.trimIndent(),
            document.id, document.tenantId, document.documentId, document.externalId, document.fileName,
            document.contentType, document.contentHash, document.downloadUri, document.downloadedBytes,
            document.lastIdempotencyKey, document.createdTime, document.updatedTime,
        )
    }

    fun delete(tenantId: String, documentId: String): Boolean = jdbcTemplate.update(
        "DELETE FROM fw_dev_platform_document WHERE tenant_id = ? AND document_id = ?",
        tenantId,
        documentId,
    ) > 0

    fun find(tenantId: String, documentId: String): DevPlatformDocument? = jdbcTemplate.query(
        """
        SELECT id, tenant_id, document_id, external_id, file_name, content_type, content_hash, download_uri,
               downloaded_bytes, last_idempotency_key, created_time, updated_time
        FROM fw_dev_platform_document WHERE tenant_id = ? AND document_id = ?
        """.trimIndent(),
        { result, _ ->
            DevPlatformDocument(
                result.getString("id"), result.getString("tenant_id"), result.getString("document_id"),
                result.getString("external_id"), result.getString("file_name"), result.getString("content_type"),
                result.getString("content_hash"), result.getString("download_uri"), result.getLong("downloaded_bytes"),
                result.getString("last_idempotency_key"), result.getLong("created_time"), result.getLong("updated_time"),
            )
        },
        tenantId,
        documentId,
    ).firstOrNull()

    fun findAll(tenantId: String?): List<DevPlatformDocument> {
        val sql = if (tenantId == null) {
            "SELECT id, tenant_id, document_id, external_id, file_name, content_type, content_hash, download_uri, downloaded_bytes, last_idempotency_key, created_time, updated_time FROM fw_dev_platform_document ORDER BY updated_time DESC"
        } else {
            "SELECT id, tenant_id, document_id, external_id, file_name, content_type, content_hash, download_uri, downloaded_bytes, last_idempotency_key, created_time, updated_time FROM fw_dev_platform_document WHERE tenant_id = ? ORDER BY updated_time DESC"
        }
        return if (tenantId == null) jdbcTemplate.query(sql, MAPPER) else jdbcTemplate.query(sql, MAPPER, tenantId)
    }

    private companion object {
        val MAPPER = org.springframework.jdbc.core.RowMapper<DevPlatformDocument> { result, _ ->
            DevPlatformDocument(
                result.getString("id"), result.getString("tenant_id"), result.getString("document_id"),
                result.getString("external_id"), result.getString("file_name"), result.getString("content_type"),
                result.getString("content_hash"), result.getString("download_uri"), result.getLong("downloaded_bytes"),
                result.getString("last_idempotency_key"), result.getLong("created_time"), result.getLong("updated_time"),
            )
        }
    }
}
