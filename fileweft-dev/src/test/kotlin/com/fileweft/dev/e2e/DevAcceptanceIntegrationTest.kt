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
        postJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"AVAILABLE"}""", null)
    }

    @Test
    fun `uploads reviews publishes and delivers a document through the development stack`() {
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
            """{"comment":"Compose acceptance approved"}""",
            reviewer,
        )
        val admin = login("admin@alpha", "dev-admin")
        post("$apiUrl/api/outbox/process?limit=20", null, admin, "application/json")

        val detail = awaitPublished(documentId, admin)
        assertEquals("PUBLISHED", detail.path("document").path("lifecycleState").asText())
        assertTrue(detail.path("syncRecords").any { it.path("status").asText() == "SUCCESS" })
        assertTrue(detail.path("outboxEvents").any { it.path("status").asText() == "SUCCESS" })

        val mirror = getJson("$platformUrl/platform/v1/documents/alpha/$documentId")
        assertEquals("alpha:$documentId", mirror.path("externalId").asText())
        assertEquals("acceptance.txt", mirror.path("fileName").asText())
        assertTrue(mirror.path("downloadedBytes").asLong() > 0)
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
        postJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"RETRYABLE_FAILURE"}""", null)
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        postJson(
            "$apiUrl/api/documents/workflows/${workflow.path("workflowId").asText()}/tasks/${workflow.path("taskId").asText()}/approve",
            """{"comment":"触发可重试故障"}""",
            reviewer,
        )
        val admin = login("admin@alpha", "dev-admin")
        post("$apiUrl/api/outbox/process?limit=20", null, admin, "application/json")
        awaitLifecycle(documentId, admin, "SYNC_ERROR")

        postJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"AVAILABLE"}""", null)
        Thread.sleep(10_500)
        post("$apiUrl/api/outbox/process?limit=20", null, admin, "application/json")
        val recovered = awaitPublished(documentId, admin)
        assertTrue(recovered.path("syncRecords").any { it.path("status").asText() == "SUCCESS" })
    }

    private fun login(username: String, password: String): String = postJson(
        "$apiUrl/api/auth/login",
        mapper.writeValueAsString(mapOf("username" to username, "password" to password)),
        null,
    ).path("token").asText().also { token -> assertTrue(token.isNotBlank()) }

    private fun createDraft(token: String, documentNumber: String): JsonNode {
        return uploadFile(
            "$apiUrl/api/documents",
            mapOf("documentNumber" to documentNumber, "title" to "Compose 验收文档"),
            token,
        )
    }

    private fun addVersion(token: String, documentId: String, versionNumber: String): JsonNode = uploadFile(
        "$apiUrl/api/documents/$documentId/versions",
        mapOf("versionNumber" to versionNumber),
        token,
    )

    private fun uploadFile(url: String, fields: Map<String, String>, token: String): JsonNode {
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
        return post(
            url,
            body,
            token,
            "multipart/form-data; boundary=$boundary",
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
