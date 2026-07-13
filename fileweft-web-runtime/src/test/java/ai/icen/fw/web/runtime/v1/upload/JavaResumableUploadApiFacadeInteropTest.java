package ai.icen.fw.web.runtime.v1.upload;

import ai.icen.fw.application.upload.ResumableUploadService;
import ai.icen.fw.web.api.v1.upload.ResumableUploadCompletionDto;
import ai.icen.fw.web.api.v1.upload.ResumableUploadDto;
import ai.icen.fw.web.api.v1.upload.ResumableUploadPartDto;
import ai.icen.fw.web.api.v1.upload.StartResumableUploadRequest;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaResumableUploadApiFacadeInteropTest {
    @Test
    void exposesFiveJavaFriendlyOperationsAndSafeLocationHelper() throws Exception {
        assertNotNull(ResumableUploadApiFacade.class.getConstructor(
            ResumableUploadService.class
        ));

        Method start = ResumableUploadApiFacade.class.getMethod(
            "start",
            List.class,
            StartResumableUploadRequest.class
        );
        Method inspect = ResumableUploadApiFacade.class.getMethod("inspect", String.class);
        Method uploadPart = ResumableUploadApiFacade.class.getMethod(
            "uploadPart",
            String.class,
            int.class,
            long.class,
            InputStream.class
        );
        Method complete = ResumableUploadApiFacade.class.getMethod("complete", String.class);
        Method abort = ResumableUploadApiFacade.class.getMethod("abort", String.class);

        assertEquals(ResumableUploadDto.class, start.getReturnType());
        assertEquals(ResumableUploadDto.class, inspect.getReturnType());
        assertEquals(ResumableUploadPartDto.class, uploadPart.getReturnType());
        assertEquals(ResumableUploadCompletionDto.class, complete.getReturnType());
        assertEquals(ResumableUploadDto.class, abort.getReturnType());
        assertEquals(
            "/fileweft/v1/uploads/upload-1",
            ResumableUploadApiLocations.inspect("upload-1").toString()
        );
        assertThrows(IllegalStateException.class, () -> ResumableUploadApiLocations.inspect("unsafe/path"));
    }
}
