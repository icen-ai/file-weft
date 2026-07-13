package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.idempotency.IdempotencyResult
import ai.icen.fw.application.idempotency.IdempotencyStoreException
import ai.icen.fw.application.idempotency.RequestIdempotency
import ai.icen.fw.application.idempotency.RequestIdempotencyClaim
import ai.icen.fw.application.idempotency.RequestIdempotencyRecord
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyStatus
import ai.icen.fw.core.id.Identifier
import java.sql.ResultSet

/** PostgreSQL persistence for tenant-scoped formal request idempotency. */
class JdbcRequestIdempotencyRepository : RequestIdempotencyRepository {
    override fun findByKeyDigest(tenantId: Identifier, keyDigest: String): RequestIdempotencyRecord? =
        find(tenantId, keyDigest, FIND_BY_KEY_DIGEST_SQL)

    override fun claim(
        request: RequestIdempotency,
        newRecordId: Identifier,
        now: Long,
    ): RequestIdempotencyClaim {
        val connection = JdbcConnectionContext.requireCurrent()
        val dialect = JdbcConnectionContext.requireDialect()
        val acquired = connection.prepareStatement(
            """
            INSERT INTO fw_idempotency_record(
                id, tenant_id, key_digest, operator_id, action, resource_type, resource_id, subresource_id,
                request_fingerprint, record_status, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?)
            ${dialect.upsertClause(listOf("tenant_id", "key_digest"), emptyList())}
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, newRecordId.value)
            statement.setString(2, request.tenantId.value)
            statement.setString(3, request.keyDigest)
            statement.setString(4, request.operatorId.value)
            statement.setString(5, request.action)
            statement.setString(6, request.resourceType)
            statement.setString(7, request.resourceId.value)
            statement.setString(8, request.subresourceId?.value)
            statement.setString(9, request.requestFingerprint)
            statement.setLong(10, now)
            statement.setLong(11, now)
            statement.executeUpdate() == 1
        }
        val record = find(request.tenantId, request.keyDigest, FIND_FOR_CLAIM_SQL)
            ?: throw IdempotencyStoreException("Claimed idempotency record is unavailable in the current tenant.")
        return RequestIdempotencyClaim(record, acquired)
    }

    override fun complete(
        recordId: Identifier,
        tenantId: Identifier,
        keyDigest: String,
        result: IdempotencyResult,
        completedAt: Long,
    ): RequestIdempotencyRecord {
        val connection = JdbcConnectionContext.requireCurrent()
        val updated = connection.prepareStatement(COMPLETE_UPDATE_SQL).use { statement ->
            statement.setString(1, result.resourceType)
            statement.setString(2, result.resourceId.value)
            statement.setString(3, result.relatedResourceType)
            statement.setString(4, result.relatedResourceId?.value)
            statement.setLong(5, completedAt)
            statement.setLong(6, completedAt)
            statement.setString(7, tenantId.value)
            statement.setString(8, recordId.value)
            statement.setString(9, keyDigest)
            statement.executeUpdate()
        }
        if (updated == 0) {
            throw IdempotencyStoreException(
                "Idempotency record cannot be completed from its current tenant, key, or status.",
            )
        }
        return connection.prepareStatement(FIND_BY_KEY_DIGEST_SQL).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, keyDigest)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Completed idempotency record is unavailable in the current tenant." }
                mapRecord(rows)
            }
        }
    }

    private fun find(
        tenantId: Identifier,
        keyDigest: String,
        sql: String,
    ): RequestIdempotencyRecord? = JdbcConnectionContext.requireCurrent().prepareStatement(sql).use { statement ->
        statement.setString(1, tenantId.value)
        statement.setString(2, keyDigest)
        statement.executeQuery().use { rows -> if (rows.next()) mapRecord(rows) else null }
    }

    private fun mapRecord(result: ResultSet): RequestIdempotencyRecord = try {
        val status = RequestIdempotencyStatus.valueOf(result.getString("record_status"))
        val completedTime = result.getLong("completed_time").let { value -> if (result.wasNull()) null else value }
        val storedResult = if (status == RequestIdempotencyStatus.COMPLETED) {
            IdempotencyResult(
                resourceType = requireNotNull(result.getString("result_resource_type")),
                resourceId = Identifier(requireNotNull(result.getString("result_resource_id"))),
                relatedResourceType = result.getString("result_related_resource_type"),
                relatedResourceId = result.getString("result_related_resource_id")?.let(::Identifier),
            )
        } else {
            null
        }
        RequestIdempotencyRecord(
            id = Identifier(result.getString("id")),
            tenantId = Identifier(result.getString("tenant_id")),
            keyDigest = result.getString("key_digest"),
            operatorId = Identifier(result.getString("operator_id")),
            action = result.getString("action"),
            resourceType = result.getString("resource_type"),
            resourceId = Identifier(result.getString("resource_id")),
            subresourceId = result.getString("subresource_id")?.let(::Identifier),
            requestFingerprint = result.getString("request_fingerprint"),
            status = status,
            result = storedResult,
            completedTime = completedTime,
            createdTime = result.getLong("created_time"),
            updatedTime = result.getLong("updated_time"),
        )
    } catch (failure: Exception) {
        if (failure is IdempotencyStoreException) throw failure
        throw IdempotencyStoreException("Persisted idempotency record is invalid.", failure)
    }

    private companion object {
        const val SELECT_COLUMNS = """
            id, tenant_id, key_digest, operator_id, action, resource_type, resource_id, subresource_id,
            request_fingerprint, record_status, result_resource_type, result_resource_id,
            result_related_resource_type, result_related_resource_id, completed_time, created_time, updated_time
        """

        const val FIND_BY_KEY_DIGEST_SQL =
            "SELECT $SELECT_COLUMNS FROM fw_idempotency_record WHERE tenant_id = ? AND key_digest = ?"

        const val FIND_FOR_CLAIM_SQL = FIND_BY_KEY_DIGEST_SQL + " FOR UPDATE"

        const val COMPLETE_UPDATE_SQL = """
            UPDATE fw_idempotency_record
            SET record_status = 'COMPLETED',
                result_resource_type = ?,
                result_resource_id = ?,
                result_related_resource_type = ?,
                result_related_resource_id = ?,
                completed_time = ?,
                updated_time = ?
            WHERE tenant_id = ? AND id = ? AND key_digest = ? AND record_status = 'IN_PROGRESS'
        """
    }
}
