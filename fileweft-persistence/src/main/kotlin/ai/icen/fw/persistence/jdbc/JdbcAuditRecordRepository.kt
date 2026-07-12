package ai.icen.fw.persistence.jdbc

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.audit.AuditRecord
import ai.icen.fw.domain.audit.AuditRecordRepository

class JdbcAuditRecordRepository(
    private val objectMapper: ObjectMapper,
) : AuditRecordRepository {
    override fun append(record: AuditRecord) {
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "INSERT INTO fw_audit_record(id, tenant_id, resource_type, resource_id, action, operator_id, operator_name, detail_json, created_time, updated_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
        ).use { statement ->
            statement.setString(1, record.id.value)
            statement.setString(2, record.tenantId.value)
            statement.setString(3, record.resourceType)
            statement.setString(4, record.resourceId.value)
            statement.setString(5, record.action)
            statement.setString(6, record.operatorId?.value)
            statement.setString(7, record.operatorName)
            statement.setString(8, objectMapper.writeValueAsString(record.details))
            statement.setLong(9, record.createdAt)
            statement.setLong(10, record.createdAt)
            statement.executeUpdate()
        }
    }

    override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord> {
        require(resourceType.isNotBlank()) { "Audit resource type must not be blank." }
        require(limit > 0) { "Audit query limit must be positive." }
        return JdbcConnectionContext.requireCurrent().prepareStatement(
            "SELECT id, tenant_id, resource_type, resource_id, action, operator_id, operator_name, detail_json, created_time FROM fw_audit_record WHERE tenant_id = ? AND resource_type = ? AND resource_id = ? ORDER BY created_time DESC, id DESC LIMIT ?",
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, resourceType)
            statement.setString(3, resourceId.value)
            statement.setInt(4, limit)
            statement.executeQuery().use { result ->
                val records = ArrayList<AuditRecord>()
                while (result.next()) records += AuditRecord(
                    id = Identifier(result.getString("id")),
                    tenantId = Identifier(result.getString("tenant_id")),
                    resourceType = result.getString("resource_type"),
                    resourceId = Identifier(result.getString("resource_id")),
                    action = result.getString("action"),
                    operatorId = result.getString("operator_id")?.let(::Identifier),
                    details = result.getString("detail_json")?.let { objectMapper.readValue(it, STRING_MAP_TYPE) } ?: emptyMap(),
                    createdAt = result.getLong("created_time"),
                    operatorName = result.getString("operator_name"),
                )
                records
            }
        }
    }

    private companion object {
        val STRING_MAP_TYPE = object : TypeReference<Map<String, String>>() {}
    }
}
