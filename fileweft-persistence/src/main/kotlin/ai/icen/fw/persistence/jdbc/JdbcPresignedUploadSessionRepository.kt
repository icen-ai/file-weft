package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.idempotency.RequestFingerprint
import ai.icen.fw.application.upload.CompletedPresignedUploadAssetClaim
import ai.icen.fw.application.upload.CompletedPresignedUploadAssetClaimRepository
import ai.icen.fw.application.upload.CompletedPresignedUploadAssetClaimState
import ai.icen.fw.application.upload.PresignedUploadSession
import ai.icen.fw.application.upload.PresignedUploadSessionStatus
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.PresignedUploadFinalization
import ai.icen.fw.spi.storage.StorageContentChecksum
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StoredObject
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

/** JDBC persistence for authoritative direct-upload sessions. */
class JdbcPresignedUploadSessionRepository(
    private val objectMapper: ObjectMapper,
) : CompletedPresignedUploadAssetClaimRepository {
    override fun create(session: PresignedUploadSession): Boolean {
        val dialect = JdbcConnectionContext.requireDialect()
        return try {
            JdbcConnectionContext.requireCurrent().prepareStatement(
                """
                INSERT INTO fw_presigned_upload_session(
                    id, tenant_id, owner_id, idempotency_key_digest, declaration_digest,
                    file_name, content_length, content_type, content_hash,
                    checksum_algorithm, checksum_value, metadata_json,
                    staging_storage_type, staging_storage_path, staging_location_digest,
                    required_headers_json, grant_duration_millis, grant_expires_at, session_expires_at,
                    session_status, row_version, claim_token, claim_time, claim_expires_at,
                    final_storage_type, final_storage_path, final_location_digest, provider_revision,
                    final_content_length, final_content_type, final_content_hash,
                    final_checksum_algorithm, final_checksum_value, final_metadata_json,
                    last_error_class, completed_time, cancelled_time, cleanup_time,
                    created_time, updated_time
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ${dialect.jsonParameterBinding()},
                    ?, ?, ?,
                    ${dialect.jsonParameterBinding()}, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ${dialect.jsonParameterBinding()},
                    ?, ?, ?, ?,
                    ?, ?
                )
                ${if (dialect.productName == "MySQL") "" else "ON CONFLICT DO NOTHING"}
                """.trimIndent(),
            ).use { statement ->
                bindInsert(statement, session)
                statement.executeUpdate() == 1
            }
        } catch (failure: SQLException) {
            if (failure.isUniqueViolation()) false else throw failure
        }
    }

    override fun findById(
        tenantId: Identifier,
        sessionId: Identifier,
    ): PresignedUploadSession? = queryOne(
        "SELECT $SESSION_COLUMNS FROM fw_presigned_upload_session WHERE tenant_id = ? AND id = ?",
    ) { statement ->
        statement.setString(1, tenantId.value)
        statement.setString(2, sessionId.value)
    }

    override fun findById(
        tenantId: Identifier,
        ownerId: String,
        sessionId: Identifier,
    ): PresignedUploadSession? = queryOne(
        """
        SELECT $SESSION_COLUMNS
        FROM fw_presigned_upload_session
        WHERE tenant_id = ? AND owner_id = ? AND id = ?
        """.trimIndent(),
    ) { statement ->
        statement.setString(1, tenantId.value)
        statement.setString(2, ownerId)
        statement.setString(3, sessionId.value)
    }

    override fun findByIdempotencyKey(
        tenantId: Identifier,
        ownerId: String,
        idempotencyKeyDigest: String,
    ): PresignedUploadSession? = queryOne(
        """
        SELECT $SESSION_COLUMNS
        FROM fw_presigned_upload_session
        WHERE tenant_id = ? AND owner_id = ? AND idempotency_key_digest = ?
        """.trimIndent(),
    ) { statement ->
        statement.setString(1, tenantId.value)
        statement.setString(2, ownerId)
        statement.setString(3, idempotencyKeyDigest)
    }

    override fun findRecoveryCandidates(now: Long, limit: Int): List<PresignedUploadSession> {
        require(limit in 1..MAX_MAINTENANCE_BATCH_SIZE) { "Presigned upload recovery limit is invalid." }
        return queryMany(
            """
            SELECT $SESSION_COLUMNS
            FROM fw_presigned_upload_session
            WHERE session_status = 'FINALIZING'
              AND claim_expires_at <= ?
              AND session_expires_at > ?
            ORDER BY claim_expires_at, tenant_id, id
            LIMIT ?
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, now)
            statement.setLong(2, now)
            statement.setInt(3, limit)
        }
    }

    override fun findCleanupCandidates(now: Long, limit: Int): List<PresignedUploadSession> {
        require(limit in 1..MAX_MAINTENANCE_BATCH_SIZE) { "Presigned upload cleanup limit is invalid." }
        return queryMany(
            """
            SELECT $SESSION_COLUMNS
            FROM fw_presigned_upload_session
            WHERE cleanup_time IS NULL
              AND grant_expires_at <= ?
              AND (
                    session_status IN ('CANCELLED', 'EXPIRED')
                    OR (
                        session_status IN ('READY', 'FINALIZING')
                        AND session_expires_at <= ?
                    )
              )
            ORDER BY updated_time, grant_expires_at, tenant_id, id
            LIMIT ?
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, now)
            statement.setLong(2, now)
            statement.setInt(3, limit)
        }
    }

    override fun lockCompletedAssetClaim(
        tenantId: Identifier,
        ownerId: String,
        uploadId: Identifier,
    ): CompletedPresignedUploadAssetClaimState? = queryClaimState(
        """
        SELECT $SESSION_COLUMNS, $ASSET_CLAIM_COLUMNS
        FROM fw_presigned_upload_session
        WHERE tenant_id = ? AND owner_id = ? AND id = ?
        FOR UPDATE
        """.trimIndent(),
        tenantId,
        ownerId,
        uploadId,
    )

    override fun findCompletedAssetClaim(
        tenantId: Identifier,
        ownerId: String,
        uploadId: Identifier,
    ): CompletedPresignedUploadAssetClaimState? = queryClaimState(
        """
        SELECT $SESSION_COLUMNS, $ASSET_CLAIM_COLUMNS
        FROM fw_presigned_upload_session
        WHERE tenant_id = ? AND owner_id = ? AND id = ?
        """.trimIndent(),
        tenantId,
        ownerId,
        uploadId,
    )

    override fun markCompletedAssetClaimed(
        expected: PresignedUploadSession,
        claim: CompletedPresignedUploadAssetClaim,
    ): CompletedPresignedUploadAssetClaimState? {
        require(
            claim.tenantId == expected.tenantId &&
                claim.uploadId == expected.id &&
                claim.claimedBy == expected.ownerId,
        ) { "Presigned upload asset claim must preserve tenant, upload and owner authority." }
        val finalization = expected.finalization
            ?: throw IllegalArgumentException("Only a provider-verified completed upload may be claimed.")
        require(expected.status == PresignedUploadSessionStatus.COMPLETED && expected.completedTime != null) {
            "Only a provider-verified completed upload may be claimed."
        }
        val replacementVersion = Math.addExact(expected.version, 1)
        val updated = JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            UPDATE fw_presigned_upload_session
            SET asset_file_object_id = ?, asset_file_asset_id = ?,
                asset_claim_key_digest = ?, asset_claim_purpose = ?,
                asset_claimed_by = ?, asset_claimed_time = ?, row_version = ?
            WHERE tenant_id = ? AND id = ? AND owner_id = ? AND row_version = ?
              AND idempotency_key_digest = ? AND declaration_digest = ?
              AND staging_location_digest = ? AND final_location_digest = ?
              AND provider_revision = ? AND final_content_length = ?
              AND final_content_type = ? AND final_content_hash = ?
              AND final_checksum_algorithm = ? AND final_checksum_value = ?
              AND completed_time = ? AND updated_time = ?
              AND session_status = 'COMPLETED' AND session_expires_at > ?
              AND asset_file_object_id IS NULL AND asset_file_asset_id IS NULL
              AND asset_claim_key_digest IS NULL AND asset_claim_purpose IS NULL
              AND asset_claimed_by IS NULL AND asset_claimed_time IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, claim.fileObjectId.value)
            statement.setString(2, claim.fileAssetId.value)
            statement.setString(3, claim.idempotencyKeyDigest)
            statement.setString(4, claim.purpose)
            statement.setString(5, claim.claimedBy)
            statement.setLong(6, claim.claimedTime)
            statement.setLong(7, replacementVersion)
            statement.setString(8, expected.tenantId.value)
            statement.setString(9, expected.id.value)
            statement.setString(10, expected.ownerId)
            statement.setLong(11, expected.version)
            statement.setString(12, expected.idempotencyKeyDigest)
            statement.setString(13, expected.declarationDigest)
            statement.setString(14, locationDigest(expected.stagingLocation))
            statement.setString(15, locationDigest(finalization.storedObject.location))
            statement.setString(16, finalization.revision)
            statement.setLong(17, finalization.storedObject.contentLength)
            statement.setString(18, finalization.storedObject.contentType)
            statement.setString(19, finalization.storedObject.contentHash)
            statement.setString(20, finalization.checksum.algorithm)
            statement.setString(21, finalization.checksum.value)
            statement.setLong(22, requireNotNull(expected.completedTime))
            statement.setLong(23, expected.updatedTime)
            statement.setLong(24, claim.claimedTime)
            statement.executeUpdate()
        }
        if (updated != 1) return null
        val state = findCompletedAssetClaim(expected.tenantId, expected.ownerId, expected.id)
            ?: throw IllegalStateException("Committed presigned upload asset claim could not be reloaded.")
        require(state.session.version == replacementVersion) {
            "Committed presigned upload asset claim did not advance its fence."
        }
        return state
    }

    override fun compareAndSet(
        tenantId: Identifier,
        sessionId: Identifier,
        expectedVersion: Long,
        replacement: PresignedUploadSession,
    ): Boolean = compareAndSet(
        tenantId,
        sessionId,
        expectedVersion,
        expectedClaimToken = null,
        replacement = replacement,
    )

    override fun compareAndSet(
        tenantId: Identifier,
        sessionId: Identifier,
        expectedVersion: Long,
        expectedClaimToken: String?,
        replacement: PresignedUploadSession,
    ): Boolean {
        require(replacement.tenantId == tenantId && replacement.id == sessionId) {
            "Presigned upload CAS must preserve tenant and session identifiers."
        }
        require(replacement.version == Math.addExact(expectedVersion, 1)) {
            "Presigned upload CAS version must advance exactly once."
        }
        val dialect = JdbcConnectionContext.requireDialect()
        val finalization = replacement.finalization
        val updated = JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            UPDATE fw_presigned_upload_session
            SET session_status = ?, row_version = ?,
                claim_token = ?, claim_time = ?, claim_expires_at = ?,
                final_storage_type = ?, final_storage_path = ?, final_location_digest = ?,
                provider_revision = ?, final_content_length = ?, final_content_type = ?, final_content_hash = ?,
                final_checksum_algorithm = ?, final_checksum_value = ?,
                final_metadata_json = ${dialect.jsonParameterBinding()},
                last_error_class = ?, completed_time = ?, cancelled_time = ?, cleanup_time = ?, updated_time = ?
            WHERE tenant_id = ? AND id = ? AND row_version = ?
              AND owner_id = ? AND idempotency_key_digest = ? AND declaration_digest = ?
              AND staging_location_digest = ? AND staging_storage_type = ? AND staging_storage_path = ?
              AND ${dialect.isNotDistinctFrom("claim_token")}
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, replacement.status.name)
            statement.setLong(2, replacement.version)
            statement.setString(3, replacement.claimToken)
            statement.setNullableLong(4, replacement.claimTime)
            statement.setNullableLong(5, replacement.claimExpiresAt)
            statement.setString(6, finalization?.storedObject?.location?.storageType)
            statement.setString(7, finalization?.storedObject?.location?.path)
            statement.setString(8, finalization?.storedObject?.location?.let(::locationDigest))
            statement.setString(9, finalization?.revision)
            statement.setNullableLong(10, finalization?.storedObject?.contentLength)
            statement.setString(11, finalization?.storedObject?.contentType)
            statement.setString(12, finalization?.storedObject?.contentHash)
            statement.setString(13, finalization?.checksum?.algorithm)
            statement.setString(14, finalization?.checksum?.value)
            statement.setString(15, finalization?.let { objectMapper.writeValueAsString(it.metadata) })
            statement.setString(16, replacement.lastError)
            statement.setNullableLong(17, replacement.completedTime)
            statement.setNullableLong(18, replacement.cancelledTime)
            statement.setNullableLong(19, replacement.cleanupTime)
            statement.setLong(20, replacement.updatedTime)
            statement.setString(21, tenantId.value)
            statement.setString(22, sessionId.value)
            statement.setLong(23, expectedVersion)
            statement.setString(24, replacement.ownerId)
            statement.setString(25, replacement.idempotencyKeyDigest)
            statement.setString(26, replacement.declarationDigest)
            statement.setString(27, locationDigest(replacement.stagingLocation))
            statement.setString(28, replacement.stagingLocation.storageType)
            statement.setString(29, replacement.stagingLocation.path)
            statement.setString(30, expectedClaimToken)
            statement.executeUpdate()
        }
        return updated == 1
    }

    private fun bindInsert(statement: PreparedStatement, session: PresignedUploadSession) {
        val finalization = session.finalization
        statement.setString(1, session.id.value)
        statement.setString(2, session.tenantId.value)
        statement.setString(3, session.ownerId)
        statement.setString(4, session.idempotencyKeyDigest)
        statement.setString(5, session.declarationDigest)
        statement.setString(6, session.fileName)
        statement.setLong(7, session.contentLength)
        statement.setString(8, session.contentType)
        statement.setString(9, session.contentHash)
        statement.setString(10, session.checksum.algorithm)
        statement.setString(11, session.checksum.value)
        statement.setString(12, objectMapper.writeValueAsString(session.metadata))
        statement.setString(13, session.stagingLocation.storageType)
        statement.setString(14, session.stagingLocation.path)
        statement.setString(15, locationDigest(session.stagingLocation))
        statement.setString(16, objectMapper.writeValueAsString(session.requiredHeaders))
        statement.setLong(17, session.grantDurationMillis)
        statement.setLong(18, session.grantExpiresAt)
        statement.setLong(19, session.sessionExpiresAt)
        statement.setString(20, session.status.name)
        statement.setLong(21, session.version)
        statement.setString(22, session.claimToken)
        statement.setNullableLong(23, session.claimTime)
        statement.setNullableLong(24, session.claimExpiresAt)
        statement.setString(25, finalization?.storedObject?.location?.storageType)
        statement.setString(26, finalization?.storedObject?.location?.path)
        statement.setString(27, finalization?.storedObject?.location?.let(::locationDigest))
        statement.setString(28, finalization?.revision)
        statement.setNullableLong(29, finalization?.storedObject?.contentLength)
        statement.setString(30, finalization?.storedObject?.contentType)
        statement.setString(31, finalization?.storedObject?.contentHash)
        statement.setString(32, finalization?.checksum?.algorithm)
        statement.setString(33, finalization?.checksum?.value)
        statement.setString(34, finalization?.let { objectMapper.writeValueAsString(it.metadata) })
        statement.setString(35, session.lastError)
        statement.setNullableLong(36, session.completedTime)
        statement.setNullableLong(37, session.cancelledTime)
        statement.setNullableLong(38, session.cleanupTime)
        statement.setLong(39, session.createdTime)
        statement.setLong(40, session.updatedTime)
    }

    private fun queryOne(
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ): PresignedUploadSession? = JdbcConnectionContext.requireCurrent().prepareStatement(sql).use { statement ->
        bind(statement)
        statement.executeQuery().use { result -> if (result.next()) mapSession(result) else null }
    }

    private fun queryMany(
        sql: String,
        bind: (PreparedStatement) -> Unit,
    ): List<PresignedUploadSession> = JdbcConnectionContext.requireCurrent().prepareStatement(sql).use { statement ->
        bind(statement)
        statement.executeQuery().use { result ->
            val sessions = ArrayList<PresignedUploadSession>()
            while (result.next()) sessions += mapSession(result)
            sessions
        }
    }

    private fun queryClaimState(
        sql: String,
        tenantId: Identifier,
        ownerId: String,
        uploadId: Identifier,
    ): CompletedPresignedUploadAssetClaimState? =
        JdbcConnectionContext.requireCurrent().prepareStatement(sql).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, ownerId)
            statement.setString(3, uploadId.value)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                val session = mapSession(result)
                val fileObjectId = result.getString("asset_file_object_id")
                val claim = fileObjectId?.let {
                    CompletedPresignedUploadAssetClaim(
                        tenantId = session.tenantId,
                        uploadId = session.id,
                        fileObjectId = Identifier(it),
                        fileAssetId = Identifier(result.getString("asset_file_asset_id")),
                        idempotencyKeyDigest = result.getString("asset_claim_key_digest"),
                        purpose = result.getString("asset_claim_purpose"),
                        claimedBy = result.getString("asset_claimed_by"),
                        claimedTime = result.getLong("asset_claimed_time"),
                    )
                }
                CompletedPresignedUploadAssetClaimState(session, claim)
            }
        }

    private fun mapSession(result: ResultSet): PresignedUploadSession {
        val id = Identifier(result.getString("id"))
        val tenantId = Identifier(result.getString("tenant_id"))
        val staging = StorageObjectLocation(
            result.getString("staging_storage_type"),
            result.getString("staging_storage_path"),
        )
        require(result.getString("staging_location_digest") == locationDigest(staging)) {
            "Persisted presigned upload staging location digest does not match its authority."
        }
        val finalStorageType = result.getString("final_storage_type")
        val finalization = finalStorageType?.let {
            val finalLocation = StorageObjectLocation(it, result.getString("final_storage_path"))
            require(result.getString("final_location_digest") == locationDigest(finalLocation)) {
                "Persisted presigned upload final location digest does not match its authority."
            }
            PresignedUploadFinalization(
                tenantId = tenantId,
                bindingId = id,
                sourceLocation = staging,
                storedObject = StoredObject(
                    location = finalLocation,
                    contentLength = result.getLong("final_content_length"),
                    contentType = result.getString("final_content_type"),
                    contentHash = result.getString("final_content_hash"),
                ),
                revision = result.getString("provider_revision"),
                checksum = StorageContentChecksum(
                    result.getString("final_checksum_algorithm"),
                    result.getString("final_checksum_value"),
                ),
                metadata = readStringMap(result.getString("final_metadata_json")),
            )
        }
        return PresignedUploadSession(
            id = id,
            tenantId = tenantId,
            ownerId = result.getString("owner_id"),
            fileName = result.getString("file_name"),
            contentLength = result.getLong("content_length"),
            contentType = result.getString("content_type"),
            contentHash = result.getString("content_hash"),
            checksum = StorageContentChecksum(
                result.getString("checksum_algorithm"),
                result.getString("checksum_value"),
            ),
            metadata = readStringMap(result.getString("metadata_json")),
            storageLocation = staging,
            grantExpiresAt = result.getLong("grant_expires_at"),
            sessionExpiresAt = result.getLong("session_expires_at"),
            status = PresignedUploadSessionStatus.valueOf(result.getString("session_status")),
            version = result.getLong("row_version"),
            claimTime = result.nullableLong("claim_time"),
            finalization = finalization,
            lastError = result.getString("last_error_class"),
            createdTime = result.getLong("created_time"),
            updatedTime = result.getLong("updated_time"),
            idempotencyKeyDigest = result.getString("idempotency_key_digest"),
            declarationDigest = result.getString("declaration_digest"),
            grantDurationMillis = result.getLong("grant_duration_millis"),
            requiredHeaders = readStringMap(result.getString("required_headers_json")),
            claimToken = result.getString("claim_token"),
            claimExpiresAt = result.nullableLong("claim_expires_at"),
            completedTime = result.nullableLong("completed_time"),
            cancelledTime = result.nullableLong("cancelled_time"),
            cleanupTime = result.nullableLong("cleanup_time"),
        )
    }

    private fun readStringMap(json: String): Map<String, String> =
        objectMapper.readValue(json, STRING_MAP_TYPE)

    private fun SQLException.isUniqueViolation(): Boolean =
        sqlState == POSTGRES_UNIQUE_SQL_STATE ||
            (sqlState == MYSQL_INTEGRITY_SQL_STATE && errorCode == MYSQL_DUPLICATE_ERROR_CODE)

    private companion object {
        const val MAX_MAINTENANCE_BATCH_SIZE = 1_000
        const val POSTGRES_UNIQUE_SQL_STATE = "23505"
        const val MYSQL_INTEGRITY_SQL_STATE = "23000"
        const val MYSQL_DUPLICATE_ERROR_CODE = 1062
        val STRING_MAP_TYPE = object : TypeReference<Map<String, String>>() {}
        const val SESSION_COLUMNS = """
            id, tenant_id, owner_id, idempotency_key_digest, declaration_digest,
            file_name, content_length, content_type, content_hash,
            checksum_algorithm, checksum_value, metadata_json,
            staging_storage_type, staging_storage_path, staging_location_digest,
            required_headers_json, grant_duration_millis, grant_expires_at, session_expires_at,
            session_status, row_version, claim_token, claim_time, claim_expires_at,
            final_storage_type, final_storage_path, final_location_digest, provider_revision,
            final_content_length, final_content_type, final_content_hash,
            final_checksum_algorithm, final_checksum_value, final_metadata_json,
            last_error_class, completed_time, cancelled_time, cleanup_time,
            created_time, updated_time
        """
        const val ASSET_CLAIM_COLUMNS = """
            asset_file_object_id, asset_file_asset_id, asset_claim_key_digest,
            asset_claim_purpose, asset_claimed_by, asset_claimed_time
        """
    }
}

private fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
}

private fun ResultSet.nullableLong(column: String): Long? = getLong(column).let { value ->
    if (wasNull()) return@let null
    value
}

private fun locationDigest(location: StorageObjectLocation): String = RequestFingerprint.sha256(
    "flowweft-presigned-upload-location-v1",
    location.storageType,
    location.path,
)
