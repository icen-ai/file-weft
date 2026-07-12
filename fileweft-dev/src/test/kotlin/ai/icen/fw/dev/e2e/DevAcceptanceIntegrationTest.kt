package ai.icen.fw.dev.e2e

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
import java.sql.Connection
import java.sql.DriverManager
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
    private val databaseUrl = System.getenv("FILEWEFT_DEV_E2E_DB_URL")
        ?: "jdbc:postgresql://127.0.0.1:5432/fileweft?currentSchema=fileweft_dev"
    private val databaseUsername = System.getenv("FILEWEFT_DEV_E2E_DB_USERNAME") ?: "fileweft"
    private val databasePassword = System.getenv("FILEWEFT_DEV_E2E_DB_PASSWORD") ?: "fileweft-dev"

    @BeforeAll
    fun requireComposeStack() {
        assumeTrue(
            System.getenv("FILEWEFT_RUN_DEV_E2E") == "true",
            "Set FILEWEFT_RUN_DEV_E2E=true after starting docker compose -f .docker/docker-compose.dev.yaml up --wait.",
        )
        assertEquals("UP", getJson("$apiUrl/api/health").path("status").asText())
        assertEquals("UP", getJson("$platformUrl/platform/v1/health").path("status").asText())
        assertTrue(platformSharedSecret().length >= 32, "FILEWEFT_DEV_PLATFORM_SHARED_SECRET must contain at least 32 characters.")
    }

    @AfterEach
    fun restoreDevelopmentPlatform() {
        listOf("default", "compliance", "collaboration", "search").forEach { targetId ->
            postPlatformJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"AVAILABLE","targetId":"$targetId"}""")
        }
    }

    @Test
    fun `rejects unauthenticated platform management reads while preserving its health probe`() {
        val protected = client.send(
            HttpRequest.newBuilder(URI("$platformUrl/platform/v1/documents")).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(401, protected.statusCode())

        val authenticated = client.send(
            platformRequest("$platformUrl/platform/v1/documents").GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        assertEquals(200, authenticated.statusCode())
    }

    @Test
    fun `returns a role scoped capability surface for the proof lab`() {
        val editor = loginResponse("editor@alpha", "dev-editor")
        val reviewer = loginResponse("reviewer@alpha", "dev-reviewer")
        val viewer = loginResponse("viewer@alpha", "dev-viewer")
        val admin = loginResponse("admin@alpha", "dev-admin")

        assertTrue(editor.path("permissions").any { it.asText() == "document:create" })
        assertTrue(editor.path("permissions").any { it.asText() == "document:edit" })
        assertTrue(editor.path("permissions").any { it.asText() == "file:upload" })
        assertTrue(editor.path("permissions").any { it.asText() == "document:doctor" })
        assertTrue(editor.path("permissions").none { it.asText() == "document:audit" })
        assertTrue(reviewer.path("permissions").any { it.asText() == "document:audit" })
        assertTrue(reviewer.path("permissions").any { it.asText() == "agent:suggestion:read" })
        assertTrue(reviewer.path("permissions").any { it.asText() == "document:doctor" })
        assertTrue(reviewer.path("permissions").none { it.asText() == "document:create" })
        assertEquals(listOf("document:read", "document:download"), viewer.path("permissions").map { it.asText() })
        assertTrue(admin.path("permissions").any { it.asText() == "system:outbox:process" })
        assertTrue(admin.path("permissions").any { it.asText() == "agent:suggestion:confirm" })
        assertTrue(admin.path("permissions").any { it.asText() == "system:doctor:read" })
        assertTrue(admin.path("permissions").any { it.asText() == "system:plugins:read" })
        assertTrue(editor.path("permissions").none { it.asText() == "system:doctor:read" })
        assertTrue(reviewer.path("permissions").none { it.asText() == "system:doctor:read" })
        assertTrue(editor.path("permissions").none { it.asText() == "system:plugins:read" })
        assertTrue(reviewer.path("permissions").none { it.asText() == "system:plugins:read" })
        assertTrue(viewer.path("permissions").none { it.asText() == "system:plugins:read" })
    }

    @Test
    fun `exposes anonymous formal health and administrator-only plugin inventory`() {
        listOf("/fileweft/v1/health", "/fileweft/health").forEach { path ->
            val response = client.send(
                HttpRequest.newBuilder(URI("$apiUrl$path")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
            )
            assertEquals(200, response.statusCode())
            assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"))
            val envelope = mapper.readTree(response.body())
            assertV1SuccessEnvelope(envelope)
            assertEquals("UP", envelope.path("data").path("status").asText())
        }

        val admin = login("admin@alpha", "dev-admin")
        val editor = login("editor@alpha", "dev-editor")
        val plugins = getResponse("$apiUrl/fileweft/v1/plugins?limit=100", admin)
        assertEquals(200, plugins.statusCode())
        assertTrue(plugins.headers().firstValue("Cache-Control").orElse("").contains("no-store"))
        val pluginEnvelope = mapper.readTree(plugins.body())
        assertV1SuccessEnvelope(pluginEnvelope)
        assertTrue(pluginEnvelope.path("data").path("items").isArray)
        assertTrue(pluginEnvelope.path("data").path("items").all { plugin ->
            plugin.fieldNames().asSequence().toSet() == setOf("id", "capabilities") &&
                plugin.path("capabilities").all { capability ->
                    capability.fieldNames().asSequence().toSet() == setOf("type", "count")
                }
        })
        assertTrue(pluginEnvelope.findValue("className") == null)
        assertTrue(pluginEnvelope.findValue("configuration") == null)

        val forbidden = getResponse("$apiUrl/fileweft/v1/plugins", editor)
        assertEquals(403, forbidden.statusCode())
        assertV1FailureEnvelope(mapper.readTree(forbidden.body()), "FORBIDDEN", "Access denied.")
        val unauthenticated = client.send(
            HttpRequest.newBuilder(URI("$apiUrl/fileweft/v1/plugins")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        assertEquals(401, unauthenticated.statusCode())
        assertV1FailureEnvelope(mapper.readTree(unauthenticated.body()), "UNAUTHENTICATED", "Authentication is required.")
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
    fun `refuses direct publication while a local review workflow is pending`() {
        val editor = login("editor@alpha", "dev-editor")
        val admin = login("admin@alpha", "dev-admin")
        val documentId = createDraft(editor, "E2E-PUBLISH-GUARD-${UUID.randomUUID().toString().take(8)}")
            .path("document").path("id").asText()
        val workflow = postJson(
            "$apiUrl/api/documents/$documentId/submit",
            """{"reviewerId":"alpha-reviewer"}""",
            editor,
        )

        val blocked = client.send(
            HttpRequest.newBuilder(URI("$apiUrl/api/documents/$documentId/publish"))
                .header("Authorization", "Bearer $admin")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )

        assertEquals(409, blocked.statusCode())
        assertEquals("ACTIVE_REVIEW_WORKFLOW", mapper.readTree(blocked.body()).path("code").asText())
        val detail = getJson("$apiUrl/api/documents/$documentId", admin)
        assertEquals("PENDING_REVIEW", detail.path("document").path("lifecycleState").asText())
        assertEquals("PENDING", detail.path("workflows").first().path("state").asText())
        assertEquals(workflow.path("workflowId").asText(), detail.path("workflows").first().path("id").asText())
    }

    @Test
    fun `exposes one redacted formal audit entry with its request trace`() {
        val editor = login("editor@alpha", "dev-editor")
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        val traceId = "e2e-operation-${UUID.randomUUID().toString().take(12)}"
        val documentId = createDraft(
            editor,
            "E2E-TRACE-${UUID.randomUUID().toString().take(12)}",
            traceId = traceId,
        ).path("document").path("id").asText()

        val detail = getJson("$apiUrl/api/documents/$documentId", editor)
        assertEquals(0, detail.path("audits").size())
        assertEquals(0, detail.path("operationLogs").size())
        val audit = documentLogs(documentId, reviewer).first { it.path("action").asText() == "document:create" }
        assertEquals(traceId, audit.path("traceId").asText())
        assertEquals("alpha-editor", audit.path("operatorId").asText())
        assertEquals("Alpha 编辑者", audit.path("operatorName").asText())
        assertTrue(!audit.has("details") && !audit.has("detailJson") && !audit.has("source"))
    }

    @Test
    fun `exposes redacted sync and formal audit history only to the current tenant auditor`() {
        val editor = login("editor@alpha", "dev-editor")
        val traceId = "e2e-status-${UUID.randomUUID().toString().take(12)}"
        val documentId = createDraft(
            editor,
            "E2E-STATUS-${UUID.randomUUID().toString().take(12)}",
            traceId = traceId,
        ).path("document").path("id").asText()
        val workflow = postJson(
            "$apiUrl/api/documents/$documentId/submit",
            """{"reviewerId":"alpha-reviewer"}""",
            editor,
        )
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        postJson(
            "$apiUrl/api/documents/workflows/${workflow.path("workflowId").asText()}/tasks/${workflow.path("taskId").asText()}/approve",
            """{"comment":"状态接口验收","deliveryProfileId":"internal"}""",
            reviewer,
        )

        val syncStatus = getJson("$apiUrl/api/documents/$documentId/sync-status", editor)
        assertTrue(syncStatus.path("deliveryTargets").isArray)
        assertTrue(syncStatus.path("deliveryTargets").size() > 0)
        assertTrue(syncStatus.path("outboxEvents").size() > 0)
        assertTrue(syncStatus.path("deliveryTargets").all { target ->
            !target.has("externalId") && !target.has("ownerRef") && !target.has("connectorId") &&
                !target.has("errorMessage") && !target.has("removalErrorMessage") && !target.has("deliveryGeneration")
        })
        assertTrue(syncStatus.path("syncRecords").all { record ->
            !record.has("sourceEventId") && !record.has("externalId") && !record.has("errorMessage")
        })
        assertTrue(syncStatus.path("outboxEvents").all { event ->
            !event.has("id") && !event.has("lastError") && !event.has("payload") && !event.has("payloadJson")
        })
        assertTrue(!syncStatus.has("platformSharedSecret"))

        val formalSyncStatus = getJson("$apiUrl/fileweft/v1/documents/$documentId/sync-status", editor)
        assertV1SuccessEnvelope(formalSyncStatus)
        assertEquals(documentId, formalSyncStatus.path("data").path("documentId").asText())
        assertTrue(formalSyncStatus.path("data").path("deliveryTargets").size() > 0)
        assertNoInternalV1Fields(formalSyncStatus)
        assertTrue(formalSyncStatus.findValue("currentEventId") == null)
        assertTrue(formalSyncStatus.findValue("dispatchSequence") == null)
        assertTrue(formalSyncStatus.findValue("errorMessage") == null)

        val boundedEnvelope = getJson("$apiUrl/fileweft/v1/documents/$documentId/logs?limit=1", reviewer)
        assertV1SuccessEnvelope(boundedEnvelope)
        assertEquals(1, boundedEnvelope.path("data").path("items").size())
        val logs = documentLogs(documentId, reviewer)
        assertTrue(logs.any { entry ->
            entry.path("action").asText() == "document:create" && entry.path("traceId").asText() == traceId
        })
        assertTrue(logs.all { entry -> !entry.has("details") && !entry.has("detailJson") && !entry.has("source") })

        val forbiddenEditor = getResponse("$apiUrl/fileweft/v1/documents/$documentId/logs", editor)
        assertEquals(403, forbiddenEditor.statusCode())
        assertV1FailureEnvelope(mapper.readTree(forbiddenEditor.body()), "FORBIDDEN", "Access denied.")

        val invalidLimit = client.send(
            HttpRequest.newBuilder(URI("$apiUrl/fileweft/v1/documents/$documentId/logs?limit=101"))
                .header("Authorization", "Bearer $reviewer")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        assertEquals(400, invalidLimit.statusCode())
        assertV1FailureEnvelope(mapper.readTree(invalidLimit.body()), "INVALID_REQUEST", "Request is invalid.")

        val betaEditor = login("editor@beta", "dev-editor")
        val crossTenant = client.send(
            HttpRequest.newBuilder(URI("$apiUrl/api/documents/$documentId/sync-status"))
                .header("Authorization", "Bearer $betaEditor")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        assertEquals(404, crossTenant.statusCode())

        val formalCrossTenant = getResponse("$apiUrl/fileweft/v1/documents/$documentId/sync-status", betaEditor)
        assertEquals(404, formalCrossTenant.statusCode())
        assertV1FailureEnvelope(mapper.readTree(formalCrossTenant.body()), "NOT_FOUND", "Resource was not found.")

        val betaReviewer = login("reviewer@beta", "dev-reviewer")
        val auditCrossTenant = getResponse("$apiUrl/fileweft/v1/documents/$documentId/logs", betaReviewer)
        assertEquals(404, auditCrossTenant.statusCode())
        assertV1FailureEnvelope(mapper.readTree(auditCrossTenant.body()), "NOT_FOUND", "Resource was not found.")

        val unauthenticated = client.send(
            HttpRequest.newBuilder(URI("$apiUrl/fileweft/v1/documents/$documentId/logs"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        assertEquals(401, unauthenticated.statusCode())
        assertV1FailureEnvelope(mapper.readTree(unauthenticated.body()), "UNAUTHENTICATED", "Authentication is required.")

        val legacy = getResponse("$apiUrl/api/documents/$documentId/logs", reviewer)
        assertEquals(404, legacy.statusCode())
    }

    @Test
    fun `authorizes a version stream without exposing storage urls or cross tenant content`() {
        val editor = login("editor@alpha", "dev-editor")
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        val documentId = createDraft(editor, "E2E-DOWNLOAD-${UUID.randomUUID().toString().take(12)}").path("document").path("id").asText()
        val detail = getJson("$apiUrl/api/documents/$documentId", editor)
        val versionId = detail.path("versions").first().path("id").asText()

        val downloaded = client.send(
            HttpRequest.newBuilder(URI("$apiUrl/api/documents/$documentId/versions/$versionId/content"))
                .header("Authorization", "Bearer $editor")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        assertEquals(200, downloaded.statusCode())
        assertEquals("development acceptance payload", downloaded.body())
        assertTrue(downloaded.headers().firstValue("Content-Disposition").orElse("").contains("attachment"))

        val betaEditor = login("editor@beta", "dev-editor")
        val crossTenant = client.send(
            HttpRequest.newBuilder(URI("$apiUrl/api/documents/$documentId/content"))
                .header("Authorization", "Bearer $betaEditor")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        assertEquals(404, crossTenant.statusCode())

        assertAuditActor(documentId, reviewer, "document:download", "alpha-editor", "Alpha 编辑者")
    }

    @Test
    fun `exercises the formal v1 document contract against the compose database and RustFS`() {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val editor = login("editor@alpha", "dev-editor")
        val auditor = login("reviewer@alpha", "dev-reviewer")
        val viewer = login("viewer@alpha", "dev-viewer")
        val betaEditor = login("editor@beta", "dev-editor")
        val documentNumber = "V1-E2E-${nonce.take(20)}"
        val originalTitle = "正式接口验收-$nonce"
        val renamedTitle = "正式接口已重命名-$nonce"
        val historicalFileName = "formal-history-$nonce.txt"
        val currentFileName = "formal-current-$nonce.txt"
        val historicalContent = "formal-v1-history-$nonce".toByteArray(StandardCharsets.UTF_8)
        val currentContent = "formal-v1-current-$nonce".toByteArray(StandardCharsets.UTF_8)
        val createTraceId = "e2e-v1-create-${nonce.take(24)}"

        val forbiddenWrite = uploadFileResponse(
            "$apiUrl/fileweft/v1/documents",
            mapOf(
                "documentNumber" to "V1-VIEWER-${nonce.take(20)}",
                "title" to "viewer must not create",
                "folderId" to "contracts",
            ),
            viewer,
            content = "viewer-denied-$nonce".toByteArray(StandardCharsets.UTF_8),
            fileName = "viewer-denied-$nonce.txt",
        )
        assertEquals(403, forbiddenWrite.statusCode())
        assertJsonContentType(forbiddenWrite)
        assertV1FailureEnvelope(mapper.readTree(forbiddenWrite.body()), "FORBIDDEN", "Access denied.")

        val createdResponse = uploadFileResponse(
            "$apiUrl/fileweft/v1/documents",
            mapOf(
                "documentNumber" to documentNumber,
                "title" to originalTitle,
                "folderId" to "contracts",
            ),
            editor,
            traceId = createTraceId,
            content = historicalContent,
            fileName = historicalFileName,
        )
        assertEquals(201, createdResponse.statusCode())
        assertJsonContentType(createdResponse)
        assertEquals(createTraceId, createdResponse.headers().firstValue("X-Trace-Id").orElse(""))
        val created = mapper.readTree(createdResponse.body())
        assertV1SuccessEnvelope(created)
        assertEquals(createTraceId, created.path("traceId").asText())
        assertEquals(setOf("documentId", "versionId"), created.path("data").fieldNames().asSequence().toSet())
        val documentId = created.path("data").path("documentId").asText()
        val historicalVersionId = created.path("data").path("versionId").asText()
        assertTrue(documentId.isNotBlank())
        assertTrue(historicalVersionId.isNotBlank())
        assertEquals(
            "/fileweft/v1/documents/$documentId",
            createdResponse.headers().firstValue("Location").orElse(""),
        )
        assertNoInternalV1Fields(created)

        val initialDetailResponse = getResponse("$apiUrl/fileweft/v1/documents/$documentId", editor)
        assertEquals(200, initialDetailResponse.statusCode())
        assertJsonContentType(initialDetailResponse)
        val initialDetail = mapper.readTree(initialDetailResponse.body())
        assertV1SuccessEnvelope(initialDetail)
        assertNoInternalV1Fields(initialDetail)
        val initialDocument = initialDetail.path("data").path("document")
        val initialVersion = initialDetail.path("data").path("versions")
            .firstOrNull { version -> version.path("id").asText() == historicalVersionId }
            ?: throw AssertionError("The formal v1 detail did not contain its created version.")
        assertEquals(documentId, initialDocument.path("id").asText())
        assertEquals(documentNumber, initialDocument.path("documentNumber").asText())
        assertEquals(originalTitle, initialDocument.path("title").asText())
        assertEquals("DRAFT", initialDocument.path("lifecycleState").asText())
        assertEquals("contracts", initialDocument.path("folderId").asText())
        assertEquals(historicalVersionId, initialDocument.path("currentVersionId").asText())
        assertEquals(historicalFileName, initialVersion.path("fileName").asText())
        assertEquals(historicalContent.size.toLong(), initialVersion.path("contentLength").asLong())

        val pageResponse = getResponse(
            "$apiUrl/fileweft/v1/documents?folderId=contracts&lifecycleState=DRAFT&limit=100",
            editor,
        )
        assertEquals(200, pageResponse.statusCode())
        assertJsonContentType(pageResponse)
        val page = mapper.readTree(pageResponse.body())
        assertV1SuccessEnvelope(page)
        assertNoInternalV1Fields(page)
        val pageDocument = page.path("data").path("items")
            .firstOrNull { document -> document.path("id").asText() == documentId }
            ?: throw AssertionError("The formal v1 page did not contain the newly created document.")
        assertEquals(documentNumber, pageDocument.path("documentNumber").asText())
        assertEquals("contracts", pageDocument.path("folderId").asText())

        val renamedResponse = jsonRequestResponse(
            "PATCH",
            "$apiUrl/fileweft/v1/documents/$documentId",
            mapper.writeValueAsString(mapOf("title" to renamedTitle)),
            editor,
        )
        assertEquals(200, renamedResponse.statusCode())
        assertJsonContentType(renamedResponse)
        assertTrue(!renamedResponse.headers().firstValue("Location").isPresent)
        val renamed = mapper.readTree(renamedResponse.body())
        assertV1SuccessEnvelope(renamed)
        assertEquals(documentId, renamed.path("data").path("documentId").asText())
        assertTrue(renamed.path("data").path("versionId").isNull)
        assertNoInternalV1Fields(renamed)

        val addedVersionResponse = uploadFileResponse(
            "$apiUrl/fileweft/v1/documents/$documentId/versions",
            mapOf("versionNumber" to "2.0"),
            editor,
            content = currentContent,
            fileName = currentFileName,
        )
        assertEquals(201, addedVersionResponse.statusCode())
        assertJsonContentType(addedVersionResponse)
        assertEquals(
            "/fileweft/v1/documents/$documentId",
            addedVersionResponse.headers().firstValue("Location").orElse(""),
        )
        val addedVersion = mapper.readTree(addedVersionResponse.body())
        assertV1SuccessEnvelope(addedVersion)
        assertNoInternalV1Fields(addedVersion)
        assertEquals(documentId, addedVersion.path("data").path("documentId").asText())
        val currentVersionId = addedVersion.path("data").path("versionId").asText()
        assertTrue(currentVersionId.isNotBlank() && currentVersionId != historicalVersionId)

        val finalDetailResponse = getResponse("$apiUrl/fileweft/v1/documents/$documentId", editor)
        assertEquals(200, finalDetailResponse.statusCode())
        assertJsonContentType(finalDetailResponse)
        val finalDetail = mapper.readTree(finalDetailResponse.body())
        assertV1SuccessEnvelope(finalDetail)
        assertNoInternalV1Fields(finalDetail)
        assertEquals(renamedTitle, finalDetail.path("data").path("document").path("title").asText())
        assertEquals(currentVersionId, finalDetail.path("data").path("document").path("currentVersionId").asText())
        val versions = finalDetail.path("data").path("versions")
        assertEquals(setOf(historicalVersionId, currentVersionId), versions.map { it.path("id").asText() }.toSet())
        assertEquals(
            historicalFileName,
            versions.first { version -> version.path("id").asText() == historicalVersionId }.path("fileName").asText(),
        )
        assertEquals(
            currentFileName,
            versions.first { version -> version.path("id").asText() == currentVersionId }.path("fileName").asText(),
        )

        assertEquals(0, downloadAuditCount(documentId, auditor))
        val currentDownloadPath = "$apiUrl/fileweft/v1/documents/$documentId/content"
        val historicalDownloadPath =
            "$apiUrl/fileweft/v1/documents/$documentId/versions/$historicalVersionId/content"
        val currentDownload = getBinary(currentDownloadPath, editor)
        assertV1Download(currentDownload, currentContent, currentFileName)
        val historicalDownload = getBinary(historicalDownloadPath, editor)
        assertV1Download(historicalDownload, historicalContent, historicalFileName)
        assertEquals(2, downloadAuditCount(documentId, auditor))

        // The application persists download intent before it opens RustFS. An
        // unchanged count therefore proves these transport rejections never
        // entered the download service or opened object content.
        listOf(currentDownloadPath to "bytes=0-2", historicalDownloadPath to "").forEach { (path, range) ->
            val rejectedRange = getBinary(path, editor, mapOf("Range" to range))
            assertEquals(416, rejectedRange.statusCode())
            assertJsonContentType(rejectedRange)
            assertV1FailureEnvelope(
                mapper.readTree(rejectedRange.body()),
                "RANGE_NOT_SUPPORTED",
                "Range requests are not supported.",
            )
            assertEquals("private, no-store", rejectedRange.headers().firstValue("Cache-Control").orElse(""))
            assertEquals("nosniff", rejectedRange.headers().firstValue("X-Content-Type-Options").orElse(""))
            assertTrue(!rejectedRange.headers().firstValue("Accept-Ranges").isPresent)
        }
        assertEquals(2, downloadAuditCount(documentId, auditor))

        listOf(currentDownloadPath, historicalDownloadPath).forEach { path ->
            val rejectedHead = client.send(
                authorizedRequest(path, editor)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            assertEquals(405, rejectedHead.statusCode())
            assertJsonContentType(rejectedHead)
            assertEquals("GET", rejectedHead.headers().firstValue("Allow").orElse(""))
            assertEquals("private, no-store", rejectedHead.headers().firstValue("Cache-Control").orElse(""))
            assertEquals("nosniff", rejectedHead.headers().firstValue("X-Content-Type-Options").orElse(""))
            assertTrue(!rejectedHead.headers().firstValue("Accept-Ranges").isPresent)
        }
        assertEquals(2, downloadAuditCount(documentId, auditor))

        val betaDetail = getResponse("$apiUrl/fileweft/v1/documents/$documentId", betaEditor)
        assertEquals(404, betaDetail.statusCode())
        assertJsonContentType(betaDetail)
        assertV1FailureEnvelope(mapper.readTree(betaDetail.body()), "NOT_FOUND", "Resource was not found.")
        val betaPageResponse = getResponse(
            "$apiUrl/fileweft/v1/documents?limit=100",
            betaEditor,
        )
        assertEquals(200, betaPageResponse.statusCode())
        assertJsonContentType(betaPageResponse)
        val betaPage = mapper.readTree(betaPageResponse.body())
        assertV1SuccessEnvelope(betaPage)
        assertTrue(betaPage.path("data").path("items").none { item -> item.path("id").asText() == documentId })
        assertNoInternalV1Fields(betaPage)
        val betaContent = getBinary(currentDownloadPath, betaEditor)
        assertEquals(404, betaContent.statusCode())
        assertJsonContentType(betaContent)
        assertV1FailureEnvelope(mapper.readTree(betaContent.body()), "NOT_FOUND", "Resource was not found.")
        assertEquals(2, downloadAuditCount(documentId, auditor))

        val downloadAudits = documentLogs(documentId, auditor).filter { entry ->
            entry.path("action").asText() == "document:download"
        }
        assertEquals(2, downloadAudits.size)
        assertTrue(downloadAudits.all { entry -> entry.path("operatorId").asText() == "alpha-editor" })
        assertTrue(downloadAudits.all { entry -> entry.path("operatorName").asText() == "Alpha 编辑者" })
    }

    @Test
    fun `persists a resumable upload session across part checkpoints and completes it once`() {
        val editor = login("editor@alpha", "dev-editor")
        val idempotencyKey = "e2e-resumable-${UUID.randomUUID()}"
        val started = postJson(
            "$apiUrl/api/resumable-uploads",
            """{"fileName":"resumable.txt","contentLength":7,"assetType":"DOCUMENT","contentType":"text/plain","idempotencyKey":"$idempotencyKey"}""",
            editor,
        )
        val sessionId = started.path("id").asText()
        assertEquals("ACTIVE", started.path("status").asText())
        assertEquals(0, started.path("parts").size())
        assertTrue(!started.has("ownerId"))
        assertTrue(!started.has("tenantId"))
        assertTrue(!started.has("storageUploadId"))
        assertTrue(!started.has("storageLocation"))

        val admin = login("admin@alpha", "dev-admin")
        val nonOwnerResponses = listOf(
            resumableResponse("GET", "$apiUrl/api/resumable-uploads/$sessionId", admin),
            resumableResponse(
                "PUT",
                "$apiUrl/api/resumable-uploads/$sessionId/parts/1",
                admin,
                "hostile".toByteArray(),
            ),
            resumableResponse("POST", "$apiUrl/api/resumable-uploads/$sessionId/complete", admin),
            resumableResponse("DELETE", "$apiUrl/api/resumable-uploads/$sessionId", admin),
        )
        nonOwnerResponses.forEach { response ->
            assertEquals(404, response.statusCode())
            assertJsonContentType(response)
            val failure = mapper.readTree(response.body())
            assertEquals(setOf("code", "message"), failure.fieldNames().asSequence().toSet())
            assertEquals("NOT_FOUND", failure.path("code").asText())
            assertEquals("Resource was not found.", failure.path("message").asText())
            assertTrue(failure.findValue("ownerId") == null)
            assertTrue(failure.findValue("tenantId") == null)
            assertTrue(failure.findValue("storageUploadId") == null)
            assertTrue(failure.findValue("storageLocation") == null)
        }

        val viewer = login("viewer@alpha", "dev-viewer")
        val hiddenFromUnprivilegedNonOwner = resumableResponse(
            "GET",
            "$apiUrl/api/resumable-uploads/$sessionId",
            viewer,
        )
        assertEquals(404, hiddenFromUnprivilegedNonOwner.statusCode())
        assertEquals("NOT_FOUND", mapper.readTree(hiddenFromUnprivilegedNonOwner.body()).path("code").asText())

        val forbiddenMaintenance = client.send(
            HttpRequest.newBuilder(URI("$apiUrl/api/resumable-uploads/maintenance"))
                .header("Authorization", "Bearer $viewer")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        assertEquals(403, forbiddenMaintenance.statusCode())

        val untouched = getJson("$apiUrl/api/resumable-uploads/$sessionId", editor)
        assertEquals("ACTIVE", untouched.path("status").asText())
        assertEquals(0, untouched.path("parts").size())

        val uploaded = putBytes("$apiUrl/api/resumable-uploads/$sessionId/parts/1", "content".toByteArray(), editor)
        assertEquals(1, uploaded.path("partNumber").asInt())
        val checkpoint = getJson("$apiUrl/api/resumable-uploads/$sessionId", editor)
        assertEquals(1, checkpoint.path("parts").size())
        assertEquals(7, checkpoint.path("parts").first().path("contentLength").asLong())

        val maintenance = getJson("$apiUrl/api/resumable-uploads/maintenance?limit=10", admin)
        assertTrue(maintenance.isArray)
        assertTrue(maintenance.all { item ->
            !item.has("ownerId") && !item.has("tenantId") && !item.has("storageUploadId") && !item.has("storagePath")
        })

        val completed = post("$apiUrl/api/resumable-uploads/$sessionId/complete", null, editor, "application/json")
        val repeated = post("$apiUrl/api/resumable-uploads/$sessionId/complete", null, editor, "application/json")
        assertEquals(completed.path("fileObjectId").asText(), repeated.path("fileObjectId").asText())
        assertTrue(completed.path("fileAssetId").asText().isNotBlank())
        assertEquals("COMPLETED", getJson("$apiUrl/api/resumable-uploads/$sessionId", editor).path("status").asText())
        assertEquals(
            404,
            resumableResponse("GET", "$apiUrl/api/resumable-uploads/$sessionId", admin).statusCode(),
        )
    }

    @Test
    fun `keeps catalog folders and bound documents isolated between tenants`() {
        val alphaEditor = login("editor@alpha", "dev-editor")
        val alphaReviewer = login("reviewer@alpha", "dev-reviewer")
        val betaEditor = login("editor@beta", "dev-editor")
        val alphaNumber = "E2E-ALPHA-${UUID.randomUUID().toString().take(8)}"
        val betaNumber = "E2E-BETA-${UUID.randomUUID().toString().take(8)}"
        val alphaCreated = createDraft(alphaEditor, alphaNumber, "contracts")
        val alphaDocument = alphaCreated.path("document").path("id").asText()
        val betaDocument = createDraft(betaEditor, betaNumber, "projects").path("document").path("id").asText()

        val alphaFolders = getJson("$apiUrl/api/catalog/folders", alphaEditor)
        val betaFolders = getJson("$apiUrl/api/catalog/folders", betaEditor)
        assertTrue(alphaFolders.any { it.path("id").asText() == "contracts" })
        assertTrue(alphaFolders.none { it.path("id").asText() == "projects" })
        assertTrue(betaFolders.any { it.path("id").asText() == "projects" })
        assertTrue(betaFolders.none { it.path("id").asText() == "contracts" })

        val alphaCreatedDetail = getJson("$apiUrl/api/documents/$alphaDocument", alphaEditor)
        assertEquals("contracts", alphaCreatedDetail.path("document").path("folderId").asText())
        assertEquals(0, alphaCreatedDetail.path("audits").size())
        assertEquals(0, alphaCreatedDetail.path("operationLogs").size())
        assertAuditActor(alphaDocument, alphaReviewer, "document:create", "alpha-editor", "Alpha 编辑者")

        val betaCannotBindAlphaFolder = uploadFileResponse(
            "$apiUrl/api/documents",
            mapOf("documentNumber" to "E2E-BETA-FORBIDDEN-${UUID.randomUUID().toString().take(8)}", "title" to "Forbidden folder", "folderId" to "contracts"),
            betaEditor,
        )
        assertEquals(422, betaCannotBindAlphaFolder.statusCode())

        val moved = postJson(
            "$apiUrl/api/documents/$alphaDocument/folder",
            """{"folderId":"finance"}""",
            alphaEditor,
        )
        assertEquals("finance", moved.path("document").path("folderId").asText())
        assertAuditActor(alphaDocument, alphaReviewer, "document:catalog:move", "alpha-editor", "Alpha 编辑者")

        val alphaDocuments = getJson("$apiUrl/api/documents?limit=100", alphaEditor)
        val betaDocuments = getJson("$apiUrl/api/documents?limit=100", betaEditor)
        assertEquals("finance", alphaDocuments.first { it.path("id").asText() == alphaDocument }.path("folderId").asText())
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
        val deliveryTraceId = "e2e-delivery-${UUID.randomUUID().toString().take(12)}"
        postJson(
            "$apiUrl/api/documents/workflows/${workflow.path("workflowId").asText()}/tasks/${workflow.path("taskId").asText()}/approve",
            """{"comment":"Compose acceptance approved","deliveryProfileId":"regulated"}""",
            reviewer,
            deliveryTraceId,
        )
        val admin = login("admin@alpha", "dev-admin")
        post("$apiUrl/api/outbox/process?limit=20", null, admin, "application/json")

        val detail = awaitPublished(documentId, admin)
        assertEquals("PUBLISHED", detail.path("document").path("lifecycleState").asText())
        assertEquals(3, detail.path("deliveries").size())
        assertTrue(detail.path("deliveries").all { it.path("status").asText() == "SUCCEEDED" })
        assertEquals(3, detail.path("outboxEvents").count { it.path("status").asText() == "SUCCESS" })
        assertAuditActor(documentId, reviewer, "document:create", "alpha-editor", "Alpha 编辑者")
        assertAuditActor(documentId, reviewer, "document:review:submit", "alpha-editor", "Alpha 编辑者")
        assertAuditActor(documentId, reviewer, "document:review:approve", "alpha-reviewer", "Alpha 审批者")
        val deliveryAudits = documentLogs(documentId, reviewer).filter { it.path("action").asText() == "document:delivery:succeeded" }
        assertEquals(3, deliveryAudits.size)
        assertTrue(deliveryAudits.all { it.path("traceId").asText() == deliveryTraceId })

        listOf("compliance", "collaboration", "search").forEach { targetId ->
            val mirror = getPlatform(targetId, documentId)
            assertEquals("$targetId:alpha:$documentId", mirror.path("externalId").asText())
            assertEquals("acceptance.txt", mirror.path("fileName").asText())
            assertTrue(mirror.path("downloadedBytes").asLong() > 0)
        }
    }

    @Test
    fun `dedicated compose worker delivers without an API initiated worker cycle`() {
        val editor = login("editor@alpha", "dev-editor")
        val documentId = createDraft(editor, "E2E-DEDICATED-WORKER-${UUID.randomUUID().toString().take(12)}")
            .path("document").path("id").asText()
        val workflow = postJson(
            "$apiUrl/api/documents/$documentId/submit",
            """{"reviewerId":"alpha-reviewer"}""",
            editor,
        )
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        postJson(
            "$apiUrl/api/documents/workflows/${workflow.path("workflowId").asText()}/tasks/${workflow.path("taskId").asText()}/approve",
            """{"comment":"独立 Worker 验收","deliveryProfileId":"internal"}""",
            reviewer,
        )

        val delivered = awaitPublished(documentId, editor)

        assertEquals("PUBLISHED", delivered.path("document").path("lifecycleState").asText())
        assertEquals(1, delivered.path("deliveries").size())
        assertEquals("SUCCEEDED", delivered.path("deliveries").single().path("status").asText())
        assertTrue(delivered.path("outboxEvents").all { it.path("status").asText() == "SUCCESS" })
    }

    @Test
    fun `holds a dual control route pending until its reviewer and administrator both approve`() {
        val editor = login("editor@alpha", "dev-editor")
        val documentId = createDraft(editor, "E2E-DUAL-${UUID.randomUUID().toString().take(12)}").path("document").path("id").asText()

        val workflow = postJson(
            "$apiUrl/api/documents/$documentId/submit",
            """{"reviewRouteId":"dual-control"}""",
            editor,
        )
        assertEquals(2, workflow.path("taskIds").size())
        val reviewerTaskId = workflow.path("taskIds")[0].asText()
        val administratorTaskId = workflow.path("taskIds")[1].asText()
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        postJson(
            "$apiUrl/api/documents/workflows/${workflow.path("workflowId").asText()}/tasks/$reviewerTaskId/approve",
            """{"comment":"专业审批已完成"}""",
            reviewer,
        )

        val afterReviewer = getJson("$apiUrl/api/documents/$documentId", editor)
        assertEquals("PENDING_REVIEW", afterReviewer.path("document").path("lifecycleState").asText())
        assertEquals("PENDING", afterReviewer.path("workflows").first().path("state").asText())
        assertEquals(0, afterReviewer.path("outboxEvents").size())
        assertEquals(0, afterReviewer.path("audits").size())
        assertEquals(0, afterReviewer.path("operationLogs").size())
        assertAuditActor(documentId, reviewer, "document:review:submit", "alpha-editor", "Alpha 编辑者")

        val administrator = login("admin@alpha", "dev-admin")
        postJson(
            "$apiUrl/api/documents/workflows/${workflow.path("workflowId").asText()}/tasks/$administratorTaskId/approve",
            """{"comment":"职责审批已完成","deliveryProfileId":"internal"}""",
            administrator,
        )
        val readyForDelivery = getJson("$apiUrl/api/documents/$documentId", administrator)
        assertEquals("PUBLISHING", readyForDelivery.path("document").path("lifecycleState").asText())
        assertEquals("APPROVED", readyForDelivery.path("workflows").first().path("state").asText())
        assertEquals(1, readyForDelivery.path("deliveries").size())

        post("$apiUrl/api/outbox/process?limit=20", null, administrator, "application/json")
        assertEquals("PUBLISHED", awaitPublished(documentId, administrator).path("document").path("lifecycleState").asText())
    }

    @Test
    fun `takes an offline document out of every delivered downstream target through the outbox`() {
        val editor = login("editor@alpha", "dev-editor")
        val documentId = createDraft(editor, "E2E-OFFLINE-${UUID.randomUUID().toString().take(12)}").path("document").path("id").asText()
        val workflow = postJson("$apiUrl/api/documents/$documentId/submit", """{"reviewerId":"alpha-reviewer"}""", editor)
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        postJson(
            "$apiUrl/api/documents/workflows/${workflow.path("workflowId").asText()}/tasks/${workflow.path("taskId").asText()}/approve",
            """{"comment":"发布后撤回验收","deliveryProfileId":"internal"}""",
            reviewer,
        )
        val administrator = login("admin@alpha", "dev-admin")
        post("$apiUrl/api/outbox/process?limit=20", null, administrator, "application/json")
        awaitPublished(documentId, administrator)
        assertEquals(200, platformDocumentStatus("collaboration", documentId))

        val offline = post("$apiUrl/api/documents/$documentId/offline", null, administrator, "application/json")
        assertEquals("OFFLINE", offline.path("document").path("lifecycleState").asText())
        assertTrue(offline.path("outboxEvents").any { it.path("type").asText() == "document.delivery.target.removal.requested" })

        post("$apiUrl/api/outbox/process?limit=20", null, administrator, "application/json")
        val withdrawn = awaitDeliveryRemoval(documentId, administrator)
        assertEquals("OFFLINE", withdrawn.path("document").path("lifecycleState").asText())
        assertEquals("SUCCEEDED", withdrawn.path("deliveries").single().path("removalStatus").asText())
        assertEquals(404, platformDocumentStatus("collaboration", documentId))
        assertAuditActor(documentId, reviewer, "document:offline", "alpha-admin", "Alpha 管理员")
        assertTrue(documentLogs(documentId, reviewer).any { it.path("action").asText() == "document:delivery:remove:succeeded" })

        val restored = post("$apiUrl/api/documents/$documentId/restore", null, editor, "application/json")
        assertEquals("DRAFT", restored.path("document").path("lifecycleState").asText())
        assertEquals(2, addVersion(editor, documentId, "1.1").path("versions").size())
        val replacementWorkflow = postJson(
            "$apiUrl/api/documents/$documentId/submit",
            """{"reviewerId":"alpha-reviewer"}""",
            editor,
        )
        postJson(
            "$apiUrl/api/documents/workflows/${replacementWorkflow.path("workflowId").asText()}/tasks/${replacementWorkflow.path("taskId").asText()}/approve",
            """{"comment":"更新版本审批","deliveryProfileId":"internal"}""",
            reviewer,
        )
        post("$apiUrl/api/outbox/process?limit=20", null, administrator, "application/json")
        val republished = awaitPublished(documentId, administrator)
        assertEquals(listOf(1, 2), republished.path("deliveries").map { it.path("deliveryGeneration").asInt() }.sorted())
        assertEquals(200, platformDocumentStatus("collaboration", documentId))
        assertAuditActor(documentId, reviewer, "document:restore", "alpha-editor", "Alpha 编辑者")
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
        assertAuditActor(documentId, reviewer, "document:rename", "alpha-editor", "Alpha 编辑者")
        assertAuditActor(documentId, reviewer, "document:version:add", "alpha-editor", "Alpha 编辑者")
        assertAuditActor(documentId, reviewer, "document:review:reject", "alpha-reviewer", "Alpha 审批者")
        assertAuditActor(documentId, reviewer, "document:revise", "alpha-editor", "Alpha 编辑者")
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
        postPlatformJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"RETRYABLE_FAILURE","targetId":"compliance"}""")
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        postJson(
            "$apiUrl/api/documents/workflows/${workflow.path("workflowId").asText()}/tasks/${workflow.path("taskId").asText()}/approve",
            """{"comment":"触发可重试故障","deliveryProfileId":"regulated"}""",
            reviewer,
        )
        val admin = login("admin@alpha", "dev-admin")
        post("$apiUrl/api/outbox/process?limit=20", null, admin, "application/json")
        awaitLifecycle(documentId, admin, "SYNC_ERROR")

        postPlatformJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"AVAILABLE","targetId":"compliance"}""")
        Thread.sleep(10_500)
        post("$apiUrl/api/outbox/process?limit=20", null, admin, "application/json")
        val recovered = awaitPublished(documentId, admin)
        assertTrue(recovered.path("deliveries").any { it.path("targetId").asText() == "compliance" && it.path("status").asText() == "SUCCEEDED" })
    }

    @Test
    fun `opens one downstream circuit after repeated failures then admits a recovery delivery`() {
        val editor = login("editor@alpha", "dev-editor")
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        val admin = login("admin@alpha", "dev-admin")
        postPlatformJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"RETRYABLE_FAILURE","targetId":"compliance"}""")

        var circuitWasObserved = false
        try {
            // Keep all failures on the dedicated Worker process. The development API's
            // manual endpoint owns a separate JVM-local circuit and must not participate
            // in a topology test for the scheduled Worker.
            val failedDetails = (1..4).map { index ->
                val documentId = createDraft(editor, "E2E-CIRCUIT-$index-${UUID.randomUUID().toString().take(8)}")
                    .path("document").path("id").asText()
                val workflow = postJson(
                    "$apiUrl/api/documents/$documentId/submit",
                    """{"reviewerId":"alpha-reviewer"}""",
                    editor,
                )
                postJson(
                    "$apiUrl/api/documents/workflows/${workflow.path("workflowId").asText()}/tasks/${workflow.path("taskId").asText()}/approve",
                    """{"comment":"验证连接器熔断","deliveryProfileId":"regulated"}""",
                    reviewer,
                )
                awaitLifecycle(documentId, admin, "SYNC_ERROR")
            }

            assertTrue(
                failedDetails.any { detail ->
                    delivery(detail, "compliance").path("errorMessage").asText().contains("circuit is open")
                },
                "At least one delivery must be rejected locally once the compliance circuit opens.",
            )
            circuitWasObserved = true
        } finally {
            postPlatformJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"AVAILABLE","targetId":"compliance"}""")
            if (!circuitWasObserved) Thread.sleep(CIRCUIT_COOLDOWN_MILLIS)
        }

        Thread.sleep(CIRCUIT_COOLDOWN_MILLIS)
        val recoveryDocumentId = createDraft(editor, "E2E-CIRCUIT-RECOVERY-${UUID.randomUUID().toString().take(8)}")
            .path("document").path("id").asText()
        val recoveryWorkflow = postJson(
            "$apiUrl/api/documents/$recoveryDocumentId/submit",
            """{"reviewerId":"alpha-reviewer"}""",
            editor,
        )
        postJson(
            "$apiUrl/api/documents/workflows/${recoveryWorkflow.path("workflowId").asText()}/tasks/${recoveryWorkflow.path("taskId").asText()}/approve",
            """{"comment":"验证熔断恢复","deliveryProfileId":"regulated"}""",
            reviewer,
        )
        assertEquals("SUCCEEDED", delivery(awaitPublished(recoveryDocumentId, admin), "compliance").path("status").asText())
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
        postPlatformJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"PERMANENT_FAILURE","targetId":"search"}""")
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
        postPlatformJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"PERMANENT_FAILURE","targetId":"compliance"}""")
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

        val formalStatus = getJson("$apiUrl/fileweft/v1/documents/$documentId/sync-status", admin)
        assertV1SuccessEnvelope(formalStatus)
        val formalCompliance = formalStatus.path("data").path("deliveryTargets")
            .first { it.path("targetId").asText() == "compliance" }
        assertEquals(compliance.path("id").asText(), formalCompliance.path("deliveryId").asText())
        assertTrue(formalCompliance.path("deliveryRetryable").asBoolean())
        assertTrue(!formalCompliance.path("removalRetryable").asBoolean())

        postPlatformJson("$platformUrl/platform/v1/admin/fault-mode", """{"mode":"AVAILABLE","targetId":"compliance"}""")
        val retryKey = "e2e-delivery-${UUID.randomUUID()}"
        val retryUrl = "$apiUrl/fileweft/v1/documents/$documentId/deliveries/${compliance.path("id").asText()}/retry"
        val retry = postV1(retryUrl, admin, retryKey)
        assertV1SuccessEnvelope(retry)
        assertEquals("DELIVERY", retry.path("data").path("operation").asText())
        assertEquals(retry.path("data"), postV1(retryUrl, admin, retryKey).path("data"))
        post("$apiUrl/api/outbox/process?limit=20", null, admin, "application/json")
        val recovered = awaitPublished(documentId, admin)
        assertEquals("SUCCEEDED", delivery(recovered, "compliance").path("status").asText())
    }

    @Test
    fun `serves immediate Doctor only through the redacted formal tenant and permission boundary`() {
        val editor = login("editor@alpha", "dev-editor")
        val documentId = createDraft(editor, "E2E-DOCTOR-${UUID.randomUUID().toString().take(12)}")
            .path("document").path("id").asText()
        val devDetail = getJson("$apiUrl/api/documents/$documentId", editor)
        assertTrue(!devDetail.has("doctorRecords"), "A document:read projection must not contain raw Doctor records.")
        assertLegacyDoctorRoutesUnavailable(documentId, editor)

        val immediateResponse = getResponse("$apiUrl/fileweft/v1/documents/$documentId/doctor", editor)
        assertEquals(200, immediateResponse.statusCode())
        assertPrivateDoctorResponse(immediateResponse)
        val immediate = mapper.readTree(immediateResponse.body())
        assertV1SuccessEnvelope(immediate)
        assertEquals(setOf("documentId", "status", "checks", "inspectedTime"), immediate.path("data").fieldNames().asSequence().toSet())
        assertEquals(documentId, immediate.path("data").path("documentId").asText())
        assertTrue(immediate.path("data").path("checks").any { it.path("checkerName").asText() == "permission" })
        assertTrue(immediate.path("data").path("checks").any { it.path("checkerName").asText() == "storage" })
        assertTrue(immediate.path("data").path("checks").all { check ->
            val fields = check.fieldNames().asSequence().toSet()
            fields.containsAll(setOf("checkerName", "status", "reason")) &&
                setOf("checkerName", "status", "reason", "repairSuggestion").containsAll(fields)
        })
        assertNoInternalDoctorFields(immediate)

        val viewer = login("viewer@alpha", "dev-viewer")
        val viewerResponse = getResponse("$apiUrl/fileweft/v1/documents/$documentId/doctor", viewer)
        assertEquals(403, viewerResponse.statusCode())
        assertPrivateDoctorResponse(viewerResponse)
        assertV1FailureEnvelope(mapper.readTree(viewerResponse.body()), "FORBIDDEN", "Access denied.")

        val betaReviewer = login("reviewer@beta", "dev-reviewer")
        val crossTenant = getResponse("$apiUrl/fileweft/v1/documents/$documentId/doctor", betaReviewer)
        assertEquals(404, crossTenant.statusCode())
        assertPrivateDoctorResponse(crossTenant)
        assertV1FailureEnvelope(mapper.readTree(crossTenant.body()), "NOT_FOUND", "Resource was not found.")
    }

    @Test
    fun `replays one asynchronous Doctor task and exposes only its safe formal report plus admin system diagnosis`() {
        val editor = login("editor@alpha", "dev-editor")
        val documentId = createDraft(editor, "E2E-DOCTOR-TASK-${UUID.randomUUID().toString().take(12)}")
            .path("document").path("id").asText()
        val idempotencyKey = "e2e-doctor-${UUID.randomUUID()}"
        val scheduleUrl = "$apiUrl/fileweft/v1/documents/$documentId/doctor/tasks"

        val freshResponse = postV1Response(scheduleUrl, editor, idempotencyKey)
        assertEquals(202, freshResponse.statusCode())
        assertPrivateDoctorResponse(freshResponse)
        val fresh = mapper.readTree(freshResponse.body())
        assertV1SuccessEnvelope(fresh)
        assertEquals(setOf("taskId", "documentId", "status"), fresh.path("data").fieldNames().asSequence().toSet())
        assertEquals(documentId, fresh.path("data").path("documentId").asText())
        assertEquals("PENDING", fresh.path("data").path("status").asText())
        val taskId = fresh.path("data").path("taskId").asText()
        assertTrue(taskId.isNotBlank())
        assertNoInternalDoctorFields(fresh)

        val replayResponse = postV1Response(scheduleUrl, editor, idempotencyKey)
        assertEquals(202, replayResponse.statusCode())
        assertPrivateDoctorResponse(replayResponse)
        val replay = mapper.readTree(replayResponse.body())
        assertV1SuccessEnvelope(replay)
        assertEquals(fresh.path("data"), replay.path("data"))

        val viewer = login("viewer@alpha", "dev-viewer")
        val forbiddenSchedule = postV1Response(scheduleUrl, viewer, "e2e-doctor-viewer-${UUID.randomUUID()}")
        assertEquals(403, forbiddenSchedule.statusCode())
        assertPrivateDoctorResponse(forbiddenSchedule)
        assertV1FailureEnvelope(mapper.readTree(forbiddenSchedule.body()), "FORBIDDEN", "Access denied.")

        val admin = login("admin@alpha", "dev-admin")
        post("$apiUrl/api/tasks/process?limit=20", null, admin, "application/json")

        val completed = awaitDoctorTask(documentId, taskId, editor)
        assertV1SuccessEnvelope(completed)
        assertEquals(setOf("task", "report"), completed.path("data").fieldNames().asSequence().toSet())
        assertEquals(
            setOf("id", "documentId", "status", "createdTime", "updatedTime"),
            completed.path("data").path("task").fieldNames().asSequence().toSet(),
        )
        assertEquals(taskId, completed.path("data").path("task").path("id").asText())
        assertEquals(documentId, completed.path("data").path("task").path("documentId").asText())
        assertEquals("SUCCESS", completed.path("data").path("task").path("status").asText())
        assertEquals(documentId, completed.path("data").path("report").path("documentId").asText())
        assertTrue(completed.path("data").path("report").path("checks").any { it.path("checkerName").asText() == "catalog" })
        assertNoInternalDoctorFields(completed)

        val forbiddenTask = getResponse("$apiUrl/fileweft/v1/documents/$documentId/doctor/tasks/$taskId", viewer)
        assertEquals(403, forbiddenTask.statusCode())
        assertPrivateDoctorResponse(forbiddenTask)
        assertV1FailureEnvelope(mapper.readTree(forbiddenTask.body()), "FORBIDDEN", "Access denied.")
        val betaReviewer = login("reviewer@beta", "dev-reviewer")
        val crossTenantTask = getResponse("$apiUrl/fileweft/v1/documents/$documentId/doctor/tasks/$taskId", betaReviewer)
        assertEquals(404, crossTenantTask.statusCode())
        assertPrivateDoctorResponse(crossTenantTask)
        assertV1FailureEnvelope(mapper.readTree(crossTenantTask.body()), "NOT_FOUND", "Resource was not found.")

        val detail = getJson("$apiUrl/api/documents/$documentId", admin)
        assertTrue(!detail.has("doctorRecords"))
        assertAuditActor(documentId, admin, "document:doctor:schedule", "alpha-editor", "Alpha 编辑者")

        val systemResponse = getResponse("$apiUrl/fileweft/v1/doctor", admin)
        assertEquals(200, systemResponse.statusCode())
        assertPrivateDoctorResponse(systemResponse)
        val system = mapper.readTree(systemResponse.body())
        assertV1SuccessEnvelope(system)
        assertEquals(setOf("status", "checks", "inspectedTime"), system.path("data").fieldNames().asSequence().toSet())
        assertNoInternalDoctorFields(system)
        assertTrue(!systemResponse.body().contains("\"alpha\""))

        val betaAdmin = login("admin@beta", "dev-admin")
        val betaSystemResponse = getResponse("$apiUrl/fileweft/v1/doctor", betaAdmin)
        assertEquals(200, betaSystemResponse.statusCode())
        assertPrivateDoctorResponse(betaSystemResponse)
        assertNoInternalDoctorFields(mapper.readTree(betaSystemResponse.body()))
        assertTrue(!betaSystemResponse.body().contains("\"beta\""))

        val forbiddenSystem = getResponse("$apiUrl/fileweft/v1/doctor", editor)
        assertEquals(403, forbiddenSystem.statusCode())
        assertPrivateDoctorResponse(forbiddenSystem)
        assertV1FailureEnvelope(mapper.readTree(forbiddenSystem.body()), "FORBIDDEN", "Access denied.")
    }

    @Test
    fun `serializes an Agent result only after its matching Agent task reaches success`() {
        val editor = login("editor@alpha", "dev-editor")
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        val documentId = createDraft(editor, "E2E-AGENT-PROJECTION-${UUID.randomUUID().toString().take(8)}")
            .path("document").path("id").asText()
        val pendingAgentTaskId = UUID.randomUUID().toString()
        val wrongTypeTaskId = UUID.randomUUID().toString()

        DriverManager.getConnection(databaseUrl, databaseUsername, databasePassword).use { connection ->
            try {
                insertAgentProjection(connection, documentId, pendingAgentTaskId, "agent.execute", "PENDING", "pending-hidden")
                insertAgentProjection(connection, documentId, wrongTypeTaskId, "document.doctor", "SUCCESS", "wrong-type-hidden")

                val hidden = getJson("$apiUrl/api/documents/$documentId", reviewer).path("agentResults")
                assertEquals(0, hidden.size(), "Pending or non-Agent task projections must not be serialized.")

                connection.prepareStatement(
                    "UPDATE fw_task SET task_status = 'SUCCESS', updated_time = ? WHERE tenant_id = 'alpha' AND id = ?",
                ).use { statement ->
                    statement.setLong(1, System.currentTimeMillis())
                    statement.setString(2, pendingAgentTaskId)
                    assertEquals(1, statement.executeUpdate())
                }

                val visible = getJson("$apiUrl/api/documents/$documentId", reviewer).path("agentResults")
                assertEquals(listOf(pendingAgentTaskId), visible.map { result -> result.path("taskId").asText() })
                assertTrue(visible.none { result -> result.path("taskId").asText() == wrongTypeTaskId })
            } finally {
                deleteAgentProjections(connection, pendingAgentTaskId, wrongTypeTaskId)
            }
        }
    }

    @Test
    fun `fans out a published event to a durable agent task and requires explicit suggestion confirmation`() {
        val editor = login("editor@alpha", "dev-editor")
        val documentId = createDraft(editor, "E2E-AGENT-${UUID.randomUUID().toString().take(12)}").path("document").path("id").asText()
        val workflow = postJson("$apiUrl/api/documents/$documentId/submit", """{"reviewerId":"alpha-reviewer"}""", editor)
        val reviewer = login("reviewer@alpha", "dev-reviewer")
        postJson(
            "$apiUrl/api/documents/workflows/${workflow.path("workflowId").asText()}/tasks/${workflow.path("taskId").asText()}/approve",
            """{"comment":"Agent acceptance","deliveryProfileId":"internal"}""", reviewer,
        )
        val admin = login("admin@alpha", "dev-admin")
        post("$apiUrl/api/outbox/process?limit=20", null, admin, "application/json")
        post("$apiUrl/api/tasks/process?limit=20", null, admin, "application/json")

        val result = awaitAgentResult(documentId, admin)
        val taskId = result.path("taskId").asText()
        val suggestionId = mapper.readTree(result.path("result").asText()).path("suggestions").first().path("id").asText()
        assertEquals("CLASSIFICATION", result.path("capability").asText())
        assertEquals("SUCCEEDED", result.path("status").asText())

        post("$apiUrl/api/documents/agent-results/$taskId/suggestions/$suggestionId/confirm", null, admin, "application/json")
        assertTrue(awaitAgentResult(documentId, admin).path("confirmations").any { it.path("suggestionId").asText() == suggestionId })

        val reviewerDetail = getJson("$apiUrl/api/documents/$documentId", reviewer)
        val viewer = login("viewer@alpha", "dev-viewer")
        val viewerDetail = getJson("$apiUrl/api/documents/$documentId", viewer)
        assertTrue(reviewerDetail.path("agentResults").size() > 0)
        assertEquals(0, viewerDetail.path("agentResults").size())
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
        content: ByteArray = "development acceptance payload".toByteArray(StandardCharsets.UTF_8),
        fileName: String = "acceptance.txt",
        contentType: String = "text/plain",
    ): HttpResponse<String> {
        val boundary = "FileWeft-${UUID.randomUUID()}"
        val body = ByteArrayOutputStream().apply {
            fields.forEach { (name, value) ->
                writeText("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            writeText("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\nContent-Type: $contentType\r\n\r\n")
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

    private fun insertAgentProjection(
        connection: Connection,
        documentId: String,
        taskId: String,
        taskType: String,
        taskStatus: String,
        label: String,
    ) {
        val now = System.currentTimeMillis()
        connection.prepareStatement(
            """
            INSERT INTO fw_task(
                id, tenant_id, task_type, business_id, payload_json, idempotency_key, task_status,
                retry_count, next_attempt_time, lease_expire_time, created_time, updated_time
            ) VALUES (?, 'alpha', ?, ?, '{}'::jsonb, ?, ?, 0, ?, 0, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, taskId)
            statement.setString(2, taskType)
            statement.setString(3, documentId)
            statement.setString(4, "e2e-agent-projection:$taskId")
            statement.setString(5, taskStatus)
            statement.setLong(6, now + 86_400_000L)
            statement.setLong(7, now)
            statement.setLong(8, now)
            assertEquals(1, statement.executeUpdate())
        }
        val suggestionId = UUID.randomUUID().toString()
        val resultJson =
            """{"suggestions":[{"id":"$suggestionId","type":"CLASSIFICATION","payload":{"label":"$label"}}]}"""
        connection.prepareStatement(
            """
            INSERT INTO fw_agent_result(
                id, tenant_id, task_id, capability, source_event_id, source_event_type,
                result_status, result_json, created_time, updated_time
            ) VALUES (?, 'alpha', ?, 'CLASSIFICATION', ?, 'document.published', 'SUCCEEDED', CAST(? AS jsonb), ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, UUID.randomUUID().toString())
            statement.setString(2, taskId)
            statement.setString(3, UUID.randomUUID().toString())
            statement.setString(4, resultJson)
            statement.setLong(5, now)
            statement.setLong(6, now)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun deleteAgentProjections(connection: Connection, vararg taskIds: String) {
        val placeholders = taskIds.joinToString(",") { "?" }
        connection.prepareStatement("DELETE FROM fw_agent_result WHERE tenant_id = 'alpha' AND task_id IN ($placeholders)").use { statement ->
            taskIds.forEachIndexed { index, taskId -> statement.setString(index + 1, taskId) }
            statement.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM fw_task WHERE tenant_id = 'alpha' AND id IN ($placeholders)").use { statement ->
            taskIds.forEachIndexed { index, taskId -> statement.setString(index + 1, taskId) }
            statement.executeUpdate()
        }
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

    private fun awaitDoctorTask(documentId: String, taskId: String, token: String): JsonNode {
        repeat(50) {
            val response = getResponse("$apiUrl/fileweft/v1/documents/$documentId/doctor/tasks/$taskId", token)
            assertEquals(200, response.statusCode())
            assertPrivateDoctorResponse(response)
            val task = mapper.readTree(response.body())
            assertV1SuccessEnvelope(task)
            if (task.path("data").path("task").path("status").asText() == "SUCCESS" && task.path("data").path("report").isObject) {
                return task
            }
            Thread.sleep(200)
        }
        throw AssertionError("Doctor task $taskId did not expose its completed safe report within the expected window.")
    }

    private fun assertLegacyDoctorRoutesUnavailable(documentId: String, token: String) {
        val responses = listOf(
            getResponse("$apiUrl/api/documents/$documentId/doctor", token),
            client.send(
                authorizedRequest("$apiUrl/api/documents/$documentId/doctor/tasks", token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
            ),
        )
        responses.forEach { response ->
            assertEquals(404, response.statusCode(), "Legacy Dev Doctor routes must stay unmapped.")
            LEGACY_DOCTOR_PAYLOAD_FIELDS.forEach { field ->
                assertTrue(!response.body().contains("\"$field\""), "Legacy route leaked Doctor field '$field': ${response.body()}")
            }
        }
    }

    private fun awaitDeliveryRemoval(documentId: String, token: String): JsonNode {
        repeat(50) {
            val detail = getJson("$apiUrl/api/documents/$documentId", token)
            if (detail.path("deliveries").all { it.path("removalStatus").asText() == "SUCCEEDED" }) return detail
            Thread.sleep(200)
        }
        throw AssertionError("Document $documentId did not complete downstream withdrawal within the expected window.")
    }

    private fun awaitAgentResult(documentId: String, token: String): JsonNode {
        repeat(50) {
            val result = getJson("$apiUrl/api/documents/$documentId", token).path("agentResults").firstOrNull()
            if (result != null) return result
            Thread.sleep(200)
        }
        throw AssertionError("Document $documentId did not receive an agent result within the expected window.")
    }

    private fun assertAuditActor(documentId: String, token: String, action: String, operatorId: String?, operatorName: String) {
        val audit = documentLogs(documentId, token).firstOrNull { it.path("action").asText() == action }
            ?: throw AssertionError("Audit action $action was not found.")
        assertEquals(operatorId, audit.path("operatorId").takeUnless { it.isNull }?.asText())
        assertEquals(operatorName, audit.path("operatorName").asText())
    }

    private fun delivery(detail: JsonNode, targetId: String): JsonNode =
        detail.path("deliveries").firstOrNull { it.path("targetId").asText() == targetId }
            ?: throw AssertionError("Delivery target $targetId was not found.")

    private fun getPlatform(targetId: String, documentId: String): JsonNode = response(
        platformRequest("$platformUrl/platform/v1/documents/alpha/$documentId")
            .header("X-FileWeft-Target", targetId)
            .GET()
            .build(),
    )

    private fun platformDocumentStatus(targetId: String, documentId: String): Int = client.send(
        platformRequest("$platformUrl/platform/v1/documents/alpha/$documentId")
            .header("X-FileWeft-Target", targetId)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.discarding(),
    ).statusCode()

    private fun getJson(url: String, token: String? = null): JsonNode {
        val request = HttpRequest.newBuilder(URI(url)).GET().apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        return response(request)
    }

    private fun getResponse(url: String, token: String): HttpResponse<String> = client.send(
        authorizedRequest(url, token).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
    )

    private fun getBinary(
        url: String,
        token: String,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<ByteArray> {
        val request = authorizedRequest(url, token).GET()
        headers.forEach { (name, value) -> request.header(name, value) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun authorizedRequest(url: String, token: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer $token")

    private fun jsonRequestResponse(
        method: String,
        url: String,
        body: String,
        token: String,
    ): HttpResponse<String> = client.send(
        authorizedRequest(url, token)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
    )

    private fun documentLogs(documentId: String, token: String): JsonNode {
        val envelope = getJson("$apiUrl/fileweft/v1/documents/$documentId/logs?limit=100", token)
        assertV1SuccessEnvelope(envelope)
        return envelope.path("data").path("items")
    }

    private fun downloadAuditCount(documentId: String, token: String): Int =
        documentLogs(documentId, token).count { entry ->
            entry.path("action").asText() == "document:download"
        }

    private fun assertV1SuccessEnvelope(response: JsonNode) {
        assertEquals(V1_ENVELOPE_FIELDS, response.fieldNames().asSequence().toSet())
        assertEquals("OK", response.path("code").asText())
        assertEquals("OK", response.path("message").asText())
        assertTrue(response.path("data").isContainerNode)
        assertTrue(response.path("error").isNull)
        assertTrue(response.path("traceId").isTextual && response.path("traceId").asText().isNotBlank())
    }

    private fun assertV1FailureEnvelope(response: JsonNode, code: String, message: String) {
        assertEquals(V1_ENVELOPE_FIELDS, response.fieldNames().asSequence().toSet())
        assertEquals(code, response.path("code").asText())
        assertEquals(message, response.path("message").asText())
        assertTrue(response.path("data").isNull)
        assertEquals(setOf("code", "message"), response.path("error").fieldNames().asSequence().toSet())
        assertEquals(code, response.path("error").path("code").asText())
        assertEquals(message, response.path("error").path("message").asText())
        assertTrue(response.path("traceId").isTextual && response.path("traceId").asText().isNotBlank())
    }

    private fun assertNoInternalV1Fields(response: JsonNode) {
        V1_INTERNAL_FIELDS.forEach { field ->
            assertTrue(response.findValue(field) == null, "Formal v1 response exposed internal field '$field': $response")
        }
    }

    private fun assertNoInternalDoctorFields(response: JsonNode) {
        DOCTOR_INTERNAL_FIELDS.forEach { field ->
            assertTrue(response.findValue(field) == null, "Formal Doctor response exposed internal field '$field': $response")
        }
    }

    private fun assertPrivateDoctorResponse(response: HttpResponse<*>) {
        assertJsonContentType(response)
        assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElse(""))
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""))
    }

    private fun assertV1Download(
        response: HttpResponse<ByteArray>,
        expectedContent: ByteArray,
        expectedFileName: String,
    ) {
        assertEquals(200, response.statusCode())
        assertTrue(expectedContent.contentEquals(response.body()), "Downloaded RustFS bytes did not match the uploaded payload.")
        assertEquals("text/plain", response.headers().firstValue("Content-Type").orElse(""))
        assertEquals("private, no-store", response.headers().firstValue("Cache-Control").orElse(""))
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""))
        val contentLength = response.headers().firstValue("Content-Length")
        assertTrue(contentLength.isPresent, "RustFS must provide a verified length for the acceptance payload.")
        assertEquals(expectedContent.size.toLong(), contentLength.get().toLong())
        val disposition = response.headers().firstValue("Content-Disposition").orElse("")
        assertTrue(disposition.startsWith("attachment; "))
        assertTrue(disposition.contains("filename=\"$expectedFileName\""))
        assertTrue(disposition.contains("filename*=UTF-8''$expectedFileName"))
        assertTrue(disposition.all { character -> character.code in 0x20..0x7e })
        assertTrue(!response.headers().firstValue("ETag").isPresent)
        assertTrue(!response.headers().firstValue("Accept-Ranges").isPresent)
        assertTrue(!response.headers().firstValue("Content-Range").isPresent)
        assertTrue(!response.headers().firstValue("Location").isPresent)
        assertTrue(response.headers().map().keys.none { name ->
            val normalized = name.lowercase()
            normalized.contains("hash") || normalized.contains("storage") || normalized.contains("bucket") ||
                normalized.contains("object-key")
        })
    }

    private fun assertJsonContentType(response: HttpResponse<*>) {
        assertTrue(
            response.headers().firstValue("Content-Type").orElse("").lowercase().startsWith("application/json"),
            "Expected a JSON response but received ${response.headers().firstValue("Content-Type").orElse("<missing>")}.",
        )
    }

    private fun postJson(url: String, body: String, token: String?, traceId: String? = null): JsonNode =
        post(url, body.toByteArray(StandardCharsets.UTF_8), token, "application/json", traceId)

    private fun postPlatformJson(url: String, body: String): JsonNode = response(
        platformRequest(url)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build(),
    )

    private fun postV1(url: String, token: String, idempotencyKey: String): JsonNode = response(
        authorizedRequest(url, token)
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
    )

    private fun postV1Response(url: String, token: String, idempotencyKey: String): HttpResponse<String> = client.send(
        authorizedRequest(url, token)
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
    )

    private fun platformRequest(url: String): HttpRequest.Builder = HttpRequest.newBuilder(URI(url))
        .header("X-FileWeft-Dev-Platform-Key", platformSharedSecret())

    private fun platformSharedSecret(): String = System.getenv("FILEWEFT_DEV_PLATFORM_SHARED_SECRET")
        ?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("Set FILEWEFT_DEV_PLATFORM_SHARED_SECRET before running development acceptance tests.")

    private fun putBytes(url: String, body: ByteArray, token: String): JsonNode = response(
        HttpRequest.newBuilder(URI(url))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/octet-stream")
            .header("X-FileWeft-Part-Length", body.size.toString())
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
            .build(),
    )

    private fun resumableResponse(
        method: String,
        url: String,
        token: String,
        body: ByteArray = ByteArray(0),
    ): HttpResponse<String> {
        val request = authorizedRequest(url, token)
        if (method == "PUT") {
            request.header("Content-Type", "application/octet-stream")
            request.header("X-FileWeft-Part-Length", body.size.toString())
        }
        return client.send(
            request.method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
    }

    private fun requestJson(method: String, url: String, body: String, token: String): JsonNode {
        val builder = HttpRequest.newBuilder(URI(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $token")
            .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        return response(builder.build())
    }

    private fun post(url: String, body: ByteArray?, token: String?, contentType: String, traceId: String? = null): JsonNode {
        val builder = HttpRequest.newBuilder(URI(url))
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body ?: ByteArray(0)))
        token?.let { builder.header("Authorization", "Bearer $it") }
        traceId?.let { builder.header("X-Trace-Id", it) }
        return response(builder.build())
    }

    private fun response(request: HttpRequest): JsonNode {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        assertTrue(response.statusCode() in 200..299, "HTTP ${response.statusCode()}: ${response.body()}")
        return mapper.readTree(response.body().ifBlank { "{}" })
    }

    private fun ByteArrayOutputStream.writeText(value: String) = write(value.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val CIRCUIT_COOLDOWN_MILLIS = 5_100L
        val V1_ENVELOPE_FIELDS = setOf("code", "message", "data", "error", "traceId")
        val V1_INTERNAL_FIELDS = setOf(
            "tenantId",
            "assetId",
            "fileAssetId",
            "fileObjectId",
            "storagePath",
            "storageUrl",
            "storageType",
            "storageLocation",
            "contentUrl",
            "downloadUrl",
            "contentHash",
            "bucket",
            "objectKey",
            "ownerRef",
            "connectorId",
        )
        val DOCTOR_INTERNAL_FIELDS = V1_INTERNAL_FIELDS + setOf(
            "tenantId",
            "evidence",
            "exceptionType",
            "errorMessage",
            "lastError",
            "folderId",
            "profileId",
            "targetId",
            "deliveryId",
            "externalId",
            "eventId",
            "outboxId",
            "leaseOwner",
            "leaseToken",
            "payload",
            "requestedBy",
            "operatorId",
            "operatorName",
        )
        val LEGACY_DOCTOR_PAYLOAD_FIELDS = setOf(
            "tenantId", "documentId", "taskId", "checks", "checkerName", "reason", "evidence", "repairSuggestion",
        )
    }
}
