package ai.icen.fw.application.upload;

import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException;
import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.spi.storage.StorageObjectLocation;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaResumableUploadSessionOwnerInteropTest {
    @Test
    void exposesTheTransactionOutcomeUnknownContractToJava() {
        IllegalStateException cause = new IllegalStateException("commit acknowledgement failed");
        ApplicationTransactionOutcomeUnknownException failure =
            new ApplicationTransactionOutcomeUnknownException(cause);

        assertSame(cause, failure.getCause());
        assertEquals(ApplicationTransactionOutcomeUnknownException.DEFAULT_MESSAGE, failure.getMessage());
    }

    @Test
    void exposesJavaFriendlyCommandAndMaintenanceOverloads() throws Exception {
        StartResumableUploadCommand command = new StartResumableUploadCommand(
            "contract.pdf",
            7L,
            "DOCUMENT",
            "client-upload-1"
        );
        assertNull(command.getContentType());
        assertNull(command.getContentHash());
        assertEquals(Collections.emptyMap(), command.getMetadata());

        ResumableUploadService.class.getMethod("cleanupExpired");
        ResumableUploadService.class.getMethod("inspectStalledCompletionsAsSystem");
        ResumableUploadService.class.getMethod("inspectStalledCompletions");
    }

    @Test
    void preservesTheReleasedFullConstructorAndExposesTheAdditiveOwner() {
        ResumableUploadSession legacy = new ResumableUploadSession(
            new Identifier("session-1"),
            new Identifier("tenant-1"),
            "key-1",
            new Identifier("storage-upload-1"),
            new StorageObjectLocation("s3", "tenant-1/session-1"),
            new Identifier("file-1"),
            new Identifier("asset-1"),
            "contract.pdf",
            7L,
            "DOCUMENT",
            "application/pdf",
            null,
            Collections.emptyMap(),
            ResumableUploadSessionStatus.ACTIVE,
            1_000L,
            null,
            null,
            100L,
            100L
        );
        assertNull(legacy.getOwnerId());

        ResumableUploadSession owned = new ResumableUploadSession(
            new Identifier("session-2"),
            new Identifier("tenant-1"),
            "key-2",
            new Identifier("storage-upload-2"),
            new StorageObjectLocation("s3", "tenant-1/session-2"),
            new Identifier("file-2"),
            new Identifier("asset-2"),
            "contract.pdf",
            7L,
            "DOCUMENT",
            "application/pdf",
            null,
            Collections.emptyMap(),
            ResumableUploadSessionStatus.ACTIVE,
            1_000L,
            null,
            null,
            100L,
            100L,
            "directory/user:Alpha"
        );
        assertEquals("directory/user:Alpha", owned.getOwnerId());

        ResumableUploadSession ownedWithJavaDefaults = new ResumableUploadSession(
            new Identifier("session-3"),
            new Identifier("tenant-1"),
            "key-3",
            new Identifier("storage-upload-3"),
            new StorageObjectLocation("s3", "tenant-1/session-3"),
            new Identifier("file-3"),
            new Identifier("asset-3"),
            "contract.pdf",
            7L,
            "DOCUMENT",
            1_000L,
            100L,
            100L,
            "directory/user:Beta"
        );
        assertEquals("directory/user:Beta", ownedWithJavaDefaults.getOwnerId());
        assertNull(ownedWithJavaDefaults.getContentType());
        assertEquals(ResumableUploadSessionStatus.ACTIVE, ownedWithJavaDefaults.getStatus());
        assertThrows(
            NoSuchMethodException.class,
            () -> ResumableUploadSession.class.getMethod("setOwnerId", String.class)
        );
    }
}
