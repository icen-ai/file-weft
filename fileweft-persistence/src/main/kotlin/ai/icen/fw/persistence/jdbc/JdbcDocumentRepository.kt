package ai.icen.fw.persistence.jdbc

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentMutationRepository
import ai.icen.fw.domain.document.DocumentNumberAlreadyExistsException
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.document.LifecycleState
import java.sql.ResultSet
import java.time.Clock

class JdbcDocumentRepository(
    private val clock: Clock,
) : DocumentMutationRepository {
    override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
        find(
            tenantId,
            "SELECT id, tenant_id, asset_id, doc_no, title, lifecycle_state, current_version_id, delivery_generation FROM fw_document WHERE tenant_id = ? AND id = ?",
        ) { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, documentId.value)
        }

    override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? =
        find(
            tenantId,
            "SELECT id, tenant_id, asset_id, doc_no, title, lifecycle_state, current_version_id, delivery_generation FROM fw_document WHERE tenant_id = ? AND id = ? FOR UPDATE",
        ) { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, documentId.value)
        }

    override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? =
        find(
            tenantId,
            "SELECT id, tenant_id, asset_id, doc_no, title, lifecycle_state, current_version_id, delivery_generation FROM fw_document WHERE tenant_id = ? AND doc_no = ?",
        ) { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, documentNumber)
        }

    override fun save(document: Document) {
        val connection = JdbcConnectionContext.requireCurrent()
        val now = clock.millis()
        val updated = connection.prepareStatement(
            "UPDATE fw_document SET asset_id = ?, doc_no = ?, title = ?, lifecycle_state = ?, current_version_id = ?, delivery_generation = ?, updated_time = ? WHERE tenant_id = ? AND id = ?",
        ).use { statement ->
            bindDocument(statement, document, now, false)
            statement.executeUpdate()
        }
        if (updated == 0) {
            try {
                connection.prepareStatement(
                    "INSERT INTO fw_document(id, tenant_id, asset_id, doc_no, title, lifecycle_state, current_version_id, delivery_generation, created_time, updated_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { statement ->
                    bindDocument(statement, document, now, true)
                    statement.executeUpdate()
                }
            } catch (failure: java.sql.SQLException) {
                if (failure.isDocumentNumberUniqueViolation()) {
                    throw DocumentNumberAlreadyExistsException(document.documentNumber)
                }
                throw failure
            }
        }
        document.versions.forEach { version -> saveVersion(version, now) }
    }

    private fun loadVersions(tenantId: Identifier, documentId: Identifier): List<DocumentVersion> {
        val connection = JdbcConnectionContext.requireCurrent()
        connection.prepareStatement(
            "SELECT id, tenant_id, document_id, version_no, file_id FROM fw_document_version WHERE tenant_id = ? AND document_id = ? ORDER BY created_time, version_no",
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, documentId.value)
            statement.executeQuery().use { result ->
                val versions = ArrayList<DocumentVersion>()
                while (result.next()) versions.add(mapVersion(result))
                return versions
            }
        }
    }

    private fun find(
        tenantId: Identifier,
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): Document? = JdbcConnectionContext.requireCurrent().prepareStatement(sql).use { statement ->
        bind(statement)
        statement.executeQuery().use { result ->
            if (!result.next()) return null
            val documentId = Identifier(result.getString("id"))
            mapDocument(result, loadVersions(tenantId, documentId))
        }
    }

    private fun saveVersion(version: DocumentVersion, now: Long) {
        val connection = JdbcConnectionContext.requireCurrent()
        val dialect = JdbcConnectionContext.requireDialect()
        connection.prepareStatement(
            "INSERT INTO fw_document_version(id, tenant_id, document_id, version_no, file_id, status, created_time, updated_time) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?) ${dialect.upsertClause(listOf("id"), emptyList())}",
        ).use { statement ->
            statement.setString(1, version.id.value)
            statement.setString(2, version.tenantId.value)
            statement.setString(3, version.documentId.value)
            statement.setString(4, version.versionNumber)
            statement.setString(5, version.fileObjectId.value)
            statement.setLong(6, now)
            statement.setLong(7, now)
            statement.executeUpdate()
        }
    }

    private fun bindDocument(statement: java.sql.PreparedStatement, document: Document, now: Long, insert: Boolean) {
        if (insert) {
            statement.setString(1, document.id.value)
            statement.setString(2, document.tenantId.value)
            statement.setString(3, document.assetId.value)
            statement.setString(4, document.documentNumber)
            statement.setString(5, document.title)
            statement.setString(6, document.lifecycleState.name)
            statement.setString(7, document.currentVersionId?.value)
            statement.setInt(8, document.deliveryGeneration)
            statement.setLong(9, now)
            statement.setLong(10, now)
        } else {
            statement.setString(1, document.assetId.value)
            statement.setString(2, document.documentNumber)
            statement.setString(3, document.title)
            statement.setString(4, document.lifecycleState.name)
            statement.setString(5, document.currentVersionId?.value)
            statement.setInt(6, document.deliveryGeneration)
            statement.setLong(7, now)
            statement.setString(8, document.tenantId.value)
            statement.setString(9, document.id.value)
        }
    }

    private fun mapDocument(result: ResultSet, versions: List<DocumentVersion>): Document = Document(
        id = Identifier(result.getString("id")),
        tenantId = Identifier(result.getString("tenant_id")),
        assetId = Identifier(result.getString("asset_id")),
        documentNumber = result.getString("doc_no"),
        title = result.getString("title"),
        lifecycleState = LifecycleState.valueOf(result.getString("lifecycle_state")),
        versions = versions,
        currentVersionId = result.getString("current_version_id")?.let(::Identifier),
        deliveryGeneration = result.getInt("delivery_generation"),
    )

    private fun mapVersion(result: ResultSet): DocumentVersion = DocumentVersion(
        id = Identifier(result.getString("id")),
        tenantId = Identifier(result.getString("tenant_id")),
        documentId = Identifier(result.getString("document_id")),
        versionNumber = result.getString("version_no"),
        fileObjectId = Identifier(result.getString("file_id")),
    )

    private fun java.sql.SQLException.isDocumentNumberUniqueViolation(): Boolean {
        val constraintName = when (sqlState) {
            POSTGRESQL_UNIQUE_VIOLATION_SQL_STATE -> POSTGRESQL_DOCUMENT_NUMBER_UNIQUE_CONSTRAINT
            MYSQL_INTEGRITY_CONSTRAINT_SQL_STATE -> {
                if (errorCode != MYSQL_DUPLICATE_ENTRY_ERROR_CODE) return false
                MYSQL_DOCUMENT_NUMBER_UNIQUE_CONSTRAINT
            }
            else -> return false
        }
        val failureMessage = message ?: return false
        return when (sqlState) {
            POSTGRESQL_UNIQUE_VIOLATION_SQL_STATE ->
                failureMessage.contains(constraintName, ignoreCase = true)
            MYSQL_INTEGRITY_CONSTRAINT_SQL_STATE ->
                MYSQL_DOCUMENT_NUMBER_DUPLICATE_KEY.containsMatchIn(failureMessage)
            else -> false
        }
    }

    private companion object {
        const val POSTGRESQL_UNIQUE_VIOLATION_SQL_STATE = "23505"
        const val POSTGRESQL_DOCUMENT_NUMBER_UNIQUE_CONSTRAINT = "fw_document_tenant_id_doc_no_key"
        const val MYSQL_INTEGRITY_CONSTRAINT_SQL_STATE = "23000"
        const val MYSQL_DUPLICATE_ENTRY_ERROR_CODE = 1062
        // V001 intentionally keeps MySQL's original unnamed UNIQUE key so its
        // released Flyway checksum stays stable. MySQL names that key after
        // its first column and reports it as either `tenant_id` or
        // `<table>.tenant_id` in duplicate-entry errors.
        const val MYSQL_DOCUMENT_NUMBER_UNIQUE_CONSTRAINT = "tenant_id"
        val MYSQL_DOCUMENT_NUMBER_DUPLICATE_KEY = Regex(
            """\bfor\s+key\s+['`](?:[^'`]+\.)?${MYSQL_DOCUMENT_NUMBER_UNIQUE_CONSTRAINT}['`]""",
            RegexOption.IGNORE_CASE,
        )
    }
}
