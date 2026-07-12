package ai.icen.fw.domain.file;

import ai.icen.fw.core.id.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaFileAssetMutationRepositoryInteropTest {
    @Test
    void exposesTheMutationReadAsAnAdditiveJavaFriendlyCapability() throws Exception {
        assertTrue(FileAssetRepository.class.isAssignableFrom(FileAssetMutationRepository.class));
        assertEquals(
            FileAsset.class,
            FileAssetMutationRepository.class.getMethod(
                "findForMutation",
                Identifier.class,
                Identifier.class
            ).getReturnType()
        );
    }
}
