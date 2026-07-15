package ai.icen.fw.application.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.spi.storage.StorageContentChecksum;
import java.lang.reflect.Method;
import java.time.Duration;
import java.net.URI;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class PresignedUploadApplicationJavaCompatibilityTest {
    @Test
    void exposesCommandsWithoutClientControlledStorageLocation() throws NoSuchMethodException {
        StartPresignedUploadCommand start = new StartPresignedUploadCommand(
            "contract.txt",
            7L,
            "text/plain",
            "sha256:239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5",
            new StorageContentChecksum("md5", "CY9rzUYh03PK3k6DJie09g=="),
            Collections.singletonMap("business", "legal"),
            Duration.ofMinutes(5)
        );
        CompletePresignedUploadCommand complete = new CompletePresignedUploadCommand(new Identifier("session-1"));
        CompletePresignedUploadAssetCommand claim = new CompletePresignedUploadAssetCommand(
            new Identifier("session-1"), "finalize-1"
        );
        CompletedPresignedUploadAssetClaimResult claimResult = new CompletedPresignedUploadAssetClaimResult(
            new Identifier("session-1"),
            new Identifier("file-object-1"),
            new Identifier("file-asset-1"),
            false
        );
        InspectPresignedUploadCommand inspect = new InspectPresignedUploadCommand(new Identifier("session-1"));
        ReissuePresignedUploadCommand reissue = new ReissuePresignedUploadCommand(new Identifier("session-1"));
        CancelPresignedUploadCommand cancel = new CancelPresignedUploadCommand(new Identifier("session-1"));
        PresignedUploadGrantResult grant = new PresignedUploadGrantResult(
            new Identifier("session-1"),
            URI.create("https://storage.example/object?signature=opaque"),
            Collections.singletonMap("Content-Type", "text/plain"),
            1_000L
        );

        assertEquals("contract.txt", start.getFileName());
        assertEquals("session-1", complete.getSessionId().getValue());
        assertEquals("session-1", claim.getUploadId().getValue());
        assertEquals("file-object-1", claimResult.getFileObjectId().getValue());
        assertEquals("session-1", inspect.getSessionId().getValue());
        assertEquals("session-1", reissue.getSessionId().getValue());
        assertEquals("session-1", cancel.getSessionId().getValue());
        assertEquals(true, grant.getCreated());
        assertNotNull(PresignedUploadService.class.getMethod(
            "startWithCallerKey", String.class, StartPresignedUploadCommand.class
        ));
        assertNotNull(PresignedUploadService.class.getMethod(
            "complete", CompletePresignedUploadCommand.class
        ));
        assertNotNull(PresignedUploadService.class.getMethod(
            "reissue", ReissuePresignedUploadCommand.class
        ));
        assertNotNull(PresignedUploadService.class.getMethod(
            "inspectForCompletion", CompletePresignedUploadCommand.class
        ));
        assertNotNull(PresignedUploadService.class.getMethod(
            "inspect", InspectPresignedUploadCommand.class
        ));
        assertNotNull(PresignedUploadService.class.getMethod(
            "cancel", CancelPresignedUploadCommand.class
        ));
        assertNotNull(CompletedPresignedUploadAssetClaimService.class.getMethod(
            "finalizeUpload", CompletePresignedUploadAssetCommand.class
        ));
        for (Method method : CompletePresignedUploadCommand.class.getMethods()) {
            assertFalse(method.getName().toLowerCase().contains("location"));
        }
        for (Method method : PresignedUploadGrantResult.class.getMethods()) {
            assertFalse(method.getName().equals("getLocation"));
            assertFalse(method.getName().equals("getStorageLocation"));
        }
        for (Method method : PresignedUploadStatusResult.class.getMethods()) {
            assertFalse(method.getName().toLowerCase().contains("location"));
            assertFalse(method.getName().toLowerCase().contains("header"));
            assertFalse(method.getName().toLowerCase().contains("revision"));
            assertFalse(method.getName().toLowerCase().contains("error"));
            assertFalse(method.getName().equals("getTenantId"));
            assertFalse(method.getName().equals("getOwnerId"));
        }
        for (Method method : CompletedPresignedUploadAssetClaimResult.class.getMethods()) {
            assertFalse(method.getName().toLowerCase().contains("location"));
            assertFalse(method.getName().toLowerCase().contains("revision"));
            assertFalse(method.getName().toLowerCase().contains("checksum"));
            assertFalse(method.getName().equals("getTenantId"));
            assertFalse(method.getName().equals("getOwnerId"));
        }
    }
}
