package ai.icen.fw.spi.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.icen.fw.core.id.Identifier;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PresignedUploadStorageAdapterJavaCompatibilityTest {
    @Test
    void exposesJavaFriendlyConstructorsGettersAndOptionalCapability() throws NoSuchMethodException {
        StorageContentChecksum checksum = new StorageContentChecksum("MD5", "CY9rzUYh03PK3k6DJie09g==");
        PresignedUploadGrantRequest request = new PresignedUploadGrantRequest(
            new Identifier("binding-1"),
            new Identifier("tenant-1"),
            "contract.txt",
            7L,
            "text/plain",
            "sha256:239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5",
            checksum,
            Collections.singletonMap("business", "legal"),
            Duration.ofMinutes(5)
        );
        StorageObjectLocation location = new StorageObjectLocation("test", "objects/key");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/plain");
        PresignedUploadGrant grant = new PresignedUploadGrant(
            location,
            URI.create("https://storage.example/objects/key?signature=opaque"),
            headers,
            1_000L
        );
        PresignedUploadFinalizeRequest finalizeRequest = new PresignedUploadFinalizeRequest(
            request.getBindingId(),
            request.getTenantId(),
            location,
            request.getContentLength(),
            request.getContentType(),
            request.getContentHash(),
            checksum,
            request.getMetadata()
        );
        PresignedUploadReissueRequest reissueRequest = new PresignedUploadReissueRequest(
            request.getBindingId(),
            request.getTenantId(),
            location,
            request.getContentLength(),
            request.getContentType(),
            request.getContentHash(),
            checksum,
            request.getMetadata(),
            headers,
            1_000L
        );
        PresignedUploadCleanupRequest cleanupRequest = new PresignedUploadCleanupRequest(
            request.getBindingId(), request.getTenantId(), location
        );
        StorageObjectLocation boundLocation = new StorageObjectLocation("test", "bound/version-1");
        StoredObject stored = new StoredObject(boundLocation, 7L, "text/plain", request.getContentHash());
        PresignedUploadFinalization finalization = new PresignedUploadFinalization(
            request.getTenantId(),
            request.getBindingId(),
            location,
            stored,
            "version-1",
            checksum,
            request.getMetadata()
        );
        PresignedUploadStorageAdapter adapter = new PresignedUploadStorageAdapter() {
            @Override
            public PresignedUploadGrant createUploadGrant(PresignedUploadGrantRequest ignored) {
                return grant;
            }

            @Override
            public PresignedUploadGrant reissueUploadGrant(PresignedUploadReissueRequest ignored) {
                return grant;
            }

            @Override
            public PresignedUploadFinalization finalizeUpload(PresignedUploadFinalizeRequest ignored) {
                return finalization;
            }

            @Override
            public void cleanupUpload(PresignedUploadCleanupRequest ignored) {
                // no-op fake
            }
        };

        assertEquals("md5", request.getChecksum().getAlgorithm());
        assertEquals("PUT", adapter.createUploadGrant(request).getHttpMethod());
        assertEquals(location, finalizeRequest.getLocation());
        assertEquals(location, reissueRequest.getLocation());
        assertEquals(location, cleanupRequest.getLocation());
        assertEquals(location, finalization.getSourceLocation());
        assertEquals(boundLocation, finalization.getStoredObject().getLocation());
        assertEquals("version-1", adapter.finalizeUpload(finalizeRequest).getRevision());
        assertNotNull(PresignedUploadStorageAdapter.class.getMethod(
            "finalizeUpload", PresignedUploadFinalizeRequest.class
        ));
        assertNotNull(PresignedUploadStorageAdapter.class.getMethod(
            "reissueUploadGrant", PresignedUploadReissueRequest.class
        ));
        assertNotNull(PresignedUploadStorageAdapter.class.getMethod(
            "cleanupUpload", PresignedUploadCleanupRequest.class
        ));
        assertThrows(UnsupportedOperationException.class, () -> grant.getRequiredHeaders().put("x", "y"));
    }
}
