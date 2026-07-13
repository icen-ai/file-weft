package ai.icen.fw.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.doctor.DocumentDoctorTaskHandler
import ai.icen.fw.application.doctor.DocumentDoctorTaskQueryRepository
import ai.icen.fw.application.doctor.DocumentDoctorTaskView
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.task.BackgroundTaskStatus
import ai.icen.fw.core.id.Identifier
import java.sql.Array as JdbcArray
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/** Exact tenant/document/task projection. Worker payload and error text never cross this read boundary. */
class JdbcDocumentDoctorTaskQueryRepository(
    objectMapper: ObjectMapper,
) : DocumentDoctorTaskQueryRepository {
    private val codec = DoctorReportJsonCodec(objectMapper)
    override fun findTask(
        tenantId: Identifier,
        documentId: Identifier,
        taskId: Identifier,
        folderReadScope: DocumentFolderReadScope?,
    ): DocumentDoctorTaskView? {
        val connection = JdbcConnectionContext.requireCurrent()
        val visibilityArray = connection.createFolderVisibilityArray(folderReadScope)
        return try {
            connection.prepareStatement(querySql(folderReadScope)).use { statement ->
                statement.bind(tenantId, documentId, taskId, visibilityArray)
                statement.executeQuery().use { result ->
                    if (!result.next()) null else map(result, tenantId, documentId, taskId)
                }
            }
        } finally {
            (visibilityArray as? JdbcArray)?.free()
        }
    }

    private fun PreparedStatement.bind(
        tenantId: Identifier,
        documentId: Identifier,
        taskId: Identifier,
        visibilityArray: Any?,
    ) {
        var index = 1
        setString(index++, tenantId.value)
        setString(index++, documentId.value)
        setString(index++, taskId.value)
        setString(index++, DocumentDoctorTaskHandler.TASK_TYPE)
        setFolderVisibilityParameter(index, visibilityArray)
    }

    private fun map(
        result: ResultSet,
        expectedTenantId: Identifier,
        expectedDocumentId: Identifier,
        expectedTaskId: Identifier,
    ): DocumentDoctorTaskView {
        val tenantId = Identifier(result.getString("tenant_id"))
        val documentId = Identifier(result.getString("document_id"))
        val taskId = Identifier(result.getString("task_id"))
        check(
            tenantId == expectedTenantId &&
                documentId == expectedDocumentId &&
                taskId == expectedTaskId
        ) { "Doctor task query returned a row outside the requested scope." }

        val status = parseTaskStatus(result.getString("task_status"))
        val reportJson = result.getString("report_json")
        val report = if (status == BackgroundTaskStatus.SUCCESS && reportJson != null) {
            codec.deserialize(reportJson, tenantId, documentId, result.getString("doctor_status"))
        } else {
            null
        }
        return DocumentDoctorTaskView(
            tenantId = tenantId,
            taskId = taskId,
            documentId = documentId,
            status = status,
            createdTime = result.getLong("created_time"),
            updatedTime = result.getLong("updated_time"),
            report = report,
        )
    }

    private fun parseTaskStatus(value: String): BackgroundTaskStatus = try {
        BackgroundTaskStatus.valueOf(value)
    } catch (failure: IllegalArgumentException) {
        throw IllegalStateException("Stored Doctor task has an unknown status.", failure)
    }

    private fun querySql(folderReadScope: DocumentFolderReadScope?): String = buildString {
        val folderExpression = folderIdExpression()
        append(selectSql(folderExpression))
        when {
            folderReadScope == null -> Unit
            folderReadScope.isEmpty -> append(" AND 1 = 0")
            else -> append(" AND ").append(JdbcConnectionContext.requireDialect().arrayContainsAny(folderExpression, "?"))
        }
    }

    private fun folderIdExpression(): String =
        "COALESCE(NULLIF(${JdbcConnectionContext.requireDialect().jsonExtractText("asset.metadata_json", "catalog.folder-id")}, ''), 'inbox')"

    private fun Connection.createFolderVisibilityArray(folderReadScope: DocumentFolderReadScope?): Any? =
        folderReadScope
            ?.takeIf { scope -> !scope.isEmpty }
            ?.let { scope -> JdbcConnectionContext.requireDialect().createStringArrayParameter(this, scope.folderIds) }

    private fun PreparedStatement.setFolderVisibilityParameter(index: Int, parameter: Any?) {
        when (parameter) {
            is JdbcArray -> setArray(index, parameter)
            is String -> setString(index, parameter)
            null -> Unit
            else -> throw IllegalArgumentException("Unsupported folder visibility parameter type ${parameter.javaClass.name}")
        }
    }

    private companion object {
        private fun selectSql(folderExpression: String): String = """
            SELECT task.tenant_id,
                   task.id AS task_id,
                   task.business_id AS document_id,
                   task.task_status,
                   task.created_time,
                   task.updated_time,
                   report.doctor_status,
                   ${JdbcConnectionContext.requireDialect().jsonToText("report.report_json")} AS report_json
            FROM fw_task task
            JOIN fw_document document
              ON document.tenant_id = task.tenant_id
             AND document.id = task.business_id
            JOIN fw_asset asset
              ON asset.tenant_id = document.tenant_id
             AND asset.id = document.asset_id
            LEFT JOIN fw_doctor_record report
              ON report.tenant_id = task.tenant_id
             AND report.document_id = task.business_id
             AND report.task_id = task.id
            WHERE task.tenant_id = ?
              AND task.business_id = ?
              AND task.id = ?
              AND task.task_type = ?
        """
    }
}
