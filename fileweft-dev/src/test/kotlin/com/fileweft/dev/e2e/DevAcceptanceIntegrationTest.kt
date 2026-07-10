package com.fileweft.dev.e2e

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * Docker Compose acceptance test for the development API, RustFS and downstream
 * platform. Start the .docker stack first, then run with
 * FILEWEFT_RUN_DEV_E2E=true ./gradlew :fileweft-dev:test.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DevAcceptanceIntegrationTest {
    private val mapper = jacksonObjectMapper()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val apiUrl = System.getenv("FILEWEFT_DEV_E2E_API_URL") ?: "http://127.0.0.1:8080"
    private val platformUrl = System.getenv("FILEWEFT_DEV_E2E_PLATFORM_URL") ?: "http://127.0.0.1:8081"

    @BeforeAll
    fun requireComposeStack() {
        assumeTrue(
            System.getenv("FILEWEFT_RUN_DEV_E2E") == "true",
            "Set FILEWEFT_RUN_DEV_E2E=true after starting docker compose -f .docker/docker-compose.dev.yaml up --wait.",
        )
        assertEquals("UP", getJson("$apiUrl/api/health").path("status").asText())
        assertEquals("UP", getJson("$platformUrl/platform/v1/health").path("status").asText())
    }

    @AfterEach
    fun restoreDevelopmentPlatform() {
        listOf("default", "compliance", "collaboration", "search").forEach { targetId ->
            postJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"AVAILABLE","targetId":"$targetId"}""", null)
        }
    }

    @Test
    fun `returns a role scoped capability surface for the proof lab`() {
        val editor = loginResponse("editor@alpha", "dev-editor")
        val reviewer = loginResponse("reviewer@alpha", "dev-reviewer")
        val viewer = loginResponse("viewer@alpha", "dev-viewer")
        val admin = loginResponse("admin@alpha", "dev-admin")

        assertTrue(editor.path("permissions").any { it.asText() == "document:create" })
        assertTrue(editor.path("permissions").none { it.asText() == "document:audit" })
        assertTrue(reviewer.path("permissions").any { it.asText() == "document:audit" })
        assertTrue(reviewer.path("permissions").none { it.asText() == "document:create" })
        assertEquals(listOf("document:read"), viewer.path("permissions").map { it.asText() })
        assertTrue(admin.path("permissions").any { it.asText() == "system:outbox:process" })
    }

    @Test
    fun `returns a conflict when an editor reuses a document number`() {
        val editor = login("editor@alpha", "dev-editor")
        val documentNumber = "E2E-DUPLICATE-${UUID.randomUUID().toString().take(8)}"
        val first = createDraft(editor, documentNumber)
        assertTrue(first.path("document").path("id").asText().isNotBlank())

        val duplicate = uploadFileResponse(
            "$apiUrl/api/documents",
            mapOf("documentNumber" to documentNumber, "title" to "Duplicate document number"),
            editor,
        )

        assertEquals(409, duplicate.statusCode())
        assertEquals("DOCUMENT_NUMBER_CONFLICT", mapper.readTree(duplicate.body()).path("code").asText())
    }

    @Test
    fun `mirrors document audit evidence into operation history with the request trace`() {
        val editor = login("editor@alpha", "dev-editor")
        val traceId = "e2e-operation-${UUID.randomUUID().toString().take(12)}"
        val documentId = createDraft(
            editor,
            "E2E-TRACE-${UUID.randomUUID().toString().take(12)}",
            traceId = traceId,
        ).path("document").path("id").asText()

        val detail = getJson("$apiUrl/api/documents/$documentId", editor)
        val audit = detail.path("audits").first { it.path("action").asText() == "document:create" }
        val operation = detail.path("operationLogs").first { it.path("action").asText() == "document:create" }
        assertEquals(audit.path("id").asText(), operation.path("id").asText())
        assertEquals(traceId, operation.path("traceId").asText())
        assertEquals("alpha-editor", operation.path("operatorId").asText())
    }

    @Test
    fun `keeps catalog folders and bound documents isolated between tenants`() {
        val alphaEditor = login("editor@alpha", "dev-editor")
        val betaEditor = login("editor@beta", "dev-editor")
        val alphaNumber = "E2E-ALPHA-${UUID.randomUUID().toString().take(8)}"
        val betaNumber = "E2E-BETA-${UUID.randomUUID().toString().take(8)}"
        val alphaDocument = createDraft(alphaEditor, alphaNumber, "contracts").path("document").path("id").asText()
        val betaDocument = createDraft(betaEditor, betaNumber, "projects").path("document").path("id").asText()

        val alphaFolders = getJson("$apiUrl/api/catalog/folders", alphaEditor)
        val betaFolders = getJson("$apiUrl/api/catalog/folders", betaEditor)
        assertTrue(alphaFolders.any { it.path("id").asText() == "contracts" })
        assertTrue(alphaFolders.none { it.path("id").asText() == "projects" })
        assertTrue(betaFolders.any { it.path("id").asText() == "projects" })
        assertTrue(betaFolders.none { it.path("id").asText() == "contracts" })

        val alphaDocuments = getJson("$apiUrl/api/documents?limit=100", alphaEditor)
        val betaDocuments = getJson("$apiUrl/api/documents?limit=100", betaEditor)
        assertEquals("contracts", alphaDocuments.first { it.path("id").asText() == alphaDocument }.path("folderId").asText())
        assertEquals("projects", betaDocuments.first { it.path("id").asText() == betaDocument }.path("folderId").asText())
        assertTrue(alphaDocuments.none { it.path("id").asText() == betaDocument })
        assertTrue(betaDocuments.none { it.path("id").asText() == alphaDocument })

        val crossTenantDetail = client.send(
            HttpRequest.newBuilder(URI("$apiUrl/api/documents/$betaDocument"))
                .header("Authorization", "Bearer $alphaEditor")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        assertEquals(404, crossTenantDetail.statusCode())
    }

    @Test
    fun `uploads reviews and delivers a document to every regulated downstream target`() {
        val editor = login("editor@alpha", "dev-editor")
        val documentNumber = "E2E-${UUID.randomUUID().toString().take(12)}"
        val documentId = createDraft(editor, documentNumber).path("document").path("id").asText()
        assertTrue(documentId.isNotBlank())

        val workflow = postJson(
            "$apiUrl/api/documents/$documentId/submit",
            """{"reviewerId":"alpha-reviewer"}""",
            editor,
        )
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        postJson(
            "$apiUrl/api/documents/workflows/${workflow.path("workflowId").asText()}/tasks/${workflow.path("taskId").asText()}/approve",
            """{"comment":"Compose acceptance approved","deliveryProfileId":"regulated"}""",
            reviewer,
        )
        val admin = login("admin@alpha", "dev-admin")
        post("$apiUrl/api/outbox/process?limit=20", null, admin, "application/json")

        val detail = awaitPublished(documentId, admin)
        assertEquals("PUBLISHED", detail.path("document").path("lifecycleState").asText())
        assertEquals(3, detail.path("deliveries").size())
        assertTrue(detail.path("deliveries").all { it.path("status").asText() == "SUCCEEDED" })
        assertEquals(3, detail.path("outboxEvents").count { it.path("status").asText() == "SUCCESS" })
        assertAuditActor(detail, "document:create", "alpha-editor", "Alpha 编辑者")
        assertAuditActor(detail, "document:review:submit", "alpha-editor", "Alpha 编辑者")
        assertAuditActor(detail, "document:review:approve", "alpha-reviewer", "Alpha 审批者")
        assertEquals(3, detail.path("audits").count { it.path("action").asText() == "document:delivery:succeeded" })

        listOf("compliance", "collaboration", "search").forEach { targetId ->
            val mirror = getPlatform(targetId, documentId)
            assertEquals("$targetId:alpha:$documentId", mirror.path("externalId").asText())
            assertEquals("acceptance.txt", mirror.path("fileName").asText())
            assertTrue(mirror.path("downloadedBytes").asLong() > 0)
        }
    }

    @Test
    fun `updates versions rejects review and returns a document to draft`() {
        val editor = login("editor@alpha", "dev-editor")
        val documentId = createDraft(editor, "E2E-${UUID.randomUUID().toString().take(12)}").path("document").path("id").asText()
        val renamed = requestJson(
            "PATCH",
            "$apiUrl/api/documents/$documentId",
            """{"title":"已重命名的验收文档"}""",
            editor,
        )
        assertEquals("已重命名的验收文档", renamed.path("document").path("title").asText())
        val withVersion = addVersion(editor, documentId, "1.1")
        assertEquals(2, withVersion.path("versions").size())

        val workflow = postJson(
            "$apiUrl/api/documents/$documentId/submit",
            """{"reviewerId":"alpha-reviewer"}""",
            editor,
        )
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        postJson(
            "$apiUrl/api/documents/workflows/${workflow.path("workflowId").asText()}/tasks/${workflow.path("taskId").asText()}/reject",
            """{"comment":"需要修订"}""",
            reviewer,
        )
        assertEquals("REJECTED", getJson("$apiUrl/api/documents/$documentId", editor).path("document").path("lifecycleState").asText())

        val revised = post("$apiUrl/api/documents/$documentId/revise", null, editor, "application/json")
        assertEquals("DRAFT", revised.path("document").path("lifecycleState").asText())
        assertAuditActor(revised, "document:rename", "alpha-editor", "Alpha 编辑者")
        assertAuditActor(revised, "document:version:add", "alpha-editor", "Alpha 编辑者")
        assertAuditActor(revised, "document:review:reject", "alpha-reviewer", "Alpha 审批者")
        assertAuditActor(revised, "document:revise", "alpha-editor", "Alpha 编辑者")
    }

    @Test
    fun `retries a retryable downstream failure after the platform recovers`() {
        val editor = login("editor@alpha", "dev-editor")
        val documentId = createDraft(editor, "E2E-${UUID.randomUUID().toString().take(12)}").path("document").path("id").asText()
        val workflow = postJson(
            "$apiUrl/api/documents/$documentId/submit",
            """{"reviewerId":"alpha-reviewer"}""",
            editor,
        )
        postJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"RETRYABLE_FAILURE","targetId":"compliance"}""", null)
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        postJson(
            "$apiUrl/api/documents/workflows/${workflow.path("workflowId").asText()}/tasks/${workflow.path("taskId").asText()}/approve",
            """{"comment":"触发可重试故障","deliveryProfileId":"regulated"}""",
            reviewer,
        )
        val admin = login("admin@alpha", "dev-admin")
        post("$apiUrl/api/outbox/process?limit=20", null, admin, "application/json")
        awaitLifecycle(documentId, admin, "SYNC_ERROR")

        postJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"AVAILABLE","targetId":"compliance"}""", null)
        Thread.sleep(10_500)
        post("$apiUrl/api/outbox/process?limit=20", null, admin, "application/json")
        val recovered = awaitPublished(documentId, admin)
        assertTrue(recovered.path("deliveries").any { it.path("targetId").asText() == "compliance" && it.path("status").asText() == "SUCCEEDED" })
    }

    @Test
    fun `keeps a regulated document published when only its optional search target fails`() {
        val editor = login("editor@alpha", "dev-editor")
        val documentId = createDraft(editor, "E2E-${UUID.randomUUID().toString().take(12)}").path("document").path("id").asText()
        val workflow = postJson(
            "$apiUrl/api/documents/$documentId/submit",
            """{"reviewerId":"alpha-reviewer"}""",
            editor,
        )
        postJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"PERMANENT_FAILURE","targetId":"search"}""", null)
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        postJson(
            "$apiUrl/api/documents/workflows/${workflow.path("workflowId").asText()}/tasks/${workflow.path("taskId").asText()}/approve",
            """{"comment":"验证可选下游失败","deliveryProfileId":"regulated"}""",
            reviewer,
        )
        val admin = login("admin@alpha", "dev-admin")
        post("$apiUrl/api/outbox/process?limit=20", null, admin, "application/json")

        val detail = awaitPublished(documentId, admin)
        assertEquals("SUCCEEDED", delivery(detail, "compliance").path("status").asText())
        assertEquals("SUCCEEDED", delivery(detail, "collaboration").path("status").asText())
        assertEquals("FAILED", delivery(detail, "search").path("status").asText())
        assertTrue(delivery(detail, "search").path("errorMessage").asText().isNotBlank())
    }

    @Test
    fun `allows an administrator to manually requeue a permanently failed required target`() {
        val editor = login("editor@alpha", "dev-editor")
        val documentId = createDraft(editor, "E2E-${UUID.randomUUID().toString().take(12)}").path("document").path("id").asText()
        val workflow = postJson(
            "$apiUrl/api/documents/$documentId/submit",
            """{"reviewerId":"alpha-reviewer"}""",
            editor,
        )
        postJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"PERMANENT_FAILURE","targetId":"compliance"}""", null)
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        postJson(
            "$apiUrl/api/documents/workflows/${workflow.path("workflowId").asText()}/tasks/${workflow.path("taskId").asText()}/approve",
            """{"comment":"验证必达下游人工恢复","deliveryProfileId":"regulated"}""",
            reviewer,
        )
        val admin = login("admin@alpha", "dev-admin")
        post("$apiUrl/api/outbox/process?limit=20", null, admin, "application/json")
        val failed = awaitLifecycle(documentId, admin, "SYNC_ERROR")
        val compliance = delivery(failed, "compliance")
        assertEquals("FAILED", compliance.path("status").asText())

        postJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"AVAILABLE","targetId":"compliance"}""", null)
        post("$apiUrl/api/documents/delivery-targets/${compliance.path("id").asText()}/retry", null, admin, "application/json")
        post("$apiUrl/api/outbox/process?limit=20", null, admin, "application/json")
        val recovered = awaitPublished(documentId, admin)
        assertEquals("SUCCEEDED", delivery(recovered, "compliance").path("status").asText())
    }

    @Test
    fun `queues and persists an asynchronous Doctor report through the durable task worker`() {
        val editor = login("editor@alpha", "dev-editor")
        val documentId = createDraft(editor, "E2E-${UUID.randomUUID().toString().take(12)}").path("document").path("id").asText()

        val scheduled = post("$apiUrl/api/documents/$documentId/doctor/tasks", null, editor, "application/json")
        assertEquals("PENDING", scheduled.path("status").asText())
        val admin = login("admin@alpha", "dev-admin")
        post("$apiUrl/api/tasks/process?limit=20", null, admin, "application/json")

        val detail = awaitDoctorRecord(documentId, admin)
        assertTrue(detail.path("tasks").any { it.path("id").asText() == scheduled.path("taskId").asText() && it.path("status").asText() == "SUCCESS" })
        assertTrue(detail.path("doctorRecords").any { it.path("taskId").asText() == scheduled.path("taskId").asText() })
        assertAuditActor(detail, "document:doctor:schedule", "alpha-editor", "Alpha 编辑者")
    }

    private fun login(username: String, password: String): String = loginResponse(username, password).path("token").asText()

    private fun loginResponse(username: String, password: String): JsonNode = postJson(
        "$apiUrl/api/auth/login",
        mapper.writeValueAsString(mapOf("username" to username, "password" to password)),
        null,
    ).also { response -> assertTrue(response.path("token").asText().isNotBlank()) }

    private fun createDraft(
        token: String,
        documentNumber: String,
        folderId: String = "inbox",
        traceId: String? = null,
    ): JsonNode {
        return uploadFile(
            "$apiUrl/api/documents",
            mapOf("documentNumber" to documentNumber, "title" to "Compose 验收文档", "folderId" to folderId),
            token,
            traceId,
        )
    }

    private fun addVersion(token: String, documentId: String, versionNumber: String): JsonNode = uploadFile(
        "$apiUrl/api/documents/$documentId/versions",
        mapOf("versionNumber" to versionNumber),
        token,
    )

    private fun uploadFile(url: String, fields: Map<String, String>, token: String, traceId: String? = null): JsonNode {
        val response = uploadFileResponse(url, fields, token, traceId)
        assertTrue(response.statusCode() in 200..299, "HTTP ${response.statusCode()}: ${response.body()}")
        return mapper.readTree(response.body().ifBlank { "{}" })
    }

    private fun uploadFileResponse(
        url: String,
        fields: Map<String, String>,
        token: String,
        traceId: String? = null,
    ): HttpResponse<String> {
        val boundary = "FileWeft-${UUID.randomUUID()}"
        val content = "development acceptance payload".toByteArray(StandardCharsets.UTF_8)
        val body = ByteArrayOutputStream().apply {
            fields.forEach { (name, value) ->
                writeText("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            writeText("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"acceptance.txt\"\r\nContent-Type: text/plain\r\n\r\n")
            write(content)
            writeText("\r\n--$boundary--\r\n")
        }.toByteArray()
        val request = HttpRequest.newBuilder(URI(url))
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        traceId?.let { request.header("X-Trace-Id", it) }
        return client.send(
            request.build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
    }

    private fun awaitPublished(documentId: String, token: String): JsonNode {
        return awaitLifecycle(documentId, token, "PUBLISHED")
    }

    private fun awaitLifecycle(documentId: String, token: String, expected: String): JsonNode {
        repeat(50) {
            val detail = getJson("$apiUrl/api/documents/$documentId", token)
            if (detail.path("document").path("lifecycleState").asText() == expected) return detail
            Thread.sleep(200)
        }
        throw AssertionError("Document $documentId did not reach lifecycle state $expected within the expected window.")
    }

    private fun awaitDoctorRecord(documentId: String, token: String): JsonNode {
        repeat(50) {
            val detail = getJson("$apiUrl/api/documents/$documentId", token)
            if (detail.path("doctorRecords").size() > 0) return detail
            Thread.sleep(200)
        }
        throw AssertionError("Document $documentId did not receive a persisted Doctor report within the expected window.")
    }

    private fun assertAuditActor(detail: JsonNode, action: String, operatorId: String?, operatorName: String) {
        val audit = detail.path("audits").firstOrNull { it.path("action").asText() == action }
            ?: throw AssertionError("Audit action $action was not found.")
        assertEquals(operatorId, audit.path("operatorId").takeUnless { it.isNull }?.asText())
        assertEquals(operatorName, audit.path("operatorName").asText())
    }

    private fun delivery(detail: JsonNode, targetId: String): JsonNode =
        detail.path("deliveries").firstOrNull { it.path("targetId").asText() == targetId }
            ?: throw AssertionError("Delivery target $targetId was not found.")

    private fun getPlatform(targetId: String, documentId: String): JsonNode = response(
        HttpRequest.newBuilder(URI("$platformUrl/platform/v1/documents/alpha/$documentId"))
            .header("X-FileWeft-Target", targetId)
            .GET()
            .build(),
    )

    private fun getJson(url: String, token: String? = null): JsonNode {
        val request = HttpRequest.newBuilder(URI(url)).GET().apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        return response(request)
    }

    private fun postJson(url: String, body: String, token: String?): JsonNode =
        post(url, body.toByteArray(StandardCharsets.UTF_8), token, "application/json")

    private fun requestJson(method: String, url: String, body: String, token: String): JsonNode {
        val builder = HttpRequest.newBuilder(URI(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        return response(builder.build())
    }

    private fun post(url: String, body: ByteArray?, token: String?, contentType: String): JsonNode {
        val builder = HttpRequest.newBuilder(URI(url))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body ?: ByteArray(0)))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return response(builder.build())
    }

    private fun response(request: HttpRequest): JsonNode {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        assertTrue(response.statusCode() in 200..299, "HTTP ${response.statusCode()}: ${response.body()}")
        return mapper.readTree(response.body().ifBlank { "{}" })
    }

    private fun ByteArrayOutputStream.writeText(value: String) = write(value.toByteArray(StandardCharsets.UTF_8))
}
