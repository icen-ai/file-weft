package ai.icen.fw.application.document;

import ai.icen.fw.application.audit.AuditTrail;
import ai.icen.fw.application.retention.DeletionVisibilityGuard;
import ai.icen.fw.application.transaction.ApplicationTransaction;
import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.domain.document.DocumentRepository;
import ai.icen.fw.domain.file.FileObjectRepository;
import ai.icen.fw.spi.authorization.AuthorizationProvider;
import ai.icen.fw.spi.identity.UserRealmProvider;
import ai.icen.fw.spi.storage.StorageAdapter;
import ai.icen.fw.spi.tenant.TenantProvider;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDocumentDownloadVisibilityInteropTest {
    private static final Class<?>[] OLD_FULL_CONSTRUCTOR = {
        TenantProvider.class,
        UserRealmProvider.class,
        AuthorizationProvider.class,
        DocumentRepository.class,
        FileObjectRepository.class,
        StorageAdapter.class,
        ApplicationTransaction.class,
        AuditTrail.class
    };

    @Test
    void preservesOldDownloadConstructionAndAddsOneUnambiguousVisibilityConstructor() throws Exception {
        assertNotNull(DocumentDownloadService.class.getConstructor(OLD_FULL_CONSTRUCTOR));
        assertNotNull(DocumentDownloadService.class.getConstructor(
            TenantProvider.class,
            UserRealmProvider.class,
            AuthorizationProvider.class,
            DocumentRepository.class,
            FileObjectRepository.class,
            StorageAdapter.class,
            ApplicationTransaction.class,
            AuditTrail.class,
            DocumentDownloadVisibility.class
        ));
        assertNotNull(DocumentDownloadService.class.getConstructor(
            TenantProvider.class,
            UserRealmProvider.class,
            AuthorizationProvider.class,
            DocumentRepository.class,
            FileObjectRepository.class,
            StorageAdapter.class,
            ApplicationTransaction.class,
            AuditTrail.class,
            DocumentDownloadVisibility.class,
            DeletionVisibilityGuard.class
        ));
        assertEquals(
            DocumentDownloadService.class,
            DocumentDownloadService.class.getMethod(
                "withDeletionVisibility",
                TenantProvider.class,
                UserRealmProvider.class,
                AuthorizationProvider.class,
                DocumentRepository.class,
                FileObjectRepository.class,
                StorageAdapter.class,
                ApplicationTransaction.class,
                AuditTrail.class,
                DeletionVisibilityGuard.class
            ).getReturnType()
        );
        assertThrows(NoSuchMethodException.class, () -> DocumentDownloadService.class.getConstructor(
            TenantProvider.class,
            UserRealmProvider.class,
            AuthorizationProvider.class,
            DocumentRepository.class,
            FileObjectRepository.class,
            StorageAdapter.class,
            ApplicationTransaction.class,
            AuditTrail.class,
            DeletionVisibilityGuard.class
        ));
        assertThrows(NoSuchMethodException.class, () -> DocumentDownloadService.class.getConstructor(
            TenantProvider.class,
            UserRealmProvider.class,
            AuthorizationProvider.class,
            DocumentRepository.class,
            FileObjectRepository.class,
            StorageAdapter.class,
            ApplicationTransaction.class,
            DocumentDownloadVisibility.class
        ));

        boolean oldKotlinDefaultConstructorPresent = Arrays.stream(DocumentDownloadService.class.getDeclaredConstructors())
            .map(constructor -> constructor.getParameterTypes())
            .anyMatch(parameters ->
                parameters.length == OLD_FULL_CONSTRUCTOR.length + 2
                    && Arrays.equals(
                        Arrays.copyOf(parameters, OLD_FULL_CONSTRUCTOR.length),
                        OLD_FULL_CONSTRUCTOR
                    )
                    && parameters[OLD_FULL_CONSTRUCTOR.length] == int.class
                    && parameters[OLD_FULL_CONSTRUCTOR.length + 1].getName()
                        .equals("kotlin.jvm.internal.DefaultConstructorMarker")
            );
        assertTrue(oldKotlinDefaultConstructorPresent);
    }

    @Test
    void exposesAnAdditiveJavaFriendlyDownloadScopeCapability() throws Exception {
        assertNotNull(DocumentDownloadVisibility.class.getConstructor(
            DocumentFolderReadAccess.class,
            DocumentQueryRepository.class
        ));
        assertTrue(DocumentFolderReadAccess.class.isAssignableFrom(DocumentFolderDownloadAccess.class));
        assertEquals(
            Set.class,
            DocumentFolderDownloadAccess.class.getMethod(
                "readableFolderIdsForDocumentDownload",
                Identifier.class
            ).getReturnType()
        );

        Set<String> oldMethods = Arrays.stream(DocumentFolderReadAccess.class.getDeclaredMethods())
            .map(method -> method.getName())
            .collect(Collectors.toSet());
        assertEquals(
            new HashSet<>(Arrays.asList("requireFolderForDocumentRead", "readableFolderIds")),
            oldMethods
        );
    }
}
