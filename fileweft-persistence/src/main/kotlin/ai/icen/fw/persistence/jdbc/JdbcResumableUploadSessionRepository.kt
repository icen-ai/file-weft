package ai.icen.fw.persistence.jdbc

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.upload.CompletionRejectionResettableResumableUploadSessionRepository
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaim
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimRepository
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimState
import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimStateException
import ai.icen.fw.application.upload.OwnerScopedResumableUploadSessionRepository
import ai.icen.fw.application.upload.ResumableUploadPart
import ai.icen.fw.application.upload.ResumableUploadSession
import ai.icen.fw.application.upload.ResumableUploadSessionStatus
import ai.icen.fw.application.upload.StagedResumableUploadSessionRepository
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.StorageObjectLocation
import java.sql.ResultSet

/** PostgreSQL persistence for durable multipart upload sessions and their acknowledged parts. */
class JdbcResumableUploadSessionRepository(
    private val objectMapper: ObjectMapper,
) : OwnerScopedResumableUploadSessionRepository,
    StagedResumableUploadSessionRepository,
    CompletionRejectionResettableResumableUploadSessionRepository,
    CompletedResumableUploadAssetClaimRepository {
    override fun save(session: ResumableUploadSession) {
        val ownerId = requireNotNull(session.ownerId) {
            "New resumable upload sessions must have a trusted owner id."
        }
        val dialect = JdbcConnectionContext.requireDialect()
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            INSERT INTO fw_upload_session(
                id, tenant_id, owner_id, idempotency_key, storage_upload_id, storage_type, storage_path,
                file_object_id, file_asset_id, file_name, content_length, asset_type, content_type, content_hash,
                metadata_json, session_status, expires_at, last_error, completed_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ${dialect.jsonParameterBinding()}, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, session.id.value)
            statement.setString(2, session.tenantId.value)
            statement.setString(3, ownerId)
            statement.setString(4, session.idempotencyKey)
            statement.setString(5, session.storageUploadId.value)
            statement.setString(6, session.storageLocation.storageType)
            statement.setString(7, session.storageLocation.path)
            statement.setString(8, session.fileObjectId.value)
            statement.setString(9, session.fileAssetId.value)
            statement.setString(10, session.fileName)
            statement.setLong(11, session.contentLength)
            statement.setString(12, session.assetType)
            statement.setString(13, session.contentType)
            statement.setString(14, session.expectedContentHash)
            statement.setString(15, objectMapper.writeValueAsString(session.metadata))
            statement.setString(16, session.status.name)
            statement.setLong(17, session.expiresAt)
            statement.setString(18, session.lastError)
            session.completedAt?.let { statement.setLong(19, it) } ?: statement.setNull(19, java.sql.Types.BIGINT)
            statement.setLong(20, session.createdTime)
            statement.setLong(21, session.updatedTime)
            statement.executeUpdate()
        }
    }

    override fun findById(tenantId: Identifier, sessionId: Identifier): ResumableUploadSession? = querySession(
        "SELECT $SESSION_COLUMNS FROM fw_upload_session WHERE tenant_id = ? AND id = ?",
    ) { statement ->
        statement.setString(1, tenantId.value)
        statement.setString(2, sessionId.value)
    }

    override fun findByIdempotencyKey(tenantId: Identifier, idempotencyKey: String): ResumableUploadSession? = querySession(
        "SELECT $SESSION_COLUMNS FROM fw_upload_session WHERE tenant_id = ? AND idempotency_key = ?",
    ) { statement ->
        statement.setString(1, tenantId.value)
        statement.setString(2, idempotencyKey)
    }

    override fun findById(
        tenantId: Identifier,
        ownerId: String,
        sessionId: Identifier,
    ): ResumableUploadSession? = querySession(
        "SELECT $SESSION_COLUMNS FROM fw_upload_session WHERE tenant_id = ? AND owner_id = ? AND id = ?",
    ) { statement ->
        statement.setString(1, tenantId.value)
        statement.setString(2, ownerId)
        statement.setString(3, sessionId.value)
    }

    override fun findByIdempotencyKey(
        tenantId: Identifier,
        ownerId: String,
        idempotencyKey: String,
    ): ResumableUploadSession? = querySession(
        "SELECT $SESSION_COLUMNS FROM fw_upload_session WHERE tenant_id = ? AND owner_id = ? AND idempotency_key = ?",
    ) { statement ->
        statement.setString(1, tenantId.value)
        statement.setString(2, ownerId)
        statement.setString(3, idempotencyKey)
    }

    override fun lockCompletedAssetClaim(
        tenantId: Identifier,
        ownerId: String,
        uploadId: Identifier,
    ): CompletedResumableUploadAssetClaimState? = queryClaimState(
        "SELECT $CLAIM_STATE_COLUMNS FROM fw_upload_session " +
            "WHERE tenant_id = ? AND owner_id = ? AND id = ? FOR UPDATE",
    ) { statement ->
        statement.setString(1, tenantId.value)
        statement.setString(2, ownerId)
        statement.setString(3, uploadId.value)
    }

    override fun findCompletedAssetClaim(
        tenantId: Identifier,
        ownerId: String,
        uploadId: Identifier,
    ): CompletedResumableUploadAssetClaimState? = queryClaimState(
        "SELECT $CLAIM_STATE_COLUMNS FROM fw_upload_session WHERE tenant_id = ? AND owner_id = ? AND id = ?",
    ) { statement ->
        statement.setString(1, tenantId.value)
        statement.setString(2, ownerId)
        statement.setString(3, uploadId.value)
    }

    override fun markCompletedAssetClaimed(
        expected: ResumableUploadSession,
        claim: CompletedResumableUploadAssetClaim,
    ): CompletedResumableUploadAssetClaimState? {
        val expectedOwnerId = expected.ownerId ?: return null
        val expectedCompletedAt = expected.completedAt ?: return null
        if (
            claim.tenantId != expected.tenantId ||
            claim.uploadId != expected.id ||
            claim.fileObjectId != expected.fileObjectId ||
            claim.fileAssetId != expected.fileAssetId ||
            claim.claimedBy != expectedOwnerId ||
            expected.status != ResumableUploadSessionStatus.COMPLETED ||
            expected.assetType != DOCUMENT_ASSET_TYPE ||
            expected.lastError != null ||
            expected.updatedTime != expectedCompletedAt ||
            claim.resourceType != DOCUMENT_RESOURCE_TYPE ||
            claim.claimedTime < expectedCompletedAt ||
            claim.claimedTime < expected.updatedTime ||
            claim.claimedTime >= expected.expiresAt
        ) return null
        val dialect = JdbcConnectionContext.requireDialect()
        val metadataJson = objectMapper.writeValueAsString(expected.metadata)
        val updated = JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            UPDATE fw_upload_session
            SET claimed_idempotency_key_digest = ?, claimed_resource_type = ?, claimed_resource_id = ?,
                claimed_subresource_id = ?, claimed_by = ?, claimed_time = ?, updated_time = ?
            WHERE tenant_id = ? AND owner_id = ? AND id = ?
              AND idempotency_key = ? AND file_object_id = ? AND file_asset_id = ?
              AND storage_upload_id = ? AND storage_type = ? AND storage_path = ?
              AND file_name = ? AND content_length = ? AND asset_type = ?
              AND ${dialect.isNotDistinctFrom("content_type")}
              AND ${dialect.isNotDistinctFrom("content_hash")}
              AND metadata_json = ${dialect.jsonParameterBinding()}
              AND session_status = 'COMPLETED'
              AND expires_at = ? AND expires_at > ?
              AND ${dialect.isNotDistinctFrom("last_error")}
              AND completed_time = ? AND completed_time IS NOT NULL
              AND created_time = ? AND updated_time = ?
              AND claimed_time IS NULL AND claimed_idempotency_key_digest IS NULL
              AND claimed_resource_type IS NULL AND claimed_resource_id IS NULL
              AND claimed_subresource_id IS NULL AND claimed_by IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, claim.idempotencyKeyDigest)
            statement.setString(2, claim.resourceType)
            statement.setString(3, claim.resourceId.value)
            statement.setString(4, claim.subresourceId.value)
            statement.setString(5, claim.claimedBy)
            statement.setLong(6, claim.claimedTime)
            statement.setLong(7, claim.claimedTime)
            statement.setString(8, expected.tenantId.value)
            statement.setString(9, expectedOwnerId)
            statement.setString(10, expected.id.value)
            statement.setString(11, expected.idempotencyKey)
            statement.setString(12, expected.fileObjectId.value)
            statement.setString(13, expected.fileAssetId.value)
            statement.setString(14, expected.storageUploadId.value)
            statement.setString(15, expected.storageLocation.storageType)
            statement.setString(16, expected.storageLocation.path)
            statement.setString(17, expected.fileName)
            statement.setLong(18, expected.contentLength)
            statement.setString(19, expected.assetType)
            statement.setString(20, expected.contentType)
            statement.setString(21, expected.expectedContentHash)
            statement.setString(22, metadataJson)
            statement.setLong(23, expected.expiresAt)
            statement.setLong(24, claim.claimedTime)
            statement.setString(25, expected.lastError)
            statement.setLong(26, expectedCompletedAt)
            statement.setLong(27, expected.createdTime)
            statement.setLong(28, expected.updatedTime)
            statement.executeUpdate()
        }
        if (updated != 1) return null
        return findCompletedAssetClaim(expected.tenantId, claim.claimedBy, expected.id)
    }

    override fun findParts(tenantId: Identifier, sessionId: Identifier): List<ResumableUploadPart> =
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "SELECT $PART_COLUMNS FROM fw_upload_session_part WHERE tenant_id = ? AND session_id = ? ORDER BY part_number",
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, sessionId.value)
            statement.executeQuery().use { result ->
                val parts = ArrayList<ResumableUploadPart>()
                while (result.next()) parts += mapPart(result)
                parts
            }
        }

    override fun savePart(part: ResumableUploadPart) {
        val connection = JdbcConnectionContext.requireCurrent()
        connection.prepareStatement(
            """
            UPDATE fw_upload_session
            SET updated_time = ?
            WHERE tenant_id = ? AND id = ? AND session_status = 'ACTIVE' AND expires_at > ?
            """.trimIndent(),
        ).use { session ->
            session.setLong(1, part.updatedTime)
            session.setString(2, part.tenantId.value)
            session.setString(3, part.sessionId.value)
            session.setLong(4, part.updatedTime)
            require(session.executeUpdate() == 1) { "Upload session is not active while acknowledging a multipart part." }
        }
        val dialect = JdbcConnectionContext.requireDialect()
        connection.prepareStatement(
            """
            INSERT INTO fw_upload_session_part(
                id, tenant_id, session_id, part_number, part_etag, content_length, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ${dialect.upsertClause(
                listOf("tenant_id", "session_id", "part_number"),
                listOf(
                    "part_etag = ${dialect.excludedColumnReference("part_etag")}",
                    "content_length = ${dialect.excludedColumnReference("content_length")}",
                    "updated_time = ${dialect.excludedColumnReference("updated_time")}",
                ),
            )}
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, part.id.value)
            statement.setString(2, part.tenantId.value)
            statement.setString(3, part.sessionId.value)
            statement.setInt(4, part.partNumber)
            statement.setString(5, part.eTag)
            statement.setLong(6, part.contentLength)
            statement.setLong(7, part.createdTime)
            statement.setLong(8, part.updatedTime)
            statement.executeUpdate()
        }
    }

    override fun claimForCompletion(tenantId: Identifier, sessionId: Identifier, now: Long): ResumableUploadSession? {
        val connection = JdbcConnectionContext.requireCurrent()
        val updated = connection.prepareStatement(
            """
            UPDATE fw_upload_session
            SET session_status = 'COMPLETING', last_error = NULL, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND session_status = 'ACTIVE' AND expires_at > ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, now)
            statement.setString(2, tenantId.value)
            statement.setString(3, sessionId.value)
            statement.setLong(4, now)
            statement.executeUpdate()
        }
        return if (updated == 1) findById(tenantId, sessionId) else null
    }

    override fun reactivateAfterCompletionFailure(tenantId: Identifier, sessionId: Identifier, message: String, updatedAt: Long): Boolean =
        transition(
            """
            UPDATE fw_upload_session SET session_status = 'ACTIVE', last_error = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND session_status = 'COMPLETING'
            """.trimIndent(),
            message, updatedAt, tenantId.value, sessionId.value,
        )

    override fun resetAfterCompletionRejection(
        tenantId: Identifier,
        sessionId: Identifier,
        message: String,
        expiresAt: Long,
        updatedAt: Long,
    ): Boolean {
        val reactivated = transition(
            """
            UPDATE fw_upload_session SET session_status = 'ACTIVE', last_error = ?, expires_at = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND session_status = 'COMPLETING'
            """.trimIndent(),
            message, expiresAt, updatedAt, tenantId.value, sessionId.value,
        )
        if (!reactivated) return false
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "DELETE FROM fw_upload_session_part WHERE tenant_id = ? AND session_id = ?",
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, sessionId.value)
            statement.executeUpdate()
        }
        return true
    }

    override fun markFailed(tenantId: Identifier, sessionId: Identifier, message: String, updatedAt: Long): Boolean {
        val dialect = JdbcConnectionContext.requireDialect()
        return transition(
            """
            UPDATE fw_upload_session SET session_status = 'FAILED', last_error = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND session_status IN ('COMPLETING', 'ABORTING')
              AND (session_status <> 'ABORTING' OR ${dialect.isDistinctFrom("last_error", "?")})
            """.trimIndent(),
            message, updatedAt, tenantId.value, sessionId.value, CREATION_STAGING_MARKER,
        )
    }

    override fun markQuarantined(
        tenantId: Identifier,
        sessionId: Identifier,
        message: String,
        updatedAt: Long,
    ): Boolean = transition(
        """
        UPDATE fw_upload_session SET session_status = 'QUARANTINED', last_error = ?, updated_time = ?
        WHERE tenant_id = ? AND id = ? AND session_status = 'ABORTING'
        """.trimIndent(),
        message, updatedAt, tenantId.value, sessionId.value,
    )

    override fun activateStaged(
        tenantId: Identifier,
        sessionId: Identifier,
        expectedOwnerId: String,
        stagingMarker: String,
        activatedAt: Long,
    ): Boolean = transition(
        """
        UPDATE fw_upload_session SET session_status = 'ACTIVE', last_error = NULL, updated_time = ?
        WHERE tenant_id = ? AND id = ? AND owner_id = ?
          AND session_status = 'ABORTING' AND last_error = ? AND expires_at > ?
        """.trimIndent(),
        activatedAt, tenantId.value, sessionId.value, expectedOwnerId, stagingMarker, activatedAt,
    )

    override fun markCompleted(tenantId: Identifier, sessionId: Identifier, completedAt: Long): Boolean =
        transition(
            """
            UPDATE fw_upload_session
            SET session_status = 'COMPLETED', last_error = NULL, completed_time = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND session_status = 'COMPLETING'
            """.trimIndent(),
            completedAt, completedAt, tenantId.value, sessionId.value,
        )

    override fun claimForAbort(tenantId: Identifier, sessionId: Identifier, updatedAt: Long): ResumableUploadSession? {
        val connection = JdbcConnectionContext.requireCurrent()
        val updated = connection.prepareStatement(
            """
            UPDATE fw_upload_session
            SET session_status = 'ABORTING', updated_time = ?
            WHERE tenant_id = ? AND id = ? AND session_status IN ('ACTIVE', 'ABORTING', 'FAILED')
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, updatedAt)
            statement.setString(2, tenantId.value)
            statement.setString(3, sessionId.value)
            statement.executeUpdate()
        }
        return if (updated == 1) findById(tenantId, sessionId) else null
    }

    override fun markAborted(tenantId: Identifier, sessionId: Identifier, expired: Boolean, updatedAt: Long): Boolean {
        val dialect = JdbcConnectionContext.requireDialect()
        return transition(
            """
            UPDATE fw_upload_session SET session_status = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND session_status = 'ABORTING'
              AND ${dialect.isDistinctFrom("last_error", "?")}
            """.trimIndent(),
            if (expired) ResumableUploadSessionStatus.EXPIRED.name else ResumableUploadSessionStatus.ABORTED.name,
            updatedAt,
            tenantId.value,
            sessionId.value,
            CREATION_STAGING_MARKER,
        )
    }

    override fun findExpired(now: Long, limit: Int): List<ResumableUploadSession> =
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            SELECT $SESSION_COLUMNS
            FROM fw_upload_session
            WHERE session_status IN ('ACTIVE', 'ABORTING', 'FAILED') AND expires_at <= ?
            ORDER BY expires_at, id
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, now)
            statement.setInt(2, limit)
            statement.executeQuery().use { result ->
                val sessions = ArrayList<ResumableUploadSession>()
                while (result.next()) sessions += mapSession(result)
                sessions
            }
        }

    override fun findExpiredCompleting(now: Long, limit: Int): List<ResumableUploadSession> =
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            SELECT $SESSION_COLUMNS
            FROM fw_upload_session
            WHERE session_status = 'COMPLETING' AND expires_at <= ?
            ORDER BY expires_at, id
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, now)
            statement.setInt(2, limit)
            statement.executeQuery().use { result ->
                val sessions = ArrayList<ResumableUploadSession>()
                while (result.next()) sessions += mapSession(result)
                sessions
            }
        }

    override fun findExpiredCompleting(tenantId: Identifier, now: Long, limit: Int): List<ResumableUploadSession> =
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            SELECT $SESSION_COLUMNS
            FROM fw_upload_session
            WHERE tenant_id = ? AND session_status = 'COMPLETING' AND expires_at <= ?
            ORDER BY expires_at, id
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setLong(2, now)
            statement.setInt(3, limit)
            statement.executeQuery().use { result ->
                val sessions = ArrayList<ResumableUploadSession>()
                while (result.next()) sessions += mapSession(result)
                sessions
            }
        }

    private fun querySession(sql: String, bind: (java.sql.PreparedStatement) -> Unit): ResumableUploadSession? =
        JdbcConnectionContext.requireCurrent().prepareStatement(sql).use { statement ->
            bind(statement)
            statement.executeQuery().use { result -> if (result.next()) mapSession(result) else null }
        }

    private fun queryClaimState(
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): CompletedResumableUploadAssetClaimState? = JdbcConnectionContext.requireCurrent().prepareStatement(sql).use { statement ->
        bind(statement)
        statement.executeQuery().use { result -> if (result.next()) mapClaimState(result) else null }
    }

    private fun transition(sql: String, vararg values: Any): Boolean = JdbcConnectionContext.requireCurrent().prepareStatement(sql).use { statement ->
        values.forEachIndexed { offset, value ->
            when (value) {
                is String -> statement.setString(offset + 1, value)
                is Long -> statement.setLong(offset + 1, value)
                else -> throw IllegalArgumentException("Unsupported upload session SQL value ${value.javaClass.name}.")
            }
        }
        statement.executeUpdate() == 1
    }

    private fun mapSession(result: ResultSet): ResumableUploadSession {
        val completedTime = result.getLong("completed_time").let { value -> if (result.wasNull()) null else value }
        return ResumableUploadSession(
            id = Identifier(result.getString("id")),
            tenantId = Identifier(result.getString("tenant_id")),
            idempotencyKey = result.getString("idempotency_key"),
            storageUploadId = Identifier(result.getString("storage_upload_id")),
            storageLocation = StorageObjectLocation(result.getString("storage_type"), result.getString("storage_path")),
            fileObjectId = Identifier(result.getString("file_object_id")),
            fileAssetId = Identifier(result.getString("file_asset_id")),
            fileName = result.getString("file_name"),
            contentLength = result.getLong("content_length"),
            assetType = result.getString("asset_type"),
            contentType = result.getString("content_type"),
            expectedContentHash = result.getString("content_hash"),
            metadata = objectMapper.readValue(result.getString("metadata_json"), STRING_MAP_TYPE),
            status = ResumableUploadSessionStatus.valueOf(result.getString("session_status")),
            expiresAt = result.getLong("expires_at"),
            lastError = result.getString("last_error"),
            completedAt = completedTime,
            createdTime = result.getLong("created_time"),
            updatedTime = result.getLong("updated_time"),
            ownerId = result.getString("owner_id"),
        )
    }

    private fun mapClaimState(result: ResultSet): CompletedResumableUploadAssetClaimState {
        val session = mapSession(result)
        val keyDigest = result.getString("claimed_idempotency_key_digest")
        val resourceType = result.getString("claimed_resource_type")
        val resourceId = result.getString("claimed_resource_id")
        val subresourceId = result.getString("claimed_subresource_id")
        val claimedBy = result.getString("claimed_by")
        val claimedTime = result.getLong("claimed_time").let { value -> if (result.wasNull()) null else value }
        val markerValues = listOf(keyDigest, resourceType, resourceId, subresourceId, claimedBy)
        val hasAnyMarker = claimedTime != null || markerValues.any { it != null }
        if (hasAnyMarker && (claimedTime == null || markerValues.any { it == null })) {
            throw CompletedResumableUploadAssetClaimStateException(
                "Persisted completed upload claim marker is incomplete.",
            )
        }
        val claim = claimedTime?.let { time ->
            try {
                CompletedResumableUploadAssetClaim(
                    tenantId = session.tenantId,
                    uploadId = session.id,
                    fileObjectId = session.fileObjectId,
                    fileAssetId = session.fileAssetId,
                    idempotencyKeyDigest = requireNotNull(keyDigest),
                    resourceType = requireNotNull(resourceType),
                    resourceId = Identifier(requireNotNull(resourceId)),
                    subresourceId = Identifier(requireNotNull(subresourceId)),
                    claimedBy = requireNotNull(claimedBy),
                    claimedTime = time,
                )
            } catch (failure: IllegalArgumentException) {
                throw CompletedResumableUploadAssetClaimStateException(
                    "Persisted completed upload claim marker is invalid.",
                    failure,
                )
            }
        }
        return CompletedResumableUploadAssetClaimState(session, claim)
    }

    private fun mapPart(result: ResultSet): ResumableUploadPart = ResumableUploadPart(
        id = Identifier(result.getString("id")),
        tenantId = Identifier(result.getString("tenant_id")),
        sessionId = Identifier(result.getString("session_id")),
        partNumber = result.getInt("part_number"),
        eTag = result.getString("part_etag"),
        contentLength = result.getLong("content_length"),
        createdTime = result.getLong("created_time"),
        updatedTime = result.getLong("updated_time"),
    )

    private companion object {
        val STRING_MAP_TYPE = object : TypeReference<Map<String, String>>() {}
        const val SESSION_COLUMNS = """
            id, tenant_id, owner_id, idempotency_key, storage_upload_id, storage_type, storage_path,
            file_object_id, file_asset_id, file_name, content_length, asset_type, content_type, content_hash,
            metadata_json, session_status, expires_at, last_error, completed_time, created_time, updated_time
        """
        const val CLAIM_STATE_COLUMNS = """
            $SESSION_COLUMNS,
            claimed_idempotency_key_digest, claimed_resource_type, claimed_resource_id,
            claimed_subresource_id, claimed_by, claimed_time
        """
        const val PART_COLUMNS = "id, tenant_id, session_id, part_number, part_etag, content_length, created_time, updated_time"
        const val CREATION_STAGING_MARKER = "fileweft:resumable-upload:creation-staging:v1"
        const val DOCUMENT_ASSET_TYPE = "DOCUMENT"
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
    }
}
