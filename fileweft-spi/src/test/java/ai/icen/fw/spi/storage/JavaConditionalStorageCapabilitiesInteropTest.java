package ai.icen.fw.spi.storage;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaConditionalStorageCapabilitiesInteropTest {
    @Test
    void exposesMetadataAndRevisionBoundRangesWithoutVendorTypes() {
        StorageObjectLocation location = new StorageObjectLocation("oss", "objects/opaque");
        StorageMetadataAdapter metadataAdapter = value -> new StorageObjectMetadata(
            value,
            3L,
            "text/plain",
            "sha256:abc",
            "revision-1",
            Collections.singletonMap("classification", "public"),
            100L
        );
        ConditionalRangedStorageAdapter rangeAdapter = request -> new StorageDownload(
            new ByteArrayInputStream(new byte[] { 1, 2 }),
            2L,
            "application/octet-stream"
        );
        RangedStorageAdapter legacyRangeAdapter = (value, offset, length) -> new StorageDownload(
            new ByteArrayInputStream(new byte[] { 2 }),
            length,
            "application/octet-stream"
        );

        StorageObjectMetadata metadata = metadataAdapter.metadata(location);
        StorageDownload download = rangeAdapter.downloadRange(
            new StorageRangeRequest(location, 1L, 2L, metadata.getRevision())
        );

        assertEquals("revision-1", metadata.getRevision());
        assertEquals("public", metadata.getMetadata().get("classification"));
        assertEquals(2L, download.getContentLength());
        assertEquals(1L, legacyRangeAdapter.downloadRange(location, 2L, 1L).getContentLength());
    }
}
