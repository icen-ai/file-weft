package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.core.id.Identifier
import com.fileweft.domain.file.FileAsset
import com.fileweft.domain.file.FileAssetRepository
import java.time.Clock

class JdbcFileAssetRepository(
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : FileAssetRepository {
    override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? {
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "SELECT id, tenant_id, file_id, asset_type, metadata_json FROM fw_asset WHERE tenant_id = ? AND id = ?",
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, fileAssetId.value)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                val metadataJson = result.getString("metadata_json")
                val metadata = if (metadataJson == null) emptyMap() else objectMapper.readValue(metadataJson, STRING_MAP_TYPE)
                return FileAsset(
                    id = Identifier(result.getString("id")),
                    tenantId = Identifier(result.getString("tenant_id")),
                    fileObjectId = Identifier(result.getString("file_id")),
                    assetType = result.getString("asset_type"),
                    metadata = metadata,
                )
            }
        }
    }

    override fun save(fileAsset: FileAsset) {
        val now = clock.millis()
        val metadata = objectMapper.writeValueAsString(fileAsset.metadata)
        val updated = JdbcConnectionContext.requireCurrent().prepareStatement(
            "UPDATE fw_asset SET file_id = ?, asset_type = ?, metadata_json = ?::jsonb, updated_time = ? WHERE tenant_id = ? AND id = ?",
        ).use { statement ->
            statement.setString(1, fileAsset.fileObjectId.value)
            statement.setString(2, fileAsset.assetType)
            statement.setString(3, metadata)
            statement.setLong(4, now)
            statement.setString(5, fileAsset.tenantId.value)
            statement.setString(6, fileAsset.id.value)
            statement.executeUpdate()
        }
        if (updated == 0) {
            JdbcConnectionContext.requireCurrent().prepareStatement(
                "INSERT INTO fw_asset(id, tenant_id, file_id, asset_type, metadata_json, created_time, updated_time) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)",
            ).use { statement ->
                statement.setString(1, fileAsset.id.value)
                statement.setString(2, fileAsset.tenantId.value)
                statement.setString(3, fileAsset.fileObjectId.value)
                statement.setString(4, fileAsset.assetType)
                statement.setString(5, metadata)
                statement.setLong(6, now)
                statement.setLong(7, now)
                statement.executeUpdate()
            }
        }
    }

    private companion object {
        val STRING_MAP_TYPE = object : TypeReference<Map<String, String>>() {}
    }
}
