package ai.icen.fw.web.runtime.v1.upload;

import ai.icen.fw.application.upload.CompletedPresignedUploadAssetClaimService;
import ai.icen.fw.application.upload.PresignedUploadService;
import ai.icen.fw.web.api.v1.upload.PresignedUploadDto;
import ai.icen.fw.web.api.v1.upload.PresignedUploadFinalizationDto;
import ai.icen.fw.web.api.v1.upload.PresignedUploadGrantDto;
import ai.icen.fw.web.api.v1.upload.StartPresignedUploadRequest;
import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPresignedUploadApiFacadeSurfaceTest {
    @Test
    void exposesOnlyConstructorsThatRequireTheAtomicCompletionCapability() {
        List<Constructor<?>> callable = Arrays.stream(PresignedUploadApiFacade.class.getConstructors())
            .filter(constructor -> !constructor.isSynthetic())
            .collect(Collectors.toList());

        assertEquals(2, callable.size());
        assertTrue(callable.stream().anyMatch(constructor -> Arrays.equals(
            constructor.getParameterTypes(),
            new Class<?>[]{PresignedUploadService.class, CompletedPresignedUploadAssetClaimService.class}
        )));
        assertTrue(callable.stream().anyMatch(constructor -> Arrays.equals(
            constructor.getParameterTypes(),
            new Class<?>[]{
                PresignedUploadService.class,
                CompletedPresignedUploadAssetClaimService.class,
                Duration.class
            }
        )));
        assertFalse(callable.stream().anyMatch(constructor ->
            Arrays.equals(constructor.getParameterTypes(), new Class<?>[]{PresignedUploadService.class})
        ));
    }

    @Test
    void exposesJavaFriendlyDirectUploadOperations() throws Exception {
        assertEquals(
            PresignedUploadGrantDto.class,
            PresignedUploadApiFacade.class.getMethod(
                "start",
                List.class,
                StartPresignedUploadRequest.class
            ).getReturnType()
        );
        assertEquals(
            PresignedUploadGrantDto.class,
            PresignedUploadApiFacade.class.getMethod("reissue", String.class).getReturnType()
        );
        assertEquals(
            PresignedUploadDto.class,
            PresignedUploadApiFacade.class.getMethod("inspect", String.class).getReturnType()
        );
        assertEquals(
            PresignedUploadDto.class,
            PresignedUploadApiFacade.class.getMethod("cancel", String.class).getReturnType()
        );
        assertEquals(
            PresignedUploadFinalizationDto.class,
            PresignedUploadApiFacade.class.getMethod(
                "finalizeUpload",
                String.class,
                List.class
            ).getReturnType()
        );
        assertEquals(
            "/flowweft/v1/presigned-uploads/upload-1",
            PresignedUploadApiLocations.inspect("upload-1").toString()
        );
        assertThrows(
            IllegalStateException.class,
            () -> PresignedUploadApiLocations.inspect("unsafe/path")
        );
    }
}
