package com.fileweft.application.document;

import com.fileweft.application.audit.AuditTrail;
import com.fileweft.application.transaction.ApplicationTransaction;
import com.fileweft.core.id.Identifier;
import com.fileweft.domain.document.DocumentRepository;
import com.fileweft.domain.file.FileObjectRepository;
import com.fileweft.spi.authorization.AuthorizationProvider;
import com.fileweft.spi.identity.UserRealmProvider;
import com.fileweft.spi.storage.StorageAdapter;
import com.fileweft.spi.tenant.TenantProvider;
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
