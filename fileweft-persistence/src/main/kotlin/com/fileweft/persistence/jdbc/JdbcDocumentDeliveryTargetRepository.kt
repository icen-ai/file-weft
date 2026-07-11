package com.fileweft.persistence.jdbc

import com.fileweft.application.delivery.DocumentDeliveryStatus
import com.fileweft.application.delivery.DocumentDeliveryRemovalStatus
import com.fileweft.application.delivery.DocumentDeliveryTarget
import com.fileweft.application.delivery.DocumentDeliveryTargetRepository
import com.fileweft.core.id.Identifier
import com.fileweft.spi.delivery.DeliveryRequirement
import java.sql.ResultSet
import java.time.Clock

class JdbcDocumentDeliveryTargetRepository(
    private val clock: Clock,
) : DocumentDeliveryTargetRepository {
    override fun findById(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget? =
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "$SELECT_COLUMNS FROM fw_document_delivery_target WHERE tenant_id = ? AND id = ?",
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
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            INSERT INTO fw_document_delivery_target(
                id, tenant_id, document_id, profile_id, target_id, target_name, connector_id,
                delivery_requirement, owner_ref, delivery_status, external_id, error_message,
                retry_count, removal_status, removal_error_message, removal_retry_count, delivery_generation, created_time, updated_time
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, document_id, target_id, delivery_generation) DO UPDATE
            SET profile_id = EXCLUDED.profile_id,
                target_name = EXCLUDED.target_name,
                connector_id = EXCLUDED.connector_id,
                delivery_requirement = EXCLUDED.delivery_requirement,
                owner_ref = EXCLUDED.owner_ref,
                delivery_status = EXCLUDED.delivery_status,
                external_id = EXCLUDED.external_id,
                error_message = EXCLUDED.error_message,
                retry_count = EXCLUDED.retry_count,
                removal_status = EXCLUDED.removal_status,
                removal_error_message = EXCLUDED.removal_error_message,
                removal_retry_count = EXCLUDED.removal_retry_count,
                updated_time = EXCLUDED.updated_time
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
            statement.setLong(18, now)
            statement.setLong(19, now)
            statement.executeUpdate()
        }
    }

    private fun map(result: ResultSet): DocumentDeliveryTarget = DocumentDeliveryTarget(
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
    )

    private companion object {
        const val SELECT_COLUMNS = "SELECT id, tenant_id, document_id, profile_id, target_id, target_name, connector_id, delivery_requirement, owner_ref, delivery_status, external_id, error_message, retry_count, removal_status, removal_error_message, removal_retry_count, delivery_generation"
    }
}
