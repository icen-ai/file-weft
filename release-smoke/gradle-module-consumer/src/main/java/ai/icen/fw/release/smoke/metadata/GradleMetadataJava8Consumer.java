package ai.icen.fw.release.smoke.metadata;

import ai.icen.fw.agent.api.AgentRunService;
import ai.icen.fw.adapter.dify.DifyKnowledgeBaseConnector;
import ai.icen.fw.adapter.oss.OssStorageAdapter;
import ai.icen.fw.migration.cli.FlowWeftMigrationExitCode;
import ai.icen.fw.retrieval.api.RetrievalAuthorizationPlanner;
import ai.icen.fw.retrieval.runtime.RetrievalRuntimeConfiguration;
import ai.icen.fw.testkit.agent.LanguageModelProviderContractTest;
import ai.icen.fw.testkit.retrieval.CandidateRetrieverContractTest;
import ai.icen.fw.workflow.api.WorkflowParticipantResolver;
import org.junit.jupiter.api.Test;

/** Compilation proves Gradle selected usable Java 8 API JAR variants, not empty artifacts. */
public final class GradleMetadataJava8Consumer {
    public RetrievalAuthorizationPlanner retrievalPlanner(RetrievalAuthorizationPlanner planner) {
        return planner;
    }

    public AgentRunService agentRuns(AgentRunService service) {
        return service;
    }

    public RetrievalRuntimeConfiguration retrievalRuntime() {
        return new RetrievalRuntimeConfiguration();
    }

    public int migrationSuccessExitCode() {
        return FlowWeftMigrationExitCode.SUCCESS;
    }

    public Class<DifyKnowledgeBaseConnector> difyConnector() {
        return DifyKnowledgeBaseConnector.class;
    }

    public Class<OssStorageAdapter> ossStorage() {
        return OssStorageAdapter.class;
    }

    public Class<LanguageModelProviderContractTest> agentProviderContract() {
        return LanguageModelProviderContractTest.class;
    }

    public Class<CandidateRetrieverContractTest> retrievalProviderContract() {
        return CandidateRetrieverContractTest.class;
    }

    public Class<Test> transitiveJunitApi() {
        return Test.class;
    }

    public WorkflowParticipantResolver workflowParticipants(WorkflowParticipantResolver resolver) {
        return resolver;
    }
}
