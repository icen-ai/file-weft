package ai.icen.fw.agent.web.spring.boot3

import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentRunStatus
import ai.icen.fw.agent.web.api.AgentConfigurationWebApplicationPort
import ai.icen.fw.agent.web.api.AgentConversationWebApplicationPort
import ai.icen.fw.agent.web.api.AgentRunWebApplicationPort
import ai.icen.fw.agent.web.api.AgentWebApplicationResult
import ai.icen.fw.agent.web.api.AgentWebCommandReceiptDto
import ai.icen.fw.agent.web.api.AgentWebCursor
import ai.icen.fw.agent.web.api.AgentWebDurableCursor
import ai.icen.fw.agent.web.api.AgentWebDurablePage
import ai.icen.fw.agent.web.api.AgentWebErrorCode
import ai.icen.fw.agent.web.api.AgentWebRoute
import ai.icen.fw.agent.web.api.AgentWebRunEventDto
import ai.icen.fw.agent.web.api.AgentWebRunEventType
import ai.icen.fw.agent.web.api.AgentWebTrustedContext
import ai.icen.fw.agent.web.api.AgentWebTrustedContextProvider
import ai.icen.fw.core.id.Identifier
import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import java.lang.reflect.Proxy
import kotlin.test.assertEquals

class FlowWeftAgentWebBoot3ControllerTest {
    @Test
    fun `controller exposes exactly the committed route catalog`() {
        val base = FlowWeftAgentWebBoot3Controller::class.java.getAnnotation(RequestMapping::class.java).value.single()
        val actual = FlowWeftAgentWebBoot3Controller::class.java.declaredMethods.flatMap { method ->
            val get = method.getAnnotation(GetMapping::class.java)?.value?.map { "GET" to base + it }.orEmpty()
            val post = method.getAnnotation(PostMapping::class.java)?.value?.map { "POST" to base + it }.orEmpty()
            val put = method.getAnnotation(PutMapping::class.java)?.value?.map { "PUT" to base + it }.orEmpty()
            get + post + put
        }.toSet()
        val expected = AgentWebRoute.all().map { it.method to it.pathTemplate }.toSet()
        assertEquals(expected, actual)
        assertEquals(25, actual.size)
    }

    @Test
    fun `fails closed for missing preconditions hidden resources and unknown outcomes`() {
        val conversations = proxy(AgentConversationWebApplicationPort::class.java) { method, _ ->
            if (method == "get") AgentWebApplicationResult.hidden<Any>() else AgentWebApplicationResult.unsupported<Any>()
        }
        val configuration = proxy(AgentConfigurationWebApplicationPort::class.java) { _, _ ->
            AgentWebApplicationResult.failure<Any>(AgentWebErrorCode.OUTCOME_UNKNOWN)
        }
        val mvc = mvc(conversations = conversations, configuration = configuration)

        mvc.perform(
            post("/flowweft/v1/agent/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().`is`(428))
            .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"))

        mvc.perform(get("/flowweft/v1/agent/conversations/hidden"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))

        mvc.perform(get("/flowweft/v1/agent/doctor"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("OUTCOME_UNKNOWN"))
            .andExpect(header().string("Cache-Control", "private, no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))

        val failingConfiguration = proxy(AgentConfigurationWebApplicationPort::class.java) { _, _ ->
            throw IllegalArgumentException("provider-secret-response")
        }
        mvc(configuration = failingConfiguration).perform(get("/flowweft/v1/agent/doctor"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(content().string(not(containsString("provider-secret-response"))))
    }

    @Test
    fun `rejects identity fields and returns strong etag without reflecting raw keys`() {
        val runs = proxy(AgentRunWebApplicationPort::class.java) { method, _ ->
            if (method == "cancel") {
                AgentWebApplicationResult.success(AgentWebCommandReceiptDto("RUN", Identifier("run-1"), 7L, "CANCELLED"))
            } else {
                AgentWebApplicationResult.unsupported<Any>()
            }
        }
        val mvc = mvc(runs = runs)

        mvc.perform(
            post("/flowweft/v1/agent/conversations")
                .writeHeaders("raw-browser-key")
                .content("""{"capabilityId":"chat","defaultBudget":${budget()},"tenantId":"attacker"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(content().string(not(containsString("attacker"))))

        mvc.perform(
            post("/flowweft/v1/agent/runs/run-1/cancel")
                .writeHeaders("raw-browser-key")
                .content("""{"reasonCode":"USER_CANCELLED"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"fw-agent-7\""))
            .andExpect(jsonPath("$.data.resourceId.value").value("run-1"))
            .andExpect(content().string(not(containsString("raw-browser-key"))))
    }

    @Test
    fun `serializes durable event batches as recoverable sse`() {
        val now = System.currentTimeMillis()
        val page = AgentWebDurablePage(
            Identifier("run-1"),
            listOf(AgentWebRunEventDto(Identifier("run-1"), 4L, now, AgentWebRunEventType.STATUS, 2L, AgentRunStatus.RUNNING)),
            AgentWebDurableCursor(
                Identifier("run-1"),
                5L,
                AgentWebCursor.of("opaque-cursor-5"),
                now,
                now + 60_000L,
            ),
        )
        val runs = proxy(AgentRunWebApplicationPort::class.java) { method, _ ->
            if (method == "listEvents") AgentWebApplicationResult.success(page)
            else AgentWebApplicationResult.unsupported<Any>()
        }

        mvc(runs = runs).perform(
            get("/flowweft/v1/agent/runs/run-1/events").accept("text/event-stream"),
        )
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
            .andExpect(content().string(containsString("id: 4")))
            .andExpect(content().string(containsString("event: flowweft.cursor")))
            .andExpect(content().string(containsString("opaque-cursor-5")))
    }

    @Test
    fun `returns unauthenticated when host has no verified context`() {
        val controller = controller(contexts = AgentWebTrustedContextProvider { null })
        MockMvcBuilders.standaloneSetup(controller).build()
            .perform(get("/flowweft/v1/agent/doctor"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
    }

    private fun mvc(
        conversations: AgentConversationWebApplicationPort? = null,
        runs: AgentRunWebApplicationPort? = null,
        configuration: AgentConfigurationWebApplicationPort? = null,
    ) = MockMvcBuilders.standaloneSetup(controller(conversations, runs, configuration)).build()

    private fun controller(
        conversations: AgentConversationWebApplicationPort? = null,
        runs: AgentRunWebApplicationPort? = null,
        configuration: AgentConfigurationWebApplicationPort? = null,
        contexts: AgentWebTrustedContextProvider = trustedProvider(),
    ): FlowWeftAgentWebBoot3Controller = FlowWeftAgentWebBoot3Controller(
        FlowWeftAgentWebBoot3ApplicationPorts(conversations, runs, null, configuration, null),
        contexts,
        FlowWeftAgentWebBoot3JsonCodec(ObjectMapper()),
    )

    private fun trustedProvider(): AgentWebTrustedContextProvider = AgentWebTrustedContextProvider {
        val now = System.currentTimeMillis()
        AgentWebTrustedContext.authenticated(
            AgentRunContext(
                Identifier("tenant-1"),
                Identifier("user-1"),
                "USER",
                Identifier("request-1"),
                now,
            ),
            Identifier("authentication-1"),
            "revision-1",
            now + 60_000L,
            digest(),
        )
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.writeHeaders(key: String) =
        contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key)
            .header("If-Match", "\"fw-agent-0\"")

    private fun budget(): String =
        """{"maximumInputTokens":100,"maximumOutputTokens":100,"maximumModelCalls":2,"maximumToolCalls":1,"maximumDurationMillis":60000}"""

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> proxy(
        type: Class<T>,
        invocation: (String, Array<out Any?>) -> Any,
    ): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
        when (method.name) {
            "toString" -> "AgentWebPortTestDouble"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.firstOrNull()
            else -> invocation(method.name, arguments ?: emptyArray())
        }
    } as T

    private fun digest(): String = "0".repeat(64)
}
