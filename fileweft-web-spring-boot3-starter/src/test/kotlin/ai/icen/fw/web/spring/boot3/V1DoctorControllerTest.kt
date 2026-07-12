package ai.icen.fw.web.spring.boot3

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.application.doctor.DocumentDoctorQueryService
import ai.icen.fw.application.doctor.DocumentDoctorTaskQueryService
import ai.icen.fw.application.doctor.DocumentDoctorTaskReceipt
import ai.icen.fw.application.doctor.DocumentDoctorTaskView
import ai.icen.fw.application.doctor.IdempotentScheduleDocumentDoctorService
import ai.icen.fw.application.doctor.SystemDoctorService
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.idempotency.IdempotencyKeyConflictException
import ai.icen.fw.application.security.ApplicationForbiddenException
import ai.icen.fw.application.security.ApplicationUnauthenticatedException
import ai.icen.fw.application.task.BackgroundTaskStatus
import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorReport
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.observability.TraceContextProvider
import ai.icen.fw.web.runtime.v1.V1ApiResponseFactory
import ai.icen.fw.web.runtime.v1.doctor.DoctorApiFacade
import ai.icen.fw.web.spring.boot3.v1.doctor.V1DocumentDoctorController
import ai.icen.fw.web.spring.boot3.v1.doctor.V1SystemDoctorController
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertTrue

class V1DoctorControllerTest {
    @Test
    fun `serves redacted document task and system Doctor contracts with private response headers`() {
        val services = services()
        val mvc = mvc(services.facade)

        mvc.perform(get("$DOCUMENTS/document-1/doctor"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE))
            .andExpect(header().string(X_CONTENT_TYPE_OPTIONS, NOSNIFF))
            .andExpect(jsonPath("$.traceId").value(TRACE_ID.value))
            .andExpect(jsonPath("$.data.documentId").value("document-1"))
            .andExpect(jsonPath("$.data.status").value("ERROR"))
            .andExpect(jsonPath("$.data.checks[0].checkerName").value("storage"))
            .andExpect(jsonPath("$.data.checks[0].reason").value("Storage check failed."))
            .andExpect(jsonPath("$.data.checks[0].evidence").doesNotExist())
            .andExpect(content().string(not(containsString("s3://private-bucket"))))
            .andExpect(content().string(not(containsString("connector-secret"))))
            .andExpect(content().string(not(containsString("SecretConnectorException"))))

        mvc.perform(post("$DOCUMENTS/document-1/doctor/tasks").header(IDEMPOTENCY_KEY, "doctor-key-1"))
            .andExpect(status().isAccepted)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE))
            .andExpect(jsonPath("$.data.taskId").value("task-1"))
            .andExpect(jsonPath("$.data.documentId").value("document-1"))
            .andExpect(jsonPath("$.data.status").value("PENDING"))

        mvc.perform(get("$DOCUMENTS/document-1/doctor/tasks/task-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.task.id").value("task-1"))
            .andExpect(jsonPath("$.data.task.createdTime").value(10))
            .andExpect(jsonPath("$.data.report.checks[0].reason").value("Storage check failed."))
            .andExpect(jsonPath("$.data.task.lastError").doesNotExist())
            .andExpect(jsonPath("$.data.task.payload").doesNotExist())

        listOf("/fileweft/v1/doctor", "/fileweft/doctor").forEach { path ->
            mvc.perform(get(path))
                .andExpect(status().isOk)
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE))
                .andExpect(jsonPath("$.data.documentId").doesNotExist())
                .andExpect(jsonPath("$.data.tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.checks[0].checkerName").value("connector"))
        }
    }

    @Test
    fun `requires exactly one valid idempotency key without invoking the scheduler`() {
        val services = services()
        val mvc = mvc(services.facade)

        mvc.perform(post("$DOCUMENTS/document-1/doctor/tasks"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        mvc.perform(
            post("$DOCUMENTS/document-1/doctor/tasks")
                .header(IDEMPOTENCY_KEY, "doctor-key-1", "doctor-key-2"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value("Request is invalid."))
        mvc.perform(post("$DOCUMENTS/document-1/doctor/tasks").header(IDEMPOTENCY_KEY, "contains space"))
            .andExpect(status().isBadRequest)

        Mockito.verifyNoInteractions(services.scheduler)
    }

    @Test
    fun `maps authentication policy and unexpected failures to fixed public errors`() {
        val services = services()
        Mockito.`when`(services.documentDoctor.inspect(Identifier("unauthenticated")))
            .thenThrow(ApplicationUnauthenticatedException("realm-secret"))
        Mockito.`when`(services.documentDoctor.inspect(Identifier("forbidden")))
            .thenThrow(ApplicationForbiddenException("host-policy-secret"))
        Mockito.`when`(services.documentDoctor.inspect(Identifier("broken")))
            .thenThrow(IllegalStateException("jdbc://internal/password=secret"))
        Mockito.`when`(services.taskQuery.find(DOCUMENT_ID, Identifier("missing-task")))
            .thenThrow(DocumentNotFoundException(DOCUMENT_ID))
        Mockito.`when`(services.scheduler.schedule(DOCUMENT_ID, "conflicting-key"))
            .thenThrow(IdempotencyKeyConflictException())
        val mvc = mvc(services.facade)

        mvc.perform(get("$DOCUMENTS/unauthenticated/doctor"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(content().string(not(containsString("realm-secret"))))
        mvc.perform(get("$DOCUMENTS/forbidden/doctor"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.message").value("Access denied."))
            .andExpect(content().string(not(containsString("host-policy-secret"))))
        mvc.perform(get("$DOCUMENTS/broken/doctor"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(content().string(not(containsString("jdbc://"))))
            .andExpect(content().string(not(containsString("password"))))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE))
        mvc.perform(get("$DOCUMENTS/document-1/doctor/tasks/missing-task"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.message").value("Resource was not found."))
            .andExpect(content().string(not(containsString("missing-task"))))
        mvc.perform(post("$DOCUMENTS/document-1/doctor/tasks").header(IDEMPOTENCY_KEY, "conflicting-key"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("CONFLICT"))

        Mockito.`when`(services.systemDoctor.inspect())
            .thenThrow(ApplicationForbiddenException("system-policy-secret"))
        mvc.perform(get("/fileweft/v1/doctor"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.message").value("Access denied."))
            .andExpect(content().string(not(containsString("system-policy-secret"))))
    }

    @Test
    fun `returns feature unavailable when no formal Doctor capability is safely installed`() {
        val mvc = mvc(DoctorApiFacade(0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList()))

        mvc.perform(get("$DOCUMENTS/document-1/doctor"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("FEATURE_UNAVAILABLE"))
        mvc.perform(post("$DOCUMENTS/document-1/doctor/tasks").header(IDEMPOTENCY_KEY, "doctor-key-1"))
            .andExpect(status().isServiceUnavailable)
        mvc.perform(get("$DOCUMENTS/document-1/doctor/tasks/task-1"))
            .andExpect(status().isServiceUnavailable)
        mvc.perform(get("/fileweft/v1/doctor"))
            .andExpect(status().isServiceUnavailable)
    }

    @Test
    fun `rejects explicit HEAD without executing any Doctor capability`() {
        val services = services()
        val mvc = mvc(services.facade)

        listOf(
            "$DOCUMENTS/document-1/doctor" to "GET",
            "$DOCUMENTS/document-1/doctor/tasks" to "POST",
            "$DOCUMENTS/document-1/doctor/tasks/task-1" to "GET",
            "/fileweft/v1/doctor" to "GET",
            "/fileweft/doctor" to "GET",
        ).forEach { (path, allowedMethod) ->
            mvc.perform(head(path))
                .andExpect(status().isMethodNotAllowed)
                .andExpect(header().string(HttpHeaders.ALLOW, allowedMethod))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE))
        }

        Mockito.verifyNoInteractions(services.documentDoctor, services.taskQuery, services.scheduler, services.systemDoctor)
    }

    @Test
    fun `public Doctor handlers accept no tenant user role or evidence switches`() {
        val parameters = (V1DocumentDoctorController::class.java.declaredMethods +
            V1SystemDoctorController::class.java.declaredMethods)
            .filter { method -> method.name in setOf("inspect", "schedule", "task") }
            .flatMap { method -> method.parameters.toList() }

        assertTrue(parameters.none { parameter ->
            parameter.name in setOf("tenantId", "userId", "operatorId", "role", "evidence", "verbose")
        })
    }

    private fun services(): Services {
        val documentDoctor = Mockito.mock(DocumentDoctorQueryService::class.java)
        val taskQuery = Mockito.mock(DocumentDoctorTaskQueryService::class.java)
        val scheduler = Mockito.mock(IdempotentScheduleDocumentDoctorService::class.java)
        val systemDoctor = Mockito.mock(SystemDoctorService::class.java)
        val report = documentReport()
        Mockito.`when`(documentDoctor.inspect(DOCUMENT_ID)).thenReturn(report)
        Mockito.`when`(scheduler.schedule(DOCUMENT_ID, "doctor-key-1"))
            .thenReturn(DocumentDoctorTaskReceipt(TASK_ID, DOCUMENT_ID))
        Mockito.`when`(taskQuery.find(DOCUMENT_ID, TASK_ID)).thenReturn(
            DocumentDoctorTaskView(
                tenantId = TENANT_ID,
                taskId = TASK_ID,
                documentId = DOCUMENT_ID,
                status = BackgroundTaskStatus.SUCCESS,
                createdTime = 10,
                updatedTime = 20,
                report = report,
            ),
        )
        Mockito.`when`(systemDoctor.inspect()).thenReturn(
            DoctorReport(
                TENANT_ID,
                null,
                listOf(DoctorCheckResult("connector", DoctorStatus.HEALTHY, "raw connector message")),
                100,
            ),
        )
        return Services(
            DoctorApiFacade(
                catalogAccessCount = 0,
                documentDoctors = listOf(documentDoctor),
                taskQueries = listOf(taskQuery),
                flatSchedulers = listOf(scheduler),
                catalogSchedulers = emptyList(),
                systemDoctors = listOf(systemDoctor),
            ),
            documentDoctor,
            taskQuery,
            scheduler,
            systemDoctor,
        )
    }

    private fun documentReport(): DoctorReport = DoctorReport(
        TENANT_ID,
        DOCUMENT_ID,
        listOf(
            DoctorCheckResult(
                "storage",
                DoctorStatus.ERROR,
                "s3://private-bucket/customer/object",
                mapOf(
                    "credential" to "connector-secret=password",
                    "exceptionType" to "com.vendor.internal.SecretConnectorException",
                ),
                "Use connector-secret to repair private-bucket.",
            ),
        ),
        100,
    )

    private fun mvc(facade: DoctorApiFacade): MockMvc {
        val responses = V1ApiResponseFactory()
        val traces = object : TraceContextProvider {
            override fun currentTraceContext(): TraceContext = TraceContext(TRACE_ID)
        }
        return MockMvcBuilders.standaloneSetup(
            V1DocumentDoctorController(facade, responses, traces),
            V1SystemDoctorController(facade, responses, traces),
        ).setMessageConverters(MappingJackson2HttpMessageConverter(ObjectMapper())).build()
    }

    private class Services(
        val facade: DoctorApiFacade,
        val documentDoctor: DocumentDoctorQueryService,
        val taskQuery: DocumentDoctorTaskQueryService,
        val scheduler: IdempotentScheduleDocumentDoctorService,
        val systemDoctor: SystemDoctorService,
    )

    private companion object {
        const val DOCUMENTS = "/fileweft/v1/documents"
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val PRIVATE_NO_STORE = "private, no-store"
        const val X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
        const val NOSNIFF = "nosniff"
        val TENANT_ID = Identifier("tenant-1")
        val DOCUMENT_ID = Identifier("document-1")
        val TASK_ID = Identifier("task-1")
        val TRACE_ID = Identifier("trace-doctor-1")
    }
}
