package ai.icen.fw.persistence.jdbc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.agent.AgentResultRepository
import ai.icen.fw.application.agent.PersistedAgentResult
import ai.icen.fw.application.agent.PersistedAgentSuggestionConfirmation
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.ai.AgentCapability
import ai.icen.fw.spi.ai.AgentExecutionStatus
import ai.icen.fw.spi.ai.AgentResult
import ai.icen.fw.spi.ai.AgentSuggestion
import java.sql.ResultSet
import java.time.Clock

/** PostgreSQL persistence for tenant-isolated agent evidence and confirmations. */
class JdbcAgentResultRepository(
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : AgentResultRepository {
    override fun save(result: PersistedAgentResult) {
        val now = clock.millis()
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            INSERT INTO fw_agent_result(
                id, tenant_id, task_id, capability, source_event_id, source_event_type,
                result_status, result_json, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
            ON CONFLICT (tenant_id, task_id) DO UPDATE
            SET capability = EXCLUDED.capability,
                source_event_id = EXCLUDED.source_event_id,
                source_event_type = EXCLUDED.source_event_type,
                result_status = EXCLUDED.result_status,
                result_json = EXCLUDED.result_json,
                updated_time = EXCLUDED.updated_time
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, result.id.value)
            statement.setString(2, result.tenantId.value)
            statement.setString(3, result.taskId.value)
            statement.setString(4, result.capability.name)
            statement.setString(5, result.sourceEventId.value)
            statement.setString(6, result.sourceEventType)
            statement.setString(7, result.result.status.name)
            statement.setString(8, serialize(result.result))
            statement.setLong(9, result.createdAt)
            statement.setLong(10, now)
            statement.executeUpdate()
        }
    }

    override fun findByTask(tenantId: Identifier, taskId: Identifier): PersistedAgentResult? =
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            SELECT id, tenant_id, task_id, capability, source_event_id, source_event_type, result_json, created_time
            FROM fw_agent_result
            WHERE tenant_id = ? AND task_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, taskId.value)
            statement.executeQuery().use { rows -> if (rows.next()) mapResult(rows) else null }
        }

    override fun saveConfirmation(
        confirmation: PersistedAgentSuggestionConfirmation,
    ): PersistedAgentSuggestionConfirmation {
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            INSERT INTO fw_agent_suggestion_confirmation(
                id, tenant_id, task_id, suggestion_id, confirmed_by, confirmed_time, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, task_id, suggestion_id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, confirmation.id.value)
            statement.setString(2, confirmation.tenantId.value)
            statement.setString(3, confirmation.taskId.value)
            statement.setString(4, confirmation.suggestionId.value)
            statement.setString(5, confirmation.confirmedBy.value)
            statement.setLong(6, confirmation.confirmedAt)
            statement.setLong(7, confirmation.confirmedAt)
            statement.setLong(8, confirmation.confirmedAt)
            statement.executeUpdate()
        }
        return JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            SELECT id, tenant_id, task_id, suggestion_id, confirmed_by, confirmed_time
            FROM fw_agent_suggestion_confirmation
            WHERE tenant_id = ? AND task_id = ? AND suggestion_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, confirmation.tenantId.value)
            statement.setString(2, confirmation.taskId.value)
            statement.setString(3, confirmation.suggestionId.value)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "Agent suggestion confirmation was not persisted." }
                mapConfirmation(rows)
            }
        }
    }

    override fun findConfirmations(
        tenantId: Identifier,
        taskId: Identifier,
    ): List<PersistedAgentSuggestionConfirmation> =
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            SELECT id, tenant_id, task_id, suggestion_id, confirmed_by, confirmed_time
            FROM fw_agent_suggestion_confirmation
            WHERE tenant_id = ? AND task_id = ?
            ORDER BY confirmed_time, id
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, taskId.value)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(mapConfirmation(rows))
                }
            }
        }

    private fun mapResult(rows: ResultSet): PersistedAgentResult = PersistedAgentResult(
        id = Identifier(rows.getString("id")),
        tenantId = Identifier(rows.getString("tenant_id")),
        taskId = Identifier(rows.getString("task_id")),
        capability = AgentCapability.valueOf(rows.getString("capability")),
        sourceEventId = Identifier(rows.getString("source_event_id")),
        sourceEventType = rows.getString("source_event_type"),
        result = deserialize(rows.getString("result_json")),
        createdAt = rows.getLong("created_time"),
    )

    private fun mapConfirmation(rows: ResultSet): PersistedAgentSuggestionConfirmation = PersistedAgentSuggestionConfirmation(
        id = Identifier(rows.getString("id")),
        tenantId = Identifier(rows.getString("tenant_id")),
        taskId = Identifier(rows.getString("task_id")),
        suggestionId = Identifier(rows.getString("suggestion_id")),
        confirmedBy = Identifier(rows.getString("confirmed_by")),
        confirmedAt = rows.getLong("confirmed_time"),
    )

    private fun serialize(result: AgentResult): String {
        val root = objectMapper.createObjectNode()
        root.put("taskId", result.taskId.value)
        root.put("status", result.status.name)
        root.put("completedAt", result.completedAt)
        result.message?.let { root.put("message", it) }
        val suggestions = root.putArray("suggestions")
        result.suggestions.forEach { suggestion ->
            suggestions.addObject().also { node ->
                node.put("id", suggestion.id.value)
                node.put("type", suggestion.type)
                suggestion.explanation?.let { node.put("explanation", it) }
                node.put("confirmationRequired", suggestion.confirmationRequired)
                val payload = node.putObject("payload")
                suggestion.payload.forEach { (key, value) -> payload.put(key, value) }
            }
        }
        return objectMapper.writeValueAsString(root)
    }

    private fun deserialize(raw: String): AgentResult {
        val root = objectMapper.readTree(raw)
        val suggestions = root.path("suggestions").map(::deserializeSuggestion)
        return AgentResult(
            taskId = Identifier(requiredText(root, "taskId")),
            status = AgentExecutionStatus.valueOf(requiredText(root, "status")),
            suggestions = suggestions,
            message = root.path("message").takeUnless(JsonNode::isMissingNode)?.takeUnless(JsonNode::isNull)?.asText(),
            completedAt = root.path("completedAt").longValue(),
        )
    }

    private fun deserializeSuggestion(node: JsonNode): AgentSuggestion = AgentSuggestion(
        id = Identifier(requiredText(node, "id")),
        type = requiredText(node, "type"),
        payload = node.path("payload").fields().asSequence().associate { it.key to it.value.asText() },
        explanation = node.path("explanation").takeUnless(JsonNode::isMissingNode)?.takeUnless(JsonNode::isNull)?.asText(),
        confirmationRequired = node.path("confirmationRequired").asBoolean(false),
    )

    private fun requiredText(node: JsonNode, field: String): String = node.path(field).asText().also {
        require(it.isNotBlank()) { "Persisted agent result field $field must not be blank." }
    }
}
