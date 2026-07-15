package ai.icen.fw.release.smoke.maven;

import ai.icen.fw.testkit.agent.LanguageModelProviderContractTest;
import ai.icen.fw.testkit.retrieval.CandidateRetrieverContractTest;
import org.junit.jupiter.api.Test;

/** Proves the published TestKit POMs expose their Java-friendly contracts and JUnit API. */
public final class MavenPublishedTestKitJava8Consumer {
    @Test
    public void exposesPublishedProviderContracts() {
        requireContract(LanguageModelProviderContractTest.class);
        requireContract(CandidateRetrieverContractTest.class);
    }

    private static void requireContract(Class<?> contractType) {
        if (contractType == null) {
            throw new AssertionError("Published provider contract type must be available.");
        }
    }
}
