package ai.icen.fw.persistence.jdbc

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import java.time.Clock

class JdbcFileObjectRepository(
    private val clock: Clock,
) : FileObjectRepository {
    override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? {
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "SELECT id, tenant_id, file_name, file_size, storage_type, storage_path, content_type, content_hash FROM fw_file_object WHERE tenant_id = ? AND id = ?",
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, fileObjectId.value)
            statement.executeQuery().use { result ->
                if (!result.next()) return null
                return FileObject(
                    id = Identifier(result.getString("id")),
                    tenantId = Identifier(result.getString("tenant_id")),
                    fileName = result.getString("file_name"),
                    contentLength = result.getLong("file_size"),
                    storageType = result.getString("storage_type"),
                    storagePath = result.getString("storage_path"),
                    contentType = result.getString("content_type"),
                    contentHash = result.getString("content_hash"),
                )
            }
        }
    }

    override fun save(fileObject: FileObject) {
        val now = clock.millis()
        val updated = JdbcConnectionContext.requireCurrent().prepareStatement(
            "UPDATE fw_file_object SET file_name = ?, content_type = ?, file_size = ?, content_hash = ?, storage_type = ?, storage_path = ?, updated_time = ? WHERE tenant_id = ? AND id = ?",
        ).use { statement ->
            statement.setString(1, fileObject.fileName)
            statement.setString(2, fileObject.contentType)
            statement.setLong(3, fileObject.contentLength)
            statement.setString(4, fileObject.contentHash)
            statement.setString(5, fileObject.storageType)
            statement.setString(6, fileObject.storagePath)
            statement.setLong(7, now)
            statement.setString(8, fileObject.tenantId.value)
            statement.setString(9, fileObject.id.value)
            statement.executeUpdate()
        }
        if (updated == 0) {
            JdbcConnectionContext.requireCurrent().prepareStatement(
                "INSERT INTO fw_file_object(id, tenant_id, file_name, content_type, file_size, content_hash, storage_type, storage_path, status, created_time, updated_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
            ).use { statement ->
                statement.setString(1, fileObject.id.value)
                statement.setString(2, fileObject.tenantId.value)
                statement.setString(3, fileObject.fileName)
                statement.setString(4, fileObject.contentType)
                statement.setLong(5, fileObject.contentLength)
                statement.setString(6, fileObject.contentHash)
                statement.setString(7, fileObject.storageType)
                statement.setString(8, fileObject.storagePath)
                statement.setLong(9, now)
                statement.setLong(10, now)
                statement.executeUpdate()
            }
        }
    }
}
