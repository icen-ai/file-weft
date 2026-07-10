package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.core.id.Identifier
import com.fileweft.domain.operation.OperationLogRecord
import com.fileweft.domain.operation.OperationLogRepository

class JdbcOperationLogRepository(
    private val objectMapper: ObjectMapper,
) : OperationLogRepository {
    override fun append(record: OperationLogRecord) {
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            INSERT INTO fw_operation_log(
                id, tenant_id, resource_type, resource_id, action, operator_id, operator_name, trace_id, detail_json, created_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, record.id.value)
            statement.setString(2, record.tenantId.value)
            statement.setString(3, record.resourceType)
            statement.setString(4, record.resourceId.value)
            statement.setString(5, record.action)
            statement.setString(6, record.operatorId?.value)
            statement.setString(7, record.operatorName)
            statement.setString(8, record.traceId?.value)
            statement.setString(9, objectMapper.writeValueAsString(record.details))
            statement.setLong(10, record.createdAt)
            statement.executeUpdate()
        }
    }

    override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<OperationLogRecord> {
        require(resourceType.isNotBlank()) { "Operation resource type must not be blank." }
        require(limit in 1..1000) { "Operation query limit must be between 1 and 1000." }
        return JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            SELECT id, tenant_id, resource_type, resource_id, action, operator_id, operator_name, trace_id, detail_json, created_time
            FROM fw_operation_log
            WHERE tenant_id = ? AND resource_type = ? AND resource_id = ?
            ORDER BY created_time DESC, id DESC LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, resourceType)
            statement.setString(3, resourceId.value)
            statement.setInt(4, limit)
            statement.executeQuery().use { result ->
                val records = ArrayList<OperationLogRecord>()
                while (result.next()) records += OperationLogRecord(
                    id = Identifier(result.getString("id")),
                    tenantId = Identifier(result.getString("tenant_id")),
                    resourceType = result.getString("resource_type"),
                    resourceId = Identifier(result.getString("resource_id")),
                    action = result.getString("action"),
                    operatorId = result.getString("operator_id")?.let(::Identifier),
                    operatorName = result.getString("operator_name"),
                    traceId = result.getString("trace_id")?.let(::Identifier),
                    details = result.getString("detail_json")?.let { objectMapper.readValue(it, STRING_MAP_TYPE) } ?: emptyMap(),
                    createdAt = result.getLong("created_time"),
                )
                records
            }
        }
    }

    private companion object {
        val STRING_MAP_TYPE = object : TypeReference<Map<String, String>>() {}
    }
}
