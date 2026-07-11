package com.fileweft.application.upload;

import com.fileweft.core.id.Identifier;
import com.fileweft.spi.storage.StorageObjectLocation;
import com.fileweft.spi.storage.StorageUploadRequest;
import com.fileweft.spi.storage.StoredObject;
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
