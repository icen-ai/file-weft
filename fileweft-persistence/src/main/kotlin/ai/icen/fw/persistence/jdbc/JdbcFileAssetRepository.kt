package ai.icen.fw.persistence.jdbc

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetMutationRepository
import java.sql.ResultSet
import java.time.Clock

class JdbcFileAssetRepository(
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : FileAssetMutationRepository {
    override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? =
        find(tenantId, fileAssetId, FIND_BY_ID_SQL)

    override fun findForMutation(tenantId: Identifier, fileAssetId: Identifier): FileAsset? =
        find(tenantId, fileAssetId, FIND_FOR_MUTATION_SQL)

    private fun find(
        tenantId: Identifier,
        fileAssetId: Identifier,
        sql: String,
    ): FileAsset? = JdbcConnectionContext.requireCurrent().prepareStatement(sql).use { statement ->
        statement.setString(1, tenantId.value)
        statement.setString(2, fileAssetId.value)
        statement.executeQuery().use { result ->
            if (result.next()) mapAsset(result) else null
        }
    }

    private fun mapAsset(result: ResultSet): FileAsset {
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
        const val FIND_BY_ID_SQL =
            "SELECT id, tenant_id, file_id, asset_type, metadata_json FROM fw_asset WHERE tenant_id = ? AND id = ?"
        const val FIND_FOR_MUTATION_SQL = FIND_BY_ID_SQL + " FOR UPDATE"
        val STRING_MAP_TYPE = object : TypeReference<Map<String, String>>() {}
    }
}
