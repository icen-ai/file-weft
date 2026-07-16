package ai.icen.fw.workflow.web.spring.boot2

import ai.icen.fw.workflow.web.api.WorkflowDefinitionWebApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowInstanceWebApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowTaskWebApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowWebApplicationResult
import ai.icen.fw.workflow.web.api.WorkflowWebCapabilitiesDto
import ai.icen.fw.workflow.web.api.WorkflowWebCapabilityApplicationPort
import ai.icen.fw.workflow.web.api.WorkflowWebCapabilityDto
import ai.icen.fw.workflow.web.api.WorkflowWebCommandReceiptDto
import ai.icen.fw.workflow.web.api.WorkflowWebTrustedContext
import ai.icen.fw.workflow.web.api.WorkflowWebTrustedContextProvider
import ai.icen.fw.workflow.web.runtime.WorkflowWebControllerRuntime
import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

class FlowWeftWorkflowWebBoot2ControllerTest {
    private val observedContexts = mutableListOf<WorkflowWebTrustedContext>()

    @Test
    fun `serves capabilities with trusted identity and mandatory response headers`() {
        val capabilities = object : WorkflowWebCapabilityApplicationPort {
            override fun listCapabilities(context: WorkflowWebTrustedContext) =
                WorkflowWebApplicationResult.success(
                    WorkflowWebCapabilitiesDto(
                        listOf(WorkflowWebCapabilityDto("workflow.definition.read", true, null)),
                    ),
                ).also { observedContexts += context }
        }

        mvc(capabilities = capabilities).perform(
            get("/flowweft/v1/workflows/capabilities")
                .accept(MediaType.APPLICATION_JSON)
                .header("X-Tenant-Id", "attacker-tenant")
                .header("X-Principal-Id", "attacker-user"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.traceId").value("trace-1"))
            .andExpect(jsonPath("$.data.capabilities[0].capabilityId").value("workflow.definition.read"))
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(header().string("Pragma", "no-cache"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(content().string(not(containsString("attacker-tenant"))))

        assertEquals("tenant-1", observedContexts.single().tenantId)
        assertEquals("user-1", observedContexts.single().principalId)
    }

    @Test
    fun `converts definition instance and task writes into validated commands`() {
        var definitionCalls = 0
        var instanceCalls = 0
        var taskCalls = 0
        val definitions = proxy(WorkflowDefinitionWebApplicationPort::class.java) { method, arguments ->
            if (method == "putDraft") {
                definitionCalls += 1
                observedContexts += arguments[0] as WorkflowWebTrustedContext
            }
            successReceipt("DEFINITION", "definition-1")
        }
        val instances = proxy(WorkflowInstanceWebApplicationPort::class.java) { method, arguments ->
            if (method == "start") {
                instanceCalls += 1
                observedContexts += arguments[0] as WorkflowWebTrustedContext
            }
            successReceipt("INSTANCE", "instance-1")
        }
        val tasks = proxy(WorkflowTaskWebApplicationPort::class.java) { method, arguments ->
            if (method == "decide") {
                taskCalls += 1
                observedContexts += arguments[0] as WorkflowWebTrustedContext
            }
            successReceipt("TASK", "task-1")
        }
        val mvc = mvc(definitions = definitions, instances = instances, tasks = tasks)

        mvc.perform(
            put("/flowweft/v1/workflows/definitions/definition-1/draft")
                .writeHeaders()
                .content(
                    """{"key":"leave","version":"1","title":"Leave","codecId":"json","codecVersion":"1","definitionSource":"{}","sourceDigest":"${digest()}"}""",
                ),
        ).andExpect(status().isOk).andExpect(jsonPath("$.data.resourceId").value("definition-1"))

        mvc.perform(
            post("/flowweft/v1/workflows/instances")
                .writeHeaders()
                .content(
                    """{"definitionKey":"leave","definitionVersion":"1","subject":{"type":"LEAVE","id":"leave-1","revision":"1","digest":"${digest()}"}}""",
                ),
        ).andExpect(status().isOk).andExpect(jsonPath("$.data.resourceId").value("instance-1"))

        mvc.perform(
            post("/flowweft/v1/workflows/tasks/task-1/decisions")
                .writeHeaders()
                .content("""{"action":"APPROVE"}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.data.resourceId").value("task-1"))

        assertEquals(1, definitionCalls)
        assertEquals(1, instanceCalls)
        assertEquals(1, taskCalls)
        assertEquals(setOf("tenant-1"), observedContexts.map { it.tenantId }.toSet())
    }

    @Test
    fun `fails closed for absent ports duplicate fields and unsupported operations`() {
        val mvc = mvc()

        mvc.perform(
            post("/flowweft/v1/workflows/tasks/task-1/decisions")
                .writeHeaders()
                .content("""{"action":"APPROVE"}"""),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("CAPABILITY_UNSUPPORTED"))
            .andExpect(header().string("Cache-Control", "private, no-store"))

        mvc.perform(
            post("/flowweft/v1/workflows/tasks/task-1/decisions")
                .writeHeaders()
                .content("""{"action":"APPROVE","action":"REJECT"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        mvc.perform(
            get("/flowweft/v1/workflows/doctor").accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("CAPABILITY_UNSUPPORTED"))

        mvc.perform(get("/flowweft/v1/workflows/capabilities").accept(MediaType.TEXT_PLAIN))
            .andExpect(status().isNotAcceptable)
            .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))

        mvc.perform(
            post("/flowweft/v1/workflows/tasks/task-1/decisions")
                .contentType(MediaType.TEXT_PLAIN)
                .header("Idempotency-Key", "operation-1")
                .header("If-Match", "\"fw-0\"")
                .content("{}"),
        )
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
    }

    @Test
    fun `returns unauthenticated when trusted provider has no verified context`() {
        val controller = controller(contextProvider = WorkflowWebTrustedContextProvider { null })
        MockMvcBuilders.standaloneSetup(controller).build()
            .perform(get("/flowweft/v1/workflows/capabilities"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
    }

    private fun mvc(
        capabilities: WorkflowWebCapabilityApplicationPort? = null,
        definitions: WorkflowDefinitionWebApplicationPort? = null,
        instances: WorkflowInstanceWebApplicationPort? = null,
        tasks: WorkflowTaskWebApplicationPort? = null,
    ): MockMvc = MockMvcBuilders.standaloneSetup(
        controller(capabilities, definitions, instances, tasks),
    ).build()

    private fun controller(
        capabilities: WorkflowWebCapabilityApplicationPort? = null,
        definitions: WorkflowDefinitionWebApplicationPort? = null,
        instances: WorkflowInstanceWebApplicationPort? = null,
        tasks: WorkflowTaskWebApplicationPort? = null,
        contextProvider: WorkflowWebTrustedContextProvider = trustedProvider(),
    ): FlowWeftWorkflowWebBoot2Controller = FlowWeftWorkflowWebBoot2Controller(
        WorkflowWebControllerRuntime(contextProvider),
        FlowWeftWorkflowWebBoot2ApplicationPorts(capabilities, definitions, instances, tasks, null, null),
        FlowWeftWorkflowWebBoot2JsonCodec(ObjectMapper()),
    )

    private fun trustedProvider(): WorkflowWebTrustedContextProvider = WorkflowWebTrustedContextProvider {
        WorkflowWebTrustedContext.authenticated(
            "tenant-1", "USER", "user-1", "authentication-1", digest(), "trace-1",
        )
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.writeHeaders() =
        contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "operation-1")
            .header("If-Match", "\"fw-0\"")

    private fun successReceipt(type: String, id: String): WorkflowWebApplicationResult<WorkflowWebCommandReceiptDto> =
        WorkflowWebApplicationResult.success(WorkflowWebCommandReceiptDto(type, id, 1L, "ACTIVE"))

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> proxy(
        type: Class<T>,
        invocation: (String, Array<out Any?>) -> Any,
    ): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
        when (method.name) {
            "toString" -> "WorkflowWebPortTestDouble"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.firstOrNull()
            else -> invocation(method.name, arguments ?: emptyArray())
        }
    } as T

    private fun digest(): String = "0".repeat(64)
}
