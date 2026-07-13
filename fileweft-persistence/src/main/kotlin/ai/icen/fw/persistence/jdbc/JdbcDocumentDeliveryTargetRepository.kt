package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.delivery.DocumentDeliveryStatus
import ai.icen.fw.application.delivery.DocumentDeliveryRemovalStatus
import ai.icen.fw.application.delivery.DocumentDeliveryTarget
import ai.icen.fw.application.delivery.DocumentDeliveryTargetMutationRepository
import ai.icen.fw.application.delivery.DeliveryDispatchFence
import ai.icen.fw.application.delivery.DeliveryDispatchOperation
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.delivery.DeliveryRequirement
import java.sql.ResultSet
import java.time.Clock

class JdbcDocumentDeliveryTargetRepository(
    private val clock: Clock,
) : DocumentDeliveryTargetMutationRepository {
    override fun findById(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget? =
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "$SELECT_COLUMNS FROM fw_document_delivery_target WHERE tenant_id = ? AND id = ?",
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, deliveryId.value)
            statement.executeQuery().use { result -> if (result.next()) map(result) else null }
        }

    override fun findForMutation(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget? =
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "$SELECT_COLUMNS FROM fw_document_delivery_target WHERE tenant_id = ? AND id = ? FOR UPDATE",
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, deliveryId.value)
            statement.executeQuery().use { result -> if (result.next()) map(result) else null }
        }

    override fun findByDocument(tenantId: Identifier, documentId: Identifier): List<DocumentDeliveryTarget> =
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "$SELECT_COLUMNS FROM fw_document_delivery_target WHERE tenant_id = ? AND document_id = ? ORDER BY created_time, id",
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, documentId.value)
            statement.executeQuery().use { result ->
                val targets = ArrayList<DocumentDeliveryTarget>()
                while (result.next()) targets += map(result)
                targets
            }
        }

    override fun save(target: DocumentDeliveryTarget) {
        val now = clock.millis()
        val fence = requireNotNull(target.currentDispatchFence) {
            "Delivery target must bind a durable dispatch event before it is persisted."
        }
        val connection = JdbcConnectionContext.requireCurrent()
        val dialect = JdbcConnectionContext.requireDialect()

        // MySQL's ON DUPLICATE KEY UPDATE has no WHERE clause, so enforce the
        // identity guard explicitly before attempting the upsert.
        connection.prepareStatement(IDENTITY_GUARD_SQL).use { statement ->
            statement.setString(1, target.tenantId.value)
            statement.setString(2, target.documentId.value)
            statement.setString(3, target.targetId)
            statement.setInt(4, target.deliveryGeneration)
            statement.executeQuery().use { result ->
                if (result.next()) {
                    check(result.getString("id") == target.id.value) {
                        "Delivery target business identity is already bound to a different persisted id."
                    }
                }
            }
        }

        val updated = connection.prepareStatement(
            """
            INSERT INTO fw_document_delivery_target(
                id, tenant_id, document_id, profile_id, target_id, target_name, connector_id,
                delivery_requirement, owner_ref, delivery_status, external_id, error_message,
                retry_count, removal_status, removal_error_message, removal_retry_count, delivery_generation,
                current_event_id, current_operation, dispatch_sequence, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ${dialect.upsertClause(
                listOf("tenant_id", "document_id", "target_id", "delivery_generation"),
                listOf(
                    "profile_id = ${dialect.excludedColumnReference("profile_id")}",
                    "target_name = ${dialect.excludedColumnReference("target_name")}",
                    "connector_id = ${dialect.excludedColumnReference("connector_id")}",
                    "delivery_requirement = ${dialect.excludedColumnReference("delivery_requirement")}",
                    "owner_ref = ${dialect.excludedColumnReference("owner_ref")}",
                    "delivery_status = ${dialect.excludedColumnReference("delivery_status")}",
                    "external_id = ${dialect.excludedColumnReference("external_id")}",
                    "error_message = ${dialect.excludedColumnReference("error_message")}",
                    "retry_count = ${dialect.excludedColumnReference("retry_count")}",
                    "removal_status = ${dialect.excludedColumnReference("removal_status")}",
                    "removal_error_message = ${dialect.excludedColumnReference("removal_error_message")}",
                    "removal_retry_count = ${dialect.excludedColumnReference("removal_retry_count")}",
                    "current_event_id = ${dialect.excludedColumnReference("current_event_id")}",
                    "current_operation = ${dialect.excludedColumnReference("current_operation")}",
                    "dispatch_sequence = ${dialect.excludedColumnReference("dispatch_sequence")}",
                    "updated_time = ${dialect.excludedColumnReference("updated_time")}",
                ),
            )}
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, target.id.value)
            statement.setString(2, target.tenantId.value)
            statement.setString(3, target.documentId.value)
            statement.setString(4, target.profileId)
            statement.setString(5, target.targetId)
            statement.setString(6, target.displayName)
            statement.setString(7, target.connectorId)
            statement.setString(8, target.requirement.name)
            statement.setString(9, target.ownerRef)
            statement.setString(10, target.status.name)
            statement.setString(11, target.externalId)
            statement.setString(12, target.errorMessage)
            statement.setInt(13, target.retryCount)
            statement.setString(14, target.removalStatus.name)
            statement.setString(15, target.removalErrorMessage)
            statement.setInt(16, target.removalRetryCount)
            statement.setInt(17, target.deliveryGeneration)
            statement.setString(18, fence.eventId.value)
            statement.setString(19, fence.operation.name)
            statement.setLong(20, fence.sequence)
            statement.setLong(21, now)
            statement.setLong(22, now)
            statement.executeUpdate()
        }
        check(updated == 1) {
            "Delivery target business identity is already bound to a different persisted id."
        }
    }

    private fun map(result: ResultSet): DocumentDeliveryTarget =
        DocumentDeliveryTarget(
            id = Identifier(result.getString("id")),
            tenantId = Identifier(result.getString("tenant_id")),
            documentId = Identifier(result.getString("document_id")),
            profileId = result.getString("profile_id"),
            targetId = result.getString("target_id"),
            displayName = result.getString("target_name"),
            connectorId = result.getString("connector_id"),
            requirement = DeliveryRequirement.valueOf(result.getString("delivery_requirement")),
            ownerRef = result.getString("owner_ref"),
            status = DocumentDeliveryStatus.valueOf(result.getString("delivery_status")),
            externalId = result.getString("external_id"),
            errorMessage = result.getString("error_message"),
            retryCount = result.getInt("retry_count"),
            removalStatus = DocumentDeliveryRemovalStatus.valueOf(result.getString("removal_status")),
            removalErrorMessage = result.getString("removal_error_message"),
            removalRetryCount = result.getInt("removal_retry_count"),
            deliveryGeneration = result.getInt("delivery_generation"),
        ).restoreDispatch(
            DeliveryDispatchFence(
                eventId = Identifier(result.getString("current_event_id")),
                operation = DeliveryDispatchOperation.valueOf(result.getString("current_operation")),
                sequence = result.getLong("dispatch_sequence"),
            ),
        )

    private companion object {
        const val SELECT_COLUMNS = "SELECT id, tenant_id, document_id, profile_id, target_id, target_name, connector_id, delivery_requirement, owner_ref, delivery_status, external_id, error_message, retry_count, removal_status, removal_error_message, removal_retry_count, delivery_generation, current_event_id, current_operation, dispatch_sequence"
        const val IDENTITY_GUARD_SQL =
            "SELECT id FROM fw_document_delivery_target WHERE tenant_id = ? AND document_id = ? AND target_id = ? AND delivery_generation = ?"
    }
}
