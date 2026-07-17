package ai.icen.fw.release.smoke.library

import com.fasterxml.jackson.databind.ObjectMapper
import ai.icen.fw.adapter.micrometer.MicrometerFileWeftMetrics
import ai.icen.fw.adapter.dify.DifyKnowledgeBaseConnector
import ai.icen.fw.adapter.oss.OssStorageAdapter
import ai.icen.fw.agent.AgentTaskHandler
import ai.icen.fw.agent.api.AgentRunService
import ai.icen.fw.agent.runtime.DurableAgentRunCoordinator
import ai.icen.fw.application.document.DocumentQueryRepository
import ai.icen.fw.application.task.LeasedTaskHandler
import ai.icen.fw.persistence.jdbc.JdbcDoctorReportRepository
import ai.icen.fw.persistence.jdbc.JdbcDocumentQueryRepository
import ai.icen.fw.migration.cli.FlowWeftMigrationExitCode
import ai.icen.fw.retrieval.api.RetrievalAuthorizationPlanner
import ai.icen.fw.retrieval.runtime.RetrievalRuntimeConfiguration
import ai.icen.fw.retrieval.spi.EmbeddingProvider
import ai.icen.fw.spi.plugin.FileWeftPlugin
import ai.icen.fw.workflow.api.WorkflowParticipantResolver
import ai.icen.fw.workflow.domain.WorkflowDomainEngine
import ai.icen.fw.workflow.persistence.jdbc.JdbcWorkflowRuntimePersistence
import ai.icen.fw.workflow.runtime.WorkflowDurableRuntime
import ai.icen.fw.workflow.spi.WorkflowDecisionProvider
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock

/** Exercises the same Maven-facing public ABI from Kotlin. */
class LibraryKotlinConsumer {
    fun plugin(plugin: FileWeftPlugin): FileWeftPlugin = plugin

    fun taskHandler(handler: AgentTaskHandler): LeasedTaskHandler = handler

    fun documentQueries(): DocumentQueryRepository = JdbcDocumentQueryRepository()

    fun doctorReports(mapper: ObjectMapper, clock: Clock) = JdbcDoctorReportRepository(mapper, clock)

    fun metrics(registry: MeterRegistry) = MicrometerFileWeftMetrics(registry)

    fun retrievalPlanner(planner: RetrievalAuthorizationPlanner): RetrievalAuthorizationPlanner = planner

    fun embeddingProvider(provider: EmbeddingProvider): EmbeddingProvider = provider

    fun retrievalRuntimeConfiguration(): RetrievalRuntimeConfiguration = RetrievalRuntimeConfiguration()

    fun agentRuns(service: AgentRunService): AgentRunService = service

    fun agentRuntime(): Class<DurableAgentRunCoordinator> = DurableAgentRunCoordinator::class.java

    fun workflowParticipants(resolver: WorkflowParticipantResolver): WorkflowParticipantResolver = resolver

    fun workflowDecisions(provider: WorkflowDecisionProvider): WorkflowDecisionProvider = provider

    fun workflowDomainEngine(): Class<WorkflowDomainEngine> = WorkflowDomainEngine::class.java

    fun workflowRuntime(): Class<WorkflowDurableRuntime> = WorkflowDurableRuntime::class.java

    fun workflowJdbcPersistence(): Class<JdbcWorkflowRuntimePersistence> =
        JdbcWorkflowRuntimePersistence::class.java

    fun migrationSuccessExitCode(): Int = FlowWeftMigrationExitCode.SUCCESS

    fun difyAdapter(): Class<DifyKnowledgeBaseConnector> = DifyKnowledgeBaseConnector::class.java

    fun ossAdapter(): Class<OssStorageAdapter> = OssStorageAdapter::class.java
}
