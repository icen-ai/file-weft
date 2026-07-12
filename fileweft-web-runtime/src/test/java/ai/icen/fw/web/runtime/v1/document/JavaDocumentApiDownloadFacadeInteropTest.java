package ai.icen.fw.web.runtime.v1.document;

import ai.icen.fw.application.document.DocumentDownloadService;
import java.io.Closeable;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDocumentApiDownloadFacadeInteropTest {
    @Test
    void exposesOnlyTheSafeJava8DownloadSurface() throws Exception {
        DocumentApiDownloadFacade facade = JavaDocumentApiDownloadFixtures.facade();

        try (DocumentApiDownload current = facade.download("document-java")) {
            byte[] content = new byte[4];
            assertEquals(4, current.getContent().read(content));
            assertEquals("java", new String(content, StandardCharsets.UTF_8));
            assertEquals("application/pdf", current.getContentType());
            assertEquals(Long.valueOf(4L), current.getVerifiedContentLength());
            assertTrue(current.getContentDisposition().startsWith("attachment; filename=\"download.pdf\""));
        }
        try (DocumentApiDownload selected = facade.download("document-java", "version-java")) {
            assertNotNull(selected.getContent());
        }

        assertEquals(1, DocumentApiDownloadFacade.class.getConstructors().length);
        assertArrayEquals(
            new Class<?>[] {DocumentDownloadService.class},
            DocumentApiDownloadFacade.class.getConstructors()[0].getParameterTypes()
        );
        assertEquals(
            DocumentApiDownload.class,
            DocumentApiDownloadFacade.class.getMethod("download", String.class).getReturnType()
        );
        assertEquals(
            DocumentApiDownload.class,
            DocumentApiDownloadFacade.class.getMethod("download", String.class, String.class).getReturnType()
        );
        assertTrue(Closeable.class.isAssignableFrom(DocumentApiDownload.class));
        assertEquals(InputStream.class, DocumentApiDownload.class.getMethod("getContent").getReturnType());
        assertEquals(String.class, DocumentApiDownload.class.getMethod("getContentDisposition").getReturnType());
        assertEquals(String.class, DocumentApiDownload.class.getMethod("getContentType").getReturnType());
        assertEquals(Long.class, DocumentApiDownload.class.getMethod("getVerifiedContentLength").getReturnType());
        assertEquals(0, DocumentApiDownload.class.getConstructors().length);
        assertEquals(0, DocumentApiDownload.class.getDeclaredFields().length);

        Set<String> publicMethods = Arrays.stream(DocumentApiDownload.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()) && !method.isSynthetic())
            .map(Method::getName)
            .collect(Collectors.toSet());
        assertEquals(
            new HashSet<>(Arrays.asList(
                "getContent",
                "getContentDisposition",
                "getContentType",
                "getVerifiedContentLength",
                "close"
            )),
            publicMethods
        );
        assertFalse(publicMethods.contains("getDocumentId"));
        assertFalse(publicMethods.contains("getVersionId"));
        assertFalse(publicMethods.contains("getFileName"));
        assertFalse(publicMethods.contains("getContentLength"));
        assertFalse(publicMethods.contains("getExpectedContentLength"));
        assertFalse(publicMethods.contains("getStoragePath"));
        assertFalse(publicMethods.contains("getContentHash"));
        assertFalse(publicMethods.contains("getTenantId"));
        assertFalse(publicMethods.contains("getAssetId"));
    }
}
