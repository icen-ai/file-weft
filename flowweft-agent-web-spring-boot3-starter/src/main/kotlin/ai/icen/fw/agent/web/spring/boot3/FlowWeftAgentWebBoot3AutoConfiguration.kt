package ai.icen.fw.agent.web.spring.boot3

import ai.icen.fw.agent.web.api.AgentConfigurationWebApplicationPort
import ai.icen.fw.agent.web.api.AgentConversationWebApplicationPort
import ai.icen.fw.agent.web.api.AgentEvaluationWebApplicationPort
import ai.icen.fw.agent.web.api.AgentRunWebApplicationPort
import ai.icen.fw.agent.web.api.AgentToolConfirmationWebApplicationPort
import ai.icen.fw.agent.web.api.AgentWebRoute
import ai.icen.fw.agent.web.api.AgentWebTrustedContextProvider
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.RestController

/**
 * Secure servlet auto-configuration. Merely adding the dependency exposes no route: a host must
 * deliberately supply exactly one trusted context provider.
 */
@AutoConfiguration
@ConditionalOnClass(RestController::class, ObjectMapper::class, AgentWebRoute::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(AgentWebTrustedContextProvider::class, ObjectMapper::class)
@ConditionalOnProperty(prefix = "flowweft.agent.web", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class FlowWeftAgentWebBoot3AutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(FlowWeftAgentWebBoot3ApplicationPorts::class)
    fun flowWeftAgentWebBoot3ApplicationPorts(
        conversations: ObjectProvider<AgentConversationWebApplicationPort>,
        runs: ObjectProvider<AgentRunWebApplicationPort>,
        confirmations: ObjectProvider<AgentToolConfirmationWebApplicationPort>,
        configuration: ObjectProvider<AgentConfigurationWebApplicationPort>,
        evaluations: ObjectProvider<AgentEvaluationWebApplicationPort>,
    ): FlowWeftAgentWebBoot3ApplicationPorts = FlowWeftAgentWebBoot3ApplicationPorts(
        conversations.onlyCandidate(),
        runs.onlyCandidate(),
        confirmations.onlyCandidate(),
        configuration.onlyCandidate(),
        evaluations.onlyCandidate(),
    )

    @Bean
    @ConditionalOnMissingBean(FlowWeftAgentWebBoot3JsonCodec::class)
    fun flowWeftAgentWebBoot3JsonCodec(objectMapper: ObjectMapper): FlowWeftAgentWebBoot3JsonCodec =
        FlowWeftAgentWebBoot3JsonCodec(objectMapper)

    @Bean
    @ConditionalOnMissingBean(FlowWeftAgentWebBoot3Controller::class)
    fun flowWeftAgentWebBoot3Controller(
        ports: FlowWeftAgentWebBoot3ApplicationPorts,
        contexts: ObjectProvider<AgentWebTrustedContextProvider>,
        codec: FlowWeftAgentWebBoot3JsonCodec,
    ): FlowWeftAgentWebBoot3Controller = FlowWeftAgentWebBoot3Controller(
        ports,
        contexts.exactlyOne("FlowWeft Agent Web requires exactly one trusted context provider."),
        codec,
    )
}

class FlowWeftAgentWebBoot3ApplicationPorts(
    val conversations: AgentConversationWebApplicationPort?,
    val runs: AgentRunWebApplicationPort?,
    val confirmations: AgentToolConfirmationWebApplicationPort?,
    val configuration: AgentConfigurationWebApplicationPort?,
    val evaluations: AgentEvaluationWebApplicationPort?,
)

private fun <T : Any> ObjectProvider<T>.onlyCandidate(): T? {
    val iterator = stream().iterator()
    if (!iterator.hasNext()) return null
    val candidate = iterator.next()
    return if (iterator.hasNext()) null else candidate
}

private fun <T : Any> ObjectProvider<T>.exactlyOne(message: String): T =
    onlyCandidate() ?: throw IllegalStateException(message)
