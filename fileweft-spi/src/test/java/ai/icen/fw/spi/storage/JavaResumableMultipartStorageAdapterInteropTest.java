package ai.icen.fw.spi.storage;

import ai.icen.fw.core.id.Identifier;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaResumableMultipartStorageAdapterInteropTest {
    @Test
    void exposesAJavaFriendlyOptionalRecoveryCapability() {
        ResumableMultipartStorageAdapter adapter = upload ->
            Collections.singletonList(new MultipartPart(1, "etag-1"));
        MultipartUpload upload = new MultipartUpload(
            new Identifier("upload-1"),
            new StorageObjectLocation("s3", "objects/opaque")
        );

        List<MultipartPart> parts = adapter.listUploadedParts(upload);

        assertEquals(1, parts.size());
        assertEquals(1, parts.get(0).getPartNumber());
    }
}
