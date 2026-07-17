package ai.icen.fw.release.smoke.maven;

import ai.icen.fw.agent.api.AgentRunService;
import ai.icen.fw.agent.runtime.DurableAgentRunCoordinator;
import ai.icen.fw.adapter.dify.DifyKnowledgeBaseConnector;
import ai.icen.fw.adapter.oss.OssStorageAdapter;
import ai.icen.fw.core.id.Identifier;
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

/** Compiles old and additive 1.0 APIs using Maven's POM-only dependency model. */
public final class MavenPomJava8Consumer {
    public Identifier legacyIdentifier(Identifier identifier) {
        return identifier;
    }

    public FileWeftPlugin legacyPlugin(FileWeftPlugin plugin) {
        return plugin;
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
