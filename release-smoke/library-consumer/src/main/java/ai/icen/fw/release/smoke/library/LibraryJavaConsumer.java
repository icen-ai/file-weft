package ai.icen.fw.release.smoke.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import ai.icen.fw.adapter.micrometer.MicrometerFileWeftMetrics;
import ai.icen.fw.adapter.dify.DifyKnowledgeBaseConnector;
import ai.icen.fw.adapter.oss.OssStorageAdapter;
import ai.icen.fw.agent.AgentTaskHandler;
import ai.icen.fw.agent.api.AgentRunService;
import ai.icen.fw.agent.runtime.DurableAgentRunCoordinator;
import ai.icen.fw.application.document.DocumentQueryRepository;
import ai.icen.fw.application.task.LeasedTaskHandler;
import ai.icen.fw.persistence.jdbc.JdbcDoctorReportRepository;
import ai.icen.fw.persistence.jdbc.JdbcDocumentQueryRepository;
import ai.icen.fw.migration.cli.FlowWeftMigrationExitCode;
import ai.icen.fw.retrieval.api.RetrievalAuthorizationPlanner;
import ai.icen.fw.retrieval.runtime.RetrievalRuntimeConfiguration;
import ai.icen.fw.retrieval.spi.EmbeddingProvider;
import ai.icen.fw.spi.plugin.FileWeftPlugin;
import ai.icen.fw.workflow.api.WorkflowParticipantResolver;
import ai.icen.fw.workflow.domain.WorkflowDomainEngine;
import ai.icen.fw.workflow.persistence.jdbc.JdbcWorkflowRuntimePersistence;
import ai.icen.fw.workflow.runtime.WorkflowDurableRuntime;
import ai.icen.fw.workflow.spi.WorkflowDecisionProvider;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;

/** Exercises public ABI that was previously hidden behind runtime-only POM scopes. */
public final class LibraryJavaConsumer {
    public FileWeftPlugin plugin(FileWeftPlugin plugin) {
        return plugin;
    }

    public LeasedTaskHandler taskHandler(AgentTaskHandler handler) {
        return handler;
    }

    public DocumentQueryRepository documentQueries() {
        return new JdbcDocumentQueryRepository();
    }

    public JdbcDoctorReportRepository doctorReports(ObjectMapper mapper, Clock clock) {
        return new JdbcDoctorReportRepository(mapper, clock);
    }

    public MicrometerFileWeftMetrics metrics(MeterRegistry registry) {
        return new MicrometerFileWeftMetrics(registry);
    }

    public RetrievalAuthorizationPlanner retrievalPlanner(RetrievalAuthorizationPlanner planner) {
        return planner;
    }

    public EmbeddingProvider embeddingProvider(EmbeddingProvider provider) {
        return provider;
    }

    public RetrievalRuntimeConfiguration retrievalRuntimeConfiguration() {
        return new RetrievalRuntimeConfiguration();
    }

    public AgentRunService agentRuns(AgentRunService service) {
        return service;
    }

    public Class<DurableAgentRunCoordinator> agentRuntime() {
        return DurableAgentRunCoordinator.class;
    }

    public WorkflowParticipantResolver workflowParticipants(WorkflowParticipantResolver resolver) {
        return resolver;
    }

    public WorkflowDecisionProvider workflowDecisions(WorkflowDecisionProvider provider) {
        return provider;
    }

    public Class<WorkflowDomainEngine> workflowDomainEngine() {
        return WorkflowDomainEngine.class;
    }

    public Class<WorkflowDurableRuntime> workflowRuntime() {
        return WorkflowDurableRuntime.class;
    }

    public Class<JdbcWorkflowRuntimePersistence> workflowJdbcPersistence() {
        return JdbcWorkflowRuntimePersistence.class;
    }

    public int migrationSuccessExitCode() {
        return FlowWeftMigrationExitCode.SUCCESS;
    }

    public Class<DifyKnowledgeBaseConnector> difyAdapter() {
        return DifyKnowledgeBaseConnector.class;
    }

    public Class<OssStorageAdapter> ossAdapter() {
        return OssStorageAdapter.class;
    }
}
