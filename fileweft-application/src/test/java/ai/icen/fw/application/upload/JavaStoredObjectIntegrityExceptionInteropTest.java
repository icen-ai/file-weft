package ai.icen.fw.application.upload;

import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.spi.storage.StorageObjectLocation;
import ai.icen.fw.spi.storage.StorageUploadRequest;
import ai.icen.fw.spi.storage.StoredObject;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaStoredObjectIntegrityExceptionInteropTest {

    @Test
    void exposesStorageIntegrityFailuresAsServerStateFailuresToJava() {
        StorageUploadRequest request = new StorageUploadRequest(
            new Identifier("tenant-1"),
            "contract.txt",
            7L,
            "text/plain",
            "sha256:expected",
            Collections.emptyMap()
        );
        StoredObject stored = new StoredObject(
            new StorageObjectLocation("test", "objects/contract.txt"),
            7L,
            "text/plain",
            "sha256:actual"
        );

        StoredObjectIntegrityException failure = assertThrows(
            StoredObjectIntegrityException.class,
            () -> StoredObjectIntegrity.requireMatches(request, stored)
        );

        assertInstanceOf(IllegalStateException.class, failure);
    }
}
